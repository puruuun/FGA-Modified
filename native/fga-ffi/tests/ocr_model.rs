//! The OCR probe functions the JNI layer calls, run on the real model and local samples. Needs
//! `FGA_OCR_MODEL` and `FGA_OCR_SAMPLES`, as fga-sensing's model tests do; without both it says
//! so and returns, because neither file may be committed (docs/agents/rust-native.md, "Test data").
//!
//! One test only: the loaded model is process-wide state, and a second test in this binary would
//! race with it.

// Clippy allows unwrap and panic only in test code; the helpers in common run only from the test.
#[cfg(test)]
mod common;

#[cfg(test)]
mod model {
    use super::common::helpers::{
        first_channel, fixed_samples_match_python, index, local_files, ok_json,
    };
    use fga_ffi::probes::{NOT_LOADED, ocr_load, ocr_read, ocr_unload};
    use fga_ffi::run_guarded;

    #[test]
    fn probe_functions_read_fixed_samples_like_python() {
        let Some((model, dir)) = local_files("ocr probe model test") else {
            return;
        };
        let rows = index(&dir);

        let model_path = model.to_str().unwrap().to_owned();
        let loaded = ok_json(&run_guarded(|| ocr_load(&model_path)));
        assert_eq!(loaded["classes"], 18710);
        // A second load replaces the first.
        let reloaded = ok_json(&run_guarded(|| ocr_load(&model_path)));
        assert_eq!(reloaded["classes"], 18710);

        let with_text = fixed_samples_match_python(&dir, &rows, |pixels, width, height| {
            run_guarded(|| ocr_read(pixels, width, height, ""))
        });

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
            "ocr probe model test: 25 samples equal to the Python text, {with_text} of them non-empty"
        );
    }
}
