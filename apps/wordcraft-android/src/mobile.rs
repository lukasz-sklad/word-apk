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
    SetSelectionRelative { delta_anchor_cp: i32, delta_focus_cp: i32 },
    Key { key: AndroidKey, pressed: bool },
}

/// Queue entry tagged with the Java IME transaction sequence that produced it.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QueuedInput {
    pub item: AndroidInput,
    pub seq: u64,
}

/// One frame's ordered slice of Android IME actions split around selection moves.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ImeFrameSlice {
    /// Cursor/selection moves to apply to the document before `egui` processes this frame's events.
    pub pre_moves: Vec<(i32, i32)>,
    /// Contiguous IME/key events forwarded through `egui::RawInput::events`.
    pub events: Vec<AndroidInput>,
    /// Cursor/selection moves requested immediately after `events`, before surrounding sync.
    pub post_moves: Vec<(i32, i32)>,
    /// Highest transaction sequence included in this slice.
    pub acked_seq: u64,
    /// True when more queued operations wait behind `post_moves` for the next frame.
    pub has_more: bool,
}

/// Extracts at most one ordered `pre_moves -> events -> post_moves` transaction slice from `queue`.
///
/// Leaving anything after `post_moves` for the next frame keeps interleaved batches strictly
/// ordered without advancing `acked_seq` past unconsumed batches.
pub fn take_ime_frame_slice(queue: &mut Vec<QueuedInput>, current_acked_seq: u64) -> ImeFrameSlice {
    let mut slice = ImeFrameSlice { acked_seq: current_acked_seq, ..Default::default() };
    let mut phase = 0u8;
    let mut cut = 0usize;
    for entry in queue.iter() {
        match entry.item {
            AndroidInput::SetSelectionRelative { delta_anchor_cp, delta_focus_cp } => {
                if phase == 1 {
                    phase = 2;
                }
                if phase == 0 {
                    slice.pre_moves.push((delta_anchor_cp, delta_focus_cp));
                } else {
                    slice.post_moves.push((delta_anchor_cp, delta_focus_cp));
                }
                slice.acked_seq = slice.acked_seq.max(entry.seq);
                cut += 1;
            }
            _ => {
                if phase == 2 {
                    break;
                }
                phase = 1;
                slice.events.push(entry.item.clone());
                slice.acked_seq = slice.acked_seq.max(entry.seq);
                cut += 1;
            }
        }
    }
    if cut > 0 {
        queue.drain(..cut);
    }
    slice.has_more = !queue.is_empty();
    slice
}

/// Steps `byte_off` inside `text` by `delta_cp` Unicode scalar values, clamping at `[0, text.len()]`.
pub fn step_utf8_by_chars(text: &str, byte_off: usize, delta_cp: i32) -> usize {
    let mut origin = byte_off.min(text.len());
    while origin > 0 && !text.is_char_boundary(origin) {
        origin -= 1;
    }
    if delta_cp >= 0 {
        let steps = usize::try_from(delta_cp).unwrap_or(0);
        text.get(origin..).and_then(|s| s.char_indices().nth(steps)).map_or(text.len(), |(rel, _)| origin + rel)
    } else {
        let steps = usize::try_from(delta_cp.unsigned_abs()).unwrap_or(usize::MAX);
        text.get(..origin).and_then(|s| s.char_indices().rev().nth(steps.saturating_sub(1))).map_or(0, |(idx, _)| idx)
    }
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
            AndroidInput::SetSelectionRelative { .. } => {}
            AndroidInput::Key { key, pressed } => {
                out.push(Event::Key { key: key.to_egui(), physical_key: None, pressed: *pressed, repeat: false, modifiers: Modifiers::NONE });
            }
        }
    }
    out
}

/// Converts an Android `MotionEvent` `ACTION_SCROLL` `(AXIS_HSCROLL, AXIS_VSCROLL)` pair into an
/// egui [`Event::MouseWheel`]. Positive Android `AXIS_VSCROLL` scrolls toward the top (positive `dy`).
pub fn wheel_to_egui_event(h_scroll: f32, v_scroll: f32) -> Option<Event> {
    if !h_scroll.is_finite() || !v_scroll.is_finite() {
        return None;
    }
    let h = h_scroll.clamp(-64.0, 64.0);
    let v = v_scroll.clamp(-64.0, 64.0);
    if h.abs() < 1e-4 && v.abs() < 1e-4 {
        return None;
    }
    Some(Event::MouseWheel {
        unit: MouseWheelUnit::Line,
        delta: Vec2::new(-h, v),
        phase: TouchPhase::Move,
        modifiers: Modifiers::NONE,
        source: MouseWheelSource::Wheel,
    })
}

/// Converts an Android `ACTION_HOVER_MOVE` physical-pixel position into a logical [`Event::PointerMoved`].
pub fn hover_to_egui_event(x_px: f32, y_px: f32, pixels_per_point: f32) -> Option<Event> {
    if !x_px.is_finite() || !y_px.is_finite() || !pixels_per_point.is_finite() || pixels_per_point <= 0.05 {
        return None;
    }
    let x = (x_px / pixels_per_point).clamp(0.0, 16_384.0);
    let y = (y_px / pixels_per_point).clamp(0.0, 16_384.0);
    Some(Event::PointerMoved(egui::pos2(x, y)))
}

