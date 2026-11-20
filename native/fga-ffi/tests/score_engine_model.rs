//! The product support-score engine on the real model, next to the probes: the two slots stay
//! apart, and both read the same fields. Needs `FGA_OCR_MODEL` and `FGA_OCR_SAMPLES` as the other
//! model tests; without both it says so and returns.
//!
//! One test only: the loaded engine and the probe recogniser are process-wide state.

#[cfg(test)]
mod common;

#[cfg(test)]
mod model {
    use super::common::helpers::{FIXED, first_channel, local_files, ok_json};
    use fga_ffi::probes::{ocr_load, ocr_unload, support_score_read};
    use fga_ffi::run_guarded;
    use fga_ffi::score_engine::{
        ALREADY_LOADED, CODE_NO, CODE_YES, NOT_LOADED, is_loaded_entry, load_entry, read_entry,
        unload_entry, wire,
    };
    use serde_json::Value;

    fn text(reply: &[i32]) -> String {
        reply[wire::HEADER_LEN..]
            .iter()
            .map(|c| char::from_u32(*c as u32).unwrap())
            .collect()
    }

    fn reason(name: Option<&str>) -> i32 {
        match name {
            None => wire::NO_REASON,
            Some("blank") => 1,
            Some("low_confidence") => 2,
            Some("format") => 3,
            Some("range") => 4,
            Some("clipped") => 5,
            Some("not_shown") => 6,
            Some("crop_size") => 7,
            Some(other) => panic!("unknown reason {other}"),
        }
    }

    fn display(name: &str) -> i32 {
        match name {
            "ordinary" => 0,
            "compound" => 1,
            "unknown" => 2,
            other => panic!("unknown display {other}"),
        }
    }

    /// Every field of the engine's reply equals the probe's JSON for the same crop.
    fn assert_same(file: &str, reply: &[i32], probe: &Value) {
        assert_eq!(reply[1], wire::STATUS_READ, "{file}: {}", text(reply));
        let value = |key: &str| probe[key].as_i64().map_or(wire::NO_VALUE, |v| v as i32);
        assert_eq!(reply[2], value("class"), "{file} class: {probe}");
        assert_eq!(reply[3], value("grand"), "{file} grand: {probe}");
        assert_eq!(
            reply[4],
            display(probe["display"].as_str().unwrap()),
            "{file}"
        );
        assert_eq!(reply[5], reason(probe["classReason"].as_str()), "{file}");
        assert_eq!(reply[6], reason(probe["grandReason"].as_str()), "{file}");
        assert_eq!(
            f32::from_bits(reply[7] as u32),
            probe["confidence"].as_f64().unwrap() as f32,
            "{file}"
        );
        assert_eq!(text(reply), probe["text"].as_str().unwrap(), "{file}");
    }

    #[test]
    fn engine_reads_like_the_probe_and_keeps_its_own_slot() {
        let Some((model, dir)) = local_files("score engine model test") else {
            return;
        };
        let model_path = model.to_str().unwrap().to_owned();
        let (pixels, w, h) = first_channel(&dir.join("0183.png"));
        assert_eq!((w, h), (60, 30));

        // Before any load.
        assert_eq!(is_loaded_entry(), CODE_NO);
        assert_eq!(text(&read_entry(|_| Ok(pixels.clone()), w, h)), NOT_LOADED);
        assert_eq!(unload_entry(), CODE_NO);

        let loaded = load_entry(|| Ok(model_path.clone()));
        assert!(loaded.starts_with("OK: "), "{loaded}");
        assert_eq!(is_loaded_entry(), CODE_YES);
        // A second load is refused and keeps the first.
        assert_eq!(
            load_entry(|| Ok(model_path.clone())),
            format!("ERR: {ALREADY_LOADED}")
        );
        assert_eq!(is_loaded_entry(), CODE_YES);
        // Issue #591: the engine reads at the reader width; at 320 this sample is unknown (format).
        let (pixels_0299, w_0299, h_0299) = first_channel(&dir.join("0299.png"));
        let reply = read_entry(|_| Ok(pixels_0299), w_0299, h_0299);
        assert_eq!(
            &reply[1..4],
            &[wire::STATUS_READ, 57, 16],
            "0299: {}",
            text(&reply)
        );

        // The probe slot: loading, reading and unloading it leaves the engine as it is.
        ok_json(&run_guarded(|| ocr_load(&model_path)));
        for &i in &FIXED {
            let file = format!("{i:04}.png");
            let (pixels, w, h) = first_channel(&dir.join(&file));
            let probe = ok_json(&run_guarded(|| support_score_read(pixels.clone(), w, h)));
            let reply = read_entry(|_| Ok(pixels), w, h);
            assert_same(&file, &reply, &probe);
        }
        assert_eq!(ok_json(&run_guarded(ocr_unload))["dropped"], true);
        assert_eq!(is_loaded_entry(), CODE_YES);
        let reply = read_entry(|_| Ok(pixels.clone()), w, h);
        assert_eq!(
            &reply[1..4],
            &[wire::STATUS_READ, 62, 16],
            "{}",
            text(&reply)
        );

        // And the reverse: an engine unload leaves a loaded probe recogniser alone.
        ok_json(&run_guarded(|| ocr_load(&model_path)));
        assert_eq!(unload_entry(), CODE_YES);
        assert_eq!(unload_entry(), CODE_NO);
        assert_eq!(is_loaded_entry(), CODE_NO);
        assert_eq!(text(&read_entry(|_| Ok(pixels.clone()), w, h)), NOT_LOADED);
        let probe = ok_json(&run_guarded(|| support_score_read(pixels.clone(), w, h)));
        assert_eq!(probe["class"], 62);
        assert_eq!(ok_json(&run_guarded(ocr_unload))["dropped"], true);

        // A failed load leaves nothing loaded.
        assert!(load_entry(|| Ok("no/such/model.onnx".to_owned())).starts_with("ERR: "));
        assert_eq!(is_loaded_entry(), CODE_NO);
        println!("score engine model test: 25 samples equal to the probe, slots independent");
    }
}
