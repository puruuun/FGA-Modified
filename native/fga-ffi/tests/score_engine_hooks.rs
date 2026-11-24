//! The slot rules of the support-score engine without a model file, so CI checks them: a marker
//! engine goes into the engine slot through the test hooks, and the probes' slot must not see it,
//! a second load must be refused, and only the engine's own unload may drop it. The slot is
//! process-wide, so this file is its own test binary with one test.

#[cfg(test)]
mod hooks {
    use fga_ffi::probes::{NOT_LOADED as PROBE_NOT_LOADED, ocr_read, ocr_unload};
    use fga_ffi::run_guarded;
    use fga_ffi::score_engine::{
        ALREADY_LOADED, CODE_NO, CODE_YES, NOT_LOADED, ScoreModel, install_for_tests,
        is_loaded_entry, load_entry, read_entry, unload_entry, wire,
    };
    use fga_sensing::image::GrayImage;
    use fga_sensing::readers::support_score::{
        ClipReport, Display, Observation, Score, SupportScore,
    };

    /// Answers +41/+7 for every crop, a value no real sample needs, so a reply from it is
    /// recognisable.
    struct Marker;

    impl ScoreModel for Marker {
        fn read(&mut self, crop: &GrayImage) -> Result<SupportScore, String> {
            Ok(SupportScore {
                class: Score::Value(41),
                grand: Score::Value(7),
                display: Display::Compound,
                observation: Observation {
                    text: "+41/+7".to_owned(),
                    confidence: 0.75,
                    min_char_confidence: Some(0.5),
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

    #[test]
    fn the_engine_slot_is_its_own() {
        assert_eq!(is_loaded_entry(), CODE_NO);
        assert_eq!(install_for_tests(Box::new(Marker)), Ok("0".to_owned()));
        assert_eq!(is_loaded_entry(), CODE_YES);

        // A second load is refused before the path is even looked at, and keeps the marker.
        assert_eq!(
            load_entry(|| Ok("no/such/model.onnx".to_owned())),
            format!("ERR: {ALREADY_LOADED}")
        );
        assert_eq!(
            install_for_tests(Box::new(Marker)),
            Err(ALREADY_LOADED.to_owned())
        );

        // The probes' slot does not see the marker, and dropping it drops nothing of the engine.
        assert_eq!(
            run_guarded(|| ocr_read(vec![0; 1800], 60, 30, "")),
            format!("ERR: {PROBE_NOT_LOADED}")
        );
        assert_eq!(run_guarded(ocr_unload), "OK: {\"dropped\":false}");
        assert_eq!(is_loaded_entry(), CODE_YES);
        let reply = read_entry(|n| Ok(vec![0; n]), 60, 30);
        assert_eq!(&reply[..4], &[wire::MAGIC, wire::STATUS_READ, 41, 7]);
        assert_eq!(text(&reply), "+41/+7");

        // Only the engine's own unload drops it, once.
        assert_eq!(unload_entry(), CODE_YES);
        assert_eq!(unload_entry(), CODE_NO);
        assert_eq!(is_loaded_entry(), CODE_NO);
        assert_eq!(text(&read_entry(|n| Ok(vec![0; n]), 60, 30)), NOT_LOADED);
    }
}