/// Returns the touch-pannable document viewport excluding scrollbars.
///
/// Falls back to `canvas_rect` trimmed on the right/bottom before the first scroll pass computes `viewport`.
pub fn document_pan_rect(viewport: Option<Rect>, canvas_rect: Option<Rect>) -> Option<Rect> {
    viewport.or_else(|| canvas_rect.map(|r| Rect::from_min_max(r.min, egui::pos2((r.max.x - 18.0).max(r.min.x), (r.max.y - 18.0).max(r.min.y)))))
}

/// Resolves whether the Android on-screen keyboard should stay active given a request `wanted`
/// and the toolbar keyboard-suppression lock (`Kla`).
pub const fn effective_ime_wanted(wanted: bool, ime_suppressed: bool) -> bool {
    wanted && !ime_suppressed
}

/// Minimum average finger distance from the centroid before scaling begins (prevents spikes).
const MIN_PINCH_RADIUS_DP: f32 = 10.0;
/// Bounds per-event pinch scale deltas against single-frame touch jitter.
const MAX_PINCH_STEP_RATIO: f32 = 1.35;

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
    Pinch {
        center: Pos2,
        span: Option<f32>,
    },
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
        if !pos.x.is_finite() || !pos.y.is_finite() {
            return;
        }
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

    fn pinch_geometry(&self) -> Option<(Pos2, f32)> {
        let count = self.active_touches.len();
        if count < 2 {
            return None;
        }
        let sum = self.active_touches.iter().fold(Vec2::ZERO, |acc, (_, p)| acc + p.to_vec2());
        let center = (sum / (count as f32)).to_pos2();
        let radius_sum: f32 = self.active_touches.iter().map(|(_, p)| (*p - center).length()).sum();
        Some((center, radius_sum / (count as f32)))
    }

    /// Rewrites `events` for a single frame and returns any tap trigger.
    ///
    /// When `modal_open` is true or a touch starts outside `canvas_viewport`, events pass through
    /// untouched so menus, toolbars, dialogs, and scrollbars respond directly.
    pub fn process(&mut self, events: &mut Vec<Event>, canvas_viewport: Option<Rect>, select_mode: bool, modal_open: bool) -> TouchOutcome {
        self.process_with_overlays(events, canvas_viewport, &[], select_mode, modal_open)
    }

    /// Rewrites `events` while treating touches that begin inside `excluded_rects` (such as floating
    /// tool windows like Read Aloud) as UI interactions rather than canvas scrolls/taps.
    pub fn process_with_overlays(
        &mut self,
        events: &mut Vec<Event>,
        canvas_viewport: Option<Rect>,
        excluded_rects: &[Rect],
        select_mode: bool,
        modal_open: bool,
    ) -> TouchOutcome {
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
            self.handle_event(ev, canvas_viewport, excluded_rects, select_mode, events, &mut outcome);
        }
        outcome
    }

    fn handle_event(
        &mut self,
        ev: Event,
        canvas_viewport: Option<Rect>,
        excluded_rects: &[Rect],
        select_mode: bool,
        out: &mut Vec<Event>,
        outcome: &mut TouchOutcome,
    ) {
        if let Event::Touch { id, phase, pos, .. } = ev {
            if matches!(phase, TouchPhase::Start) {
                self.drop_trailing_pointer = false;
            }
            let in_canvas = point_in_canvas(pos, canvas_viewport, excluded_rects);
            let tracked = matches!(phase, TouchPhase::Start) && in_canvas || self.active_touches.iter().any(|(tid, _)| *tid == id);
            if tracked {
                self.set_touch(id, pos, phase);
            }
            if tracked && self.active_touches.len() >= 2 && !matches!(self.phase, GesturePhase::Pinch { .. }) {
                self.enter_pinch(out);
            }
            if tracked {
                match phase {
                    TouchPhase::Start => self.on_touch_start(id, pos, in_canvas, select_mode),
                    TouchPhase::Move => self.on_touch_move(id, pos, out),
                    TouchPhase::End => self.on_touch_end(id, pos, out, outcome),
                    TouchPhase::Cancel => self.on_touch_cancel(id, out),
                }
            }
            // During active Pinch we emit explicit bounded Event::Zoom + PointerMoved(centroid)
            // directly, avoiding egui::TouchState's unguarded avg_distance division when fingers
            // start close together or when PointerGone clears pointer_pos.
            if !tracked || !matches!(self.phase, GesturePhase::Pinch { .. }) {
                out.push(ev);
            }
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
        if matches!(self.phase, GesturePhase::Idle) || (select_mode && !matches!(self.phase, GesturePhase::Pinch { .. })) {
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
        if let Some((center, radius)) = self.pinch_geometry() {
            out.push(Event::PointerMoved(center));
            emit_wheel(out, Vec2::ZERO, TouchPhase::Start);
            let span = (radius >= MIN_PINCH_RADIUS_DP).then_some(radius);
            self.phase = GesturePhase::Pinch { center, span };
        }
    }

    fn on_touch_start(&mut self, id: TouchId, pos: Pos2, in_canvas: bool, select_mode: bool) {
        if matches!(self.phase, GesturePhase::Pinch { .. }) {
            if let Some((center, radius)) = self.pinch_geometry() {
                self.phase = GesturePhase::Pinch { center, span: (radius >= MIN_PINCH_RADIUS_DP).then_some(radius) };
            }
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
            GesturePhase::Pinch { mut center, mut span } => {
                if let Some((new_center, new_radius)) = self.pinch_geometry() {
                    out.push(Event::PointerMoved(new_center));
                    let pan_delta = new_center - center;
                    if pan_delta.length_sq() > 0.25 {
                        emit_wheel(out, pan_delta, TouchPhase::Move);
                        center = new_center;
                    }
                    if let Some(old_span) = span {
                        if new_radius >= MIN_PINCH_RADIUS_DP {
                            let min_ratio = 1.0 / MAX_PINCH_STEP_RATIO;
                            let ratio = (new_radius / old_span).clamp(min_ratio, MAX_PINCH_STEP_RATIO);
                            if (ratio - 1.0).abs() > 0.002 {
                                out.push(Event::Zoom(ratio));
                                span = Some(new_radius);
                            }
                        }
                    } else if new_radius >= MIN_PINCH_RADIUS_DP {
                        span = Some(new_radius);
                    }
                    self.phase = GesturePhase::Pinch { center, span };
                }
            }
            _ => {}
        }
    }

    fn finish_pinch_or_reanchor(&mut self, out: &mut Vec<Event>) {
        if let Some((new_center, new_radius)) = self.pinch_geometry() {
            out.push(Event::PointerMoved(new_center));
            self.phase = GesturePhase::Pinch { center: new_center, span: (new_radius >= MIN_PINCH_RADIUS_DP).then_some(new_radius) };
        } else if let Some((_, remaining_pos)) = self.active_touches.first().copied() {
            self.phase = GesturePhase::Pinch { center: remaining_pos, span: None };
        } else {
            emit_wheel(out, Vec2::ZERO, TouchPhase::End);
            out.push(Event::PointerGone);
            self.phase = GesturePhase::Idle;
            self.drop_trailing_pointer = true;
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
            GesturePhase::Pinch { .. } => self.finish_pinch_or_reanchor(out),
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
            GesturePhase::Pinch { .. } => self.finish_pinch_or_reanchor(out),
            _ => {}
        }
    }
}

fn point_in_canvas(pos: Pos2, canvas_viewport: Option<Rect>, excluded_rects: &[Rect]) -> bool {
    canvas_viewport.is_some_and(|r| r.contains(pos)) && !excluded_rects.iter().any(|ex| ex.contains(pos))
}

fn emit_wheel(out: &mut Vec<Event>, delta: Vec2, phase: TouchPhase) {
    out.push(Event::MouseWheel { unit: MouseWheelUnit::Point, delta, phase, modifiers: Modifiers::NONE, source: MouseWheelSource::Unknown });
}

/// Localized UI strings for the custom Android mobile shell.
///
/// Currently provides English (`en`, default fallback) and Polish (`pl`) so the top bar never
/// mixes languages on screen. Additional languages can be added cleanly via pull request.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MobileStrings {
    pub open: &'static str,
    pub new_doc: &'static str,
    pub save: &'static str,
    pub undo: &'static str,
    pub redo: &'static str,
    pub bold: &'static str,
    pub italic: &'static str,
    pub select: &'static str,
    pub keyboard: &'static str,
    pub page_view: &'static str,
    pub web_view: &'static str,
    pub read_aloud: &'static str,
    pub read_aloud_show: &'static str,
    pub read_aloud_hide: &'static str,
    pub ribbon: &'static str,
    pub lock_keyboard: &'static str,
    pub theme_system: &'static str,
    pub theme_light: &'static str,
    pub theme_sepia: &'static str,
    pub theme_dark: &'static str,
    pub nothing_to_read: &'static str,
    pub select_on_hint: &'static str,
    pub select_off_hint: &'static str,
    pub open_failed_prefix: &'static str,
    page_short: &'static str,
    words_short: &'static str,
}

