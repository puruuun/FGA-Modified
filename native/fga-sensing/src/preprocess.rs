//! From a grayscale crop to the input tensor of the PaddleOCR recognition model.
//!
//! The PC check that chose the model (docs/qa/0025-stage-0-baseline.md, section 4.5) fed it the
//! crop upscaled 4x with OpenCV's `INTER_CUBIC`, then let RapidOCR resize it to height 48 with
//! OpenCV's default `INTER_LINEAR`. Both resamplers here copy OpenCV's own 8-bit code on
//! x86 (pixel-centre alignment, replicated borders, weights in 11-bit fixed point), including its
//! SIMD rounding steps, because the model reads small crops where one grey level can matter:
//! - cubic (A = -0.75): integer horizontal pass, float vertical pass rounded half to even. Checked
//!   against OpenCV 5.1 with IPP switched off: identical on all 370 samples. The Python check ran
//!   with IPP on, whose cubic resize differs from OpenCV's own code on 53 of the 10.6 million
//!   upscaled pixels, by one grey level each; that is the one place this does not match it.
//! - linear: OpenCV's (not bit-exact) `INTER_LINEAR` with its SIMD vertical pass, which drops low
//!   bits in three steps. Identical to OpenCV on all 370 samples. Like OpenCV it does not average
//!   the pixels it skips when shrinking.

use crate::Error;
use crate::image::GrayImage;

/// Height the model was trained on.
pub const MODEL_HEIGHT: usize = 48;
/// RapidOCR's `rec_img_shape` width: narrower crops are padded to it, never shrunk below. The
/// Python results the model was chosen on used it, so it stays the default here and of a backend
/// that does not plan its width; the tract backend's reader width is `READER_MIN_WIDTH` (256).
pub const MIN_MODEL_WIDTH: usize = 320;
/// The smallest minimum width a caller may choose: the model emits one output step per 8 input
/// columns (40 steps at 320, 4 at 32), so a narrower input has no step at all.
pub const SMALLEST_MIN_WIDTH: usize = 8;
/// The scale the measured Python path applied before recognition.
pub const DEFAULT_UPSCALE: usize = 4;

// Size limits. Matching runs on 720p frames, which are at most about 1680 px wide (21:9 at a
// height of 720), and a text line there is well under 100 px tall. A crop past these limits is
// not a text line, so it is refused before anything is allocated for it. Within them every size
// product in this module stays far below 2^31, so it cannot overflow even where usize is 32 bits
// (armeabi-v7a); the largest is the cubic pass's buffer, MAX_CROP_HEIGHT * MAX_UPSCALE *
// MAX_IMAGE_WIDTH = 2048 * 16384 = 2^25 values.

/// Widest crop the recogniser takes: a full 720p frame width with margin.
pub const MAX_CROP_WIDTH: usize = 2048;
/// Tallest crop the recogniser takes: more than twice the tallest text line in a 720p frame.
pub const MAX_CROP_HEIGHT: usize = 256;
/// Twice the measured 4x, to leave room for trying other scales.
pub const MAX_UPSCALE: usize = 8;
/// Widest image the resamplers take or produce: a maximal crop at the maximal upscale.
pub const MAX_IMAGE_WIDTH: usize = MAX_CROP_WIDTH * MAX_UPSCALE;
/// Tallest image the resamplers take or produce.
pub const MAX_IMAGE_HEIGHT: usize = MAX_CROP_HEIGHT * MAX_UPSCALE;
/// Widest model input. A line across a 1680 px frame at 20 px high (smaller than any game text
/// at 720p) needs 48 * 84 = 4032. The output then has 512 steps of 18710 classes, about 38 MB,
/// which is the most one crop should cost.
pub const MAX_MODEL_WIDTH: usize = 4096;

/// OpenCV's `INTER_RESIZE_COEF_BITS`.
const COEF_BITS: u32 = 11;
const COEF_SCALE: f32 = (1 << COEF_BITS) as f32;
const CUBIC_A: f32 = -0.75;

/// The model input for one crop: shape `[1, 3, MODEL_HEIGHT, width]`, channel-major, values in
/// [-1, 1] and zero in the padding.
#[derive(Debug, Clone, PartialEq)]
pub struct InputTensor {
    width: usize,
    data: Vec<f32>,
}

impl InputTensor {
    pub fn width(&self) -> usize {
        self.width
    }

    pub fn shape(&self) -> [usize; 4] {
        [1, 3, MODEL_HEIGHT, self.width]
    }

