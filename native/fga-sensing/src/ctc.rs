//! Greedy CTC decoding of the recognition model's output, as RapidOCR's `CTCLabelDecode` does it.

use crate::Error;
use crate::charset::{BLANK, CharacterList};

/// Per-step class probabilities for one crop: `steps` rows of `classes` values. The PaddleOCR
/// export ends in a softmax, so each row already sums to one.
#[derive(Debug, Clone, PartialEq)]
pub struct Probabilities {
    steps: usize,
    classes: usize,
    data: Vec<f32>,
}

impl Probabilities {
    pub fn new(steps: usize, classes: usize, data: Vec<f32>) -> Result<Self, Error> {
        if classes == 0 || steps.checked_mul(classes) != Some(data.len()) {
            return Err(Error::InvalidOutput {
                steps,
                classes,
                values: data.len(),
            });
        }
        Ok(Self {
            steps,
            classes,
            data,
        })
    }

    pub fn steps(&self) -> usize {
        self.steps
    }

    pub fn classes(&self) -> usize {
        self.classes
    }

    fn rows(&self) -> impl Iterator<Item = &[f32]> {
        self.data.chunks_exact(self.classes)
    }
}

/// The classes allowed to win a step, besides the blank. A reader that only wants a score passes
/// `+/0123456789`, and the decoded text then cannot hold anything else.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AllowedSet {
    classes: Vec<usize>,
}

