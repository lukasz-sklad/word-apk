//! Platform-independent mobile helpers for WordCraft Android.
//!
//! Covers Pixel/notch safe-area inset conversion, soft-keyboard IME event normalization,
//! single-finger touch scroll vs two-finger pinch vs click/selection translation, theme/layout
//! presets, SAF filename sanitization, and atomic preference storage. This module compiles and
//! runs under unit tests on all hosts so Android logic stays covered by workspace CI.

use std::path::Path;

use egui::{Event, ImeEvent, Key, Modifiers, MouseWheelSource, MouseWheelUnit, PointerButton, Pos2, Rect, TouchId, TouchPhase, Vec2};
use serde_json::json;
use wordcraft_layout::ViewMode;
use wordcraft_ui_egui::WordApp;

/// Upper bound for any single IME preedit/commit payload pushed from Java.
pub const MAX_IME_BYTES: usize = 16 * 1024;
/// Upper bound (dp) for any single display-cutout / system-bar / IME inset edge.
pub const MAX_INSET_DP: f32 = 1600.0;
/// Movement threshold (in logical dp) separating a tap from a vertical/horizontal pan gesture.
pub const TOUCH_SLOP_DP: f32 = 8.0;
/// Mobile phone zoom preset (%) when no explicit user zoom override is stored yet.
pub const MOBILE_DEFAULT_ZOOM_PCT: f32 = 115.0;

/// Window insets reported by Android `WindowInsetsCompat` in physical pixels.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct SafeInsetsPx {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
    pub ime_bottom: i32,
}

/// Safe-area padding in logical egui points (dp).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SafeInsetsDp {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
    pub ime_bottom: f32,
    pub ime_visible: bool,
}

impl SafeInsetsPx {
    /// Converts physical-pixel Android insets into clamped logical dp using `pixels_per_point`.
    pub fn into_dp(self, pixels_per_point: f32) -> SafeInsetsDp {
        let ppp = if pixels_per_point.is_finite() && pixels_per_point > 0.05 { pixels_per_point } else { 1.0 };
        let to_dp = |px: i32| -> f32 { (px.max(0) as f32 / ppp).clamp(0.0, MAX_INSET_DP) };
        let nav_bottom = to_dp(self.bottom);
        let ime_bottom = to_dp(self.ime_bottom);
        SafeInsetsDp {
            left: to_dp(self.left),
            top: to_dp(self.top),
            right: to_dp(self.right),
            bottom: nav_bottom.max(ime_bottom),
            ime_bottom,
            ime_visible: self.ime_bottom > 0,
        }
    }
}

/// Key codes forwarded from Android `KeyEvent` / `InputConnection`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AndroidKey {
    Backspace,
    Enter,
    Escape,
    Tab,
    Left,
    Right,
    Up,
    Down,
    Home,
    End,
    ForwardDelete,
}

impl AndroidKey {
    /// Maps an Android `android.view.KeyEvent.KEYCODE_*` constant into an [`AndroidKey`].
    pub const fn from_keycode(keycode: i32) -> Option<Self> {
        match keycode {
            4 | 111 => Some(Self::Escape),    // KEYCODE_BACK / KEYCODE_ESCAPE
            19 => Some(Self::Up),             // KEYCODE_DPAD_UP
            20 => Some(Self::Down),           // KEYCODE_DPAD_DOWN
            21 => Some(Self::Left),           // KEYCODE_DPAD_LEFT
            22 => Some(Self::Right),          // KEYCODE_DPAD_RIGHT
            61 => Some(Self::Tab),            // KEYCODE_TAB
            66 | 160 => Some(Self::Enter),    // KEYCODE_ENTER / KEYCODE_NUMPAD_ENTER
            67 => Some(Self::Backspace),      // KEYCODE_DEL
            112 => Some(Self::ForwardDelete), // KEYCODE_FORWARD_DEL
            122 => Some(Self::Home),          // KEYCODE_MOVE_HOME
            123 => Some(Self::End),           // KEYCODE_MOVE_END
            _ => None,
        }
    }

