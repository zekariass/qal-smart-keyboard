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
/// The sixth order: what a consonant shows before any vowel is typed.
const BARE_ORDER: u8 = 6;

/// One transliterated unit of the buffer.
struct Tok {
    text: String,
    /// Whole-token replacements (homophones, other orders).
    alts: Vec<String>,
    /// Series of a consonant that has no vowel yet (candidate for gemination collapsing).
    bare: Option<usize>,
    /// Consonant rule of a bare consonant (its homophones give more labialised alternatives).
    rule: Option<usize>,
    /// Replacements of this token together with the next one: the labialised reading of a bare
    /// consonant followed by the profile's `labialAltMarker` syllable (`g` + `wa` also gives ጓ).
    merge_next: Vec<String>,
}

impl Tok {
    fn plain(text: String) -> Self {
        Self {
            text,
            alts: Vec::new(),
            bare: None,
            rule: None,
            merge_next: Vec::new(),
        }
    }
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
    /// All transliteration alternatives of the current composition (not capped at
    /// `max_candidates`), in C1.4 order; the lexicon merge (A1.3, ADR 0004 U6) ranks them.
    alternatives: Vec<String>,
    max_candidates: usize,
}

impl PhoneticSession {
    pub fn new(profile: Arc<Profile>, max_candidates: usize) -> Self {
        Self {
            profile,
            raw: String::new(),
            pending: None,
            candidates: Vec::new(),
            alternatives: Vec::new(),
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
        self.alternatives.clear();
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

    /// Every transliteration alternative of the current composition (after index 0), in the
    /// order the rules produce them; not capped at `max_candidates`.
    pub fn alternatives(&self) -> Vec<String> {
        self.alternatives.clone()
    }

    /// Replaces the strip with the merged list (A1.3 / U6) so that [`select`](Self::select)
    /// resolves exactly what the host shows. Index 0 must stay the default composition;
    /// anything else is ignored. The list is capped at `max_candidates`.
    pub fn set_candidates(&mut self, mut list: Vec<String>) {
        if list.first() != self.candidates.first() {
            return;
        }
        list.truncate(self.max_candidates);
        self.candidates = list;
    }

    /// Builds the update for the host from the current buffer.
    fn view(&mut self, commit: String) -> EditorUpdate {
        let (composing, mut cands) = if self.raw.is_empty() {
            self.alternatives.clear();
            (self.pending.map(String::from).unwrap_or_default(), vec![])
        } else {
            let (primary, alts) = self.render();
            let mut all = vec![primary.clone()];
            all.extend(alts.iter().cloned());
            self.alternatives = alts;
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
            // A merge replaces this token and the next one; other alternatives replace one.
            let merges = tok.merge_next.iter().map(|a| (a, 2));
            for (a, span) in merges.chain(tok.alts.iter().map(|a| (a, 1))) {
                let s: String = toks
                    .iter()
                    .enumerate()
                    .map(|(k, x)| {
                        if k == i {
                            a.as_str()
                        } else if k > i && k < i + span {
                            ""
                        } else {
                            x.text.as_str()
                        }
                    })
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
        // Rule inputs are validated ASCII: a rule can only match if its first byte is the key
        // or (case folding) its lowercase form. Cheap pre-check on the per-key hot path.
        let &key = raw.get(i)?;
        let lower = key.to_ascii_lowercase();
        let may_start = |input: &str| {
            let f = char::from(input.as_bytes()[0]);
            f == key || f == lower
        };
        if all {
            if p.separator == Some(key) {
                consider(1, true, Hit::Separator);
            }
            for (k, c) in p.consonants.iter().enumerate() {
                if !may_start(&c.input) {
                    continue;
                }
                if let Some(e) = self.matches_at(raw, i, &c.input) {
                    consider(c.input.len(), e, Hit::Consonant(k));
                }
            }
        }
        for (k, v) in p.vowels.iter().enumerate() {
            if !may_start(&v.input) {
                continue;
            }
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
            let hit = self.best_at(raw, i, true);
            // A merge offered by the previous token was with a token that is replaced now.
            if let Some(prev) = toks.last_mut() {
                prev.merge_next.clear();
            }
            match hit {
                Some((Hit::Separator, n)) => {
                    // Splits a sequence that would otherwise combine; shows nothing.
                    toks.push(Tok::plain(String::new()));
                    i += n;
                }
                Some((Hit::Consonant(k), n)) => {
                    let rule = &p.consonants[k];
                    let (mut tok, used, plain_order) = self.consonant(raw, i + n, rule);
                    // Fidel does not mark gemination, so a doubled consonant *before a vowel*
                    // (`messeret`, `etta`) is one letter. A doubled consonant with no vowel after
                    // it is two sixth-order letters (`ggr` ግግር, `gg` ግግ): pressing a key twice
                    // types the letter twice. The two-letter reading of a collapsed geminate stays
                    // reachable as the last alternative of the merged letter (`shshit`: ሺት, ሽሺት).
                    if p.collapse_gemination
                        && tok.bare.is_none()
                        && toks.last().is_some_and(|t| t.bare == Some(rule.series))
                    {
                        if let Some(first) = toks.pop() {
                            let split = format!("{}{}", first.text, tok.text);
                            if !tok.alts.contains(&split) {
                                tok.alts.push(split);
                            }
                        }
                        if let Some(prev) = toks.last_mut() {
                            prev.merge_next.clear();
                        }
                    }
                    if tok.bare.is_some() {
                        tok.rule = Some(k);
                    }
                    if let (Some(order), true) = (plain_order, p.labial_alt_rule == Some(k)) {
                        if let Some(prev) = toks.last_mut() {
                            prev.merge_next = self.labial_merges(prev, order);
                        }
                    }
                    toks.push(tok);
                    i += n + used;
                }
                Some((Hit::Vowel(k), n)) => {
                    toks.push(self.standalone_vowel(k, &raw[i..i + n]));
                    i += n;
                }
                None => {
                    toks.push(Tok::plain(raw[i].to_string()));
                    i += 1;
                }
            }
        }
        toks
    }

    /// Labialised readings of the bare consonant `prev` followed by a `labialAltMarker` syllable
    /// in vowel `order`: its own form first, then its homophones' (ጓ for `g` + `wa`).
    fn labial_merges(&self, prev: &Tok, order: u8) -> Vec<String> {
        let p = &self.profile;
        let Some(rule) = prev.rule.map(|r| &p.consonants[r]) else {
            return Vec::new();
        };
        let mut out: Vec<String> = Vec::new();
        for s in std::iter::once(rule.series).chain(rule.alt.iter().copied()) {
            if let Some(g) = p.glyph(s, order, true).map(String::from) {
                if !out.contains(&g) {
                    out.push(g);
                }
            }
        }
        out
    }

    /// A consonant whose rule matched just before `at`. Returns the token, how many further
    /// characters (labial marker, vowel) it consumed, and its vowel order when it was read
    /// without the labial marker.
    fn consonant(&self, raw: &[char], at: usize, rule: &ConsonantRule) -> (Tok, usize, Option<u8>) {
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
                alts,
                ..Tok::plain(text)
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
                    return (tok, m.len() + vlen, None);
                }
            }
        }
        let vowel = self.vowel_at(raw, at);
        if let Some((v, len)) = vowel {
            let order = p.vowels[v].order;
            if let Some(tok) = build(order, false) {
                return (tok, len, Some(order));
            }
        }
        // Bare consonant: the sixth order. (The series was validated to have one.)
        let mut tok =
            build(BARE_ORDER, false).unwrap_or_else(|| Tok::plain(literal(rule.input.len())));
        tok.bare = Some(rule.series);
        (tok, 0, Some(BARE_ORDER))
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
            return Tok::plain(typed.iter().collect());
        };
        // The first other order, then the alternative series in the same order, then each other
        // order in the initial and the alternative series (`a`: ኣ, ዐ, ዓ; `e`: አ, ዕ, ዐ, ኧ).
        let first = alt_orders.first().map(|o| (p.initial_series, *o));
        let same = p.initial_alt_series.iter().map(|s| (*s, order));
        let further = alt_orders.iter().enumerate().flat_map(|(i, o)| {
            std::iter::once(p.initial_series)
                .chain(p.initial_alt_series.iter().copied())
                .skip(usize::from(i == 0))
                .map(move |s| (s, *o))
        });
        let mut alts: Vec<String> = Vec::new();
        for (s, o) in first.into_iter().chain(same).chain(further) {
            if let Some(g) = p.glyph(s, o, false).filter(|g| *g != text) {
                let g = g.to_string();
                if !alts.contains(&g) {
                    alts.push(g);
                }
            }
        }
        Tok {
            alts,
            ..Tok::plain(text.to_string())
        }
    }
}
