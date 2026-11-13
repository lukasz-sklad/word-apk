//! WordCraft Android application crate (`cdylib` loaded by `WordCraftActivity`).
//!
//! Pairs `wordcraft_ui_egui::WordApp` with `eframe`, `wgpu`, and `android-activity`'s
//! `GameActivity` backend, plus Android-specific IME composition, Pixel display-cutout inset
//! protection, touch scroll/pinch gestures, and Storage Access Framework (SAF) document I/O.

#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "android")]
mod ime;
pub mod mobile;

#[cfg(target_os = "android")]
pub use android::android_main;
