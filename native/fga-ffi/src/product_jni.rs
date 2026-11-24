//! JNI entry points for `io.github.fate_grand_automata.sensing.RustSensing`, the product binding
//! of issue #568 (see [`crate::score_engine`]). Each export only hands its Java arguments to an
//! `*_entry` function, which reads them inside the panic guard, and then turns the answer into a
//! Java value after clearing any pending exception.

use jni::JNIEnv;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jint, jintArray, jstring};

use crate::jni_exports::to_java;
use crate::score_engine::{self, wire};

/// As `to_java` for the probes: a pending exception (for example an OutOfMemoryError from the copy
/// of the pixels) is cleared before any further JNI call, and the reply then says so instead of
/// carrying a read. Null reaches Kotlin only when the exception cannot be cleared or the array
/// cannot be built; the Kotlin side treats null as an engine error.
fn to_java_ints(env: &mut JNIEnv, reply: Vec<i32>) -> jintArray {
    let reply = match env.exception_check() {
        Ok(false) => reply,
        Ok(true) | Err(_) => {
            if env.exception_clear().is_err() {
                return std::ptr::null_mut();
            }
            wire::after_pending_exception(&reply)
        }
    };
    let Ok(length) = i32::try_from(reply.len()) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_int_array(length) else {
        return std::ptr::null_mut();
    };
    if env.set_int_array_region(&array, 0, &reply).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

fn to_java_code(env: &mut JNIEnv, code: i32) -> jint {
    match env.exception_check() {
        Ok(false) => code,
        Ok(true) | Err(_) => {
            // Even if clearing fails, the answer must not look like a normal one.
            let _ = env.exception_clear();
            score_engine::after_pending_exception_code(code)
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_sensing_RustSensing_load<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    model_path: JString<'local>,
) -> jstring {
    let text = score_engine::load_entry(|| {
        env.get_string(&model_path)
            .map(String::from)
            .map_err(|e| format!("modelPath: {e}"))
    });
    to_java(&mut env, text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_sensing_RustSensing_readScore<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    pixels: JByteArray<'local>,
    width: jint,
    height: jint,
) -> jintArray {
    let reply = score_engine::read_entry(
        |expected| {
            // The length is checked before the copy, so a huge array is refused, not allocated.
            let length = env
                .get_array_length(&pixels)
                .map_err(|e| format!("pixels: {e}"))?;
            if usize::try_from(length).ok() != Some(expected) {
                return Err(format!(
                    "pixels: array of {length} bytes, expected {expected}"
                ));
            }
            env.convert_byte_array(&pixels)
                .map_err(|e| format!("pixels: {e}"))
        },
        width,
        height,
    );
    to_java_ints(&mut env, reply)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_sensing_RustSensing_unload<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jint {
    let code = score_engine::unload_entry();
    to_java_code(&mut env, code)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_sensing_RustSensing_isLoaded<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jint {
    let code = score_engine::is_loaded_entry();
    to_java_code(&mut env, code)
}
