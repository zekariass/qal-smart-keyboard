//! Arbitrary bytes as a whole container: framing, key lookup and signature paths.
//! Must never panic; must never accept (the fuzzer cannot forge Ed25519 signatures).
#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;
use qal_keyboard_core::pack::{verify_pack, PackLimits};

fuzz_target!(|data: &[u8]| {
    let limits = PackLimits::default();
    if let Ok(pack) = verify_pack(data.to_vec(), common::ring(), &limits) {
        // Only possible for an input that is a genuinely signed seed (e.g. from the vectors).
        assert_eq!(pack.artifact().len() as u64, pack.manifest().size_bytes);
    }
});
