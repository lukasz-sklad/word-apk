//! Android JNI bridge queues and logcat adapter for `WordCraftActivity`.
//!
//! Receives soft-keyboard IME actions, hardware/navigation key events, safe-area window insets,
//! SAF file-picker answers, and `ACTION_VIEW` document intents from Kotlin on the Android UI
//! thread and wakes `eframe` on the `android_main` thread.

use std::ffi::CString;
use std::sync::atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Once, OnceLock};
use std::time::{Duration, Instant};

use android_activity::AndroidAppWaker;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JObject, JString, JValue};
use jni::refs::Global;
use jni::sys::{jfloat, jint, jlong};
use jni::{Env, EnvUnowned, JavaVM, jni_sig, jni_str};
use wordcraft_engine::speech::PlatformSpoken;
use wordcraft_ui_egui::FileDialogAnswer;

use crate::mobile::{AndroidInput, AndroidKey, ImeFrameSlice, MAX_IME_BYTES, QueuedInput, SafeInsetsPx, take_ime_frame_slice, truncate_utf8_bytes};

const MAX_QUEUED_INPUTS: usize = 4096;
const MAX_QUEUED_OPENS: usize = 32;

/// Document picked through SAF along with optional staging-path -> `content://` URI pairs for writeback.
#[derive(Clone, Debug)]
pub struct PickedAnswer {
    pub answer: FileDialogAnswer,
    pub saf_mappings: Vec<(String, String)>,
}

/// Incoming file open request (e.g. from Android `ACTION_VIEW` intent).
#[derive(Clone, Debug)]
pub struct PendingOpen {
    pub staging_path: String,
    pub content_uri: String,
}

#[derive(Default)]
struct SharedQueues {
    inputs: Vec<QueuedInput>,
    staged_inputs: Vec<AndroidInput>,
    latest_seq: u64,
    acked_seq: u64,
    wheel_deltas: Vec<(f32, f32)>,
    mouse_hover_px: Option<(f32, f32)>,
    insets: SafeInsetsPx,
    insets_changed: bool,
    ime_dismissed: bool,
    file_answer: Option<PickedAnswer>,
    pending_opens: Vec<PendingOpen>,
}

static QUEUES: Mutex<SharedQueues> = Mutex::new(SharedQueues {
    inputs: Vec::new(),
    staged_inputs: Vec::new(),
    latest_seq: 0,
    acked_seq: 0,
    wheel_deltas: Vec::new(),
    mouse_hover_px: None,
    insets: SafeInsetsPx { left: 0, top: 0, right: 0, bottom: 0, ime_bottom: 0 },
    insets_changed: false,
    ime_dismissed: false,
    file_answer: None,
    pending_opens: Vec::new(),
});

/// Android `Configuration.UI_MODE_NIGHT_MASK` state (`0` = light, `1` = dark, `2` = unknown).
static SYSTEM_NIGHT_MODE: AtomicU8 = AtomicU8::new(2);
/// True when the latest pointer event came from a mouse/trackpad rather than finger touch.
static POINTER_IS_MOUSE: AtomicBool = AtomicBool::new(false);

static WAKER: Mutex<Option<AndroidAppWaker>> = Mutex::new(None);
static LOGGER_INIT: Once = Once::new();

#[allow(unsafe_code)]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
}

struct AndroidLogcatLogger;

impl log::Log for AndroidLogcatLogger {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        metadata.level() <= log::Level::Warn
    }

    #[allow(unsafe_code)]
    fn log(&self, record: &log::Record<'_>) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let prio = match record.level() {
            log::Level::Error => 6,
            log::Level::Warn => 5,
            log::Level::Info => 4,
            log::Level::Debug => 3,
            log::Level::Trace => 2,
        };
        let msg = format!("[{}] {}", record.target(), record.args());
        let capped = truncate_utf8_bytes(&msg, 2048).replace('\0', " ");
        if let Ok(c_msg) = CString::new(capped) {
            unsafe {
                let _ = __android_log_write(prio, c"WordCraft".as_ptr(), c_msg.as_ptr());
            }
        }
    }

    fn flush(&self) {}
}

