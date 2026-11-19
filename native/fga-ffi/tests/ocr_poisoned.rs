//! A poisoned recogniser lock must turn every OCR probe into an error, never a panic and never a
//! silent recovery. Poisoning is process-wide, so this file is its own test binary.

#[cfg(test)]
mod poisoned {
    use fga_ffi::probes::{
        POISONED, ocr_load, ocr_load_tract, ocr_read, ocr_unload, poison_lock_for_tests,
        support_score_read,
    };
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
        assert_eq!(
            run_guarded(|| support_score_read(vec![0; 1800], 60, 30)),
            expected
        );
        // The size is checked before the lock is taken, so a bad one is still named.
        assert_eq!(
            run_guarded(|| support_score_read(vec![], -1, 30)),
            "ERR: invalid crop size -1x30: at most 2048x256"
        );
        assert_eq!(
            run_guarded(|| ocr_load_tract("no/such/model.onnx", 160, 1)),
            expected
        );
        // The arguments are checked before the lock is taken, so a bad one is still named.
        assert_eq!(
            run_guarded(|| ocr_load_tract("no/such/model.onnx", 7, 1)),
            "ERR: minimum model width 7 is not in 8..=4096"
        );
        assert_eq!(
            run_guarded(|| ocr_load_tract("no/such/model.onnx", 160, 17)),
            "ERR: invalid thread count 17: 0..=16"
        );
    }
}
