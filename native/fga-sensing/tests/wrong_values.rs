//! The classification of wrong values that the cut sweep of the evaluation tool counts. Needs no
//! model: the inputs are built here.

#[path = "support/samples.rs"]
mod samples;
#[path = "support/wrong.rs"]
mod wrong;

use samples::Sample;
use wrong::{BaseRead, Kinds, classify, counts, expect, sum_rows, truth};

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
fn a_flag_that_says_not_16_makes_a_16_wrong_when_nothing_else_is_known() {
    let s = sample(None, Some(false), None, None);
    let t = truth(&s, None, None);
    assert_eq!(t, (None, None));
    assert_eq!(classify(&s, t, false, None, Some(16)), kinds(0, 1, 0));
    assert_eq!(classify(&s, t, false, None, Some(9)), Kinds::default());
}

#[test]
fn a_flag_that_says_no_with_a_known_truth_of_62_or_16_makes_the_read_wrong() {
    let s = sample(Some(false), Some(false), None, None);
    let t = truth(&s, Some(62), Some(16));
    assert_eq!(t, (Some(62), Some(16)));
    let k = classify(&s, t, false, Some(62), Some(16));
    assert_eq!(
        k,
        Kinds {
            f62: 1,
            f16: 1,
            ..Kinds::default()
        }
    );
}

#[test]
fn no_flag_with_a_known_truth_of_62_or_16_makes_the_read_correct() {
    let s = sample(None, None, None, None);
    let t = truth(&s, Some(62), Some(16));
    let k = classify(&s, t, false, Some(62), Some(16));
    assert_eq!(
        k,
        Kinds {
            correct: 2,
            ..Kinds::default()
        }
    );
}

#[test]
fn without_an_unshifted_read_the_labels_of_the_sample_still_decide() {
    let s = sample(Some(false), Some(true), Some(45), None);
    let e = expect(&s, None);
    assert_eq!(e.truth, (Some(45), Some(16)));
    assert!(!e.no_grand);
    let e = expect(
        &s,
        Some(BaseRead {
            class: Some(7),
            grand: None,
            ordinary: true,
        }),
    );
    assert_eq!(e.truth, (Some(45), Some(16)));
    assert!(e.no_grand);
}

#[test]
fn the_columns_of_the_rows_add_up_per_direction() {
    let tsv = "file\tdirection\tamount\ttext\tconfidence\tmin_char\tclass\tgrand\tf62\tf16\tother\tcorrect\tno_truth\n\
               a.png\tright\t3\t+62/+16\t0.99000\t0.9\t62\t16\t0\t0\t0\t2\t0\n\
               b.png\tright\t4\t+62/+1\t0.99000\t0.9\t62\t1\t0\t0\t1\t1\t0\n\
               c.png\tleft\t5\t+16\t0.99000\t0.9\t16\t\t1\t0\t0\t0\t2\n";
    let sums = sum_rows(tsv).unwrap();
    assert_eq!(sums["right"], [0, 0, 1, 3, 0]);
    assert_eq!(sums["left"], [1, 0, 0, 0, 2]);
    assert!(sum_rows("h\nx\tup\t1\n").is_err());
    assert_eq!(
        counts(&Kinds {
            f62: 1,
            f16: 2,
            other: 3,
            correct: 4,
            no_truth: 5
        }),
        [1, 2, 3, 4, 5]
    );
}

#[test]
fn every_field_is_in_at_most_one_kind_and_a_wrong_62_or_16_is_a_wrong_pick() {
    let truths = [None, Some(40), Some(62), Some(16)];
    let flags = [None, Some(false), Some(true)];
    let values = [None, Some(40), Some(62), Some(16), Some(9)];
    for no_grand in [false, true] {
        for flag in flags {
            for t in truths {
                for value in values {
                    let s = sample(flag, flag, None, None);
                    // Class only, then Grand only, so each field is looked at on its own.
                    let class = classify(&s, (t, None), no_grand, value, None);
                    let grand = classify(&s, (None, t), no_grand, None, value);
                    for (k, special, name) in [(class, 62, "class"), (grand, 16, "grand")] {
                        let n = counts(&k).iter().sum::<usize>();
                        let at = format!(
                            "{name} {value:?} truth {t:?} flag {flag:?} no_grand {no_grand}"
                        );
                        assert!(n <= 1, "{at}: {k:?}");
                        let wrong_field = k.wrong() == 1;
                        if wrong_field && value == Some(special) {
                            assert_eq!(k.f62 + k.f16, 1, "{at}: {k:?}");
                        }
                        if value == Some(special) && !wrong_field && t.is_some() {
                            assert_eq!(k.correct, 1, "{at}: {k:?}");
                        }
                    }
                }
            }
        }
    }
}
