//! The FFI-facing engine object and its data types.

use crate::composer::{sanitize, DirectFidel, FidelLayout, Layout};
use crate::error::CoreError;
use crate::latin::Orthography;
use crate::lexicon::Lexicon;
use crate::pack::{
    self, KeyRing, PackInfo, PackLimits, PackPlatform, PackRejectReason, ResourceType, TrustedKey,
    VerifiedPack,
};
use crate::ranker::{RankKind, Ranker};
use crate::translit::{PhoneticSession, Profile, ProfileStatus};
use std::collections::{HashMap, HashSet};
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
    /// Arabic script keys (ADR 0034): a direct layout, so typed text is committed as it is, but
    /// normalised to NFC and checked like a Fidel popup choice (no control characters). No
    /// transliteration, no composition, no candidates (no Arabic dictionary is licensed, N10).
    /// Only for `ar`. Added last so the FFI ordinals of the other modes do not change.
    Arabic,
}

/// A key event from the host keyboard.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum KeyEvent {
    /// A character or text chosen by the user: a letter in ABC mode, a long-press popup choice
    /// in DirectFidel (a syllable, punctuation or an Ethiopic numeral). Committed as NFC text.
    Char {
        text: String,
    },
    /// DirectFidel TWO_KEY: a consonant key. `series_id` is `FidelKey::series_id`.
    FidelConsonant {
        series_id: String,
    },
    /// DirectFidel TWO_KEY: a vowel-order key. `order` is `FidelVowelKey::order` (1..=9).
    FidelVowel {
        order: u8,
    },
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
    /// Number of grapheme clusters before the cursor the host should delete first (never UTF-16
    /// code units; one Fidel syllable is one cluster).
    pub delete_before: u32,
    pub candidates: Vec<Candidate>,
}

/// Review status of a transliteration profile. `Draft` profiles have not been approved by the
/// native-speaker panel (gate G0); hosts must not present them as verified (rule N9).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TranslitProfileStatus {
    Draft,
    Approved,
}

/// Identity and review status of the phonetic profile loaded for a language.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TranslitProfileInfo {
    pub profile_id: String,
    pub profile_version: String,
    pub status: TranslitProfileStatus,
}

#[derive(Debug)]
struct State {
    mode: InputMode,
    /// Language of the current mode (as passed to `set_input_mode`).
    language: String,
    /// Bumped by every call that changes the state. Lexicon candidates are computed outside
    /// the lock and only attached if no other call changed the state meanwhile.
    generation: u64,
    /// The committed word before the cursor, for bigram ranking (phonetic mode only).
    context: WordContext,
    composing: String,
    /// Active direct-Fidel session; `Some` exactly when `mode` is `DirectFidel`.
    direct: Option<DirectFidel>,
    /// Active phonetic session; `Some` exactly when `mode` is `PhoneticLatin`.
    phonetic: Option<PhoneticSession>,
    /// Qubee: the strip the host shows for the composed word (index 0 = the word as typed), so
    /// `select_candidate` resolves exactly what the host shows. Empty while nothing is composed.
    word_candidates: Vec<Candidate>,
}

/// A language's user overlay as exported by the host: ("QUD1" dictionary, "QBG1" bigrams).
type UserData = (Vec<u8>, Vec<u8>);

/// Rollback-protection slot: one loaded pack per (language, resource type, platform).
type PackSlot = (String, ResourceType, PackPlatform);

/// Longest word kept as ranking context (the ranker's typed-word bound).
const MAX_CONTEXT_CHARS: usize = 48;

/// The last committed word, kept in memory only for bigram ranking: never logged, never
/// persisted, cleared by `reset`, `set_input_mode` and any deletion.
#[derive(Debug, Default)]
struct WordContext {
    /// The last complete word before the cursor (`None` at the start or after a sentence end).
    previous: Option<String>,
    /// Letters committed since the last word boundary.
    current: String,
}

impl WordContext {
    fn clear(&mut self) {
        self.previous = None;
        self.current.clear();
    }

    /// Follows the text the host commits. Whitespace and the Ethiopic wordspace end a word;
    /// any other punctuation ends the context (a new sentence has no useful previous word).
    fn observe(&mut self, update: &EditorUpdate) {
        if update.delete_before > 0 {
            self.clear();
        }
        for c in update.commit.chars() {
            // An apostrophe inside a Latin word (the Afaan Oromoo hudhaa in `har'a`, English
            // `don't`) belongs to the word; a trailing one (a closing quote) is dropped below.
            if c.is_alphanumeric()
                || is_combining_mark(c)
                || (is_word_apostrophe(c) && !self.current.is_empty())
            {
                if self.current.chars().count() < MAX_CONTEXT_CHARS {
                    self.current.push(c);
                }
            } else if c.is_whitespace() || c == '\u{1361}' {
                let word = self.current.trim_end_matches(is_word_apostrophe);
                if !word.is_empty() {
                    self.previous = Some(word.to_string());
                }
                self.current.clear();
            } else {
                self.clear();
            }
        }
    }
}

/// The apostrophe characters a Latin word may contain (U+0027, U+2019, U+02BC; `crate::latin`).
fn is_word_apostrophe(c: char) -> bool {
    matches!(c, '\'' | '\u{2019}' | '\u{02BC}')
}

fn is_combining_mark(c: char) -> bool {
    matches!(c, '\u{0300}'..='\u{036F}' | '\u{135D}'..='\u{135F}')
}

