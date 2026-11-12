//! WordCraft Android JNI Bridge
//!
//! Provides a safe, crash-proof JNI interface between the Rust WordCraft
//! engine and Android Kotlin / Jetpack Compose.

#![allow(unsafe_code)]

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jfloatArray, jint, jlong, jstring};
use serde_json::Value;

use wordcraft_doc::StoryRef;
use wordcraft_engine::Session;
use wordcraft_geom::Region;
use wordcraft_render::{RenderOptions, render_page};

/// Safe helper to access the Session pointer.
fn with_session<F, R>(handle: jlong, f: F) -> Option<R>
where
    F: FnOnce(&mut Session) -> R,
{
    if handle == 0 {
        return None;
    }
    let res = catch_unwind(AssertUnwindSafe(|| {
        let session_ptr = handle as *mut Session;
        // SAFETY: The pointer was created by `Box::into_raw` in `createSession`
        // and is only dereferenced here while held by the Java side.
        let session = unsafe { &mut *session_ptr };
        f(session)
    }));
    res.ok()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_initBridge<'local>(_env: JNIEnv<'local>, _class: JClass<'local>) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // Optional logger or locale init
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_createSession<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    doc_bytes: JByteArray<'local>,
    filename: JString<'local>,
) -> jlong {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let region = Region { language: "pl".into(), territory: "PL".into(), letter: Some(false), inches: Some(false) };

        let fname: Option<String> = if !filename.is_null() { env.get_string(&filename).ok().map(|s| s.into()) } else { None };

        let session = if doc_bytes.is_null() {
            Session::new(wordcraft_engine::blank_document(&region))
        } else {
            match env.convert_byte_array(&doc_bytes) {
                Ok(bytes) if !bytes.is_empty() => {
                    let ext = fname.as_deref().and_then(|f| f.rsplit('.').next()).unwrap_or("");
                    let doc = match ext.to_ascii_lowercase().as_str() {
                        "docx" => wordcraft_docx::read(&bytes).ok().or_else(|| wordcraft_formats::import("odt", &bytes).and_then(Result::ok)),
                        "odt" | "ott" => wordcraft_formats::import("odt", &bytes).and_then(Result::ok).or_else(|| wordcraft_docx::read(&bytes).ok()),
                        "rtf" => wordcraft_formats::import("rtf", &bytes).and_then(Result::ok),
                        "md" | "markdown" => wordcraft_formats::import("md", &bytes).and_then(Result::ok),
                        "html" | "htm" => wordcraft_formats::import("html", &bytes).and_then(Result::ok),
                        "txt" | "text" => wordcraft_formats::import("txt", &bytes).and_then(Result::ok),
                        _ => {
                            if bytes.starts_with(b"PK\x03\x04") {
                                wordcraft_docx::read(&bytes).ok().or_else(|| wordcraft_formats::import("odt", &bytes).and_then(Result::ok))
                            } else if bytes.starts_with(b"{\\rtf") {
                                wordcraft_formats::import("rtf", &bytes).and_then(Result::ok)
                            } else {
                                wordcraft_formats::import("txt", &bytes).and_then(Result::ok)
                            }
                        }
                    };
                    match doc {
                        Some(d) => Session::new(d),
                        None => Session::new(wordcraft_engine::blank_document(&region)),
                    }
                }
                _ => Session::new(wordcraft_engine::blank_document(&region)),
            }
        };

        let boxed = Box::new(session);
        Box::into_raw(boxed) as jlong
    }));

    res.unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_destroySession<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let session_ptr = handle as *mut Session;
        // SAFETY: Takes back ownership of the pointer created in `createSession`.
        unsafe {
            drop(Box::from_raw(session_ptr));
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_getPageCount<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    with_session(handle, |session| {
        let layout = session.layout();
        layout.pages.len() as jint
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_getPageWidth<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_idx: jint,
) -> jfloat {
    with_session(handle, |session| {
        let layout = session.layout();
        if let Some(page) = layout.pages.get(page_idx as usize) { page.w as jfloat } else { 0.0 }
    })
    .unwrap_or(0.0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_getPageHeight<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_idx: jint,
) -> jfloat {
    with_session(handle, |session| {
        let layout = session.layout();
        if let Some(page) = layout.pages.get(page_idx as usize) { page.h as jfloat } else { 0.0 }
    })
    .unwrap_or(0.0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_renderPage<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_idx: jint,
    scale: jfloat,
    dark_mode: jboolean,
) -> jbyteArray {
    let rendered_bytes: Option<Vec<u8>> = with_session(handle, |session| {
        let layout = session.layout();
        let page = layout.pages.get(page_idx as usize)?;

        let opts = RenderOptions { dark: dark_mode != 0, ..Default::default() };

        let rendered = render_page(&session.doc, page, scale.max(0.1), &opts);
        let straight = rendered.to_straight();
        let mut out = Vec::with_capacity(8 + straight.len());
        out.extend_from_slice(&rendered.width.to_le_bytes());
        out.extend_from_slice(&rendered.height.to_le_bytes());
        out.extend_from_slice(&straight);
        Some(out)
    })
    .flatten();

    match rendered_bytes {
        Some(data) => match env.byte_array_from_slice(&data) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_getCaret<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jfloatArray {
    let caret_coords: Option<[f32; 4]> = with_session(handle, |session| {
        let layout = session.layout();
        let caret = layout.caret_on(&session.sel.focus, session.page_hint)?;
        Some([caret.page as f32, caret.x, caret.top, caret.height])
    })
    .flatten();

    match caret_coords {
        Some(coords) => match env.new_float_array(4) {
            Ok(arr) => {
                let _ = env.set_float_array_region(&arr, 0, &coords);
                arr.into_raw()
            }
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_setCaret<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_idx: jint,
    x: jfloat,
    y: jfloat,
) {
    let _ = with_session(handle, |session| {
        let _ = session.run(
            "caret.set",
            &serde_json::json!({
                "page": page_idx,
                "x": x,
                "y": y
            }),
        );
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_insertText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    text: JString<'local>,
) {
    if text.is_null() {
        return;
    }
    let s: String = match env.get_string(&text) {
        Ok(jstr) => jstr.into(),
        Err(_) => return,
    };
    let _ = with_session(handle, |session| {
        let _ = session.run("text.insert", &serde_json::json!({ "text": s }));
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_deleteBackward<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = with_session(handle, |session| {
        let _ = session.run("edit.backspace", &serde_json::json!({}));
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_insertParagraph<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let _ = with_session(handle, |session| {
        let _ = session.run("edit.enter", &serde_json::json!({}));
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_runCommand<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    cmd_id: JString<'local>,
    params_json: JString<'local>,
) -> jstring {
    let id_str: String = match env.get_string(&cmd_id) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let params_val: Value = if params_json.is_null() {
        Value::Null
    } else {
        match env.get_string(&params_json) {
            Ok(s) => serde_json::from_str(&s.to_string_lossy()).unwrap_or(Value::Null),
            Err(_) => Value::Null,
        }
    };

    let result_json = with_session(handle, |session| match session.run(&id_str, &params_val) {
        Ok(v) => serde_json::to_string(&v).unwrap_or_else(|_| "{}".to_string()),
        Err(e) => format!("{{\"error\":\"{}\"}}", e),
    });

    match result_json {
        Some(json) => match env.new_string(json) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_saveDocx<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    let bytes: Option<Vec<u8>> = with_session(handle, |session| wordcraft_docx::write(&session.doc).ok()).flatten();

    match bytes {
        Some(data) => match env.byte_array_from_slice(&data) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_getPlainText<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let text = with_session(handle, |session| session.doc.plain_text(StoryRef::Body));

    match text {
        Some(txt) => match env.new_string(txt) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}
