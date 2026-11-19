//! Issue #530: the PP-OCRv6 recogniser of `fga-sensing` on a device, tract backend only. Kotlin
//! loads the model once, reads crops, then drops it, and reads the process memory between steps.
//!
//! The loaded recogniser has to outlive a single JNI call, so it sits in a static behind a mutex.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;

use fga_sensing::Recognizer;
use fga_sensing::backend::{Backend, TractBackend};
use fga_sensing::image::GrayImage;

static RECOGNIZER: Mutex<Option<Recognizer<TractBackend>>> = Mutex::new(None);

pub const NOT_LOADED: &str = "no model is loaded; call ocrLoad first";

pub const POISONED: &str =
    "the recogniser lock is poisoned: an earlier call panicked while holding it";

/// A panic while the lock was held (caught by the guard) leaves it poisoned. The recogniser may
/// then be half-updated, so every later call reports that instead of using it.
fn recognizer() -> Result<MutexGuard<'static, Option<Recognizer<TractBackend>>>, String> {
    RECOGNIZER.lock().map_err(|_| POISONED.to_owned())
}

/// Only for tests/ocr_poisoned.rs: no recogniser call panics on demand, and the poisoned-lock
/// path must still be exercised. It has no JNI export, and the `test-hooks` feature keeps it out
/// of every build but the tests.
#[cfg(feature = "test-hooks")]
#[doc(hidden)]
#[expect(
    clippy::panic,
    reason = "a panic while holding the lock is what poisons it"
)]
pub fn poison_lock_for_tests() -> Result<String, String> {
    let _held = RECOGNIZER.lock();
    panic!("poisoning the recogniser lock")
}

pub fn ocr_load(model_path: &str) -> Result<String, String> {
    let mut slot = recognizer()?;
    // Dropped before the new model loads, so two models never sit in memory together and a
    // memory reading after a reload shows one model. A failed load therefore leaves none.
    *slot = None;
    let started = Instant::now();
    let backend = TractBackend::load_file(Path::new(model_path)).map_err(|e| e.to_string())?;
    let loaded = Recognizer::new(backend);
    let load_ms = started.elapsed().as_millis();
    let classes = loaded.characters().class_count();
    *slot = Some(loaded);
    Ok(load_json(load_ms, classes))
}

/// `width` and `height` arrive as JNI ints, so a negative value is checked here, not assumed away.
/// `allowed` empty means no restriction.
pub fn ocr_read(pixels: Vec<u8>, width: i32, height: i32, allowed: &str) -> Result<String, String> {
    let crop = crop_from(pixels, width, height)?;
    let mut slot = recognizer()?;
    let loaded = slot.as_mut().ok_or(NOT_LOADED)?;
    let allowed = if allowed.is_empty() {
        None
    } else {
        Some(loaded.allowed_set(allowed).map_err(|e| e.to_string())?)
    };
    // Only the recogniser itself is timed: JNI copies and the allowed set are not engine cost.
    let started = Instant::now();
    let decoded = loaded.recognize(&crop, allowed.as_ref());
    let micros = started.elapsed().as_micros();
    let decoded = decoded.map_err(|e| e.to_string())?;
    Ok(read_json(&decoded.text, decoded.confidence, micros))
}

/// Built before the lock is taken, so a malformed crop is reported even when no model is loaded.
/// `GrayImage::new` is the pixel-count check.
pub(super) fn crop_from(pixels: Vec<u8>, width: i32, height: i32) -> Result<GrayImage, String> {
    let (Ok(w), Ok(h)) = (usize::try_from(width), usize::try_from(height)) else {
        return Err(format!("invalid crop size {width}x{height}"));
    };
    GrayImage::new(w, h, pixels).map_err(|e| e.to_string())
}

pub fn ocr_unload() -> Result<String, String> {
    let dropped = recognizer()?.take().is_some();
    Ok(format!("{{\"dropped\":{dropped}}}"))
}

/// The kernel's own counters for this process: resident now, resident peak, and virtual size.
pub fn memory_status() -> Result<String, String> {
    let text = std::fs::read_to_string("/proc/self/status")
        .map_err(|e| format!("cannot read /proc/self/status: {e}"))?;
    memory_from_status(&text)
}

fn memory_from_status(text: &str) -> Result<String, String> {
    let field = |name: &str| {
        text.lines().find_map(|line| {
            line.strip_prefix(name)?
                .strip_prefix(':')?
                .trim()
                .strip_suffix("kB")?
                .trim()
                .parse::<u64>()
                .ok()
        })
    };
    match (field("VmRSS"), field("VmHWM"), field("VmSize")) {
        (Some(rss), Some(hwm), Some(size)) => Ok(format!(
            "{{\"vmRssKb\":{rss},\"vmHwmKb\":{hwm},\"vmSizeKb\":{size}}}"
        )),
        (rss, hwm, size) => Err(format!(
            "/proc/self/status lacks a kB value for{}{}{}",
            if rss.is_none() { " VmRSS" } else { "" },
            if hwm.is_none() { " VmHWM" } else { "" },
            if size.is_none() { " VmSize" } else { "" },
        )),
    }
}

