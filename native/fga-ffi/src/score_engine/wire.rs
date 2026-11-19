//! The answer of `RustSensing.readScore` as one Java `int[]`, so the Kotlin side reads a score on
//! the hot path without a JSON parser and without a second call for the text.
//!
//! | Index | Meaning |
//! |---|---|
//! | 0 | [`MAGIC`] |
//! | 1 | [`STATUS_READ`] or [`STATUS_ERROR`] |
//! | 2 | class score 0..=62, or [`NO_VALUE`] |
//! | 3 | Grand score 0..=16, or [`NO_VALUE`] |
//! | 4 | display form ([`display_code`]) |
//! | 5 | class reason ([`reason_code`]), [`NO_REASON`] with a value |
//! | 6 | Grand reason, as index 5 |
//! | 7 | text confidence as `f32` bits, NaN when there is none |
//! | 8 | weakest character confidence as `f32` bits, NaN when there is none |
//! | 9 | microseconds inside Rust, saturated at `i32::MAX` |
//! | 10 | n, the number of code points that follow |
//! | 11.. | the recognised text, or the error message, as Unicode code points |
//!
//! A value and its reason travel separately, so an unknown field (`NO_VALUE` with a reason) can
//! never read as 0, and a value always comes with [`NO_REASON`]. The Kotlin parser
//! (`ScoreWire.kt`) refuses every other combination. The codes are part of the contract: the
//! tests on both sides pin the same numbers.

use fga_sensing::readers::support_score::{Display, Reason, Score, SupportScore};

pub const MAGIC: i32 = 0x4653_4331;
pub const HEADER_LEN: usize = 11;
pub const STATUS_READ: i32 = 0;
pub const STATUS_ERROR: i32 = 1;
pub const NO_VALUE: i32 = -1;
pub const NO_REASON: i32 = 0;
/// A score text holds at most `+NN/+MM`; a longer one is a misread whose tail the log does not
/// need, and the bound keeps the Java array small whatever the model emits.
pub const MAX_TEXT_CHARS: usize = 64;
/// Error messages name a path or a size; longer ones are cut.
pub const MAX_ERROR_CHARS: usize = 256;

pub fn reason_code(reason: Reason) -> i32 {
    match reason {
        Reason::Blank => 1,
        Reason::LowConfidence => 2,
        Reason::Format => 3,
        Reason::Range => 4,
        Reason::Clipped => 5,
        Reason::NotShown => 6,
        Reason::CropSize => 7,
    }
}

pub fn display_code(display: Display) -> i32 {
    match display {
        Display::Ordinary => 0,
        Display::Compound => 1,
        Display::Unknown => 2,
    }
}

fn field(score: Score) -> (i32, i32) {
    match score {
        Score::Value(value) => (i32::from(value), NO_REASON),
        Score::Unknown(reason) => (NO_VALUE, reason_code(reason)),
    }
}

fn float_bits(value: Option<f64>) -> i32 {
    let value = value
        .filter(|v| v.is_finite())
        .map_or(f32::NAN, |v| v as f32);
    // Reinterpreted, not converted: Kotlin rebuilds the float with Float.fromBits.
    value.to_bits() as i32
}

fn push_text(out: &mut Vec<i32>, text: &str, max: usize) {
    let chars: Vec<i32> = text
        .chars()
        .take(max)
        .map(|c| u32::from(c) as i32)
        .collect();
    // `max` is far below i32::MAX, so the length always fits.
    out.push(i32::try_from(chars.len()).unwrap_or(i32::MAX));
    out.extend(chars);
}

