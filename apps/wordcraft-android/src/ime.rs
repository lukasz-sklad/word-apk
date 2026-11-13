//! Android JNI bridge queues and logcat adapter for `WordCraftActivity`.
//!
//! Receives soft-keyboard IME actions, hardware/navigation key events, safe-area window insets,
//! SAF file-picker answers, and `ACTION_VIEW` document intents from Kotlin on the Android UI
//! thread and wakes `eframe` on the `android_main` thread.

use std::ffi::CString;
use std::sync::{Mutex, Once};

use android_activity::AndroidAppWaker;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JObject, JString};
use jni::sys::jint;
use jni::{Env, EnvUnowned};
use wordcraft_ui_egui::FileDialogAnswer;

use crate::mobile::{AndroidInput, AndroidKey, MAX_IME_BYTES, SafeInsetsPx, truncate_utf8_bytes};

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
    inputs: Vec<AndroidInput>,
    insets: SafeInsetsPx,
    insets_changed: bool,
    ime_dismissed: bool,
    file_answer: Option<PickedAnswer>,
    pending_opens: Vec<PendingOpen>,
}

static QUEUES: Mutex<SharedQueues> = Mutex::new(SharedQueues {
    inputs: Vec::new(),
    insets: SafeInsetsPx { left: 0, top: 0, right: 0, bottom: 0, ime_bottom: 0 },
    insets_changed: false,
    ime_dismissed: false,
    file_answer: None,
    pending_opens: Vec::new(),
});

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
        q.inputs.push(item);
    }
    wake_ui();
}

/// Drains all queued Android IME / key inputs for the current frame.
pub fn drain_inputs() -> Vec<AndroidInput> {
    QUEUES.lock().map(|mut q| std::mem::take(&mut q.inputs)).unwrap_or_default()
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
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImePreedit<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    text: JString<'caller>,
    cursor_start: jint,
    cursor_end: jint,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let owned = read_jstring(env, &text)?;
            push_input(AndroidInput::Preedit { text: owned, cursor_start, cursor_end });
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeCommit<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _obj: JObject<'caller>,
    text: JString<'caller>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let owned = read_jstring(env, &text)?;
            push_input(AndroidInput::Commit(owned));
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_WordCraftActivity_nativeOnImeDeleteSurrounding(
    _env: EnvUnowned<'_>,
    _obj: JObject<'_>,
    before: jint,
    after: jint,
) {
    push_input(AndroidInput::DeleteSurrounding { before: before.max(0), after: after.max(0) });
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
                let saf_mappings: Vec<(String, String)> = paths
                    .iter()
                    .zip(uris_raw.lines())
                    .filter(|(_, u)| !u.is_empty())
                    .map(|(p, u)| (p.clone(), u.to_owned()))
                    .collect();
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
