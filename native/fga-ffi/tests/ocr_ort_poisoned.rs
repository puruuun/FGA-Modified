//! A poisoned ONNX Runtime recogniser lock turns every ONNX Runtime probe into an error, as
//! ocr_poisoned.rs checks for tract. Its own test binary, because poisoning is process-wide.

#[cfg(all(test, feature = "ort-probe"))]
mod poisoned {
    use fga_ffi::probes::{
        POISONED, ocr_load_ort, ocr_read_ort, ocr_unload_ort, poison_ort_lock_for_tests,
    };
    use fga_ffi::run_guarded;

    #[test]
    fn every_ort_probe_reports_the_poisoned_lock() {
        assert_eq!(
            run_guarded(poison_ort_lock_for_tests),
            "ERR: panic: poisoning the ONNX Runtime recogniser lock"
        );
        let expected = format!("ERR: {POISONED}");
        assert_eq!(run_guarded(|| ocr_read_ort(vec![0; 6], 3, 2, "")), expected);
        assert_eq!(
            run_guarded(|| ocr_load_ort("no/such/model.onnx", 1)),
            expected
        );
        assert_eq!(run_guarded(ocr_unload_ort), expected);
    }
}
