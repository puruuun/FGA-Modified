use crate::Error;
use crate::backend::Backend;
use crate::charset::CharacterList;
use crate::ctc::{AllowedSet, Decoded, decode};
use crate::image::GrayImage;
use crate::preprocess::{DEFAULT_UPSCALE, prepare_crop};

/// A loaded model plus the steps around it: crop in, text out.
pub struct Recognizer<B> {
    backend: B,
    upscale: usize,
}

impl<B: Backend> Recognizer<B> {
    /// Uses the 4x upscale of the measured Python path.
    pub fn new(backend: B) -> Self {
        Self::with_upscale(backend, DEFAULT_UPSCALE)
    }

    pub fn with_upscale(backend: B, upscale: usize) -> Self {
        Self { backend, upscale }
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
        let input = prepare_crop(crop, self.upscale)?;
        let probabilities = self.backend.run(&input)?;
        decode(&probabilities, self.backend.characters(), allowed)
    }
}
