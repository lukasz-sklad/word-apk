//! Native Android shell built on `eframe` + `wgpu` + `android-activity` (`GameActivity`).
//!
//! Renders `WordApp` directly at native GPU speed while protecting Pixel camera cutouts / system
//! bars / soft-keyboard area, bridging Android soft-keyboard composing input, handling Android SAF
//! Open/Save/Export URIs, and offering a touch-first toolbar and gesture layer.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use android_activity::AndroidApp;
use eframe::NativeOptions;
use eframe::egui_wgpu::WgpuSetup;
use eframe::wgpu::{Backends, DeviceDescriptor};
use egui::{Color32, OutputCommand, RichText, Ui};
use jni::objects::{JObject, JString, JValue};
use jni::refs::Global;
use jni::sys::jint;
use jni::{JavaVM, jni_sig, jni_str};
use serde_json::json;
use wordcraft_doc::Document;
use wordcraft_engine::Session;
use wordcraft_layout::ViewMode;
use wordcraft_ui_egui::{FileDialogAnswer, FileDialogRequest, Services, UiState, WordApp, theme};

use crate::ime;
use crate::mobile::{
    self, AndroidThemeMode, SafeInsetsDp, TouchGestureFilter, apply_initial_mobile_defaults, prefs_write_atomic,
    to_egui_events, utf8_byte_to_utf16_offset,
};

#[derive(Clone, Debug, PartialEq, Eq)]
struct FileStamp {
    len: u64,
    modified: Option<SystemTime>,
}

impl FileStamp {
    fn of(path: &Path) -> Option<Self> {
        let meta = std::fs::metadata(path).ok()?;
        Some(Self { len: meta.len(), modified: meta.modified().ok() })
    }
}

#[derive(Clone)]
struct ActivityBridge {
    vm: JavaVM,
    activity: Arc<Global<JObject<'static>>>,
}