const EN_STRINGS: MobileStrings = MobileStrings {
    open: "Open",
    new_doc: "New",
    save: "Save",
    undo: "Undo",
    redo: "Redo",
    bold: "B",
    italic: "I",
    select: "Sel",
    keyboard: "KB",
    page_view: "Page",
    web_view: "Web",
    read_aloud: "Read",
    read_aloud_show: "Show Read Aloud window",
    read_aloud_hide: "Hide Read Aloud window",
    ribbon: "Rib",
    lock_keyboard: "Lock",
    theme_system: "Sys",
    theme_light: "Light",
    theme_sepia: "Sepia",
    theme_dark: "Dark",
    nothing_to_read: "Nothing to read",
    select_on_hint: "Select mode: drag finger across text to select",
    select_off_hint: "Scroll mode: drag to pan, tap to type",
    open_failed_prefix: "Open failed",
    page_short: "P",
    words_short: "w",
};

const PL_STRINGS: MobileStrings = MobileStrings {
    open: "Otwórz",
    new_doc: "Nowy",
    save: "Zapisz",
    undo: "Cofnij",
    redo: "Ponów",
    bold: "B",
    italic: "I",
    select: "Zazn",
    keyboard: "Klaw",
    page_view: "Dok",
    web_view: "Web",
    read_aloud: "Czytaj",
    read_aloud_show: "Pokaż okienko Czytaj na głos",
    read_aloud_hide: "Ukryj okienko Czytaj na głos",
    ribbon: "Wst",
    lock_keyboard: "Blok",
    theme_system: "Sys",
    theme_light: "Jasny",
    theme_sepia: "Sepia",
    theme_dark: "Ciemny",
    nothing_to_read: "Brak tekstu do czytania",
    select_on_hint: "Tryb zaznaczania: przeciągnij palcem po tekście",
    select_off_hint: "Tryb przewijania: przesuwaj palcem, stuknij aby pisać",
    open_failed_prefix: "Błąd otwierania",
    page_short: "S",
    words_short: " sł.",
};

