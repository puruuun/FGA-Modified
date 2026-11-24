//! After a panic while the recogniser lock was held, every OCR probe works again: the first call
//! after the panic drops the recogniser the panicking call held and clears the poison, as for the
//! product engine (score_engine_poisoned.rs). A small stand-in model fills the slot, so this holds
//! without the real model. Poisoning is process-wide, so this file is its own test binary.

#[cfg(test)]
mod common;

#[cfg(test)]
mod poisoned {
    use super::common::onnx;
    use fga_ffi::probes::{
        NOT_LOADED, ocr_load, ocr_load_tract, ocr_read, ocr_unload, poison_lock_for_tests,
        support_score_read,
    };
    use fga_ffi::run_guarded;
    use fga_ffi::score_engine::{CODE_NO, is_loaded_entry, unload_entry};

    const DROPPED: &str = "OK: {\"dropped\":true}";
    const NOTHING: &str = "OK: {\"dropped\":false}";

    fn poison() {
        assert_eq!(
            run_guarded(poison_lock_for_tests),
            "ERR: panic: poisoning the recogniser lock"
        );
    }

    #[test]
    fn every_probe_recovers_as_the_first_call_after_a_panic_under_the_lock() {
        let stand_in = onnx::write("ocr_poisoned", "stand-in.onnx", &onnx::stand_in());
        let load = || assert!(run_guarded(|| ocr_load(&stand_in)).starts_with("OK: "));
        let not_loaded = format!("ERR: {NOT_LOADED}");

        // Without a panic, a read on the stand-in is an error of its own, not "not loaded", so a
        // "not loaded" below shows that the recovery dropped it.
        load();
        let filled = run_guarded(|| ocr_read(vec![0; 1800], 60, 30, ""));
        assert!(
            filled.starts_with("ERR: ") && filled != not_loaded,
            "{filled}"
        );
        let filled = run_guarded(|| support_score_read(vec![0; 1800], 60, 30));
        assert!(
            filled.starts_with("ERR: ") && filled != not_loaded,
            "{filled}"
        );
        assert_eq!(run_guarded(ocr_unload), DROPPED);

        // ocrRead first.
        load();
        poison();
        assert_eq!(
            run_guarded(|| ocr_read(vec![0; 1800], 60, 30, "")),
            not_loaded
        );
        assert_eq!(run_guarded(ocr_unload), NOTHING);

        // supportScoreRead first.
        load();
        poison();
        assert_eq!(
            run_guarded(|| support_score_read(vec![0; 1800], 60, 30)),
            not_loaded
        );
        assert_eq!(run_guarded(ocr_unload), NOTHING);

        // ocrUnload first.
        load();
        poison();
        assert_eq!(run_guarded(ocr_unload), NOTHING);
        assert_eq!(run_guarded(ocr_unload), NOTHING);

        // ocrLoad and ocrLoadTract first: the load must also clear the poison, or the next call
        // would drop the recogniser it just loaded.
        load();
        poison();
        load();
        assert_eq!(run_guarded(ocr_unload), DROPPED);
        load();
        poison();
        assert!(run_guarded(|| ocr_load_tract(&stand_in, 320, 1)).starts_with("OK: "));
        assert_eq!(run_guarded(ocr_unload), DROPPED);

        // The size and the arguments are checked before the lock is taken, so a poisoned lock is
        // neither met nor cleared by them.
        load();
        poison();
        assert_eq!(
            run_guarded(|| support_score_read(vec![], -1, 30)),
            "ERR: invalid crop size -1x30: at most 2048x256"
        );
        assert_eq!(
            run_guarded(|| ocr_load_tract("no/such/model.onnx", 7, 1)),
            "ERR: minimum model width 7 is not in 8..=4096"
        );
        // Issue #568: the product engine keeps its own lock, and its calls leave the probes'
        // poison for the probes' next call, which then drops the stand-in.
        assert_eq!(is_loaded_entry(), CODE_NO);
        assert_eq!(unload_entry(), CODE_NO);
        assert_eq!(run_guarded(ocr_unload), NOTHING);

        // With the local model: the real recogniser is dropped the same way and loads again.
        let Some(model) = std::env::var_os("FGA_OCR_MODEL") else {
            println!("ocr poisoned test: the real-model part skipped, FGA_OCR_MODEL is not set");
            return;
        };
        let model = model.to_str().unwrap().to_owned();
        assert!(run_guarded(|| ocr_load(&model)).starts_with("OK: "));
        poison();
        assert_eq!(run_guarded(ocr_unload), NOTHING);
        assert!(run_guarded(|| ocr_load(&model)).starts_with("OK: "));
        assert!(run_guarded(|| ocr_read(vec![0; 1800], 60, 30, "")).starts_with("OK: "));
        assert_eq!(run_guarded(ocr_unload), DROPPED);
    }
}
