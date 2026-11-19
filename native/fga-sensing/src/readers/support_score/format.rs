//! The text forms of a support score: `+NN` and `+NN/+MM`.

/// How strictly the plus signs are demanded. Tesseract loses a plus now and then, which is why
/// the Kotlin reader tolerates a missing one; whether the new engine needs that is decided on the
/// labelled samples and recorded in docs/agents/rust-native.md.
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
/// text. Nothing is repaired (`+571+16` stays unknown) and no full-width sign is folded.
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
