//! After a panic while the recogniser lock was held, every OCR probe works again on an empty slot:
//! the recogniser the panicking call held is dropped, as for the product engine
//! (score_engine_poisoned.rs). Poisoning is process-wide, so this file is its own test binary.

#[cfg(test)]
mod poisoned {
    use fga_ffi::probes::{
        NOT_LOADED, ocr_load, ocr_load_tract, ocr_read, ocr_unload, poison_lock_for_tests,
        support_score_read,
    };
    use fga_ffi::run_guarded;

    fn poison() {
        assert_eq!(
            run_guarded(poison_lock_for_tests),
            "ERR: panic: poisoning the recogniser lock"
        );
    }

    #[test]
    fn every_probe_works_again_after_a_panic_under_the_lock() {
        poison();
        let not_loaded = format!("ERR: {NOT_LOADED}");
        assert_eq!(run_guarded(|| ocr_read(vec![0; 6], 3, 2, "")), not_loaded);
        poison();
        assert_eq!(run_guarded(ocr_unload), "OK: {\"dropped\":false}");
        poison();
        assert_eq!(
            run_guarded(|| support_score_read(vec![0; 1800], 60, 30)),
            not_loaded
        );
        poison();
        let answer = run_guarded(|| ocr_load("no/such/model.onnx"));
        assert!(answer.starts_with("ERR: inference backend: "), "{answer}");
        poison();
        let answer = run_guarded(|| ocr_load_tract("no/such/model.onnx", 160, 1));
        assert!(answer.starts_with("ERR: inference backend: "), "{answer}");
        // The size and the arguments are still checked before the lock is taken.
        assert_eq!(
            run_guarded(|| support_score_read(vec![], -1, 30)),
            "ERR: invalid crop size -1x30: at most 2048x256"
        );
        assert_eq!(
            run_guarded(|| ocr_load_tract("no/such/model.onnx", 7, 1)),
            "ERR: minimum model width 7 is not in 8..=4096"
        );
        // Issue #568: the product engine keeps its own lock.
        assert_eq!(fga_ffi::score_engine::is_loaded_entry(), 0);
        assert_eq!(fga_ffi::score_engine::unload_entry(), 0);

        // With the local model: a loaded recogniser is dropped by the recovery and loads again.
        let Some(model) = std::env::var_os("FGA_OCR_MODEL") else {
            println!("ocr poisoned test: the loaded part skipped, FGA_OCR_MODEL is not set");
            return;
        };
        let model = model.to_str().unwrap().to_owned();
        assert!(run_guarded(|| ocr_load(&model)).starts_with("OK: "));
        poison();
        assert_eq!(run_guarded(ocr_unload), "OK: {\"dropped\":false}");
        assert!(run_guarded(|| ocr_load(&model)).starts_with("OK: "));
        assert!(run_guarded(|| ocr_read(vec![0; 1800], 60, 30, "")).starts_with("OK: "));
        assert_eq!(run_guarded(ocr_unload), "OK: {\"dropped\":true}");
    }
}