    pub fn data(&self) -> &[f32] {
        &self.data
    }
}

/// The full Python path: upscale by `upscale` (1 skips it), then [`to_input_tensor`].
pub fn prepare_crop(crop: &GrayImage, upscale: usize) -> Result<InputTensor, Error> {
    prepare_crop_with(crop, upscale, MIN_MODEL_WIDTH)
}

/// [`prepare_crop`] with a chosen minimum model width (see [`to_input_tensor_with`]).
pub fn prepare_crop_with(
    crop: &GrayImage,
    upscale: usize,
    min_width: usize,
) -> Result<InputTensor, Error> {
    check_min_width(min_width)?;
    check_limits(crop.width(), crop.height(), MAX_CROP_WIDTH, MAX_CROP_HEIGHT)?;
    if upscale == 0 || upscale > MAX_UPSCALE {
        return Err(Error::InvalidUpscale {
            factor: upscale,
            max: MAX_UPSCALE,
        });
    }
    if upscale == 1 {
        return to_input_tensor_with(crop, min_width);
    }
    // Within MAX_IMAGE_WIDTH x MAX_IMAGE_HEIGHT, given the two checks above.
    let (width, height) = (crop.width() * upscale, crop.height() * upscale);
    to_input_tensor_with(&resize_cubic(crop, width, height)?, min_width)
}

/// RapidOCR's `resize_norm_img` for a batch of one: scale to the model height keeping the aspect
/// ratio, map 0..255 to -1..1, and pad on the right to at least [`MIN_MODEL_WIDTH`].
pub fn to_input_tensor(image: &GrayImage) -> Result<InputTensor, Error> {
    to_input_tensor_with(image, MIN_MODEL_WIDTH)
}

/// [`to_input_tensor`] padding to at least `min_width` instead of [`MIN_MODEL_WIDTH`]. Resize,
/// normalisation and the padding value stay the same; only the number of padding columns changes.
pub fn to_input_tensor_with(image: &GrayImage, min_width: usize) -> Result<InputTensor, Error> {
    check_min_width(min_width)?;
    check_image(image)?;
    let ratio = image.width() as f64 / image.height() as f64;
    let max_ratio = (min_width as f64 / MODEL_HEIGHT as f64).max(ratio);
    // `as` saturates on overflow, and the result is checked against the limit right away. For
    // every allowed minimum, 48 * (min_width / 48) truncates back to min_width.
    let width = (MODEL_HEIGHT as f64 * max_ratio) as usize;
    if width > MAX_MODEL_WIDTH {
        return Err(Error::InputTooWide {
            width,
            max: MAX_MODEL_WIDTH,
        });
    }
    let wanted = (MODEL_HEIGHT as f64 * ratio).ceil() as usize;
    let resized_width = wanted.clamp(1, width.max(1));
    let resized = resize_linear(image, resized_width, MODEL_HEIGHT)?;

    // At most 3 * 48 * 4096 values; `channel * plane + y * width + x` stays below that.
    let plane = MODEL_HEIGHT * width;
    let mut data = vec![0.0f32; plane * 3];
    for (y, row) in resized.pixels().chunks(resized_width).enumerate() {
        for (x, &value) in row.iter().enumerate() {
            // Same float32 steps as numpy: divide by 255, subtract 0.5, divide by 0.5.
            let normalised = (value as f32 / 255.0 - 0.5) / 0.5;
            for channel in 0..3 {
                if let Some(cell) = data.get_mut(channel * plane + y * width + x) {
                    *cell = normalised;
                }
            }
        }
    }
    Ok(InputTensor { width, data })
}

/// OpenCV `INTER_CUBIC` for 8-bit images (see the module comment).
pub fn resize_cubic(image: &GrayImage, width: usize, height: usize) -> Result<GrayImage, Error> {
    check_resize(image, width, height)?;
    let columns = cubic_taps(image.width(), width);
    let rows = cubic_taps(image.height(), height);
    let horizontal = horizontal_pass(image, width, |y| {
        columns.iter().map(move |(start, weights)| {
            weights
                .iter()
                .zip(0..)
                .map(|(&w, k)| i32::from(image.clamped(start + k, y)) * w)
                .sum()
        })
    });

    let scale = 1.0f32 / (COEF_SCALE * COEF_SCALE);
    let mut pixels = Vec::with_capacity(width * height);
    for (start, weights) in &rows {
        let taps: Vec<(&[i32], f32)> = weights
            .iter()
            .zip(0..)
            .map(|(&w, k)| {
                (
                    row_of(&horizontal, width, image.height(), start + k),
                    w as f32 * scale,
                )
            })
            .collect();
        for x in 0..width {
            let value = |tap: usize| {
                taps.get(tap)
                    .map(|(row, w)| row.get(x).copied().unwrap_or_default() as f32 * w)
                    .unwrap_or_default()
            };
            // OpenCV's SSE order: the last tap first, each product added separately (no fused
            // multiply-add), so the float rounding matches.
            let mut sum = value(3);
            sum += value(2);
            sum += value(1);
            sum += value(0);
            pixels.push(sum.round_ties_even().clamp(0.0, 255.0) as u8);
        }
    }
    GrayImage::new(width, height, pixels)
}

