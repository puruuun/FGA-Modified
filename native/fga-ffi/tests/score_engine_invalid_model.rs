//! A file that is not a valid model, given to the product engine's load through the same entry the
//! JNI export calls, is an error: never a panic, never an abort, and the slot stays empty. The
//! cases without the real model run in CI: an empty file, random bytes of the pinned model's size,
//! valid ONNX models that are not the recogniser (a small graph built here, with and without the
//! character list), and every truncation of those. With `FGA_OCR_MODEL` set, the real model cut
//! at several lengths and with its first bytes overwritten is loaded too. The slot is
//! process-wide, so this file is its own test binary with one test.

#[cfg(test)]
mod common;

#[cfg(test)]
mod invalid {
    use std::fs;

    use super::common::onnx;
    use fga_ffi::score_engine::{
        CODE_NO, CODE_YES, is_loaded_entry, load_entry, read_entry, unload_entry, wire,
    };

    /// The pinned model's size (native/ocr-model/model.properties), so a same-size file is tried.
    const MODEL_SIZE: usize = 21_234_383;

    /// Loads `bytes` as a model file and returns the answer; the caller checks the slot.
    fn load_bytes(name: &str, bytes: &[u8]) -> String {
        let path = onnx::write("score_engine_invalid_model", name, bytes);
        let answer = load_entry(|| Ok(path.clone()));
        fs::remove_file(&path).unwrap();
        answer
    }

    /// The load must be refused with an error, not a panic, and leave the slot empty.
    fn assert_refused(name: &str, bytes: &[u8]) -> String {
        let answer = load_bytes(name, bytes);
        assert!(answer.starts_with("ERR: "), "{name}: {answer}");
        assert!(!answer.starts_with("ERR: panic"), "{name}: {answer}");
        assert_eq!(is_loaded_entry(), CODE_NO, "{name}: {answer}");
        answer
    }

    #[test]
    fn a_file_that_is_not_the_model_is_an_error_not_a_panic() {
        assert_eq!(is_loaded_entry(), CODE_NO);
        println!("empty: {}", assert_refused("empty.onnx", &[]));
        println!(
            "random, model size: {}",
            assert_refused("random.onnx", &onnx::random_bytes(MODEL_SIZE))
        );

        let without_characters = onnx::model("Identity", None);
        let answer = assert_refused("identity.onnx", &without_characters);
        assert!(answer.contains("character"), "{answer}");
        println!("identity, no character list: {answer}");

        // Graphs with a character list: whatever the load says, the slot must either stay empty
        // with an error or hold a model the read rejects without a panic.
        for op in ["Identity", "Relu", "NoSuchOp"] {
            let model = onnx::model(op, Some("0\n1\n+\n/\n"));
            let answer = load_bytes(&format!("{op}.onnx"), &model);
            assert!(!answer.starts_with("ERR: panic"), "{op}: {answer}");
            if answer.starts_with("OK: ") {
                assert_eq!(is_loaded_entry(), CODE_YES);
                let reply = read_entry(|n| Ok(vec![128; n]), 60, 30);
                assert_eq!(
                    reply[1],
                    wire::STATUS_ERROR,
                    "{op}: a model that is not the recogniser gave a reading {reply:?}"
                );
                assert_eq!(unload_entry(), CODE_YES);
            } else {
                assert_eq!(is_loaded_entry(), CODE_NO, "{op}: {answer}");
            }
            println!("{op} with a character list: {answer}");

            for cut in 0..model.len() {
                assert_refused(&format!("{op}-{cut}.onnx"), &model[..cut]);
            }
        }

        let Some(real) = std::env::var_os("FGA_OCR_MODEL") else {
            println!("invalid model test: the real-model part skipped, FGA_OCR_MODEL is not set");
            return;
        };
        let real = fs::read(real).unwrap();
        assert_eq!(real.len(), MODEL_SIZE);
        for cut in [1, 16, 1024, MODEL_SIZE / 4, MODEL_SIZE / 2, MODEL_SIZE - 1] {
            println!(
                "real model cut to {cut}: {}",
                assert_refused("cut.onnx", &real[..cut])
            );
        }
        let mut header = real.clone();
        header[..4096].copy_from_slice(&onnx::random_bytes(4096));
        println!(
            "real model, first 4096 bytes random: {}",
            assert_refused("header.onnx", &header)
        );
    }
}
