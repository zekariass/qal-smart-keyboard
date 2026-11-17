//! The FFI-facing engine object and its data types.

use crate::error::CoreError;
use crate::pack::{
    self, KeyRing, PackInfo, PackLimits, PackPlatform, PackRejectReason, ResourceType, TrustedKey,
    VerifiedPack,
};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};

/// Engine construction options.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EngineConfig {
    /// Upper bound on candidates returned per update.
    pub max_candidates: u32,
    /// Pinned pack-signing public keys (current and next, plus retiring/revoked ones).
    /// An empty list means every pack is rejected with `UnknownKey`.
    pub trusted_keys: Vec<TrustedKey>,
    /// Pack size limits; `None` uses the defaults (32 MiB artifact, 64 KiB manifest).
    pub pack_limits: Option<PackLimits>,
}

/// How typed characters are interpreted for a language.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum InputMode {
    Abc,
    DirectFidel,
    PhoneticLatin,
    Qubee,
    English,
}

/// A key event from the host keyboard.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum KeyEvent {
    Char { text: String },
    Backspace,
    Space,
    Enter,
    Commit,
}

/// Why a candidate is offered.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CandidateKind {
    Transliteration,
    Completion,
    Correction,
    Prediction,
}

/// One suggestion shown in the candidate strip.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Candidate {
    pub text: String,
    pub kind: CandidateKind,
}

/// Instructions for the host editor after an engine call.
#[derive(Debug, Clone, Default, PartialEq, Eq, uniffi::Record)]
pub struct EditorUpdate {
    /// Text to show as composing (underlined) text; empty when nothing is composing.
    pub composing: String,
    /// Text to commit to the editor.
    pub commit: String,
    /// Number of characters before the cursor the host should delete first.
    pub delete_before: u32,
    pub candidates: Vec<Candidate>,
}

#[derive(Debug)]
struct State {
    mode: InputMode,
    composing: String,
}

/// Rollback-protection slot: one loaded pack per (language, resource type, platform).
type PackSlot = (String, ResourceType, PackPlatform);

/// The language engine. Internally synchronised, so it is `Send + Sync`.
#[derive(uniffi::Object)]
pub struct KeyboardEngine {
    state: Mutex<State>,
    /// Verified packs. Separate lock so pack loading never blocks the key path.
    packs: Mutex<HashMap<PackSlot, Arc<VerifiedPack>>>,
    key_ring: KeyRing,
    pack_limits: PackLimits,
    #[allow(dead_code)] // max_candidates is used by the ranker from C1.7
    config: EngineConfig,
}

const SUPPORTED_LANGUAGES: [&str; 4] = ["am", "ti", "om", "en"];

#[uniffi::export]
impl KeyboardEngine {
    /// Fails with `CoreError::InvalidConfig` if a trusted key is malformed (not 32 bytes, not a
    /// valid Ed25519 point, small order, duplicate id or duplicate key) or the limits are unusable.
    #[uniffi::constructor]
    pub fn new(config: EngineConfig) -> Result<Arc<Self>, CoreError> {
        let key_ring = KeyRing::new(&config.trusted_keys)?;
        let pack_limits = config.pack_limits.unwrap_or_default();
        pack_limits.validate()?;
        Ok(Arc::new(Self {
            state: Mutex::new(State {
                mode: InputMode::Abc,
                composing: String::new(),
            }),
            packs: Mutex::new(HashMap::new()),
            key_ring,
            pack_limits,
            config,
        }))
    }

