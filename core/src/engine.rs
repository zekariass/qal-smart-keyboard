//! The FFI-facing engine object and its data types.

use crate::composer::{sanitize, DirectFidel, FidelLayout, Layout};
use crate::error::CoreError;
use crate::latin::Orthography;
use crate::lexicon::Lexicon;
use crate::pack::{
    self, KeyRing, PackInfo, PackLimits, PackPlatform, PackRejectReason, ResourceType, TrustedKey,
    VerifiedPack,
};
use crate::predict::{
    attaches_to_word, ends_sentence_part, graphemes, is_word_apostrophe, PredictionOptions,
    WordContext, WordScript,
};
use crate::ranker::{AutocorrectAction, RankKind, Ranker};
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
    /// Index in `candidates` of the correction a Space (or sentence punctuation) will apply
    /// now (ADR 0039: English and Afaan Oromoo with autocorrection on). The host marks it on the
    /// strip; `None` when the word will be committed as typed.
    pub autocorrect_index: Option<u32>,
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
    /// The strip the engine shows outside a phonetic composition (word modes: index 0 = the
    /// word as typed; next-word predictions; Fidel completions), so `select_candidate` resolves
    /// exactly what the host shows. What it holds is `strip`.
    word_candidates: Vec<Candidate>,
    strip: Strip,
    /// The host's prediction choices for the current field (ADR 0039).
    options: PredictionOptions,
    /// After an autocorrection: what an immediate Backspace restores.
    undo: Option<Undo>,
    /// The last commit ended with the space added behind a picked suggestion: a Space now adds
    /// none, and attaching punctuation replaces it.
    auto_space: bool,
    /// A word the user restored after an autocorrection: it is not corrected again.
    keep_typed: Option<String>,
    /// The composed word began at a word boundary (eligible for autocorrection).
    word_start_ok: bool,
    /// The text after the cursor starts with whitespace (`set_cursor_context`): a picked
    /// suggestion then gets no space of its own.
    space_after: bool,
}

/// What `State::word_candidates` holds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Strip {
    None,
    /// Word modes: the composed word (index 0) and dictionary words for it.
    Word,
    /// Next-word predictions while nothing composes.
    Predictions,
    /// Direct Fidel: completions of the word typed so far (committed letters plus the pending
    /// syllable).
    Fidel,
}

/// An applied autocorrection, undone by a Backspace right after it.
#[derive(Debug, Clone)]
struct Undo {
    typed: String,
    /// Grapheme clusters to delete: the correction plus what was committed after it.
    remove: u32,
    /// The word context before the correction was committed (restored with the typed word).
    previous: Option<String>,
    sentence_start: bool,
    last: Option<char>,
}

impl State {
    /// Clears every prediction state (mode switch, reset, cursor move).
    fn clear_prediction(&mut self) {
        self.context.clear();
        self.composing.clear();
        self.word_candidates.clear();
        self.strip = Strip::None;
        self.undo = None;
        self.auto_space = false;
        self.keep_typed = None;
        self.word_start_ok = false;
        self.space_after = false;
    }

    /// Nothing composes in any mode.
    fn idle(&self) -> bool {
        self.composing.is_empty()
            && self.phonetic.as_ref().is_none_or(PhoneticSession::is_idle)
            && self.direct.as_ref().is_none_or(DirectFidel::is_idle)
    }
}

/// A language's user overlay as exported by the host: ("QUD1" dictionary, "QBG1" bigrams).
type UserData = (Vec<u8>, Vec<u8>);

/// Rollback-protection slot: one loaded pack per (language, resource type, platform).
type PackSlot = (String, ResourceType, PackPlatform);

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

/// Longest word a word mode composes; a longer run of letters goes on as plain typed text.
const MAX_WORD_CHARS: usize = 48;

