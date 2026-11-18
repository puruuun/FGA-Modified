use std::cmp::Ordering;

/// Checks every output against its expected value and returns the largest difference.
///
/// A broken SIMD kernel tends to produce NaN or infinity, and NaN slips through ordinary `>` and
/// `max` comparisons, so each element must be finite and provably within the tolerance.
pub fn within_tolerance(actual: &[f32], expected: &[f32], tolerance: f32) -> Result<f32, String> {
    if actual.len() != expected.len() {
        return Err(format!(
            "expected {} outputs, got {}",
            expected.len(),
            actual.len()
        ));
    }
    let mut worst = 0.0_f32;
    for (index, (a, e)) in actual.iter().zip(expected).enumerate() {
        if !a.is_finite() || !e.is_finite() {
            return Err(format!("output {index}: {a} against {e} is not finite"));
        }
        let difference = (a - e).abs();
        if !matches!(
            difference.partial_cmp(&tolerance),
            Some(Ordering::Less | Ordering::Equal)
        ) {
            return Err(format!(
                "output {index}: {a} differs from {e} by {difference}"
            ));
        }
        if difference > worst {
            worst = difference;
        }
    }
    Ok(worst)
}

#[cfg(test)]
mod tests {
    use super::within_tolerance;

    #[test]
    fn equal_values_pass() {
        assert_eq!(within_tolerance(&[1.0, -2.5], &[1.0, -2.5], 1e-4), Ok(0.0));
    }

    #[test]
    fn difference_above_tolerance_fails() {
        assert!(within_tolerance(&[1.0, 2.0], &[1.0, 2.1], 1e-4).is_err());
    }

    #[test]
    fn nan_in_actual_fails() {
        assert!(within_tolerance(&[1.0, f32::NAN], &[1.0, 2.0], 1e-4).is_err());
    }

    #[test]
    fn infinity_fails() {
        assert!(within_tolerance(&[f32::INFINITY], &[1.0], 1e-4).is_err());
        assert!(within_tolerance(&[1.0], &[f32::NEG_INFINITY], 1e-4).is_err());
    }

    #[test]
    fn nan_in_expected_fails() {
        assert!(within_tolerance(&[1.0, 2.0], &[1.0, f32::NAN], 1e-4).is_err());
    }

    #[test]
    fn same_infinity_on_both_sides_fails() {
        // inf - inf is NaN, which must not count as a zero difference.
        assert!(within_tolerance(&[1.0, f32::INFINITY], &[1.0, f32::INFINITY], 1e-4).is_err());
    }

    #[test]
    fn difference_inside_tolerance_passes_and_is_reported() {
        let worst = within_tolerance(&[1.0, 2.00005], &[1.0, 2.0], 1e-4);
        let Ok(worst) = worst else {
            panic!("expected a pass, got {worst:?}");
        };
        assert!((worst - 0.00005).abs() < 1e-6, "{worst}");
    }

    #[test]
    fn length_mismatch_fails() {
        assert!(within_tolerance(&[1.0], &[1.0, 2.0], 1e-4).is_err());
    }
}
