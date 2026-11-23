//! Issue #535: the same recogniser probes as `ocr.rs`, with ONNX Runtime as the backend instead of
//! tract, so the two can be measured on the same devices. Only built with the `ort-probe`
//! feature; without it these functions and their JNI exports do not exist, and Kotlin reports
//! the cases as skipped when the call throws `UnsatisfiedLinkError`.
//!
//! The ONNX Runtime recogniser has its own slot, so loading one backend never drops the other's
//! model and a tract result can never come from an ONNX Runtime session or the other way round.

use std::num::NonZeroUsize;
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;

use fga_sensing::Recognizer;
use fga_sensing::backend::OrtBackend;

use super::ocr::{crop_from, read_json};
use crate::guard::lock_slot;

static RECOGNIZER: Mutex<Option<Recognizer<OrtBackend>>> = Mutex::new(None);

pub const ORT_NOT_LOADED: &str = "no ONNX Runtime model is loaded; call ocrLoadOrt first";

/// Recovers from a panic like the tract slot (`ocr::recognizer`).
fn recognizer() -> MutexGuard<'static, Option<Recognizer<OrtBackend>>> {
    lock_slot(&RECOGNIZER)
}

#[cfg(feature = "test-hooks")]
#[doc(hidden)]
#[expect(
    clippy::panic,
    reason = "a panic while holding the lock is what poisons it"
)]
pub fn poison_ort_lock_for_tests() -> Result<String, String> {
    let _held = RECOGNIZER.lock();
    panic!("poisoning the ONNX Runtime recogniser lock")
}

/// The largest intra-op thread count accepted. A chosen bound, not a measured one: above the
/// core count of current phone CPUs (10 at most), so a measurement never needs more, while a
/// mistaken argument such as `Int.MAX_VALUE` would have ONNX Runtime try to create that many
/// threads. Checked before the lock is taken, like every argument.
pub const MAX_INTRA_THREADS: i32 = 16;

/// The thread count arrives as a JNI int. 0 is ONNX Runtime's own "use the default", so it is
/// passed on as such; a negative count, or one above [`MAX_INTRA_THREADS`], is refused.
pub fn intra_threads(threads: i32) -> Result<Option<NonZeroUsize>, String> {
    if threads > MAX_INTRA_THREADS {
        return Err(format!(
            "invalid thread count {threads}: at most {MAX_INTRA_THREADS}"
        ));
    }
    usize::try_from(threads)
        .map(NonZeroUsize::new)
        .map_err(|_| format!("invalid thread count {threads}"))
}

pub fn ocr_load_ort(model_path: &str, threads: i32) -> Result<String, String> {
    let threads = intra_threads(threads)?;
    let mut slot = recognizer();
    // As in ocr_load: the old model goes first, so a memory reading after a reload shows one.
    *slot = None;
    let started = Instant::now();
    let backend = OrtBackend::load_file_with_threads(Path::new(model_path), threads)
        .map_err(|e| e.to_string())?;
    let configured = backend.intra_threads();
    let loaded = Recognizer::new(backend).map_err(|e| e.to_string())?;
    let load_ms = started.elapsed().as_millis();
    let classes = loaded.characters().class_count();
    *slot = Some(loaded);
    Ok(load_json(load_ms, classes, configured))
}

pub fn ocr_read_ort(
    pixels: Vec<u8>,
    width: i32,
    height: i32,
    allowed: &str,
) -> Result<String, String> {
    let crop = crop_from(pixels, width, height)?;
    let mut slot = recognizer();
    let loaded = slot.as_mut().ok_or(ORT_NOT_LOADED)?;
    let allowed = if allowed.is_empty() {
        None
    } else {
        Some(loaded.allowed_set(allowed).map_err(|e| e.to_string())?)
    };
    let started = Instant::now();
    let decoded = loaded.recognize(&crop, allowed.as_ref());
    let micros = started.elapsed().as_micros();
    let decoded = decoded.map_err(|e| e.to_string())?;
    Ok(read_json(&decoded.text, decoded.confidence, micros))
}

