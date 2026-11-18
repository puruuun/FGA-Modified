//! JNI entry points for `io.github.fate_grand_automata.qa.NativeProbe` (device-test code only).
//! Each one only runs a probe through the panic guard and converts the text to a Java string.

use jni::JNIEnv;
use jni::objects::JObject;
use jni::sys::jstring;

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
