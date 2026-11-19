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

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::*;
    use crate::ctc::Probabilities;
    use crate::preprocess::{InputTensor, MODEL_HEIGHT};

    /// Records the width of every tensor it is given, as a real backend would see it.
    struct WidthRecorder {
        characters: CharacterList,
        widths: Vec<usize>,
    }

    impl WidthRecorder {
        fn new() -> Self {
            Self {
                characters: CharacterList::from_metadata("0\n1\n").unwrap(),
                widths: Vec::new(),
            }
        }
    }

    impl Backend for WidthRecorder {
        fn load_file(_: &Path) -> Result<Self, Error> {
            Ok(Self::new())
        }

        fn load_bytes(_: &[u8]) -> Result<Self, Error> {
            Ok(Self::new())
        }

        fn run(&mut self, input: &InputTensor) -> Result<crate::ctc::Probabilities, Error> {
            assert_eq!(input.shape()[2], MODEL_HEIGHT);
            self.widths.push(input.width());
            let steps = input.width() / 8;
            let classes = self.characters.class_count();
            // Every step on the blank: decodes to an empty text.
            let mut data = vec![0.0f32; steps * classes];
            for step in 0..steps {
                data[step * classes] = 1.0;
            }
            Probabilities::new(steps, classes, data)
        }

        fn characters(&self) -> &CharacterList {
            &self.characters
        }
    }

    fn crop() -> GrayImage {
        GrayImage::new(60, 30, vec![200; 60 * 30]).unwrap()
    }

    #[test]
    fn the_backend_gets_the_default_width() {
        let mut recognizer = Recognizer::new(WidthRecorder::new());
        assert_eq!(recognizer.min_width(), MIN_MODEL_WIDTH);
        recognizer.recognize(&crop(), None).unwrap();
        assert_eq!(recognizer.backend().widths, vec![MIN_MODEL_WIDTH]);
    }

    #[test]
    fn the_backend_gets_the_chosen_width() {
        for (min, width) in [(160, 160), (96, 96), (8, 96)] {
            let mut recognizer = Recognizer::new(WidthRecorder::new())
                .with_min_width(min)
                .unwrap();
            assert_eq!(recognizer.min_width(), min);
            recognizer.recognize(&crop(), None).unwrap();
            assert_eq!(recognizer.backend().widths, vec![width], "min {min}");
        }
    }

    #[test]
    fn a_bad_width_is_refused() {
        assert!(matches!(
            Recognizer::new(WidthRecorder::new()).with_min_width(7),
            Err(Error::InvalidMinWidth { width: 7, .. })
        ));
    }
}
