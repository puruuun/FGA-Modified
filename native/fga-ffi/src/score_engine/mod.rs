//! Issue #568: the support-score reader for product code (the Kotlin class
//! `io.github.fate_grand_automata.sensing.RustSensing`), apart from the device probes.
//!
//! Slot rule: the engine sits in its own static, not in the probes' recogniser slot. A probe load,
//! read or unload never touches it, and a product load or unload never touches the probes. A
//! second load while one is loaded is refused and keeps the loaded engine, so two models never
//! sit in memory together and nobody's engine is swapped under them; the owner unloads first.
//!
//! The slot holds a [`ScoreModel`], the real one being the PP-OCRv6 recogniser; the trait exists so
//! that host tests (the `test-hooks` feature) can put a marker engine into the slot without a model
//! file, which CI does not have.
//!
//! A call that panics while it holds the engine lock answers with an error like any other failure,
//! and the model it was using is dropped: the next call finds the slot empty (see
//! `guard::lock_slot`), so `unload` answers that nothing was loaded and a later `load` loads again.
//!
//! The `*_entry` functions are what the JNI exports call: each runs the whole call, the reading of
//! the Java arguments included, inside the panic guard, so a host test can drive the guard
//! through the same path as the export.

pub mod wire;

use std::path::Path;
use std::sync::{Mutex, MutexGuard, TryLockError};
use std::time::Instant;

use fga_sensing::Recognizer;
use fga_sensing::backend::{TractBackend, TractSettings};
use fga_sensing::image::GrayImage;
use fga_sensing::readers::support_score::{
    EXPECTED_CROP_SIZE, Reason, ScoreConfig, SupportScore, read_with,
};

use crate::guard::{catch_panic, lock_slot, recover_slot, run_guarded};

/// What the engine slot holds: something that reads one 60x30 crop with the default `ScoreConfig`.
pub trait ScoreModel: Send {
    fn read(&mut self, crop: &GrayImage) -> Result<SupportScore, String>;
}

impl ScoreModel for Recognizer<TractBackend> {
    fn read(&mut self, crop: &GrayImage) -> Result<SupportScore, String> {
        read_with(self, &ScoreConfig::default(), crop).map_err(|e| e.to_string())
    }
}

type Slot = Option<Box<dyn ScoreModel>>;

static ENGINE: Mutex<Slot> = Mutex::new(None);

pub const NOT_LOADED: &str = "the support-score engine is not loaded";
pub const ALREADY_LOADED: &str =
    "the support-score engine is already loaded; unload it before loading again";

/// `unload` and `isLoaded` answer with these; anything else is an error on the Kotlin side.
pub const CODE_NO: i32 = 0;
pub const CODE_YES: i32 = 1;
/// `isLoaded` only: another call holds the engine lock (a load or a read), so the slot is in use.
pub const CODE_BUSY: i32 = 2;
/// A caught panic.
pub const CODE_ERROR: i32 = -1;
/// The call left a Java exception pending, which the export cleared.
pub const CODE_PENDING_EXCEPTION: i32 = -2;

fn engine() -> MutexGuard<'static, Slot> {
    lock_slot(&ENGINE)
}

/// Only for tests/score_engine_poisoned.rs: a panic while holding the lock with nothing loaded;
/// the `test-hooks` feature keeps it out of every build but the tests, as `poison_lock_for_tests`
/// for the probes.
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

/// Only for tests/score_engine_busy.rs: runs `body` while this thread holds the engine lock, as a
/// load or a read would.
#[cfg(feature = "test-hooks")]
#[doc(hidden)]
pub fn with_engine_locked_for_tests<R>(body: impl FnOnce() -> R) -> Result<R, String> {
    let _held = engine();
    Ok(body())
}

