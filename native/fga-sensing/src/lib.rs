//! Frame and text recognition of the FGA Rust core.
//!
//! For now it holds the PaddleOCR text recogniser of issue #524: preprocessing a grayscale crop
//! into the model input, CTC decoding of the model output, and the inference backends behind
//! Cargo features (`tract`, `ort`). Nothing here knows about Android or JNI, so all of it is
//! tested on a PC.

#![forbid(unsafe_code)]

pub mod backend;
pub mod charset;
pub mod ctc;
mod error;
pub mod image;
pub mod preprocess;
pub mod readers;
mod recognizer;

pub use error::Error;
pub use recognizer::Recognizer;
