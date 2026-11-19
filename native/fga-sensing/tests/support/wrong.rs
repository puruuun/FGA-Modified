//! What counts as a wrong value in the evaluation, in one place: the cut sweep of the evaluation
//! tool and its unit tests share it. Needs `crate::samples` at the root of the including crate.

#![allow(dead_code)]

use crate::samples::Sample;

/// What the class and Grand fields of a sample are supposed to read.
pub type Truth = (Option<u8>, Option<u8>);

/// The label where it gives the number, the special value (62, 16) where only the flag is true,
/// else what the reader itself returned on the unshifted crop (no wrong value was ever seen
/// there), else nothing is known.
pub fn truth(sample: &Sample, base_class: Option<u8>, base_grand: Option<u8>) -> Truth {
    let class = sample
        .cls
        .or((sample.is62 == Some(true)).then_some(62))
        .or(base_class);
    let grand = sample
        .grand
        .or((sample.is16 == Some(true)).then_some(16))
        .or(base_grand);
    (class, grand)
}

/// A returned value is wrong when it differs from a known truth, or is the special value of a field
/// labelled as not having it.
pub fn wrong_field(value: Option<u8>, truth: Option<u8>, flag: Option<bool>, special: u8) -> bool {
    match value {
        None => false,
        Some(v) => truth.is_some_and(|t| t != v) || (flag == Some(false) && v == special),
    }
}

/// The wrong values of one read, split by what the automation does with them. It acts only on
/// class 62 and a compound's Grand 16, so a wrong 62 (`f62`) or 16 (`f16`) is a wrong pick and any
/// other wrong number (`other`) is a miss. The three partition the wrong values: each wrong field
/// is in exactly one, by the one definition of "wrong" above (`no_grand` adds a 16 read for a
/// field that has no Grand score at all).
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Kinds {
    pub f62: usize,
    pub f16: usize,
    pub other: usize,
}

pub fn classify(
    sample: &Sample,
    truth: Truth,
    no_grand: bool,
    class: Option<u8>,
    grand: Option<u8>,
) -> Kinds {
    let class_wrong = wrong_field(class, truth.0, sample.is62, 62);
    let grand_wrong =
        wrong_field(grand, truth.1, sample.is16, 16) || (grand == Some(16) && no_grand);
    let f62 = class_wrong && class == Some(62);
    let f16 = grand_wrong && grand == Some(16);
    Kinds {
        f62: usize::from(f62),
        f16: usize::from(f16),
        other: usize::from(class_wrong && !f62) + usize::from(grand_wrong && !f16),
    }
}