/// Only for tests/score_engine_hooks.rs: fills the slot with `model` as `load` would, refusing a
/// filled slot as `load` does, so the slot rules can be checked without a model file.
#[cfg(feature = "test-hooks")]
#[doc(hidden)]
pub fn install_for_tests(model: Box<dyn ScoreModel>) -> Result<String, String> {
    let mut slot = engine();
    if slot.is_some() {
        return Err(ALREADY_LOADED.to_owned());
    }
    *slot = Some(model);
    Ok("0".to_owned())
}

/// The tract backend's defaults: the reader width (320) on one thread, the setting the stored
/// samples, the device probe and the live scans were measured with (docs/qa/0025-stage-0-baseline.md,
/// sections 4.15 and 4.16). A test pins the settings, so a change of the default reaches the
/// product only on purpose.
pub fn settings() -> TractSettings {
    TractSettings::default()
}

/// Loads the model at `model_path` with the tract backend at [`settings`]; the recogniser pads to
/// the width the backend planned. Returns the load time in milliseconds.
pub fn load(model_path: &str) -> Result<String, String> {
    let mut slot = engine();
    if slot.is_some() {
        return Err(ALREADY_LOADED.to_owned());
    }
    let started = Instant::now();
    let backend = TractBackend::load_file_with(Path::new(model_path), settings())
        .map_err(|e| e.to_string())?;
    *slot = Some(Box::new(
        Recognizer::new(backend).map_err(|e| e.to_string())?,
    ));
    Ok(started.elapsed().as_millis().to_string())
}

pub fn unload() -> Result<bool, String> {
    Ok(engine().take().is_some())
}

/// `Some(filled)`, or `None` when another call holds the lock. It never waits: Kotlin polls it
/// while an earlier run's engine may still be loading or reading, and a wait here would make that
/// poll as slow as the call it waits for.
pub fn is_loaded() -> Result<Option<bool>, String> {
    match ENGINE.try_lock() {
        Ok(slot) => Ok(Some(slot.is_some())),
        Err(TryLockError::WouldBlock) => Ok(None),
        Err(TryLockError::Poisoned(poisoned)) => {
            Ok(Some(recover_slot(&ENGINE, poisoned).is_some()))
        }
    }
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
    let mut slot = engine();
    let loaded = slot.as_mut().ok_or(NOT_LOADED)?;
    let started = Instant::now();
    let score = loaded.read(&crop)?;
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
    match catch_panic(is_loaded) {
        Ok(Some(filled)) => code(Ok(filled)),
        Ok(None) => CODE_BUSY,
        Err(_) => CODE_ERROR,
    }
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

    /// Held by every test here that takes the engine lock or asks `is_loaded`, which answers busy
    /// instead of waiting while another test holds the lock (issue #591).
    static SLOT_TESTS: Mutex<()> = Mutex::new(());

    fn slot_test() -> MutexGuard<'static, ()> {
        SLOT_TESTS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    #[test]
    fn the_engine_plans_the_reader_width_on_one_thread() {
        assert_eq!(
            settings(),
            TractSettings {
                min_width: 320,
                threads: 1
            }
        );
    }

    fn text_of(wire: &[i32]) -> String {
        wire[wire::HEADER_LEN..]
            .iter()
            .map(|c| char::from_u32(*c as u32).unwrap())
            .collect()
    }

    #[test]
    fn read_before_load_is_an_error() {
        let _slot = slot_test();
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
        let _slot = slot_test();
        let text = load_entry(|| Ok("no/such/model.onnx".to_owned()));
        assert!(text.starts_with("ERR: inference backend: "), "{text}");
        assert_eq!(is_loaded_entry(), CODE_NO);
    }

    #[test]
    fn unload_while_unloaded_drops_nothing() {
        let _slot = slot_test();
        assert_eq!(unload_entry(), CODE_NO);
        assert_eq!(is_loaded_entry(), CODE_NO);
    }

    #[test]
    fn a_pending_exception_turns_any_code_into_its_own() {
        for code in [CODE_NO, CODE_YES, CODE_ERROR] {
            assert_eq!(after_pending_exception_code(code), CODE_PENDING_EXCEPTION);
        }
    }
}
