//! A panic while a call holds the engine lock is that call's error, and the engine is usable again
//! afterwards: the model the call was using is dropped, `isLoaded` and `unload` answer that nothing
//! is loaded, and a later load fills the slot again. The probes' slot is not touched, and the
//! engine's poison stays until an engine call meets it. A model whose drop panics during the
//! recovery leaves the slot empty and the next call clean. Poisoning is process-wide, so this file
//! is its own test binary with one test.

#[cfg(test)]
mod common;

#[cfg(test)]
mod poisoned {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::common::onnx;
    use fga_ffi::probes::{NOT_LOADED as PROBE_NOT_LOADED, ocr_load, ocr_read, ocr_unload};
    use fga_ffi::run_guarded;
    use fga_ffi::score_engine::{
        CODE_ERROR, CODE_NO, CODE_YES, NOT_LOADED, ScoreModel, install_for_tests, is_loaded_entry,
        load_entry, poison_engine_lock_for_tests, read_entry, unload_entry, wire,
    };
    use fga_sensing::image::GrayImage;
    use fga_sensing::readers::support_score::{
        ClipReport, Display, Observation, Reason, Score, SupportScore,
    };

    /// Panics in every read, as a model that breaks mid-read would, and counts its drops.
    struct Panicking(Arc<AtomicUsize>);

    impl ScoreModel for Panicking {
        fn read(&mut self, _crop: &GrayImage) -> Result<SupportScore, String> {
            panic!("the model panicked mid-read")
        }
    }

    impl Drop for Panicking {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    /// Panics in every read and again in its drop.
    struct PanicsInDropToo;

    impl ScoreModel for PanicsInDropToo {
        fn read(&mut self, _crop: &GrayImage) -> Result<SupportScore, String> {
            panic!("the model panicked mid-read")
        }
    }

    impl Drop for PanicsInDropToo {
        fn drop(&mut self) {
            panic!("the model panicked in its drop")
        }
    }

    /// Answers +41/+7, as in score_engine_hooks.rs.
    struct Marker;

    impl ScoreModel for Marker {
        fn read(&mut self, crop: &GrayImage) -> Result<SupportScore, String> {
            Ok(SupportScore {
                class: Score::Value(41),
                grand: Score::Value(7),
                display: Display::Compound,
                sign_corrected: false,
                observation: Observation {
                    text: "+41/+7".to_owned(),
                    confidence: 0.75,
                    min_char_confidence: Some(0.5),
                    char_confidences: vec![0.5, 1.0, 1.0, 0.5, 1.0, 0.5],
                    clip: ClipReport::default(),
                    crop_size: (crop.width(), crop.height()),
                    featureless: false,
                },
            })
        }
    }

    fn text(reply: &[i32]) -> String {
        reply[wire::HEADER_LEN..]
            .iter()
            .map(|c| char::from_u32(*c as u32).unwrap())
            .collect()
    }

    fn read() -> Vec<i32> {
        read_entry(|n| Ok(vec![0; n]), 60, 30)
    }

    fn panicking_read_leaves_the_slot(drops: &Arc<AtomicUsize>) {
        assert_eq!(
            install_for_tests(Box::new(Panicking(drops.clone()))),
            Ok("0".to_owned())
        );
        assert_eq!(is_loaded_entry(), CODE_YES);
        // The engine-error reply ScoreWire.kt already decodes as EngineError.
        assert_eq!(
            read(),
            wire::encode_error("panic: the model panicked mid-read")
        );
    }