/// Returns consistent mobile toolbar strings in Polish (`"pl"`) or English (default fallback).
pub fn mobile_strings(lang_code: &str) -> MobileStrings {
    let primary = lang_code.trim().split(['-', '_']).next().unwrap_or("");
    if primary.eq_ignore_ascii_case("pl") { PL_STRINGS } else { EN_STRINGS }
}

impl MobileStrings {
    /// Formats the compact page/word indicator (`P1/2 · 15w` in EN, `S1/2 · 15 sł.` in PL).
    pub fn format_status(self, page: usize, pages: usize, words: usize) -> String {
        format!("{}{page}/{pages} · {words}{}", self.page_short, self.words_short)
    }
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
    /// Short localized label displayed on the compact mobile theme pill.
    pub const fn short_label_for(self, strings: &MobileStrings) -> &'static str {
        match self {
            Self::System => strings.theme_system,
            Self::Light => strings.theme_light,
            Self::Sepia => strings.theme_sepia,
            Self::Dark => strings.theme_dark,
        }
    }

    /// English short label displayed on the compact mobile theme pill.
    pub const fn short_label(self) -> &'static str {
        self.short_label_for(&EN_STRINGS)
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
/// Disables online startup update checks and upstream community/Discord links, skips desktop
/// start-screen Backstage, collapses the full desktop ribbon when first launched so content fits on
/// a phone viewport, and defaults to Document (`Print`) view on first launch so pages render at
/// real proportions rather than stretched web text.
pub fn apply_initial_mobile_defaults(app: &mut WordApp, had_saved_prefs: bool) {
    app.set_startup_update_check(false);
    app.ui.show_discord = false;
    app.ui.start_screen = false;
    app.ui.backstage = false;
    app.ui.compact_chrome = true;
    if !had_saved_prefs {
        app.ui.ribbon_collapsed = true;
        if app.session.view.mode != ViewMode::Print {
            let _ = app.execute("view.printLayout", json!({}));
        }
    }
}

/// Narrow viewport threshold (in logical dp) below which initial Document view uses Fit-to-Page-Width.
pub const NARROW_VIEWPORT_DP: f32 = 760.0;

/// Applies responsive initial zoom on narrow phone screens in Document (`Print`) mode without overriding manual zoom.
pub fn apply_initial_viewport_zoom(app: &mut WordApp, avail_width_dp: f32) {
    if !avail_width_dp.is_finite() || avail_width_dp <= 0.0 {
        return;
    }
    if app.session.view.mode == ViewMode::Print
        && app.session.view.fit.is_empty()
        && (app.session.view.zoom - 1.0).abs() < 0.01
        && avail_width_dp < NARROW_VIEWPORT_DP
    {
        let _ = app.execute("view.pageWidth", json!({}));
    }
}

/// Tracks smart Play/Pause/Resume/Read-Selection behaviour for the mobile `Czytaj` (`Read`) pill.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CzytajController {
    paused_selection: Option<(u64, wordcraft_doc::Pos, wordcraft_doc::Pos)>,
}

impl CzytajController {
    /// Toggles Read Aloud for the active document:
    /// - If currently playing on this document, pauses and remembers the exact sentence position.
    /// - If paused on this document without a newly changed selection, resumes from that position.
    /// - Otherwise reads the non-empty selection if present, or reads from the beginning to the end.
    pub fn toggle(&mut self, app: &mut WordApp) {
        let doc_id = app.session.document_id();
        let state = app.session.read_aloud.state();
        let same_doc = app.session.read_aloud.document == Some(doc_id);
        if same_doc && state == wordcraft_engine::speech::State::Playing {
            let _ = app.execute("readAloud.playPause", json!({}));
            return;
        }
        let has_sel = !app.session.sel.is_collapsed();
        let sel_pair = app.session.sel.ordered();
        let same_paused_sel = self.paused_selection.as_ref() == Some(&(doc_id, sel_pair.0.clone(), sel_pair.1.clone()));
        if same_doc && state == wordcraft_engine::speech::State::Paused && (!has_sel || same_paused_sel) {
            let _ = app.execute("readAloud.playPause", json!({}));
            return;
        }
        self.start_reading(app, doc_id, has_sel, sel_pair);
    }

