//! Arbitrary manifest bytes that are validly signed by a trusted test key: exercises everything
//! after signature verification (UTF-8, JSON, field validation, key binding, size, SHA-256).
//!
//! Input layout: [key selector u8][manifest_len u16 BE][manifest][artifact = rest].
#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;
use qal_keyboard_core::pack::builder::sign_container;
use qal_keyboard_core::pack::{verify_pack, PackLimits};

fuzz_target!(|data: &[u8]| {
    let Some((&selector, rest)) = data.split_first() else {
        return;
    };
    let Some((len, rest)) = rest.split_first_chunk::<2>() else {
        return;
    };
    let len = usize::from(u16::from_be_bytes(*len)).min(rest.len());
    let (manifest, artifact) = rest.split_at(len);
    let (key_id, seed, _) = common::KEYS[usize::from(selector) % common::KEYS.len()];
    let Ok(container) = sign_container(&common::signing_key(seed), key_id, manifest, artifact)
    else {
        return; // e.g. empty manifest: not representable
    };
    if let Ok(pack) = verify_pack(container, common::ring(), &PackLimits::default()) {
        assert_eq!(pack.artifact(), artifact);
        assert_eq!(pack.manifest_bytes(), manifest);
        assert_eq!(pack.manifest().signature_key_id, key_id);
    }
});
