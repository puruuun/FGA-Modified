//! The support-score reader of issue #555: from a 60x30 gray crop of the number field to the class
//! score (0..=62) and the Grand score (0..=16), or to "unknown" for whatever it is not sure of.
//!
//! The owner's bar is that it never returns a wrong value: a missed read costs a refresh, a false
//! accept picks the wrong servant. So every rule below only ever turns a value into "unknown".
//! The recogniser behind it is any [`Backend`]; nothing here knows the model.
//!
//! Reading is two steps. [`SupportScoreReader::observe`] runs the recogniser and looks at the
//! pixels; [`decide`] is a pure function of that [`Observation`] and a [`ScoreConfig`]. The split
//! lets the evaluation tool try other thresholds without running the model again.

mod clipping;
mod format;

use crate::Error;
use crate::backend::Backend;
use crate::ctc::AllowedSet;
use crate::image::GrayImage;
use crate::recognizer::Recognizer;

pub use clipping::{ClipReport, inspect as inspect_clipping, is_featureless};
pub use format::{Format, Parsed, parse};

pub const MAX_CLASS_SCORE: u8 = 62;
pub const MAX_GRAND_SCORE: u8 = 16;

/// The only characters a score field holds.
pub const SCORE_CHARACTERS: &str = "+/0123456789";

/// Why a field is unknown. Closed, so a caller can count the reasons.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Reason {
    /// The recogniser found no text, or the crop has no texture to hold any.
    Blank,
    /// The text, or one of its characters, is below the confidence floor.
    LowConfidence,
    /// The text is not `+NN` or `+NN/+MM`.
    Format,
    /// The number is outside 0..=62 (class) or 0..=16 (Grand).
    Range,
    /// The pixels say the field is cut by the crop.
    Clipped,
    /// The ordinary form `+NN` has no Grand score.
    NotShown,
    /// The crop is not the size the pixel rules were built for.
    CropSize,
}

impl Reason {
    pub const ALL: [Reason; 7] = [
        Reason::Blank,
        Reason::LowConfidence,
        Reason::Format,
        Reason::Range,
        Reason::Clipped,
        Reason::NotShown,
        Reason::CropSize,
    ];

