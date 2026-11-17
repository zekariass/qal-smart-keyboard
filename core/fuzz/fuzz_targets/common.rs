// Shared fuzz fixtures: TEST keys from fixed public seeds (never trusted in production).
use qal_keyboard_core::pack::builder::{signing_key_from_seed, SigningKey};
use qal_keyboard_core::pack::KeyRing;
use qal_keyboard_core::{KeyStatus, TrustedKey};
use std::sync::OnceLock;

/// Same keys and seeds as `core/tests/common/mod.rs` and the test vectors.
pub const KEYS: [(&str, u8, KeyStatus); 3] = [
    ("test-active-1", 0x00, KeyStatus::Active),
    ("test-retiring-1", 0x20, KeyStatus::Retiring),
    ("test-revoked-1", 0x40, KeyStatus::Revoked),
];

pub fn signing_key(seed_start: u8) -> SigningKey {
    signing_key_from_seed(std::array::from_fn(|i| seed_start.wrapping_add(i as u8)))
}

pub fn ring() -> &'static KeyRing {
    static RING: OnceLock<KeyRing> = OnceLock::new();
    RING.get_or_init(|| {
        let keys: Vec<TrustedKey> = KEYS
            .iter()
            .map(|(id, seed, status)| TrustedKey {
                key_id: id.to_string(),
                public_key: signing_key(*seed).verifying_key().to_bytes().to_vec(),
                status: *status,
            })
            .collect();
        KeyRing::new(&keys).expect("test keys are valid")
    })
}