static ANDROID_LOGGER: AndroidLogcatLogger = AndroidLogcatLogger;

/// Installs the Android logcat logger once for this process.
pub fn init_logger() {
    LOGGER_INIT.call_once(|| {
        if log::set_logger(&ANDROID_LOGGER).is_ok() {
            log::set_max_level(log::LevelFilter::Warn);
        }
    });
}

/// Registers the active `AndroidAppWaker` and resets transient event queues for a fresh Activity run.
pub fn bind_waker(waker: AndroidAppWaker) {
    if let Ok(mut guard) = WAKER.lock() {
        *guard = Some(waker);
    }
    if let Ok(mut q) = QUEUES.lock() {
        q.inputs.clear();
        q.staged_inputs.clear();
        q.latest_seq = 0;
        q.acked_seq = 0;
        q.wheel_deltas.clear();
        q.mouse_hover_px = None;
        q.ime_dismissed = false;
        q.file_answer = None;
        q.insets_changed = true;
    }
}

fn wake_ui() {
    if let Ok(guard) = WAKER.lock()
        && let Some(waker) = guard.as_ref()
    {
        waker.wake();
    }
}

fn push_input(item: AndroidInput) {
    if let Ok(mut q) = QUEUES.lock()
        && q.inputs.len() < MAX_QUEUED_INPUTS
    {
        let seq = q.latest_seq;
        q.inputs.push(QueuedInput { item, seq });
    }
    wake_ui();
}

fn push_staged_input(item: AndroidInput) {
    if let Ok(mut q) = QUEUES.lock()
        && q.staged_inputs.len() < MAX_QUEUED_INPUTS
    {
        q.staged_inputs.push(item);
    }
}

/// Drains one ordered slice of queued Android IME / key inputs for the current frame.
pub fn drain_ime_frame_slice() -> ImeFrameSlice {
    QUEUES
        .lock()
        .map(|mut q| {
            let current_acked = q.acked_seq;
            let slice = take_ime_frame_slice(&mut q.inputs, current_acked);
            q.acked_seq = slice.acked_seq;
            slice
        })
        .unwrap_or_default()
}

/// Drains queued Android mouse-wheel `(AXIS_HSCROLL, AXIS_VSCROLL)` deltas for the current frame.
pub fn drain_wheel_deltas() -> Vec<(f32, f32)> {
    QUEUES.lock().map(|mut q| std::mem::take(&mut q.wheel_deltas)).unwrap_or_default()
}

/// Returns the latest queued Android mouse-hover position in physical pixels, if any.
pub fn take_mouse_hover_px() -> Option<(f32, f32)> {
    QUEUES.lock().ok().and_then(|mut q| q.mouse_hover_px.take())
}

/// Returns Android's current system night mode (`Some(true)` = dark, `Some(false)` = light).
pub fn system_night_mode() -> Option<bool> {
    match SYSTEM_NIGHT_MODE.load(Ordering::Relaxed) {
        0 => Some(false),
        1 => Some(true),
        _ => None,
    }
}

/// Returns true when the most recent pointer/generic-motion event originated from a mouse device.
pub fn pointer_is_mouse() -> bool {
    POINTER_IS_MOUSE.load(Ordering::Relaxed)
}

/// Returns current physical-pixel safe-area insets and whether they updated since last check.
pub fn take_insets() -> (SafeInsetsPx, bool) {
    QUEUES
        .lock()
        .map(|mut q| {
            let changed = q.insets_changed;
            q.insets_changed = false;
            (q.insets, changed)
        })
        .unwrap_or_default()
}

/// Consumes any notification that Android dismissed the soft keyboard via Back or swipe.
pub fn take_ime_dismissed() -> bool {
    QUEUES.lock().map(|mut q| std::mem::take(&mut q.ime_dismissed)).unwrap_or(false)
}