    pub fn name(self) -> &'static str {
        match self {
            Reason::Blank => "blank",
            Reason::LowConfidence => "low_confidence",
            Reason::Format => "format",
            Reason::Range => "range",
            Reason::Clipped => "clipped",
            Reason::NotShown => "not_shown",
            Reason::CropSize => "crop_size",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Score {
    Value(u8),
    Unknown(Reason),
}

impl Score {
    pub fn value(self) -> Option<u8> {
        match self {
            Score::Value(value) => Some(value),
            Score::Unknown(_) => None,
        }
    }

    pub fn reason(self) -> Option<Reason> {
        match self {
            Score::Value(_) => None,
            Score::Unknown(reason) => Some(reason),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Display {
    /// `+NN`.
    Ordinary,
    /// `+NN/+MM`.
    Compound,
    Unknown,
}

impl Display {
    pub fn name(self) -> &'static str {
        match self {
            Display::Ordinary => "ordinary",
            Display::Compound => "compound",
            Display::Unknown => "unknown",
        }
    }
}

/// What an out-of-range number does to the other field of a compound score.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RangePolicy {
    /// Each field stands on its own, as in the Kotlin reader.
    Independent,
    /// An out-of-range number marks the whole text as misread, so the other field is unknown too.
    Joint,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ScoreConfig {
    pub format: Format,
    pub range_policy: RangePolicy,
    /// Minimum confidence of the whole text (the mean over its characters).
    pub min_confidence: f64,
    /// Minimum confidence of every single character. 0 accepts any weakest character, but a
    /// non-empty text without per-character confidences is refused whatever the floor.
    pub min_char_confidence: f32,
    /// Recognise with [`SCORE_CHARACTERS`] only.
    pub restrict_characters: bool,
    /// Switches the pixel check off. Only the evaluation tool does that, to show what it changes.
    pub check_clipping: bool,
    /// The crop size (width, height) the pixel rules are built for: the score region at 720p. A
    /// crop of another size is unknown, since the constants of those rules would not fit it.
    pub expected_size: (usize, usize),
    /// The rows the recogniser reads; the pixel rules still judge the whole crop.
    pub row_trim: RowTrim,
}

/// Rows removed from the top and the bottom of the crop before the recogniser reads it.
///
/// Measured on real KR and JP device crops and on shifted and rescaled copies of them: the label
/// rows at the top and the dark band under the row are what moves a read when the field is a
/// pixel or two off. With them removed the model reads the digits at width 320 on the worst of 21
/// shifted and rescaled copies in 388 of 391 JP crops and 221 of 221 KR crops (whole crop: 364 and 216).
/// The pixel rules ([`inspect_clipping`], [`is_featureless`], the size check) were fitted on the
/// whole crop and keep judging it: a flat band that lies only in the removed rows must still count
/// as a cut field.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RowTrim {
    pub top: usize,
    pub bottom: usize,
}

impl RowTrim {
    /// Reads the crop as it is.
    pub const NONE: RowTrim = RowTrim { top: 0, bottom: 0 };
    /// Rows 3..26 of the 60x30 crop (issue #639).
    pub const MEASURED: RowTrim = RowTrim { top: 3, bottom: 4 };

    /// The image the recogniser reads. Fails when no row would be left.
    pub fn apply(self, crop: &GrayImage) -> Result<GrayImage, Error> {
        let bottom = crop.height().saturating_sub(self.bottom);
        crop.rows(self.top, bottom)
    }
}

/// The score region of a support row at 720p.
pub const EXPECTED_CROP_SIZE: (usize, usize) = (60, 30);

/// A margin, not a measured boundary: 0.05 below the lowest confidence of a correct read on the
/// 370 labelled samples when the floors were set (0.91163, whole crop at model width 320). It separates
/// nothing there, because no wrong read of a valid text exists on those samples; the format and
/// the pixel rule do the real work.
pub const DEFAULT_MIN_CONFIDENCE: f64 = 0.86;

/// A character the model itself gives less than even odds is not a read. Not fitted: it costs no
/// labelled read on the 370 samples at model width 320.
pub const DEFAULT_MIN_CHAR_CONFIDENCE: f32 = 0.5;

impl Default for ScoreConfig {
    fn default() -> Self {
        Self {
            // The plus is the leftmost glyph, so a field cut at the left loses it first and is
            // then refused here; a tolerance for a missing class plus let `52/+16` and `2/+16`
            // through the synthetic cut sweep.
            format: Format::Strict,
            range_policy: RangePolicy::Joint,
            min_confidence: DEFAULT_MIN_CONFIDENCE,
            min_char_confidence: DEFAULT_MIN_CHAR_CONFIDENCE,
            restrict_characters: false,
            check_clipping: true,
            expected_size: EXPECTED_CROP_SIZE,
            row_trim: RowTrim::MEASURED,
        }
    }
}

/// What the recogniser and the pixels said about one crop, before any rule is applied.
#[derive(Debug, Clone, PartialEq)]
pub struct Observation {
    pub text: String,
    pub confidence: f64,
    /// The weakest character of the text; `None` for an empty text.
    pub min_char_confidence: Option<f32>,
    pub clip: ClipReport,
    /// Width and height of the crop that was read.
    pub crop_size: (usize, usize),
    /// No texture anywhere in the crop (see [`is_featureless`]): whatever the model read is noise.
    pub featureless: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SupportScore {
    pub class: Score,
    pub grand: Score,
    pub display: Display,
    pub observation: Observation,
}

pub struct SupportScoreReader<B> {
    recognizer: Recognizer<B>,
    config: ScoreConfig,
    allowed: Option<AllowedSet>,
}

impl<B: Backend> SupportScoreReader<B> {
    /// Fails when the model cannot produce one of [`SCORE_CHARACTERS`].
    pub fn new(recognizer: Recognizer<B>, config: ScoreConfig) -> Result<Self, Error> {
        let allowed = if config.restrict_characters {
            Some(recognizer.allowed_set(SCORE_CHARACTERS)?)
        } else {
            None
        };
        Ok(Self {
            recognizer,
            config,
            allowed,
        })
    }

    pub fn config(&self) -> &ScoreConfig {
        &self.config
    }

    pub fn recognizer(&self) -> &Recognizer<B> {
        &self.recognizer
    }

    pub fn observe(&mut self, crop: &GrayImage) -> Result<Observation, Error> {
        observe_with(
            &mut self.recognizer,
            self.allowed.as_ref(),
            &self.config,
            crop,
        )
    }

    pub fn read(&mut self, crop: &GrayImage) -> Result<SupportScore, Error> {
        let observation = self.observe(crop)?;
        Ok(decide(observation, &self.config))
    }
}

/// [`SupportScoreReader::observe`] on a recogniser somebody else holds: the device probe keeps
/// its loaded recogniser in a static, and the reader must not take it over.
///
/// A crop that is not `config.expected_size` is not read and not trimmed: [`decide`] refuses it
/// by size whatever the recogniser would say, and a trim fitted to the expected crop would cut
/// some other crop in the wrong place. The recogniser gets the rows of `config.row_trim`; the
/// pixel rules look at the whole crop.
pub fn observe_with<B: Backend>(
    recognizer: &mut Recognizer<B>,
    allowed: Option<&AllowedSet>,
    config: &ScoreConfig,
    crop: &GrayImage,
) -> Result<Observation, Error> {
    let crop_size = (crop.width(), crop.height());
    let (text, confidence, min_char_confidence) = if crop_size == config.expected_size {
        let seen = config.row_trim.apply(crop)?;
        let decoded = recognizer.recognize(&seen, allowed)?;
        let weakest = decoded.char_confidences.iter().copied().reduce(f32::min);
        (decoded.text, decoded.confidence, weakest)
    } else {
        (String::new(), 0.0, None)
    };
    Ok(Observation {
        text,
        confidence,
        min_char_confidence,
        clip: inspect_clipping(crop),
        crop_size,
        featureless: is_featureless(crop),
    })
}

pub fn read_with<B: Backend>(
    recognizer: &mut Recognizer<B>,
    config: &ScoreConfig,
    crop: &GrayImage,
) -> Result<SupportScore, Error> {
    let allowed = if config.restrict_characters {
        Some(recognizer.allowed_set(SCORE_CHARACTERS)?)
    } else {
        None
    };
    let observation = observe_with(recognizer, allowed.as_ref(), config, crop)?;
    Ok(decide(observation, config))
}

/// Applies the rules in a fixed order: size, blank, confidence, format, range, clipping.
pub fn decide(observation: Observation, config: &ScoreConfig) -> SupportScore {
    let unknown = |reason: Reason, observation: Observation| SupportScore {
        class: Score::Unknown(reason),
        grand: Score::Unknown(reason),
        display: Display::Unknown,
        observation,
    };
    if observation.crop_size != config.expected_size {
        return unknown(Reason::CropSize, observation);
    }
    if is_blank(&observation) {
        return unknown(Reason::Blank, observation);
    }
    if !is_confident(&observation, config) {
        return unknown(Reason::LowConfidence, observation);
    }
    let Some(parsed) = parse(&observation.text, config.format) else {
        return unknown(Reason::Format, observation);
    };
    let display = if parsed.grand.is_some() {
        Display::Compound
    } else {
        Display::Ordinary
    };
    let (class, grand) = apply_range(parsed, config.range_policy);
    // With nothing left to report, the display form is as unreliable as the numbers.
    let display = if class.value().is_none() && grand.value().is_none() {
        Display::Unknown
    } else {
        display
    };
    let mut score = SupportScore {
        class,
        grand,
        display,
        observation,
    };
    if config.check_clipping {
        apply_clipping(&mut score);
    }
    score
}

fn is_blank(observation: &Observation) -> bool {
    observation.text.is_empty() || observation.featureless
}

/// A text without per-character confidences cannot be checked against the floor, and the decoder
/// always gives them for a non-empty text, so their absence fails closed (a blank text never gets
/// here).
fn is_confident(observation: &Observation, config: &ScoreConfig) -> bool {
    observation.confidence >= config.min_confidence
        && observation
            .min_char_confidence
            .is_some_and(|weakest| weakest >= config.min_char_confidence)
}

fn apply_range(parsed: Parsed, policy: RangePolicy) -> (Score, Score) {
    let class_ok = parsed.class <= MAX_CLASS_SCORE;
    let grand_ok = parsed.grand.is_none_or(|grand| grand <= MAX_GRAND_SCORE);
    let joint_failure = policy == RangePolicy::Joint && !(class_ok && grand_ok);
    let class = if class_ok && !joint_failure {
        Score::Value(parsed.class)
    } else {
        Score::Unknown(Reason::Range)
    };
    let grand = match parsed.grand {
        None => Score::Unknown(Reason::NotShown),
        Some(grand) if grand_ok && !joint_failure => Score::Value(grand),
        Some(_) => Score::Unknown(Reason::Range),
    };
    (class, grand)
}

/// A text that runs into a flat band at the bottom has lost the lower part of every digit, so no
/// digit of it can be trusted: every field is unknown, and so is the display form, since a cut
/// field cannot know that it has no Grand score.
fn apply_clipping(score: &mut SupportScore) {
    if score.observation.clip.any() {
        score.class = Score::Unknown(Reason::Clipped);
        score.grand = Score::Unknown(Reason::Clipped);
        score.display = Display::Unknown;
    }
}

#[cfg(test)]
mod tests;
