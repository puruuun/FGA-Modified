use std::fmt;

/// Everything that can go wrong between a crop and its text.
///
/// Backend failures carry the runtime's message as text, so callers do not depend on the error
/// types of whichever runtime is compiled in.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// Width or height is zero, or the pixel count does not match them.
    InvalidImage {
        width: usize,
        height: usize,
        pixels: usize,
    },
    /// A requested size or scale factor is zero or would overflow.
    InvalidSize { width: usize, height: usize },
    /// The character list text holds no characters.
    EmptyCharacterList,
    /// The model carries no character list in its metadata.
    MissingCharacterList,
    /// An allowed character is not one the model can produce.
    UnknownCharacter(char),
    /// The model output does not have the shape the decoder needs.
    InvalidOutput {
        steps: usize,
        classes: usize,
        values: usize,
    },
    /// The model's class count does not match its character list.
    ClassCountMismatch { model: usize, characters: usize },
    /// The inference runtime failed to load or run the model.
    Backend(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::InvalidImage {
                width,
                height,
                pixels,
            } => write!(
                f,
                "invalid image: {width}x{height} with {pixels} pixel values"
            ),
            Error::InvalidSize { width, height } => write!(f, "invalid size {width}x{height}"),
            Error::EmptyCharacterList => write!(f, "the character list is empty"),
            Error::MissingCharacterList => {
                write!(f, "the model has no `character` metadata entry")
            }
            Error::UnknownCharacter(c) => {
                write!(f, "the model cannot produce the character {c:?}")
            }
            Error::InvalidOutput {
                steps,
                classes,
                values,
            } => write!(
                f,
                "invalid model output: {steps} steps x {classes} classes with {values} values"
            ),
            Error::ClassCountMismatch { model, characters } => write!(
                f,
                "the model has {model} classes but its character list gives {characters}"
            ),
            Error::Backend(message) => write!(f, "inference backend: {message}"),
        }
    }
}

impl std::error::Error for Error {}
