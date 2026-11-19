//! What counts as a wrong value in the evaluation, in one place: the cut sweep of the evaluation
//! tool and its unit tests share it. Needs `crate::samples` at the root of the including crate.

#![allow(dead_code)]

use crate::samples::Sample;

/// What the class and Grand fields of a sample are supposed to read.
pub type Truth = (Option<u8>, Option<u8>);

/// What a read is judged against: the truth of both fields, and whether the field has no Grand
/// score at all (an ordinary `+NN`), in which case any Grand value is wrong.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Expect {
    pub truth: Truth,
    pub no_grand: bool,
}

/// What the unshifted read of a sample said, when there was one.
#[derive(Debug, Clone, Copy)]
pub struct BaseRead {
    pub class: Option<u8>,
    pub grand: Option<u8>,
    /// The read was an ordinary `+NN`, so the field has no Grand score.
    pub ordinary: bool,
}

/// What a read of `sample` is judged against. Without an unshifted read the sample's own labels
/// still decide.
pub fn expect(sample: &Sample, base: Option<BaseRead>) -> Expect {
    match base {
        Some(b) => Expect {
            truth: truth(sample, b.class, b.grand),
            no_grand: b.ordinary,
        },
        None => Expect {
            truth: truth(sample, None, None),
            no_grand: false,
        },
    }
}

/// The label where it gives the number, the special value (62, 16) where only the flag is true,
/// else what the reader itself returned on the unshifted crop (no wrong value was ever seen
/// there), else nothing is known. `py_text`, the Python reading, is deliberately not used.
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

/// The values of one read, split by what the automation does with them. It acts only on class 62
/// and a compound's Grand 16, so a wrong 62 (`f62`) or 16 (`f16`) is a wrong pick and any other
/// wrong number (`other`) is a miss. The three partition the wrong fields by the one definition of
/// "wrong" above, where a Grand value of any size on a field with no Grand score is wrong too.
///
/// A 62 or 16 that is not wrong is either `correct` (the truth is known and equal) or `no_truth`
/// (nothing is known about the field: no label, no flag, and the unshifted read was unknown), so
/// no such value is counted nowhere.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Kinds {
    pub f62: usize,
    pub f16: usize,
    pub other: usize,
    pub correct: usize,
    pub no_truth: usize,
}

impl Kinds {
    pub fn wrong(&self) -> usize {
        self.f62 + self.f16 + self.other
    }
}

/// `truth` must come from [`truth`] (or [`expect`]), which puts the label, the flag and the
/// unshifted-read fallback in one place; a hand-made truth would make the kinds disagree with the
/// rest of the evaluation.
pub fn classify(
    sample: &Sample,
    truth: Truth,
    no_grand: bool,
    class: Option<u8>,
    grand: Option<u8>,
) -> Kinds {
    let class_wrong = wrong_field(class, truth.0, sample.is62, 62);
    let grand_wrong = wrong_field(grand, truth.1, sample.is16, 16) || (grand.is_some() && no_grand);
    let f62 = class_wrong && class == Some(62);
    let f16 = grand_wrong && grand == Some(16);
    let class_pick = class == Some(62) && !class_wrong;
    let grand_pick = grand == Some(16) && !grand_wrong;
    Kinds {
        f62: usize::from(f62),
        f16: usize::from(f16),
        other: usize::from(class_wrong && !f62) + usize::from(grand_wrong && !f16),
        correct: usize::from(class_pick && truth.0 == Some(62))
            + usize::from(grand_pick && truth.1 == Some(16)),
        no_truth: usize::from(class_pick && truth.0.is_none())
            + usize::from(grand_pick && truth.1.is_none()),
    }
}

/// The counts of [`Kinds`] in the order of the columns of the sweep's TSV.
pub fn counts(kinds: &Kinds) -> [usize; 5] {
    [
        kinds.f62,
        kinds.f16,
        kinds.other,
        kinds.correct,
        kinds.no_truth,
    ]
}

/// Where the sweep's TSV keeps the direction and the first of the five count columns.
pub const DIRECTION_COLUMN: usize = 1;
pub const COUNT_COLUMN: usize = 8;

/// The five count columns of the sweep's TSV summed per direction, so the file can be checked
/// against the printed table.
pub fn sum_rows(tsv: &str) -> Result<std::collections::BTreeMap<String, [usize; 5]>, String> {
    let mut sums: std::collections::BTreeMap<String, [usize; 5]> = Default::default();
    for (n, line) in tsv.lines().enumerate().skip(1) {
        let fields: Vec<&str> = line.split('\t').collect();
        let direction = fields
            .get(DIRECTION_COLUMN)
            .ok_or_else(|| format!("line {}: no direction column", n + 1))?;
        let entry = sums.entry((*direction).to_owned()).or_default();
        for (i, slot) in entry.iter_mut().enumerate() {
            let text = fields
                .get(COUNT_COLUMN + i)
                .ok_or_else(|| format!("line {}: no count column {i}", n + 1))?;
            *slot += text
                .parse::<usize>()
                .map_err(|_| format!("line {}: {text:?} is not a count", n + 1))?;
        }
    }
    Ok(sums)
}
