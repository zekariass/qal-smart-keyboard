//! The FFI-facing engine object and its data types.

use crate::error::CoreError;
use std::sync::{Arc, Mutex, MutexGuard};

/// Engine construction options.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EngineConfig {
    /// Upper bound on candidates returned per update.
    pub max_candidates: u32,
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

/// Metadata of a successfully loaded pack.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PackInfo {
    pub pack_id: String,
    pub language_code: String,
    pub version: String,
}

#[derive(Debug)]
struct State {
    mode: InputMode,
    composing: String,
}

/// The language engine. Internally synchronised, so it is `Send + Sync`.
#[derive(uniffi::Object)]
pub struct KeyboardEngine {
    state: Mutex<State>,
    #[allow(dead_code)] // used by the ranker from C1.7
    config: EngineConfig,
}

const SUPPORTED_LANGUAGES: [&str; 4] = ["am", "ti", "om", "en"];

#[uniffi::export]
impl KeyboardEngine {
    #[uniffi::constructor]
    pub fn new(config: EngineConfig) -> Arc<Self> {
        Arc::new(Self {
            state: Mutex::new(State {
                mode: InputMode::Abc,
                composing: String::new(),
            }),
            config,
        })
    }

    /// Load a signed language pack. Real verification arrives in C1.2; every pack is rejected now.
    pub fn load_pack(&self, bytes: Vec<u8>) -> Result<PackInfo, CoreError> {
        let _ = bytes;
        Err(CoreError::PackRejected {
            reason: "pack loading is not implemented yet".to_string(),
        })
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
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine() -> Arc<KeyboardEngine> {
        KeyboardEngine::new(EngineConfig { max_candidates: 5 })
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
