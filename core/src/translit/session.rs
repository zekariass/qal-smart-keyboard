//! The phonetic composing buffer (plan step C1.4).
//!
//! The session keeps the raw Latin keystrokes of the current word. After every key it
//! re-tokenises the whole buffer with longest-match rules from the [`Profile`], so backspace
//! (remove the last keystroke) and late disambiguation (`s` then `h` becomes `sh`) need no
//! special cases. Output is deterministic; alternatives are single homophone substitutions
//! (lexicon-based ranking arrives with C1.6/C1.7). No typed text is ever logged (N2).

use super::profile::{ConsonantRule, Profile};
use crate::composer::{nfc_commit, sanitize};
use crate::engine::{Candidate, CandidateKind, EditorUpdate, KeyEvent};
use std::sync::Arc;

/// Longest raw buffer of a transliterated word; longer input is committed and restarted.
const MAX_RAW_CHARS: usize = 48;
/// Longest raw buffer of a protected (URL or e-mail like) token.
const MAX_PROTECTED_CHARS: usize = 256;
/// Most alternatives generated for one composition.
const MAX_ALTERNATIVES: usize = 8;

/// One transliterated unit of the buffer.
struct Tok {
    text: String,
    /// Whole-token replacements (homophones, other orders).
    alts: Vec<String>,
    /// Series of a consonant that has no vowel yet (candidate for gemination collapsing).
    bare: Option<usize>,
}

enum Hit {
    Separator,
    Consonant(usize),
    Vowel(usize),
}

/// Phonetic Latin to Ge'ez composing session for one language profile.
#[derive(Debug)]
pub struct PhoneticSession {
    profile: Arc<Profile>,
    raw: String,
    /// First character of a possible two-character punctuation sequence, shown as composing.
    pending: Option<char>,
    candidates: Vec<String>,
    max_candidates: usize,
}

impl PhoneticSession {
    pub fn new(profile: Arc<Profile>, max_candidates: usize) -> Self {
        Self {
            profile,
            raw: String::new(),
            pending: None,
            candidates: Vec::new(),
            max_candidates,
        }
    }

    pub fn profile(&self) -> &Arc<Profile> {
        &self.profile
    }

    pub fn reset(&mut self) {
        self.raw.clear();
        self.pending = None;
        self.candidates.clear();
    }

    pub fn on_key(&mut self, event: KeyEvent) -> EditorUpdate {
        let mut commit = String::new();
        match event {
            KeyEvent::Char { text } => {
                if let Some(t) = sanitize(&text) {
                    for c in t.chars() {
                        self.push_char(c, &mut commit);
                    }
                }
            }
            KeyEvent::Space => {
                commit = self.flush();
                commit.push(' ');
            }
            KeyEvent::Enter => {
                commit = self.flush();
                commit.push('\n');
            }
            KeyEvent::Commit => commit = self.flush(),
            KeyEvent::Backspace => {
                if self.pending.take().is_some() || self.raw.pop().is_some() {
                    // The last Latin keystroke is gone; the view recomputes the composition.
                } else {
                    return EditorUpdate {
                        delete_before: 1,
                        ..Default::default()
                    };
                }
            }
            // Two-key Fidel events only mean something in DirectFidel.
            KeyEvent::FidelConsonant { .. } | KeyEvent::FidelVowel { .. } => {}
        }
        self.view(commit)
    }

    /// Replaces the composition by candidate `index` (0 is the default) and commits it.
    /// An unknown index changes nothing.
    pub fn select(&mut self, index: usize) -> EditorUpdate {
        match self.candidates.get(index).cloned() {
            Some(text) => {
                self.reset();
                self.view(text)
            }
            None => self.view(String::new()),
        }
    }

    /// Appends lexicon candidates (A1.3) after the transliteration alternatives, so that
    /// [`select`](Self::select) resolves the merged strip the host shows. Texts already present
    /// are skipped and the list stays capped at `max_candidates`. Returns the texts added, in
    /// order.
    pub fn extend_candidates(&mut self, extra: impl IntoIterator<Item = String>) -> Vec<String> {
        let mut added = Vec::new();
        for text in extra {
            if self.candidates.len() >= self.max_candidates {
                break;
            }
            if !text.is_empty() && !self.candidates.contains(&text) {
                self.candidates.push(text.clone());
                added.push(text);
            }
        }
        added
    }

    /// Builds the update for the host from the current buffer.
    fn view(&mut self, commit: String) -> EditorUpdate {
        let (composing, mut cands) = if self.raw.is_empty() {
            (self.pending.map(String::from).unwrap_or_default(), vec![])
        } else {
            let (primary, alts) = self.render();
            let mut all = vec![primary.clone()];
            all.extend(alts);
            (primary, all)
        };
        cands.truncate(self.max_candidates);
        self.candidates = cands;
        EditorUpdate {
            composing,
            commit: nfc_commit(commit),
            delete_before: 0,
            candidates: self
                .candidates
                .iter()
                .map(|t| Candidate {
                    text: t.clone(),
                    kind: CandidateKind::Transliteration,
                })
                .collect(),
        }
    }

