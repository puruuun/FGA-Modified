//! Issue #555: the support-score reader of `fga-sensing` on a device, next to the Kotlin reader.
//! It reads through the recogniser that `ocrLoad` (or `ocrLoadTract`) already loaded, so it needs
//! no load call of its own, and `ocrUnload` serves it too. The reader is not connected to the app.

use std::time::Instant;

use fga_sensing::preprocess::{MAX_CROP_HEIGHT, MAX_CROP_WIDTH};
use fga_sensing::readers::support_score::{ScoreConfig, SupportScore, read_with};

use super::ocr::{NOT_LOADED, crop_from, json_string, recognizer};

/// `width` and `height` arrive as JNI ints and are bounded here, before the lock is taken.
pub fn support_score_read(pixels: Vec<u8>, width: i32, height: i32) -> Result<String, String> {
    let bounded = |value: i32, max: usize| usize::try_from(value).is_ok_and(|v| v <= max);
    if !bounded(width, MAX_CROP_WIDTH) || !bounded(height, MAX_CROP_HEIGHT) {
        return Err(format!(
            "invalid crop size {width}x{height}: at most {MAX_CROP_WIDTH}x{MAX_CROP_HEIGHT}"
        ));
    }
    let crop = crop_from(pixels, width, height)?;
    let mut slot = recognizer()?;
    let loaded = slot.as_mut().ok_or(NOT_LOADED)?;
    // The recogniser, the pixel checks and the rules, as the device case compares them with the
    // Kotlin reader; the JNI copies are not reader cost.
    let started = Instant::now();
    let score = read_with(loaded, &ScoreConfig::default(), &crop).map_err(|e| e.to_string())?;
    let micros = started.elapsed().as_micros();
    Ok(score_json(&score, micros))
}

fn number(value: Option<u8>) -> String {
    value.map_or("null".to_owned(), |v| v.to_string())
}

fn reason(value: Option<&str>) -> String {
    value.map_or("null".to_owned(), json_string)
}

pub(super) fn score_json(score: &SupportScore, micros: u128) -> String {
    let observation = &score.observation;
    // JSON has no NaN or infinity; the decoder never produces them, but the format must hold anyway.
    let confidence = if observation.confidence.is_finite() {
        observation.confidence.to_string()
    } else {
        "null".to_owned()
    };
    let weakest = observation
        .min_char_confidence
        .filter(|c| c.is_finite())
        .map_or("null".to_owned(), |c| c.to_string());
    format!(
        "{{\"class\":{},\"grand\":{},\"display\":{},\"text\":{},\"confidence\":{confidence},\
         \"minCharConfidence\":{weakest},\"classReason\":{},\"grandReason\":{},\
         \"clipRight\":{},\"clipVertical\":{},\"featureless\":{},\"micros\":{micros}}}",
        number(score.class.value()),
        number(score.grand.value()),
        json_string(score.display.name()),
        json_string(&observation.text),
        reason(score.class.reason().map(|r| r.name())),
        reason(score.grand.reason().map(|r| r.name())),
        observation.clip.right,
        observation.clip.vertical,
        observation.featureless,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::run_guarded;
    use fga_sensing::readers::support_score::{
        ClipReport, Display, Observation, Reason, Score, SupportScore,
    };
    use serde_json::Value;

    fn json(text: &str) -> Value {
        serde_json::from_str(text).unwrap()
    }

    fn score(class: Score, grand: Score, text: &str) -> SupportScore {
        SupportScore {
            class,
            grand,
            display: Display::Compound,
            observation: Observation {
                text: text.to_owned(),
                confidence: 0.99761,
                min_char_confidence: Some(0.9),
                clip: ClipReport::default(),
                featureless: false,
            },
        }
    }

    #[test]
    fn result_shape_with_values() {
        let value = json(&score_json(
            &score(Score::Value(62), Score::Value(16), "+62/+16"),
            27000,
        ));
        assert_eq!(value["class"], 62);
        assert_eq!(value["grand"], 16);
        assert_eq!(value["display"], "compound");
        assert_eq!(value["text"], "+62/+16");
        assert_eq!(value["confidence"], 0.99761);
        assert!(value["classReason"].is_null() && value["grandReason"].is_null());
        assert_eq!(value["micros"], 27000);
        assert_eq!(value.as_object().unwrap().len(), 12);
    }

    #[test]
    fn unknown_fields_are_null_with_a_reason() {
        let value = json(&score_json(
            &score(
                Score::Unknown(Reason::Clipped),
                Score::Unknown(Reason::LowConfidence),
                "a\"b",
            ),
            1,
        ));
        assert!(value["class"].is_null() && value["grand"].is_null());
        assert_eq!(value["classReason"], "clipped");
        assert_eq!(value["grandReason"], "low_confidence");
        assert_eq!(value["text"], "a\"b");
    }

    #[test]
    fn a_read_before_load_is_an_error() {
        let text = run_guarded(|| support_score_read(vec![0; 6], 3, 2));
        assert_eq!(text, format!("ERR: {NOT_LOADED}"));
    }

    #[test]
    fn the_size_is_bounded_before_the_lock() {
        for (w, h) in [(-1, 30), (60, -1), (2049, 30), (60, 257), (i32::MAX, 1)] {
            let text = run_guarded(|| support_score_read(vec![], w, h));
            assert!(
                text.starts_with("ERR: invalid crop size"),
                "{w}x{h}: {text}"
            );
        }
    }

    #[test]
    fn a_wrong_pixel_count_is_an_error() {
        let text = run_guarded(|| support_score_read(vec![0; 1799], 60, 30));
        assert_eq!(text, "ERR: invalid image: 60x30 with 1799 pixel values");
    }
}