    /// Verify and activate a signed language pack (`docs/specs/pack-format-v1.md`).
    ///
    /// Nothing is activated unless the container is authentic (strict Ed25519 under a trusted,
    /// non-revoked key) and intact (artifact size and SHA-256 match the signed manifest).
    /// Engine policy on top of verification:
    /// - rollback protection per (language, resource type, platform): a lower version, or the
    ///   same version with a different artifact, is rejected with `Downgrade`; reloading the
    ///   identical pack is an idempotent success;
    /// - delta packs (`baseResourceId` set) are verified, then rejected with
    ///   `DeltaNotSupported`: the host pack manager applies deltas and loads the full result.
    pub fn load_pack(&self, bytes: Vec<u8>) -> Result<PackInfo, CoreError> {
        // Verify outside any lock: hashing a large artifact must not block other calls.
        let verified = pack::verify_pack(bytes, &self.key_ring, &self.pack_limits)?;
        if verified.is_delta() {
            return Err(CoreError::PackRejected {
                reason: PackRejectReason::DeltaNotSupported,
                detail: "delta packs are applied by the host pack manager".to_string(),
            });
        }
        let info = verified.info();
        let m = verified.manifest();
        let slot = (m.language_code.clone(), m.resource_type, m.platform);

        let mut packs = self.lock_packs();
        if let Some(loaded) = packs.get(&slot) {
            let l = loaded.manifest();
            if m.version == l.version && m.sha256 == l.sha256 && m.resource_id == l.resource_id {
                return Ok(loaded.info()); // identical pack: idempotent no-op
            }
            if m.version <= l.version {
                return Err(CoreError::PackRejected {
                    reason: PackRejectReason::Downgrade,
                    detail: "a pack with the same or a higher version is already loaded"
                        .to_string(),
                });
            }
        }
        packs.insert(slot, Arc::new(verified));
        Ok(info)
    }

    /// Metadata of every loaded pack, sorted by language, resource type and platform.
    pub fn loaded_packs(&self) -> Vec<PackInfo> {
        let packs = self.lock_packs();
        let mut slots: Vec<&PackSlot> = packs.keys().collect();
        slots.sort();
        slots
            .into_iter()
            .filter_map(|s| packs.get(s).map(|p| p.info()))
            .collect()
    }

    pub fn set_input_mode(&self, language_code: String, mode: InputMode) -> Result<(), CoreError> {
        if !SUPPORTED_LANGUAGES.contains(&language_code.as_str()) {
            return Err(CoreError::UnsupportedLanguage {
                code: language_code,
            });
        }
        if !mode_fits_language(mode, &language_code) {
            return Err(CoreError::InvalidState {
                reason: format!("{mode:?} is not an input mode for {language_code}"),
            });
        }
        // Refuse modes whose engines have not landed yet, so the host keeps a working
        // mode instead of an engine that silently swallows keystrokes.
        if !mode_available(mode) {
            return Err(CoreError::InvalidState {
                reason: format!("{mode:?} is not available in this build"),
            });
        }
        let mut s = self.lock();
        s.mode = mode;
        s.composing.clear();
        Ok(())
    }

    pub fn on_key(&self, event: KeyEvent) -> EditorUpdate {
        let mut s = self.lock();
        if !matches!(s.mode, InputMode::Abc | InputMode::English) {
            // Unreachable today: set_input_mode refuses modes that have not landed (C1.3 to C1.5).
            return EditorUpdate::default();
        }
        match event {
            KeyEvent::Char { text } => EditorUpdate {
                commit: text,
                ..Default::default()
            },
            KeyEvent::Space => EditorUpdate {
                commit: " ".to_string(),
                ..Default::default()
            },
            KeyEvent::Enter => EditorUpdate {
                commit: "\n".to_string(),
                ..Default::default()
            },
            KeyEvent::Backspace => {
                if s.composing.pop().is_some() {
                    EditorUpdate {
                        composing: s.composing.clone(),
                        ..Default::default()
                    }
                } else {
                    EditorUpdate {
                        delete_before: 1,
                        ..Default::default()
                    }
                }
            }
            KeyEvent::Commit => EditorUpdate::default(),
        }
    }

    /// Select a candidate by index. No candidates exist yet, so this is a no-op update.
    pub fn select_candidate(&self, index: u32) -> EditorUpdate {
        let _ = index;
        EditorUpdate::default()
    }

    pub fn reset(&self) -> EditorUpdate {
        self.lock().composing.clear();
        EditorUpdate::default()
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_string()
    }
}

