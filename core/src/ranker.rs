//! Candidate ranking, next-word suggestions and autocorrect with a harm guard (plan step C1.7).
//!
//! # Model
//! Every candidate gets one log-scale score (nats):
//!
//! ```text
//! score = ln(1 + lexicon score)                  unigram: base frequency + user overlay
//!       + BIGRAM_WEIGHT * ln(1 + bigram count)   context, when the previous word is known
//!       + EXACT_BONUS                            the typed word itself is a known word
//!       - EDIT_PENALTY * edit cost               typo cost (adjacent key / vowel slip < 1)
//!       - COMPLETION_PENALTY * extra letters     completions of the typed prefix
//!       - equivalence penalty                    case / diacritic / homophone match (tiny)
//! ```
//!
//! # Equivalences (matching only, never rewriting)
//! Case, Latin diacritics and the Ge'ez homophone letter families (ሀ/ሐ/ኀ, ሰ/ሠ, አ/ዐ, ጸ/ፀ) are
//! treated as equivalent for matching and for next-word statistics. Candidates always carry the
//! lexicon's own spelling, a typed word that is valid is never replaced, and a homophone
//! variant is only ever a suggestion (`RankKind::Variant`), never an autocorrection.
//!
//! Corrections are one edit away (delete, transpose, substitute, insert; Fidel words: delete,
//! transpose and vowel-order slips). Known limits: no Fidel insertions, and a typo combined
//! with a homophone mismatch is not bridged.
//!
//! # Harm guard
//! [`Ranker::autocorrect`] applies a correction only when ALL of these hold (otherwise it is
//! at most a suggestion): the typed word is not valid in any enabled language (nor equivalent
//! to a valid word); it is at least [`MIN_AUTOCORRECT_CHARS`] letters, with no digits or
//! symbols and no unexpected capitals; the best candidate is within one edit
//! ([`MAX_AUTOCORRECT_EDIT_COST`]), keeps the first letter and the script; and its posterior
//! probability against every rival *and against "the typed word is intended"* (a fixed prior,
//! [`UNKNOWN_WORD_PRIOR`]) is at least [`AUTOCORRECT_MIN_CONFIDENCE`].
//!
//! Privacy (N1, N2): all in-process and offline; nothing is logged and errors carry no text.

mod bigram;
mod fold;

use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use unicode_normalization::UnicodeNormalization;

use crate::lexicon::Lexicon;
use bigram::BigramModel;
use fold::{fold_key, is_ethiopic, neighbours, title_case, variants};

/// Weight of `ln(1 + bigram count)` in a candidate's score.
pub const BIGRAM_WEIGHT: f64 = 1.0;
/// Bonus when the typed word is itself a known word (keeps it at the front of the strip).
pub const EXACT_BONUS: f64 = 1.0;
/// Score lost per unit of edit cost.
pub const EDIT_PENALTY: f64 = 2.5;
/// Score lost per letter a completion adds beyond the typed prefix.
pub const COMPLETION_PENALTY: f64 = 0.15;
/// Weight of the unigram in next-word ordering (bigram count dominates).
pub const NEXT_WORD_UNIGRAM_WEIGHT: f64 = 0.1;
/// A user's own bigram observation counts this many corpus observations.
pub const USER_BIGRAM_WEIGHT: u32 = 20;
/// Most pairs the user's bigram overlay holds; further new pairs are refused.
pub const MAX_USER_BIGRAMS: usize = 50_000;

/// Score of the hypothesis "the typed word is what the user meant" (names, slang, new words),
/// in the same nats as candidate scores; about `ln(150)`. Higher is more conservative.
pub const UNKNOWN_WORD_PRIOR: f64 = 5.0;
/// Minimum posterior probability for a correction to be applied automatically.
pub const AUTOCORRECT_MIN_CONFIDENCE: f64 = 0.85;
/// Largest weighted edit cost an automatic correction may have (a single edit).
pub const MAX_AUTOCORRECT_EDIT_COST: f64 = 1.0;
/// Shortest typed word (in characters) that may be auto-corrected.
pub const MIN_AUTOCORRECT_CHARS: usize = 3;
/// Longest typed word the ranker processes (bounds work; longer input yields nothing).
pub const MAX_TYPED_CHARS: usize = 48;
/// Most candidates returned by one call.
pub const MAX_RESULTS: u32 = 50;