pub fn encode_score(score: &SupportScore, micros: u128) -> Vec<i32> {
    let (class, class_reason) = field(score.class);
    let (grand, grand_reason) = field(score.grand);
    let observation = &score.observation;
    let mut out = Vec::with_capacity(HEADER_LEN + MAX_TEXT_CHARS);
    out.extend([
        MAGIC,
        STATUS_READ,
        class,
        grand,
        display_code(score.display),
        class_reason,
        grand_reason,
        float_bits(Some(observation.confidence)),
        float_bits(observation.min_char_confidence.map(f64::from)),
        i32::try_from(micros).unwrap_or(i32::MAX),
    ]);
    push_text(&mut out, &observation.text, MAX_TEXT_CHARS);
    out
}

/// Both fields unknown for `reason` without a model run: a crop of the wrong size never reaches
/// the recogniser, as the reader's own size rule would have decided anyway.
pub fn encode_unknown(reason: Reason) -> Vec<i32> {
    let code = reason_code(reason);
    let mut out = vec![
        MAGIC,
        STATUS_READ,
        NO_VALUE,
        NO_VALUE,
        display_code(Display::Unknown),
        code,
        code,
        float_bits(None),
        float_bits(None),
        0,
    ];
    push_text(&mut out, "", MAX_TEXT_CHARS);
    out
}

pub fn encode_error(message: &str) -> Vec<i32> {
    let mut out = vec![
        MAGIC,
        STATUS_ERROR,
        NO_VALUE,
        NO_VALUE,
        display_code(Display::Unknown),
        NO_REASON,
        NO_REASON,
        float_bits(None),
        float_bits(None),
        0,
    ];
    push_text(&mut out, message, MAX_ERROR_CHARS);
    out
}