    #[test]
    fn a_panic_under_the_lock_drops_the_model_and_frees_the_slot() {
        let drops = Arc::new(AtomicUsize::new(0));

        // The probes' slot works while the engine's lock is poisoned, and leaves that poison for
        // the engine's next call: the model is dropped only then.
        let stand_in = onnx::write("score_engine_poisoned", "stand-in.onnx", &onnx::stand_in());
        let probe_not_loaded = format!("ERR: {PROBE_NOT_LOADED}");
        panicking_read_leaves_the_slot(&drops);
        assert!(run_guarded(|| ocr_load(&stand_in)).starts_with("OK: "));
        let probe_read = run_guarded(|| ocr_read(vec![0; 1800], 60, 30, ""));
        assert!(
            probe_read.starts_with("ERR: ") && probe_read != probe_not_loaded,
            "{probe_read}"
        );
        assert_eq!(run_guarded(ocr_unload), "OK: {\"dropped\":true}");
        assert_eq!(
            run_guarded(|| ocr_read(vec![0; 6], 3, 2, "")),
            probe_not_loaded
        );
        assert_eq!(drops.load(Ordering::SeqCst), 0);
        // Recovery through isLoaded (try_lock) first.
        assert_eq!(is_loaded_entry(), CODE_NO);
        assert_eq!(drops.load(Ordering::SeqCst), 1);
        assert_eq!(unload_entry(), CODE_NO);
        assert_eq!(text(&read()), NOT_LOADED);

        // Recovery through unload (lock) first.
        panicking_read_leaves_the_slot(&drops);
        assert_eq!(unload_entry(), CODE_NO);
        assert_eq!(drops.load(Ordering::SeqCst), 2);
        assert_eq!(is_loaded_entry(), CODE_NO);

        // Recovery through read first: nothing is read from the dropped model.
        panicking_read_leaves_the_slot(&drops);
        assert_eq!(text(&read()), NOT_LOADED);
        assert_eq!(drops.load(Ordering::SeqCst), 3);

        // Recovery through load first: the load runs (here it reaches the backend and fails on the
        // missing file, so nothing is loaded) instead of being refused as already loaded.
        panicking_read_leaves_the_slot(&drops);
        let answer = load_entry(|| Ok("no/such/model.onnx".to_owned()));
        assert!(answer.starts_with("ERR: inference backend: "), "{answer}");
        assert_eq!(drops.load(Ordering::SeqCst), 4);
        assert_eq!(is_loaded_entry(), CODE_NO);

        // A panic with nothing loaded.
        assert_eq!(
            run_guarded(poison_engine_lock_for_tests),
            "ERR: panic: poisoning the support-score engine lock"
        );
        assert_eq!(is_loaded_entry(), CODE_NO);

        // The slot loads and reads again, and unloads what it loaded.
        assert_eq!(install_for_tests(Box::new(Marker)), Ok("0".to_owned()));
        let reply = read();
        assert_eq!(&reply[..4], &[wire::MAGIC, wire::STATUS_READ, 41, 7]);
        assert_eq!(text(&reply), "+41/+7");
        // The size rule needs no lock and answers as before.
        assert_eq!(
            read_entry(|n| Ok(vec![0; n]), 59, 30),
            wire::encode_unknown(Reason::CropSize)
        );
        assert_eq!(unload_entry(), CODE_YES);
        assert_eq!(unload_entry(), CODE_NO);

        // A model that panics in its drop as well: the call that recovers answers that panic as
        // its error, the slot is empty by then, and the call after it finds a clean, empty slot.
        let drop_panic = "panic: the model panicked in its drop";
        let install_and_panic = || {
            assert_eq!(
                install_for_tests(Box::new(PanicsInDropToo)),
                Ok("0".to_owned())
            );
            assert_eq!(
                read(),
                wire::encode_error("panic: the model panicked mid-read")
            );
        };
        install_and_panic();
        assert_eq!(is_loaded_entry(), CODE_ERROR);
        assert_eq!(is_loaded_entry(), CODE_NO);
        install_and_panic();
        assert_eq!(read(), wire::encode_error(drop_panic));
        assert_eq!(text(&read()), NOT_LOADED);
        install_and_panic();
        assert_eq!(unload_entry(), CODE_ERROR);
        assert_eq!(unload_entry(), CODE_NO);
        install_and_panic();
        assert_eq!(
            load_entry(|| Ok("no/such/model.onnx".to_owned())),
            format!("ERR: {drop_panic}")
        );
        assert_eq!(is_loaded_entry(), CODE_NO);
        assert_eq!(install_for_tests(Box::new(Marker)), Ok("0".to_owned()));
        assert_eq!(text(&read()), "+41/+7");
        assert_eq!(unload_entry(), CODE_YES);
    }
}