pub(super) fn load_json(load_ms: u128, classes: usize) -> String {
    format!("{{\"loadMs\":{load_ms},\"classes\":{classes}}}")
}

pub(super) fn read_json(text: &str, confidence: f64, micros: u128) -> String {
    // JSON has no NaN or infinity; the decoder never produces them, but the format must hold anyway.
    let confidence = if confidence.is_finite() {
        confidence.to_string()
    } else {
        "null".to_owned()
    };
    format!(
        "{{\"text\":{},\"confidence\":{confidence},\"micros\":{micros}}}",
        json_string(text)
    )
}

fn json_string(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if u32::from(c) < 0x20 => out.push_str(&format!("\\u{:04x}", u32::from(c))),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::run_guarded;
    use serde_json::Value;

    fn json(text: &str) -> Value {
        serde_json::from_str(text).unwrap()
    }

    #[test]
    fn load_result_shape() {
        let value = json(&load_json(1234, 18710));
        assert_eq!(value["loadMs"], 1234);
        assert_eq!(value["classes"], 18710);
        assert_eq!(value.as_object().unwrap().len(), 2);
    }

    #[test]
    fn read_result_shape() {
        let value = json(&read_json("+62/+16", 0.99761, 27000));
        assert_eq!(value["text"], "+62/+16");
        assert_eq!(value["confidence"], 0.99761);
        assert_eq!(value["micros"], 27000);
        assert_eq!(value.as_object().unwrap().len(), 3);
    }

    #[test]
    fn read_result_escapes_text() {
        let text = "a\"b\\c\nd\u{1}屋";
        assert_eq!(json(&read_json(text, 0.5, 1))["text"], text);
        assert!(json(&read_json("", f64::NAN, 1))["confidence"].is_null());
    }

    #[test]
    fn unload_result_shape() {
        let text = run_guarded(ocr_unload);
        let value = json(text.strip_prefix("OK: ").unwrap());
        assert!(value["dropped"].is_boolean(), "{text}");
    }

    #[test]
    fn memory_result_shape() {
        let status = "Name:\tapp\nVmPeak:\t  999 kB\nVmSize:\t  2048 kB\nVmHWM:\t   300 kB\nVmRSS:\t   200 kB\n";
        let value = json(&memory_from_status(status).unwrap());
        assert_eq!(value["vmRssKb"], 200);
        assert_eq!(value["vmHwmKb"], 300);
        assert_eq!(value["vmSizeKb"], 2048);
        assert_eq!(value.as_object().unwrap().len(), 3);
    }

    #[test]
    fn memory_names_missing_fields() {
        assert_eq!(
            memory_from_status("VmRSS:\t1 kB\n"),
            Err("/proc/self/status lacks a kB value for VmHWM VmSize".to_owned())
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn memory_status_reads_this_process() {
        let text = run_guarded(memory_status);
        assert!(
            json(text.strip_prefix("OK: ").unwrap())["vmRssKb"]
                .as_u64()
                .unwrap()
                > 0
        );
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    #[test]
    fn memory_status_says_it_cannot_read() {
        let text = run_guarded(memory_status);
        assert!(
            text.starts_with("ERR: cannot read /proc/self/status"),
            "{text}"
        );
    }

    // The tests below never load a model successfully, so they can share the static with each
    // other. Tests that load the real model live in tests/ocr_model.rs, a separate process.

    #[test]
    fn read_before_load_is_an_error() {
        let text = run_guarded(|| ocr_read(vec![0; 6], 3, 2, ""));
        assert_eq!(text, format!("ERR: {NOT_LOADED}"));
    }

    #[test]
    fn wrong_pixel_count_is_an_error() {
        let text = run_guarded(|| ocr_read(vec![0; 1799], 60, 30, ""));
        assert_eq!(text, "ERR: invalid image: 60x30 with 1799 pixel values");
    }

    #[test]
    fn negative_size_is_an_error() {
        let text = run_guarded(|| ocr_read(vec![], -1, 30, ""));
        assert_eq!(text, "ERR: invalid crop size -1x30");
    }

    // Unloading a loaded model is checked in tests/ocr_model.rs, which has the model.
    #[test]
    fn unload_without_a_model_drops_nothing() {
        assert_eq!(run_guarded(ocr_unload), "OK: {\"dropped\":false}");
        let text = run_guarded(|| ocr_read(vec![0; 6], 3, 2, ""));
        assert_eq!(text, format!("ERR: {NOT_LOADED}"));
    }

    #[test]
    fn missing_model_is_an_error() {
        let text = run_guarded(|| ocr_load("no/such/model.onnx"));
        assert!(text.starts_with("ERR: inference backend: "), "{text}");
    }
}