    const fn to_egui(self) -> Key {
        match self {
            Self::Backspace => Key::Backspace,
            Self::Enter => Key::Enter,
            Self::Escape => Key::Escape,
            Self::Tab => Key::Tab,
            Self::Left => Key::ArrowLeft,
            Self::Right => Key::ArrowRight,
            Self::Up => Key::ArrowUp,
            Self::Down => Key::ArrowDown,
            Self::Home => Key::Home,
            Self::End => Key::End,
            Self::ForwardDelete => Key::Delete,
        }
    }
}

/// Normalized input event queued from the Android Java IME / key thread.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum AndroidInput {
    Preedit { text: String, cursor_start: i32, cursor_end: i32 },
    Commit(String),
    DeleteSurrounding { before: i32, after: i32 },
    Key { key: AndroidKey, pressed: bool },
}

/// Truncates `s` on a UTF-8 character boundary at or below `max_bytes`.
pub fn truncate_utf8_bytes(s: &str, max_bytes: usize) -> &str {
    if s.len() <= max_bytes {
        return s;
    }
    let mut end = max_bytes;
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    s.get(..end).unwrap_or("")
}

/// Converts a UTF-8 byte offset within `s` into a UTF-16 code-unit offset for Android `InputConnection`.
pub fn utf8_byte_to_utf16_offset(s: &str, byte_idx: usize) -> usize {
    let clamped = truncate_utf8_bytes(s, byte_idx);
    clamped.encode_utf16().count()
}

/// Piece of a committed text payload split around line endings (`\r\n`, `\n`, `\r`).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CommitPiece<'a> {
    Text(&'a str),
    Enter,
}

/// Splits committed IME text into literal slices and explicit paragraph breaks.
pub fn split_commit_text(text: &str) -> Vec<CommitPiece<'_>> {
    let mut out = Vec::new();
    let bytes = text.as_bytes();
    let mut start = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        let Some(&b) = bytes.get(i) else {
            break;
        };
        if b == b'\r' || b == b'\n' {
            if let Some(chunk) = text.get(start..i)
                && !chunk.is_empty()
            {
                out.push(CommitPiece::Text(chunk));
            }
            out.push(CommitPiece::Enter);
            if b == b'\r' && bytes.get(i + 1) == Some(&b'\n') {
                i += 1;
            }
            start = i + 1;
        }
        i += 1;
    }
    if let Some(tail) = text.get(start..)
        && !tail.is_empty()
    {
        out.push(CommitPiece::Text(tail));
    }
    out
}

fn push_key_tap(events: &mut Vec<Event>, key: Key) {
    events.push(Event::Key { key, physical_key: None, pressed: true, repeat: false, modifiers: Modifiers::NONE });
    events.push(Event::Key { key, physical_key: None, pressed: false, repeat: false, modifiers: Modifiers::NONE });
}

fn preedit_range_chars(text: &str, cursor_start: i32, cursor_end: i32) -> Option<std::ops::Range<usize>> {
    if cursor_start < 0 || cursor_end < cursor_start {
        return None;
    }
    let char_len = text.chars().count();
    let s = usize::try_from(cursor_start).ok()?.min(char_len);
    let e = usize::try_from(cursor_end).ok()?.min(char_len);
    Some(s..e)
}

