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
