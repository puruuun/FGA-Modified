//! The text forms of a support score: `+NN` and `+NN/+MM`.

/// How strictly the plus signs are demanded. Tesseract loses a plus now and then, which is why
/// the Kotlin reader tolerates a missing one; the default here is `Strict`, because the plus is the
/// leftmost glyph, so a field cut at the left loses it first and must then be refused.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    /// `+NN` and `+NN/+MM`.
    Strict,
    /// As `Strict`, but the plus in front of the class score may be missing when a Grand score
    /// follows. A lone digit string is still refused: a blank field read as a digit would be one.
    CompoundClassPlusOptional,
    /// As `Strict`, but the plus in front of the class score may be missing.
    ClassPlusOptional,
    /// Every plus may be missing.
    PlusOptional,
}

/// The numbers of a text that matches the format, before any range is applied.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Parsed {
    pub class: u8,
    /// `None` for the ordinary `+NN` form.
    pub grand: Option<u8>,
}

/// Anything else is `None`: extra digits, a doubled sign, other characters, spaces, an empty
/// text. Nothing is repaired here (`+571+16` stays unknown) and no full-width sign is folded; the
/// one repair the reader makes, a misread sign, is [`correct_signs`] in front of this.
pub fn parse(text: &str, format: Format) -> Option<Parsed> {
    let mut parts = text.split('/');
    let class_part = parts.next()?;
    let grand_part = parts.next();
    if parts.next().is_some() {
        return None;
    }
    let class_plus_needed = match format {
        Format::Strict => true,
        Format::CompoundClassPlusOptional => grand_part.is_none(),
        Format::ClassPlusOptional | Format::PlusOptional => false,
    };
    let grand_plus_needed = format != Format::PlusOptional;
    let class = number(class_part, class_plus_needed)?;
    let grand = match grand_part {
        None => None,
        Some(part) => Some(number(part, grand_plus_needed)?),
    };
    Some(Parsed { class, grand })
}

/// What the recogniser has been seen to read, or could read, for a plus whose vertical stroke it
/// lost: on the owner's tablet the separator line of the support list crosses the plus before
/// `16`, and `+62/+16` came out as `+62/-16` (QA 0025, section 4.19). Only these: accepting any
/// character in a sign position turned cut KR fields into answers in that measurement.
pub const SIGN_LOOKALIKES: [char; 7] = ['-', '=', '~', '_', '\u{2013}', '\u{2014}', '\u{2212}'];

/// The text with each [`SIGN_LOOKALIKES`] character in a sign position (the first character, and
/// the one right after the only `/`) read as `+`, and the character indices that were changed.
/// `None` when nothing was changed. The result is not checked here: it must pass [`parse`] like any
/// other text, so a missing plus or a `/` read as a digit stays refused.
pub fn correct_signs(text: &str) -> Option<(String, Vec<usize>)> {
    let chars: Vec<char> = text.chars().collect();
    let slashes: Vec<usize> = chars
        .iter()
        .enumerate()
        .filter(|&(_, &c)| c == '/')
        .map(|(i, _)| i)
        .collect();
    let mut sign_positions = vec![0];
    if let [slash] = slashes.as_slice() {
        sign_positions.push(slash + 1);
    }
    let mut corrected = Vec::new();
    let text: String = chars
        .iter()
        .enumerate()
        .map(|(i, &c)| {
            if sign_positions.contains(&i) && SIGN_LOOKALIKES.contains(&c) {
                corrected.push(i);
                '+'
            } else {
                c
            }
        })
        .collect();
    (!corrected.is_empty()).then_some((text, corrected))
}