/// Converts a frame's queued [`AndroidInput`] events into [`egui::Event`] values.
///
/// An unpaired `DeleteSurrounding(1, 0)` not immediately followed by a `Commit` or `Preedit`
/// (standard Android IME single-char Backspace) is translated into [`Key::Backspace`] so it works
/// across paragraph boundaries, non-collapsed selections, equations, and egui text fields.
pub fn to_egui_events(batch: &[AndroidInput]) -> Vec<Event> {
    let mut out = Vec::with_capacity(batch.len() * 2);
    for (idx, item) in batch.iter().enumerate() {
        match item {
            AndroidInput::Preedit { text, cursor_start, cursor_end } => {
                let capped = truncate_utf8_bytes(text, MAX_IME_BYTES);
                let range = preedit_range_chars(capped, *cursor_start, *cursor_end);
                out.push(Event::Ime(ImeEvent::Preedit { text: capped.to_owned(), active_range_chars: range }));
            }
            AndroidInput::Commit(text) => {
                let capped = truncate_utf8_bytes(text, MAX_IME_BYTES);
                for piece in split_commit_text(capped) {
                    match piece {
                        CommitPiece::Text(s) => out.push(Event::Ime(ImeEvent::Commit(s.to_owned()))),
                        CommitPiece::Enter => push_key_tap(&mut out, Key::Enter),
                    }
                }
            }
            AndroidInput::DeleteSurrounding { before, after } => {
                let before_u = usize::try_from((*before).clamp(0, 1024)).unwrap_or(0);
                let after_u = usize::try_from((*after).clamp(0, 1024)).unwrap_or(0);
                let paired = matches!(batch.get(idx + 1), Some(AndroidInput::Commit(_) | AndroidInput::Preedit { .. }));
                if before_u == 1 && after_u == 0 && !paired {
                    push_key_tap(&mut out, Key::Backspace);
                } else if before_u > 0 || after_u > 0 {
                    out.push(Event::Ime(ImeEvent::DeleteSurrounding { before_chars: before_u, after_chars: after_u }));
                }
            }
            AndroidInput::Key { key, pressed } => {
                out.push(Event::Key { key: key.to_egui(), physical_key: None, pressed: *pressed, repeat: false, modifiers: Modifiers::NONE });
            }
        }
    }
    out
}

/// Phase of a touch gesture inside the document canvas when not in explicit text-selection mode.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
enum GesturePhase {
    #[default]
    Idle,
    PendingTap {
        id: TouchId,
        start: Pos2,
    },
    Scrolling {
        id: TouchId,
        last: Pos2,
    },
    Pinch,
}

/// Side effects reported after [`TouchGestureFilter::process`] rewrites one frame's events.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct TouchOutcome {
    /// True when the user completed a quick tap inside the canvas (focus caret & open IME).
    pub tapped_canvas: bool,
}

/// Translates Android touchscreen events inside the document canvas so single-finger drags pan the
/// page smoothly with inertial coasting, two fingers pinch-zoom, and quick taps place the caret.
#[derive(Debug, Default)]
pub struct TouchGestureFilter {
    active_touches: Vec<(TouchId, Pos2)>,
    phase: GesturePhase,
    drop_trailing_pointer: bool,
}

impl TouchGestureFilter {
    fn set_touch(&mut self, id: TouchId, pos: Pos2, phase: TouchPhase) {
        match phase {
            TouchPhase::Start | TouchPhase::Move => {
                if let Some(entry) = self.active_touches.iter_mut().find(|(tid, _)| *tid == id) {
                    entry.1 = pos;
                } else if self.active_touches.len() < 16 {
                    self.active_touches.push((id, pos));
                }
            }
            TouchPhase::End | TouchPhase::Cancel => {
                self.active_touches.retain(|(tid, _)| *tid != id);
            }
        }
    }

    fn centroid(&self) -> Option<Pos2> {
        if self.active_touches.is_empty() {
            return None;
        }
        let sum = self.active_touches.iter().fold(Vec2::ZERO, |acc, (_, p)| acc + p.to_vec2());
        Some((sum / self.active_touches.len() as f32).to_pos2())
    }

    /// Rewrites `events` for a single frame and returns any tap trigger.
    ///
    /// When `modal_open` is true or a touch starts outside `canvas_viewport`, events pass through
    /// untouched so menus, toolbars, dialogs, and scrollbars respond directly.
    pub fn process(&mut self, events: &mut Vec<Event>, canvas_viewport: Option<Rect>, select_mode: bool, modal_open: bool) -> TouchOutcome {
        let mut outcome = TouchOutcome::default();
        if modal_open {
            self.active_touches.clear();
            self.phase = GesturePhase::Idle;
            self.drop_trailing_pointer = false;
            return outcome;
        }
        let original = std::mem::take(events);
        events.reserve(original.len() + 4);
        for ev in original {
            self.handle_event(ev, canvas_viewport, select_mode, events, &mut outcome);
        }
        outcome
    }

