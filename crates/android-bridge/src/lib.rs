//! WordCraft Android JNI Bridge
//!
//! Provides a safe, crash-proof JNI interface between the Rust WordCraft
//! engine and Android Kotlin / Jetpack Compose.

#![allow(unsafe_code)]

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong, jstring};
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
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_initBridge<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        // Optional logger or locale init
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_one_lmk_wordcraft_bridge_WordCraftBridge_createSession<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    doc_bytes: JByteArray<'local>,
) -> jlong {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let region = Region {
            language: "pl".into(),
            territory: "PL".into(),
            letter: Some(false),
            inches: Some(false),
        };

        let is_null = doc_bytes.is_null();
        let session = if is_null {
            Session::new(wordcraft_engine::blank_document(&region))
        } else {
            match env.convert_byte_array(&doc_bytes) {
                Ok(bytes) if !bytes.is_empty() => match wordcraft_docx::read(&bytes) {
                    Ok(doc) => Session::new(doc),
                    Err(_) => Session::new(wordcraft_engine::blank_document(&region)),
                },
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
        if let Some(page) = layout.pages.get(page_idx as usize) {
            page.w as jfloat
        } else {
            0.0
        }
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
        if let Some(page) = layout.pages.get(page_idx as usize) {
            page.h as jfloat
        } else {
            0.0
        }
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

        let mut opts = RenderOptions::default();
        opts.dark = dark_mode != 0;

        let rendered = render_page(&session.doc, page, scale.max(0.1), &opts);
        Some(rendered.pixels)
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
    let text = with_session(handle, |session| {
        session.doc.plain_text(StoryRef::Body)
    });

    match text {
        Some(txt) => match env.new_string(txt) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}
