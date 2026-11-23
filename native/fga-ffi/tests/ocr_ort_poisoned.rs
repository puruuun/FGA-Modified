//! After a panic while the ONNX Runtime recogniser lock was held, every ONNX Runtime probe works
//! again, as ocr_poisoned.rs checks for tract, on the same stand-in model. Its own test binary,
//! because poisoning is process-wide.

#[cfg(all(test, feature = "ort-probe"))]
mod common;

#[cfg(all(test, feature = "ort-probe"))]
mod poisoned {
    use super::common::onnx;
    use fga_ffi::probes::{
        ORT_NOT_LOADED, ocr_load_ort, ocr_read_ort, ocr_unload, ocr_unload_ort,
        poison_ort_lock_for_tests,
    };
    use fga_ffi::run_guarded;

    const DROPPED: &str = "OK: {\"dropped\":true}";
    const NOTHING: &str = "OK: {\"dropped\":false}";

    fn poison() {
        assert_eq!(
            run_guarded(poison_ort_lock_for_tests),
            "ERR: panic: poisoning the ONNX Runtime recogniser lock"
        );
    }

    #[test]
    fn every_ort_probe_recovers_as_the_first_call_after_a_panic_under_the_lock() {
        let stand_in = onnx::write("ocr_ort_poisoned", "stand-in.onnx", &onnx::stand_in());
        let load = || {
            let answer = run_guarded(|| ocr_load_ort(&stand_in, 1));
            assert!(answer.starts_with("OK: "), "{answer}");
        };
        let not_loaded = format!("ERR: {ORT_NOT_LOADED}");

        load();
        let filled = run_guarded(|| ocr_read_ort(vec![0; 1800], 60, 30, ""));
        assert!(
            filled.starts_with("ERR: ") && filled != not_loaded,
            "{filled}"
        );
        assert_eq!(run_guarded(ocr_unload_ort), DROPPED);

        // ocrReadOrt first.
        load();
        poison();
        assert_eq!(
            run_guarded(|| ocr_read_ort(vec![0; 1800], 60, 30, "")),
            not_loaded
        );
        assert_eq!(run_guarded(ocr_unload_ort), NOTHING);

        // ocrUnloadOrt first.
        load();
        poison();
        assert_eq!(run_guarded(ocr_unload_ort), NOTHING);
        assert_eq!(run_guarded(ocr_unload_ort), NOTHING);

        // ocrLoadOrt first: it clears the poison, so the next call keeps what it loaded.
        load();
        poison();
        load();
        assert_eq!(run_guarded(ocr_unload_ort), DROPPED);

        // The tract probes' slot keeps its own lock while this one is poisoned.
        load();
        poison();
        assert_eq!(run_guarded(ocr_unload), NOTHING);
        assert_eq!(run_guarded(ocr_unload_ort), NOTHING);
    }
}
