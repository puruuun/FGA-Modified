//! Known-answer and round-trip checks for ring, the TLS crypto candidate for stages 2A/2B.

use super::hex;

/// SHA-256("abc"), FIPS 180-2 appendix B.1.
const SHA256_ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
/// RFC 8032 section 7.1, test 1 (empty message).
const ED25519_PUBLIC: &str = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
const ED25519_SIGNATURE: &str = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

fn checks() -> Result<String, String> {
    use ring::{aead, digest, signature};

    let digest = digest::digest(&digest::SHA256, b"abc");
    if digest.as_ref() != hex::decode(SHA256_ABC)?.as_slice() {
        return Err("sha256 mismatch".to_owned());
    }

    let mut ciphers = Vec::new();
    for (name, algorithm) in [
        ("aes256gcm", &aead::AES_256_GCM),
        ("chacha20poly1305", &aead::CHACHA20_POLY1305),
    ] {
        let plain = b"fga probe plaintext".to_vec();
        let make_key = || {
            aead::UnboundKey::new(algorithm, &[0x42; 32])
                .map(aead::LessSafeKey::new)
                .map_err(|_| format!("{name}: key rejected"))
        };
        // A fixed nonce is fine here: the key never protects anything but this test text.
        let nonce = || aead::Nonce::assume_unique_for_key([7; 12]);
        let key = make_key()?;
        let mut sealed = plain.clone();
        key.seal_in_place_append_tag(nonce(), aead::Aad::from(b"aad"), &mut sealed)
            .map_err(|_| format!("{name}: seal failed"))?;
        if sealed.len() != plain.len() + algorithm.tag_len() || sealed.starts_with(&plain) {
            return Err(format!("{name}: sealed text looks wrong"));
        }

        let mut tampered = sealed.clone();
        if let Some(byte) = tampered.first_mut() {
            *byte ^= 1;
        }
        if key
            .open_in_place(nonce(), aead::Aad::from(b"aad"), &mut tampered)
            .is_ok()
        {
            return Err(format!("{name}: tampered text was accepted"));
        }

        let opened = key
            .open_in_place(nonce(), aead::Aad::from(b"aad"), &mut sealed)
            .map_err(|_| format!("{name}: open failed"))?;
        if opened != plain.as_slice() {
            return Err(format!("{name}: round trip mismatch"));
        }
        ciphers.push(name);
    }

    let public = hex::decode(ED25519_PUBLIC)?;
    let signature_bytes = hex::decode(ED25519_SIGNATURE)?;
    let key = signature::UnparsedPublicKey::new(&signature::ED25519, &public);
    key.verify(b"", &signature_bytes)
        .map_err(|_| "ed25519: valid signature rejected".to_owned())?;
    if key.verify(b"x", &signature_bytes).is_ok() {
        return Err("ed25519: signature accepted for another message".to_owned());
    }

    Ok(format!("sha256 {} ed25519", ciphers.join(" ")))
}

pub fn ring_checks() -> Result<String, String> {
    checks()
        .map(|detail| format!("ring: {detail}"))
        .map_err(|detail| format!("ring: {detail}"))
}