impl ActivityBridge {
    #[allow(unsafe_code)]
    fn from_app(app: &AndroidApp) -> Result<Self, String> {
        let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
        let global = vm
            .attach_current_thread(|env| -> jni::errors::Result<Global<JObject<'static>>> {
                let raw = app.activity_as_ptr();
                if raw.is_null() {
                    return Err(jni::errors::Error::NullPtr("null activity pointer"));
                }
                let borrowed = std::mem::ManuallyDrop::new(unsafe { JObject::from_raw(env, raw.cast()) });
                env.new_global_ref(&*borrowed)
            })
            .map_err(|e| format!("JNI bridge init failed: {e}"))?;
        Ok(Self { vm, activity: Arc::new(global) })
    }

    fn call_void_int(&self, method: &jni::strings::JNIStr, arg: i32) {
        let _ = self.vm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let _ = env.call_method(&*self.activity, method, jni_sig!("(I)V"), &[JValue::Int(arg as jint)])?;
            Ok(())
        });
    }

    fn set_ime_wanted(&self, wanted: bool) {
        self.call_void_int(jni_str!("setImeWanted"), i32::from(wanted));
    }

    fn set_light_system_bars(&self, light: bool) {
        self.call_void_int(jni_str!("setLightSystemBars"), i32::from(light));
    }

    fn update_ime_selection(&self, surrounding: &str, sel_start: usize, sel_end: usize) {
        let _ = self.vm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let text_j = env.new_string(surrounding)?;
            let _ = env.call_method(
                &*self.activity,
                jni_str!("updateImeSurrounding"),
                jni_sig!("(Ljava/lang/String;II)V"),
                &[JValue::Object(&text_j), JValue::Int(sel_start as jint), JValue::Int(sel_end as jint)],
            )?;
            Ok(())
        });
    }

    fn launch_open_picker(&self, purpose: &str, multiple: bool) -> Result<(), String> {
        self.vm
            .attach_current_thread(|env| -> jni::errors::Result<()> {
                let p_j = env.new_string(purpose)?;
                let _ = env.call_method(
                    &*self.activity,
                    jni_str!("launchOpenPicker"),
                    jni_sig!("(Ljava/lang/String;I)V"),
                    &[JValue::Object(&p_j), JValue::Int(i32::from(multiple))],
                )?;
                Ok(())
            })
            .map_err(|e| format!("cannot open Android picker: {e}"))
    }

    fn launch_save_picker(&self, suggested_name: &str) -> Result<(), String> {
        self.vm
            .attach_current_thread(|env| -> jni::errors::Result<()> {
                let n_j = env.new_string(suggested_name)?;
                let _ = env.call_method(
                    &*self.activity,
                    jni_str!("launchSavePicker"),
                    jni_sig!("(Ljava/lang/String;)V"),
                    &[JValue::Object(&n_j)],
                )?;
                Ok(())
            })
            .map_err(|e| format!("cannot open Android save picker: {e}"))
    }

    fn flush_staging_to_uri(&self, staging_path: &Path, content_uri: &str) -> Result<(), String> {
        let path_str = staging_path.to_string_lossy();
        let res = self
            .vm
            .attach_current_thread(|env| -> jni::errors::Result<String> {
                let p_j = env.new_string(&*path_str)?;
                let u_j = env.new_string(content_uri)?;
                let obj = env
                    .call_method(
                        &*self.activity,
                        jni_str!("flushStagingToUri"),
                        jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                        &[JValue::Object(&p_j), JValue::Object(&u_j)],
                    )?
                    .l()?;
                if obj.is_null() {
                    return Ok(String::new());
                }
                let s: JString<'_> = env.cast_local(obj)?;
                s.try_to_string(env)
            })
            .map_err(|e| format!("SAF writeback JNI error: {e}"))?;
        if res.is_empty() { Ok(()) } else { Err(res) }
    }

    fn get_clipboard_text(&self) -> Option<String> {
        self.vm
            .attach_current_thread(|env| -> jni::errors::Result<Option<String>> {
                let obj = env.call_method(&*self.activity, jni_str!("getClipboardText"), jni_sig!("()Ljava/lang/String;"), &[])?.l()?;
                if obj.is_null() {
                    return Ok(None);
                }
                let s: JString<'_> = env.cast_local(obj)?;
                let text = s.try_to_string(env)?;
                Ok(if text.is_empty() { None } else { Some(text) })
            })
            .ok()
            .flatten()
    }

    fn set_clipboard_text(&self, text: &str) -> bool {
        if text.is_empty() {
            return false;
        }
        self.vm
            .attach_current_thread(|env| -> jni::errors::Result<()> {
                let s_j = env.new_string(text)?;
                let _ = env.call_method(&*self.activity, jni_str!("setClipboardText"), jni_sig!("(Ljava/lang/String;)V"), &[JValue::Object(&s_j)])?;
                Ok(())
            })
            .is_ok()
    }
}

#[derive(Default)]
struct SafState {
    pending_tx: Option<Sender<FileDialogAnswer>>,
    targets: HashMap<PathBuf, (String, Option<FileStamp>)>,
}

impl SafState {
    fn register_mapping(&mut self, staging: PathBuf, uri: String) {
        let stamp = FileStamp::of(&staging);
        self.targets.insert(staging, (uri, stamp));
    }

    fn deliver_picker_answer(&mut self, answer: ime::PickedAnswer) {
        for (path, uri) in answer.saf_mappings {
            let p = PathBuf::from(path);
            let stamp = FileStamp::of(&p);
            self.targets.insert(p, (uri, stamp));
        }
        if let Some(tx) = self.pending_tx.take() {
            let _ = tx.send(answer.answer);
        }
    }

    fn flush_changed_outputs(&mut self, bridge: &ActivityBridge) -> Vec<String> {
        let mut messages = Vec::new();
        for (path, (uri, last_stamp)) in &mut self.targets {
            let Some(now_stamp) = FileStamp::of(path) else {
                continue;
            };
            if now_stamp.len == 0 || last_stamp.as_ref() == Some(&now_stamp) {
                continue;
            }
            match bridge.flush_staging_to_uri(path, uri) {
                Ok(()) => *last_stamp = Some(now_stamp),
                Err(e) => messages.push(e),
            }
        }
        messages
    }
}