/// One or two digits after an optional or required plus. A two-digit number may not start with
/// zero: `+05` is a misread, not a score the game shows.
fn number(part: &str, plus_required: bool) -> Option<u8> {
    let digits = match part.strip_prefix('+') {
        Some(rest) => rest,
        None if plus_required => return None,
        None => part,
    };
    let values: Option<Vec<u8>> = digits
        .chars()
        .map(|c| c.to_digit(10).and_then(|d| u8::try_from(d).ok()))
        .collect();
    match values?.as_slice() {
        [ones] => Some(*ones),
        [tens, ones] if *tens > 0 => Some(tens * 10 + ones),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strict(text: &str) -> Option<(u8, Option<u8>)> {
        parse(text, Format::Strict).map(|p| (p.class, p.grand))
    }

    #[test]
    fn a_cut_grand_score_is_still_a_number() {
        assert_eq!(strict("+62/+1"), Some((62, Some(1))));
        assert_eq!(strict("+62/+0"), Some((62, Some(0))));
    }

    #[test]
    fn strict_takes_the_two_forms() {
        assert_eq!(strict("+62"), Some((62, None)));
        assert_eq!(strict("+7"), Some((7, None)));
        assert_eq!(strict("+0"), Some((0, None)));
        assert_eq!(strict("+62/+16"), Some((62, Some(16))));
        assert_eq!(strict("+57/+0"), Some((57, Some(0))));
    }

    #[test]
    fn strict_refuses_a_missing_plus() {
        assert_eq!(strict("62"), None);
        assert_eq!(strict("62/+16"), None);
        assert_eq!(strict("+62/16"), None);
    }

    #[test]
    fn the_class_plus_may_be_missing_only_before_a_grand_score() {
        let take =
            |text| parse(text, Format::CompoundClassPlusOptional).map(|p| (p.class, p.grand));
        assert_eq!(take("62/+16"), Some((62, Some(16))));
        assert_eq!(take("+62/+16"), Some((62, Some(16))));
        assert_eq!(take("62"), None);
        assert_eq!(take("+62/16"), None);
    }

    #[test]
    fn the_class_plus_may_be_the_only_one_missing() {
        let take = |text| parse(text, Format::ClassPlusOptional).map(|p| (p.class, p.grand));
        assert_eq!(take("62/+16"), Some((62, Some(16))));
        assert_eq!(take("62"), Some((62, None)));
        assert_eq!(take("+62/16"), None);
    }

    #[test]
    fn plus_optional_takes_every_plus_missing() {
        let take = |text| parse(text, Format::PlusOptional).map(|p| (p.class, p.grand));
        assert_eq!(take("62/16"), Some((62, Some(16))));
        assert_eq!(take("5"), Some((5, None)));
    }

    #[test]
    fn signs_are_corrected_only_in_sign_positions() {
        let fixed = |text: &str| correct_signs(text);
        assert_eq!(fixed("+62/-16"), Some(("+62/+16".to_owned(), vec![4])));
        assert_eq!(fixed("-62/+16"), Some(("+62/+16".to_owned(), vec![0])));
        assert_eq!(
            fixed("=62/\u{2212}16"),
            Some(("+62/+16".to_owned(), vec![0, 4]))
        );
        assert_eq!(fixed("\u{2014}5"), Some(("+5".to_owned(), vec![0])));
        assert_eq!(fixed("_62//-16"), Some(("+62//-16".to_owned(), vec![0])));
        for text in [
            "+6-2", "+62-16", "+62/1-6", "+62/+16", "+62/t16", "+62/16-", "",
        ] {
            assert_eq!(fixed(text), None, "{text:?}");
        }
    }

    #[test]
    fn nothing_else_is_a_score() {
        for format in [
            Format::Strict,
            Format::CompoundClassPlusOptional,
            Format::ClassPlusOptional,
            Format::PlusOptional,
        ] {
            for text in [
                "",
                "+",
                "/",
                "+/+",
                "+571+16",
                "+562",
                "++62",
                "+-6",
                "+6 2",
                " +62",
                "+62 ",
                "+62/",
                "/+16",
                "+62//+16",
                "+62/+16/+1",
                "+062",
                "+162",
                "+62/+016",
                "+62/+16/+16",
                "+\u{0666}\u{0662}",
                "+\u{0662}/+\u{0661}\u{0666}",
                "+05",
                "+00",
                "+6A",
                "＋62",
                "+６２",
                "+62\n",
                "_",
                "X",
                "016",
                "+62/+160",
                "+62/++16",
            ] {
                assert_eq!(parse(text, format), None, "{text:?} with {format:?}");
            }
        }
    }
}
