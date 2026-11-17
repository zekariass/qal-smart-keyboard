//! Property tests: no input makes pack loading panic, and no mutation of a valid pack is accepted.

mod common;

use common::*;
use proptest::prelude::*;
use qal_keyboard_core::pack::MAGIC;
use qal_keyboard_core::CoreError;

fn rejected(r: Result<qal_keyboard_core::PackInfo, CoreError>) -> bool {
    matches!(r, Err(CoreError::PackRejected { .. }))
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 512, ..ProptestConfig::default() })]

    #[test]
    fn arbitrary_bytes_are_rejected_without_panic(bytes in proptest::collection::vec(any::<u8>(), 0..2048)) {
        prop_assert!(rejected(engine().load_pack(bytes)));
    }

    #[test]
    fn arbitrary_bytes_after_a_valid_header_are_rejected(
        key_id_len in any::<u8>(),
        rest in proptest::collection::vec(any::<u8>(), 0..1024),
    ) {
        let mut bytes = MAGIC.to_vec();
        bytes.extend_from_slice(&1u16.to_be_bytes());
        bytes.push(key_id_len);
        bytes.extend_from_slice(&rest);
        prop_assert!(rejected(engine().load_pack(bytes)));
    }

    #[test]
    fn mutated_valid_pack_is_rejected(
        edits in proptest::collection::vec((any::<prop::sample::Index>(), 1u8..=255), 1..8),
    ) {
        let good = valid_pack();
        let mut bytes = good.clone();
        for (idx, x) in edits {
            let i = idx.index(bytes.len());
            bytes[i] ^= x;
        }
        prop_assume!(bytes != good);
        prop_assert!(rejected(engine().load_pack(bytes)));
    }

    #[test]
    fn spliced_valid_pack_is_rejected(
        at in any::<prop::sample::Index>(),
        cut in 0usize..64,
        insert in proptest::collection::vec(any::<u8>(), 0..64),
    ) {
        let good = valid_pack();
        let start = at.index(good.len());
        let end = (start + cut).min(good.len());
        let mut bytes = good[..start].to_vec();
        bytes.extend_from_slice(&insert);
        bytes.extend_from_slice(&good[end..]);
        prop_assume!(bytes != good);
        prop_assert!(rejected(engine().load_pack(bytes)));
    }

    /// Validly signed but arbitrary manifest bytes: exercises the post-signature JSON path.
    #[test]
    fn signed_arbitrary_manifest_never_panics(
        manifest in proptest::collection::vec(any::<u8>(), 1..512),
        artifact in proptest::collection::vec(any::<u8>(), 0..64),
    ) {
        let _ = engine().load_pack(signed_raw(&active(), &manifest, &artifact));
    }

    /// Validly signed manifests with arbitrary string field values.
    #[test]
    fn signed_manifest_with_arbitrary_fields_never_panics(
        field in prop::sample::select(vec![
            "resourceId", "languageCode", "resourceType", "version", "platform",
            "releaseChannel", "sha256", "licenseCode", "signatureKeyId", "baseResourceId",
            "minAppVersion", "artifactUrl",
        ]),
        value in ".{0,80}",
    ) {
        let a = active();
        let mut m = manifest_value(&a, ARTIFACT);
        m.insert(field.into(), serde_json::Value::String(value));
        let _ = engine().load_pack(signed_raw(&a, &serde_json::to_vec(&m).unwrap(), ARTIFACT));
    }
}