/// Errors from ranker payload handling. Messages are fixed strings, never word content.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum RankerError {
    /// A bigram payload is malformed or has an unknown format.
    #[error("invalid bigram data: {reason}")]
    InvalidData { reason: String },
}

/// Why a candidate is offered.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RankKind {
    /// The typed word itself, which is a known word.
    Exact,
    /// A known word equal to the typed one up to case, diacritics or homophone letters.
    Variant,
    /// A longer known word starting with the typed text.
    Completion,
    /// A known word one typing slip away.
    Correction,
    /// Predicted next word (from the bigram model).
    NextWord,
    /// Frequent word shown when there is no context.
    Frequent,
}

/// One ranked candidate. Owned data for the FFI.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RankedCandidate {
    /// The word to offer, in the lexicon's spelling (case adapted to the typed word).
    pub text: String,
    pub kind: RankKind,
    /// Relative score in nats; only comparable within one call.
    pub score: f32,
    /// Weighted edit cost from the typed text (0 unless `kind` is `Correction`).
    pub edit_cost: f32,
}

/// What the host should do with the typed word at a word boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AutocorrectAction {
    /// Leave the typed word alone.
    None,
    /// Show `replacement` on the strip but do not change the text.
    SuggestOnly,
    /// Replace the typed word with `replacement`.
    Apply,
}

/// Why the decision was made (for tests, telemetry-free diagnostics and UI hints).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AutocorrectReason {
    /// The typed word (or an equivalent spelling) is a valid word; never corrected.
    ValidWord,
    /// Valid in another enabled language; never corrected.
    OtherLanguageWord,
    /// Too short, contains digits/symbols or unexpected capitals; never corrected.
    NotEligible,
    /// Nothing within one edit is a known word.
    NoCandidate,
    /// There is a candidate but it fails the confidence or safety checks: suggestion only.
    LowConfidence,
    /// A confident, safe correction.
    Confident,
}

/// Result of [`Ranker::autocorrect`].
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AutocorrectDecision {
    pub action: AutocorrectAction,
    /// Best candidate, present for `SuggestOnly` and `Apply`.
    pub replacement: Option<String>,
    /// Posterior probability of the best candidate (0 when there is none).
    pub confidence: f32,
    pub reason: AutocorrectReason,
}

impl AutocorrectDecision {
    fn none(reason: AutocorrectReason) -> Self {
        AutocorrectDecision {
            action: AutocorrectAction::None,
            replacement: None,
            confidence: 0.0,
            reason,
        }
    }
}

fn nfc(s: &str) -> String {
    s.nfc().collect()
}

/// Adapts the case of a lexicon word to the typed text: a Title-case typed word capitalises a
/// lower-case lexicon word. Lexicon spelling is otherwise kept.
fn adapt_case(typed: &str, word: &str) -> String {
    let mut t = typed.chars();
    let first_upper = t.next().is_some_and(char::is_uppercase);
    let rest_upper = t.any(char::is_uppercase);
    let word_lower = word.chars().next().is_some_and(char::is_lowercase);
    if first_upper && !rest_upper && word_lower {
        title_case(word)
    } else {
        word.to_string()
    }
}

fn ln1p(v: u32) -> f64 {
    f64::from(v).ln_1p()
}

/// Bigram evidence after one previous word: (next-word key, next word, count).
type Context = Vec<(String, String, u32)>;

struct Cand {
    text: String,
    kind: RankKind,
    uni: u32,
    edit_cost: f64,
    /// Equivalence penalty plus completion length penalty.
    adjust: f64,
}