/// OpenCV `INTER_LINEAR` for 8-bit images (see the module comment).
pub fn resize_linear(image: &GrayImage, width: usize, height: usize) -> Result<GrayImage, Error> {
    check_resize(image, width, height)?;
    let columns = linear_taps(image.width(), width, true);
    let rows = linear_taps(image.height(), height, false);
    let horizontal = horizontal_pass(image, width, |y| {
        columns.iter().map(move |&(start, w0, w1)| {
            i32::from(image.clamped(start, y)) * w0 + i32::from(image.clamped(start + 1, y)) * w1
        })
    });

    let mut pixels = Vec::with_capacity(width * height);
    for &(start, w0, w1) in &rows {
        let top = row_of(&horizontal, width, image.height(), start);
        let bottom = row_of(&horizontal, width, image.height(), start + 1);
        for x in 0..width {
            // OpenCV's SIMD pass: drop 4 bits, keep the high 16 of each product, round the last 2.
            let tap =
                |row: &[i32], w: i32| ((row.get(x).copied().unwrap_or_default() >> 4) * w) >> 16;
            let sum = tap(top, w0) + tap(bottom, w1);
            pixels.push(((sum + 2) >> 2).clamp(0, 255) as u8);
        }
    }
    GrayImage::new(width, height, pixels)
}

/// Runs `row` over every source row, as OpenCV does before its vertical pass.
fn horizontal_pass<'a, I>(image: &'a GrayImage, width: usize, row: impl Fn(isize) -> I) -> Vec<i32>
where
    I: Iterator<Item = i32> + 'a,
{
    let mut out = Vec::with_capacity(image.height() * width);
    for y in 0..image.height() as isize {
        out.extend(row(y));
    }
    out
}

/// One row of the horizontal pass, with the row index clamped (replicated border).
fn row_of(horizontal: &[i32], width: usize, height: usize, row: isize) -> &[i32] {
    let row = row.clamp(0, height.saturating_sub(1) as isize) as usize;
    horizontal
        .get(row * width..(row + 1) * width)
        .unwrap_or_default()
}

/// A resampler's source and target must both fit the image limits; see the comment on them.
fn check_resize(image: &GrayImage, width: usize, height: usize) -> Result<(), Error> {
    check_image(image)?;
    if width == 0 || height == 0 {
        return Err(Error::InvalidSize { width, height });
    }
    check_limits(width, height, MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT)
}

/// A minimum width the model can take: see [`SMALLEST_MIN_WIDTH`] and [`MAX_MODEL_WIDTH`].
pub fn check_min_width(min_width: usize) -> Result<(), Error> {
    if (SMALLEST_MIN_WIDTH..=MAX_MODEL_WIDTH).contains(&min_width) {
        Ok(())
    } else {
        Err(Error::InvalidMinWidth {
            width: min_width,
            min: SMALLEST_MIN_WIDTH,
            max: MAX_MODEL_WIDTH,
        })
    }
}

fn check_image(image: &GrayImage) -> Result<(), Error> {
    check_limits(
        image.width(),
        image.height(),
        MAX_IMAGE_WIDTH,
        MAX_IMAGE_HEIGHT,
    )
}

fn check_limits(
    width: usize,
    height: usize,
    max_width: usize,
    max_height: usize,
) -> Result<(), Error> {
    if width > max_width || height > max_height {
        return Err(Error::ImageTooLarge {
            width,
            height,
            max_width,
            max_height,
        });
    }
    Ok(())
}

/// OpenCV derives the step from the size ratio, not from a requested factor, and computes each
/// source position in f32.
fn source_position(source: usize, target: usize, index: usize) -> (isize, f32) {
    let scale = 1.0 / (target as f64 / source as f64);
    let position = ((index as f64 + 0.5) * scale - 0.5) as f32;
    let start = position.floor();
    (start as isize, position - start)
}