/// One key in a word mode (ADR 0039): Qubee (Afaan Oromoo) and English once their dictionary is
/// loaded, Arabic once the user has learned or added Arabic words. The word being typed is held as
/// composing text so that the host can replace it with a suggestion the user picks; it is
/// committed exactly as typed (SD 7.1: no transformation, no auto-capitalisation) unless the
/// caller applied an autocorrection to `s.composing` first. Letters compose; an apostrophe
/// (hudhaa, `don't`) composes right after a letter (`ba'a`, SD 7.3); a key of several letters
/// (Arabic lam-alef) composes as a whole; any other key ends the word and commits it together
/// with that key. Arabic text is sanitised and NFC like the Arabic passthrough (ADR 0034).
fn word_key(s: &mut State, script: &WordScript<'_>, event: KeyEvent) -> EditorUpdate {
    fn finish(s: &mut State, tail: &str) -> EditorUpdate {
        let mut commit = std::mem::take(&mut s.composing);
        commit.push_str(tail);
        EditorUpdate {
            commit: crate::composer::nfc_commit(commit),
            ..Default::default()
        }
    }
    fn composing(s: &State) -> EditorUpdate {
        EditorUpdate {
            composing: s.composing.clone(),
            ..Default::default()
        }
    }
    let arabic = matches!(script, WordScript::Arabic);
    match event {
        KeyEvent::Char { text } => {
            let text = if arabic {
                match sanitize(&text) {
                    Some(t) => t,
                    None => return composing(s),
                }
            } else {
                text
            };
            let mut after_letter = s
                .composing
                .chars()
                .last()
                .is_some_and(|p| script.is_letter(p));
            let mut composes = !text.is_empty()
                && s.composing.chars().count() + text.chars().count() <= MAX_WORD_CHARS;
            for c in text.chars() {
                composes &= script.is_letter(c) || (script.is_apostrophe(c) && after_letter);
                after_letter = script.is_letter(c);
            }
            if composes {
                s.composing.push_str(&text);
                if arabic {
                    s.composing = crate::composer::nfc_commit(std::mem::take(&mut s.composing));
                }
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

/// Abc, English and Qubee without a dictionary, and Arabic without learned words, are plain
/// passthrough (SD 7.1): typed text is committed exactly as received, never transformed (no
/// auto-capitalisation, no apostrophe rewriting). After commit, Backspace deletes one grapheme
/// cluster, so a digraph such as `dh` takes two presses (the host's cursor view). Arabic
/// (ADR 0034) is sanitised and NFC like every script-native mode: a host that sends alef +
/// combining hamza gets the precomposed letter, and control characters are never committed. Text
/// is in logical order; the editor's bidi algorithm shows it right to left.
fn passthrough(s: &mut State, event: KeyEvent) -> EditorUpdate {
    let commit = |text: String| EditorUpdate {
        commit: text,
        ..Default::default()
    };
    match event {
        // Two-key Fidel events only mean something in DirectFidel.
        KeyEvent::FidelConsonant { .. } | KeyEvent::FidelVowel { .. } | KeyEvent::Commit => {
            EditorUpdate::default()
        }
        KeyEvent::Char { text } if s.mode == InputMode::Arabic => {
            commit(sanitize(&text).unwrap_or_default())
        }
        KeyEvent::Char { text } => commit(text),
        KeyEvent::Space => commit(" ".to_string()),
        KeyEvent::Enter => commit("\n".to_string()),
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
    }
}

/// The update ended a word: nothing composes and the last committed character is whitespace or
/// the Ethiopic wordspace.
fn ends_at_word_boundary(update: &EditorUpdate) -> bool {
    update.composing.is_empty()
        && update
            .commit
            .chars()
            .last()
            .is_some_and(|c| c.is_whitespace() || c == '\u{1361}')
}

/// Dictionary-backed ranking for one language: built from its `DICTIONARY` pack plus, when
/// loaded, its `FREQUENCY_LIST` (bigram) pack. Arabic (no licensed dictionary, N10) gets a model
/// with an empty base lexicon holding only the user's own words and word pairs (ADR 0039).
struct LangModel {
    /// The dictionary pack the lexicon was parsed from (reused when only the bigrams change);
    /// `None` for a user-only model. Not necessarily the active pack: an active dictionary that
    /// does not parse leaves the last good one in service.
    dictionary: Option<Arc<VerifiedPack>>,
    /// The bigram pack the ranker was loaded from, if any (same last-good rule).
    bigrams: Option<Arc<VerifiedPack>>,
    lexicon: Arc<Lexicon>,
    ranker: Arc<Ranker>,
}

/// Languages without a shippable dictionary whose model holds only the user's words (N10).
const USER_ONLY_LANGUAGES: [&str; 1] = ["ar"];

/// Latin-script languages whose lexicons guard each other against autocorrection (a word valid
/// in one is never "corrected" while typing the other).
const LATIN_LANGUAGES: [&str; 2] = ["en", "om"];

/// Slots of the strip kept for dictionary completions when the lexicon offers any (ADR 0004 U6).
const RESERVED_COMPLETION_SLOTS: usize = 2;

/// What an update needs to look up candidates once the state lock is released.
struct CompletionRequest {
    language: String,
    previous: Option<String>,
    generation: u64,
    kind: RequestKind,
}

enum RequestKind {
    /// Phonetic composition: the transliteration alternatives (after index 0), in C1.4 order.
    Phonetic { homophones: Vec<String> },
    /// Word modes: dictionary words for the composed word; `autocorrect` when the word may be
    /// corrected (the strip marks the correction).
    Word {
        script: WordKind,
        autocorrect: bool,
        sentence_start: bool,
    },
    /// Direct Fidel: completions of the word typed so far.
    Fidel { key: String },
    /// Next-word predictions after `previous`.
    Predict,
}

/// Which word script a request uses (the orthography lives in the engine).
#[derive(Clone, Copy, PartialEq, Eq)]
enum WordKind {
    Oromo,
    English,
    Arabic,
}

/// Which follow-up an update gets.
enum Follow {
    None,
    Phonetic,
    Word(WordKind),
    Fidel,
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
    /// The English orthography: which characters compose an English word.
    english: Option<Orthography>,
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
                strip: Strip::None,
                options: PredictionOptions::default(),
                undo: None,
                auto_space: false,
                keep_typed: None,
                word_start_ok: false,
                space_after: false,
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
            english: Orthography::builtin("en"),
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
        s.clear_prediction();
        s.direct = direct;
        s.phonetic = phonetic;
        Ok(())
    }

    /// The host's prediction choices for the current field (ADR 0039). Kept across mode
    /// switches; set again for every field. Turning an option off takes effect at the next key.
    pub fn set_prediction_options(&self, options: PredictionOptions) {
        let mut s = self.lock();
        s.generation = s.generation.wrapping_add(1);
        s.options = options;
        if !options.autocorrect {
            s.undo = None;
        }
    }

    /// Tells the engine the text around the cursor after it moved for a reason the engine did
    /// not see (field start, a tap in the text; call `reset` first): `before` is the text before
    /// the cursor (its tail is enough; at most the last 96 characters are read), `after` the text
    /// after it (only its first character is read). The engine rebuilds its word context from
    /// it (previous word, sentence start, Fidel word prefix) and returns the strip for the new
    /// position: next-word predictions after a word, Fidel completions inside an Ethiopic word.
    /// Ignored while something composes. Nothing is kept beyond the context (N2).
    pub fn set_cursor_context(&self, before: String, after: String) -> EditorUpdate {
        let mut s = self.lock();
        s.generation = s.generation.wrapping_add(1);
        if !s.idle() {
            return EditorUpdate::default();
        }
        s.context = WordContext::from_text(&before);
        s.space_after = after.chars().next().is_some_and(char::is_whitespace);
        s.undo = None;
        s.auto_space = false;
        s.keep_typed = None;
        s.word_candidates.clear();
        s.strip = Strip::None;
        let follow = match s.mode {
            InputMode::DirectFidel => Follow::Fidel,
            InputMode::PhoneticLatin => Follow::Phonetic,
            InputMode::Qubee => Follow::Word(WordKind::Oromo),
            InputMode::English => Follow::Word(WordKind::English),
            InputMode::Arabic => Follow::Word(WordKind::Arabic),
            InputMode::Abc => Follow::None,
        };
        let mut update = EditorUpdate::default();
        // Predictions need a word boundary right before the cursor.
        let boundary = before
            .chars()
            .last()
            .is_some_and(|c| c.is_whitespace() || c == '\u{1361}');
        let request = if boundary || matches!(follow, Follow::Fidel) {
            self.followup(&mut s, &mut update, &follow, boundary)
        } else {
            None
        };
        drop(s);
        self.complete(update, request)
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
        // ADR 0039: a Backspace right after an autocorrection restores the typed word.
        if let Some(u) = s.undo.take() {
            if event == KeyEvent::Backspace && s.idle() {
                return self.undo_correction(s, u);
            }
        }
        // After the space added behind a picked suggestion: a Space adds no second one, and
        // punctuation that attaches to the word replaces it (`word ` + `,` gives `word,`).
        let mut drop_space = false;
        if std::mem::take(&mut s.auto_space) && s.idle() {
            match &event {
                KeyEvent::Space => {
                    return EditorUpdate {
                        candidates: s.word_candidates.clone(),
                        ..Default::default()
                    };
                }
                KeyEvent::Char { text } if attaches_to_word(text) => drop_space = true,
                _ => {}
            }
        }
        let (mut update, follow) = self.dispatch(&mut s, event);
        if drop_space {
            update.delete_before = update.delete_before.saturating_add(1);
        }
        s.context.observe(&update);
        let boundary = ends_at_word_boundary(&update);
        let request = self.followup(&mut s, &mut update, &follow, boundary);
        drop(s);
        self.complete(update, request)
    }

    /// Select a candidate by index from the last update's `candidates`. With a composition its
    /// text is replaced by the candidate (phonetic: index 0 is the default composition,
    /// transliteration alternatives and dictionary words follow; word modes: index 0 is the word
    /// as typed); a next-word prediction is inserted; a Fidel completion replaces the letters of
    /// the word typed so far. A space follows the inserted word (ADR 0039) unless the text after
    /// the cursor already starts with one (`set_cursor_context`); a Space typed right after it
    /// adds no second space, and attaching punctuation replaces it. An index that is out of
    /// range changes nothing.
    pub fn select_candidate(&self, index: u32) -> EditorUpdate {
        let mut s = self.lock();
        s.generation = s.generation.wrapping_add(1);
        s.undo = None;
        let i = usize::try_from(index).unwrap_or(usize::MAX);
        let space = if s.space_after { "" } else { " " };
        let follow = Self::follow_of(s.mode);
        let picked = (s.strip != Strip::None)
            .then(|| s.word_candidates.get(i).map(|c| c.text.clone()))
            .flatten();
        let mut update = match (s.strip, picked) {
            (Strip::None, _) => {
                // A phonetic composition's strip (the session resolves the index).
                let Some(p) = s.phonetic.as_mut().filter(|p| !p.is_idle()) else {
                    return EditorUpdate::default();
                };
                let mut u = p.select(i);
                if !u.commit.is_empty() {
                    u.commit.push_str(space);
                }
                u
            }
            (_, None) => {
                // Out of range: the composition and the strip stay as they are.
                return EditorUpdate {
                    composing: Self::shown(&s),
                    candidates: s.word_candidates.clone(),
                    ..Default::default()
                };
            }
            (strip, Some(text)) => {
                let mut u = EditorUpdate {
                    commit: format!("{text}{space}"),
                    ..Default::default()
                };
                match strip {
                    Strip::Fidel => {
                        // The committed letters of the word go; the pending syllable is replaced.
                        u.delete_before = graphemes(&s.context.current);
                        if let Some(d) = s.direct.as_mut() {
                            d.reset();
                        }
                    }
                    Strip::Word => s.composing.clear(),
                    Strip::Predictions | Strip::None => {}
                }
                u
            }
        };
        s.auto_space = !space.is_empty() && update.commit.ends_with(' ');
        s.keep_typed = None;
        s.context.observe(&update);
        let boundary = ends_at_word_boundary(&update);
        let request = self.followup(&mut s, &mut update, &follow, boundary);
        drop(s);
        self.complete(update, request)
    }

    pub fn reset(&self) -> EditorUpdate {
        let mut s = self.lock();
        s.generation = s.generation.wrapping_add(1);
        s.clear_prediction();
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
        let mut model = self.lock_models().get(&language_code).cloned();
        if model.is_none() && !(dictionary.is_empty() && bigrams.is_empty()) {
            // Arabic: the user's own words are the whole model (ADR 0039).
            model = self.user_only_model(&language_code);
        }
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
    /// False if refused (invalid word, full, or no dictionary loaded for the language; Arabic,
    /// which has no shippable dictionary, gets a model of the user's own words, ADR 0039).
    pub fn add_user_word(&self, language_code: String, word: String) -> bool {
        self.model_or_user_only(&language_code)
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
        let Some(m) = self.model_or_user_only(&language_code) else {
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
            (_, Some(m))
                if m.dictionary
                    .as_ref()
                    .is_some_and(|d| Arc::ptr_eq(d, &dictionary)) =>
            {
                Some(Arc::clone(&m.lexicon))
            }
            _ => Lexicon::from_bytes(dictionary.artifact().to_vec()).ok(),
        };
        // An active dictionary that does not parse never drops the working model.
        let (dictionary, lexicon) = match (parsed, &current) {
            (Some(l), _) => (Some(dictionary), l),
            (None, Some(m)) => (m.dictionary.clone(), Arc::clone(&m.lexicon)),
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
        self.refresh_guards();
    }

    /// The Latin-script lexicons guard each other: a word valid in Afaan Oromoo is never
    /// autocorrected while typing English, and the other way round (ranker harm guard).
    fn refresh_guards(&self) {
        let models = self.lock_models();
        for lang in LATIN_LANGUAGES {
            if let Some(m) = models.get(lang) {
                let others: Vec<Arc<Lexicon>> = LATIN_LANGUAGES
                    .iter()
                    .filter(|l| **l != lang)
                    .filter_map(|l| models.get(*l).map(|o| Arc::clone(&o.lexicon)))
                    .collect();
                m.ranker.set_guard_lexicons(others);
            }
        }
    }

    /// The model of `language_code`, creating the user-only model of a language without a
    /// shippable dictionary (Arabic) on first use. Lock order: `models` only.
    fn model_or_user_only(&self, language_code: &str) -> Option<Arc<LangModel>> {
        if let Some(m) = self.lock_models().get(language_code).cloned() {
            return Some(m);
        }
        self.user_only_model(language_code)
    }

    /// Creates (or returns) the user-only model of `language_code`; `None` for a language that
    /// needs a dictionary pack.
    fn user_only_model(&self, language_code: &str) -> Option<Arc<LangModel>> {
        if !USER_ONLY_LANGUAGES.contains(&language_code) {
            return None;
        }
        let mut models = self.lock_models();
        let m = models.entry(language_code.to_string()).or_insert_with(|| {
            let lexicon = Lexicon::new_empty();
            Arc::new(LangModel {
                dictionary: None,
                bigrams: None,
                ranker: Ranker::new(Arc::clone(&lexicon)),
                lexicon,
            })
        });
        Some(Arc::clone(m))
    }

    /// The follow-up a mode's updates get.
    fn follow_of(mode: InputMode) -> Follow {
        match mode {
            InputMode::DirectFidel => Follow::Fidel,
            InputMode::PhoneticLatin => Follow::Phonetic,
            InputMode::Qubee => Follow::Word(WordKind::Oromo),
            InputMode::English => Follow::Word(WordKind::English),
            InputMode::Arabic => Follow::Word(WordKind::Arabic),
            InputMode::Abc => Follow::None,
        }
    }

    fn script(&self, kind: WordKind) -> Option<WordScript<'_>> {
        match kind {
            WordKind::Oromo => self.qubee.as_ref().map(WordScript::Latin),
            WordKind::English => self.english.as_ref().map(WordScript::Latin),
            WordKind::Arabic => Some(WordScript::Arabic),
        }
    }

    /// What the host shows as composing text now.
    fn shown(s: &State) -> String {
        if let Some(d) = s.direct.as_ref() {
            return d.shown_text();
        }
        if let Some(p) = s.phonetic.as_ref() {
            return p.shown_text();
        }
        s.composing.clone()
    }

    /// Runs `event` through the mode's composer (state lock held).
    fn dispatch(&self, s: &mut State, event: KeyEvent) -> (EditorUpdate, Follow) {
        match s.mode {
            InputMode::DirectFidel => (
                s.direct
                    .as_mut()
                    .map(|d| d.on_key(event))
                    .unwrap_or_default(),
                Follow::Fidel,
            ),
            InputMode::PhoneticLatin => (
                s.phonetic
                    .as_mut()
                    .map(|p| p.on_key(event))
                    .unwrap_or_default(),
                Follow::Phonetic,
            ),
            InputMode::Qubee | InputMode::English | InputMode::Arabic => {
                let follow = Self::follow_of(s.mode);
                let Follow::Word(kind) = follow else {
                    return (passthrough(s, event), Follow::None);
                };
                // A word composes only when the language has a model to suggest from (lock
                // order: state, then models); without one the mode stays plain passthrough.
                let active =
                    !s.composing.is_empty() || self.lock_models().contains_key(&s.language);
                let Some(script) = self.script(kind).filter(|_| active) else {
                    return (passthrough(s, event), Follow::None);
                };
                let was_empty = s.composing.is_empty();
                let corrected = self.autocorrect_at_boundary(s, &event, kind);
                let update = word_key(s, &script, event);
                if was_empty && !s.composing.is_empty() {
                    s.word_start_ok = s.context.at_word_boundary();
                }
                if s.composing.is_empty() {
                    s.keep_typed = None;
                }
                if let Some(typed) = corrected {
                    s.undo = Some(Undo {
                        typed,
                        remove: graphemes(&update.commit),
                        previous: s.context.previous.clone(),
                        sentence_start: s.context.sentence_start,
                        last: s.context.last,
                    });
                }
                (update, follow)
            }
            InputMode::Abc => (passthrough(s, event), Follow::None),
        }
    }

    /// At a word boundary (Space, Enter, sentence punctuation) in English or Afaan Oromoo with
    /// autocorrection on: replaces the composed word by a confident correction and returns the
    /// typed word. The ranker's harm guard decides (never a known or user word, a word of the
    /// other Latin language, a capitalised word mid-sentence, a word with digits or symbols);
    /// a word glued to `@`, `/`, `.` or digits (an address, a number) is never corrected.
    fn autocorrect_at_boundary(
        &self,
        s: &mut State,
        event: &KeyEvent,
        kind: WordKind,
    ) -> Option<String> {
        let latin = matches!(kind, WordKind::English | WordKind::Oromo);
        let boundary = match event {
            KeyEvent::Space | KeyEvent::Enter => true,
            KeyEvent::Char { text } => ends_sentence_part(text),
            _ => false,
        };
        if !latin
            || !boundary
            || !s.options.autocorrect
            || !s.options.suggestions
            || s.composing.is_empty()
            || !s.word_start_ok
            || s.keep_typed.as_deref() == Some(s.composing.as_str())
        {
            return None;
        }
        let model = self.lock_models().get(&s.language).cloned()?;
        let key = self.script(kind)?.key(&s.composing);
        let d = model
            .ranker
            .autocorrect(key, s.context.previous.clone(), s.context.sentence_start);
        let replacement = d
            .replacement
            .filter(|r| d.action == AutocorrectAction::Apply && *r != s.composing)?;
        Some(std::mem::replace(&mut s.composing, replacement))
    }

    /// Backspace right after an autocorrection: deletes the correction and what followed it and
    /// composes the typed word again; it is not corrected a second time (ADR 0039).
    fn undo_correction(&self, mut s: MutexGuard<'_, State>, u: Undo) -> EditorUpdate {
        let follow = Self::follow_of(s.mode);
        if !matches!(follow, Follow::Word(_)) {
            return EditorUpdate::default();
        }
        s.composing.clone_from(&u.typed);
        s.keep_typed = Some(u.typed.clone());
        s.word_start_ok = true;
        s.auto_space = false;
        let mut update = EditorUpdate {
            delete_before: u.remove,
            composing: u.typed,
            ..Default::default()
        };
        s.context.observe(&update);
        s.context.previous = u.previous;
        s.context.sentence_start = u.sentence_start;
        s.context.last = u.last;
        let request = self.followup(&mut s, &mut update, &follow, false);
        drop(s);
        self.complete(update, request)
    }

    /// After an update (state lock held, context already updated): sets up the strip the engine
    /// owns and says which candidates to look up once the lock is released. `boundary`: the
    /// update ended a word (next-word predictions may follow).
    fn followup(
        &self,
        s: &mut State,
        update: &mut EditorUpdate,
        follow: &Follow,
        boundary: bool,
    ) -> Option<CompletionRequest> {
        s.word_candidates.clear();
        s.strip = Strip::None;
        let request = |s: &State, kind| {
            Some(CompletionRequest {
                language: s.language.clone(),
                previous: s.context.previous.clone(),
                generation: s.generation,
                kind,
            })
        };
        let composing_empty = update.composing.is_empty();
        let predict = |s: &State| {
            let wants = boundary
                && composing_empty
                && s.options.next_word
                && s.options.suggestions
                && (s.context.previous.is_some() || s.context.sentence_start);
            if wants {
                request(s, RequestKind::Predict)
            } else {
                None
            }
        };
        match follow {
            Follow::None => None,
            Follow::Phonetic => {
                if composing_empty {
                    return predict(s);
                }
                let wants = s.options.suggestions && update.composing.chars().all(is_ethiopic);
                if !wants {
                    return None;
                }
                let homophones = s
                    .phonetic
                    .as_ref()
                    .map(PhoneticSession::alternatives)
                    .unwrap_or_default();
                request(s, RequestKind::Phonetic { homophones })
            }
            Follow::Word(kind) => {
                if composing_empty {
                    return predict(s);
                }
                s.word_candidates.push(Candidate {
                    text: update.composing.clone(),
                    kind: CandidateKind::Transliteration,
                });
                s.strip = Strip::Word;
                update.candidates = s.word_candidates.clone();
                if !s.options.suggestions {
                    return None;
                }
                let autocorrect = s.options.autocorrect
                    && matches!(kind, WordKind::English | WordKind::Oromo)
                    && s.word_start_ok
                    && s.keep_typed.as_deref() != Some(update.composing.as_str());
                let sentence_start = s.context.sentence_start;
                request(
                    s,
                    RequestKind::Word {
                        script: *kind,
                        autocorrect,
                        sentence_start,
                    },
                )
            }
            Follow::Fidel => {
                if !s.options.suggestions {
                    return None;
                }
                let key = format!("{}{}", s.context.current, update.composing);
                if !key.is_empty() && key.chars().all(is_ethiopic) {
                    request(s, RequestKind::Fidel { key })
                } else if composing_empty {
                    predict(s)
                } else {
                    None
                }
            }
        }
    }

    /// Looks up the candidates a follow-up asked for (outside the state lock) and attaches them
    /// if nothing changed the state meanwhile, so `select_candidate` always matches the strip.
    fn complete(&self, update: EditorUpdate, request: Option<CompletionRequest>) -> EditorUpdate {
        let Some(req) = request else {
            return update;
        };
        let max = usize::try_from(self.config.max_candidates).unwrap_or(usize::MAX);
        let Some(model) = self.lock_models().get(&req.language).cloned() else {
            return update;
        };
        match &req.kind {
            RequestKind::Phonetic { homophones } => {
                self.with_lexicon_candidates(update, &req, &model, homophones.clone(), max)
            }
            RequestKind::Word {
                script,
                autocorrect,
                sentence_start,
            } => self.with_word_candidates(
                update,
                &req,
                &model,
                (*script, *autocorrect, *sentence_start),
                max,
            ),
            RequestKind::Fidel { key } => {
                self.with_fidel_candidates(update, &req, &model, key, max)
            }
            RequestKind::Predict => self.with_predictions(update, &req, &model, max),
        }
    }

    /// Word modes' strip: the word as typed (index 0, kind `Transliteration`: the identity
    /// "rule"), then completions and one-edit corrections from the language's lexicon + user
    /// overlay, in the ranker's order (frequency, the previous word's bigrams, edit cost), up to
    /// `max_candidates`. The ranker keeps the typed word's capitalisation (`Akk` offers `Akkam`).
    /// When autocorrection may apply, the correction a Space would apply is moved to index 1 and
    /// reported as `autocorrect_index`. A word ending in an apostrophe (a closing quote, or a
    /// hudhaa still waiting for its vowel) is not looked up.
    fn with_word_candidates(
        &self,
        mut update: EditorUpdate,
        req: &CompletionRequest,
        model: &LangModel,
        (kind, autocorrect, sentence_start): (WordKind, bool, bool),
        max: usize,
    ) -> EditorUpdate {
        let typed = update.composing.clone();
        if max <= 1 || typed.ends_with(is_word_apostrophe) {
            return update;
        }
        let Some(script) = self.script(kind) else {
            return update;
        };
        let key = script.key(&typed);
        let want = self.config.max_candidates.saturating_add(1);
        let mut list = update.candidates.clone();
        for c in model.ranker.rank(key.clone(), req.previous.clone(), want) {
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
        let mut autocorrect_index = None;
        if autocorrect {
            let d = model
                .ranker
                .autocorrect(key, req.previous.clone(), sentence_start);
            if let Some(r) = d
                .replacement
                .filter(|r| d.action == AutocorrectAction::Apply && *r != typed)
            {
                list.retain(|c| c.text != r);
                list.insert(
                    1.min(list.len()),
                    Candidate {
                        text: r,
                        kind: CandidateKind::Correction,
                    },
                );
                list.truncate(max);
                autocorrect_index = Some(1);
            }
        }
        let mut s = self.lock();
        if s.generation != req.generation || s.composing != typed {
            return update;
        }
        s.word_candidates.clone_from(&list);
        s.strip = Strip::Word;
        drop(s);
        update.candidates = list;
        update.autocorrect_index = autocorrect_index;
        update
    }

    /// Merges lexicon candidates into the phonetic strip (A1.3 merge policy, ADR 0004 incl. the
    /// U6 amendment, reordered by ADR 0039). Index 0 stays the literal rule output. Then the
    /// transliteration alternatives that are dictionary words (most frequent first), then the
    /// ranker's completions and one-edit corrections of the composition (frequency + the
    /// previous word), then the alternatives that are not dictionary words (C1.4 order), up to
    /// `max_candidates`. At least [`RESERVED_COMPLETION_SLOTS`] slots go to completions whenever
    /// the ranker offers any. Nothing is applied automatically (harm guard): corrections are only
    /// offered. Without a dictionary for the language the C1.4 strip is returned unchanged.
    fn with_lexicon_candidates(
        &self,
        mut update: EditorUpdate,
        req: &CompletionRequest,
        model: &LangModel,
        homophones: Vec<String>,
        max: usize,
    ) -> EditorUpdate {
        if update.candidates.is_empty() || max <= 1 {
            return update;
        }
        // Ask for enough to survive removing the homophones that the ranker may also return.
        let want = self
            .config
            .max_candidates
            .saturating_add(u32::try_from(homophones.len()).unwrap_or(u32::MAX));
        let ranked = model
            .ranker
            .rank(update.composing.clone(), req.previous.clone(), want);
        let primary = update.candidates[0].text.clone();
        // Stable sort: known words by descending frequency; unknown ones keep C1.4 order.
        let mut known: Vec<(u32, String)> = Vec::new();
        let mut unknown: Vec<String> = Vec::new();
        for t in homophones {
            match model.lexicon.word_score(t.clone()) {
                Some(score) => known.push((score, t)),
                None => unknown.push(t),
            }
        }
        known.sort_by(|a, b| b.0.cmp(&a.0));
        let known: Vec<String> = known.into_iter().map(|(_, t)| t).collect();
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
                && !known.contains(&text)
                && !unknown.contains(&text)
                && !completions.iter().any(|(t, _)| *t == text)
            {
                completions.push((text, kind));
            }
        }
        let room = max - 1;
        let reserved = completions.len().min(RESERVED_COMPLETION_SLOTS);
        let known_take = known.len().min(room.saturating_sub(reserved));
        let comp_take = completions.len().min(room - known_take);
        let unknown_take = unknown.len().min(room - known_take - comp_take);
        let mut merged = vec![(primary, CandidateKind::Transliteration)];
        merged.extend(
            known
                .into_iter()
                .take(known_take)
                .map(|t| (t, CandidateKind::Transliteration)),
        );
        merged.extend(completions.into_iter().take(comp_take));
        merged.extend(
            unknown
                .into_iter()
                .take(unknown_take)
                .map(|t| (t, CandidateKind::Transliteration)),
        );
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

    /// Direct Fidel's strip: completions (and one-edit corrections) of the word typed so far,
    /// the committed letters since the last word boundary plus the pending syllable, ranked by
    /// frequency and the previous word. A pick replaces those letters (ADR 0039).
    fn with_fidel_candidates(
        &self,
        mut update: EditorUpdate,
        req: &CompletionRequest,
        model: &LangModel,
        key: &str,
        max: usize,
    ) -> EditorUpdate {
        let want = self.config.max_candidates.saturating_add(1);
        let mut list: Vec<Candidate> = Vec::new();
        for c in model
            .ranker
            .rank(key.to_string(), req.previous.clone(), want)
        {
            let kind = match c.kind {
                RankKind::Variant | RankKind::Completion => CandidateKind::Completion,
                RankKind::Correction => CandidateKind::Correction,
                RankKind::Exact | RankKind::NextWord | RankKind::Frequent => continue,
            };
            if list.len() >= max {
                break;
            }
            if c.text != key && !list.iter().any(|x| x.text == c.text) {
                list.push(Candidate { text: c.text, kind });
            }
        }
        self.attach(&mut update, req, list, Strip::Fidel);
        update
    }

    /// Next-word predictions after the previous word: the bigram pack and the user's own word
    /// pairs first, then frequent words to fill the strip (never the previous word itself). After a
    /// sentence end (`. ! ? ። ፧ ؟` and a space) there is no previous word: the most frequent words.
    fn with_predictions(
        &self,
        mut update: EditorUpdate,
        req: &CompletionRequest,
        model: &LangModel,
        max: usize,
    ) -> EditorUpdate {
        let limit = self.config.max_candidates.saturating_add(1);
        let prev = req.previous.clone().unwrap_or_default();
        let ranked = if prev.is_empty() {
            model.ranker.frequent(limit)
        } else {
            model.ranker.next_words(prev.clone(), limit)
        };
        let list: Vec<Candidate> = ranked
            .into_iter()
            .filter(|c| !c.text.is_empty() && c.text != prev)
            .take(max)
            .map(|c| Candidate {
                text: c.text,
                kind: CandidateKind::Prediction,
            })
            .collect();
        self.attach(&mut update, req, list, Strip::Predictions);
        update
    }

    /// Attaches an engine-owned strip if nothing changed the state since the request.
    fn attach(
        &self,
        update: &mut EditorUpdate,
        req: &CompletionRequest,
        list: Vec<Candidate>,
        strip: Strip,
    ) {
        if list.is_empty() {
            return;
        }
        let mut s = self.lock();
        if s.generation != req.generation {
            return;
        }
        s.word_candidates.clone_from(&list);
        s.strip = strip;
        drop(s);
        update.candidates = list;
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
