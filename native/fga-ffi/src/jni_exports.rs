//! JNI entry points for `io.github.fate_grand_automata.qa.NativeProbe` (device-test code only).
//! Each one only runs a probe through the panic guard and converts the text to a Java string.

use jni::JNIEnv;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jint, jstring};

use crate::guard::run_guarded;
use crate::probes;

/// A failed `get_string` or `convert_byte_array` (for example an OutOfMemoryError) returns with
/// the Java exception still pending. jni 0.21's `new_string` calls NewStringUTF without looking,
/// and with a pending exception that aborts the process under CheckJNI (debuggable builds) or
/// returns null. So the exception is cleared first and the result says so.
///
/// A null return reaches Kotlin as an exception at the call site, which a test reports; it is
/// the only way left once the exception cannot be cleared or the JVM cannot allocate the string.
pub(crate) fn to_java(env: &mut JNIEnv, text: String) -> jstring {
    let text = match env.exception_check() {
        Ok(false) => text,
        // An Err cannot say whether one is pending; clearing when none is is harmless.
        Ok(true) | Err(_) => {
            // If clearing fails the exception may still be pending, and NewStringUTF must not run.
            if env.exception_clear().is_err() {
                return std::ptr::null_mut();
            }
            after_pending_exception(&text)
        }
    };
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// The probe's own text is kept, since it usually says which argument failed.
fn after_pending_exception(text: &str) -> String {
    format!("ERR: a Java exception was pending and has been cleared ({text})")
}

macro_rules! export_probe {
    ($symbol:ident, $probe:path) => {
        #[unsafe(no_mangle)]
        pub extern "system" fn $symbol<'local>(
            mut env: JNIEnv<'local>,
            _this: JObject<'local>,
        ) -> jstring {
            let text = run_guarded($probe);
            to_java(&mut env, text)
        }
    };
}

export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_ping,
    probes::ping
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_panicProbe,
    probes::panic_probe
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_ringProbe,
    probes::ring_probe
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_rustlsProbe,
    probes::rustls_probe
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_tractProbe,
    probes::tract_probe
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_cpuProbe,
    probes::cpu_probe
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrUnload,
    probes::ocr_unload
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_memoryStatus,
    probes::memory_status
);
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrTractFeatures,
    probes::ocr_tract_features
);

/// Reading the Java arguments can fail, so it happens inside the guard and comes back as `ERR`. A
/// null argument fails before any JNI call and leaves no exception; a JNI failure leaves one
/// pending, which `to_java` clears and names in the text.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrLoad<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    model_path: JString<'local>,
) -> jstring {
    let text = run_guarded(|| {
        let path: String = env
            .get_string(&model_path)
            .map_err(|e| format!("modelPath: {e}"))?
            .into();
        probes::ocr_load(&path)
    });
    to_java(&mut env, text)
}

/// Issue #549: `ocrLoad` with a minimum model width and a tract thread count. The numbers are
/// checked inside the probe before its lock, after the path has been read here.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrLoadTract<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    model_path: JString<'local>,
    min_width: jint,
    threads: jint,
) -> jstring {
    let text = run_guarded(|| {
        let path: String = env
            .get_string(&model_path)
            .map_err(|e| format!("modelPath: {e}"))?
            .into();
        probes::ocr_load_tract(&path, min_width, threads)
    });
    to_java(&mut env, text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrRead<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    pixels: JByteArray<'local>,
    width: jint,
    height: jint,
    allowed: JString<'local>,
) -> jstring {
    let text = run_guarded(|| {
        // Test-only probe: a product function checks `get_array_length` against the crop limits
        // before copying, so a huge array is refused instead of allocated.
        let pixels = env
            .convert_byte_array(&pixels)
            .map_err(|e| format!("pixels: {e}"))?;
        let allowed: String = env
            .get_string(&allowed)
            .map_err(|e| format!("allowed: {e}"))?
            .into();
        probes::ocr_read(pixels, width, height, &allowed)
    });
    to_java(&mut env, text)
}

/// Issue #555: the support-score reader on the recogniser that `ocrLoad` or `ocrLoadTract` loaded,
/// so it reads at whichever model width that load planned. Only the pixels are read from Java,
/// inside the guard; the size is bounded inside the probe before its lock.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_qa_NativeProbe_supportScoreRead<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    pixels: JByteArray<'local>,
    width: jint,
    height: jint,
) -> jstring {
    let text = run_guarded(|| {
        // Test-only probe, as ocrRead: the copy is not bounded.
        let pixels = env
            .convert_byte_array(&pixels)
            .map_err(|e| format!("pixels: {e}"))?;
        probes::support_score_read(pixels, width, height)
    });
    to_java(&mut env, text)
}

// Issue #535: the ONNX Runtime twins of the three functions above, only in a library built with
// `ort-probe`. Without it the symbols are absent and Kotlin gets UnsatisfiedLinkError, which the
// ocrProbe suite reports as a skipped case, so the default library stays as it was.
#[cfg(feature = "ort-probe")]
export_probe!(
    Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrUnloadOrt,
    probes::ocr_unload_ort
);

#[cfg(feature = "ort-probe")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrLoadOrt<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    model_path: JString<'local>,
    threads: jint,
) -> jstring {
    let text = run_guarded(|| {
        let path: String = env
            .get_string(&model_path)
            .map_err(|e| format!("modelPath: {e}"))?
            .into();
        probes::ocr_load_ort(&path, threads)
    });
    to_java(&mut env, text)
}

#[cfg(feature = "ort-probe")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_fate_1grand_1automata_qa_NativeProbe_ocrReadOrt<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    pixels: JByteArray<'local>,
    width: jint,
    height: jint,
    allowed: JString<'local>,
) -> jstring {
    let text = run_guarded(|| {
        // Test-only probe, as ocrRead: the copy is not bounded.
        let pixels = env
            .convert_byte_array(&pixels)
            .map_err(|e| format!("pixels: {e}"))?;
        let allowed: String = env
            .get_string(&allowed)
            .map_err(|e| format!("allowed: {e}"))?
            .into();
        probes::ocr_read_ort(pixels, width, height, &allowed)
    });
    to_java(&mut env, text)
}

// Clearing a pending exception needs a JVM, which host tests do not have. The device suite
// nativeProbe covers the null-argument path (NativeProbeTest.ocrLoadNullPath).
#[cfg(test)]
mod tests {
    use super::after_pending_exception;

    #[test]
    fn pending_exception_turns_any_result_into_an_error() {
        assert_eq!(
            after_pending_exception("ERR: pixels: Java exception was thrown"),
            "ERR: a Java exception was pending and has been cleared (ERR: pixels: Java exception was thrown)"
        );
        assert!(after_pending_exception("OK: {}").starts_with("ERR: "));
    }
}
