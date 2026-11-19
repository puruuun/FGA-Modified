//! Issue #568: the support-score reader for product code (the Kotlin class
//! `io.github.fate_grand_automata.sensing.RustSensing`), apart from the device probes.
//!
//! Slot rule: the engine sits in its own static, not in the probes' recogniser slot. A probe load,
//! read or unload never touches it, and a product load or unload never touches the probes. A
//! second load while one is loaded is refused and keeps the loaded engine, so two models never
//! sit in memory together and nobody's engine is swapped under them; the owner unloads first.
//!
//! The `*_entry` functions are what the JNI exports call: each runs the whole call, the reading of
//! the Java arguments included, inside the panic guard, so a host test can drive the guard
//! through the same path as the export.

pub mod wire;

use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::Instant;

use fga_sensing::Recognizer;
use fga_sensing::backend::{Backend, TractBackend};
use fga_sensing::image::GrayImage;
use fga_sensing::readers::support_score::{EXPECTED_CROP_SIZE, Reason, ScoreConfig, read_with};

use crate::guard::{catch_panic, run_guarded};

static ENGINE: Mutex<Option<Recognizer<TractBackend>>> = Mutex::new(None);

pub const NOT_LOADED: &str = "the support-score engine is not loaded";
pub const ALREADY_LOADED: &str =
    "the support-score engine is already loaded; unload it before loading again";
pub const POISONED: &str =
    "the support-score engine lock is poisoned: an earlier call panicked while holding it";

/// `unload` and `isLoaded` answer with these; anything else is an error on the Kotlin side.
pub const CODE_NO: i32 = 0;
pub const CODE_YES: i32 = 1;
/// A poisoned lock or a caught panic.
pub const CODE_ERROR: i32 = -1;
/// The call left a Java exception pending, which the export cleared.
pub const CODE_PENDING_EXCEPTION: i32 = -2;

fn engine() -> Result<MutexGuard<'static, Option<Recognizer<TractBackend>>>, String> {
    ENGINE.lock().map_err(|_| POISONED.to_owned())
}

/// Only for tests/score_engine_poisoned.rs; the `test-hooks` feature keeps it out of every build
/// but the tests, as `poison_lock_for_tests` for the probes.
#[cfg(feature = "test-hooks")]
#[doc(hidden)]
#[expect(
    clippy::panic,
    reason = "a panic while holding the lock is what poisons it"
)]
pub fn poison_engine_lock_for_tests() -> Result<String, String> {
    let _held = ENGINE.lock();
    panic!("poisoning the support-score engine lock")
}

/// Loads the model at `model_path` with the tract backend at the reader's defaults (320 wide, one
/// thread, the settings the reader was measured with). Returns the load time in milliseconds.
pub fn load(model_path: &str) -> Result<String, String> {
    let mut slot = engine()?;
    if slot.is_some() {
        return Err(ALREADY_LOADED.to_owned());
    }
    let started = Instant::now();
    let backend = TractBackend::load_file(Path::new(model_path)).map_err(|e| e.to_string())?;
    *slot = Some(Recognizer::new(backend));
    Ok(started.elapsed().as_millis().to_string())
}

pub fn unload() -> Result<bool, String> {
    Ok(engine()?.take().is_some())
}

pub fn is_loaded() -> Result<bool, String> {
    Ok(engine()?.is_some())
}

/// One score field. `fetch` copies the pixels out of Java; it receives the only length a 60x30
/// crop can have and refuses any other before it allocates. A crop of another size is answered
/// as unknown (`crop_size`) without touching the pixels, the lock or the model.
pub fn read<F>(fetch: F, width: i32, height: i32) -> Result<Vec<i32>, String>
where
    F: FnOnce(usize) -> Result<Vec<u8>, String>,
{
    let (expected_width, expected_height) = EXPECTED_CROP_SIZE;
    if usize::try_from(width).ok() != Some(expected_width)
        || usize::try_from(height).ok() != Some(expected_height)
    {
        return Ok(wire::encode_unknown(Reason::CropSize));
    }
    let length = expected_width * expected_height;
    let pixels = fetch(length)?;
    let crop =
        GrayImage::new(expected_width, expected_height, pixels).map_err(|e| e.to_string())?;
    let mut slot = engine()?;
    let loaded = slot.as_mut().ok_or(NOT_LOADED)?;
    let started = Instant::now();
    let score = read_with(loaded, &ScoreConfig::default(), &crop).map_err(|e| e.to_string())?;
    Ok(wire::encode_score(&score, started.elapsed().as_micros()))
}

