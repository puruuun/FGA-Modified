//! Inference runtimes for the recognition model, each behind its own Cargo feature.
//!
//! Issue #524 compares two: `tract` (pure Rust, already run on our devices in the stage 1-0
//! probes) and ONNX Runtime through `ort`. Both stay off by default, so a build that picks
//! neither carries no runtime at all.

use std::path::Path;

use crate::Error;
use crate::charset::CharacterList;
use crate::ctc::Probabilities;
use crate::preprocess::InputTensor;

#[cfg(feature = "ort")]
mod ort;
#[cfg(feature = "tract")]
mod tract;

#[cfg(feature = "ort")]
pub use self::ort::OrtBackend;
#[cfg(feature = "tract")]
pub use self::tract::TractBackend;

/// One loaded model. Loading is the expensive part, so a backend is built once and then run for
/// every crop.
pub trait Backend: Sized {
    fn load_file(path: &Path) -> Result<Self, Error>;

    fn load_bytes(bytes: &[u8]) -> Result<Self, Error>;

    /// Runs one crop and returns the per-step class probabilities.
    fn run(&mut self, input: &InputTensor) -> Result<Probabilities, Error>;

    /// The character list from the model's own metadata.
    fn characters(&self) -> &CharacterList;
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

#[cfg(any(feature = "tract", feature = "ort"))]
fn backend_error(error: impl std::fmt::Display) -> Error {
    Error::Backend(format!("{error:#}"))
}
