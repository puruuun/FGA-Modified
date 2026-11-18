/// Decodes the fixed test vectors. Kept local so the probe adds no crate for it.
#[cfg_attr(all(windows, target_env = "gnu"), allow(dead_code))]
pub fn decode(text: &str) -> Result<Vec<u8>, String> {
    if !text.len().is_multiple_of(2) {
        return Err(format!("odd hex length {}", text.len()));
    }
    text.as_bytes()
        .chunks(2)
        .map(|pair| {
            let digits = std::str::from_utf8(pair).map_err(|e| e.to_string())?;
            u8::from_str_radix(digits, 16).map_err(|e| format!("bad hex {digits:?}: {e}"))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::decode;

    #[test]
    fn decodes_and_rejects() {
        assert_eq!(decode("00ff10").unwrap(), vec![0x00, 0xff, 0x10]);
        assert!(decode("abc").is_err());
        assert!(decode("zz").is_err());
    }
}
