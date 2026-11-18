//! JNI entry points for `io.github.fate_grand_automata.qa.NativeProbe` (device-test code only).
//! Each one only runs a probe through the panic guard and converts the text to a Java string.

use jni::JNIEnv;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jint, jstring};

use crate::guard::run_guarded;
use crate::probes;

/// A null return reaches Kotlin as an exception at the call site, which a test reports; it is
/// the only way left once the JVM cannot allocate the string.
fn to_java(env: &mut JNIEnv, text: String) -> jstring {
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
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

/// Reading the Java arguments can fail (a null string, a pending exception), so it happens inside
/// the guard and comes back as `ERR` like any other failure.
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
