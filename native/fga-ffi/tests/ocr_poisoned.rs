//! A poisoned recogniser lock must turn every OCR probe into an error, never a panic and never a
//! silent recovery. Poisoning is process-wide, so this file is its own test binary.

#[cfg(test)]
mod poisoned {
    use fga_ffi::probes::{POISONED, ocr_load, ocr_read, ocr_unload, poison_lock_for_tests};
    use fga_ffi::run_guarded;

    #[test]
    fn every_probe_reports_the_poisoned_lock() {
        assert_eq!(
            run_guarded(poison_lock_for_tests),
            "ERR: panic: poisoning the recogniser lock"
        );
        let expected = format!("ERR: {POISONED}");
        assert_eq!(run_guarded(|| ocr_read(vec![0; 6], 3, 2, "")), expected);
        assert_eq!(run_guarded(|| ocr_load("no/such/model.onnx")), expected);
        assert_eq!(run_guarded(ocr_unload), expected);
    }
}