    /// Takes everything pending as text to commit.
    fn flush(&mut self) -> String {
        let mut out = self.pending.take().map(String::from).unwrap_or_default();
        out.push_str(&self.take_raw());
        out
    }

    fn take_raw(&mut self) -> String {
        if self.raw.is_empty() {
            return String::new();
        }
        let (primary, _) = self.render();
        self.raw.clear();
        primary
    }

    fn push_char(&mut self, ch: char, commit: &mut String) {
        let p = Arc::clone(&self.profile);
        if ch.is_whitespace() {
            commit.push_str(&self.flush());
            commit.push(ch);
            return;
        }
        if self.is_protected() || p.protected_chars.contains(&ch) || p.is_letter_key(ch) {
            if let Some(c) = self.pending.take() {
                commit.push(c);
            }
            let limit = if self.is_protected() {
                MAX_PROTECTED_CHARS
            } else {
                MAX_RAW_CHARS
            };
            if self.raw.chars().count() >= limit {
                commit.push_str(&self.take_raw());
            }
            self.raw.push(ch);
            return;
        }
        // Not a letter: it ends the word, and may extend a punctuation sequence.
        if let Some(first) = self.pending.take() {
            if let Some(out) = p.punct_pair(first, ch) {
                commit.push(out);
                return;
            }
            commit.push(first);
        }
        commit.push_str(&self.take_raw());
        if p.is_punct_start(ch) {
            self.pending = Some(ch);
        } else {
            commit.push(ch);
        }
    }

    /// A buffer that looks like a URL or e-mail address stays Latin (protected token).
    fn is_protected(&self) -> bool {
        let p = &self.profile;
        let lower = self.raw.to_lowercase();
        p.protected_prefixes.iter().any(|x| lower.starts_with(x))
            || self.raw.chars().any(|c| p.protected_chars.contains(&c))
    }