    fn handle_event(&mut self, ev: Event, canvas_viewport: Option<Rect>, select_mode: bool, out: &mut Vec<Event>, outcome: &mut TouchOutcome) {
        if let Event::Touch { id, phase, pos, .. } = ev {
            if matches!(phase, TouchPhase::Start) {
                self.drop_trailing_pointer = false;
            }
            self.set_touch(id, pos, phase);
            let any_in_canvas = canvas_viewport.is_some_and(|r| self.active_touches.iter().any(|(_, p)| r.contains(*p)));
            if self.active_touches.len() >= 2 && any_in_canvas && !matches!(self.phase, GesturePhase::Pinch) {
                self.enter_pinch(out);
            }
            match phase {
                TouchPhase::Start => self.on_touch_start(id, pos, canvas_viewport, select_mode),
                TouchPhase::Move => self.on_touch_move(id, pos, out),
                TouchPhase::End => self.on_touch_end(id, pos, out, outcome),
                TouchPhase::Cancel => self.on_touch_cancel(id, out),
            }
            out.push(ev);
            return;
        }
        if matches!(self.phase, GesturePhase::Idle) && self.drop_trailing_pointer {
            match ev {
                Event::PointerButton { button: PointerButton::Primary, pressed: false, .. } | Event::PointerMoved(_) => return,
                Event::PointerGone => {
                    self.drop_trailing_pointer = false;
                    return;
                }
                _ => {
                    self.drop_trailing_pointer = false;
                }
            }
        }
        if matches!(self.phase, GesturePhase::Idle) || (select_mode && !matches!(self.phase, GesturePhase::Pinch)) {
            out.push(ev);
            return;
        }
        // While filtering a canvas tap/scroll/pinch, drop egui-winit's synthetic pointer clicks/moves
        // so dragging the page never starts accidental text selection.
        match ev {
            Event::PointerButton { button: PointerButton::Primary, .. } | Event::PointerMoved(_) | Event::PointerGone => {}
            other => out.push(other),
        }
    }

    fn enter_pinch(&mut self, out: &mut Vec<Event>) {
        if matches!(self.phase, GesturePhase::Scrolling { .. }) {
            emit_wheel(out, Vec2::ZERO, TouchPhase::End);
        }
        if let Some(c) = self.centroid() {
            out.push(Event::PointerMoved(c));
        }
        self.phase = GesturePhase::Pinch;
    }

    fn on_touch_start(&mut self, id: TouchId, pos: Pos2, canvas_viewport: Option<Rect>, select_mode: bool) {
        let in_canvas = canvas_viewport.is_some_and(|r| r.contains(pos));
        if self.active_touches.len() >= 2 && in_canvas {
            self.phase = GesturePhase::Pinch;
        } else if matches!(self.phase, GesturePhase::Idle) && in_canvas && !select_mode {
            self.phase = GesturePhase::PendingTap { id, start: pos };
        }
    }

    fn on_touch_move(&mut self, id: TouchId, pos: Pos2, out: &mut Vec<Event>) {
        match self.phase {
            GesturePhase::PendingTap { id: tid, start } if tid == id => {
                if pos.distance(start) >= TOUCH_SLOP_DP {
                    out.push(Event::PointerMoved(pos));
                    emit_wheel(out, pos - start, TouchPhase::Start);
                    self.phase = GesturePhase::Scrolling { id: tid, last: pos };
                }
            }
            GesturePhase::Scrolling { id: tid, last } if tid == id => {
                out.push(Event::PointerMoved(pos));
                emit_wheel(out, pos - last, TouchPhase::Move);
                self.phase = GesturePhase::Scrolling { id: tid, last: pos };
            }
            GesturePhase::Pinch => {
                if let Some(c) = self.centroid() {
                    out.push(Event::PointerMoved(c));
                }
            }
            _ => {}
        }
    }

    fn on_touch_end(&mut self, id: TouchId, pos: Pos2, out: &mut Vec<Event>, outcome: &mut TouchOutcome) {
        match self.phase {
            GesturePhase::PendingTap { id: tid, .. } if tid == id => {
                out.push(Event::PointerMoved(pos));
                out.push(Event::PointerButton { pos, button: PointerButton::Primary, pressed: true, modifiers: Modifiers::NONE });
                out.push(Event::PointerButton { pos, button: PointerButton::Primary, pressed: false, modifiers: Modifiers::NONE });
                out.push(Event::PointerGone);
                outcome.tapped_canvas = true;
                self.phase = GesturePhase::Idle;
                self.drop_trailing_pointer = true;
            }
            GesturePhase::Scrolling { id: tid, .. } if tid == id => {
                emit_wheel(out, Vec2::ZERO, TouchPhase::End);
                out.push(Event::PointerGone);
                self.phase = GesturePhase::Idle;
                self.drop_trailing_pointer = true;
            }
            GesturePhase::Pinch if self.active_touches.is_empty() => {
                out.push(Event::PointerGone);
                self.phase = GesturePhase::Idle;
                self.drop_trailing_pointer = true;
            }
            _ => {}
        }
    }