    fn start_reading(&mut self, app: &mut WordApp, doc_id: u64, has_sel: bool, sel_pair: (wordcraft_doc::Pos, wordcraft_doc::Pos)) {
        let (from, to, next_sel) = if has_sel {
            let (a, b) = sel_pair;
            (a.clone(), Some(b.clone()), Some((doc_id, a, b)))
        } else {
            (app.session.doc.start_of(wordcraft_doc::StoryRef::Body), None, None)
        };
        let queue = wordcraft_engine::speech::sentences(&app.session.doc, &from, to.as_ref(), app.session.read_aloud.skip_citations);
        if queue.is_empty() {
            self.paused_selection = None;
            let s = mobile_strings(wordcraft_ui_egui::i18n::current().code());
            app.status(s.nothing_to_read);
            return;
        }
        self.paused_selection = next_sel;
        app.session.read_aloud.open = true;
        app.session.read_aloud.document = Some(doc_id);
        app.session.read_aloud.start(queue);
    }
}

/// Shows or hides the floating Read Aloud player window without stopping active speech.
pub fn toggle_read_aloud_window(app: &mut WordApp) -> bool {
    app.session.read_aloud.open = !app.session.read_aloud.open;
    app.session.read_aloud.open
}

/// Returns true when Read Aloud is actively speaking the currently visible document.
pub fn czytaj_is_playing(app: &WordApp) -> bool {
    app.session.read_aloud.state() == wordcraft_engine::speech::State::Playing && app.session.read_aloud.document == Some(app.session.document_id())
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

        // Two-finger pinch emits bounded Zoom + PointerMoved(centroid) while suppressing Primary pointer selection.
        let mut pinch = vec![
            touch(1, TouchPhase::Start, pos2(100.0, 200.0)),
            press(pos2(100.0, 200.0), true),
            touch(2, TouchPhase::Start, pos2(200.0, 200.0)),
            touch(2, TouchPhase::Move, pos2(220.0, 200.0)),
            touch(1, TouchPhase::End, pos2(100.0, 200.0)),
            touch(2, TouchPhase::End, pos2(220.0, 200.0)),
            press(pos2(220.0, 200.0), false),
        ];
        filter.process(&mut pinch, vp, false, false);
        assert!(!pinch.iter().any(|e| matches!(e, Event::PointerButton { .. })));
        let zooms: Vec<f32> = pinch.iter().filter_map(|e| if let Event::Zoom(z) = e { Some(*z) } else { None }).collect();
        assert_eq!(zooms.len(), 1);
        assert!((zooms[0] - 1.2).abs() < 0.01, "expected 1.20x zoom, got {zooms:?}");
        assert!(pinch.iter().any(|e| matches!(e, Event::PointerMoved(p) if (p.x - 160.0).abs() < 0.1)));

        // Near-coincident double touch cannot trigger a huge zoom spike.
        let mut spike = vec![
            touch(10, TouchPhase::Start, pos2(200.0, 200.0)),
            touch(11, TouchPhase::Start, pos2(201.0, 200.0)),
            touch(11, TouchPhase::Move, pos2(224.0, 200.0)),
            touch(10, TouchPhase::End, pos2(200.0, 200.0)),
            touch(11, TouchPhase::End, pos2(224.0, 200.0)),
        ];
        filter.process(&mut spike, vp, false, false);
        for ev in &spike {
            if let Event::Zoom(z) = ev {
                assert!(*z <= MAX_PINCH_STEP_RATIO && *z >= 1.0 / MAX_PINCH_STEP_RATIO, "unbounded pinch zoom: {z}");
            }
        }

        // Explicit select mode passes single-finger drag straight through.
        let mut sel = vec![touch(3, TouchPhase::Start, pos2(100.0, 200.0)), press(pos2(100.0, 200.0), true)];
        filter.process(&mut sel, vp, true, false);
        assert!(sel.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));
    }

    #[test]
    fn mobile_defaults_and_theme_cycle() {
        let mut app = test_app();
        apply_initial_mobile_defaults(&mut app, false);
        assert!(!app.ui.show_discord);
        assert!(!app.ui.start_screen);
        assert!(app.ui.ribbon_collapsed);
        assert!(app.ui.compact_chrome);
        assert_eq!(app.session.view.mode, ViewMode::Print);

        // Even with previously saved desktop prefs, Android forces community/Discord off.
        app.ui.show_discord = true;
        apply_initial_mobile_defaults(&mut app, true);
        assert!(!app.ui.show_discord);

        apply_initial_viewport_zoom(&mut app, 411.0);
        assert_eq!(app.session.view.fit, "pageWidth");

        let mut wide_app = test_app();
        apply_initial_mobile_defaults(&mut wide_app, false);
        apply_initial_viewport_zoom(&mut wide_app, 1280.0);
        assert!(wide_app.session.view.fit.is_empty());

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

    #[test]
    fn touch_filter_leaves_scrollbar_strip_untouched_and_allows_mouse_select() {
        let canvas_rect = Some(Rect::from_min_size(pos2(0.0, 50.0), vec2(400.0, 700.0)));
        let inner_vp = Some(Rect::from_min_size(pos2(0.0, 50.0), vec2(386.0, 686.0)));
        let pan_rect = document_pan_rect(inner_vp, canvas_rect);
        let mut filter = TouchGestureFilter::default();

        // Dragging UP along the right scrollbar strip (x=393, outside inner_vp) passes pointer events through cleanly.
        let mut sb_drag = vec![
            touch(1, TouchPhase::Start, pos2(393.0, 300.0)),
            Event::PointerMoved(pos2(393.0, 300.0)),
            press(pos2(393.0, 300.0), true),
            touch(1, TouchPhase::Move, pos2(393.0, 150.0)),
            Event::PointerMoved(pos2(393.0, 150.0)),
            touch(1, TouchPhase::End, pos2(393.0, 150.0)),
            press(pos2(393.0, 150.0), false),
        ];
        filter.process(&mut sb_drag, pan_rect, false, false);
        assert!(!sb_drag.iter().any(|e| matches!(e, Event::MouseWheel { .. })), "scrollbar drag must not be inverted into wheel");
        assert!(sb_drag.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));

        // When pointer_is_mouse is true (pass-through selection enabled), dragging inside viewport stays primary drag.
        let mut mouse_drag = vec![
            touch(2, TouchPhase::Start, pos2(120.0, 200.0)),
            press(pos2(120.0, 200.0), true),
            touch(2, TouchPhase::Move, pos2(180.0, 200.0)),
            Event::PointerMoved(pos2(180.0, 200.0)),
            touch(2, TouchPhase::End, pos2(180.0, 200.0)),
            press(pos2(180.0, 200.0), false),
        ];
        filter.process(&mut mouse_drag, pan_rect, true, false);
        assert!(!mouse_drag.iter().any(|e| matches!(e, Event::MouseWheel { .. })));
        assert!(mouse_drag.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));
    }

    #[test]
    fn wheel_and_hover_conversions_clamp_and_orient() {
        assert!(wheel_to_egui_event(f32::NAN, 1.0).is_none());
        assert!(wheel_to_egui_event(0.0, 0.0).is_none());
        assert_eq!(
            wheel_to_egui_event(1.5, 2.0),
            Some(Event::MouseWheel {
                unit: MouseWheelUnit::Line,
                delta: vec2(-1.5, 2.0),
                phase: TouchPhase::Move,
                modifiers: Modifiers::NONE,
                source: MouseWheelSource::Wheel,
            })
        );
        assert_eq!(
            wheel_to_egui_event(0.0, -500.0),
            Some(Event::MouseWheel {
                unit: MouseWheelUnit::Line,
                delta: vec2(0.0, -64.0),
                phase: TouchPhase::Move,
                modifiers: Modifiers::NONE,
                source: MouseWheelSource::Wheel,
            })
        );

        assert!(hover_to_egui_event(100.0, 200.0, 0.0).is_none());
        assert!(hover_to_egui_event(f32::INFINITY, 200.0, 2.0).is_none());
        assert_eq!(hover_to_egui_event(300.0, 450.0, 2.5), Some(Event::PointerMoved(pos2(120.0, 180.0))));

        assert!(effective_ime_wanted(true, false));
        assert!(!effective_ime_wanted(true, true));
        assert!(!effective_ime_wanted(false, false));
        assert!(!effective_ime_wanted(false, true));
    }

    #[test]
    fn czytaj_reads_from_start_remembers_pause_and_supports_selection() {
        use wordcraft_engine::speech::{Backend, State};

        let mut app = test_app();
        app.session.read_aloud.backend = Backend::Hold;
        let _ = app.execute("text.insert", json!({ "text": "Pierwsze zdanie. Drugie zdanie. Trzecie zdanie." }));
        let _ = app.execute("cursor.move", json!({ "target": "end" }));

        let mut czytaj = CzytajController::default();

        // Starts from beginning even though caret is at the end (no selection).
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Playing);
        assert!(czytaj_is_playing(&app));
        assert_eq!(app.session.read_aloud.progress(), (0, 3));
        assert_eq!(app.session.read_aloud.current().map(|u| u.text).as_deref(), Some("Pierwsze zdanie."));

        // Advance to second sentence, pause, and confirm resume continues from sentence 2.
        let _ = app.execute("readAloud.next", json!({}));
        assert_eq!(app.session.read_aloud.progress(), (1, 3));
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Paused);
        assert!(!czytaj_is_playing(&app));

        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Playing);
        assert_eq!(app.session.read_aloud.progress(), (1, 3));
        assert_eq!(app.session.read_aloud.current().map(|u| u.text).as_deref(), Some("Drugie zdanie."));

        // Pause, select only "Trzecie zdanie.", toggle starts reading that selection cleanly.
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Paused);
        let _ = app.execute("select.text", json!({ "text": "Trzecie zdanie." }));
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Playing);
        assert_eq!(app.session.read_aloud.progress(), (0, 1));
        assert_eq!(app.session.read_aloud.current().map(|u| u.text).as_deref(), Some("Trzecie zdanie."));

        // Toggling pause then resume with the same selection still active resumes instead of restarting.
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Paused);
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Playing);
        assert_eq!(app.session.read_aloud.current().map(|u| u.text).as_deref(), Some("Trzecie zdanie."));
    }

    #[test]
    fn mobile_strings_english_set_is_consistent() {
        let en = mobile_strings("en");
        assert_eq!((en.open, en.new_doc, en.save, en.undo, en.redo), ("Open", "New", "Save", "Undo", "Redo"));
        assert_eq!((en.select, en.keyboard, en.page_view, en.read_aloud, en.ribbon, en.lock_keyboard), ("Sel", "KB", "Page", "Read", "Rib", "Lock"),);
        assert_eq!(AndroidThemeMode::Light.short_label_for(&en), "Light");
        assert_eq!(AndroidThemeMode::Dark.short_label_for(&en), "Dark");
        assert_eq!(en.format_status(1, 3, 42), "P1/3 · 42w");
    }

    #[test]
    fn mobile_strings_polish_set_is_consistent() {
        for code in ["pl", "pl-PL", "PL_pl"] {
            let pl = mobile_strings(code);
            assert_eq!((pl.open, pl.new_doc, pl.save, pl.undo, pl.redo), ("Otwórz", "Nowy", "Zapisz", "Cofnij", "Ponów"));
            assert_eq!(
                (pl.select, pl.keyboard, pl.page_view, pl.read_aloud, pl.ribbon, pl.lock_keyboard),
                ("Zazn", "Klaw", "Dok", "Czytaj", "Wst", "Blok"),
            );
            assert_ne!(pl.keyboard, pl.lock_keyboard);
            assert_eq!(AndroidThemeMode::Light.short_label_for(&pl), "Jasny");
            assert_eq!(AndroidThemeMode::Dark.short_label_for(&pl), "Ciemny");
            assert_eq!(pl.format_status(2, 5, 19), "S2/5 · 19 sł.");
        }
    }

    #[test]
    fn mobile_strings_unsupported_locales_fall_back_to_english() {
        let en = mobile_strings("en");
        assert_eq!(mobile_strings("de"), en);
        assert_eq!(mobile_strings("ja"), en);
        assert_eq!(mobile_strings(""), en);
        assert!(!en.select_on_hint.is_empty() && !en.select_off_hint.is_empty());
        let pl = mobile_strings("pl");
        assert!(!pl.select_on_hint.is_empty() && !pl.select_off_hint.is_empty());
        assert_ne!(pl.select_on_hint, en.select_on_hint);
    }

    #[test]
    fn step_utf8_by_chars_handles_polish_and_emoji() {
        let text = "😊 Może być";
        let end = text.len();
        assert_eq!(step_utf8_by_chars(text, end, -3), "😊 Może ".len());
        assert_eq!(step_utf8_by_chars(text, 0, 2), "😊 ".len());
        assert_eq!(step_utf8_by_chars(text, 0, 99), end);
        assert_eq!(step_utf8_by_chars(text, end, -99), 0);
        // Unaligned interior byte rounds down safely without panicking.
        assert_eq!(step_utf8_by_chars(text, 2, 1), "😊".len());
    }

    #[test]
    fn ime_frame_slice_splits_pre_and_post_moves_in_order() {
        let mut queue = vec![
            QueuedInput { item: AndroidInput::SetSelectionRelative { delta_anchor_cp: -4, delta_focus_cp: 0 }, seq: 1 },
            QueuedInput { item: AndroidInput::Commit("Może".into()), seq: 1 },
            QueuedInput { item: AndroidInput::SetSelectionRelative { delta_anchor_cp: -2, delta_focus_cp: -2 }, seq: 2 },
            QueuedInput { item: AndroidInput::Commit("!".into()), seq: 3 },
        ];
        let first = take_ime_frame_slice(&mut queue, 0);
        assert_eq!(first.pre_moves, vec![(-4, 0)]);
        assert_eq!(first.events, vec![AndroidInput::Commit("Może".into())]);
        assert_eq!(first.post_moves, vec![(-2, -2)]);
        assert_eq!(first.acked_seq, 2);
        assert!(first.has_more);

        let second = take_ime_frame_slice(&mut queue, first.acked_seq);
        assert!(second.pre_moves.is_empty());
        assert_eq!(second.events, vec![AndroidInput::Commit("!".into())]);
        assert!(second.post_moves.is_empty());
        assert_eq!(second.acked_seq, 3);
        assert!(!second.has_more);
    }

    #[test]
    fn touch_filter_select_mode_drags_without_scroll_or_tap_keyboard() {
        let vp = Some(Rect::from_min_size(pos2(0.0, 50.0), vec2(400.0, 700.0)));
        let mut filter = TouchGestureFilter::default();

        let mut frame1 =
            vec![touch(5, TouchPhase::Start, pos2(100.0, 200.0)), Event::PointerMoved(pos2(100.0, 200.0)), press(pos2(100.0, 200.0), true)];
        let o1 = filter.process(&mut frame1, vp, true, false);
        assert!(!o1.tapped_canvas);
        assert!(!frame1.iter().any(|e| matches!(e, Event::MouseWheel { .. })));
        assert!(frame1.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));

        let mut frame2 = vec![touch(5, TouchPhase::Move, pos2(220.0, 240.0)), Event::PointerMoved(pos2(220.0, 240.0))];
        let o2 = filter.process(&mut frame2, vp, true, false);
        assert!(!o2.tapped_canvas);
        assert!(!frame2.iter().any(|e| matches!(e, Event::MouseWheel { .. })));
        assert!(frame2.iter().any(|e| matches!(e, Event::PointerMoved(p) if *p == pos2(220.0, 240.0))));

        let mut frame3 = vec![touch(5, TouchPhase::End, pos2(220.0, 240.0)), press(pos2(220.0, 240.0), false), Event::PointerGone];
        let o3 = filter.process(&mut frame3, vp, true, false);
        assert!(!o3.tapped_canvas);
        assert!(frame3.iter().any(|e| matches!(e, Event::PointerButton { pressed: false, .. })));

        // Two-finger pinch still zooms cleanly even while select_mode is active.
        let mut pinch = vec![
            touch(6, TouchPhase::Start, pos2(100.0, 200.0)),
            touch(7, TouchPhase::Start, pos2(200.0, 200.0)),
            touch(7, TouchPhase::Move, pos2(230.0, 200.0)),
            touch(6, TouchPhase::End, pos2(100.0, 200.0)),
            touch(7, TouchPhase::End, pos2(230.0, 200.0)),
        ];
        filter.process(&mut pinch, vp, true, false);
        assert!(pinch.iter().any(|e| matches!(e, Event::Zoom(_))));
    }

    #[test]
    fn touch_filter_passes_overlay_window_drag_through_without_scrolling() {
        let vp = Some(Rect::from_min_size(pos2(0.0, 50.0), vec2(400.0, 700.0)));
        let overlay = [Rect::from_min_max(pos2(150.0, 100.0), pos2(390.0, 250.0))];
        let mut filter = TouchGestureFilter::default();

        // Drag starting inside the floating Read Aloud window must move the window, not scroll canvas,
        // even as the finger moves outside the initial window rectangle.
        let mut start =
            vec![touch(1, TouchPhase::Start, pos2(160.0, 120.0)), Event::PointerMoved(pos2(160.0, 120.0)), press(pos2(160.0, 120.0), true)];
        let o1 = filter.process_with_overlays(&mut start, vp, &overlay, false, false);
        assert!(!o1.tapped_canvas);
        assert!(start.iter().any(|e| matches!(e, Event::PointerButton { pressed: true, .. })));

        let mut drag = vec![touch(1, TouchPhase::Move, pos2(60.0, 320.0)), Event::PointerMoved(pos2(60.0, 320.0))];
        let _ = filter.process_with_overlays(&mut drag, vp, &overlay, false, false);
        assert!(!drag.iter().any(|e| matches!(e, Event::MouseWheel { .. })), "overlay drag must not scroll canvas");
        assert!(drag.iter().any(|e| matches!(e, Event::PointerMoved(p) if *p == pos2(60.0, 320.0))));

        let mut end = vec![touch(1, TouchPhase::End, pos2(60.0, 320.0)), press(pos2(60.0, 320.0), false), Event::PointerGone];
        let o3 = filter.process_with_overlays(&mut end, vp, &overlay, false, false);
        assert!(!o3.tapped_canvas);

        // Quick tap inside overlay must not request the soft keyboard as a canvas tap.
        let mut tap_start = vec![touch(2, TouchPhase::Start, pos2(200.0, 150.0)), press(pos2(200.0, 150.0), true)];
        filter.process_with_overlays(&mut tap_start, vp, &overlay, false, false);
        let mut tap_end = vec![touch(2, TouchPhase::End, pos2(201.0, 150.0)), press(pos2(201.0, 150.0), false)];
        let tap_out = filter.process_with_overlays(&mut tap_end, vp, &overlay, false, false);
        assert!(!tap_out.tapped_canvas);

        // Dragging outside the overlay still pans the canvas normally.
        let mut canvas_drag = vec![
            touch(3, TouchPhase::Start, pos2(40.0, 400.0)),
            press(pos2(40.0, 400.0), true),
            touch(3, TouchPhase::Move, pos2(40.0, 330.0)),
            touch(3, TouchPhase::End, pos2(40.0, 330.0)),
        ];
        filter.process_with_overlays(&mut canvas_drag, vp, &overlay, false, false);
        assert!(canvas_drag.iter().any(|e| matches!(e, Event::MouseWheel { delta, .. } if delta.y < -50.0)));
    }

    #[test]
    fn toggle_read_aloud_window_hides_and_restores_without_stopping_speech() {
        use wordcraft_engine::speech::{Backend, State};

        let mut app = test_app();
        app.session.read_aloud.backend = Backend::Hold;
        let _ = app.execute("text.insert", json!({ "text": "Pierwsze zdanie. Drugie zdanie." }));
        let _ = app.execute("cursor.move", json!({ "target": "start" }));

        let mut czytaj = CzytajController::default();
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Playing);
        assert!(app.session.read_aloud.open);

        // Hide player window while speech stays playing.
        assert!(!toggle_read_aloud_window(&mut app));
        assert!(!app.session.read_aloud.open);
        assert_eq!(app.session.read_aloud.state(), State::Playing);

        // Pausing and resuming via Czytaj keeps the window hidden until explicitly shown again.
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Paused);
        assert!(!app.session.read_aloud.open);
        czytaj.toggle(&mut app);
        assert_eq!(app.session.read_aloud.state(), State::Playing);
        assert!(!app.session.read_aloud.open);

        // Toggle back visible.
        assert!(toggle_read_aloud_window(&mut app));
        assert!(app.session.read_aloud.open);
    }
}
