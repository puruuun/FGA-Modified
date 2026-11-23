//! After a panic while the ONNX Runtime recogniser lock was held, every ONNX Runtime probe works
//! again on an empty slot, as ocr_poisoned.rs checks for tract. Its own test binary, because
//! poisoning is process-wide.

#[cfg(all(test, feature = "ort-probe"))]
mod poisoned {
    use fga_ffi::probes::{
        ORT_NOT_LOADED, ocr_load_ort, ocr_read_ort, ocr_unload_ort, poison_ort_lock_for_tests,
    };
    use fga_ffi::run_guarded;

    fn poison() {
        assert_eq!(
            run_guarded(poison_ort_lock_for_tests),
            "ERR: panic: poisoning the ONNX Runtime recogniser lock"
        );
    }

    #[test]
    fn every_ort_probe_works_again_after_a_panic_under_the_lock() {
        poison();
        assert_eq!(
            run_guarded(|| ocr_read_ort(vec![0; 6], 3, 2, "")),
            format!("ERR: {ORT_NOT_LOADED}")
        );
        poison();
        let answer = run_guarded(|| ocr_load_ort("no/such/model.onnx", 1));
        assert!(answer.starts_with("ERR: "), "{answer}");
        poison();
        assert_eq!(run_guarded(ocr_unload_ort), "OK: {\"dropped\":false}");
    }
}