    fn on_touch_cancel(&mut self, id: TouchId, out: &mut Vec<Event>) {
        match self.phase {
            GesturePhase::Scrolling { id: tid, .. } if tid == id => {
                emit_wheel(out, Vec2::ZERO, TouchPhase::End);
                out.push(Event::PointerGone);
                self.phase = GesturePhase::Idle;
                self.drop_trailing_pointer = true;
            }
            GesturePhase::PendingTap { id: tid, .. } if tid == id => {
                self.phase = GesturePhase::Idle;
                self.drop_trailing_pointer = true;
            }
            GesturePhase::Pinch if self.active_touches.is_empty() => {
                out.push(Event::PointerGone);
                self.phase = GesturePhase::Idle;
                self.drop_trailing_pointer = true;
            }
            _ => {}
        }
    }
}

fn emit_wheel(out: &mut Vec<Event>, delta: Vec2, phase: TouchPhase) {
    out.push(Event::MouseWheel { unit: MouseWheelUnit::Point, delta, phase, modifiers: Modifiers::NONE, source: MouseWheelSource::Unknown });
}

/// User-facing Android appearance & paper preset cycled from the mobile quick bar.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum AndroidThemeMode {
    #[default]
    System,
    Light,
    Sepia,
    Dark,
}

impl AndroidThemeMode {
    /// Short label displayed on the compact mobile theme pill.
    pub const fn short_label(self) -> &'static str {
        match self {
            Self::System => "Sys",
            Self::Light => "Light",
            Self::Sepia => "Sepia",
            Self::Dark => "Dark",
        }
    }

    /// Cycles through System -> Light -> Sepia -> Dark -> System.
    pub const fn cycle_next(self) -> Self {
        match self {
            Self::System => Self::Light,
            Self::Light => Self::Sepia,
            Self::Sepia => Self::Dark,
            Self::Dark => Self::System,
        }
    }

    /// Applies the UI theme and screen paper preset cleanly via engine/UI commands.
    pub fn apply(self, app: &mut WordApp) {
        let (theme, paper) = match self {
            Self::System => ("system", "document"),
            Self::Light => ("light", "document"),
            Self::Sepia => ("light", "cream"),
            Self::Dark => ("dark", "document"),
        };
        let _ = app.execute("view.interfaceTheme", json!({ "value": theme }));
        let _ = app.execute("view.paperColor", json!({ "value": paper }));
    }
}

/// Applies mobile-friendly defaults on WordCraft Android.
///
/// Disables online startup update checks, skips desktop start-screen Backstage, collapses the full
/// desktop ribbon when first launched so content fits on a phone viewport, and ensures documents
/// open in readable phone-width Web Layout unless the user switched back to Print Layout.
pub fn apply_initial_mobile_defaults(app: &mut WordApp, had_saved_prefs: bool) {
    app.set_startup_update_check(false);
    app.ui.start_screen = false;
    app.ui.backstage = false;
    if !had_saved_prefs {
        app.ui.ribbon_collapsed = true;
    }
    if app.session.view.mode != ViewMode::Web {
        let _ = app.execute("view.webLayout", json!({}));
    }
    if !app.session.view.zoom.is_finite() || (app.session.view.zoom - 1.0).abs() < 0.02 {
        let _ = app.execute("view.zoom", json!({ "value": MOBILE_DEFAULT_ZOOM_PCT }));
    }
}

/// Sanitizes an arbitrary Android SAF display name for local staging storage.
pub fn sanitize_stage_filename(raw: &str) -> String {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or(raw).trim();
    let mut cleaned: String = base
        .chars()
        .map(|c| match c {
            c if c.is_control() => '_',
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            c => c,
        })
        .collect();
    while cleaned.starts_with('.') {
        cleaned.remove(0);
    }
    let truncated = truncate_utf8_bytes(&cleaned, 120).trim().to_owned();
    if truncated.is_empty() || truncated == "." || truncated == ".." { "document.docx".to_owned() } else { truncated }
}

