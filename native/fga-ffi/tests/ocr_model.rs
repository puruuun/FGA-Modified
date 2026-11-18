//! The OCR probe functions the JNI layer calls, run on the real model and local samples. Needs
//! `FGA_OCR_MODEL` and `FGA_OCR_SAMPLES`, as fga-sensing's model tests do; without both it says
//! so and returns, because neither file may be committed (docs/agents/rust-native.md, "Test data").
//!
//! One test only: the loaded model is process-wide state, and a second test in this binary would
//! race with it.

// Clippy allows unwrap and panic only in test code; the helpers below run only from the test.
#[cfg(test)]
mod model {
    use std::fs::File;
    use std::io::BufReader;
    use std::path::{Path, PathBuf};

    use fga_ffi::probes::{NOT_LOADED, ocr_load, ocr_read, ocr_unload};
    use fga_ffi::run_guarded;
    use serde_json::Value;

    /// 25 samples spread over the whole set, the same ones fga-sensing's model test uses.
    const FIXED: [usize; 25] = [
        0, 15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180, 195, 210, 225, 240, 255, 270, 285,
        300, 315, 330, 345, 360,
    ];

    fn local_files() -> Option<(PathBuf, PathBuf)> {
        match (
            std::env::var_os("FGA_OCR_MODEL"),
            std::env::var_os("FGA_OCR_SAMPLES"),
        ) {
            (Some(model), Some(samples)) => Some((model.into(), samples.into())),
            (None, None) => {
                println!(
                    "ocr probe model test: skipped, FGA_OCR_MODEL and FGA_OCR_SAMPLES are not set"
                );
                None
            }
            _ => panic!(
                "set both FGA_OCR_MODEL and FGA_OCR_SAMPLES to run the model test, or neither"
            ),
        }
    }

    /// The samples store gray as equal R, G and B (plus alpha), so the first channel is the gray
    /// value; the device test decodes them the same way.
    fn first_channel(path: &Path) -> (Vec<u8>, i32, i32) {
        let mut decoder = png::Decoder::new(BufReader::new(File::open(path).unwrap()));
        decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
        let mut reader = decoder.read_info().unwrap();
        let mut buffer = vec![0u8; reader.output_buffer_size().unwrap()];
        let info = reader.next_frame(&mut buffer).unwrap();
        let channels = info.color_type.samples();
        let pixels = buffer[..info.buffer_size()]
            .chunks_exact(channels)
            .map(|px| px[0])
            .collect();
        (pixels, info.width as i32, info.height as i32)
    }

    fn ok_json(text: &str) -> Value {
        let body = text
            .strip_prefix("OK: ")
            .unwrap_or_else(|| panic!("{text}"));
        serde_json::from_str(body).unwrap()
    }

    #[test]
    fn probe_functions_read_fixed_samples_like_python() {
        let Some((model, dir)) = local_files() else {
            return;
        };
        let index: Value =
            serde_json::from_str(&std::fs::read_to_string(dir.join("index.json")).unwrap())
                .unwrap();
        let rows = index.as_array().unwrap();

        let model_path = model.to_str().unwrap().to_owned();
        let loaded = ok_json(&run_guarded(|| ocr_load(&model_path)));
        assert_eq!(loaded["classes"], 18710);
        // A second load replaces the first.
        let reloaded = ok_json(&run_guarded(|| ocr_load(&model_path)));
        assert_eq!(reloaded["classes"], 18710);

        let mut with_text = 0;
        for &i in &FIXED {
            let row = &rows[i];
            let file = row["file"].as_str().unwrap();
            let expected = row["py_text"].as_str().unwrap_or_default();
            let (pixels, width, height) = first_channel(&dir.join(file));
            let read = ok_json(&run_guarded(|| ocr_read(pixels, width, height, "")));
            assert_eq!(read["text"], expected, "{file}");
            let confidence = read["confidence"].as_f64().unwrap();
            let py_conf = row["py_conf"].as_f64().unwrap_or_default();
            assert!(
                (confidence - py_conf).abs() < 1e-4,
                "{file}: {confidence} against {py_conf}"
            );
            assert!(read["micros"].as_u64().unwrap() > 0, "{file}");
            with_text += usize::from(!expected.is_empty());
        }
        assert!(
            with_text * 2 > FIXED.len(),
            "only {with_text} samples have text"
        );

        // The allowed set reaches the decoder: a score read keeps only score characters.
        let (pixels, width, height) = first_channel(&dir.join("0002.png"));
        let limited = ok_json(&run_guarded(|| {
            ocr_read(pixels, width, height, "+/0123456789")
        }));
        assert!(
            limited["text"]
                .as_str()
                .unwrap()
                .chars()
                .all(|c| "+/0123456789".contains(c)),
            "{limited}"
        );
        let (pixels, width, height) = first_channel(&dir.join("0000.png"));
        let unknown = run_guarded(|| ocr_read(pixels, width, height, "+/0123456789\u{1}"));
        assert!(
            unknown.starts_with("ERR: the model cannot produce"),
            "{unknown}"
        );

        assert_eq!(ok_json(&run_guarded(ocr_unload))["dropped"], true);
        let (pixels, width, height) = first_channel(&dir.join("0000.png"));
        assert_eq!(
            run_guarded(|| ocr_read(pixels, width, height, "")),
            format!("ERR: {NOT_LOADED}")
        );
        assert_eq!(ok_json(&run_guarded(ocr_unload))["dropped"], false);

        // A failed load leaves no model behind, even after a good one.
        ok_json(&run_guarded(|| ocr_load(&model_path)));
        assert!(run_guarded(|| ocr_load("no/such/model.onnx")).starts_with("ERR: "));
        assert_eq!(ok_json(&run_guarded(ocr_unload))["dropped"], false);

        println!(
            "ocr probe model test: {} samples equal to the Python text, {with_text} of them non-empty",
            FIXED.len()
        );
    }
}