/// Takes a completed SAF picker answer if one has arrived.
pub fn take_file_answer() -> Option<PickedAnswer> {
    QUEUES.lock().ok().and_then(|mut q| q.file_answer.take())
}

/// Drains incoming `ACTION_VIEW` document staging requests.
pub fn drain_pending_opens() -> Vec<PendingOpen> {
    QUEUES.lock().map(|mut q| std::mem::take(&mut q.pending_opens)).unwrap_or_default()
}

#[derive(Clone)]
struct TtsBridge {
    vm: JavaVM,
    activity: Arc<Global<JObject<'static>>>,
}

static TTS_BRIDGE: OnceLock<TtsBridge> = OnceLock::new();
static NEXT_UTTERANCE_ID: AtomicU64 = AtomicU64::new(1);

const TTS_STATUS_SPEAKING: i32 = 0;
const TTS_STATUS_DONE: i32 = 1;
const TTS_STATUS_STOPPED: i32 = 2;
const TTS_SPEAK_STARTED: i32 = 0;
const TTS_SPEAK_INITIALIZING: i32 = 1;

/// Registers the `JavaVM` and `WordCraftActivity` global reference used for Android `TextToSpeech`.
pub fn bind_tts_bridge(vm: JavaVM, activity: Arc<Global<JObject<'static>>>) {
    let _ = TTS_BRIDGE.set(TtsBridge { vm, activity });
}

fn jni_tts_speak(bridge: &TtsBridge, utterance_id: &str, text: &str, lang: &str, rate: f32) -> Result<i32, String> {
    bridge
        .vm
        .attach_current_thread(|env| -> jni::errors::Result<i32> {
            let id_j = env.new_string(utterance_id)?;
            let text_j = env.new_string(text)?;
            let lang_j = env.new_string(lang)?;
            let code = env
                .call_method(
                    &*bridge.activity,
                    jni_str!("ttsSpeak"),
                    jni_sig!("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;F)I"),
                    &[JValue::Object(&id_j), JValue::Object(&text_j), JValue::Object(&lang_j), JValue::Float(rate as jfloat)],
                )?
                .i()?;
            Ok(code)
        })
        .map_err(|e| format!("Android TTS JNI error: {e}"))
}

fn jni_tts_status(bridge: &TtsBridge, utterance_id: &str) -> Result<i32, String> {
    bridge
        .vm
        .attach_current_thread(|env| -> jni::errors::Result<i32> {
            let id_j = env.new_string(utterance_id)?;
            env.call_method(&*bridge.activity, jni_str!("ttsStatus"), jni_sig!("(Ljava/lang/String;)I"), &[JValue::Object(&id_j)])?.i()
        })
        .map_err(|e| format!("Android TTS status error: {e}"))
}

fn jni_tts_stop(bridge: &TtsBridge) {
    let _ = bridge.vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let _ = env.call_method(&*bridge.activity, jni_str!("ttsStop"), jni_sig!("()V"), &[])?;
        Ok(())
    });
}

fn jni_tts_last_error(bridge: &TtsBridge) -> String {
    bridge
        .vm
        .attach_current_thread(|env| -> jni::errors::Result<String> {
            let obj = env.call_method(&*bridge.activity, jni_str!("ttsLastError"), jni_sig!("()Ljava/lang/String;"), &[])?.l()?;
            if obj.is_null() {
                return Ok(String::new());
            }
            let s = env.cast_local::<JString<'_>>(obj)?;
            s.try_to_string(env)
        })
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "Android TextToSpeech unavailable".into())
}