/// Ranker over a shared [`Lexicon`], an optional shipped bigram model and a local user bigram
/// overlay. Thread-safe; share as an `Arc` across the FFI.
#[derive(uniffi::Object)]
pub struct Ranker {
    lexicon: Arc<Lexicon>,
    guards: RwLock<Vec<Arc<Lexicon>>>,
    base: RwLock<BigramModel>,
    user: RwLock<BigramModel>,
}

/// Builds a "QBG1" bigram payload from `previous<TAB>next<TAB>count` lines (pack tooling and
/// tests); blank lines and `#` comments are skipped, a missing or bad count counts as 1.
#[uniffi::export]
pub fn bigram_payload_from_text(text: String) -> Vec<u8> {
    let triples = text.lines().filter_map(|l| {
        let l = l.trim_end_matches('\r');
        if l.trim().is_empty() || l.starts_with('#') {
            return None;
        }
        let mut p = l.split('\t');
        let (a, b) = (p.next()?.trim(), p.next()?.trim());
        let c = p.next().and_then(|c| c.trim().parse().ok()).unwrap_or(1);
        Some((a.to_string(), b.to_string(), c))
    });
    BigramModel::from_triples(triples).to_bytes()
}

fn read<T>(l: &RwLock<T>) -> std::sync::RwLockReadGuard<'_, T> {
    l.read().unwrap_or_else(|e| e.into_inner())
}

fn write<T>(l: &RwLock<T>) -> std::sync::RwLockWriteGuard<'_, T> {
    l.write().unwrap_or_else(|e| e.into_inner())
}

/// True if `bytes` is a valid "QBG1" bigram payload (used by the engine to check a pack).
pub(crate) fn validate_bigrams(bytes: &[u8]) -> bool {
    bigram::BigramModel::from_bytes(bytes).is_ok()
}

fn bigram_error(reason: &str) -> RankerError {
    RankerError::InvalidData {
        reason: reason.to_string(),
    }
}

impl Ranker {
    /// Combined bigram counts after `prev` (shipped plus weighted user overlay).
    fn context(&self, prev: Option<&str>) -> Context {
        let Some(prev) = prev.filter(|p| !p.is_empty()) else {
            return Vec::new();
        };
        let mut merged: HashMap<String, (String, u32)> = HashMap::new();
        for e in read(&self.base).after(prev) {
            let s = merged.entry(e.key.clone()).or_insert((e.next.clone(), 0));
            s.1 = s.1.saturating_add(e.count);
        }
        for e in read(&self.user).after(prev) {
            let s = merged.entry(e.key.clone()).or_insert((e.next.clone(), 0));
            s.1 =
                s.1.saturating_add(e.count.saturating_mul(USER_BIGRAM_WEIGHT));
        }
        merged.into_iter().map(|(k, (n, c))| (k, n, c)).collect()
    }

    fn bigram_bonus(ctx: &Context, word: &str) -> f64 {
        if ctx.is_empty() {
            return 0.0;
        }
        let key = fold_key(word);
        ctx.iter()
            .find(|(k, _, _)| *k == key)
            .map_or(0.0, |(_, _, c)| BIGRAM_WEIGHT * ln1p(*c))
    }

    /// First visible spelling-equivalent of `typed`, if any.
    fn known_variant(&self, typed: &str) -> bool {
        variants(typed)
            .into_iter()
            .any(|(v, _)| self.lexicon.word_score(v).is_some())
    }

    fn guard_knows(&self, typed: &str) -> bool {
        let vs = variants(typed);
        read(&self.guards)
            .iter()
            .any(|g| vs.iter().any(|(v, _)| g.contains(v.clone())))
    }

