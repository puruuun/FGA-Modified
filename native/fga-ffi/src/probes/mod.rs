//! Stage 1-0 probes. Each returns plain text so the JNI layer only converts, and each also runs
//! as a host test.

mod compare;
mod cpu;
mod crypto;
mod hex;
mod inference;
mod ocr;
#[cfg(feature = "ort-probe")]
mod ocr_ort;
mod support_score;
mod tls;

pub use cpu::cpu_probe;
#[cfg(feature = "test-hooks")]
pub use ocr::poison_lock_for_tests;
pub use ocr::{
    NOT_LOADED, THREADS_NOT_BUILT, TRACT_THREADS_BUILT, memory_status, ocr_load, ocr_load_tract,
    ocr_read, ocr_tract_features, ocr_unload, tract_settings,
};
#[cfg(all(feature = "ort-probe", feature = "test-hooks"))]
pub use ocr_ort::poison_ort_lock_for_tests;
#[cfg(feature = "ort-probe")]
pub use ocr_ort::{ORT_NOT_LOADED, ocr_load_ort, ocr_read_ort, ocr_unload_ort};
pub use support_score::support_score_read;

pub fn ping() -> Result<String, String> {
    Ok(format!(
        "fga-ffi {} on {}",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::ARCH
    ))
}

/// Proves the panic guard on the device: the caller must see an error, not a crash.
#[expect(clippy::panic, reason = "this probe exists to panic")]
pub fn panic_probe() -> Result<String, String> {
    panic!("deliberate panic from panicProbe")
}

pub fn ring_probe() -> Result<String, String> {
    crypto::ring_checks()
}

pub fn rustls_probe() -> Result<String, String> {
    tls::handshake_in_memory()
}

pub fn tract_probe() -> Result<String, String> {
    inference::tiny_model()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::run_guarded;

    #[test]
    fn ping_names_version_and_arch() {
        let text = run_guarded(ping);
        assert!(text.starts_with("OK: fga-ffi 0.1.0 on "), "{text}");
        assert!(text.ends_with(std::env::consts::ARCH), "{text}");
    }

    #[test]
    fn panic_probe_comes_back_as_error() {
        assert_eq!(
            run_guarded(panic_probe),
            "ERR: panic: deliberate panic from panicProbe"
        );
    }

    #[test]
    fn ring_probe_passes() {
        let text = run_guarded(ring_probe);
        assert!(text.starts_with("OK: "), "{text}");
    }

    #[test]
    fn rustls_probe_passes() {
        let text = run_guarded(rustls_probe);
        assert!(text.starts_with("OK: "), "{text}");
    }

    #[test]
    fn tract_probe_passes() {
        let text = run_guarded(tract_probe);
        assert!(text.starts_with("OK: "), "{text}");
    }

    #[test]
    fn cpu_probe_reports_arch() {
        let text = run_guarded(cpu_probe);
        assert!(text.starts_with("OK: arch="), "{text}");
    }
}