fn start_tts_with_init_wait(
    bridge: &TtsBridge,
    id: &str,
    text: &str,
    lang: &str,
    rate: f32,
    changed: &dyn Fn() -> bool,
) -> Result<(), PlatformSpoken> {
    let deadline = Instant::now() + Duration::from_secs(4);
    loop {
        if changed() {
            return Err(PlatformSpoken::Interrupted);
        }
        match jni_tts_speak(bridge, id, text, lang, rate) {
            Ok(TTS_SPEAK_STARTED) => return Ok(()),
            Ok(TTS_SPEAK_INITIALIZING) if Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(25));
            }
            Ok(TTS_SPEAK_INITIALIZING) => {
                return Err(PlatformSpoken::Failed("Android TextToSpeech initialization timed out".into()));
            }
            Ok(_) => return Err(PlatformSpoken::Failed(jni_tts_last_error(bridge))),
            Err(e) => return Err(PlatformSpoken::Failed(e)),
        }
    }
}

/// Speaks one utterance through Android's `TextToSpeech` (Google TTS on Pixel devices).
pub fn speak_tts(text: &str, lang: Option<&str>, rate: f32, changed: &dyn Fn() -> bool) -> PlatformSpoken {
    let Some(bridge) = TTS_BRIDGE.get() else {
        return PlatformSpoken::Failed("Android TTS bridge not initialized".into());
    };
    let id = format!("wc_{}", NEXT_UTTERANCE_ID.fetch_add(1, Ordering::Relaxed));
    let clean_rate = if rate.is_finite() { rate.clamp(0.5, 3.0) } else { 1.0 };
    if let Err(early) = start_tts_with_init_wait(bridge, &id, text, lang.unwrap_or(""), clean_rate, changed) {
        if matches!(early, PlatformSpoken::Interrupted) {
            jni_tts_stop(bridge);
        }
        return early;
    }

    let deadline = Instant::now() + Duration::from_secs(120);
    loop {
        if changed() {
            jni_tts_stop(bridge);
            return PlatformSpoken::Interrupted;
        }
        match jni_tts_status(bridge, &id) {
            Ok(TTS_STATUS_SPEAKING) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(25)),
            Ok(TTS_STATUS_SPEAKING) => {
                jni_tts_stop(bridge);
                return PlatformSpoken::Failed("Android TextToSpeech utterance timed out".into());
            }
            Ok(TTS_STATUS_DONE) => return PlatformSpoken::Done,
            Ok(TTS_STATUS_STOPPED) => return PlatformSpoken::Interrupted,
            Ok(_) => return PlatformSpoken::Failed(jni_tts_last_error(bridge)),
            Err(e) => return PlatformSpoken::Failed(e),
        }
    }
}

