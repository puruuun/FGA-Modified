//! The model's class list.
//!
//! PaddleOCR ONNX exports carry their characters in the metadata entry `character`, one per
//! line. RapidOCR reads it with Python's `str.splitlines()`, then puts the CTC blank in front
//! (class 0) and a space at the end (the last class). For PP-OCRv6 small that is 18708 lines,
//! so the model has 18710 classes. The same layout is kept here so a class number means the same
//! character as in the Python check.

use crate::Error;

/// The metadata key that holds the character list.
pub const METADATA_KEY: &str = "character";

/// Class 0 of every CTC output.
pub const BLANK: usize = 0;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CharacterList {
    /// Classes 1..=len; the blank and the trailing space are implied.
    entries: Vec<String>,
}

impl CharacterList {
    /// Parses the metadata text exactly as RapidOCR does.
    pub fn from_metadata(text: &str) -> Result<Self, Error> {
        let entries: Vec<String> = split_lines(text).map(str::to_owned).collect();
        if entries.is_empty() {
            return Err(Error::EmptyCharacterList);
        }
        Ok(Self { entries })
    }

    /// Number of model classes: the entries plus the blank and the space.
    pub fn class_count(&self) -> usize {
        self.entries.len() + 2
    }

    /// The text of a class; `None` for the blank and for numbers past the end.
    pub fn symbol(&self, class: usize) -> Option<&str> {
        if class == BLANK {
            None
        } else if class == self.entries.len() + 1 {
            Some(" ")
        } else {
            self.entries.get(class - 1).map(String::as_str)
        }
    }

    /// Every class whose text is exactly `c`.
    pub fn classes_of(&self, c: char) -> Vec<usize> {
        let mut buffer = [0u8; 4];
        let wanted: &str = c.encode_utf8(&mut buffer);
        (1..self.class_count())
            .filter(|&class| self.symbol(class) == Some(wanted))
            .collect()
    }
}

/// Python's `str.splitlines()`: every line boundary it knows, and no empty item after a
/// trailing boundary.
fn split_lines(text: &str) -> impl Iterator<Item = &str> {
    let mut rest = text;
    std::iter::from_fn(move || {
        if rest.is_empty() {
            return None;
        }
        match rest.char_indices().find(|&(_, c)| is_line_boundary(c)) {
            Some((at, c)) => {
                let line = rest.get(..at).unwrap_or_default();
                let mut next = at + c.len_utf8();
                if c == '\r' && rest.get(next..next + 1) == Some("\n") {
                    next += 1;
                }
                rest = rest.get(next..).unwrap_or_default();
                Some(line)
            }
            None => {
                let line = rest;
                rest = "";
                Some(line)
            }
        }
    })
}

fn is_line_boundary(c: char) -> bool {
    matches!(
        c,
        '\n' | '\r'
            | '\u{0b}'
            | '\u{0c}'
            | '\u{1c}'
            | '\u{1d}'
            | '\u{1e}'
            | '\u{85}'
            | '\u{2028}'
            | '\u{2029}'
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_first_and_space_last() {
        let list = CharacterList::from_metadata("+\n/\n0\n1").unwrap();
        assert_eq!(list.class_count(), 6);
        assert_eq!(list.symbol(0), None);
        assert_eq!(list.symbol(1), Some("+"));
        assert_eq!(list.symbol(4), Some("1"));
        assert_eq!(list.symbol(5), Some(" "));
        assert_eq!(list.symbol(6), None);
    }

    #[test]
    fn splits_like_python_splitlines() {
        let list = CharacterList::from_metadata("a\r\nb\rc\u{2028}d\u{85}e\n").unwrap();
        let symbols: Vec<_> = (1..=5).map(|c| list.symbol(c).unwrap()).collect();
        assert_eq!(symbols, ["a", "b", "c", "d", "e"]);
        // A trailing newline adds no empty entry, so the space is class 6.
        assert_eq!(list.class_count(), 7);
    }

    #[test]
    fn keeps_empty_lines_between_boundaries() {
        let list = CharacterList::from_metadata("a\n\nb").unwrap();
        assert_eq!(list.symbol(2), Some(""));
        assert_eq!(list.class_count(), 5);
    }

    #[test]
    fn multibyte_entries_survive() {
        let list = CharacterList::from_metadata("屋\n敷\n\u{1f694}").unwrap();
        assert_eq!(list.symbol(3), Some("\u{1f694}"));
        assert_eq!(list.classes_of('敷'), vec![2]);
    }

    #[test]
    fn empty_text_is_an_error() {
        assert_eq!(
            CharacterList::from_metadata(""),
            Err(Error::EmptyCharacterList)
        );
    }

    #[test]
    fn finds_the_space_class() {
        let list = CharacterList::from_metadata("a\nb").unwrap();
        assert_eq!(list.classes_of(' '), vec![3]);
        assert!(list.classes_of('z').is_empty());
    }
}
