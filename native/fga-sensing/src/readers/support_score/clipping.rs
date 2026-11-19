//! Whether the score field is cut off by the crop, decided from the pixels and not from the
//! recognised text: a cut "+62/+16" reads as a valid "+62/+1", and a cut "+62/+16" with its right
//! half missing is a valid ordinary score.
//!
//! The rules were chosen by the cut sweep of the evaluation tool (docs/agents/rust-native.md,
//! "Support-score reader"), which shifts real crops inside the 60x30 size and counts the wrong
//! values the reader then returns:
//! - the right end of the text is not at a fixed place (the last ink column of the labelled real
//!   reads spreads over 33 to 55), so a "must end here" rule does not exist;
//! - ink in the last four columns of the content is the narrowest right-end rule that leaves no
//!   wrong value at any shift to the right (three columns leave four);
//! - ink in the first column is the narrowest rule that leaves none at any shift to the left; the
//!   plus the text starts with is the leftmost glyph, so a cut there takes it first;
//! - a text that ends in a textureless band at the bottom was kept although the sweep found no
//!   wrong value without it (vertical shifts never gave one): it costs no labelled read, and it
//!   is the reason `0070` is rejected, which the left-edge rule only does by one pixel of margin.
//!
//! All of it assumes the 60x30 score region; the reader refuses a crop of another size.

use crate::image::GrayImage;

/// A pixel at or below this belongs to the dark outline the game draws around score digits.
const INK_DARK: u8 = 75;
/// A column holds ink when this many outline pixels lie in the text band, so one speckle is not.
const INK_PIXELS_PER_COLUMN: usize = 2;
/// The columns at the right end of the content that must hold no ink.
const RIGHT_COLUMNS: usize = 4;
/// The rows searched for ink, as fifteenths of the crop height. The band keeps the label printed
/// above the score out of the check.
const BAND_TOP_FIFTEENTHS: usize = 4;
const BAND_BOTTOM_FIFTEENTHS: usize = 14;
const BAND_DENOMINATOR: usize = 15;
/// Largest step between neighbouring pixels, and largest spread, of a row without texture.
const FLAT_STEP: u8 = 8;
const FLAT_RANGE: u8 = 32;
/// A flat band shorter than this is a smooth background, not the end of the content.
const MIN_FLAT_ROWS: usize = 3;
/// Rows above a flat band that are searched for outline pixels.
const INK_ROWS_ABOVE: usize = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ClipReport {
    /// Ink in the last columns of the content: the digits may continue past it.
    pub right: bool,
    /// Ink in the first column: the leading plus, and perhaps more, may be missing.
    pub left: bool,
    /// Text ends in a textureless band at the bottom of the crop: the lower part of every digit
    /// is missing.
    pub vertical: bool,
}

impl ClipReport {
    pub fn any(self) -> bool {
        self.right || self.left || self.vertical
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
        right: (content.saturating_sub(RIGHT_COLUMNS)..content).any(|x| column_has_ink(image, x)),
        left: column_has_ink(image, 0),
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

fn text_rows(image: &GrayImage) -> std::ops::Range<usize> {
    let top = image.height() * BAND_TOP_FIFTEENTHS / BAND_DENOMINATOR;
    let bottom = image.height() * BAND_BOTTOM_FIFTEENTHS / BAND_DENOMINATOR;
    top..bottom
}

fn column_has_ink(image: &GrayImage, x: usize) -> bool {
    text_rows(image)
        .filter(|&y| pixel(image, x, y) <= INK_DARK)
        .count()
        >= INK_PIXELS_PER_COLUMN
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

    fn black_strip(pixels: &mut [u8]) {
        for y in 0..H {
            for x in 55..W {
                put(pixels, x, y, 0);
            }
        }
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
    fn ink_in_the_middle_is_not_cut() {
        let mut pixels = panel();
        outline(&mut pixels, 30, 10..24);
        outline(&mut pixels, 8, 10..24);
        assert_eq!(inspect(&image(pixels)), ClipReport::default());
    }

    #[test]
    fn ink_in_the_last_columns_is_a_cut_on_the_right() {
        for x in W - 4..W {
            let mut pixels = panel();
            outline(&mut pixels, x, 10..24);
            assert!(inspect(&image(pixels)).right, "column {x}");
        }
        let mut pixels = panel();
        outline(&mut pixels, W - 5, 10..24);
        assert!(!inspect(&image(pixels)).right);
    }

    #[test]
    fn the_right_end_is_where_the_black_strip_starts() {
        let mut touching = panel();
        black_strip(&mut touching);
        outline(&mut touching, 52, 10..24);
        assert!(inspect(&image(touching)).right);

        let mut inside = panel();
        black_strip(&mut inside);
        outline(&mut inside, 50, 10..24);
        assert!(!inspect(&image(inside)).right);
    }

    #[test]
    fn ink_in_the_first_column_is_a_cut_on_the_left() {
        let mut pixels = panel();
        outline(&mut pixels, 0, 10..24);
        assert!(inspect(&image(pixels)).left);
        let mut pixels = panel();
        outline(&mut pixels, 1, 10..24);
        assert!(!inspect(&image(pixels)).left);
    }

    #[test]
    fn one_dark_pixel_is_a_speckle_not_ink() {
        let mut pixels = panel();
        put(&mut pixels, 0, 15, 40);
        put(&mut pixels, W - 1, 15, 40);
        assert_eq!(inspect(&image(pixels)), ClipReport::default());
    }

    #[test]
    fn the_label_above_the_score_does_not_count() {
        let mut pixels = panel();
        outline(&mut pixels, 0, 0..6);
        outline(&mut pixels, W - 1, 0..6);
        assert_eq!(inspect(&image(pixels)), ClipReport::default());
    }

    #[test]
    fn a_bright_frame_at_the_edge_is_not_ink() {
        let mut pixels = panel();
        for y in 0..H {
            put(&mut pixels, W - 1, y, 190);
            put(&mut pixels, W - 2, y, 190);
            put(&mut pixels, 0, y, 190);
        }
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
    fn a_dark_crop_is_all_ink_and_the_blank_rule_takes_it() {
        let dark = image(vec![58; W * H]);
        assert!(inspect(&dark).right && inspect(&dark).left);
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
