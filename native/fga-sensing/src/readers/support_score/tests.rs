use std::path::Path;

use super::*;
use crate::charset::CharacterList;
use crate::ctc::Probabilities;
use crate::preprocess::InputTensor;

/// Classes: 0 blank, 1 "+", 2 "/", 3..=12 "0".."9", 13 "A", 14 "-", 15 space.
const CHARACTERS: &str = "+\n/\n0\n1\n2\n3\n4\n5\n6\n7\n8\n9\nA\n-";

fn class_of(c: char) -> usize {
    match c {
        '+' => 1,
        '/' => 2,
        '0'..='9' => 3 + (c as usize - '0' as usize),
        '-' => 14,
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
        expected_size: (W, H),
        row_trim: RowTrim::NONE,
        sign_correction: true,
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
        char_confidences: text.chars().map(|_| confidence as f32).collect(),
        clip: ClipReport::default(),
        crop_size: (W, H),
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
    assert_eq!(values(&decide(weak, &config())), (Some(62), Some(16)));
    let mut edge = observation("+62/+16", 0.99);
    edge.min_char_confidence = Some(0.5);
    assert_eq!(values(&decide(edge, &strict)), (Some(62), Some(16)));
}

#[test]
fn a_cut_takes_an_ordinary_field_and_its_display() {
    let mut cut = observation("+62", 0.99);
    cut.clip.vertical = true;
    let score = decide(cut, &config());
    assert_eq!(score.class, Score::Unknown(Reason::Clipped));
    // A cut field cannot know that it has no Grand score.
    assert_eq!(score.grand, Score::Unknown(Reason::Clipped));
    assert_eq!(score.display, Display::Unknown);
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
fn a_cut_takes_a_field_that_the_range_rule_had_left() {
    let mut cut = observation("+63/+16", 0.99);
    cut.clip.vertical = true;
    let score = decide(cut, &config());
    assert_eq!(score.class, Score::Unknown(Reason::Clipped));
    assert_eq!(score.grand, Score::Unknown(Reason::Clipped));
}

#[test]
fn nothing_left_after_the_range_rule_leaves_no_display_form() {
    let joint = ScoreConfig {
        range_policy: RangePolicy::Joint,
        ..config()
    };
    for text in ["+63/+16", "+62/+17", "+63"] {
        let score = decide(observation(text, 0.99), &joint);
        assert_eq!(values(&score), (None, None), "{text}");
        assert_eq!(score.display, Display::Unknown, "{text}");
    }
    assert_eq!(decided("+63/+16", 0.99).display, Display::Compound);
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

#[test]
fn a_crop_of_another_size_is_unknown_with_its_own_reason() {
    for size in [(W + 1, H), (W, H + 1), (W - 1, H), (120, 60)] {
        let mut other = observation("+62/+16", 0.99);
        other.crop_size = size;
        let score = decide(other, &config());
        assert_eq!(score.class, Score::Unknown(Reason::CropSize), "{size:?}");
        assert_eq!(score.grand, Score::Unknown(Reason::CropSize), "{size:?}");
        assert_eq!(score.display, Display::Unknown, "{size:?}");
    }
    let wide = ScoreConfig {
        expected_size: (120, 60),
        ..config()
    };
    let mut other = observation("+62/+16", 0.99);
    other.crop_size = (120, 60);
    assert_eq!(values(&decide(other, &wide)), (Some(62), Some(16)));
}

#[test]
fn the_defaults_are_pinned() {
    let default = ScoreConfig::default();
    assert_eq!(default.format, Format::Strict);
    assert_eq!(default.range_policy, RangePolicy::Joint);
    assert_eq!(default.min_confidence, 0.86);
    assert_eq!(default.min_char_confidence, 0.5);
    assert!(!default.restrict_characters);
    assert!(default.check_clipping);
    assert_eq!(default.expected_size, (60, 30));
    assert_eq!(default.row_trim, RowTrim { top: 3, bottom: 4 });
    assert!(default.sign_correction);
}

#[test]
fn row_trim_matches_the_shared_table() {
    let mut entries = Vec::new();
    for line in include_str!("row-trim.txt")
        .lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
    {
        let fields: Vec<&str> = line.split(' ').collect();
        assert_eq!(fields.len(), 2, "{line}");
        entries.push((fields[0].to_owned(), fields[1].parse::<usize>().unwrap()));
    }
    let ours = vec![
        ("crop_height".to_owned(), EXPECTED_CROP_SIZE.1),
        ("top".to_owned(), RowTrim::MEASURED.top),
        ("bottom".to_owned(), RowTrim::MEASURED.bottom),
    ];
    assert_eq!(entries, ours);
    assert_eq!(ScoreConfig::default().row_trim, RowTrim::MEASURED);
    assert_eq!(ScoreConfig::default().expected_size, EXPECTED_CROP_SIZE);
}

fn reader(script: Scripted, config: ScoreConfig) -> SupportScoreReader<Scripted> {
    SupportScoreReader::new(Recognizer::new(script).unwrap(), config).unwrap()
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

/// A crop with dark outline in `columns` over the text rows, on a textured panel.
fn crop_with_outline(columns: &[usize]) -> GrayImage {
    let mut pixels = panel();
    for &x in columns {
        for y in 10..24 {
            pixels[y * W + x] = 40;
        }
    }
    crop(pixels)
}

fn assert_cut_is_unknown(image: GrayImage, what: &str) {
    for text in ["+62/+16", "+62/+1", "+62", "+2/+16"] {
        let mut reader = reader(Scripted::text(text, 0.99), ScoreConfig::default());
        let score = reader.read(&image).unwrap();
        assert_eq!(
            score.class,
            Score::Unknown(Reason::Clipped),
            "{what}: {text}"
        );
        assert_eq!(
            score.grand,
            Score::Unknown(Reason::Clipped),
            "{what}: {text}"
        );
        assert_eq!(score.display, Display::Unknown, "{what}: {text}");
    }
}

#[test]
fn a_field_cut_at_the_bottom_is_unknown_whatever_the_text() {
    let mut pixels = panel();
    for y in 10..24 {
        pixels[y * W + 30] = 40;
    }
    flat_bottom(&mut pixels);
    assert_cut_is_unknown(crop(pixels), "text running into a flat band");
}

#[test]
fn ink_at_the_left_and_right_edges_is_not_a_cut() {
    // Real `+62/+16` fields fill the crop edge to edge.
    let mut reader = reader(Scripted::text("+62/+16", 0.99), ScoreConfig::default());
    let score = reader.read(&crop_with_outline(&[0, 30, W - 1])).unwrap();
    assert_eq!(values(&score), (Some(62), Some(16)));
    assert_eq!(score.display, Display::Compound);
}

#[test]
fn the_reader_checks_the_size_of_the_crop() {
    let mut reader = reader(Scripted::text("+62/+16", 0.99), ScoreConfig::default());
    let other = GrayImage::new(W + 10, H, vec![120; (W + 10) * H]).unwrap();
    let score = reader.read(&other).unwrap();
    assert_eq!(score.class, Score::Unknown(Reason::CropSize));
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
        SupportScoreReader::new(Recognizer::new(letters).unwrap(), restricted),
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

#[test]
fn the_weakest_character_is_the_minimum_of_the_text() {
    let script = || {
        Scripted::new(vec![
            vec![('+', 0.99)],
            vec![('6', 0.55)],
            vec![('2', 0.97)],
        ])
    };
    let mut reader = reader(script(), config());
    let seen = reader.observe(&crop(panel())).unwrap();
    assert_eq!(seen.text, "+62");
    let weakest = seen.min_char_confidence.unwrap();
    assert!((weakest - 0.55).abs() < 1e-6, "{weakest}");

    // Through `read_with`: the floor sits between the weakest character and the others, so
    // anything but the minimum would let the read through.
    let mut recognizer = Recognizer::new(script()).unwrap();
    let floor = |min_char_confidence| ScoreConfig {
        min_confidence: 0.5,
        min_char_confidence,
        ..config()
    };
    let low = read_with(&mut recognizer, &floor(0.6), &crop(panel())).unwrap();
    assert_eq!(low.class, Score::Unknown(Reason::LowConfidence));
    let ok = read_with(&mut recognizer, &floor(0.5), &crop(panel())).unwrap();
    assert_eq!(ok.class, Score::Value(62));
}

#[test]
fn a_text_without_character_confidences_is_not_confident() {
    let mut observed = observation("+62", 0.99);
    observed.min_char_confidence = None;
    for config in [config(), ScoreConfig::default()] {
        let score = decide(observed.clone(), &config);
        assert_eq!(score.class, Score::Unknown(Reason::LowConfidence));
        assert_eq!(score.display, Display::Unknown);
    }
}

/// Records every tensor it is given and reads nothing, so a test can see which rows of the crop
/// reached the model.
struct Recording {
    characters: CharacterList,
    seen: std::rc::Rc<std::cell::RefCell<Vec<Vec<f32>>>>,
}

impl Backend for Recording {
    fn load_file(_: &Path) -> Result<Self, Error> {
        Err(Error::Backend("not loadable".to_owned()))
    }

    fn load_bytes(_: &[u8]) -> Result<Self, Error> {
        Err(Error::Backend("not loadable".to_owned()))
    }

    fn run(&mut self, input: &InputTensor) -> Result<Probabilities, Error> {
        self.seen.borrow_mut().push(input.data().to_vec());
        let steps = input.width() / 8;
        let classes = self.characters.class_count();
        let mut data = vec![0.0f32; steps * classes];
        for step in 0..steps {
            data[step * classes] = 1.0;
        }
        Probabilities::new(steps, classes, data)
    }

    fn characters(&self) -> &CharacterList {
        &self.characters
    }
}

type Seen = std::rc::Rc<std::cell::RefCell<Vec<Vec<f32>>>>;

fn recording() -> (Recognizer<Recording>, Seen) {
    let seen = Seen::default();
    let backend = Recording {
        characters: CharacterList::from_metadata(CHARACTERS).unwrap(),
        seen: seen.clone(),
    };
    (Recognizer::new(backend).unwrap(), seen)
}

/// Rows differ from each other, so a model that is given other rows gets another tensor.
fn striped() -> GrayImage {
    crop((0..W * H).map(|i| (20 + (i / W) * 7) as u8).collect())
}

fn tensor_of(image: &GrayImage) -> Vec<f32> {
    let (mut recognizer, seen) = recording();
    recognizer.recognize(image, None).unwrap();
    let tensors = seen.borrow();
    tensors[0].clone()
}

#[test]
fn the_default_reader_gives_the_model_rows_3_to_26() {
    let image = striped();
    let (recognizer, seen) = recording();
    let mut reader = SupportScoreReader::new(recognizer, ScoreConfig::default()).unwrap();
    reader.read(&image).unwrap();
    let rows = image.rows(3, 26).unwrap();
    assert_eq!((rows.width(), rows.height()), (W, 23));
    assert_eq!(seen.borrow().len(), 1);
    assert_eq!(seen.borrow()[0], tensor_of(&rows));
    assert_ne!(seen.borrow()[0], tensor_of(&image));
}

#[test]
fn no_trim_gives_the_model_the_whole_crop() {
    let image = striped();
    let (recognizer, seen) = recording();
    let config = ScoreConfig {
        row_trim: RowTrim::NONE,
        ..ScoreConfig::default()
    };
    SupportScoreReader::new(recognizer, config)
        .unwrap()
        .read(&image)
        .unwrap();
    assert_eq!(seen.borrow()[0], tensor_of(&image));
}

#[test]
fn the_pixel_rules_still_judge_the_whole_crop() {
    // The flat band lies only in rows 26..30, which the model is not given.
    let mut pixels = panel();
    for y in 10..26 {
        pixels[y * W + 30] = 40;
    }
    for y in 26..H {
        for x in 0..W {
            pixels[y * W + x] = 146;
        }
    }
    let image = crop(pixels);
    assert!(!inspect_clipping(&image.rows(3, 26).unwrap()).vertical);
    assert_cut_is_unknown(image, "flat band only in the trimmed rows");
}

#[test]
fn a_crop_of_another_size_is_refused_before_any_trim_or_read() {
    let (recognizer, seen) = recording();
    let mut reader = SupportScoreReader::new(recognizer, ScoreConfig::default()).unwrap();
    for (w, h) in [(W + 10, H), (W, H + 1), (W, 5), (W, 7), (W, 4)] {
        let other = GrayImage::new(w, h, vec![120; w * h]).unwrap();
        let score = reader.read(&other).unwrap();
        assert_eq!(score.class, Score::Unknown(Reason::CropSize), "{w}x{h}");
        assert_eq!(score.observation.crop_size, (w, h));
    }
    assert!(seen.borrow().is_empty());
}

#[test]
fn a_trim_that_leaves_no_rows_is_an_error() {
    let all = RowTrim {
        top: 15,
        bottom: 15,
    };
    assert!(all.apply(&striped()).is_err());
    assert!(RowTrim { top: 31, bottom: 0 }.apply(&striped()).is_err());
    assert_eq!(RowTrim::NONE.apply(&striped()).unwrap(), striped());
}

#[test]
fn texture_only_in_the_trimmed_rows_is_not_blank_by_the_pixel_rule() {
    // Rows 3..26 are flat, so the model's image alone would be featureless; the crop is not.
    let mut pixels = vec![146u8; W * H];
    for (i, p) in panel().iter().enumerate() {
        if i / W < 3 || i / W >= 26 {
            pixels[i] = *p;
        }
    }
    let image = crop(pixels);
    assert!(is_featureless(&image.rows(3, 26).unwrap()));
    let mut reader = reader(Scripted::text("+62/+16", 0.99), ScoreConfig::default());
    let score = reader.read(&image).unwrap();
    assert!(!score.observation.featureless);
    assert_ne!(score.class, Score::Unknown(Reason::Blank));
}

#[test]
fn a_trim_larger_than_the_crop_is_an_error_not_a_panic() {
    let image = striped();
    for trim in [
        RowTrim {
            top: 0,
            bottom: H + 1,
        },
        RowTrim { top: 0, bottom: H },
        RowTrim {
            top: 20,
            bottom: 10,
        },
        RowTrim {
            top: 20,
            bottom: 11,
        },
        RowTrim { top: H, bottom: 0 },
    ] {
        assert!(trim.apply(&image).is_err(), "{trim:?}");
        let config = ScoreConfig {
            row_trim: trim,
            ..ScoreConfig::default()
        };
        let mut reader = reader(Scripted::text("+62/+16", 0.99), config);
        assert!(reader.read(&image).is_err(), "{trim:?}");
    }
    assert_eq!(
        RowTrim { top: 29, bottom: 0 }
            .apply(&image)
            .unwrap()
            .height(),
        1
    );
}

/// The tablet's crop `+62/-16` of QA 0025, section 4.19 (digits 0.99 and above, the sign 0.61 to
/// 0.81, the whole text 0.944 to 0.972), with every confidence settable.
fn read_as(text: &str, char_confidences: &[f32], confidence: f64) -> SupportScore {
    assert_eq!(text.chars().count(), char_confidences.len(), "{text}");
    decide(
        sign_observation(text, char_confidences, confidence),
        &ScoreConfig::default(),
    )
}

fn sign_observation(text: &str, char_confidences: &[f32], confidence: f64) -> Observation {
    Observation {
        text: text.to_owned(),
        confidence,
        min_char_confidence: char_confidences.iter().copied().reduce(f32::min),
        char_confidences: char_confidences.to_vec(),
        clip: ClipReport::default(),
        crop_size: (W, H),
        featureless: false,
    }
}

fn answer(score: &SupportScore) -> (Option<u8>, Option<u8>, Display, bool) {
    (
        score.class.value(),
        score.grand.value(),
        score.display,
        score.sign_corrected,
    )
}

fn refused(score: &SupportScore) -> bool {
    score.class.value().is_none()
        && score.grand.value().is_none()
        && score.display == Display::Unknown
}

const COMPOUND: [f32; 7] = [0.999, 0.999, 0.999, 0.999, 0.61, 0.999, 0.999];

#[test]
fn every_sign_lookalike_before_the_grand_score_is_read_as_a_plus() {
    for sign in SIGN_LOOKALIKES {
        for (class, text) in [(62, format!("+62/{sign}16")), (58, format!("+58/{sign}16"))] {
            assert_eq!(
                answer(&read_as(&text, &COMPOUND, 0.944)),
                (Some(class), Some(16), Display::Compound, true),
                "{text:?}"
            );
        }
    }
}

#[test]
fn every_sign_lookalike_before_the_class_score_is_read_as_a_plus() {
    let leading = [0.61, 0.999, 0.999, 0.999, 0.999, 0.999, 0.999];
    let both = [0.61, 0.999, 0.999, 0.999, 0.61, 0.999, 0.999];
    for sign in SIGN_LOOKALIKES {
        let text = format!("{sign}62/+16");
        assert_eq!(
            answer(&read_as(&text, &leading, 0.944)),
            (Some(62), Some(16), Display::Compound, true),
            "{text:?}"
        );
        let text = format!("{sign}62");
        assert_eq!(
            answer(&read_as(&text, &[0.61, 0.999, 0.999], 0.944)),
            (Some(62), None, Display::Ordinary, true),
            "{text:?}"
        );
        let text = format!("{sign}62/{sign}16");
        assert_eq!(
            answer(&read_as(&text, &both, 0.944)),
            (Some(62), Some(16), Display::Compound, true),
            "{text:?}"
        );
    }
}

#[test]
fn a_corrected_sign_below_the_character_floor_is_still_read() {
    let weak_sign = [0.999, 0.999, 0.999, 0.999, 0.3, 0.999, 0.999];
    assert_eq!(
        answer(&read_as("+62/-16", &weak_sign, 0.9)),
        (Some(62), Some(16), Display::Compound, true)
    );
}

#[test]
fn a_weak_digit_keeps_a_corrected_text_unknown() {
    for weak in [1, 2, 5, 6] {
        let mut confidences = COMPOUND;
        confidences[weak] = 0.49;
        let score = read_as("+62/-16", &confidences, 0.9);
        assert!(refused(&score), "digit {weak}: {score:?}");
        assert_eq!(score.class, Score::Unknown(Reason::LowConfidence));
        assert!(!score.sign_corrected);
    }
    let mut at_floor = COMPOUND;
    at_floor[1] = 0.5;
    assert_eq!(
        answer(&read_as("+62/-16", &at_floor, 0.9)),
        (Some(62), Some(16), Display::Compound, true)
    );
}

#[test]
fn a_weak_slash_or_kept_plus_keeps_a_corrected_text_unknown() {
    let mut weak_slash = COMPOUND;
    weak_slash[3] = 0.49;
    assert!(refused(&read_as("+62/-16", &weak_slash, 0.9)));
    let mut weak_plus = COMPOUND;
    weak_plus[0] = 0.49;
    assert!(refused(&read_as("+62/-16", &weak_plus, 0.9)));
}

#[test]
fn a_corrected_text_below_the_text_floor_is_unknown() {
    let score = read_as("+62/-16", &COMPOUND, 0.85999);
    assert!(refused(&score));
    assert_eq!(score.class, Score::Unknown(Reason::LowConfidence));
    assert_eq!(
        answer(&read_as("+62/-16", &COMPOUND, 0.86)),
        (Some(62), Some(16), Display::Compound, true)
    );
    assert!(refused(&read_as("+62/-16", &COMPOUND, f64::NAN)));
}

#[test]
fn a_missing_plus_stays_unknown() {
    let six = [0.999; 6];
    for text in ["62/+16", "+62/16", "-62/16", "62/-16"] {
        let score = read_as(text, &six, 0.99);
        assert!(refused(&score), "{text}: {score:?}");
        assert_eq!(score.class, Score::Unknown(Reason::Format), "{text}");
    }
    assert!(refused(&read_as("62", &[0.999; 2], 0.99)));
}

#[test]
fn a_slash_read_as_a_digit_stays_unknown() {
    for text in [
        "+621-16", "+627-16", "+621+16", "-621+16", "+62l-16", "-627-16",
    ] {
        let score = read_as(text, &COMPOUND, 0.99);
        assert!(refused(&score), "{text}: {score:?}");
        assert_eq!(score.class, Score::Unknown(Reason::Format), "{text}");
    }
}

#[test]
fn a_corrected_text_out_of_range_is_unknown() {
    for text in ["+63/-16", "+62/-17", "-63/+16", "-99", "-63"] {
        let confidences = vec![0.999; text.chars().count()];
        let score = read_as(text, &confidences, 0.99);
        assert!(refused(&score), "{text}: {score:?}");
        assert_eq!(score.class, Score::Unknown(Reason::Range), "{text}");
        assert!(!score.sign_corrected, "{text}");
    }
}

#[test]
fn a_corrected_grand_part_must_be_sixteen() {
    for text in ["+62/-6", "+62/-1", "+62/-0", "-62/+6", "-0/-0", "+58/-15"] {
        let confidences = vec![0.999; text.chars().count()];
        let score = read_as(text, &confidences, 0.99);
        assert!(refused(&score), "{text}: {score:?}");
        assert_eq!(score.class, Score::Unknown(Reason::Range), "{text}");
        assert_eq!(score.grand, Score::Unknown(Reason::Range), "{text}");
        assert!(!score.sign_corrected, "{text}");
    }
    let independent = ScoreConfig {
        range_policy: RangePolicy::Independent,
        ..ScoreConfig::default()
    };
    let score = decide(sign_observation("+62/-6", &[0.999; 6], 0.99), &independent);
    assert!(refused(&score), "{score:?}");
}

#[test]
fn the_strict_path_keeps_every_grand_score() {
    for (text, grand) in [("+62/+6", 6), ("+62/+1", 1), ("+62/+0", 0), ("+58/+15", 15)] {
        let confidences = vec![0.999; text.chars().count()];
        assert_eq!(
            answer(&read_as(text, &confidences, 0.99)),
            (
                text[1..3].parse().ok(),
                Some(grand),
                Display::Compound,
                false
            ),
            "{text}"
        );
    }
}

#[test]
fn a_cut_corrected_text_is_unknown() {
    let mut cut = sign_observation("+62/-16", &COMPOUND, 0.944);
    cut.clip.vertical = true;
    let score = decide(cut, &ScoreConfig::default());
    assert!(refused(&score));
    assert_eq!(score.class, Score::Unknown(Reason::Clipped));
    assert!(!score.sign_corrected);
    let mut blank = sign_observation("+62/-16", &COMPOUND, 0.944);
    blank.featureless = true;
    assert_eq!(
        decide(blank, &ScoreConfig::default()).class,
        Score::Unknown(Reason::Blank)
    );
}

#[test]
fn any_other_character_in_a_sign_position_stays_unknown() {
    for other in [
        't', '4', '1', '*', 'A', ' ', '.', '\u{FF0B}', '\u{2010}', '\u{2011}',
    ] {
        for text in [format!("+62/{other}16"), format!("{other}62/+16")] {
            let score = read_as(&text, &COMPOUND, 0.99);
            assert!(refused(&score), "{text:?}: {score:?}");
            assert!(!score.sign_corrected, "{text:?}");
        }
    }
}

#[test]
fn confidences_that_do_not_match_the_text_keep_it_unknown() {
    let mut short = sign_observation("+62/-16", &COMPOUND, 0.99);
    short.char_confidences.pop();
    assert!(refused(&decide(short, &ScoreConfig::default())));
}

#[test]
fn switched_off_the_sign_correction_reads_nothing() {
    let off = ScoreConfig {
        sign_correction: false,
        ..ScoreConfig::default()
    };
    let score = decide(sign_observation("+62/-16", &COMPOUND, 0.99), &off);
    assert!(refused(&score));
    assert_eq!(score.class, Score::Unknown(Reason::Format));
}

#[test]
fn strict_answers_are_unchanged_and_not_marked() {
    let off = ScoreConfig {
        sign_correction: false,
        ..ScoreConfig::default()
    };
    for (text, expected) in [
        ("+62/+16", (Some(62), Some(16), Display::Compound, false)),
        ("+58/+16", (Some(58), Some(16), Display::Compound, false)),
        ("+0", (Some(0), None, Display::Ordinary, false)),
        ("+62", (Some(62), None, Display::Ordinary, false)),
        ("+57/+0", (Some(57), Some(0), Display::Compound, false)),
    ] {
        let confidences = vec![0.999; text.chars().count()];
        assert_eq!(
            answer(&read_as(text, &confidences, 0.999)),
            expected,
            "{text}"
        );
        let observation = sign_observation(text, &confidences, 0.999);
        assert_eq!(answer(&decide(observation, &off)), expected, "{text}");
    }
}

#[test]
fn a_nan_in_another_character_keeps_a_corrected_text_unknown() {
    for at in [0, 1, 3, 6] {
        let mut confidences = COMPOUND;
        confidences[at] = f32::NAN;
        let score = read_as("+62/-16", &confidences, 0.95);
        assert!(refused(&score), "{at}: {score:?}");
        assert!(!score.sign_corrected);
    }
}

#[test]
fn a_weak_kept_plus_after_a_corrected_class_sign_is_unknown() {
    let confidences = [0.61, 0.999, 0.999, 0.999, 0.49, 0.999, 0.999];
    let score = read_as("-62/+16", &confidences, 0.9);
    assert!(refused(&score), "{score:?}");
    assert!(!score.sign_corrected);
}

#[test]
fn a_weak_real_plus_is_not_exempt() {
    let confidences = [0.999, 0.999, 0.999, 0.999, 0.3, 0.999, 0.999];
    let score = read_as("+62/+16", &confidences, 0.9);
    assert!(refused(&score), "{score:?}");
    assert_eq!(score.class, Score::Unknown(Reason::LowConfidence));
    assert!(!score.sign_corrected);
}

#[test]
fn the_reader_corrects_a_sign_from_the_recogniser() {
    let script = Scripted::new(vec![
        vec![('+', 0.999)],
        vec![('6', 0.999)],
        vec![('2', 0.999)],
        vec![('/', 0.99)],
        vec![('-', 0.61)],
        vec![('1', 0.999)],
        vec![('6', 0.999)],
    ]);
    let mut reader = reader(script, ScoreConfig::default());
    let score = reader.read(&crop(panel())).unwrap();
    assert_eq!(score.observation.text, "+62/-16");
    assert_eq!(score.observation.char_confidences.len(), 7);
    assert_eq!(
        answer(&score),
        (Some(62), Some(16), Display::Compound, true)
    );
}