/// What a reply becomes when a Java exception was pending after it was built: an error that says
/// so, never the read itself, because the JNI calls that produced it may have failed half-way.
pub fn after_pending_exception(wire: &[i32]) -> Vec<i32> {
    encode_error(&format!(
        "a Java exception was pending and has been cleared (reply status {})",
        wire.get(1).copied().unwrap_or(NO_VALUE)
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use fga_sensing::readers::support_score::{ClipReport, Observation};

    fn score(class: Score, grand: Score, display: Display, text: &str) -> SupportScore {
        SupportScore {
            class,
            grand,
            display,
            observation: Observation {
                text: text.to_owned(),
                confidence: 0.75,
                min_char_confidence: Some(0.5),
                clip: ClipReport::default(),
                crop_size: (60, 30),
                featureless: false,
            },
        }
    }

    /// The same vector is decoded in app/src/test/.../ScoreWireTest.kt (`GOLDEN_COMPOUND`).
    #[test]
    fn golden_compound_reply() {
        let wire = encode_score(
            &score(
                Score::Value(62),
                Score::Value(16),
                Display::Compound,
                "+62/+16",
            ),
            1234,
        );
        assert_eq!(
            wire,
            vec![
                0x4653_4331,
                0,
                62,
                16,
                1,
                0,
                0,
                0x3F40_0000,
                0x3F00_0000,
                1234,
                7,
                43,
                54,
                50,
                47,
                43,
                49,
                54
            ]
        );
    }

    /// Also decoded in ScoreWireTest.kt (`GOLDEN_ORDINARY`).
    #[test]
    fn golden_ordinary_reply() {
        let wire = encode_score(
            &score(
                Score::Value(0),
                Score::Unknown(Reason::NotShown),
                Display::Ordinary,
                "+0",
            ),
            5,
        );
        assert_eq!(
            wire,
            vec![
                MAGIC,
                0,
                0,
                -1,
                0,
                0,
                6,
                0x3F40_0000,
                0x3F00_0000,
                5,
                2,
                43,
                48
            ]
        );
    }

    #[test]
    fn reason_codes_are_pinned_and_distinct() {
        let codes: Vec<i32> = Reason::ALL.iter().map(|r| reason_code(*r)).collect();
        assert_eq!(codes, vec![1, 2, 3, 4, 5, 6, 7]);
        assert_eq!(display_code(Display::Ordinary), 0);
        assert_eq!(display_code(Display::Compound), 1);
        assert_eq!(display_code(Display::Unknown), 2);
    }

    #[test]
    fn every_reason_travels_as_unknown_never_as_a_value() {
        for reason in Reason::ALL {
            let wire = encode_score(
                &score(
                    Score::Unknown(reason),
                    Score::Unknown(reason),
                    Display::Unknown,
                    "",
                ),
                0,
            );
            assert_eq!(wire[2], NO_VALUE, "{reason:?}");
            assert_eq!(wire[3], NO_VALUE, "{reason:?}");
            assert_eq!(wire[5], reason_code(reason), "{reason:?}");
            assert_eq!(wire[6], reason_code(reason), "{reason:?}");
            assert_ne!(wire[5], NO_REASON);
            assert_eq!(wire.len(), HEADER_LEN);
        }
    }

    #[test]
    fn a_zero_value_carries_no_reason() {
        let wire = encode_score(
            &score(Score::Value(0), Score::Value(0), Display::Compound, "+0/+0"),
            0,
        );
        assert_eq!(&wire[2..7], &[0, 0, 1, NO_REASON, NO_REASON]);
    }

    #[test]
    fn missing_or_non_finite_confidences_are_nan() {
        let mut s = score(
            Score::Unknown(Reason::Blank),
            Score::Unknown(Reason::Blank),
            Display::Unknown,
            "",
        );
        s.observation.confidence = f64::NAN;
        s.observation.min_char_confidence = None;
        let wire = encode_score(&s, 0);
        assert!(f32::from_bits(wire[7] as u32).is_nan());
        assert!(f32::from_bits(wire[8] as u32).is_nan());
    }

    #[test]
    fn text_is_code_points_and_bounded() {
        let wire = encode_score(
            &score(
                Score::Unknown(Reason::Format),
                Score::Unknown(Reason::Format),
                Display::Unknown,
                "屋+😀",
            ),
            0,
        );
        assert_eq!(&wire[10..], &[3, 0x5C4B, 43, 0x1F600]);
        let long = "9".repeat(200);
        let wire = encode_score(
            &score(
                Score::Unknown(Reason::Format),
                Score::Unknown(Reason::Format),
                Display::Unknown,
                &long,
            ),
            0,
        );
        assert_eq!(wire[10], MAX_TEXT_CHARS as i32);
        assert_eq!(wire.len(), HEADER_LEN + MAX_TEXT_CHARS);
    }

    #[test]
    fn micros_saturate() {
        let wire = encode_score(
            &score(Score::Value(1), Score::Value(1), Display::Compound, "+1/+1"),
            u128::MAX,
        );
        assert_eq!(wire[9], i32::MAX);
    }

    #[test]
    fn crop_size_reply() {
        let wire = encode_unknown(Reason::CropSize);
        assert_eq!(&wire[..7], &[MAGIC, STATUS_READ, -1, -1, 2, 7, 7]);
        assert_eq!(wire.len(), HEADER_LEN);
    }

    #[test]
    fn error_reply_holds_the_message() {
        let wire = encode_error("no");
        assert_eq!(&wire[..7], &[MAGIC, STATUS_ERROR, -1, -1, 2, 0, 0]);
        assert_eq!(&wire[10..], &[2, 110, 111]);
        let wire = encode_error(&"x".repeat(1000));
        assert_eq!(wire.len(), HEADER_LEN + MAX_ERROR_CHARS);
    }

    #[test]
    fn a_pending_exception_turns_a_read_into_an_error() {
        let read = encode_score(
            &score(
                Score::Value(62),
                Score::Value(16),
                Display::Compound,
                "+62/+16",
            ),
            1,
        );
        let wire = after_pending_exception(&read);
        assert_eq!(wire[1], STATUS_ERROR);
        assert_eq!(wire[2], NO_VALUE);
        let text: String = wire[11..]
            .iter()
            .map(|c| char::from_u32(*c as u32).unwrap())
            .collect();
        assert!(text.starts_with("a Java exception was pending"), "{text}");
    }
}