struct AndroidShell {
    app: WordApp,
    bridge: ActivityBridge,
    files_dir: PathBuf,
    saf: Arc<Mutex<SafState>>,
    touch_filter: TouchGestureFilter,
    select_mode: bool,
    ime_wanted: bool,
    ime_requested_at: f64,
    prev_wants_kb: bool,
    prev_ime_bottom_dp: f32,
    insets: SafeInsetsDp,
    theme_mode: AndroidThemeMode,
    last_synced_bars_light: Option<bool>,
    last_ime_snapshot: Option<(String, usize, usize)>,
    last_saved_prefs_json: String,
}

impl AndroidShell {
    fn new(cc: &eframe::CreationContext<'_>, bridge: ActivityBridge, files_dir: PathBuf) -> Self {
        let saf = Arc::new(Mutex::new(SafState::default()));
        let services = build_services(&bridge, &saf);
        let mut word_app = WordApp::new(Session::new(Document::new()), services);

        let prefs_path = files_dir.join("ui.json");
        let saved_bytes = std::fs::read(&prefs_path).ok();
        let had_saved_prefs = saved_bytes.as_deref().and_then(UiState::from_json).map(|ui| word_app.apply_prefs(ui)).is_some();

        let theme_mode = load_theme_mode(&files_dir);
        theme_mode.apply(&mut word_app);
        apply_initial_mobile_defaults(&mut word_app, had_saved_prefs);

        let initial_prefs = serde_json::to_string(&word_app.prefs()).unwrap_or_default();
        word_app.logic(&cc.egui_ctx);

        Self {
            app: word_app,
            bridge,
            files_dir,
            saf,
            touch_filter: TouchGestureFilter::default(),
            select_mode: false,
            ime_wanted: false,
            ime_requested_at: -10.0,
            prev_wants_kb: false,
            prev_ime_bottom_dp: 0.0,
            insets: SafeInsetsDp::default(),
            theme_mode,
            last_synced_bars_light: None,
            last_ime_snapshot: None,
            last_saved_prefs_json: initial_prefs,
        }
    }

    fn set_ime_wanted(&mut self, wanted: bool, now: f64) {
        if self.ime_wanted != wanted || wanted {
            self.ime_wanted = wanted;
            if wanted {
                self.ime_requested_at = now;
            }
            self.bridge.set_ime_wanted(wanted);
        }
    }

    fn persist_prefs_if_changed(&mut self) {
        if let Ok(json) = serde_json::to_string(&self.app.prefs())
            && json != self.last_saved_prefs_json
        {
            if let Err(e) = prefs_write_atomic(&self.files_dir.join("ui.json"), json.as_bytes()) {
                log::warn!("failed saving Android ui.json: {e}");
            }
            self.last_saved_prefs_json = json;
        }
    }

    fn sync_ime_surrounding_to_java(&mut self) {
        if !self.app.canvas.focused {
            return;
        }
        let Some(window) = self.app.session.doc.ime_surrounding(&self.app.session.sel.focus, &self.app.session.sel.anchor, 512) else {
            return;
        };
        let s_u16 = utf8_byte_to_utf16_offset(&window.text, window.anchor);
        let e_u16 = utf8_byte_to_utf16_offset(&window.text, window.cursor);
        let (sel_start, sel_end) = (s_u16.min(e_u16), s_u16.max(e_u16));
        let changed = self
            .last_ime_snapshot
            .as_ref()
            .is_none_or(|(t, s, e)| t != &window.text || *s != sel_start || *e != sel_end);
        if changed {
            self.bridge.update_ime_selection(&window.text, sel_start, sel_end);
            self.last_ime_snapshot = Some((window.text, sel_start, sel_end));
        }
    }

    fn handle_incoming_opens_and_answers(&mut self) {
        if let Some(answer) = ime::take_file_answer()
            && let Ok(mut saf) = self.saf.lock()
        {
            saf.deliver_picker_answer(answer);
        }
        for req in ime::drain_pending_opens() {
            let path = PathBuf::from(&req.staging_path);
            if !req.content_uri.is_empty()
                && let Ok(mut saf) = self.saf.lock()
            {
                saf.register_mapping(path.clone(), req.content_uri);
            }
            if let Err(e) = self.app.open_from_platform(&req.staging_path, None) {
                self.app.status(format!("Open failed: {e}"));
            } else {
                apply_initial_mobile_defaults(&mut self.app, true);
            }
        }
    }