/// Ethiopic blocks (SD 7.2). Lexicon candidates are only looked up for a composition that is
/// entirely Ge'ez, so protected Latin tokens and pending punctuation get none.
fn is_ethiopic(c: char) -> bool {
    matches!(
        c,
        '\u{1200}'..='\u{139F}'
            | '\u{2D80}'..='\u{2DDF}'
            | '\u{AB00}'..='\u{AB2F}'
            | '\u{1E7E0}'..='\u{1E7FF}'
    )
}

/// Longest word Qubee composes; a longer run of letters goes on as plain typed text.
const MAX_WORD_CHARS: usize = 48;

/// One key in Qubee with word suggestions (Afaan Oromoo, used once the `om` dictionary is
/// loaded). The word being typed is held as composing text so that the host can replace it with
/// a suggestion the user picks; everything is still committed exactly as typed (SD 7.1: no
/// transformation, no auto-capitalisation, nothing applied automatically). Letters compose; an
/// apostrophe (hudhaa) composes right after a letter (`ba'a`, SD 7.3); any other key ends the
/// word and commits it together with that key.
fn qubee_key(s: &mut State, o: &Orthography, event: KeyEvent) -> EditorUpdate {
    fn finish(s: &mut State, tail: &str) -> EditorUpdate {
        let mut commit = std::mem::take(&mut s.composing);
        commit.push_str(tail);
        EditorUpdate {
            commit,
            ..Default::default()
        }
    }
    fn composing(s: &State) -> EditorUpdate {
        EditorUpdate {
            composing: s.composing.clone(),
            ..Default::default()
        }
    }
    match event {
        KeyEvent::Char { text } => {
            let mut chars = text.chars();
            let single = match (chars.next(), chars.next()) {
                (Some(c), None) => Some(c),
                _ => None,
            };
            let after_letter = s.composing.chars().last().is_some_and(|p| o.is_letter(p));
            let composes = single.is_some_and(|c| {
                (o.is_letter(c) || (o.is_apostrophe(c) && after_letter))
                    && s.composing.chars().count() < MAX_WORD_CHARS
            });
            if composes {
                s.composing.push_str(&text);
                composing(s)
            } else {
                finish(s, &text)
            }
        }
        KeyEvent::Space => finish(s, " "),
        KeyEvent::Enter => finish(s, "\n"),
        KeyEvent::Commit => finish(s, ""),
        KeyEvent::Backspace => {
            if s.composing.pop().is_some() {
                composing(s)
            } else {
                EditorUpdate {
                    delete_before: 1,
                    ..Default::default()
                }
            }
        }
        // Two-key Fidel events mean nothing here; the composition stays as it is.
        KeyEvent::FidelConsonant { .. } | KeyEvent::FidelVowel { .. } => composing(s),
    }
}

/// Qubee: commits the strip entry `index` in place of the composed word. Out of range changes
/// nothing (the composition and strip stay).
fn qubee_select(s: &mut State, index: usize) -> EditorUpdate {
    let Some(text) = s.word_candidates.get(index).map(|c| c.text.clone()) else {
        return EditorUpdate {
            composing: s.composing.clone(),
            candidates: s.word_candidates.clone(),
            ..Default::default()
        };
    };
    s.composing.clear();
    s.word_candidates.clear();
    let update = EditorUpdate {
        commit: text,
        ..Default::default()
    };
    s.context.observe(&update);
    update
}

/// Dictionary-backed ranking for one language: built from its `DICTIONARY` pack plus, when
/// loaded, its `FREQUENCY_LIST` (bigram) pack.
struct LangModel {
    /// The dictionary pack the lexicon was parsed from (reused when only the bigrams change).
    /// Not necessarily the active pack: an active dictionary that does not parse leaves the
    /// last good one in service.
    dictionary: Arc<VerifiedPack>,
    /// The bigram pack the ranker was loaded from, if any (same last-good rule).
    bigrams: Option<Arc<VerifiedPack>>,
    lexicon: Arc<Lexicon>,
    ranker: Arc<Ranker>,
}

/// Slots of the strip kept for dictionary completions when the lexicon offers any (ADR 0004 U6).
const RESERVED_COMPLETION_SLOTS: usize = 2;

/// What a key event needs to look up lexicon candidates once the state lock is released.
struct CompletionRequest {
    language: String,
    previous: Option<String>,
    generation: u64,
    /// The composition's transliteration alternatives (after index 0), in C1.4 order.
    homophones: Vec<String>,
}

/// The language engine. Internally synchronised, so it is `Send + Sync`.
#[derive(uniffi::Object)]
pub struct KeyboardEngine {
    state: Mutex<State>,
    /// Verified packs. Separate lock so pack loading never blocks the key path.
    packs: Mutex<HashMap<PackSlot, Arc<VerifiedPack>>>,
    /// Parsed `KEYBOARD_LAYOUT` artifacts by language (lock order: `packs` before `layouts`).
    layouts: Mutex<HashMap<String, Arc<Layout>>>,
    /// Lexicon + ranker per language. Held only to clone or swap an entry, never while
    /// parsing, so the key path never waits for pack loading.
    models: Mutex<HashMap<String, Arc<LangModel>>>,
    /// Serialises `DICTIONARY` / `FREQUENCY_LIST` activation so concurrent loads cannot leave a
    /// model built from a superseded pack. Lock order: `model_build`, `packs`, then `models`.
    /// Never taken on the key path.
    model_build: Mutex<()>,
    /// SHA-256 of verified `DICTIONARY` / `FREQUENCY_LIST` artifacts whose payload does not
    /// parse (reported as `PackInfo::payload_usable == false`).
    unusable_payloads: Mutex<HashSet<String>>,
    /// User overlays imported before the language's dictionary was loaded ("QUD1" dictionary,
    /// "QBG1" bigrams); applied when its model is first built.
    pending_user: Mutex<HashMap<String, UserData>>,
    key_ring: KeyRing,
    pack_limits: PackLimits,
    config: EngineConfig,
    /// The Qubee orthography (`crate::latin`): which characters compose an Afaan Oromoo word.
    qubee: Option<Orthography>,
}

