//! The classification of wrong values that the cut sweep of the evaluation tool counts. Needs no
//! model: the inputs are built here.

#[path = "support/samples.rs"]
mod samples;
#[path = "support/wrong.rs"]
mod wrong;

use samples::Sample;
use wrong::{Kinds, classify, truth};

fn sample(is62: Option<bool>, is16: Option<bool>, cls: Option<u8>, grand: Option<u8>) -> Sample {
    Sample {
        file: "0000.png".to_owned(),
        case: String::new(),
        server: "Jp".to_owned(),
        kind: String::new(),
        is62,
        is16,
        cls,
        grand,
        kt_cls: None,
        kt_grand: None,
        known_miss: false,
        py_text: String::new(),
        py_conf: 0.0,
    }
}

fn kinds(f62: usize, f16: usize, other: usize) -> Kinds {
    Kinds {
        f62,
        f16,
        other,
        ..Kinds::default()
    }
}

#[test]
fn an_unlabelled_class_read_as_57_that_a_cut_reads_as_62_is_a_wrong_pick() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(57), None);
    assert_eq!(classify(&s, t, false, Some(62), None), kinds(1, 0, 0));
}

#[test]
fn an_unlabelled_grand_read_as_12_that_a_cut_reads_as_16_is_a_wrong_pick() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(30), Some(12));
    assert_eq!(classify(&s, t, false, Some(30), Some(16)), kinds(0, 1, 0));
}

#[test]
fn a_16_read_for_a_field_with_no_grand_score_is_a_wrong_pick() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(62), None);
    assert_eq!(classify(&s, t, true, Some(62), Some(16)).f16, 1);
    assert_eq!(classify(&s, t, false, Some(62), Some(16)).f16, 0);
}

#[test]
fn another_grand_value_for_a_field_with_no_grand_score_is_an_other() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(30), None);
    let k = classify(&s, t, true, Some(30), Some(9));
    assert_eq!((k.f62, k.f16, k.other), (0, 0, 1));
    // The same value on a field that has a Grand score of unknown truth is not wrong.
    assert_eq!(classify(&s, t, false, Some(30), Some(9)).wrong(), 0);
}

#[test]
fn any_other_wrong_number_is_an_other() {
    let s = sample(Some(true), Some(true), None, None);
    let t = truth(&s, Some(62), Some(16));
    assert_eq!(classify(&s, t, false, Some(52), Some(1)), kinds(0, 0, 2));
    let k = classify(&s, t, false, Some(62), Some(16));
    assert_eq!((k.wrong(), k.correct, k.no_truth), (0, 2, 0));
    assert_eq!(classify(&s, t, false, None, None), Kinds::default());
}

#[test]
fn a_label_still_decides_before_the_unshifted_read() {
    let s = sample(Some(false), Some(false), Some(45), Some(3));
    let t = truth(&s, Some(62), Some(16));
    assert_eq!(t, (Some(45), Some(3)));
    assert_eq!(classify(&s, t, false, Some(62), Some(16)).f62, 1);
    assert_eq!(classify(&s, t, false, Some(62), Some(16)).f16, 1);
}

#[test]
fn a_flag_that_says_not_62_makes_a_62_wrong_without_a_value_label() {
    let s = sample(Some(false), None, None, None);
    let t = truth(&s, None, None);
    assert_eq!(t, (None, None));
    assert_eq!(classify(&s, t, false, Some(62), None), kinds(1, 0, 0));
    // Another class value on such a row is not decided by the flag.
    assert_eq!(classify(&s, t, false, Some(30), None).wrong(), 0);
}

#[test]
fn a_field_wrong_by_no_grand_is_not_also_correct() {
    // A label that says 16 on a field the reader took for ordinary: the read cannot be both.
    let s = sample(None, Some(true), None, None);
    let t = truth(&s, Some(30), None);
    assert_eq!(t.1, Some(16));
    let k = classify(&s, t, true, Some(30), Some(16));
    assert_eq!((k.f16, k.correct, k.no_truth), (1, 0, 0));
    let k = classify(&s, t, false, Some(30), Some(16));
    assert_eq!((k.f16, k.correct, k.no_truth), (0, 1, 0));
}

#[test]
fn a_62_or_16_with_no_truth_is_counted_as_that_and_nothing_else() {
    let s = sample(None, None, None, None);
    let t = truth(&s, None, None);
    assert_eq!(t, (None, None));
    let k = classify(&s, t, false, Some(62), Some(16));
    assert_eq!(
        k,
        Kinds {
            no_truth: 2,
            ..Kinds::default()
        }
    );
    // Other numbers with no truth are not picks, so they are counted nowhere.
    assert_eq!(classify(&s, t, false, Some(30), Some(9)), Kinds::default());
}

#[test]
fn every_wrong_field_is_in_exactly_one_kind() {
    for no_grand in [false, true] {
        for flags in [(None, None), (Some(false), Some(false))] {
            let s = sample(flags.0, flags.1, None, None);
            let t = truth(&s, Some(40), Some(5));
            for class in [None, Some(40), Some(62), Some(7)] {
                for grand in [None, Some(5), Some(16), Some(9)] {
                    let k = classify(&s, t, no_grand, class, grand);
                    let class_wrong = class.is_some_and(|v| Some(v) != t.0);
                    let grand_wrong =
                        grand.is_some_and(|v| Some(v) != t.1) || (grand.is_some() && no_grand);
                    assert_eq!(
                        k.wrong(),
                        usize::from(class_wrong) + usize::from(grand_wrong),
                        "{class:?} {grand:?} no_grand {no_grand} flags {flags:?}"
                    );
                }
            }
        }
    }
}
