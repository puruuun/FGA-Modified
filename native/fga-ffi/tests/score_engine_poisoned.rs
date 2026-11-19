//! A poisoned engine lock must turn every engine call into an error, never a panic and never a
//! silent recovery, and must leave the probes' slot alone. Poisoning is process-wide, so this file
//! is its own test binary.

#[cfg(test)]
mod poisoned {
    use fga_ffi::probes::{NOT_LOADED, ocr_read, ocr_unload};
    use fga_ffi::run_guarded;
    use fga_ffi::score_engine::{
        CODE_ERROR, POISONED, is_loaded_entry, load_entry, poison_engine_lock_for_tests,
        read_entry, unload_entry, wire,
    };

    #[test]
    fn every_engine_call_reports_the_poisoned_lock() {
        assert_eq!(
            run_guarded(poison_engine_lock_for_tests),
            "ERR: panic: poisoning the support-score engine lock"
        );
        assert_eq!(
            load_entry(|| Ok("no/such/model.onnx".to_owned())),
            format!("ERR: {POISONED}")
        );
        assert_eq!(
            read_entry(|n| Ok(vec![0; n]), 60, 30),
            wire::encode_error(POISONED)
        );
        assert_eq!(unload_entry(), CODE_ERROR);
        assert_eq!(is_loaded_entry(), CODE_ERROR);
        // The size rule needs no lock, so it still answers.
        assert_eq!(
            read_entry(|n| Ok(vec![0; n]), 59, 30)[5],
            wire::reason_code(fga_sensing_reason_crop_size())
        );
        // The probes keep their own lock.
        assert_eq!(run_guarded(ocr_unload), "OK: {\"dropped\":false}");
        assert_eq!(
            run_guarded(|| ocr_read(vec![0; 6], 3, 2, "")),
            format!("ERR: {NOT_LOADED}")
        );
    }

    fn fga_sensing_reason_crop_size() -> fga_sensing::readers::support_score::Reason {
        fga_sensing::readers::support_score::Reason::CropSize
    }
}
