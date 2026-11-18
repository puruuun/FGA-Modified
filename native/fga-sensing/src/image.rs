//! An 8-bit grayscale image, the form in which the app hands a crop to a reader.

use crate::Error;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GrayImage {
    width: usize,
    height: usize,
    pixels: Vec<u8>,
}

impl GrayImage {
    /// Row-major pixels, one byte each. Fails on a zero side or a pixel count that does not
    /// match, so every later step can rely on `width * height == pixels.len()`.
    pub fn new(width: usize, height: usize, pixels: Vec<u8>) -> Result<Self, Error> {
        let expected = width.checked_mul(height);
        if width == 0 || height == 0 || expected != Some(pixels.len()) {
            return Err(Error::InvalidImage {
                width,
                height,
                pixels: pixels.len(),
            });
        }
        Ok(Self {
            width,
            height,
            pixels,
        })
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    pub fn pixels(&self) -> &[u8] {
        &self.pixels
    }

    /// The pixel at (x, y) with coordinates clamped into the image, which is the border rule
    /// (replicate) that OpenCV's resize uses.
    pub(crate) fn clamped(&self, x: isize, y: isize) -> u8 {
        let x = clamp_index(x, self.width);
        let y = clamp_index(y, self.height);
        self.pixels
            .get(y * self.width + x)
            .copied()
            .unwrap_or_default()
    }
}

fn clamp_index(value: isize, len: usize) -> usize {
    if value <= 0 {
        0
    } else {
        (value as usize).min(len.saturating_sub(1))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_matching_pixel_count() {
        let image = GrayImage::new(3, 2, vec![0, 1, 2, 3, 4, 5]).unwrap();
        assert_eq!((image.width(), image.height()), (3, 2));
        assert_eq!(image.pixels(), &[0, 1, 2, 3, 4, 5]);
    }

    #[test]
    fn rejects_wrong_pixel_count() {
        assert_eq!(
            GrayImage::new(3, 2, vec![0; 5]),
            Err(Error::InvalidImage {
                width: 3,
                height: 2,
                pixels: 5
            })
        );
    }

    #[test]
    fn rejects_zero_sides() {
        assert!(GrayImage::new(0, 2, vec![]).is_err());
        assert!(GrayImage::new(2, 0, vec![]).is_err());
    }

    #[test]
    fn rejects_overflowing_size() {
        assert!(GrayImage::new(usize::MAX, 2, vec![0; 4]).is_err());
    }

    #[test]
    fn clamps_reads_to_the_border() {
        let image = GrayImage::new(2, 2, vec![10, 20, 30, 40]).unwrap();
        assert_eq!(image.clamped(-3, -1), 10);
        assert_eq!(image.clamped(5, 0), 20);
        assert_eq!(image.clamped(0, 9), 30);
        assert_eq!(image.clamped(1, 1), 40);
    }
}
