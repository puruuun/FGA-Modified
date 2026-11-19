//! Whether the score field is cut off by the crop, decided from the pixels and not from the
//! recognised text: a cut "+62/+16" still reads as a valid score.
//!
//! Which edges count was decided on the 370 labelled samples (docs/agents/rust-native.md,
//! "Support-score reader"): the left, top and bottom crop edges carry ink in most valid reads
//! (the leading plus arm, the label above, the digits' outline), so only two checks remain. The
//! right edge is the one the Kotlin reader checks too (`SupportScoreInk.clippedByEdgeOnly`).

use crate::image::GrayImage;

/// A pixel at or below this belongs to the dark outline the game draws around score digits.
const INK_DARK: u8 = 75;
/// A pixel at or above this is the white fill inside that outline.
const INK_BRIGHT: u8 = 170;
/// The columns next to the right end of the content that are checked.
const RIGHT_COLUMNS: usize = 2;
/// The rows searched at the right end, as fifteenths of the crop height. The band keeps the label
/// printed above the score out of the check.
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
    /// Fill and outline pixels meet at the right end of the content, so the last digits may
    /// continue past it.
    pub right: bool,
    /// Text ends in a textureless band at the bottom of the crop: the lower part of every digit
    /// is missing, so no digit of the field can be trusted.
    pub vertical: bool,
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
        right: ink_at_right_end(image, content),
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

/// A fill pixel in one of the last columns of the content with an outline pixel in the same
/// column within three rows of it: the stroke runs on past the edge. A frame or panel edge is a
/// plain mid-gray or bright column. A looser test (an outline pixel within two columns) also
/// flagged complete fields, among them two labelled +16 reads, so it is not used.
fn ink_at_right_end(image: &GrayImage, content: usize) -> bool {
    let rows = text_rows(image);
    (content.saturating_sub(RIGHT_COLUMNS)..content).any(|x| {
        rows.clone().any(|y| {
            pixel(image, x, y) >= INK_BRIGHT
                && (y.saturating_sub(3)..=y + 3)
                    .filter(|near| rows.contains(near))
                    .any(|near| pixel(image, x, near) <= INK_DARK)
        })
    })
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

    /// A digit-like stroke: bright fill with a dark outline either side, at column `x`.
    fn stroke(pixels: &mut [u8], x: usize, rows: std::ops::Range<usize>) {
        for y in rows {
            put(pixels, x - 1, y, 40);
            put(pixels, x, y, 200);
            put(pixels, x + 1, y, 40);
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
        stroke(&mut pixels, 30, 10..24);
        assert_eq!(inspect(&image(pixels)), ClipReport::default());
    }

    /// A horizontal stroke: outline, fill, outline in one column, over `columns`.
    fn bar(pixels: &mut [u8], columns: std::ops::Range<usize>, y: usize) {
        for x in columns {
            put(pixels, x, y - 1, 40);
            put(pixels, x, y, 200);
            put(pixels, x, y + 1, 40);
        }
    }

    #[test]
    fn a_stroke_at_the_last_column_is_cut_on_the_right() {
        let mut pixels = panel();
        bar(&mut pixels, W - 6..W, 15);
        assert!(inspect(&image(pixels)).right);
    }

    #[test]
    fn a_vertical_stroke_whose_outline_is_beside_it_is_not_a_right_cut() {
        let mut pixels = panel();
        stroke(&mut pixels, W - 3, 10..24);
        assert!(!inspect(&image(pixels)).right);
    }

    #[test]
    fn featureless_means_no_row_has_texture() {
        assert!(is_featureless(&image(vec![58; W * H])));
        assert!(is_featureless(&image(vec![0; W * H])));
        assert!(!is_featureless(&image(panel())));
        let mut ink = vec![58; W * H];
        stroke(&mut ink, 30, 10..24);
        assert!(!is_featureless(&image(ink)));
    }

    #[test]
    fn the_right_end_is_where_the_black_strip_starts() {
        let mut touching = panel();
        black_strip(&mut touching);
        bar(&mut touching, 49..55, 15);
        assert!(inspect(&image(touching)).right);

        let mut inside = panel();
        black_strip(&mut inside);
        bar(&mut inside, 35..45, 15);
        assert!(!inspect(&image(inside)).right);
    }

    #[test]
    fn a_bright_frame_at_the_edge_is_not_ink() {
        let mut pixels = panel();
        for y in 0..H {
            put(&mut pixels, W - 1, y, 190);
            put(&mut pixels, W - 2, y, 190);
        }
        assert_eq!(inspect(&image(pixels)), ClipReport::default());
    }

    #[test]
    fn the_label_above_the_score_does_not_count_at_the_right() {
        let mut pixels = panel();
        bar(&mut pixels, W - 6..W, 3);
        assert!(!inspect(&image(pixels)).right);
    }

    #[test]
    fn text_that_runs_into_a_flat_band_at_the_bottom_is_cut() {
        let mut pixels = panel();
        stroke(&mut pixels, 30, 10..24);
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
        stroke(&mut pixels, 30, 10..26);
        flat_bottom(&mut pixels, 28);
        assert!(!inspect(&image(pixels)).vertical);
    }

    #[test]
    fn a_blank_dark_crop_is_not_a_cut() {
        assert_eq!(inspect(&image(vec![58; W * H])), ClipReport::default());
    }
}