pub fn ocr_unload_ort() -> Result<String, String> {
    let dropped = recognizer().take().is_some();
    Ok(format!("{{\"dropped\":{dropped}}}"))
}

/// The tract load result plus the thread count the session was built with, read back from the
/// backend; 0 means ONNX Runtime's default.
fn load_json(load_ms: u128, classes: usize, threads: Option<NonZeroUsize>) -> String {
    let threads = threads.map_or(0, NonZeroUsize::get);
    format!("{{\"loadMs\":{load_ms},\"classes\":{classes},\"intraThreads\":{threads}}}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::run_guarded;
    use serde_json::Value;

    #[test]
    fn thread_count_conversion() {
        assert_eq!(intra_threads(0), Ok(None));
        assert_eq!(intra_threads(1), Ok(NonZeroUsize::new(1)));
        assert_eq!(intra_threads(8), Ok(NonZeroUsize::new(8)));
        assert_eq!(
            intra_threads(MAX_INTRA_THREADS),
            Ok(NonZeroUsize::new(MAX_INTRA_THREADS as usize))
        );
        assert_eq!(
            intra_threads(MAX_INTRA_THREADS + 1),
            Err(format!(
                "invalid thread count 17: at most {MAX_INTRA_THREADS}"
            ))
        );
        assert_eq!(
            intra_threads(i32::MAX),
            Err(format!("invalid thread count {}: at most 16", i32::MAX))
        );
        assert_eq!(intra_threads(-1), Err("invalid thread count -1".to_owned()));
        assert_eq!(
            intra_threads(i32::MIN),
            Err(format!("invalid thread count {}", i32::MIN))
        );
    }

    #[test]
    fn load_result_shape() {
        let value: Value =
            serde_json::from_str(&load_json(1234, 18710, NonZeroUsize::new(1))).unwrap();
        assert_eq!(value["loadMs"], 1234);
        assert_eq!(value["classes"], 18710);
        assert_eq!(value["intraThreads"], 1);
        assert_eq!(value.as_object().unwrap().len(), 3);
        let default: Value = serde_json::from_str(&load_json(1, 2, None)).unwrap();
        assert_eq!(default["intraThreads"], 0);
    }

    // As in ocr.rs, nothing below loads a model, so these tests can share the static.

    #[test]
    fn read_before_load_is_an_error() {
        let text = run_guarded(|| ocr_read_ort(vec![0; 6], 3, 2, ""));
        assert_eq!(text, format!("ERR: {ORT_NOT_LOADED}"));
    }

    #[test]
    fn wrong_pixel_count_is_an_error() {
        let text = run_guarded(|| ocr_read_ort(vec![0; 1799], 60, 30, ""));
        assert_eq!(text, "ERR: invalid image: 60x30 with 1799 pixel values");
    }

    #[test]
    fn unload_without_a_model_drops_nothing() {
        assert_eq!(run_guarded(ocr_unload_ort), "OK: {\"dropped\":false}");
    }

    #[test]
    fn too_many_threads_are_refused_before_loading() {
        let text = run_guarded(|| ocr_load_ort("no/such/model.onnx", i32::MAX));
        assert_eq!(
            text,
            format!("ERR: invalid thread count {}: at most 16", i32::MAX)
        );
    }

    #[test]
    fn negative_thread_count_is_refused_before_loading() {
        let text = run_guarded(|| ocr_load_ort("no/such/model.onnx", -2));
        assert_eq!(text, "ERR: invalid thread count -2");
    }

    #[test]
    fn missing_model_is_an_error() {
        let text = run_guarded(|| ocr_load_ort("no/such/model.onnx", 1));
        assert!(text.starts_with("ERR: inference backend: "), "{text}");
    }
}