    fn flush_saf_and_clipboard(&mut self, ctx: &egui::Context) {
        if let Ok(mut saf) = self.saf.lock() {
            for err in saf.flush_changed_outputs(&self.bridge) {
                self.app.status(err);
            }
        }
        let copied: Vec<String> = ctx.output(|o| {
            o.commands
                .iter()
                .filter_map(|cmd| match cmd {
                    OutputCommand::CopyText(text) => Some(text.clone()),
                    _ => None,
                })
                .collect()
        });
        for text in copied {
            let _ = self.bridge.set_clipboard_text(&text);
        }
    }

    fn sync_insets_and_bars(&mut self, ctx: &egui::Context) {
        let (px, changed) = ime::take_insets();
        let next = px.into_dp(ctx.pixels_per_point());
        let now = ctx.input(|i| i.time);
        if changed && next.ime_bottom > self.prev_ime_bottom_dp + 4.0 && self.app.canvas.focused {
            self.app.canvas.scroll_to_caret = true;
            ctx.request_repaint();
        }
        let past_grace = (now - self.ime_requested_at) > 0.6;
        if (ime::take_ime_dismissed() || (!next.ime_visible && self.prev_ime_bottom_dp > 4.0)) && self.ime_wanted && past_grace {
            self.ime_wanted = false;
            self.bridge.set_ime_wanted(false);
        }
        self.prev_ime_bottom_dp = next.ime_bottom;
        self.insets = next;

        let light_bars = !theme::Tokens::get(ctx).dark;
        if self.last_synced_bars_light != Some(light_bars) {
            self.bridge.set_light_system_bars(light_bars);
            self.last_synced_bars_light = Some(light_bars);
        }
    }

    fn modal_blocking_canvas(&self, ctx: &egui::Context) -> bool {
        self.app.dialog.is_some()
            || self.app.colors.is_some()
            || self.app.file_dialog_open()
            || self.app.ui.backstage
            || ctx.any_popup_open()
    }

    fn draw_safe_insets(&self, ui: &mut Ui) {
        let t = theme::Tokens::get(ui.ctx());
        let edge_frame = egui::Frame::NONE.fill(t.panel);
        if self.insets.top > 0.5 {
            egui::Panel::top("android_safe_top").exact_size(self.insets.top).frame(egui::Frame::NONE.fill(t.title_bar)).show_inside(ui, |_| {});
        }
        if self.insets.bottom > 0.5 {
            egui::Panel::bottom("android_safe_bottom")
                .exact_size(self.insets.bottom)
                .frame(egui::Frame::NONE.fill(t.status_bar))
                .show_inside(ui, |_| {});
        }
        if self.insets.left > 0.5 {
            egui::Panel::left("android_safe_left").exact_size(self.insets.left).frame(edge_frame).show_inside(ui, |_| {});
        }
        if self.insets.right > 0.5 {
            egui::Panel::right("android_safe_right").exact_size(self.insets.right).frame(edge_frame).show_inside(ui, |_| {});
        }
    }

    fn draw_mobile_toolbar(&mut self, ui: &mut Ui) {
        let t = theme::Tokens::get(ui.ctx());
        let frame = egui::Frame::NONE.fill(t.title_bar).inner_margin(egui::Margin::symmetric(6, 4));
        egui::Panel::top("android_mobile_bar").exact_size(40.0).frame(frame).show_inside(ui, |ui| {
            egui::ScrollArea::horizontal().auto_shrink([false, false]).show(ui, |ui| {
                ui.horizontal_centered(|ui| {
                    ui.spacing_mut().item_spacing.x = 6.0;
                    self.draw_toolbar_buttons(ui);
                });
            });
        });
    }

