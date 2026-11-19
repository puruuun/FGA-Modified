use std::path::Path;

use super::*;
use crate::charset::CharacterList;
use crate::ctc::Probabilities;
use crate::preprocess::InputTensor;

/// Classes: 0 blank, 1 "+", 2 "/", 3..=12 "0".."9", 13 "A", 14 space.
const CHARACTERS: &str = "+\n/\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\nA";

fn class_of(c: char) -> usize {
    match c {
        '+' => 1,
        '/' => 2,
        '0'..='9' => 3 + (c as usize - '0' as usize),
        _ => 13,
    }
}

/// A backend that outputs what the test scripted: one entry per emitted character, each a list of
/// (character, probability) for that step; the rest of the probability is spread over the other
/// classes. Between two characters there is a blank step.
struct Scripted {
    characters: CharacterList,
    script: Vec<Vec<(char, f32)>>,
}

impl Scripted {
    fn new(script: Vec<Vec<(char, f32)>>) -> Self {
        Self {
            characters: CharacterList::from_metadata(CHARACTERS).unwrap(),
            script,
        }
    }

    /// Every character with the same probability, as the common case needs.
    fn text(text: &str, probability: f32) -> Self {
        Self::new(text.chars().map(|c| vec![(c, probability)]).collect())
    }
}

impl Backend for Scripted {
    fn load_file(_: &Path) -> Result<Self, Error> {
        Ok(Self::new(Vec::new()))
    }

    fn load_bytes(_: &[u8]) -> Result<Self, Error> {
        Ok(Self::new(Vec::new()))
    }

    fn run(&mut self, input: &InputTensor) -> Result<Probabilities, Error> {
        let steps = input.width() / 8;
        let classes = self.characters.class_count();
        let mut data = vec![0.0f32; steps * classes];
        for step in 0..steps {
            let row = &mut data[step * classes..(step + 1) * classes];
            let wanted = match (step % 2, self.script.get(step / 2)) {
                (0, Some(entry)) => entry.clone(),
                _ => vec![],
            };
            if wanted.is_empty() {
                row[0] = 1.0;
                continue;
            }
            let given: f32 = wanted.iter().map(|&(_, p)| p).sum();
            let rest = (1.0 - given) / (classes - wanted.len()) as f32;
            row.fill(rest);
            for (c, p) in wanted {
                row[class_of(c)] = p;
            }
        }
        Probabilities::new(steps, classes, data)
    }

    fn characters(&self) -> &CharacterList {
        &self.characters
    }
}

const W: usize = 60;
const H: usize = 30;

fn panel() -> Vec<u8> {
    (0..W * H)
        .map(|i| 100 + ((i % W) * 7 + (i / W) * 13) as u8 % 50)
        .collect()
}

fn crop(pixels: Vec<u8>) -> GrayImage {
    GrayImage::new(W, H, pixels).unwrap()
}

fn stroke(pixels: &mut [u8], x: usize, rows: std::ops::Range<usize>) {
    for y in rows {
        pixels[y * W + x - 1] = 40;
        pixels[y * W + x] = 200;
        pixels[y * W + x + 1] = 40;
    }
}

fn flat_bottom(pixels: &mut [u8]) {
    for y in 24..H {
        for x in 0..W {
            pixels[y * W + x] = 146;
        }
    }
}

fn config() -> ScoreConfig {
    ScoreConfig {
        format: Format::Strict,
        range_policy: RangePolicy::Independent,
        min_confidence: 0.9,
        min_char_confidence: 0.0,
        restrict_characters: false,
        check_clipping: true,
    }
}

fn observation(text: &str, confidence: f64) -> Observation {
    Observation {
        text: text.to_owned(),
        confidence,
        min_char_confidence: if text.is_empty() {
            None
        } else {
            Some(confidence as f32)
        },
        clip: ClipReport::default(),
        featureless: false,
    }
}

fn decided(text: &str, confidence: f64) -> SupportScore {
    decide(observation(text, confidence), &config())
}

fn values(score: &SupportScore) -> (Option<u8>, Option<u8>) {
    (score.class.value(), score.grand.value())
}

#[test]
fn a_clean_ordinary_score_is_read() {
    let score = decided("+62", 0.99);
    assert_eq!(values(&score), (Some(62), None));
    assert_eq!(score.display, Display::Ordinary);
    assert_eq!(score.grand, Score::Unknown(Reason::NotShown));
}

#[test]
fn a_clean_compound_score_is_read() {
    let score = decided("+62/+16", 0.99);
    assert_eq!(values(&score), (Some(62), Some(16)));
    assert_eq!(score.display, Display::Compound);
}

#[test]
fn blank_text_is_unknown_with_the_reason_blank() {
    let score = decided("", 0.0);
    assert_eq!(score.class, Score::Unknown(Reason::Blank));
    assert_eq!(score.grand, Score::Unknown(Reason::Blank));
    assert_eq!(score.display, Display::Unknown);
}

