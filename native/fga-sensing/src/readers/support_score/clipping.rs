//! Whether the score field is cut off at the bottom of the crop, decided from the pixels and not
//! from the recognised text: a text that runs into a textureless band has lost the lower part of
//! every digit, whatever it reads as.
//!
//! There is no left or right rule. A cut can only shorten a number, so it cannot create the 62 or
//! the 16 the automation acts on, and on real screens `+62/+16` fills the crop edge to edge, so ink
//! at the left or right edge does not separate a whole field from a cut one (the owner's decision,
//! after the cut sweep of the evaluation tool showed what such rules cost in labelled reads). What a
//! cut at the sides does to other numbers is measured by that sweep and counts as misses.
//!
//! The 60x30 score region is assumed; the reader refuses a crop of another size.

use crate::image::GrayImage;

/// A pixel at or below this belongs to the dark outline the game draws around score digits.
const INK_DARK: u8 = 75;
/// Largest step between neighbouring pixels, and largest spread, of a row without texture.
const FLAT_STEP: u8 = 8;
const FLAT_RANGE: u8 = 32;
/// A flat band shorter than this is a smooth background, not the end of the content.
const MIN_FLAT_ROWS: usize = 3;
/// Rows above a flat band that are searched for outline pixels.
const INK_ROWS_ABOVE: usize = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ClipReport {
    /// Text ends in a textureless band at the bottom of the crop: the lower part of every digit
    /// is missing.
    pub vertical: bool,
}

impl ClipReport {
    pub fn any(self) -> bool {
        self.vertical
    }
}

/// No row has texture: an empty panel or a dark band, where the model may still read a digit out
/// of noise. An all-black crop counts, since there is nothing to read in it either.
pub fn is_featureless(image: &GrayImage) -> bool {
    match content_width(image) {
        None => true,
        Some(content) => (0..image.height()).all(|y| is_flat(row(image, y, content))),
    }
}

pub fn inspect(image: &GrayImage) -> ClipReport {
    let Some(content) = content_width(image) else {
        return ClipReport::default();
    };
    ClipReport {
        vertical: text_cut_by_flat_bottom(image, content),
    }
}

/// The black columns at the right are outside the game screen (the capture is narrower than the
/// crop), so the content ends at the last column that is not all zero. `None` for an all-black crop.
fn content_width(image: &GrayImage) -> Option<usize> {
    (0..image.width())
        .rev()
        .find(|&x| (0..image.height()).any(|y| pixel(image, x, y) > 0))
        .map(|x| x + 1)
}

fn pixel(image: &GrayImage, x: usize, y: usize) -> u8 {
    image
        .pixels()
        .get(y * image.width() + x)
        .copied()
        .unwrap_or_default()
}

fn row(image: &GrayImage, y: usize, content: usize) -> &[u8] {
    let start = y * image.width();
    image.pixels().get(start..start + content).unwrap_or(&[])
}

fn is_flat(line: &[u8]) -> bool {
    let (Some(&low), Some(&high)) = (line.iter().min(), line.iter().max()) else {
        return false;
    };
    high - low <= FLAT_RANGE
        && line
            .windows(2)
            .all(|pair| matches!(pair, [a, b] if a.abs_diff(*b) <= FLAT_STEP))
}

fn text_cut_by_flat_bottom(image: &GrayImage, content: usize) -> bool {
    let flat = (0..image.height())
        .rev()
        .take_while(|&y| is_flat(row(image, y, content)))
        .count();
    if flat < MIN_FLAT_ROWS || flat >= image.height() {
        return false;
    }
    let first_flat = image.height() - flat;
    (first_flat.saturating_sub(INK_ROWS_ABOVE)..first_flat)
        .any(|y| row(image, y, content).iter().any(|&p| p <= INK_DARK))
}

#[cfg(test)]
mod tests {
    use super::*;

    const W: usize = 60;
    const H: usize = 30;

    /// Background with texture everywhere, as the game panel has: a perfectly flat one would
    /// look like the cut-off band.
    fn panel() -> Vec<u8> {
        (0..W * H)
            .map(|i| 100 + ((i % W) * 7 + (i / W) * 13) as u8 % 50)
            .collect()
    }

    fn put(pixels: &mut [u8], x: usize, y: usize, value: u8) {
        pixels[y * W + x] = value;
    }

    fn image(pixels: Vec<u8>) -> GrayImage {
        GrayImage::new(W, H, pixels).unwrap()
    }

    fn flat_bottom(pixels: &mut [u8], from: usize) {
        for y in from..H {
            for x in 0..W {
                put(pixels, x, y, 146);
            }
        }
    }

    /// Outline pixels down one column, as the edge of a digit's stroke.
    fn outline(pixels: &mut [u8], x: usize, rows: std::ops::Range<usize>) {
        for y in rows {
            put(pixels, x, y, 40);
        }
    }

    #[test]
    fn a_plain_panel_is_not_cut() {
        assert_eq!(inspect(&image(panel())), ClipReport::default());
    }

    #[test]
    fn an_all_black_crop_is_not_cut() {
        assert_eq!(inspect(&image(vec![0; W * H])), ClipReport::default());
    }

    #[test]
    fn ink_at_the_sides_is_not_a_cut() {
        let mut pixels = panel();
        outline(&mut pixels, 0, 10..24);
        outline(&mut pixels, W - 1, 10..24);
        assert_eq!(inspect(&image(pixels)), ClipReport::default());
    }

    #[test]
    fn featureless_means_no_row_has_texture() {
        assert!(is_featureless(&image(vec![58; W * H])));
        assert!(is_featureless(&image(vec![0; W * H])));
        assert!(!is_featureless(&image(panel())));
        let mut ink = vec![58; W * H];
        outline(&mut ink, 30, 10..24);
        assert!(!is_featureless(&image(ink)));
    }

    #[test]
    fn text_that_runs_into_a_flat_band_at_the_bottom_is_cut() {
        let mut pixels = panel();
        outline(&mut pixels, 30, 10..24);
        flat_bottom(&mut pixels, 24);
        assert!(inspect(&image(pixels)).vertical);
    }

    #[test]
    fn a_flat_band_without_text_above_it_is_not_a_cut() {
        let mut pixels = panel();
        flat_bottom(&mut pixels, 24);
        assert!(!inspect(&image(pixels)).vertical);
    }

    #[test]
    fn a_short_flat_band_is_not_a_cut() {
        let mut pixels = panel();
        outline(&mut pixels, 30, 10..26);
        flat_bottom(&mut pixels, 28);
        assert!(!inspect(&image(pixels)).vertical);
    }

    #[test]
    fn a_dark_crop_is_not_a_cut_and_the_blank_rule_takes_it() {
        let dark = image(vec![58; W * H]);
        assert_eq!(inspect(&dark), ClipReport::default());
        assert!(is_featureless(&dark));
    }

    #[test]
    fn a_gentle_ramp_is_not_a_flat_row() {
        // Each step is small enough, but the spread over the row is not: only the range check
        // tells a ramp from a flat band.
        let ramp: Vec<u8> = (0..55).map(|x| (x * 4) as u8).collect();
        assert!(!is_flat(&ramp));
        assert!(is_flat(&[100; 55]));
        assert!(!is_flat(&[]));
    }
}
