//! The ONNX Runtime probe functions (issue #535) on the real model and local samples, at ONNX
//! Runtime's default thread count and at one thread. Needs `--features ort-probe` and, like
//! ocr_model.rs, `FGA_OCR_MODEL` and `FGA_OCR_SAMPLES`; without them it says so and returns.
//!
//! One test only, for the same reason as ocr_model.rs: the loaded model is process-wide state.

#[cfg(all(test, feature = "ort-probe"))]
mod common;

#[cfg(all(test, feature = "ort-probe"))]
mod model {
    use super::common::helpers::{
        first_channel, fixed_samples_match_python, index, local_files, ok_json,
    };
    use fga_ffi::probes::{
        NOT_LOADED, ORT_NOT_LOADED, ocr_load_ort, ocr_read, ocr_read_ort, ocr_unload,
        ocr_unload_ort,
    };
    use fga_ffi::run_guarded;

    #[test]
    fn ort_probe_functions_read_fixed_samples_like_python() {
        let Some((model, dir)) = local_files("ocr ort probe model test") else {
            return;
        };
        let rows = index(&dir);
        let model_path = model.to_str().unwrap().to_owned();

        for threads in [0, 1] {
            let loaded = ok_json(&run_guarded(|| ocr_load_ort(&model_path, threads)));
            assert_eq!(loaded["classes"], 18710);
            assert_eq!(loaded["intraThreads"], threads, "{loaded}");
            let with_text = fixed_samples_match_python(&dir, &rows, |pixels, width, height| {
                run_guarded(|| ocr_read_ort(pixels, width, height, ""))
            });
            println!(
                "ocr ort probe model test, intraThreads {threads}: 25 samples equal to the Python text, {with_text} of them non-empty"
            );
        }

        let (pixels, width, height) = first_channel(&dir.join("0002.png"));
        let limited = ok_json(&run_guarded(|| {
            ocr_read_ort(pixels, width, height, "+/0123456789")
        }));
        assert!(
            limited["text"]
                .as_str()
                .unwrap()
                .chars()
                .all(|c| "+/0123456789".contains(c)),
            "{limited}"
        );

        // The two backends have separate slots: the ONNX Runtime model does not serve tract calls.
        let (pixels, width, height) = first_channel(&dir.join("0000.png"));
        assert_eq!(
            run_guarded(|| ocr_read(pixels, width, height, "")),
            format!("ERR: {NOT_LOADED}")
        );
        assert_eq!(ok_json(&run_guarded(ocr_unload))["dropped"], false);

        assert_eq!(ok_json(&run_guarded(ocr_unload_ort))["dropped"], true);
        let (pixels, width, height) = first_channel(&dir.join("0000.png"));
        assert_eq!(
            run_guarded(|| ocr_read_ort(pixels, width, height, "")),
            format!("ERR: {ORT_NOT_LOADED}")
        );

        // A failed load leaves no model behind, even after a good one.
        ok_json(&run_guarded(|| ocr_load_ort(&model_path, 1)));
        assert!(run_guarded(|| ocr_load_ort("no/such/model.onnx", 1)).starts_with("ERR: "));
        assert_eq!(ok_json(&run_guarded(ocr_unload_ort))["dropped"], false);
    }
}