#[test]
fn a_crop_without_texture_is_blank_whatever_the_text() {
    let mut empty_panel = observation("+62/+16", 0.99);
    empty_panel.featureless = true;
    let score = decide(empty_panel, &config());
    assert_eq!(score.class, Score::Unknown(Reason::Blank));
    assert_eq!(score.grand, Score::Unknown(Reason::Blank));
}

#[test]
fn the_reader_calls_a_flat_crop_blank_even_when_the_model_reads_a_score() {
    let mut reader = reader(Scripted::text("+62/+16", 0.99), config());
    let score = reader.read(&crop(vec![58; W * H])).unwrap();
    assert_eq!(score.class, Score::Unknown(Reason::Blank));
}

#[test]
fn a_text_that_is_not_a_score_is_unknown_with_the_reason_format() {
    for text in ["+571+16", "62", "+62/16", "A", "+6/", "016"] {
        let score = decided(text, 0.99);
        assert_eq!(score.class, Score::Unknown(Reason::Format), "{text}");
        assert_eq!(score.grand, Score::Unknown(Reason::Format), "{text}");
        assert_eq!(score.display, Display::Unknown, "{text}");
    }
}

#[test]
fn a_laxer_format_is_a_setting() {
    let lax = ScoreConfig {
        format: Format::ClassPlusOptional,
        ..config()
    };
    assert_eq!(
        values(&decide(observation("62/+16", 0.99), &lax)),
        (Some(62), Some(16))
    );
    assert_eq!(
        values(&decide(observation("62/+16", 0.99), &config())),
        (None, None)
    );
}

#[test]
fn a_class_above_62_is_unknown_and_the_grand_score_stands_alone() {
    let score = decided("+63/+16", 0.99);
    assert_eq!(score.class, Score::Unknown(Reason::Range));
    assert_eq!(score.grand, Score::Value(16));
    assert_eq!(decided("+62", 0.99).class, Score::Value(62));
}

#[test]
fn a_grand_score_above_16_is_unknown_and_the_class_score_stands_alone() {
    let score = decided("+62/+17", 0.99);
    assert_eq!(score.class, Score::Value(62));
    assert_eq!(score.grand, Score::Unknown(Reason::Range));
    assert_eq!(decided("+62/+16", 0.99).grand, Score::Value(16));
}

#[test]
fn a_joint_range_policy_takes_both_fields() {
    let joint = ScoreConfig {
        range_policy: RangePolicy::Joint,
        ..config()
    };
    let score = decide(observation("+63/+16", 0.99), &joint);
    assert_eq!(score.class, Score::Unknown(Reason::Range));
    assert_eq!(score.grand, Score::Unknown(Reason::Range));
    let score = decide(observation("+62/+17", 0.99), &joint);
    assert_eq!(values(&score), (None, None));
    assert_eq!(
        values(&decide(observation("+62/+16", 0.99), &joint)),
        (Some(62), Some(16))
    );
}

#[test]
fn a_text_below_the_floor_is_unknown_and_one_at_the_floor_is_read() {
    let below = decided("+62/+16", 0.89);
    assert_eq!(below.class, Score::Unknown(Reason::LowConfidence));
    assert_eq!(below.grand, Score::Unknown(Reason::LowConfidence));
    assert_eq!(below.display, Display::Unknown);
    assert_eq!(values(&decided("+62/+16", 0.9)), (Some(62), Some(16)));
}

#[test]
fn one_weak_character_is_unknown_when_a_character_floor_is_set() {
    let strict = ScoreConfig {
        min_char_confidence: 0.5,
        ..config()
    };
    let mut weak = observation("+62/+16", 0.99);
    weak.min_char_confidence = Some(0.49);
    let score = decide(weak.clone(), &strict);
    assert_eq!(score.class, Score::Unknown(Reason::LowConfidence));
    // The same read passes when no character floor is set.
    assert_eq!(values(&decide(weak, &config())), (Some(62), Some(16)));
    let mut edge = observation("+62/+16", 0.99);
    edge.min_char_confidence = Some(0.5);
    assert_eq!(values(&decide(edge, &strict)), (Some(62), Some(16)));
}

#[test]
fn a_cut_at_the_right_takes_the_grand_score_of_a_compound() {
    let mut cut = observation("+62/+16", 0.99);
    cut.clip.right = true;
    let score = decide(cut, &config());
    assert_eq!(score.class, Score::Value(62));
    assert_eq!(score.grand, Score::Unknown(Reason::Clipped));
    assert_eq!(score.display, Display::Compound);
}

#[test]
fn a_cut_at_the_right_takes_the_class_score_of_an_ordinary_field() {
    let mut cut = observation("+62", 0.99);
    cut.clip.right = true;
    let score = decide(cut, &config());
    assert_eq!(score.class, Score::Unknown(Reason::Clipped));
    assert_eq!(score.display, Display::Ordinary);
}

#[test]
fn a_cut_at_the_bottom_takes_every_field() {
    let mut cut = observation("+62/+16", 0.99);
    cut.clip.vertical = true;
    let score = decide(cut, &config());
    assert_eq!(score.class, Score::Unknown(Reason::Clipped));
    assert_eq!(score.grand, Score::Unknown(Reason::Clipped));
    assert_eq!(score.display, Display::Unknown);
}

