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
    /// A requested size is zero.
    InvalidSize { width: usize, height: usize },
    /// An image is past the size the recogniser accepts (see `preprocess::MAX_CROP_WIDTH`).
    ImageTooLarge {
        width: usize,
        height: usize,
        max_width: usize,
        max_height: usize,
    },
    /// The crop is so wide for its height that the model input would pass its limit.
    InputTooWide { width: usize, max: usize },
    /// The upscale factor is zero or past `preprocess::MAX_UPSCALE`.
    InvalidUpscale { factor: usize, max: usize },
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
    /// The allowed set was built for a different character list.
    AllowedSetMismatch,
    /// The model output holds NaN or infinity, first at this step and class.
    NonFiniteOutput { step: usize, class: usize },
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
            Error::ImageTooLarge {
                width,
                height,
                max_width,
                max_height,
            } => write!(
                f,
                "image {width}x{height} is larger than the limit {max_width}x{max_height}"
            ),
            Error::InputTooWide { width, max } => write!(
                f,
                "the crop would need a model input {width} wide, past the limit {max}"
            ),
            Error::InvalidUpscale { factor, max } => {
                write!(f, "upscale factor {factor} is not in 1..={max}")
            }
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
            Error::AllowedSetMismatch => write!(
                f,
                "the allowed character set was built for another model's character list"
            ),
            Error::NonFiniteOutput { step, class } => write!(
                f,
                "the model output is not a finite number at step {step}, class {class}"
            ),
            Error::Backend(message) => write!(f, "inference backend: {message}"),
        }
    }
}

impl std::error::Error for Error {}