    /// Known words one edit away from `typed` (lower-cased matching, lexicon spelling), as
    /// (word, lexicon score, cheapest edit cost), cheapest first.
    fn corrections(&self, typed: &str) -> Vec<(String, u32, f64)> {
        let lower: String = typed.chars().flat_map(char::to_lowercase).collect();
        let title_typed = typed.chars().next().is_some_and(char::is_uppercase);
        let mut best: HashMap<String, (u32, f64)> = HashMap::new();
        let fk = fold_key(typed);
        for (n, cost) in neighbours(&lower) {
            if n.is_empty() || fold_key(&n) == fk {
                continue;
            }
            let mut forms = vec![n.clone()];
            if title_typed {
                forms.push(title_case(&n));
            }
            for f in forms {
                if let Some(score) = self.lexicon.word_score(f.clone()) {
                    let e = best.entry(f).or_insert((score, cost));
                    if cost < e.1 {
                        e.1 = cost;
                    }
                }
            }
        }
        let mut v: Vec<(String, u32, f64)> =
            best.into_iter().map(|(w, (s, c))| (w, s, c)).collect();
        v.sort_by(|a, b| a.2.total_cmp(&b.2).then_with(|| a.0.cmp(&b.0)));
        v
    }

    fn total(c: &Cand, ctx: &Context) -> f64 {
        let exact = if c.kind == RankKind::Exact {
            EXACT_BONUS
        } else {
            0.0
        };
        ln1p(c.uni) + Self::bigram_bonus(ctx, &c.text) + exact
            - EDIT_PENALTY * c.edit_cost
            - c.adjust
    }

    fn rank_inner(&self, typed: &str, prev: Option<&str>, limit: usize) -> Vec<(Cand, f64)> {
        let ctx = self.context(prev);
        let mut cands: Vec<Cand> = Vec::new();
        let per_variant = (limit * 2).clamp(10, 64) as u32;
        let mut have_known = false;
        for (v, pen) in variants(typed) {
            let vlen = v.chars().count();
            if let Some(score) = self.lexicon.word_score(v.clone()) {
                have_known = true;
                let text = adapt_case(typed, &v);
                let kind = if text == typed {
                    RankKind::Exact
                } else {
                    RankKind::Variant
                };
                cands.push(Cand {
                    text,
                    kind,
                    uni: score,
                    edit_cost: 0.0,
                    adjust: pen * EDIT_PENALTY,
                });
            }
            for m in self.lexicon.suggest(v, per_variant) {
                let extra = m.word.chars().count().saturating_sub(vlen);
                if extra == 0 {
                    continue; // the word equal to the variant was added above
                }
                cands.push(Cand {
                    text: adapt_case(typed, &m.word),
                    kind: RankKind::Completion,
                    uni: m.score,
                    edit_cost: 0.0,
                    adjust: pen * EDIT_PENALTY + COMPLETION_PENALTY * extra as f64,
                });
            }
        }
        if !have_known {
            for (w, score, cost) in self.corrections(typed) {
                cands.push(Cand {
                    text: adapt_case(typed, &w),
                    kind: RankKind::Correction,
                    uni: score,
                    edit_cost: cost,
                    adjust: 0.0,
                });
            }
        }
        let mut out: Vec<(Cand, f64)> = cands
            .into_iter()
            .map(|c| {
                let s = Self::total(&c, &ctx);
                (c, s)
            })
            .collect();
        out.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.text.cmp(&b.0.text)));
        // Keep the best-scoring entry per text.
        let mut seen = std::collections::HashSet::new();
        out.retain(|(c, _)| seen.insert(c.text.clone()));
        out.truncate(limit);
        out
    }

    /// Typed text eligible for automatic correction at all (before looking at candidates).
    fn eligible(typed: &str, sentence_start: bool) -> bool {
        let n = typed.chars().count();
        if !(MIN_AUTOCORRECT_CHARS..=MAX_TYPED_CHARS).contains(&n) {
            return false;
        }
        let mut it = typed.chars();
        let first_upper = it.next().is_some_and(char::is_uppercase);
        let rest_upper = it.any(char::is_uppercase);
        typed.chars().all(char::is_alphabetic) && !rest_upper && (!first_upper || sentence_start)
    }
}