#[test]
fn clipping_keeps_the_reason_of_a_field_that_was_already_unknown() {
    let mut cut = observation("+63/+16", 0.99);
    cut.clip.vertical = true;
    let score = decide(cut, &config());
    assert_eq!(score.class, Score::Unknown(Reason::Range));
    assert_eq!(score.grand, Score::Unknown(Reason::Clipped));
}

#[test]
fn the_pixel_check_can_be_switched_off_for_the_evaluation() {
    let mut cut = observation("+62/+16", 0.99);
    cut.clip.vertical = true;
    let off = ScoreConfig {
        check_clipping: false,
        ..config()
    };
    assert_eq!(values(&decide(cut, &off)), (Some(62), Some(16)));
}

fn reader(script: Scripted, config: ScoreConfig) -> SupportScoreReader<Scripted> {
    SupportScoreReader::new(Recognizer::new(script), config).unwrap()
}

#[test]
fn the_reader_reads_through_any_backend() {
    let mut reader = reader(Scripted::text("+62/+16", 0.99), config());
    let score = reader.read(&crop(panel())).unwrap();
    assert_eq!(values(&score), (Some(62), Some(16)));
    assert_eq!(score.observation.text, "+62/+16");
    assert!((score.observation.confidence - 0.99).abs() < 1e-4);
    assert_eq!(score.observation.min_char_confidence, Some(0.99));
}

#[test]
fn the_reader_takes_the_cut_from_the_pixels_not_from_the_text() {
    let mut pixels = panel();
    stroke(&mut pixels, 30, 10..24);
    flat_bottom(&mut pixels);
    let mut reader = reader(Scripted::text("+62/+16", 0.99), config());
    let score = reader.read(&crop(pixels)).unwrap();
    assert_eq!(score.class, Score::Unknown(Reason::Clipped));
    assert_eq!(score.grand, Score::Unknown(Reason::Clipped));
}

#[test]
fn the_reader_sees_a_cut_at_the_right() {
    let mut pixels = panel();
    for x in W - 6..W {
        pixels[14 * W + x] = 40;
        pixels[15 * W + x] = 200;
        pixels[16 * W + x] = 40;
    }
    let mut reader = reader(Scripted::text("+62/+16", 0.99), config());
    let score = reader.read(&crop(pixels)).unwrap();
    assert_eq!(values(&score), (Some(62), None));
    assert_eq!(score.grand, Score::Unknown(Reason::Clipped));
}

#[test]
fn nothing_recognised_is_blank() {
    let mut reader = reader(Scripted::new(vec![]), config());
    let score = reader.read(&crop(panel())).unwrap();
    assert_eq!(score.class, Score::Unknown(Reason::Blank));
    assert_eq!(score.observation.min_char_confidence, None);
}

#[test]
fn the_character_restriction_turns_a_letter_into_a_weak_digit() {
    // The letter wins by far, but a digit has the second best probability: unrestricted the text
    // is the letter, restricted it is that digit with its low confidence.
    let script = || Scripted::new(vec![vec![('A', 0.7), ('1', 0.2)]]);
    let mut free = reader(script(), config());
    assert_eq!(free.observe(&crop(panel())).unwrap().text, "A");
    let restricted_config = ScoreConfig {
        restrict_characters: true,
        ..config()
    };
    let mut restricted = reader(script(), restricted_config);
    let seen = restricted.observe(&crop(panel())).unwrap();
    assert_eq!(seen.text, "1");
    assert!(seen.confidence < 0.3);
    // The floor then keeps that digit from being a class score, under any format.
    let lax = ScoreConfig {
        format: Format::PlusOptional,
        ..config()
    };
    assert_eq!(
        decide(seen, &lax).class,
        Score::Unknown(Reason::LowConfidence)
    );
}

#[test]
fn a_model_without_a_score_character_is_refused() {
    struct Letters(CharacterList);
    impl Backend for Letters {
        fn load_file(_: &Path) -> Result<Self, Error> {
            Ok(Self(CharacterList::from_metadata("A\nB").unwrap()))
        }
        fn load_bytes(_: &[u8]) -> Result<Self, Error> {
            Self::load_file(Path::new(""))
        }
        fn run(&mut self, _: &InputTensor) -> Result<Probabilities, Error> {
            Probabilities::new(1, 4, vec![1.0, 0.0, 0.0, 0.0])
        }
        fn characters(&self) -> &CharacterList {
            &self.0
        }
    }
    let letters = Letters::load_file(Path::new("")).unwrap();
    let restricted = ScoreConfig {
        restrict_characters: true,
        ..config()
    };
    assert!(matches!(
        SupportScoreReader::new(Recognizer::new(letters), restricted),
        Err(Error::UnknownCharacter(_))
    ));
}

#[test]
fn reasons_have_distinct_names() {
    let mut names: Vec<_> = Reason::ALL.iter().map(|r| r.name()).collect();
    names.sort_unstable();
    names.dedup();
    assert_eq!(names.len(), Reason::ALL.len());
}
