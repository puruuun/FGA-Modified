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

#[test]
fn an_unlabelled_class_read_as_57_that_a_cut_reads_as_62_is_a_wrong_pick() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(57), None);
    assert_eq!(
        classify(&s, t, false, Some(62), None),
        Kinds {
            f62: 1,
            f16: 0,
            other: 0
        }
    );
}

#[test]
fn an_unlabelled_grand_read_as_12_that_a_cut_reads_as_16_is_a_wrong_pick() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(30), Some(12));
    assert_eq!(
        classify(&s, t, false, Some(30), Some(16)),
        Kinds {
            f62: 0,
            f16: 1,
            other: 0
        }
    );
}

#[test]
fn a_16_read_for_a_field_with_no_grand_score_is_a_wrong_pick() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(62), None);
    assert_eq!(classify(&s, t, true, Some(62), Some(16)).f16, 1);
    assert_eq!(classify(&s, t, false, Some(62), Some(16)).f16, 0);
}

#[test]
fn any_other_wrong_number_is_an_other() {
    let s = sample(Some(true), Some(true), None, None);
    let t = truth(&s, Some(62), Some(16));
    assert_eq!(
        classify(&s, t, false, Some(52), Some(1)),
        Kinds {
            f62: 0,
            f16: 0,
            other: 2
        }
    );
    // A true 62 and a true 16 are not wrong.
    assert_eq!(classify(&s, t, false, Some(62), Some(16)), Kinds::default());
    // Unknown is not a value.
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
fn every_wrong_field_is_in_exactly_one_kind() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(40), Some(5));
    for class in [None, Some(40), Some(62), Some(7)] {
        for grand in [None, Some(5), Some(16), Some(9)] {
            let k = classify(&s, t, false, class, grand);
            let wrong_fields = usize::from(class.is_some_and(|v| Some(v) != t.0))
                + usize::from(grand.is_some_and(|v| Some(v) != t.1));
            assert_eq!(k.f62 + k.f16 + k.other, wrong_fields, "{class:?} {grand:?}");
        }
    }
}