/// Atomically writes `bytes` to `path` via a sibling temporary file + rename.
pub fn prefs_write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "ui.json".into());
    let tmp = path.with_file_name(format!(".{name}.{}.tmp", std::process::id()));
    let res = std::fs::write(&tmp, bytes).and_then(|()| std::fs::rename(&tmp, path));
    if res.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    res
}

#[cfg(test)]
mod tests {
    use super::*;
    use egui::{TouchDeviceId, pos2, vec2};
    use wordcraft_doc::Document;
    use wordcraft_engine::Session;
    use wordcraft_ui_egui::{Services, theme};

    fn test_app() -> WordApp {
        WordApp::new(Session::new(Document::new()), Services::default())
    }

    #[test]
    fn insets_clamp_and_scale_with_ime() {
        let px = SafeInsetsPx { left: -12, top: 144, right: 0, bottom: 72, ime_bottom: 900 };
        let dp = px.into_dp(3.0);
        assert_eq!(dp.left, 0.0);
        assert!((dp.top - 48.0).abs() < 1e-4);
        assert!((dp.bottom - 300.0).abs() < 1e-4);
        assert!(dp.ime_visible);

        // Invalid or non-finite pixels_per_point falls back to 1.0 safely.
        let safe = SafeInsetsPx { left: 10, top: 20, right: 0, bottom: 5, ime_bottom: 0 }.into_dp(f32::NAN);
        assert_eq!(safe.top, 20.0);
        assert!(!safe.ime_visible);
    }

    #[test]
    fn commit_splits_newlines_and_caps_on_char_boundary() {
        let pieces = split_commit_text("zażółć\r\ngęślą\njaźń");
        assert_eq!(
            pieces,
            vec![CommitPiece::Text("zażółć"), CommitPiece::Enter, CommitPiece::Text("gęślą"), CommitPiece::Enter, CommitPiece::Text("jaźń")]
        );
        // Splitting mid-codepoint backs down cleanly without panicking.
        assert_eq!(truncate_utf8_bytes("ąćę", 3), "ą");
        assert_eq!(utf8_byte_to_utf16_offset("a😀b", "a😀".len()), 3);
    }

    #[test]
    fn unpaired_single_delete_becomes_backspace_while_paired_stays_delete_surrounding() {
        let unpaired = to_egui_events(&[AndroidInput::DeleteSurrounding { before: 1, after: 0 }]);
        assert_eq!(unpaired.len(), 2);
        assert!(matches!(unpaired[0], Event::Key { key: Key::Backspace, pressed: true, .. }));
        assert!(matches!(unpaired[1], Event::Key { key: Key::Backspace, pressed: false, .. }));

        let paired = to_egui_events(&[AndroidInput::DeleteSurrounding { before: 1, after: 0 }, AndroidInput::Commit("I\n".into())]);
        assert!(matches!(paired[0], Event::Ime(ImeEvent::DeleteSurrounding { before_chars: 1, after_chars: 0 })));
        assert!(matches!(paired[1], Event::Ime(ImeEvent::Commit(ref s)) if s == "I"));
        assert!(matches!(paired[2], Event::Key { key: Key::Enter, pressed: true, .. }));
    }

    fn touch(id: u64, phase: TouchPhase, pos: Pos2) -> Event {
        Event::Touch { device_id: TouchDeviceId(0), id: TouchId(id), phase, pos, force: None }
    }

    fn press(pos: Pos2, pressed: bool) -> Event {
        Event::PointerButton { pos, button: PointerButton::Primary, pressed, modifiers: Modifiers::NONE }
    }

    #[test]
    fn touch_filter_turns_drag_into_scroll_and_tap_into_click() {
        let vp = Some(Rect::from_min_size(pos2(0.0, 50.0), vec2(400.0, 700.0)));
        let mut filter = TouchGestureFilter::default();

        // Quick tap inside canvas triggers click + IME request, no wheel events.
        let mut events =
            vec![touch(1, TouchPhase::Start, pos2(100.0, 200.0)), Event::PointerMoved(pos2(100.0, 200.0)), press(pos2(100.0, 200.0), true)];
        let out1 = filter.process(&mut events, vp, false, false);
        assert!(!out1.tapped_canvas);
        assert!(!events.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));