    /// The default text and the alternatives for the current buffer.
    fn render(&self) -> (String, Vec<String>) {
        if self.is_protected() {
            return (self.raw.clone(), Vec::new());
        }
        let chars: Vec<char> = self.raw.chars().collect();
        let toks = self.tokenize(&chars);
        let primary: String = toks.iter().map(|t| t.text.as_str()).collect();
        let mut alts = Vec::new();
        'outer: for (i, tok) in toks.iter().enumerate() {
            for a in &tok.alts {
                let s: String = toks
                    .iter()
                    .enumerate()
                    .map(|(k, x)| if k == i { a.as_str() } else { x.text.as_str() })
                    .collect();
                if s != primary && !alts.contains(&s) {
                    alts.push(s);
                    if alts.len() >= MAX_ALTERNATIVES {
                        break 'outer;
                    }
                }
            }
        }
        if self
            .profile
            .protected_terms
            .contains(&self.raw.to_lowercase())
        {
            // A known product or place name: keep it Latin, offer the transliteration.
            alts.insert(0, primary);
            return (self.raw.clone(), alts);
        }
        (primary, alts)
    }

    /// Does `input` match `raw` at `i`? `Some(true)` for an exact match, `Some(false)` for a
    /// case-folded one (only lowercase rules fold).
    fn matches_at(&self, raw: &[char], i: usize, input: &str) -> Option<bool> {
        let n = input.chars().count();
        if i + n > raw.len() {
            return None;
        }
        let slice = &raw[i..i + n];
        if input.chars().zip(slice).all(|(a, b)| a == *b) {
            return Some(true);
        }
        let foldable = self.profile.fold_case && !input.chars().any(|c| c.is_ascii_uppercase());
        (foldable
            && input
                .chars()
                .zip(slice)
                .all(|(a, b)| a == b.to_ascii_lowercase()))
        .then_some(false)
    }

    /// The longest vowel rule at `i`: `(order, length)`.
    fn vowel_at(&self, raw: &[char], i: usize) -> Option<(usize, usize)> {
        self.best_at(raw, i, false).and_then(|(h, len)| match h {
            Hit::Vowel(v) => Some((v, len)),
            _ => None,
        })
    }

    /// Longest rule at `i` (longest wins, exact beats folded). With `all`, separators and
    /// consonants compete too; otherwise only vowels are considered.
    fn best_at(&self, raw: &[char], i: usize, all: bool) -> Option<(Hit, usize)> {
        let p = &self.profile;
        let mut best: Option<((usize, bool), Hit)> = None;
        let mut consider = |len: usize, exact: bool, hit: Hit| {
            if best.as_ref().is_none_or(|(k, _)| (len, exact) > *k) {
                best = Some(((len, exact), hit));
            }
        };
        if all {
            if p.separator == Some(raw[i]) {
                consider(1, true, Hit::Separator);
            }
            for (k, c) in p.consonants.iter().enumerate() {
                if let Some(e) = self.matches_at(raw, i, &c.input) {
                    consider(c.input.len(), e, Hit::Consonant(k));
                }
            }
        }
        for (k, v) in p.vowels.iter().enumerate() {
            if let Some(e) = self.matches_at(raw, i, &v.input) {
                consider(v.input.len(), e, Hit::Vowel(k));
            }
        }
        best.map(|((len, _), h)| (h, len))
    }

    fn tokenize(&self, raw: &[char]) -> Vec<Tok> {
        let p = &self.profile;
        let mut toks: Vec<Tok> = Vec::new();
        let mut i = 0;
        while i < raw.len() {
            match self.best_at(raw, i, true) {
                Some((Hit::Separator, n)) => {
                    // Splits a sequence that would otherwise combine; shows nothing.
                    toks.push(Tok {
                        text: String::new(),
                        alts: Vec::new(),
                        bare: None,
                    });
                    i += n;
                }
                Some((Hit::Consonant(k), n)) => {
                    let rule = &p.consonants[k];
                    if p.collapse_gemination
                        && toks.last().is_some_and(|t| t.bare == Some(rule.series))
                    {
                        // Fidel does not mark gemination: a doubled consonant is one letter.
                        toks.pop();
                    }
                    let (tok, used) = self.consonant(raw, i + n, rule);
                    toks.push(tok);
                    i += n + used;
                }
                Some((Hit::Vowel(k), n)) => {
                    toks.push(self.standalone_vowel(k, &raw[i..i + n]));
                    i += n;
                }
                None => {
                    toks.push(Tok {
                        text: raw[i].to_string(),
                        alts: Vec::new(),
                        bare: None,
                    });
                    i += 1;
                }
            }
        }
        toks
    }

    /// A consonant whose rule matched just before `at`. Returns the token and how many further
    /// characters (labial marker, vowel) it consumed.
    fn consonant(&self, raw: &[char], at: usize, rule: &ConsonantRule) -> (Tok, usize) {
        let p = &self.profile;
        let literal = |len: usize| -> String { raw[at - len..at].iter().collect() };
        let build = |order: u8, labial: bool| -> Option<Tok> {
            let text = p.glyph(rule.series, order, labial)?.to_string();
            let alts = rule
                .alt
                .iter()
                .filter_map(|a| p.glyph(*a, order, labial))
                .map(String::from)
                .collect();
            Some(Tok {
                text,
                alts,
                bare: None,
            })
        };

        // Labialised form: consonant, marker, optional vowel (`gWa` gives the labiovelar).
        if let Some(m) = &p.labial_marker {
            if raw[at..].starts_with(m) {
                let k = at + m.len();
                let (order, vlen) = self
                    .vowel_at(raw, k)
                    .map_or((6, 0), |(v, len)| (p.vowels[v].order, len));
                if let Some(tok) = build(order, true) {
                    return (tok, m.len() + vlen);
                }
            }
        }
        let vowel = self.vowel_at(raw, at);
        if let Some((v, len)) = vowel {
            if let Some(tok) = build(p.vowels[v].order, false) {
                return (tok, len);
            }
        }
        // Bare consonant: the sixth order. (The series was validated to have one.)
        let mut tok = build(6, false).unwrap_or_else(|| Tok {
            text: literal(rule.input.len()),
            alts: Vec::new(),
            bare: None,
        });
        tok.bare = Some(rule.series);
        (tok, 0)
    }

    /// A vowel typed with no consonant: a glottal-series syllable (`a` gives አ).
    fn standalone_vowel(&self, rule: usize, typed: &[char]) -> Tok {
        let p = &self.profile;
        let v = &p.vowels[rule];
        let (order, alt_orders) = p
            .initial_overrides
            .iter()
            .find(|o| o.input == v.input)
            .map_or((v.order, &[][..]), |o| (o.order, o.alt_orders.as_slice()));
        let Some(text) = p.glyph(p.initial_series, order, false) else {
            return Tok {
                text: typed.iter().collect(),
                alts: Vec::new(),
                bare: None,
            };
        };
        let mut alts: Vec<String> = alt_orders
            .iter()
            .filter_map(|o| p.glyph(p.initial_series, *o, false))
            .chain(
                p.initial_alt_series
                    .iter()
                    .filter_map(|s| p.glyph(*s, order, false)),
            )
            .map(String::from)
            .collect();
        alts.retain(|a| *a != text.to_string());
        Tok {
            text: text.to_string(),
            alts,
            bare: None,
        }
    }
}
