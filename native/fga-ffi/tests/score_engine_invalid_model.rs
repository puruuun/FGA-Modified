//! A file that is not a valid model, given to the product engine's load through the same entry the
//! JNI export calls, is an error: never a panic, never an abort, and the slot stays empty. The
//! cases without the real model run in CI: an empty file, random bytes of the pinned model's size,
//! valid ONNX models that are not the recogniser (a small graph built here, with and without the
//! character list), and every truncation of those. With `FGA_OCR_MODEL` set, the real model cut
//! at several lengths and with its first bytes overwritten is loaded too. The slot is
//! process-wide, so this file is its own test binary with one test.

#[cfg(test)]
mod invalid {
    use std::fs;
    use std::path::{Path, PathBuf};

    use fga_ffi::score_engine::{CODE_NO, CODE_YES, is_loaded_entry, load_entry, unload_entry};

    /// The pinned model's size (native/ocr-model/model.properties), so a same-size file is tried.
    const MODEL_SIZE: usize = 21_234_383;

    fn varint(out: &mut Vec<u8>, mut value: u64) {
        loop {
            let byte = (value & 0x7f) as u8;
            value >>= 7;
            if value == 0 {
                out.push(byte);
                return;
            }
            out.push(byte | 0x80);
        }
    }

    fn field_varint(out: &mut Vec<u8>, field: u64, value: u64) {
        varint(out, field << 3);
        varint(out, value);
    }

    fn field_bytes(out: &mut Vec<u8>, field: u64, bytes: &[u8]) {
        varint(out, (field << 3) | 2);
        varint(out, bytes.len() as u64);
        out.extend_from_slice(bytes);
    }

    /// ValueInfoProto: a float tensor `name` of shape 1x3x48xW (W symbolic).
    fn value_info(name: &str) -> Vec<u8> {
        let mut shape = Vec::new();
        for dim in [1u64, 3, 48] {
            let mut d = Vec::new();
            field_varint(&mut d, 1, dim);
            field_bytes(&mut shape, 1, &d);
        }
        let mut w = Vec::new();
        field_bytes(&mut w, 2, b"W");
        field_bytes(&mut shape, 1, &w);
        let mut tensor = Vec::new();
        field_varint(&mut tensor, 1, 1); // FLOAT
        field_bytes(&mut tensor, 2, &shape);
        let mut type_proto = Vec::new();
        field_bytes(&mut type_proto, 1, &tensor);
        let mut info = Vec::new();
        field_bytes(&mut info, 1, name.as_bytes());
        field_bytes(&mut info, 2, &type_proto);
        info
    }

    /// A valid ONNX model (opset 13) whose graph is one `op` from `x` to `y`, optionally with the
    /// recogniser's character list in its metadata.
    fn onnx(op: &str, characters: Option<&str>) -> Vec<u8> {
        let mut node = Vec::new();
        field_bytes(&mut node, 1, b"x");
        field_bytes(&mut node, 2, b"y");
        field_bytes(&mut node, 3, b"n");
        field_bytes(&mut node, 4, op.as_bytes());
        let mut graph = Vec::new();
        field_bytes(&mut graph, 1, &node);
        field_bytes(&mut graph, 2, b"g");
        field_bytes(&mut graph, 11, &value_info("x"));
        field_bytes(&mut graph, 12, &value_info("y"));
        let mut opset = Vec::new();
        field_bytes(&mut opset, 1, b"");
        field_varint(&mut opset, 2, 13);
        let mut model = Vec::new();
        field_varint(&mut model, 1, 8);
        field_bytes(&mut model, 8, &opset);
        field_bytes(&mut model, 7, &graph);
        if let Some(text) = characters {
            let mut entry = Vec::new();
            field_bytes(&mut entry, 1, b"character");
            field_bytes(&mut entry, 2, text.as_bytes());
            field_bytes(&mut model, 14, &entry);
        }
        model
    }

    /// Deterministic bytes without a crate: xorshift64.
    fn random_bytes(len: usize) -> Vec<u8> {
        let mut state = 0x9e37_79b9_7f4a_7c15_u64;
        (0..len)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 7;
                state ^= state << 17;
                (state >> 32) as u8
            })
            .collect()
    }

    fn dir() -> PathBuf {
        let dir = Path::new(env!("CARGO_TARGET_TMPDIR")).join("score_engine_invalid_model");
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Loads `bytes` as a model file and returns the answer; the slot must stay empty.
    fn load_bytes(name: &str, bytes: &[u8]) -> String {
        let path = dir().join(name);
        fs::write(&path, bytes).unwrap();
        let path_text = path.to_str().unwrap().to_owned();
        let answer = load_entry(|| Ok(path_text));
        fs::remove_file(&path).unwrap();
        answer
    }

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
            assert_refused("random.onnx", &random_bytes(MODEL_SIZE))
        );

        let without_characters = onnx("Identity", None);
        let answer = assert_refused("identity.onnx", &without_characters);
        assert!(answer.contains("character"), "{answer}");
        println!("identity, no character list: {answer}");

        // A graph that tract can plan, with a character list: whatever the load says, the slot
        // must either stay empty with an error or hold a model the read rejects without a panic.
        for op in ["Identity", "Relu", "NoSuchOp"] {
            let model = onnx(op, Some("0\n1\n+\n/\n"));
            let answer = load_bytes(&format!("{op}.onnx"), &model);
            assert!(!answer.starts_with("ERR: panic"), "{op}: {answer}");
            if answer.starts_with("OK: ") {
                assert_eq!(is_loaded_entry(), CODE_YES);
                let reply = fga_ffi::score_engine::read_entry(|n| Ok(vec![128; n]), 60, 30);
                assert_eq!(
                    reply[1],
                    fga_ffi::score_engine::wire::STATUS_ERROR,
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
        header[..4096].copy_from_slice(&random_bytes(4096));
        println!(
            "real model, first 4096 bytes random: {}",
            assert_refused("header.onnx", &header)
        );
    }
}