fn fixed(weight: f32) -> i32 {
    (weight * COEF_SCALE).round_ties_even() as i32
}

/// Per destination index: the first of four source taps and their integer weights.
fn cubic_taps(source: usize, target: usize) -> Vec<(isize, [i32; 4])> {
    (0..target)
        .map(|d| {
            let (start, fraction) = source_position(source, target, d);
            (start - 1, cubic_weights(fraction).map(fixed))
        })
        .collect()
}

/// OpenCV's `interpolateCubic`, in f32 like the original.
fn cubic_weights(x: f32) -> [f32; 4] {
    let a = CUBIC_A;
    let c0 = ((a * (x + 1.0) - 5.0 * a) * (x + 1.0) + 8.0 * a) * (x + 1.0) - 4.0 * a;
    let c1 = ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0;
    let c2 = ((a + 2.0) * (1.0 - x) - (a + 3.0)) * (1.0 - x) * (1.0 - x) + 1.0;
    [c0, c1, c2, 1.0 - c0 - c1 - c2]
}

/// Per destination index: the first source tap and the two integer weights. OpenCV pins
/// positions outside the source to the border pixel with full weight, but only horizontally;
/// vertically it clamps the row indices and keeps the weights.
fn linear_taps(source: usize, target: usize, pin_borders: bool) -> Vec<(isize, i32, i32)> {
    (0..target)
        .map(|d| {
            let (mut start, mut fraction) = source_position(source, target, d);
            if pin_borders && start < 0 {
                (start, fraction) = (0, 0.0);
            }
            if pin_borders && start >= source as isize - 1 {
                (start, fraction) = (source as isize - 1, 0.0);
            }
            (start, fixed(1.0 - fraction), fixed(fraction))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn image(width: usize, height: usize, f: impl Fn(usize, usize) -> u8) -> GrayImage {
        let pixels = (0..height)
            .flat_map(|y| (0..width).map(move |x| (x, y)))
            .map(|(x, y)| f(x, y))
            .collect();
        GrayImage::new(width, height, pixels).unwrap()
    }

    #[test]
    fn cubic_weights_sum_to_one_in_fixed_point() {
        for (_, weights) in cubic_taps(60, 240) {
            assert_eq!(weights.iter().sum::<i32>(), 1 << COEF_BITS);
        }
    }

    #[test]
    fn cubic_keeps_a_flat_image_flat() {
        let flat = image(7, 5, |_, _| 137);
        let up = resize_cubic(&flat, 28, 20).unwrap();
        assert_eq!((up.width(), up.height()), (28, 20));
        assert!(up.pixels().iter().all(|&p| p == 137));
    }

    #[test]
    fn cubic_saturates_overshoot_at_a_hard_edge() {
        let edge = image(8, 2, |x, _| if x < 4 { 0 } else { 255 });
        let up = resize_cubic(&edge, 32, 8).unwrap();
        let row = &up.pixels()[..32];
        // Cubic overshoots on both sides of the step; the clamp keeps it in 0..=255.
        assert_eq!(row[0], 0);
        assert_eq!(row[31], 255);
        assert!(row.windows(2).any(|w| w[0] != w[1]));
    }

    #[test]
    fn linear_reproduces_hand_computed_values() {
        // 5 -> 2 columns: positions 0.75 and 3.25, so weights 1/4 and 3/4 in each pair.
        let ramp = image(5, 1, |x, _| (x * 40) as u8);
        let small = resize_linear(&ramp, 2, 1).unwrap();
        assert_eq!(small.pixels(), &[30, 130]);
    }

    #[test]
    fn linear_replicates_borders_when_enlarging() {
        let pair = image(2, 1, |x, _| if x == 0 { 10 } else { 50 });
        let wide = resize_linear(&pair, 4, 1).unwrap();
        // Positions -0.25, 0.25, 0.75, 1.25: the outer two take the border pixel alone.
        assert_eq!(wide.pixels(), &[10, 20, 40, 50]);
    }

    #[test]
    fn rejects_zero_target_size() {
        let one = image(1, 1, |_, _| 0);
        assert!(resize_linear(&one, 0, 1).is_err());
        assert!(resize_cubic(&one, 1, 0).is_err());
        assert!(prepare_crop(&one, 0).is_err());
    }

    #[test]
    fn a_single_pixel_crop_still_works() {
        let one = image(1, 1, |_, _| 255);
        let tensor = prepare_crop(&one, DEFAULT_UPSCALE).unwrap();
        assert_eq!(tensor.width(), MIN_MODEL_WIDTH);
    }

    #[test]
    fn crops_just_inside_the_limits_work() {
        let largest = image(MAX_CROP_WIDTH, MAX_CROP_HEIGHT, |x, y| (x ^ y) as u8);
        assert_eq!(
            prepare_crop(&largest, DEFAULT_UPSCALE).unwrap().width(),
            384
        );
        // 2048 / 24 * 48 = 4096, the widest model input.
        let widest = image(MAX_CROP_WIDTH, 24, |x, _| x as u8);
        assert_eq!(
            prepare_crop(&widest, DEFAULT_UPSCALE).unwrap().width(),
            MAX_MODEL_WIDTH
        );
    }

    fn too_large(width: usize, height: usize) -> Error {
        Error::ImageTooLarge {
            width,
            height,
            max_width: MAX_CROP_WIDTH,
            max_height: MAX_CROP_HEIGHT,
        }
    }

    #[test]
    fn crops_past_the_limits_are_refused() {
        let wide = image(MAX_CROP_WIDTH + 1, 30, |_, _| 0);
        assert_eq!(
            prepare_crop(&wide, 4),
            Err(too_large(MAX_CROP_WIDTH + 1, 30))
        );
        let tall = image(30, MAX_CROP_HEIGHT + 1, |_, _| 0);
        assert_eq!(
            prepare_crop(&tall, 4),
            Err(too_large(30, MAX_CROP_HEIGHT + 1))
        );
    }

    #[test]
    fn extreme_crops_are_refused_before_any_resampling() {
        let line = image(1_000_000, 1, |_, _| 0);
        assert_eq!(prepare_crop(&line, 4), Err(too_large(1_000_000, 1)));
        assert!(matches!(
            to_input_tensor(&line),
            Err(Error::ImageTooLarge { .. })
        ));
        let column = image(1, 1_000_000, |_, _| 0);
        assert_eq!(prepare_crop(&column, 4), Err(too_large(1, 1_000_000)));
        assert!(matches!(
            resize_linear(&column, 1, 48),
            Err(Error::ImageTooLarge { .. })
        ));
    }

    #[test]
    fn a_crop_too_wide_for_its_height_is_refused() {
        // 2048 / 23 * 48 = 4274, past the widest model input.
        let thin = image(MAX_CROP_WIDTH, 23, |_, _| 0);
        assert_eq!(
            prepare_crop(&thin, 4),
            Err(Error::InputTooWide {
                width: 4274,
                max: MAX_MODEL_WIDTH
            })
        );
    }

    #[test]
    fn resize_targets_past_the_limits_are_refused() {
        let one = image(1, 1, |_, _| 0);
        assert!(matches!(
            resize_cubic(&one, MAX_IMAGE_WIDTH + 1, 1),
            Err(Error::ImageTooLarge { .. })
        ));
        assert!(matches!(
            resize_linear(&one, 1, usize::MAX),
            Err(Error::ImageTooLarge { .. })
        ));
    }

    #[test]
    fn upscale_factor_is_bounded() {
        let one = image(1, 1, |_, _| 0);
        assert!(prepare_crop(&one, MAX_UPSCALE).is_ok());
        assert_eq!(
            prepare_crop(&one, MAX_UPSCALE + 1),
            Err(Error::InvalidUpscale {
                factor: MAX_UPSCALE + 1,
                max: MAX_UPSCALE
            })
        );
    }

    #[test]
    fn short_crop_is_padded_to_the_minimum_width() {
        // 240x120 after upscaling: ratio 2, so 96 columns of picture and 224 of padding.
        let crop = image(60, 30, |_, _| 255);
        let tensor = prepare_crop(&crop, DEFAULT_UPSCALE).unwrap();
        assert_eq!(tensor.shape(), [1, 3, MODEL_HEIGHT, MIN_MODEL_WIDTH]);
        assert_eq!(tensor.data().len(), 3 * MODEL_HEIGHT * MIN_MODEL_WIDTH);
        for channel in 0..3 {
            for y in 0..MODEL_HEIGHT {
                let row = (channel * MODEL_HEIGHT + y) * MIN_MODEL_WIDTH;
                assert!(tensor.data()[row..row + 96].iter().all(|&v| v == 1.0));
                assert!(tensor.data()[row + 96..row + 320].iter().all(|&v| v == 0.0));
            }
        }
    }

    #[test]
    fn values_map_black_to_minus_one_and_white_to_one() {
        let crop = image(2, 1, |x, _| if x == 0 { 0 } else { 255 });
        let tensor = to_input_tensor(&GrayImage::new(2, 1, crop.pixels().to_vec()).unwrap());
        let tensor = tensor.unwrap();
        let data = tensor.data();
        assert!(data.iter().all(|v| (-1.0..=1.0).contains(v)));
        assert!(data.contains(&-1.0));
        assert!(data.contains(&1.0));
    }

    #[test]
    fn wide_crop_widens_the_tensor_instead_of_shrinking() {
        // Ratio 10 is past 320/48, so the width becomes 480 with no padding.
        let crop = image(100, 10, |_, _| 0);
        let tensor = to_input_tensor(&crop).unwrap();
        assert_eq!(tensor.width(), 480);
        assert!(tensor.data().iter().all(|&v| v == -1.0));
    }

    #[test]
    fn the_default_minimum_is_320() {
        for (w, h) in [(60, 30), (1, 1), (100, 10), (61, 30), (80, 12), (55, 30)] {
            let crop = image(w, h, |x, y| (x * 31 + y * 17) as u8);
            let default = prepare_crop(&crop, DEFAULT_UPSCALE).unwrap();
            assert_eq!(
                prepare_crop_with(&crop, DEFAULT_UPSCALE, MIN_MODEL_WIDTH).unwrap(),
                default
            );
            assert_eq!(
                to_input_tensor_with(&crop, MIN_MODEL_WIDTH).unwrap(),
                to_input_tensor(&crop).unwrap()
            );
        }
    }

    #[test]
    fn a_narrower_minimum_drops_only_padding() {
        let crop = image(60, 30, |x, y| (x * 7 + y * 13) as u8);
        let full = prepare_crop(&crop, DEFAULT_UPSCALE).unwrap();
        // 240x120 after upscaling: 96 columns of picture.
        for (min, width) in [
            (256, 256),
            (160, 160),
            (112, 112),
            (97, 97),
            (96, 96),
            (8, 96),
        ] {
            let narrow = prepare_crop_with(&crop, DEFAULT_UPSCALE, min).unwrap();
            assert_eq!(narrow.shape(), [1, 3, MODEL_HEIGHT, width], "min {min}");
            for row in 0..3 * MODEL_HEIGHT {
                let ours = &narrow.data()[row * width..(row + 1) * width];
                let wide = &full.data()[row * MIN_MODEL_WIDTH..(row + 1) * MIN_MODEL_WIDTH];
                assert_eq!(&ours[..96], &wide[..96], "min {min}, row {row}");
                assert!(ours[96..].iter().all(|&v| v == 0.0), "min {min}, row {row}");
            }
        }
    }

    #[test]
    fn a_wide_crop_ignores_a_narrower_minimum() {
        let crop = image(100, 10, |x, _| x as u8);
        let default = to_input_tensor(&crop).unwrap();
        assert_eq!(default.width(), 480);
        assert_eq!(to_input_tensor_with(&crop, 160).unwrap(), default);
    }

    #[test]
    fn the_minimum_width_is_bounded() {
        let crop = image(60, 30, |_, _| 0);
        let invalid = |width| Error::InvalidMinWidth {
            width,
            min: SMALLEST_MIN_WIDTH,
            max: MAX_MODEL_WIDTH,
        };
        for bad in [0, SMALLEST_MIN_WIDTH - 1, MAX_MODEL_WIDTH + 1, usize::MAX] {
            assert_eq!(prepare_crop_with(&crop, 4, bad), Err(invalid(bad)));
            assert_eq!(to_input_tensor_with(&crop, bad), Err(invalid(bad)));
            assert_eq!(check_min_width(bad), Err(invalid(bad)));
        }
        assert_eq!(
            prepare_crop_with(&crop, 4, SMALLEST_MIN_WIDTH)
                .unwrap()
                .width(),
            96
        );
        assert_eq!(
            prepare_crop_with(&crop, 4, MAX_MODEL_WIDTH)
                .unwrap()
                .width(),
            MAX_MODEL_WIDTH
        );
    }

    #[test]
    fn channels_are_identical() {
        let crop = image(30, 12, |x, y| (x * 7 + y * 13) as u8);
        let tensor = to_input_tensor(&crop).unwrap();
        let plane = MODEL_HEIGHT * tensor.width();
        let data = tensor.data();
        assert_eq!(&data[..plane], &data[plane..2 * plane]);
        assert_eq!(&data[..plane], &data[2 * plane..]);
    }
}
