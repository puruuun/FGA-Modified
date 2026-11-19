use crate::Error;
use crate::backend::Backend;
use crate::charset::CharacterList;
use crate::ctc::{AllowedSet, Decoded, decode};
use crate::image::GrayImage;
use crate::preprocess::{DEFAULT_UPSCALE, MIN_MODEL_WIDTH, check_min_width, prepare_crop_with};

/// A loaded model plus the steps around it: crop in, text out.
pub struct Recognizer<B> {
    backend: B,
    upscale: usize,
    min_width: usize,
}

impl<B: Backend> Recognizer<B> {
    /// Uses the 4x upscale of the measured Python path.
    pub fn new(backend: B) -> Self {
        Self::with_upscale(backend, DEFAULT_UPSCALE)
    }

    pub fn with_upscale(backend: B, upscale: usize) -> Self {
        Self {
            backend,
            upscale,
            min_width: MIN_MODEL_WIDTH,
        }
    }

    /// Pads crops to at least `min_width` columns instead of [`MIN_MODEL_WIDTH`] (issue #549, a
    /// measurement: the Python result the model was chosen on used 320).
    pub fn with_min_width(mut self, min_width: usize) -> Result<Self, Error> {
        check_min_width(min_width)?;
        self.min_width = min_width;
        Ok(self)
    }

    pub fn min_width(&self) -> usize {
        self.min_width
    }

    pub fn backend(&self) -> &B {
        &self.backend
    }

    pub fn characters(&self) -> &CharacterList {
        self.backend.characters()
    }

    pub fn allowed_set(&self, allowed: &str) -> Result<AllowedSet, Error> {
        AllowedSet::new(self.backend.characters(), allowed)
    }

    pub fn recognize(
        &mut self,
        crop: &GrayImage,
        allowed: Option<&AllowedSet>,
    ) -> Result<Decoded, Error> {
        let input = prepare_crop_with(crop, self.upscale, self.min_width)?;
        let probabilities = self.backend.run(&input)?;
        decode(&probabilities, self.backend.characters(), allowed)
    }
}