pub fn load_entry<F>(fetch_path: F) -> String
where
    F: FnOnce() -> Result<String, String>,
{
    run_guarded(|| load(&fetch_path()?))
}

pub fn read_entry<F>(fetch: F, width: i32, height: i32) -> Vec<i32>
where
    F: FnOnce(usize) -> Result<Vec<u8>, String>,
{
    catch_panic(|| read(fetch, width, height)).unwrap_or_else(|e| wire::encode_error(&e))
}

fn code(result: Result<bool, String>) -> i32 {
    match result {
        Ok(true) => CODE_YES,
        Ok(false) => CODE_NO,
        Err(_) => CODE_ERROR,
    }
}

pub fn unload_entry() -> i32 {
    code(catch_panic(unload))
}

pub fn is_loaded_entry() -> i32 {
    code(catch_panic(is_loaded))
}

/// What an `int` answer becomes when a Java exception was pending after the call.
pub fn after_pending_exception_code(_code: i32) -> i32 {
    CODE_PENDING_EXCEPTION
}

// Tests that load the real model are in tests/score_engine_model.rs, a process of their own; the
// ones here never load one, so they can share the static.
#[cfg(test)]
mod tests {
    use super::*;

    fn text_of(wire: &[i32]) -> String {
        wire[wire::HEADER_LEN..]
            .iter()
            .map(|c| char::from_u32(*c as u32).unwrap())
            .collect()
    }

    #[test]
    fn read_before_load_is_an_error() {
        let wire = read_entry(|n| Ok(vec![0; n]), 60, 30);
        assert_eq!(wire[1], wire::STATUS_ERROR);
        assert_eq!(text_of(&wire), NOT_LOADED);
    }

    #[test]
    fn another_size_is_crop_size_without_touching_the_pixels() {
        for (w, h) in [
            (59, 30),
            (60, 31),
            (0, 0),
            (-60, 30),
            (60, -30),
            (i32::MAX, 30),
        ] {
            let wire = read_entry(|_| panic!("fetched pixels for {w}x{h}"), w, h);
            assert_eq!(wire, wire::encode_unknown(Reason::CropSize), "{w}x{h}");
        }
    }

    #[test]
    fn the_fetch_gets_the_crop_length_and_its_error_is_kept() {
        let wire = read_entry(
            |n| {
                assert_eq!(n, 1800);
                Err("pixels: array of 5 bytes, expected 1800".to_owned())
            },
            60,
            30,
        );
        assert_eq!(wire[1], wire::STATUS_ERROR);
        assert_eq!(text_of(&wire), "pixels: array of 5 bytes, expected 1800");
    }

    #[test]
    fn a_short_array_from_the_fetch_is_an_error() {
        let wire = read_entry(|_| Ok(vec![0; 1799]), 60, 30);
        assert_eq!(wire[1], wire::STATUS_ERROR);
        assert_eq!(
            text_of(&wire),
            "invalid image: 60x30 with 1799 pixel values"
        );
    }

    #[test]
    fn a_panic_in_a_read_is_an_error_reply() {
        let wire = read_entry(|_| panic!("boom in fetch"), 60, 30);
        assert_eq!(wire[1], wire::STATUS_ERROR);
        assert_eq!(text_of(&wire), "panic: boom in fetch");
    }

    #[test]
    fn a_panic_in_a_load_is_an_error() {
        assert_eq!(
            load_entry(|| panic!("boom in path")),
            "ERR: panic: boom in path"
        );
        assert_eq!(
            load_entry(|| Err("modelPath: null".to_owned())),
            "ERR: modelPath: null"
        );
    }

    #[test]
    fn missing_model_is_an_error_and_loads_nothing() {
        let text = load_entry(|| Ok("no/such/model.onnx".to_owned()));
        assert!(text.starts_with("ERR: inference backend: "), "{text}");
        assert_eq!(is_loaded_entry(), CODE_NO);
    }

    #[test]
    fn unload_while_unloaded_drops_nothing() {
        assert_eq!(unload_entry(), CODE_NO);
        assert_eq!(is_loaded_entry(), CODE_NO);
    }

    #[test]
    fn a_pending_exception_turns_any_code_into_its_own() {
        for code in [CODE_NO, CODE_YES, CODE_ERROR] {
            assert_eq!(after_pending_exception_code(code), CODE_PENDING_EXCEPTION);
        }
    }

    #[test]
    fn probe_calls_never_reach_the_engine_slot() {
        assert_eq!(
            crate::run_guarded(crate::probes::ocr_unload),
            "OK: {\"dropped\":false}"
        );
        assert_eq!(is_loaded_entry(), CODE_NO);
    }
}
