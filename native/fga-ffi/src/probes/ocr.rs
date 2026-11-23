//! Issue #530: the PP-OCRv6 recogniser of `fga-sensing` on a device, tract backend only. Kotlin
//! loads the model once, reads crops, then drops it, and reads the process memory between steps.
//!
//! The loaded recogniser has to outlive a single JNI call, so it sits in a static behind a mutex.

use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;

use fga_sensing::Recognizer;
use fga_sensing::backend::{Backend, MAX_TRACT_THREADS, TractBackend, TractSettings};
use fga_sensing::image::GrayImage;
use fga_sensing::preprocess::check_min_width;

static RECOGNIZER: Mutex<Option<Recognizer<TractBackend>>> = Mutex::new(None);

pub const NOT_LOADED: &str = "no model is loaded; call ocrLoad first";

pub const POISONED: &str =
    "the recogniser lock is poisoned: an earlier call panicked while holding it";

/// A panic while the lock was held (caught by the guard) leaves it poisoned. The recogniser may
/// then be half-updated, so every later call reports that instead of using it.
pub(super) fn recognizer() -> Result<MutexGuard<'static, Option<Recognizer<TractBackend>>>, String>
{
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

/// The recogniser the support-score reader runs on: the tract backend's defaults, as the product
/// engine loads it, so the `rust-reader` device case reads at the product's width. A comparison
/// with the Python results loads through [`ocr_load_tract`] at 320, the width they used.
pub fn ocr_load(model_path: &str) -> Result<String, String> {
    let mut slot = recognizer()?;
    // Dropped before the new model loads, so two models never sit in memory together and a
    // memory reading after a reload shows one model. A failed load therefore leaves none.
    *slot = None;
    let started = Instant::now();
    let backend = TractBackend::load_file(Path::new(model_path)).map_err(|e| e.to_string())?;
    let loaded = Recognizer::new(backend).map_err(|e| e.to_string())?;
    let load_ms = started.elapsed().as_millis();
    let classes = loaded.characters().class_count();
    let min_width = loaded.min_width();
    *slot = Some(loaded);
    Ok(load_json(load_ms, classes, min_width))
}

/// Whether this library was built with fga-ffi's `tract-threads` feature.
pub const TRACT_THREADS_BUILT: bool = cfg!(feature = "tract-threads");

pub const THREADS_NOT_BUILT: &str = "more than one tract thread needs a library built with the \
     tract-threads feature; build with -Pfga.native=true -Pfga.native.threads=true";

/// Issue #549: the arguments of [`ocr_load_tract`], which arrive as JNI ints. Checked here, before
/// the lock is taken and before anything is loaded. A thread count of 0 means tract's default,
/// its single-threaded kernels on the calling thread, as 1 does.
pub fn tract_settings(min_width: i32, threads: i32) -> Result<TractSettings, String> {
    let width = usize::try_from(min_width)
        .map_err(|_| format!("invalid minimum width {min_width}"))
        .and_then(|width| {
            check_min_width(width)
                .map(|()| width)
                .map_err(|e| e.to_string())
        })?;
    let threads = usize::try_from(threads)
        .ok()
        .filter(|&t| t <= MAX_TRACT_THREADS)
        .ok_or_else(|| format!("invalid thread count {threads}: 0..={MAX_TRACT_THREADS}"))?;
    if threads > 1 && !TRACT_THREADS_BUILT {
        // Running on one thread instead would report a single-thread time as a multi-thread one.
        return Err(THREADS_NOT_BUILT.to_owned());
    }
    Ok(TractSettings {
        min_width: width,
        threads: threads.max(1),
    })
}

/// [`ocr_load`] with a minimum model width and a thread count. It fills the same slot, so
/// `ocrRead` and `ocrUnload` serve both.
pub fn ocr_load_tract(model_path: &str, min_width: i32, threads: i32) -> Result<String, String> {
    let settings = tract_settings(min_width, threads)?;
    let mut slot = recognizer()?;
    // As in ocr_load: the old model goes first, so two never sit in memory together.
    *slot = None;
    let started = Instant::now();
    let backend =
        TractBackend::load_file_with(Path::new(model_path), settings).map_err(|e| e.to_string())?;
    let configured = backend.threads();
    let loaded = Recognizer::new(backend)
        .and_then(|loaded| loaded.with_min_width(settings.min_width))
        .map_err(|e| e.to_string())?;
    let load_ms = started.elapsed().as_millis();
    let classes = loaded.characters().class_count();
    let min_width = loaded.min_width();
    *slot = Some(loaded);
    Ok(load_tract_json(load_ms, classes, min_width, configured))
}

/// What the library was built with, so the device suite can skip the thread cases.
pub fn ocr_tract_features() -> Result<String, String> {
    Ok(format!(
        "{{\"threadsBuilt\":{TRACT_THREADS_BUILT},\"maxThreads\":{MAX_TRACT_THREADS}}}"
    ))
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

fn load_json(load_ms: u128, classes: usize, min_width: usize) -> String {
    format!("{{\"loadMs\":{load_ms},\"classes\":{classes},\"minWidth\":{min_width}}}")
}

fn load_tract_json(load_ms: u128, classes: usize, min_width: usize, threads: usize) -> String {
    format!(
        "{{\"loadMs\":{load_ms},\"classes\":{classes},\"minWidth\":{min_width},\"threads\":{threads}}}"
    )
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

pub(super) fn json_string(text: &str) -> String {
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
        let value = json(&load_json(1234, 18710, 320));
        assert_eq!(value["loadMs"], 1234);
        assert_eq!(value["classes"], 18710);
        assert_eq!(value["minWidth"], 320);
        assert_eq!(value.as_object().unwrap().len(), 3);
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
        let text = run_guarded(|| ocr_load_tract("no/such/model.onnx", 160, 1));
        assert!(text.starts_with("ERR: inference backend: "), "{text}");
    }

    #[test]
    fn load_tract_result_shape() {
        let value = json(&load_tract_json(12, 18710, 160, 2));
        assert_eq!(value["loadMs"], 12);
        assert_eq!(value["classes"], 18710);
        assert_eq!(value["minWidth"], 160);
        assert_eq!(value["threads"], 2);
        assert_eq!(value.as_object().unwrap().len(), 4);
    }

    #[test]
    fn features_result_shape() {
        let text = run_guarded(ocr_tract_features);
        let value = json(text.strip_prefix("OK: ").unwrap());
        assert_eq!(value["threadsBuilt"], TRACT_THREADS_BUILT);
        assert_eq!(value["maxThreads"], 16);
    }

    #[test]
    fn tract_width_is_bounded() {
        assert_eq!(tract_settings(320, 1).unwrap().min_width, 320);
        assert_eq!(tract_settings(8, 1).unwrap().min_width, 8);
        assert_eq!(tract_settings(4096, 1).unwrap().min_width, 4096);
        assert_eq!(
            tract_settings(-1, 1),
            Err("invalid minimum width -1".to_owned())
        );
        assert_eq!(
            tract_settings(i32::MIN, 1),
            Err(format!("invalid minimum width {}", i32::MIN))
        );
        for bad in [0, 7, 4097, i32::MAX] {
            assert_eq!(
                tract_settings(bad, 1),
                Err(format!("minimum model width {bad} is not in 8..=4096"))
            );
        }
    }

    // The reader width and the Python results' width, the two the device cases load.
    #[test]
    fn score_reader_widths_are_accepted_and_others_refused() {
        for width in [256, 320] {
            let settings = tract_settings(width, 1).unwrap();
            assert_eq!(settings.min_width, width as usize);
            assert_eq!(settings.threads, 1);
        }
        assert_eq!(
            tract_settings(-256, 1),
            Err("invalid minimum width -256".to_owned())
        );
        for bad in [0, 7, 4097] {
            let refused = format!("minimum model width {bad} is not in 8..=4096");
            assert_eq!(tract_settings(bad, 1), Err(refused.clone()));
            let text = run_guarded(|| ocr_load_tract("no/such/model.onnx", bad, 1));
            assert_eq!(text, format!("ERR: {refused}"));
        }
    }

    #[test]
    fn tract_threads_are_bounded() {
        assert_eq!(tract_settings(320, 0).unwrap().threads, 1);
        assert_eq!(tract_settings(320, 1).unwrap().threads, 1);
        for bad in [-1, 17, i32::MAX, i32::MIN] {
            assert_eq!(
                tract_settings(320, bad),
                Err(format!("invalid thread count {bad}: 0..=16"))
            );
        }
    }

    #[cfg(not(feature = "tract-threads"))]
    #[test]
    fn more_than_one_thread_needs_the_feature() {
        for threads in [2, 4, 16] {
            assert_eq!(
                tract_settings(320, threads),
                Err(THREADS_NOT_BUILT.to_owned())
            );
        }
        let text = run_guarded(|| ocr_load_tract("no/such/model.onnx", 320, 2));
        assert_eq!(text, format!("ERR: {THREADS_NOT_BUILT}"));
    }

    #[cfg(feature = "tract-threads")]
    #[test]
    fn the_feature_takes_more_than_one_thread() {
        for threads in [2, 4, 16] {
            assert_eq!(
                tract_settings(320, threads).unwrap().threads,
                threads as usize
            );
        }
    }

    #[test]
    fn bad_tract_arguments_are_refused_before_loading() {
        let text = run_guarded(|| ocr_load_tract("no/such/model.onnx", 7, 1));
        assert_eq!(text, "ERR: minimum model width 7 is not in 8..=4096");
        let text = run_guarded(|| ocr_load_tract("no/such/model.onnx", 320, 99));
        assert_eq!(text, "ERR: invalid thread count 99: 0..=16");
    }
}