/// Which input modes make sense for which language. `Abc` (plain Latin passthrough)
/// is allowed for every language.
fn mode_fits_language(mode: InputMode, language_code: &str) -> bool {
    match mode {
        InputMode::Abc => true,
        InputMode::DirectFidel | InputMode::PhoneticLatin => matches!(language_code, "am" | "ti"),
        InputMode::Qubee => language_code == "om",
        InputMode::English => language_code == "en",
    }
}

/// Modes implemented in this build. Extend as C1.3 (DirectFidel), C1.4 (PhoneticLatin)
/// and C1.5 (Qubee) land.
fn mode_available(mode: InputMode) -> bool {
    matches!(mode, InputMode::Abc | InputMode::English)
}

impl KeyboardEngine {
    fn lock(&self) -> MutexGuard<'_, State> {
        // A poisoned lock only means another thread panicked; the state is still usable.
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn lock_packs(&self) -> MutexGuard<'_, HashMap<PackSlot, Arc<VerifiedPack>>> {
        // Packs are only inserted after full verification, so a poisoned map is still valid.
        self.packs.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine() -> Arc<KeyboardEngine> {
        KeyboardEngine::new(EngineConfig {
            max_candidates: 5,
            trusted_keys: vec![],
            pack_limits: None,
        })
        .unwrap()
    }

    #[test]
    fn abc_passthrough() {
        let e = engine();
        let u = e.on_key(KeyEvent::Char { text: "a".into() });
        assert_eq!(u.commit, "a");
        assert!(u.composing.is_empty() && u.candidates.is_empty());
        assert_eq!(e.on_key(KeyEvent::Space).commit, " ");
        assert_eq!(e.on_key(KeyEvent::Enter).commit, "\n");
    }

    #[test]
    fn english_passthrough() {
        let e = engine();
        e.set_input_mode("en".into(), InputMode::English).unwrap();
        assert_eq!(e.on_key(KeyEvent::Char { text: "x".into() }).commit, "x");
    }

    #[test]
    fn backspace_with_empty_composing_deletes_one() {
        let u = engine().on_key(KeyEvent::Backspace);
        assert_eq!(u.delete_before, 1);
        assert!(u.commit.is_empty());
    }

    #[test]
    fn unimplemented_mode_is_refused_and_previous_mode_kept() {
        let e = engine();
        let err = e
            .set_input_mode("am".into(), InputMode::PhoneticLatin)
            .unwrap_err();
        assert!(matches!(err, CoreError::InvalidState { .. }));
        // Still in ABC: keystrokes are not swallowed.
        assert_eq!(e.on_key(KeyEvent::Char { text: "a".into() }).commit, "a");
    }

    #[test]
    fn mode_must_fit_language() {
        let e = engine();
        for (lang, mode) in [
            ("en", InputMode::DirectFidel),
            ("am", InputMode::Qubee),
            ("om", InputMode::English),
            ("ti", InputMode::English),
        ] {
            assert!(
                matches!(
                    e.set_input_mode(lang.into(), mode),
                    Err(CoreError::InvalidState { .. })
                ),
                "{lang} {mode:?} should be rejected"
            );
        }
        e.set_input_mode("am".into(), InputMode::Abc).unwrap();
    }

    #[test]
    fn unsupported_language_rejected() {
        let err = engine()
            .set_input_mode("xx".into(), InputMode::Abc)
            .unwrap_err();
        assert!(matches!(err, CoreError::UnsupportedLanguage { .. }));
    }

    #[test]
    fn load_pack_rejects_garbage() {
        let e = engine();
        for bytes in [vec![], vec![0u8; 16], b"not a pack".to_vec()] {
            assert!(matches!(
                e.load_pack(bytes),
                Err(CoreError::PackRejected { .. })
            ));
        }
    }

    #[test]
    fn version_matches_crate() {
        assert_eq!(engine().version(), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn engine_is_send_sync() {
        fn assert_ss<T: Send + Sync>() {}
        assert_ss::<KeyboardEngine>();
    }
}