/// Typing languages the engine accepts. `ar` (ADR 0034) has its own passthrough mode and no packs.
const SUPPORTED_LANGUAGES: [&str; 5] = ["am", "ti", "om", "en", "ar"];

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
                language: String::new(),
                generation: 0,
                context: WordContext::default(),
                composing: String::new(),
                direct: None,
                phonetic: None,
                word_candidates: Vec::new(),
            }),
            packs: Mutex::new(HashMap::new()),
            layouts: Mutex::new(HashMap::new()),
            models: Mutex::new(HashMap::new()),
            model_build: Mutex::new(()),
            unusable_payloads: Mutex::new(HashSet::new()),
            pending_user: Mutex::new(HashMap::new()),
            key_ring,
            pack_limits,
            config,
            qubee: Orthography::builtin("om"),
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
    /// - a `KEYBOARD_LAYOUT` artifact must parse and validate as a layout for the manifest's
    ///   language (`InvalidArtifact` otherwise) before the pack is activated;
    /// - a `TRANSLITERATION_RULES` artifact is parsed and validated as a phonetic profile when
    ///   `set_input_mode(.., PhoneticLatin)` or `translit_profile` first needs it (`InvalidState`
    ///   there), because transliteration packs of other kinds share the resource type;
    /// - activating a `DICTIONARY` ("QLX1" lexicon) or `FREQUENCY_LIST` ("QBG1" bigrams) pack
    ///   rebuilds the language's lexicon + ranker (A1.3, ADR 0004), so phonetic compositions get
    ///   lexicon candidates after the transliteration alternatives. Like transliteration packs,
    ///   their payloads are opaque to the container check, so a verified pack whose payload does
    ///   not parse is still activated (it counts for rollback protection and is returned with
    ///   `PackInfo::payload_usable == false`) but never replaces a working model: the language
    ///   keeps its last good lexicon and bigrams. The host should treat such a pack as corrupt;
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
        let resource_type = verified.manifest().resource_type;
        // Parse a dictionary before taking any lock (it can be large).
        let lexicon = (resource_type == ResourceType::Dictionary)
            .then(|| Lexicon::from_bytes(verified.artifact().to_vec()).ok())
            .flatten();
        let payload_usable = match resource_type {
            ResourceType::Dictionary => lexicon.is_some(),
            ResourceType::FrequencyList => crate::ranker::validate_bigrams(verified.artifact()),
            _ => true,
        };
        let builds_model = matches!(
            resource_type,
            ResourceType::Dictionary | ResourceType::FrequencyList
        );
        let _build_guard = builds_model.then(|| self.lock_model_build());
        let layout = if resource_type == ResourceType::KeyboardLayout {
            let parsed =
                Layout::parse(verified.artifact()).map_err(|e| CoreError::PackRejected {
                    reason: PackRejectReason::InvalidArtifact,
                    detail: e.to_string(),
                })?;
            if parsed.language() != verified.manifest().language_code {
                return Err(CoreError::PackRejected {
                    reason: PackRejectReason::InvalidArtifact,
                    detail: "layout language differs from the manifest language".to_string(),
                });
            }
            Some(Arc::new(parsed))
        } else {
            None
        };
        let info = verified.info();
        let m = verified.manifest();
        let slot = (m.language_code.clone(), m.resource_type, m.platform);

        let mut packs = self.lock_packs();
        if let Some(loaded) = packs.get(&slot) {
            let l = loaded.manifest();
            if m.version == l.version && m.sha256 == l.sha256 && m.resource_id == l.resource_id {
                return Ok(self.info_of(loaded)); // identical pack: idempotent no-op
            }
            if m.version <= l.version {
                return Err(CoreError::PackRejected {
                    reason: PackRejectReason::Downgrade,
                    detail: "a pack with the same or a higher version is already loaded"
                        .to_string(),
                });
            }
        }
        if let Some(layout) = layout {
            self.lock_layouts()
                .insert(info.language_code.clone(), layout);
        }
        if !payload_usable {
            self.lock_unusable().insert(m.sha256.clone());
        }
        let activated = Arc::new(verified);
        packs.insert(slot, Arc::clone(&activated));
        drop(packs);
        if builds_model {
            self.rebuild_model(&info.language_code, lexicon.map(|l| (activated, l)));
        }
        Ok(PackInfo {
            payload_usable,
            ..info
        })
    }

    /// Metadata of every loaded pack, sorted by language, resource type and platform.
    pub fn loaded_packs(&self) -> Vec<PackInfo> {
        let packs = self.lock_packs();
        let mut slots: Vec<&PackSlot> = packs.keys().collect();
        slots.sort();
        slots
            .into_iter()
            .filter_map(|s| packs.get(s).map(|p| self.info_of(p)))
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
        // DirectFidel is data-driven: it needs a verified KEYBOARD_LAYOUT for the language.
        let direct = if mode == InputMode::DirectFidel {
            let layout = self.lock_layouts().get(&language_code).cloned();
            let layout = layout.ok_or_else(|| CoreError::InvalidState {
                reason: format!("no keyboard layout is loaded for {language_code}"),
            })?;
            Some(DirectFidel::new(layout))
        } else {
            None
        };
        // PhoneticLatin is data-driven too: it needs a verified TRANSLITERATION_RULES profile.
        let phonetic = if mode == InputMode::PhoneticLatin {
            let profile = self.profile_for(&language_code)?;
            let max = usize::try_from(self.config.max_candidates).unwrap_or(usize::MAX);
            Some(PhoneticSession::new(profile, max))
        } else {
            None
        };
        let mut s = self.lock();
        s.mode = mode;
        s.language = language_code;
        s.generation = s.generation.wrapping_add(1);
        s.context.clear();
        s.composing.clear();
        s.word_candidates.clear();
        s.direct = direct;
        s.phonetic = phonetic;
        Ok(())
    }

    /// Identity and review status of the phonetic profile loaded for `language_code`, so the
    /// host can refuse to present a `Draft` profile as verified (N9). `InvalidState` when no
    /// `TRANSLITERATION_RULES` pack is loaded for it.
    pub fn translit_profile(
        &self,
        language_code: String,
    ) -> Result<TranslitProfileInfo, CoreError> {
        if !SUPPORTED_LANGUAGES.contains(&language_code.as_str()) {
            return Err(CoreError::UnsupportedLanguage {
                code: language_code,
            });
        }
        let p = self.profile_for(&language_code)?;
        Ok(TranslitProfileInfo {
            profile_id: p.profile_id().to_string(),
            profile_version: p.profile_version().to_string(),
            status: match p.status() {
                ProfileStatus::Draft => TranslitProfileStatus::Draft,
                ProfileStatus::Approved => TranslitProfileStatus::Approved,
            },
        })
    }

    /// The direct-Fidel layout loaded for `language_code`, for the host to render.
    /// `InvalidState` when no `KEYBOARD_LAYOUT` pack is loaded for it.
    pub fn fidel_layout(&self, language_code: String) -> Result<FidelLayout, CoreError> {
        if !SUPPORTED_LANGUAGES.contains(&language_code.as_str()) {
            return Err(CoreError::UnsupportedLanguage {
                code: language_code,
            });
        }
        let layout = self.lock_layouts().get(&language_code).cloned();
        layout
            .map(|l| l.to_ffi())
            .ok_or_else(|| CoreError::InvalidState {
                reason: format!("no keyboard layout is loaded for {language_code}"),
            })
    }

    pub fn on_key(&self, event: KeyEvent) -> EditorUpdate {
        let mut s = self.lock();
        s.generation = s.generation.wrapping_add(1);
        if s.mode == InputMode::DirectFidel {
            return s
                .direct
                .as_mut()
                .map(|d| d.on_key(event))
                .unwrap_or_default();
        }
        if s.mode == InputMode::PhoneticLatin {
            let update = s
                .phonetic
                .as_mut()
                .map(|p| p.on_key(event))
                .unwrap_or_default();
            let request = Self::phonetic_followup(&mut s, &update);
            drop(s);
            return self.with_lexicon_candidates(update, request);
        }
        if s.mode == InputMode::Qubee {
            // A word starts composing only when the language has a dictionary to suggest from
            // (lock order: state, then models); without one Qubee stays plain passthrough.
            let suggests = !s.composing.is_empty() || self.lock_models().contains_key(&s.language);
            if suggests {
                if let Some(o) = &self.qubee {
                    let mut update = qubee_key(&mut s, o, event);
                    let request = Self::word_followup(&mut s, &mut update);
                    drop(s);
                    return self.with_word_candidates(update, request);
                }
            }
        }
        // Abc, English and Qubee without a dictionary are plain passthrough (SD 7.1): typed text is committed exactly
        // as received, never transformed (no auto-capitalisation, no apostrophe rewriting). The
        // Qubee apostrophe (hudhaa) is an ordinary character here; word segmentation around it
        // lives in `crate::latin`. After commit, Backspace deletes one grapheme cluster, so a
        // digraph such as `dh` takes two presses (the host's cursor view).
        // Arabic (ADR 0034) is passthrough too, but its text is sanitised and NFC like every
        // script-native mode: a host that sends alef + combining hamza gets the precomposed
        // letter, and control characters are never committed. Text is in logical order; the
        // editor's bidi algorithm shows it right to left.
        debug_assert!(matches!(
            s.mode,
            InputMode::Abc | InputMode::English | InputMode::Qubee | InputMode::Arabic
        ));
        match event {
            // Two-key Fidel events only mean something in DirectFidel.
            KeyEvent::FidelConsonant { .. } | KeyEvent::FidelVowel { .. } => {
                EditorUpdate::default()
            }
            KeyEvent::Char { text } if s.mode == InputMode::Arabic => EditorUpdate {
                commit: sanitize(&text).unwrap_or_default(),
                ..Default::default()
            },
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

    /// Select a candidate by index from the last update's `candidates`: the composing text is
    /// replaced by it and committed (index 0 is the default composition; transliteration
    /// alternatives and lexicon candidates follow; in Qubee index 0 is the word as typed, then
    /// dictionary words). Other modes offer no candidates, and an index that is out of range
    /// changes nothing.
    pub fn select_candidate(&self, index: u32) -> EditorUpdate {
        let mut s = self.lock();
        s.generation = s.generation.wrapping_add(1);
        if s.mode == InputMode::Qubee {
            return qubee_select(&mut s, usize::try_from(index).unwrap_or(usize::MAX));
        }
        let Some(p) = s.phonetic.as_mut() else {
            return EditorUpdate::default();
        };
        let update = p.select(usize::try_from(index).unwrap_or(usize::MAX));
        let request = Self::phonetic_followup(&mut s, &update);
        drop(s);
        self.with_lexicon_candidates(update, request)
    }

    pub fn reset(&self) -> EditorUpdate {
        let mut s = self.lock();
        s.generation = s.generation.wrapping_add(1);
        s.context.clear();
        s.composing.clear();
        s.word_candidates.clear();
        if let Some(d) = s.direct.as_mut() {
            d.reset();
        }
        if let Some(p) = s.phonetic.as_mut() {
            p.reset();
        }
        EditorUpdate::default()
    }

    /// Replaces the user overlay of `language_code` with the host's stored copy: `dictionary` is
    /// `Lexicon::export_user_dictionary` output, `bigrams` is `Ranker::export_user_bigrams`
    /// output (either may be empty to mean "none"). Both are validated first; on error nothing
    /// changes. If the language has no dictionary yet the overlay is kept and applied when its
    /// model is built, and a later pack load never drops it (A1.8).
    pub fn import_user_data(
        &self,
        language_code: String,
        dictionary: Vec<u8>,
        bigrams: Vec<u8>,
    ) -> Result<(), CoreError> {
        Self::check_language(&language_code)?;
        // Validate on throw-away objects so a bad payload changes nothing.
        let probe = Lexicon::new_empty();
        if !dictionary.is_empty() {
            probe
                .import_user_dictionary(dictionary.clone())
                .map_err(|_| Self::bad_user_data())?;
        }
        if !bigrams.is_empty() {
            Ranker::new(probe)
                .import_user_bigrams(bigrams.clone())
                .map_err(|_| Self::bad_user_data())?;
        }
        let _build = self.lock_model_build();
        let model = self.lock_models().get(&language_code).cloned();
        match model {
            Some(m) => Self::apply_user_data(&m, &dictionary, &bigrams),
            None => {
                self.lock_pending_user()
                    .insert(language_code, (dictionary, bigrams));
            }
        }
        Ok(())
    }

    /// Adds `word` to the user dictionary of `language_code` (explicit "add to dictionary").
    /// False if refused (invalid word, full, or no dictionary loaded for the language).
    pub fn add_user_word(&self, language_code: String, word: String) -> bool {
        self.lock_models()
            .get(&language_code)
            .is_some_and(|m| m.lexicon.add_user_word(word))
    }

    /// Learns one committed `word` (counts a use) and, when `previous` is the committed word
    /// before it, the bigram. The host decides *whether* to learn (never in password, no-learning
    /// or incognito fields). False if refused or no dictionary is loaded for the language.
    pub fn learn_user_word(
        &self,
        language_code: String,
        word: String,
        previous: Option<String>,
    ) -> bool {
        let Some(m) = self.lock_models().get(&language_code).cloned() else {
            return false;
        };
        if !m.lexicon.learn_word(word.clone()) {
            return false;
        }
        if let Some(p) = previous {
            m.ranker.learn_bigram(p, word);
        }
        true
    }

    /// Forgets a user word; base-lexicon words stay visible. True if it was in the user
    /// dictionary.
    pub fn forget_user_word(&self, language_code: String, word: String) -> bool {
        self.lock_models()
            .get(&language_code)
            .is_some_and(|m| m.lexicon.forget_user_word(word))
    }

    /// Forgets everything the user added, learned or removed, and learned word pairs, for the
    /// language ("clear learned words").
    pub fn clear_user_data(&self, language_code: String) {
        let _build = self.lock_model_build();
        self.lock_pending_user().remove(&language_code);
        if let Some(m) = self.lock_models().get(&language_code) {
            m.lexicon.clear_user_dictionary();
            m.ranker.clear_user_bigrams();
        }
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
        InputMode::Arabic => language_code == "ar",
    }
}

/// Modes implemented in this build. DirectFidel (C1.3) and PhoneticLatin (C1.4) additionally
/// need a loaded layout / profile, checked in `set_input_mode`. Qubee and English (C1.5) and
/// Arabic (ADR 0034) are passthrough and need no data.
fn mode_available(mode: InputMode) -> bool {
    matches!(
        mode,
        InputMode::Abc
            | InputMode::English
            | InputMode::Qubee
            | InputMode::Arabic
            | InputMode::DirectFidel
            | InputMode::PhoneticLatin
    )
}

impl KeyboardEngine {
    fn lock(&self) -> MutexGuard<'_, State> {
        // A poisoned lock only means another thread panicked; the state is still usable.
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn lock_layouts(&self) -> MutexGuard<'_, HashMap<String, Arc<Layout>>> {
        // Entries are only inserted after full validation, so a poisoned map is still valid.
        self.layouts.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn lock_models(&self) -> MutexGuard<'_, HashMap<String, Arc<LangModel>>> {
        // Entries are only inserted fully built, so a poisoned map is still valid.
        self.models.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn lock_unusable(&self) -> MutexGuard<'_, HashSet<String>> {
        self.unusable_payloads
            .lock()
            .unwrap_or_else(|e| e.into_inner())
    }

    /// Pack metadata including whether its payload is known to be unusable.
    fn info_of(&self, p: &VerifiedPack) -> PackInfo {
        let usable = !self.lock_unusable().contains(&p.manifest().sha256);
        PackInfo {
            payload_usable: usable,
            ..p.info()
        }
    }

    fn check_language(code: &str) -> Result<(), CoreError> {
        if SUPPORTED_LANGUAGES.contains(&code) {
            Ok(())
        } else {
            Err(CoreError::UnsupportedLanguage {
                code: code.to_string(),
            })
        }
    }

    fn bad_user_data() -> CoreError {
        CoreError::InvalidState {
            reason: "malformed user dictionary data".to_string(),
        }
    }

    /// Payloads were validated by `import_user_data`, so the imports cannot fail.
    fn apply_user_data(m: &LangModel, dictionary: &[u8], bigrams: &[u8]) {
        if dictionary.is_empty() {
            m.lexicon.clear_user_dictionary();
        } else {
            let _ = m.lexicon.import_user_dictionary(dictionary.to_vec());
        }
        if bigrams.is_empty() {
            m.ranker.clear_user_bigrams();
        } else {
            let _ = m.ranker.import_user_bigrams(bigrams.to_vec());
        }
    }

    fn lock_pending_user(&self) -> MutexGuard<'_, HashMap<String, UserData>> {
        self.pending_user.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn lock_model_build(&self) -> MutexGuard<'_, ()> {
        self.model_build.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The active pack of `resource_type` for `language_code` (Shared, then Android, then iOS).
    fn find_pack(
        packs: &HashMap<PackSlot, Arc<VerifiedPack>>,
        language_code: &str,
        resource_type: ResourceType,
    ) -> Option<Arc<VerifiedPack>> {
        [
            PackPlatform::Shared,
            PackPlatform::Android,
            PackPlatform::Ios,
        ]
        .into_iter()
        .find_map(|pl| packs.get(&(language_code.to_string(), resource_type, pl)))
        .cloned()
    }

    /// Rebuilds the lexicon + ranker of `language_code` from its active `DICTIONARY` and
    /// `FREQUENCY_LIST` packs. Called with `model_build` held. `fresh` is the dictionary pack
    /// just activated with its already parsed lexicon, so nothing is parsed twice.
    fn rebuild_model(&self, language_code: &str, fresh: Option<(Arc<VerifiedPack>, Arc<Lexicon>)>) {
        let (dictionary, bigrams) = {
            let packs = self.lock_packs();
            (
                Self::find_pack(&packs, language_code, ResourceType::Dictionary),
                Self::find_pack(&packs, language_code, ResourceType::FrequencyList),
            )
        };
        // A ranker needs a lexicon; bigrams alone wait for the dictionary.
        let Some(dictionary) = dictionary else {
            return;
        };
        let current = self.lock_models().get(language_code).cloned();
        let parsed = match (fresh, &current) {
            (Some((p, l)), _) if Arc::ptr_eq(&p, &dictionary) => Some(l),
            (_, Some(m)) if Arc::ptr_eq(&m.dictionary, &dictionary) => Some(Arc::clone(&m.lexicon)),
            _ => Lexicon::from_bytes(dictionary.artifact().to_vec()).ok(),
        };
        // An active dictionary that does not parse never drops the working model.
        let (dictionary, lexicon) = match (parsed, &current) {
            (Some(l), _) => (dictionary, l),
            (None, Some(m)) => (Arc::clone(&m.dictionary), Arc::clone(&m.lexicon)),
            (None, None) => return,
        };
        let ranker = Ranker::new(Arc::clone(&lexicon));
        // Unusable bigrams fall back to the last good ones (or none: unigram frequencies only).
        let bigrams = bigrams
            .filter(|b| ranker.load_bigrams(b.artifact().to_vec()).is_ok())
            .or_else(|| {
                let last = current.as_ref()?.bigrams.clone()?;
                ranker.load_bigrams(last.artifact().to_vec()).ok()?;
                Some(last)
            });
        // The user overlay outlives pack changes: take what the host imported before any
        // dictionary existed, or carry it over from the model being replaced.
        let pending = self.lock_pending_user().remove(language_code);
        if let Some((d, b)) = pending {
            if d.is_empty() {
                lexicon.clear_user_dictionary();
            } else {
                let _ = lexicon.import_user_dictionary(d);
            }
            if !b.is_empty() {
                let _ = ranker.import_user_bigrams(b);
            }
        } else if let Some(old) = &current {
            // A reused lexicon object keeps its overlay already.
            if !Arc::ptr_eq(&old.lexicon, &lexicon) {
                let _ = lexicon.import_user_dictionary(old.lexicon.export_user_dictionary());
            }
            let _ = ranker.import_user_bigrams(old.ranker.export_user_bigrams());
        }
        self.lock_models().insert(
            language_code.to_string(),
            Arc::new(LangModel {
                dictionary,
                bigrams,
                lexicon,
                ranker,
            }),
        );
    }

    /// After a phonetic update (state lock held): follows committed words for ranking context
    /// and says whether lexicon candidates should be looked up for the new composition.
    fn phonetic_followup(s: &mut State, update: &EditorUpdate) -> Option<CompletionRequest> {
        s.context.observe(update);
        let wants = !update.composing.is_empty() && update.composing.chars().all(is_ethiopic);
        wants.then(|| CompletionRequest {
            language: s.language.clone(),
            previous: s.context.previous.clone(),
            generation: s.generation,
            homophones: s
                .phonetic
                .as_ref()
                .map(|p| p.alternatives())
                .unwrap_or_default(),
        })
    }

    /// After a Qubee update (state lock held): follows committed words for ranking context, puts
    /// the word as typed at index 0 of the strip and says whether dictionary words should be
    /// looked up for it.
    fn word_followup(s: &mut State, update: &mut EditorUpdate) -> Option<CompletionRequest> {
        s.context.observe(update);
        s.word_candidates.clear();
        if update.composing.is_empty() {
            return None;
        }
        s.word_candidates.push(Candidate {
            text: update.composing.clone(),
            kind: CandidateKind::Transliteration,
        });
        update.candidates = s.word_candidates.clone();
        Some(CompletionRequest {
            language: s.language.clone(),
            previous: s.context.previous.clone(),
            generation: s.generation,
            homophones: Vec::new(),
        })
    }

    /// Qubee strip: the word as typed (index 0, kind `Transliteration`: the identity "rule"),
    /// then completions and one-edit corrections from the language's lexicon + user overlay, in
    /// the ranker's order, up to `max_candidates`. The ranker keeps the typed word's
    /// capitalisation (`Akk` offers `Akkam`). Nothing is applied automatically (harm guard).
    /// A word ending in an apostrophe (a closing quote, or a hudhaa still waiting for its vowel)
    /// is not looked up. Ranking runs outside the state lock; if another call changed the state
    /// meanwhile the strip stays the typed word alone.
    fn with_word_candidates(
        &self,
        mut update: EditorUpdate,
        request: Option<CompletionRequest>,
    ) -> EditorUpdate {
        let Some(req) = request else {
            return update;
        };
        let max = usize::try_from(self.config.max_candidates).unwrap_or(usize::MAX);
        let typed = update.composing.clone();
        if max <= 1 || typed.ends_with(is_word_apostrophe) {
            return update;
        }
        let Some(model) = self.lock_models().get(&req.language).cloned() else {
            return update;
        };
        let key = self
            .qubee
            .as_ref()
            .map_or_else(|| typed.clone(), |o| o.fold_apostrophes(&typed));
        let want = self.config.max_candidates.saturating_add(1);
        let mut list = update.candidates.clone();
        for c in model.ranker.rank(key, req.previous, want) {
            let kind = match c.kind {
                RankKind::Exact | RankKind::Variant | RankKind::Completion => {
                    CandidateKind::Completion
                }
                RankKind::Correction => CandidateKind::Correction,
                RankKind::NextWord | RankKind::Frequent => continue,
            };
            if list.len() >= max {
                break;
            }
            if !c.text.is_empty() && !list.iter().any(|x| x.text == c.text) {
                list.push(Candidate { text: c.text, kind });
            }
        }
        let mut s = self.lock();
        if s.generation != req.generation || s.composing != typed {
            return update;
        }
        s.word_candidates.clone_from(&list);
        drop(s);
        update.candidates = list;
        update
    }

    /// Merges lexicon candidates into the strip (A1.3 merge policy, ADR 0004 incl. the U6
    /// amendment). Index 0 stays the literal rule output. The transliteration alternatives
    /// follow sorted by dictionary frequency (known words first, most frequent first; unknown
    /// ones keep their C1.4 order after them; ties keep C1.4 order). At least
    /// [`RESERVED_COMPLETION_SLOTS`] slots go to completions and one-edit corrections from the
    /// ranker whenever it offers any; the rest of the strip is filled in order, up to
    /// `max_candidates`. Nothing is applied automatically (harm guard): corrections are only
    /// offered. Ranking runs outside the state lock; if another call changed the state in the
    /// meantime the candidates are dropped, so `select_candidate` always matches the strip.
    /// Without a dictionary for the language the C1.4 strip is returned unchanged.
    fn with_lexicon_candidates(
        &self,
        mut update: EditorUpdate,
        request: Option<CompletionRequest>,
    ) -> EditorUpdate {
        let Some(req) = request else {
            return update;
        };
        let max = usize::try_from(self.config.max_candidates).unwrap_or(usize::MAX);
        if update.candidates.is_empty() || max <= 1 {
            return update;
        }
        let Some(model) = self.lock_models().get(&req.language).cloned() else {
            return update;
        };
        // Ask for enough to survive removing the homophones that the ranker may also return.
        let want = self
            .config
            .max_candidates
            .saturating_add(u32::try_from(req.homophones.len()).unwrap_or(u32::MAX));
        let ranked = model
            .ranker
            .rank(update.composing.clone(), req.previous, want);
        let primary = update.candidates[0].text.clone();
        // Stable sort: known words by descending frequency, unknown ones last, C1.4 order kept
        // for ties and among the unknown.
        let mut keyed: Vec<(Option<u32>, String)> = req
            .homophones
            .into_iter()
            .map(|t| (model.lexicon.word_score(t.clone()), t))
            .collect();
        keyed.sort_by(|a, b| match (a.0, b.0) {
            (Some(x), Some(y)) => y.cmp(&x),
            (Some(_), None) => std::cmp::Ordering::Less,
            (None, Some(_)) => std::cmp::Ordering::Greater,
            (None, None) => std::cmp::Ordering::Equal,
        });
        let homophones: Vec<String> = keyed.into_iter().map(|(_, t)| t).collect();
        let mut completions: Vec<(String, CandidateKind)> = Vec::new();
        for c in ranked {
            let kind = match c.kind {
                RankKind::Exact | RankKind::Variant | RankKind::Completion => {
                    CandidateKind::Completion
                }
                RankKind::Correction => CandidateKind::Correction,
                RankKind::NextWord | RankKind::Frequent => continue,
            };
            let text = c.text;
            if !text.is_empty()
                && text != primary
                && !homophones.contains(&text)
                && !completions.iter().any(|(t, _)| *t == text)
            {
                completions.push((text, kind));
            }
        }
        let reserved = completions.len().min(RESERVED_COMPLETION_SLOTS);
        let hom_take = homophones.len().min((max - 1).saturating_sub(reserved));
        let comp_take = completions.len().min(max - 1 - hom_take);
        let mut merged = vec![(primary, CandidateKind::Transliteration)];
        merged.extend(
            homophones
                .into_iter()
                .take(hom_take)
                .map(|t| (t, CandidateKind::Transliteration)),
        );
        merged.extend(completions.into_iter().take(comp_take));
        let mut s = self.lock();
        if s.generation != req.generation {
            return update;
        }
        let Some(p) = s.phonetic.as_mut() else {
            return update;
        };
        p.set_candidates(merged.iter().map(|(t, _)| t.clone()).collect());
        drop(s);
        update.candidates = merged
            .into_iter()
            .map(|(text, kind)| Candidate { text, kind })
            .collect();
        update
    }

    /// Parses the `TRANSLITERATION_RULES` artifact loaded for `language_code`. The pack was
    /// verified at load time; the profile itself is validated here, when it is first needed, so
    /// an invalid profile can only fail phonetic mode and never other pack types.
    fn profile_for(&self, language_code: &str) -> Result<Arc<Profile>, CoreError> {
        let pack = Self::find_pack(
            &self.lock_packs(),
            language_code,
            ResourceType::TransliterationRules,
        );
        let pack = pack.ok_or_else(|| CoreError::InvalidState {
            reason: format!("no transliteration profile is loaded for {language_code}"),
        })?;
        let profile = Profile::parse(pack.artifact()).map_err(|e| CoreError::InvalidState {
            reason: e.to_string(),
        })?;
        if profile.language() != language_code {
            return Err(CoreError::InvalidState {
                reason: "profile language differs from the pack language".to_string(),
            });
        }
        Ok(Arc::new(profile))
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
            ("en", InputMode::Arabic),
            ("ar", InputMode::English),
            ("ar", InputMode::Qubee),
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
        e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
    }

    #[test]
    fn arabic_mode_commits_nfc() {
        let e = engine();
        e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
        assert_eq!(
            e.on_key(KeyEvent::Char {
                text: "\u{0627}\u{0654}".into()
            })
            .commit,
            "\u{0623}"
        );
        assert_eq!(e.on_key(KeyEvent::Backspace).delete_before, 1);
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

    fn commit(text: &str) -> EditorUpdate {
        EditorUpdate {
            commit: text.into(),
            ..Default::default()
        }
    }

    #[test]
    fn word_context_follows_commits() {
        let mut c = WordContext::default();
        c.observe(&commit("ሰላም"));
        assert_eq!(c.previous, None);
        c.observe(&commit(" "));
        assert_eq!(c.previous.as_deref(), Some("ሰላም"));
        // The Ethiopic wordspace is a word boundary too.
        c.observe(&commit("ነው፡"));
        assert_eq!(c.previous.as_deref(), Some("ነው"));
        // Sentence punctuation ends the context; deletions forget it.
        c.observe(&commit("ደህና። "));
        assert_eq!(c.previous, None);
        c.observe(&commit("ሰላም "));
        c.observe(&EditorUpdate {
            delete_before: 1,
            ..Default::default()
        });
        assert_eq!(c.previous, None);
        assert!(c.current.is_empty());
    }

    #[test]
    fn word_context_keeps_the_hudhaa_inside_a_word() {
        let mut c = WordContext::default();
        // Afaan Oromoo `har'a` is one word, not `har` + `a`.
        c.observe(&commit("har'a "));
        assert_eq!(c.previous.as_deref(), Some("har'a"));
        c.observe(&commit("baay\u{2019}ee "));
        assert_eq!(c.previous.as_deref(), Some("baay\u{2019}ee"));
        // A closing quote is not part of the word; an opening one ends the context.
        c.observe(&commit("dhugaa' "));
        assert_eq!(c.previous.as_deref(), Some("dhugaa"));
        c.observe(&commit("'jedhe"));
        assert_eq!(c.previous, None);
        assert_eq!(c.current, "jedhe");
    }

    #[test]
    fn word_context_is_bounded() {
        let mut c = WordContext::default();
        c.observe(&commit(&"ሰ".repeat(500)));
        assert_eq!(c.current.chars().count(), MAX_CONTEXT_CHARS);
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