#[uniffi::export]
impl Ranker {
    /// A ranker over `lexicon` with no bigram data (add it with `load_bigrams`).
    #[uniffi::constructor]
    pub fn new(lexicon: Arc<Lexicon>) -> Arc<Self> {
        Arc::new(Ranker {
            lexicon,
            guards: RwLock::new(Vec::new()),
            base: RwLock::new(BigramModel::default()),
            user: RwLock::new(BigramModel::default()),
        })
    }

    /// Loads the shipped bigram model (a `FREQUENCY_LIST` payload, "QBG1"). Fully validated;
    /// on error nothing changes.
    pub fn load_bigrams(&self, bytes: Vec<u8>) -> Result<(), RankerError> {
        let m = BigramModel::from_bytes(&bytes).map_err(bigram_error)?;
        *write(&self.base) = m;
        Ok(())
    }

    /// Lexicons of the user's other enabled languages: a typed word valid in any of them is
    /// never auto-corrected (SD 7: no "correcting" another language's tokens).
    pub fn set_guard_lexicons(&self, lexicons: Vec<Arc<Lexicon>>) {
        *write(&self.guards) = lexicons;
    }

    /// Records that `next` followed `previous` (learning). False if refused (invalid or full).
    pub fn learn_bigram(&self, previous: String, next: String) -> bool {
        write(&self.user).add(&previous, &next, 1, MAX_USER_BIGRAMS)
    }

    /// Serialises the user bigram overlay for the host to store (encrypted, local only).
    pub fn export_user_bigrams(&self) -> Vec<u8> {
        read(&self.user).to_bytes()
    }

    /// Replaces the user bigram overlay. On error nothing changes.
    pub fn import_user_bigrams(&self, bytes: Vec<u8>) -> Result<(), RankerError> {
        let m = BigramModel::from_bytes(&bytes).map_err(bigram_error)?;
        if m.pair_count() > MAX_USER_BIGRAMS {
            return Err(bigram_error("too many bigrams"));
        }
        *write(&self.user) = m;
        Ok(())
    }

    /// Forgets all learned bigrams.
    pub fn clear_user_bigrams(&self) {
        *write(&self.user) = BigramModel::default();
    }

    /// Candidates for the word being typed, best first. Empty `typed` gives next-word
    /// suggestions. `previous_word` is the committed word before it, if any.
    pub fn rank(
        &self,
        typed: String,
        previous_word: Option<String>,
        limit: u32,
    ) -> Vec<RankedCandidate> {
        let typed = nfc(&typed);
        if typed.is_empty() {
            return match previous_word {
                Some(p) => self.next_words(p, limit),
                None => self.frequent(limit),
            };
        }
        let limit = limit.min(MAX_RESULTS) as usize;
        if limit == 0 || typed.chars().count() > MAX_TYPED_CHARS {
            return Vec::new();
        }
        self.rank_inner(&typed, previous_word.as_deref(), limit)
            .into_iter()
            .map(|(c, s)| RankedCandidate {
                text: c.text,
                kind: c.kind,
                score: s as f32,
                edit_cost: c.edit_cost as f32,
            })
            .collect()
    }