fn read_jstring(env: &mut Env<'_>, s: &JString<'_>) -> jni::errors::Result<String> {
    let raw = s.try_to_string(env)?;
    Ok(truncate_utf8_bytes(&raw, MAX_IME_BYTES).to_owned())
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnInsets(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    left: jint,
    top: jint,
    right: jint,
    bottom: jint,
    ime_bottom: jint,
) {
    if let Ok(mut q) = QUEUES.lock() {
        q.insets = SafeInsetsPx { left: left.max(0), top: top.max(0), right: right.max(0), bottom: bottom.max(0), ime_bottom: ime_bottom.max(0) };
        q.insets_changed = true;
    }
    wake_ui();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeBatchStart(_env: EnvUnowned<'_>, _obj: JObject<'_>) {
    if let Ok(mut q) = QUEUES.lock() {
        q.staged_inputs.clear();
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeStagePreedit<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    text: JString<'caller>,
    cursor_start: jint,
    cursor_end: jint,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let owned = read_jstring(env, &text)?;
            push_staged_input(AndroidInput::Preedit { text: owned, cursor_start, cursor_end });
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeStageCommit<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    text: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let owned = read_jstring(env, &text)?;
            push_staged_input(AndroidInput::Commit(owned));
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeStageDeleteSurrounding(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    before: jint,
    after: jint,
) {
    push_staged_input(AndroidInput::DeleteSurrounding { before: before.max(0), after: after.max(0) });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeStageSetSelectionRelative(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    delta_anchor_cp: jint,
    delta_focus_cp: jint,
) {
    push_staged_input(AndroidInput::SetSelectionRelative { delta_anchor_cp, delta_focus_cp });
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeStageKey(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    keycode: jint,
    pressed: jint,
) {
    if let Some(key) = AndroidKey::from_keycode(keycode) {
        push_staged_input(AndroidInput::Key { key, pressed: pressed != 0 });
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeBatchCommit(_env: EnvUnowned<'_>, _obj: JObject<'_>, seq: jlong) {
    let seq_u64 = u64::try_from(seq.max(0)).unwrap_or(0);
    if let Ok(mut q) = QUEUES.lock() {
        let staged = std::mem::take(&mut q.staged_inputs);
        for item in staged {
            if q.inputs.len() >= MAX_QUEUED_INPUTS {
                break;
            }
            q.inputs.push(QueuedInput { item, seq: seq_u64 });
        }
        q.latest_seq = q.latest_seq.max(seq_u64);
    }
    wake_ui();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeKey(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    keycode: jint,
    pressed: jint,
) {
    if let Some(key) = AndroidKey::from_keycode(keycode) {
        push_input(AndroidInput::Key { key, pressed: pressed != 0 });
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeHidden(_env: EnvUnowned<'_>, _obj: JObject<'_>) {
    if let Ok(mut q) = QUEUES.lock() {
        q.ime_dismissed = true;
    }
    wake_ui();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnFilePickerResult<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    paths_joined: JString<'caller>,
    uris_joined: JString<'caller>,
    error_msg: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let err = error_msg.try_to_string(env)?;
            let parsed = if !err.is_empty() {
                PickedAnswer { answer: Err(err), saf_mappings: Vec::new() }
            } else {
                let paths_raw = paths_joined.try_to_string(env)?;
                let uris_raw = uris_joined.try_to_string(env)?;
                let paths: Vec<String> = paths_raw.lines().filter(|l| !l.is_empty()).map(str::to_owned).collect();
                let saf_mappings: Vec<(String, String)> =
                    paths.iter().zip(uris_raw.lines()).filter(|(_, u)| !u.is_empty()).map(|(p, u)| (p.clone(), u.to_owned())).collect();
                PickedAnswer { answer: Ok(paths), saf_mappings }
            };
            if let Ok(mut q) = QUEUES.lock() {
                q.file_answer = Some(parsed);
            }
            wake_ui();
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnOpenFilePath<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    staging_path: JString<'caller>,
    content_uri: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let path = staging_path.try_to_string(env)?;
            let uri = content_uri.try_to_string(env)?;
            if !path.is_empty()
                && let Ok(mut q) = QUEUES.lock()
                && q.pending_opens.len() < MAX_QUEUED_OPENS
            {
                q.pending_opens.push(PendingOpen { staging_path: path, content_uri: uri });
            }
            wake_ui();
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnSystemNightMode(_env: EnvUnowned<'_>, _obj: JObject<'_>, night: jint) {
    let encoded = match night {
        0 => 0,
        1 => 1,
        _ => 2,
    };
    SYSTEM_NIGHT_MODE.store(encoded, Ordering::Relaxed);
    wake_ui();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnPointerIsMouse(_env: EnvUnowned<'_>, _obj: JObject<'_>, is_mouse: jint) {
    POINTER_IS_MOUSE.store(is_mouse != 0, Ordering::Relaxed);
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnMouseWheel(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    h_scroll: jfloat,
    v_scroll: jfloat,
) {
    if !h_scroll.is_finite() || !v_scroll.is_finite() {
        return;
    }
    if let Ok(mut q) = QUEUES.lock()
        && q.wheel_deltas.len() < MAX_QUEUED_INPUTS
    {
        q.wheel_deltas.push((h_scroll, v_scroll));
    }
    wake_ui();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnMouseHover(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    x_px: jfloat,
    y_px: jfloat,
) {
    if !x_px.is_finite() || !y_px.is_finite() {
        return;
    }
    if let Ok(mut q) = QUEUES.lock() {
        q.mouse_hover_px = Some((x_px, y_px));
    }
    wake_ui();
}