impl AllowedSet {
    /// Fails on a character the model cannot produce, so a typo cannot silently shrink the set.
    pub fn new(characters: &CharacterList, allowed: &str) -> Result<Self, Error> {
        let mut classes = Vec::new();
        for c in allowed.chars() {
            let found = characters.classes_of(c);
            if found.is_empty() {
                return Err(Error::UnknownCharacter(c));
            }
            classes.extend(found);
        }
        classes.sort_unstable();
        classes.dedup();
        Ok(Self { classes })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Decoded {
    /// Empty when every step was the blank; that is a normal result, not an error.
    pub text: String,
    /// RapidOCR's score: the mean of the kept steps' probabilities, each rounded to five
    /// decimals, the mean rounded again; 0 for an empty text.
    pub confidence: f64,
    /// The probability of the step each character came from, in text order.
    pub char_confidences: Vec<f32>,
}

/// Takes the best class per step, merges runs of the same class, and drops blanks. With an
/// allowed set only the blank and the allowed classes compete at each step.
pub fn decode(
    probabilities: &Probabilities,
    characters: &CharacterList,
    allowed: Option<&AllowedSet>,
) -> Result<Decoded, Error> {
    if probabilities.classes() != characters.class_count() {
        return Err(Error::ClassCountMismatch {
            model: probabilities.classes(),
            characters: characters.class_count(),
        });
    }
    let mut text = String::new();
    let mut char_confidences = Vec::new();
    let mut previous = None;
    for row in probabilities.rows() {
        let (class, probability) = match allowed {
            Some(set) => best_of(
                row,
                std::iter::once(BLANK).chain(set.classes.iter().copied()),
            ),
            None => best_of(row, 0..row.len()),
        };
        if previous != Some(class) && class != BLANK {
            text.push_str(characters.symbol(class).unwrap_or_default());
            char_confidences.push(probability);
        }
        previous = Some(class);
    }
    let confidence = if char_confidences.is_empty() {
        0.0
    } else {
        let rounded: f64 = char_confidences.iter().map(|&p| round5(f64::from(p))).sum();
        round5(rounded / char_confidences.len() as f64)
    };
    Ok(Decoded {
        text,
        confidence,
        char_confidences,
    })
}

/// numpy's `argmax`: the first of equal maxima wins.
fn best_of(row: &[f32], candidates: impl Iterator<Item = usize>) -> (usize, f32) {
    let mut best = (BLANK, f32::NEG_INFINITY);
    for class in candidates {
        if let Some(&p) = row.get(class)
            && p > best.1
        {
            best = (class, p);
        }
    }
    best
}

/// numpy's `round(5)`: scale, round half to even, scale back.
fn round5(value: f64) -> f64 {
    (value * 1e5).round_ties_even() / 1e5
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Classes: 0 blank, 1 "+", 2 "/", 3 "6", 4 "2", 5 "A", 6 space.
    fn characters() -> CharacterList {
        CharacterList::from_metadata("+\n/\n6\n2\nA").unwrap()
    }

    /// One step per entry, each a one-hot-ish row that gives `p` to its class and spreads the rest.
    fn steps(winners: &[(usize, f32)]) -> Probabilities {
        let classes = 7;
        let mut data = Vec::new();
        for &(class, p) in winners {
            let rest = (1.0 - p) / (classes - 1) as f32;
            data.extend((0..classes).map(|c| if c == class { p } else { rest }));
        }
        Probabilities::new(winners.len(), classes, data).unwrap()
    }

    #[test]
    fn merges_repeats_and_drops_blanks() {
        let output = steps(&[
            (0, 0.9),
            (1, 0.8),
            (1, 0.7),
            (3, 0.9),
            (0, 0.9),
            (3, 0.6),
            (4, 1.0),
        ]);
        let decoded = decode(&output, &characters(), None).unwrap();
        assert_eq!(decoded.text, "+662");
        assert_eq!(decoded.char_confidences, vec![0.8, 0.9, 0.6, 1.0]);
        assert_eq!(decoded.confidence, 0.825);
    }

    #[test]
    fn a_blank_separates_a_doubled_character() {
        let output = steps(&[(3, 0.9), (3, 0.9), (0, 0.9), (3, 0.9)]);
        assert_eq!(decode(&output, &characters(), None).unwrap().text, "66");
    }

    #[test]
    fn all_blank_is_an_empty_text_with_zero_confidence() {
        let output = steps(&[(0, 0.99), (0, 0.98), (0, 0.97)]);
        let decoded = decode(&output, &characters(), None).unwrap();
        assert_eq!(decoded.text, "");
        assert_eq!(decoded.confidence, 0.0);
        assert!(decoded.char_confidences.is_empty());
    }

    #[test]
    fn zero_steps_is_an_empty_text() {
        let output = Probabilities::new(0, 7, vec![]).unwrap();
        assert_eq!(decode(&output, &characters(), None).unwrap().text, "");
    }

    #[test]
    fn decodes_the_space_class() {
        let output = steps(&[(3, 0.9), (6, 0.9), (4, 0.9)]);
        assert_eq!(decode(&output, &characters(), None).unwrap().text, "6 2");
    }

    #[test]
    fn allowed_set_falls_back_to_the_best_allowed_class() {
        // Step 2: "A" leads with 0.5, "2" is the best allowed class with 0.3, blank has 0.2.
        let mut data = vec![0.0f32; 21];
        data[3] = 1.0;
        data[7 + 5] = 0.5;
        data[7 + 4] = 0.3;
        data[7] = 0.2;
        data[14 + 1] = 1.0;
        let output = Probabilities::new(3, 7, data).unwrap();
        let list = characters();
        assert_eq!(decode(&output, &list, None).unwrap().text, "6A+");
        let set = AllowedSet::new(&list, "+/62").unwrap();
        let decoded = decode(&output, &list, Some(&set)).unwrap();
        assert_eq!(decoded.text, "62+");
        assert_eq!(decoded.char_confidences, vec![1.0, 0.3, 1.0]);
    }

    #[test]
    fn allowed_set_falls_back_to_the_blank() {
        // Step 2: "A" leads, the blank beats every allowed class, so nothing is emitted there
        // and the two "6" steps on either side stay separate characters.
        let mut data = vec![0.0f32; 21];
        data[3] = 1.0;
        data[7 + 5] = 0.6;
        data[7] = 0.3;
        data[7 + 3] = 0.1;
        data[14 + 3] = 1.0;
        let output = Probabilities::new(3, 7, data).unwrap();
        let list = characters();
        let set = AllowedSet::new(&list, "+/62").unwrap();
        assert_eq!(decode(&output, &list, None).unwrap().text, "6A6");
        assert_eq!(decode(&output, &list, Some(&set)).unwrap().text, "66");
    }

    #[test]
    fn allowed_set_rejects_unknown_characters() {
        assert_eq!(
            AllowedSet::new(&characters(), "+9"),
            Err(Error::UnknownCharacter('9'))
        );
    }

    #[test]
    fn class_count_must_match_the_list() {
        let output = Probabilities::new(1, 5, vec![0.2; 5]).unwrap();
        assert_eq!(
            decode(&output, &characters(), None),
            Err(Error::ClassCountMismatch {
                model: 5,
                characters: 7
            })
        );
    }

    #[test]
    fn rejects_output_of_the_wrong_length() {
        assert!(Probabilities::new(2, 7, vec![0.0; 13]).is_err());
        assert!(Probabilities::new(1, 0, vec![]).is_err());
    }

    #[test]
    fn confidence_rounds_like_numpy() {
        assert_eq!(round5(0.123_456), 0.12346);
        assert_eq!(round5(0.0), 0.0);
    }
}
