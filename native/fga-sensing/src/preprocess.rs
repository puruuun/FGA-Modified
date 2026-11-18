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
/// RapidOCR's `rec_img_shape` width: narrower crops are padded to it, never shrunk below.
pub const MIN_MODEL_WIDTH: usize = 320;
/// The scale the measured Python path applied before recognition.
pub const DEFAULT_UPSCALE: usize = 4;

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
    if upscale == 1 {
        return to_input_tensor(crop);
    }
    let width = crop.width().checked_mul(upscale);
    let height = crop.height().checked_mul(upscale);
    match (width, height) {
        (Some(width), Some(height)) if upscale > 0 => {
            to_input_tensor(&resize_cubic(crop, width, height)?)
        }
        _ => Err(Error::InvalidSize {
            width: crop.width(),
            height: crop.height(),
        }),
    }
}

/// RapidOCR's `resize_norm_img` for a batch of one: scale to the model height keeping the aspect
/// ratio, map 0..255 to -1..1, and pad on the right to at least [`MIN_MODEL_WIDTH`].
pub fn to_input_tensor(image: &GrayImage) -> Result<InputTensor, Error> {
    let ratio = image.width() as f64 / image.height() as f64;
    let max_ratio = (MIN_MODEL_WIDTH as f64 / MODEL_HEIGHT as f64).max(ratio);
    let width = (MODEL_HEIGHT as f64 * max_ratio) as usize;
    let wanted = (MODEL_HEIGHT as f64 * ratio).ceil() as usize;
    let resized_width = wanted.clamp(1, width.max(1));
    let resized = resize_linear(image, resized_width, MODEL_HEIGHT)?;

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
    check_size(width, height)?;
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
    check_size(width, height)?;
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

fn check_size(width: usize, height: usize) -> Result<(), Error> {
    if width == 0 || height == 0 || width.checked_mul(height).is_none() {
        return Err(Error::InvalidSize { width, height });
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
    fn channels_are_identical() {
        let crop = image(30, 12, |x, y| (x * 7 + y * 13) as u8);
        let tensor = to_input_tensor(&crop).unwrap();
        let plane = MODEL_HEIGHT * tensor.width();
        let data = tensor.data();
        assert_eq!(&data[..plane], &data[plane..2 * plane]);
        assert_eq!(&data[..plane], &data[2 * plane..]);
    }
}