        let mut end = vec![touch(1, TouchPhase::End, pos2(101.0, 201.0)), press(pos2(101.0, 201.0), false), Event::PointerGone];
        let out2 = filter.process(&mut end, vp, false, false);
        assert!(out2.tapped_canvas);
        assert!(end.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));
        assert!(!end.iter().any(|e| matches!(e, Event::MouseWheel { .. })));

        // Drag past slop emits MouseWheel deltas and never presses Primary pointer button.
        let mut drag = vec![
            touch(2, TouchPhase::Start, pos2(120.0, 300.0)),
            press(pos2(120.0, 300.0), true),
            touch(2, TouchPhase::Move, pos2(120.0, 250.0)),
            Event::PointerMoved(pos2(120.0, 250.0)),
            touch(2, TouchPhase::End, pos2(120.0, 250.0)),
            press(pos2(120.0, 250.0), false),
        ];
        let out3 = filter.process(&mut drag, vp, false, false);
        assert!(!out3.tapped_canvas);
        assert!(!drag.iter().any(|e| matches!(e, Event::PointerButton { .. })));
        assert!(drag.iter().any(|e| matches!(e, Event::MouseWheel { delta, .. } if delta.y < -40.0)));
    }

    #[test]
    fn touch_filter_allows_pinch_and_select_mode() {
        let vp = Some(Rect::from_min_size(pos2(0.0, 50.0), vec2(400.0, 700.0)));
        let mut filter = TouchGestureFilter::default();

        // Two-finger pinch keeps Touch events while suppressing Primary pointer selection.
        let mut pinch = vec![
            touch(1, TouchPhase::Start, pos2(100.0, 200.0)),
            press(pos2(100.0, 200.0), true),
            touch(2, TouchPhase::Start, pos2(200.0, 200.0)),
            touch(2, TouchPhase::Move, pos2(250.0, 200.0)),
            touch(1, TouchPhase::End, pos2(100.0, 200.0)),
            touch(2, TouchPhase::End, pos2(250.0, 200.0)),
            press(pos2(250.0, 200.0), false),
        ];
        filter.process(&mut pinch, vp, false, false);
        assert!(!pinch.iter().any(|e| matches!(e, Event::PointerButton { .. })));
        assert_eq!(pinch.iter().filter(|e| matches!(e, Event::Touch { .. })).count(), 5);

        // Explicit select mode passes single-finger drag straight through.
        let mut sel = vec![touch(3, TouchPhase::Start, pos2(100.0, 200.0)), press(pos2(100.0, 200.0), true)];
        filter.process(&mut sel, vp, true, false);
        assert!(sel.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));
    }

    #[test]
    fn mobile_defaults_and_theme_cycle() {
        let mut app = test_app();
        apply_initial_mobile_defaults(&mut app, false);
        assert!(!app.ui.start_screen);
        assert!(app.ui.ribbon_collapsed);
        assert_eq!(app.session.view.mode, ViewMode::Web);

        let mut mode = AndroidThemeMode::System;
        mode = mode.cycle_next();
        assert_eq!(mode, AndroidThemeMode::Light);
        mode.apply(&mut app);
        assert_eq!(app.ui.theme, theme::Appearance::Light);

        mode = mode.cycle_next();
        assert_eq!(mode, AndroidThemeMode::Sepia);
        mode.apply(&mut app);
        assert_eq!(app.ui.theme, theme::Appearance::Light);

        mode = mode.cycle_next();
        assert_eq!(mode, AndroidThemeMode::Dark);
        mode.apply(&mut app);
        assert_eq!(app.ui.theme, theme::Appearance::Dark);
    }

    #[test]
    fn filename_sanitization_strips_traversal() {
        assert_eq!(sanitize_stage_filename("../../secret:note?.docx"), "secret_note_.docx");
        assert_eq!(sanitize_stage_filename(".."), "document.docx");
        assert_eq!(sanitize_stage_filename("   "), "document.docx");
    }
}