    /// Likely next words after `previous_word`: bigram predictions first (shipped model plus
    /// the user's overlay), then frequent words to fill the strip.
    pub fn next_words(&self, previous_word: String, limit: u32) -> Vec<RankedCandidate> {
        let limit = limit.min(MAX_RESULTS) as usize;
        if limit == 0 {
            return Vec::new();
        }
        let prev = nfc(&previous_word);
        let mut scored: Vec<(String, f64)> = self
            .context(Some(&prev))
            .into_iter()
            .filter(|(_, next, _)| {
                // Skip words the user removed (hidden from the base lexicon).
                self.lexicon.contains(next.clone())
                    || self.lexicon.base_frequency(next.clone()).is_none()
            })
            .map(|(_, next, count)| {
                let uni = self.lexicon.word_score(next.clone()).unwrap_or(0);
                let s = ln1p(count) + NEXT_WORD_UNIGRAM_WEIGHT * ln1p(uni);
                (next, s)
            })
            .collect();
        scored.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        scored.truncate(limit);
        let mut out: Vec<RankedCandidate> = scored
            .into_iter()
            .map(|(text, s)| RankedCandidate {
                text,
                kind: RankKind::NextWord,
                score: s as f32,
                edit_cost: 0.0,
            })
            .collect();
        if out.len() < limit {
            for f in self.frequent(limit as u32 + out.len() as u32) {
                if out.len() >= limit {
                    break;
                }
                let key = fold_key(&f.text);
                if !out.iter().any(|o| fold_key(&o.text) == key) {
                    out.push(f);
                }
            }
        }
        out
    }

    /// The most frequent words, for an empty strip with no context.
    pub fn frequent(&self, limit: u32) -> Vec<RankedCandidate> {
        self.lexicon
            .suggest(String::new(), limit.min(MAX_RESULTS))
            .into_iter()
            .map(|m| RankedCandidate {
                score: ln1p(m.score) as f32,
                text: m.word,
                kind: RankKind::Frequent,
                edit_cost: 0.0,
            })
            .collect()
    }

    /// Decides what to do with `typed` when the user finishes the word. Never `Apply`s unless
    /// every harm-guard condition in the module docs holds. `sentence_start` allows a
    /// capitalised word to be corrected (otherwise it may be a name).
    pub fn autocorrect(
        &self,
        typed: String,
        previous_word: Option<String>,
        sentence_start: bool,
    ) -> AutocorrectDecision {
        let typed = nfc(&typed);
        if !Self::eligible(&typed, sentence_start) {
            return AutocorrectDecision::none(AutocorrectReason::NotEligible);
        }
        if self.known_variant(&typed) {
            return AutocorrectDecision::none(AutocorrectReason::ValidWord);
        }
        if self.guard_knows(&typed) {
            return AutocorrectDecision::none(AutocorrectReason::OtherLanguageWord);
        }
        let corrections = self.corrections(&typed);
        if corrections.is_empty() {
            return AutocorrectDecision::none(AutocorrectReason::NoCandidate);
        }
        let ctx = self.context(previous_word.as_deref());
        let scores: Vec<f64> = corrections
            .iter()
            .map(|(w, s, c)| ln1p(*s) + Self::bigram_bonus(&ctx, w) - EDIT_PENALTY * c)
            .collect();
        // Posterior over {each correction, "the typed word is intended"}.
        let max = scores.iter().copied().fold(UNKNOWN_WORD_PRIOR, f64::max);
        let denom: f64 =
            scores.iter().map(|s| (s - max).exp()).sum::<f64>() + (UNKNOWN_WORD_PRIOR - max).exp();
        let mut bi = 0;
        for (i, s) in scores.iter().enumerate() {
            if *s > scores[bi] {
                bi = i;
            }
        }
        let confidence = (scores[bi] - max).exp() / denom;
        let (word, _, cost) = &corrections[bi];
        let first_ok = fold_key(word).chars().next() == fold_key(&typed).chars().next();
        let same_script = typed.chars().all(is_ethiopic) == word.chars().all(is_ethiopic);
        let safe = confidence >= AUTOCORRECT_MIN_CONFIDENCE
            && *cost <= MAX_AUTOCORRECT_EDIT_COST
            && first_ok
            && same_script;
        let (action, reason) = if safe {
            (AutocorrectAction::Apply, AutocorrectReason::Confident)
        } else {
            (
                AutocorrectAction::SuggestOnly,
                AutocorrectReason::LowConfidence,
            )
        };
        AutocorrectDecision {
            action,
            replacement: Some(adapt_case(&typed, word)),
            confidence: confidence as f32,
            reason,
        }
    }
}

#[cfg(test)]
mod tests;
