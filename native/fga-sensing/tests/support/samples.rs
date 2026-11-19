//! Reads the local OCR samples (`FGA_OCR_SAMPLES`): `NNNN.png` crops and their `index.json`.
//! Shared by the model tests and the evaluation tool. The samples hold real-play crops, so they
//! stay under `build/` and are never committed (docs/agents/rust-native.md, "Test data").

// The model tests and the evaluation tool each read a different subset of the fields.
#![allow(dead_code)]

use std::fs::File;
use std::io::BufReader;
use std::path::Path;

use fga_sensing::image::GrayImage;
use serde_json::Value;

pub struct Sample {
    pub file: String,
    pub case: String,
    pub server: String,
    pub kind: String,
    pub is62: Option<bool>,
    pub is16: Option<bool>,
    pub cls: Option<u8>,
    pub grand: Option<u8>,
    /// What the Kotlin reader returned in the stage 0 replay of this crop.
    pub kt_cls: Option<u8>,
    pub kt_grand: Option<u8>,
    pub known_miss: bool,
    pub py_text: String,
    pub py_conf: f64,
}

fn small(row: &Value, key: &str) -> Option<u8> {
    row.get(key)
        .and_then(Value::as_u64)
        .and_then(|v| u8::try_from(v).ok())
}

pub fn load_index(dir: &Path) -> Result<Vec<Sample>, String> {
    let path = dir.join("index.json");
    let text = std::fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?;
    let value: Value = serde_json::from_str(&text).map_err(|e| format!("index.json: {e}"))?;
    let rows = value
        .as_array()
        .ok_or_else(|| "index.json is not a list".to_string())?;
    rows.iter()
        .map(|row| {
            let text = |key: &str| row.get(key).and_then(Value::as_str).map(str::to_owned);
            Ok(Sample {
                file: text("file").ok_or_else(|| format!("row without file: {row}"))?,
                case: text("case").unwrap_or_default(),
                server: text("server").unwrap_or_default(),
                kind: text("kind").unwrap_or_default(),
                is62: row.get("is62").and_then(Value::as_bool),
                is16: row.get("is16").and_then(Value::as_bool),
                cls: small(row, "cls"),
                grand: small(row, "grand"),
                kt_cls: small(row, "kt_cls"),
                kt_grand: small(row, "kt_grand"),
                known_miss: row
                    .get("known_miss")
                    .and_then(Value::as_bool)
                    .unwrap_or(false),
                py_text: text("py_text").unwrap_or_default(),
                py_conf: row.get("py_conf").and_then(Value::as_f64).unwrap_or(0.0),
            })
        })
        .collect()
}

/// Decodes a PNG to gray with OpenCV's `cvtColor` weights, as `cv2.imread(..., IMREAD_GRAYSCALE)`
/// did in the Python check. The samples have equal R, G and B, which this maps to themselves.
pub fn load_gray(path: &Path) -> Result<GrayImage, String> {
    let file = File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut decoder = png::Decoder::new(BufReader::new(file));
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder
        .read_info()
        .map_err(|e| format!("{}: {e}", path.display()))?;
    let size = reader
        .output_buffer_size()
        .ok_or_else(|| format!("{}: image too large", path.display()))?;
    let mut buffer = vec![0u8; size];
    let info = reader
        .next_frame(&mut buffer)
        .map_err(|e| format!("{}: {e}", path.display()))?;
    let channels = info.color_type.samples();
    let pixels: Vec<u8> = buffer
        .get(..info.buffer_size())
        .unwrap_or_default()
        .chunks_exact(channels)
        .map(|px| match px {
            [r, g, b, ..] => {
                let sum = u32::from(*r) * 4899 + u32::from(*g) * 9617 + u32::from(*b) * 1868;
                ((sum + (1 << 13)) >> 14) as u8
            }
            [gray, ..] => *gray,
            [] => 0,
        })
        .collect();
    GrayImage::new(info.width as usize, info.height as usize, pixels)
        .map_err(|e| format!("{}: {e}", path.display()))
}
