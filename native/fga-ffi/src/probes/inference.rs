//! A tiny model built in code (2x2 convolution, then a dense layer) and run through tract's
//! optimised plan, so the SIMD kernels tract picks for the device are the ones exercised.

use tract_core::internal::*;
use tract_core::ops::cnn::{Conv, KernelFormat, PaddingSpec, PoolSpec};
use tract_core::ops::einsum::EinSum;
use tract_core::ops::nn::DataFormat;

const SIDE: usize = 4;
const CONV_OUT: usize = 2;
const CONV_SIDE: usize = SIDE - 1;
const DENSE_OUT: usize = 3;
const TOLERANCE: f32 = 1e-4;

fn input() -> Vec<f32> {
    (0..SIDE * SIDE).map(|i| i as f32 * 0.25 - 1.0).collect()
}

/// OIHW, one input channel.
fn conv_weights() -> Vec<f32> {
    vec![0.5, -1.0, 0.25, 2.0, -0.75, 0.125, 1.5, -0.5]
}

fn conv_bias() -> Vec<f32> {
    vec![0.1, -0.2]
}

/// [DENSE_OUT, CONV_OUT, CONV_SIDE, CONV_SIDE]
fn dense_weights() -> Vec<f32> {
    (0..DENSE_OUT * CONV_OUT * CONV_SIDE * CONV_SIDE)
        .map(|i| ((i * 7) % 11) as f32 * 0.1 - 0.5)
        .collect()
}

/// NaN for a missing index, so a layout mistake shows up as a mismatch instead of a panic.
fn at(values: &[f32], index: usize) -> f32 {
    values.get(index).copied().unwrap_or(f32::NAN)
}

/// The same arithmetic in plain loops, as the reference the tract output must match.
fn expected() -> Vec<f32> {
    let (x, w, b, d) = (input(), conv_weights(), conv_bias(), dense_weights());
    let mut conv = vec![0.0; CONV_OUT * CONV_SIDE * CONV_SIDE];
    for (o, bias) in b.iter().enumerate() {
        for row in 0..CONV_SIDE {
            for col in 0..CONV_SIDE {
                let mut sum = *bias;
                for kr in 0..2 {
                    for kc in 0..2 {
                        sum += at(&x, (row + kr) * SIDE + col + kc) * at(&w, o * 4 + kr * 2 + kc);
                    }
                }
                if let Some(cell) = conv.get_mut((o * CONV_SIDE + row) * CONV_SIDE + col) {
                    *cell = sum;
                }
            }
        }
    }
    d.chunks(conv.len())
        .map(|weights| weights.iter().zip(&conv).map(|(a, b)| a * b).sum())
        .collect()
}

fn run_model() -> TractResult<Vec<f32>> {
    let mut model = TypedModel::default();
    let source = model.add_source("input", f32::fact([1, 1, SIDE, SIDE]))?;
    let kernel = model.add_const(
        "conv.weights",
        Tensor::from_shape(&[CONV_OUT, 1, 2, 2], &conv_weights())?,
    )?;
    let bias = model.add_const("conv.bias", Tensor::from_shape(&[CONV_OUT], &conv_bias())?)?;
    let conv = model.wire_node(
        "conv",
        Conv {
            pool_spec: PoolSpec {
                data_format: DataFormat::NCHW,
                kernel_shape: tvec!(2, 2),
                padding: PaddingSpec::Valid,
                dilations: None,
                strides: None,
                input_channels: 1,
                output_channels: CONV_OUT,
            },
            kernel_fmt: KernelFormat::OIHW,
            group: 1,
            q_params: None,
        },
        &[source, kernel, bias],
    )?;
    let dense = model.add_const(
        "dense.weights",
        Tensor::from_shape(
            &[DENSE_OUT, CONV_OUT, CONV_SIDE, CONV_SIDE],
            &dense_weights(),
        )?,
    )?;
    let output = model.wire_node(
        "dense",
        EinSum::new("nchw,kchw->nk".parse()?, f32::datum_type()),
        &[conv[0], dense],
    )?;
    model.select_output_outlets(&output)?;

    let plan = model.into_optimized()?.into_runnable()?;
    let input = Tensor::from_shape(&[1, 1, SIDE, SIDE], &input())?;
    let result = plan.run(tvec!(input.into_tvalue()))?;
    let values = result
        .first()
        .ok_or_else(|| format_err!("model produced no output"))?
        .to_plain_array_view::<f32>()?
        .iter()
        .copied()
        .collect();
    Ok(values)
}

pub fn tiny_model() -> Result<String, String> {
    let actual = run_model().map_err(|e| format!("tract: {e:#}"))?;
    let expected = expected();
    if actual.len() != expected.len() {
        return Err(format!(
            "expected {} outputs, got {}",
            expected.len(),
            actual.len()
        ));
    }
    let worst = actual
        .iter()
        .zip(&expected)
        .map(|(a, e)| (a - e).abs())
        .fold(0.0_f32, f32::max);
    if worst > TOLERANCE {
        return Err(format!(
            "output {actual:?} differs from {expected:?} by {worst}"
        ));
    }
    Ok(format!(
        "conv+dense output {actual:?} within {TOLERANCE} (worst {worst:e})"
    ))
}
