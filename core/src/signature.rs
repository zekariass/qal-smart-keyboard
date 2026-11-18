//! Strict Ed25519 verification exposed over FFI.
//!
//! The Android client verifies the entitlement token's signature (ADR 0013 D4.3) through this one
//! function, so the app ships no second Ed25519 implementation. It uses the same strict rules as
//! pack verification: canonical `S`, and small-order keys and `R` rejected.

use ed25519_dalek::{Signature, VerifyingKey};

/// Verifies an Ed25519 `signature` (64 bytes) over `message` with a raw 32-byte `public_key`,
/// using `verify_strict`. Wrong lengths, an undecodable key or a bad signature give `false`;
/// it never panics.
#[uniffi::export]
pub fn verify_ed25519(public_key: Vec<u8>, message: Vec<u8>, signature: Vec<u8>) -> bool {
    let Ok(key_bytes) = <[u8; 32]>::try_from(public_key.as_slice()) else {
        return false;
    };
    let Ok(sig_bytes) = <[u8; 64]>::try_from(signature.as_slice()) else {
        return false;
    };
    let Ok(key) = VerifyingKey::from_bytes(&key_bytes) else {
        return false;
    };
    key.verify_strict(&message, &Signature::from_bytes(&sig_bytes))
        .is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    fn b64url(s: &str) -> Vec<u8> {
        let val = |c: u8| match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'-' => 62,
            b'_' => 63,
            _ => panic!("bad base64url"),
        };
        let mut out = Vec::new();
        let (mut acc, mut bits) = (0u32, 0);
        for c in s.bytes() {
            acc = (acc << 6) | u32::from(val(c));
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                out.push((acc >> bits) as u8);
                acc &= (1 << bits) - 1;
            }
        }
        out
    }

    const PK1: &str = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    const SIG1: &str = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";
    const PK2: &str = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    const SIG2: &str = "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00";

    #[test]
    fn rfc8032_vectors_verify() {
        assert!(verify_ed25519(hex(PK1), vec![], hex(SIG1)));
        assert!(verify_ed25519(hex(PK2), hex("72"), hex(SIG2)));
    }

    #[test]
    fn rfc8037_a4_jws_example_verifies() {
        let key = b64url("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo");
        let sig = b64url(
            "hgyY0il_MGCjP0JzlnLWG1PPOt7-09PGcvMg3AIbQR6dWbhijcNR4ki4iylGjg5BhVsPt9g7sVvpAr_MuM0KAg",
        );
        let input = b"eyJhbGciOiJFZERTQSJ9.RXhhbXBsZSBvZiBFZDI1NTE5IHNpZ25pbmc".to_vec();
        assert!(verify_ed25519(key.clone(), input.clone(), sig.clone()));
        let mut tampered = input;
        tampered[0] ^= 1;
        assert!(!verify_ed25519(key, tampered, sig));
    }

    #[test]
    fn tampered_message_signature_or_key_is_rejected() {
        assert!(!verify_ed25519(hex(PK2), hex("73"), hex(SIG2)));
        let mut sig = hex(SIG2);
        sig[10] ^= 1;
        assert!(!verify_ed25519(hex(PK2), hex("72"), sig));
        assert!(!verify_ed25519(hex(PK1), hex("72"), hex(SIG2)));
    }

    #[test]
    fn malformed_lengths_do_not_panic() {
        assert!(!verify_ed25519(vec![], vec![], vec![]));
        assert!(!verify_ed25519(hex(PK1)[..31].to_vec(), vec![], hex(SIG1)));
        assert!(!verify_ed25519(hex(PK1), vec![], hex(SIG1)[..63].to_vec()));
        let mut long = hex(SIG1);
        long.push(0);
        assert!(!verify_ed25519(hex(PK1), vec![], long));
        assert!(!verify_ed25519([0xffu8; 33].to_vec(), vec![], hex(SIG1)));
    }

    #[test]
    fn malleable_signature_is_rejected() {
        // S + L encodes the same scalar; a verifier without a canonical-S check accepts it.
        let l = hex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");
        let mut sig = hex(SIG1);
        let mut carry = 0u16;
        for i in 0..32 {
            let v = u16::from(sig[32 + i]) + u16::from(l[i]) + carry;
            sig[32 + i] = (v & 0xff) as u8;
            carry = v >> 8;
        }
        assert_eq!(carry, 0, "S + L must still fit in 32 bytes for this vector");
        assert!(!verify_ed25519(hex(PK1), vec![], sig));
    }

    #[test]
    fn small_order_key_is_rejected() {
        // The identity point as the key and as R, with S = 0, verifies for every message under
        // non-strict rules; the strict check refuses a small-order key.
        let mut key = vec![0u8; 32];
        key[0] = 1;
        let mut sig = vec![0u8; 64];
        sig[0] = 1;
        assert!(!verify_ed25519(key, b"any message".to_vec(), sig));
    }
}