    fn draw_toolbar_buttons(&mut self, ui: &mut Ui) {
        if toolbar_pill(ui, "Open", false).clicked() {
            let _ = self.app.run("ui.openFileDialog", json!({}));
        }
        if toolbar_pill(ui, "New", false).clicked() {
            let _ = self.app.run("file.new", json!({}));
            apply_initial_mobile_defaults(&mut self.app, true);
        }
        if toolbar_pill(ui, "Save", self.app.session.dirty).clicked() {
            let cmd = if self.app.session.path.is_some() { "file.save" } else { "file.saveAs" };
            let _ = self.app.run(cmd, json!({}));
        }
        if ui.add_enabled(self.app.session.can_undo(), egui::Button::new("Undo")).clicked() {
            let _ = self.app.execute("edit.undo", json!({}));
        }
        if ui.add_enabled(self.app.session.can_redo(), egui::Button::new("Redo")).clicked() {
            let _ = self.app.execute("edit.redo", json!({}));
        }
        if toolbar_pill(ui, "B", false).clicked() {
            let _ = self.app.execute("format.bold", json!({}));
        }
        if toolbar_pill(ui, "I", false).clicked() {
            let _ = self.app.execute("format.italic", json!({}));
        }
        if toolbar_pill(ui, "Sel", self.select_mode).clicked() {
            self.select_mode = !self.select_mode;
        }
        let kb_active = self.ime_wanted || self.insets.ime_visible;
        if toolbar_pill(ui, "KB", kb_active).clicked() {
            let now = ui.ctx().input(|i| i.time);
            self.set_ime_wanted(!kb_active, now);
            if !kb_active {
                self.app.canvas.scroll_to_caret = true;
            }
        }
        self.draw_view_buttons(ui);
    }

    fn draw_view_buttons(&mut self, ui: &mut Ui) {
        let is_web = self.app.session.view.mode == ViewMode::Web;
        let layout_label = if is_web { "Web" } else { "Page" };
        if toolbar_pill(ui, layout_label, is_web).clicked() {
            let cmd = if is_web { "view.printLayout" } else { "view.webLayout" };
            let _ = self.app.execute(cmd, json!({}));
        }
        let pct = (self.app.session.view.zoom * 100.0).round();
        if toolbar_pill(ui, "-", false).clicked() {
            let next = (pct - 15.0).clamp(25.0, 400.0);
            let _ = self.app.execute("view.zoom", json!({ "value": next }));
        }
        let zoom_text = format!("{pct:.0}%");
        if toolbar_pill(ui, &zoom_text, false).clicked() {
            let _ = self.app.execute("view.zoom", json!({ "value": mobile::MOBILE_DEFAULT_ZOOM_PCT }));
        }
        if toolbar_pill(ui, "+", false).clicked() {
            let next = (pct + 15.0).clamp(25.0, 400.0);
            let _ = self.app.execute("view.zoom", json!({ "value": next }));
        }
        if toolbar_pill(ui, self.theme_mode.short_label(), self.theme_mode != AndroidThemeMode::System).clicked() {
            self.theme_mode = self.theme_mode.cycle_next();
            self.theme_mode.apply(&mut self.app);
            save_theme_mode(&self.files_dir, self.theme_mode);
        }
        if toolbar_pill(ui, "Ribbon", !self.app.ui.ribbon_collapsed).clicked() {
            self.app.ui.ribbon_collapsed = !self.app.ui.ribbon_collapsed;
        }
    }
}

impl eframe::App for AndroidShell {
    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.handle_incoming_opens_and_answers();
        self.app.logic(ctx);
        self.sync_insets_and_bars(ctx);
        self.flush_saf_and_clipboard(ctx);
        self.persist_prefs_if_changed();
    }

    fn raw_input_hook(&mut self, ctx: &egui::Context, raw: &mut egui::RawInput) {
        let ime_events = to_egui_events(&ime::drain_inputs());
        raw.events.extend(ime_events);

        let modal = self.modal_blocking_canvas(ctx);
        let outcome = self.touch_filter.process(&mut raw.events, self.app.canvas.viewport, self.select_mode, modal);
        if outcome.tapped_canvas {
            let now = ctx.input(|i| i.time);
            self.set_ime_wanted(true, now);
            self.app.canvas.scroll_to_caret = true;
        }
        self.app.raw_input_hook(ctx, raw);
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.draw_safe_insets(ui);
        self.draw_mobile_toolbar(ui);
        self.app.ui(ui);

        let wants_dialog_kb = ctx.wants_keyboard_input() && !self.app.canvas.focused;
        if wants_dialog_kb && !self.prev_wants_kb {
            let now = ctx.input(|i| i.time);
            self.set_ime_wanted(true, now);
        }
        self.prev_wants_kb = wants_dialog_kb;
        self.sync_ime_surrounding_to_java();
    }

    fn on_exit(&mut self) {
        self.app.session.finish_recovery();
        if let Ok(mut saf) = self.saf.lock() {
            let _ = saf.flush_changed_outputs(&self.bridge);
        }
        self.persist_prefs_if_changed();
    }
}

