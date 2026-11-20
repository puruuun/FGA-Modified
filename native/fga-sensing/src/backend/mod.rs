//! Inference runtimes for the recognition model, each behind its own Cargo feature.
//!
//! Issue #524 compares two: `tract` (pure Rust, already run on our devices in the stage 1-0
//! probes) and ONNX Runtime through `ort`. Both stay off by default, so a build that picks
//! neither carries no runtime at all.

use std::path::Path;

use crate::Error;
use crate::charset::CharacterList;
use crate::ctc::Probabilities;
use crate::preprocess::{InputTensor, MIN_MODEL_WIDTH};

#[cfg(feature = "ort")]
mod ort;
#[cfg(feature = "tract")]
mod tract;

#[cfg(feature = "ort")]
pub use self::ort::OrtBackend;
#[cfg(feature = "tract")]
pub use self::tract::{MAX_TRACT_THREADS, READER_MIN_WIDTH, TractBackend, TractSettings};

/// One loaded model. Loading is the expensive part, so a backend is built once and then run for
/// every crop.
pub trait Backend: Sized {
    fn load_file(path: &Path) -> Result<Self, Error>;

    fn load_bytes(bytes: &[u8]) -> Result<Self, Error>;

    /// Runs one crop and returns the per-step class probabilities.
    fn run(&mut self, input: &InputTensor) -> Result<Probabilities, Error>;

    /// The character list from the model's own metadata.
    fn characters(&self) -> &CharacterList;

    /// The width a [`crate::Recognizer`] pads short crops to unless it is given another one. A
    /// backend that plans its input width at load gives that width, so the recogniser never asks
    /// it for a plan it does not have; any other keeps RapidOCR's.
    fn min_width(&self) -> usize {
        MIN_MODEL_WIDTH
    }
}

/// Turns a `[1, steps, classes]` output into [`Probabilities`].
#[cfg(any(feature = "tract", feature = "ort"))]
fn probabilities_from(shape: &[usize], data: Vec<f32>) -> Result<Probabilities, Error> {
    match shape {
        [1, steps, classes] => Probabilities::new(*steps, *classes, data),
        _ => Err(Error::Backend(format!(
            "expected an output of shape [1, steps, classes], got {shape:?}"
        ))),
    }
}

/// ONNX Runtime reports dimensions as i64. A negative one (ONNX's "unknown") must not become a
/// size of zero, which would read as an empty output instead of a broken one.
#[cfg_attr(not(feature = "ort"), allow(dead_code))]
fn dimensions(shape: &[i64]) -> Result<Vec<usize>, Error> {
    shape
        .iter()
        .map(|&d| {
            usize::try_from(d)
                .map_err(|_| Error::Backend(format!("invalid output dimension {d} in {shape:?}")))
        })
        .collect()
}

#[cfg(any(feature = "tract", feature = "ort"))]
fn backend_error(error: impl std::fmt::Display) -> Error {
    Error::Backend(format!("{error:#}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dimensions_convert_when_valid() {
        assert_eq!(dimensions(&[1, 40, 18710]), Ok(vec![1, 40, 18710]));
        assert_eq!(dimensions(&[]), Ok(vec![]));
    }

    #[test]
    fn negative_dimensions_are_an_error() {
        assert!(matches!(
            dimensions(&[1, -1, 18710]),
            Err(Error::Backend(_))
        ));
        assert!(matches!(dimensions(&[i64::MIN]), Err(Error::Backend(_))));
    }

    #[cfg(target_pointer_width = "32")]
    #[test]
    fn dimensions_past_usize_are_an_error() {
        assert!(matches!(dimensions(&[i64::MAX]), Err(Error::Backend(_))));
    }
}
