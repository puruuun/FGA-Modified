//! Shared by the model tests of the tract and ONNX Runtime probes. Each test file is its own
//! process, which the process-wide recogniser slots need.

#![allow(dead_code)]

// Clippy allows unwrap and panic only in test code, so the helpers sit in a test-only module.
#[cfg(test)]
pub mod helpers {
    use std::fs::File;
    use std::io::BufReader;
    use std::path::{Path, PathBuf};

    use serde_json::Value;

    /// 25 samples spread over the whole set, the same ones fga-sensing's model test uses.
    pub const FIXED: [usize; 25] = [
        0, 15, 30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180, 195, 210, 225, 240, 255, 270, 285,
        300, 315, 330, 345, 360,
    ];

    pub fn local_files(test: &str) -> Option<(PathBuf, PathBuf)> {
        match (
            std::env::var_os("FGA_OCR_MODEL"),
            std::env::var_os("FGA_OCR_SAMPLES"),
        ) {
            (Some(model), Some(samples)) => Some((model.into(), samples.into())),
            (None, None) => {
                println!("{test}: skipped, FGA_OCR_MODEL and FGA_OCR_SAMPLES are not set");
                None
            }
            _ => panic!(
                "set both FGA_OCR_MODEL and FGA_OCR_SAMPLES to run the model test, or neither"
            ),
        }
    }

    /// The samples store gray as equal R, G and B (plus alpha), so the first channel is the gray
    /// value; the device test decodes them the same way.
    pub fn first_channel(path: &Path) -> (Vec<u8>, i32, i32) {
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

    pub fn ok_json(text: &str) -> Value {
        let body = text
            .strip_prefix("OK: ")
            .unwrap_or_else(|| panic!("{text}"));
        serde_json::from_str(body).unwrap()
    }

    pub fn index(dir: &Path) -> Vec<Value> {
        let index: Value =
            serde_json::from_str(&std::fs::read_to_string(dir.join("index.json")).unwrap())
                .unwrap();
        index.as_array().unwrap().clone()
    }

    /// Reads the fixed samples through `read` and checks text and confidence against Python.
    /// Returns how many of them hold text.
    pub fn fixed_samples_match_python(
        dir: &Path,
        rows: &[Value],
        mut read: impl FnMut(Vec<u8>, i32, i32) -> String,
    ) -> usize {
        let mut with_text = 0;
        for &i in &FIXED {
            let row = &rows[i];
            let file = row["file"].as_str().unwrap();
            let expected = row["py_text"].as_str().unwrap_or_default();
            let (pixels, width, height) = first_channel(&dir.join(file));
            let read = ok_json(&read(pixels, width, height));
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
        with_text
    }
}

/// Small ONNX models built here, so tests can fill a recogniser slot without the real model,
/// which CI does not have.
#[cfg(test)]
pub mod onnx {
    use std::fs;
    use std::path::{Path, PathBuf};

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
    pub fn model(op: &str, characters: Option<&str>) -> Vec<u8> {
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

    /// An Identity graph with a character list: every recogniser loader accepts it, and a read on
    /// it is an error, so a test can tell a filled slot from an empty one without the real model.
    pub fn stand_in() -> Vec<u8> {
        model("Identity", Some("0\n1\n+\n/\n"))
    }

    /// Deterministic bytes without a crate: xorshift64.
    pub fn random_bytes(len: usize) -> Vec<u8> {
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

    /// Writes `bytes` under this test binary's temporary folder and returns the path as text.
    pub fn write(test: &str, name: &str, bytes: &[u8]) -> String {
        let dir: PathBuf = Path::new(env!("CARGO_TARGET_TMPDIR")).join(test);
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join(name);
        fs::write(&path, bytes).unwrap();
        path.to_str().unwrap().to_owned()
    }
}