fn toolbar_pill(ui: &mut Ui, label: &str, active: bool) -> egui::Response {
    let t = theme::Tokens::get(ui.ctx());
    let fill = if active { t.checked } else { Color32::TRANSPARENT };
    let text_col = if active { t.text } else { t.text_dim };
    let btn = egui::Button::new(RichText::new(label).size(13.0).color(text_col)).fill(fill).min_size(egui::vec2(28.0, 28.0));
    ui.add(btn)
}

fn build_services(bridge: &ActivityBridge, saf: &Arc<Mutex<SafState>>) -> Services {
    let picker_bridge = bridge.clone();
    let picker_saf = Arc::clone(saf);
    let clip_get_bridge = bridge.clone();
    let clip_set_bridge = bridge.clone();
    Services {
        file_dialog: Some(Box::new(move |req| {
            let (tx, rx) = std::sync::mpsc::channel();
            if let Ok(mut guard) = picker_saf.lock() {
                guard.pending_tx = Some(tx);
            }
            let launch = match req {
                FileDialogRequest::Open { purpose } => picker_bridge.launch_open_picker(&purpose, false),
                FileDialogRequest::OpenMany { purpose } => picker_bridge.launch_open_picker(&purpose, true),
                FileDialogRequest::Save { name, .. } => picker_bridge.launch_save_picker(&name),
            };
            if let Err(err) = launch
                && let Ok(mut guard) = picker_saf.lock()
                && let Some(tx) = guard.pending_tx.take()
            {
                let _ = tx.send(Err(err));
            }
            rx
        })),
        clipboard_text: Some(Box::new(move || clip_get_bridge.get_clipboard_text())),
        set_clipboard_rich: Some(Box::new(move |text, _, _, _| clip_set_bridge.set_clipboard_text(text))),
        ..Default::default()
    }
}

fn load_theme_mode(files_dir: &Path) -> AndroidThemeMode {
    let raw = std::fs::read_to_string(files_dir.join("android_theme.txt")).ok();
    match raw.as_deref().map(str::trim) {
        Some("light") => AndroidThemeMode::Light,
        Some("sepia") => AndroidThemeMode::Sepia,
        Some("dark") => AndroidThemeMode::Dark,
        _ => AndroidThemeMode::System,
    }
}

fn save_theme_mode(files_dir: &Path, mode: AndroidThemeMode) {
    let val = match mode {
        AndroidThemeMode::System => "system",
        AndroidThemeMode::Light => "light",
        AndroidThemeMode::Sepia => "sepia",
        AndroidThemeMode::Dark => "dark",
    };
    let _ = prefs_write_atomic(&files_dir.join("android_theme.txt"), val.as_bytes());
}

fn configure_mobile_wgpu(options: &mut NativeOptions) {
    if let WgpuSetup::CreateNew(create) = &mut options.wgpu_options.wgpu_setup {
        create.instance_descriptor.backends = Backends::VULKAN | Backends::GL;
        create.device_descriptor =
            Arc::new(|adapter| DeviceDescriptor { label: Some("WordCraft Android"), required_limits: adapter.limits(), ..Default::default() });
    }
}

/// Native Android entrypoint invoked by `android-activity`'s `GameActivity` glue.
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "Rust" fn android_main(app: AndroidApp) {
    ime::init_logger();
    ime::bind_waker(app.create_waker());

    let bridge = match ActivityBridge::from_app(&app) {
        Ok(b) => b,
        Err(e) => {
            log::error!("{e}");
            return;
        }
    };
    let files_dir = app.internal_data_path().unwrap_or_else(|| PathBuf::from("/data/local/tmp/wordcraft"));
    let mut options = NativeOptions { android_app: Some(app), ..Default::default() };
    configure_mobile_wgpu(&mut options);

    if let Err(e) = eframe::run_native("WordCraft", options, Box::new(move |cc| Ok(Box::new(AndroidShell::new(cc, bridge, files_dir))))) {
        log::error!("eframe exited with error: {e}");
    }
}
