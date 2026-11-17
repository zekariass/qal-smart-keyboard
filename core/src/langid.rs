//! Cheap on-device language / script identification for code-switching (plan step C1.8,
//! SD 7.5).
//!
//! For each typed word the detector returns a ranked list of candidate languages with
//! confidences; it never forces a single decision when the evidence is thin (plan rule:
//! ambiguity gives multiple candidates). Signals, combined as a naive-Bayes posterior:
//!
//! 1. **Script** (deterministic): Ge'ez letters give `Am`/`Ti`; Latin letters give
//!    `Om`/`En`/`AmLatn`/`TiLatn`; digits and punctuation give no candidates (the caller keeps
//!    the active language).
//! 2. **Character n-grams** (1..3) per class, from a model loaded from bytes
//!    ([`LangIdDetector::from_bytes`]).
//! 3. **Lexicon hits**: optional per-language [`Lexicon`]s ([`LangIdDetector::set_lexicon`]) add
//!    a fixed log-bonus when the word is a known word of that language.
//! 4. **Context**: the previous (up to 3) words' language evidence, decayed, shifts the prior;
//!    Ge'ez Amharic context also supports Latin-typed Amharic, and likewise for Tigrinya.
//! 5. **Enabled languages**: classes of languages the user has not enabled are excluded.
//!
//! Privacy (N1, N2): offline, no logging, no word content in errors. The shipped seed model is
//! `DRAFT`: trained on a tiny synthetic word list, not native-speaker data (human gate G0), so
//! the languages it covers must not be exposed as supported to users (N9).

mod model;

use std::sync::{Arc, RwLock};

use crate::lexicon::Lexicon;
use model::Model;

/// Maximum number of previous words that influence a decision.
pub const MAX_CONTEXT_WORDS: usize = 3;
/// Candidates below this confidence are dropped (the top candidate is always kept).
pub const MIN_CANDIDATE_CONFIDENCE: f32 = 0.08;
/// Most candidates returned.
pub const MAX_CANDIDATES: usize = 4;
/// A detection whose top confidence is below this is flagged `ambiguous`.
pub const AMBIGUITY_THRESHOLD: f32 = 0.8;

const LIKELIHOOD_SCALE: f32 = 0.7; // tempers naive-Bayes over-confidence
const LEXICON_BONUS: f32 = 3.0; // nats
const CONTEXT_WEIGHT: f32 = 0.4;
const CONTEXT_DECAY: f32 = 0.6;

/// Draft seed training data (SYNTHETIC, see `language-data/eval/langid/`).
const SEED: [(LangTag, &str); 6] = [
    (
        LangTag::Am,
        include_str!("../../language-data/eval/langid/train_am.txt"),
    ),
    (
        LangTag::Ti,
        include_str!("../../language-data/eval/langid/train_ti.txt"),
    ),
    (
        LangTag::Om,
        include_str!("../../language-data/eval/langid/train_om.txt"),
    ),
    (
        LangTag::En,
        include_str!("../../language-data/eval/langid/train_en.txt"),
    ),
    (
        LangTag::AmLatn,
        include_str!("../../language-data/eval/langid/train_am-latn.txt"),
    ),
    (
        LangTag::TiLatn,
        include_str!("../../language-data/eval/langid/train_ti-latn.txt"),
    ),
];

/// Language-ID errors. Messages are fixed strings, never typed content.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum LangIdError {
    /// A model payload is malformed or has an unknown format.
    #[error("invalid language-id model: {reason}")]
    InvalidData { reason: String },
}

/// A language as written in a script: the unit the detector ranks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum LangTag {
    /// Amharic in Ge'ez script.
    Am,
    /// Tigrinya in Ge'ez script.
    Ti,
    /// Afaan Oromoo in Latin script (Qubee).
    Om,
    /// English.
    En,
    /// Amharic typed in Latin letters (romanised).
    AmLatn,
    /// Tigrinya typed in Latin letters (romanised).
    TiLatn,
}

/// Script of a word.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScriptKind {
    Geez,
    Latin,
    /// No letters (digits, punctuation, emoji) or an unsupported script.
    Other,
}

/// Language families used to pass context across scripts.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Lang {
    Am,
    Ti,
    Om,
    En,
}

impl Lang {
    fn index(self) -> usize {
        match self {
            Lang::Am => 0,
            Lang::Ti => 1,
            Lang::Om => 2,
            Lang::En => 3,
        }
    }
}

impl LangTag {
    fn to_u8(self) -> u8 {
        match self {
            LangTag::Am => 0,
            LangTag::Ti => 1,
            LangTag::Om => 2,
            LangTag::En => 3,
            LangTag::AmLatn => 4,
            LangTag::TiLatn => 5,
        }
    }
    fn from_u8(v: u8) -> Option<LangTag> {
        Some(match v {
            0 => LangTag::Am,
            1 => LangTag::Ti,
            2 => LangTag::Om,
            3 => LangTag::En,
            4 => LangTag::AmLatn,
            5 => LangTag::TiLatn,
            _ => return None,
        })
    }
    fn script(self) -> ScriptKind {
        match self {
            LangTag::Am | LangTag::Ti => ScriptKind::Geez,
            _ => ScriptKind::Latin,
        }
    }
    fn lang(self) -> Lang {
        match self {
            LangTag::Am | LangTag::AmLatn => Lang::Am,
            LangTag::Ti | LangTag::TiLatn => Lang::Ti,
            LangTag::Om => Lang::Om,
            LangTag::En => Lang::En,
        }
    }
    /// Stable short code (`am`, `ti`, `om`, `en`, `am-latn`, `ti-latn`).
    pub fn code(self) -> &'static str {
        match self {
            LangTag::Am => "am",
            LangTag::Ti => "ti",
            LangTag::Om => "om",
            LangTag::En => "en",
            LangTag::AmLatn => "am-latn",
            LangTag::TiLatn => "ti-latn",
        }
    }
}

/// One ranked candidate language.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct LangCandidate {
    pub lang: LangTag,
    /// Posterior probability in `0..=1` among the allowed candidates.
    pub confidence: f32,
}

/// The detector's answer for one word.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LangDetection {
    pub script: ScriptKind,
    /// Ranked by confidence, descending; empty when the word has no letters or no allowed
    /// language uses its script.
    pub candidates: Vec<LangCandidate>,
    /// True when the top confidence is below [`AMBIGUITY_THRESHOLD`] or there are no candidates.
    pub ambiguous: bool,
}

/// A labelled training text (whitespace-separated words) for [`LangIdDetector::train`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LabeledText {
    pub lang: LangTag,
    pub text: String,
}

/// Per-word language identifier. Thread-safe; share as an `Arc` across the FFI.
#[derive(uniffi::Object)]
pub struct LangIdDetector {
    model: Model,
    lexicons: RwLock<Vec<(LangTag, Arc<Lexicon>)>>,
}

fn is_geez(c: char) -> bool {
    matches!(c, '\u{1200}'..='\u{139F}' | '\u{2D80}'..='\u{2DDF}' | '\u{AB00}'..='\u{AB2F}')
}

/// Normalises a token: trims non-letters from both ends, folds typographic apostrophes and
/// lower-cases. Returns the token and its script.
fn normalise(word: &str) -> (String, ScriptKind) {
    let t: String = word
        .trim_matches(|c: char| !c.is_alphabetic())
        .chars()
        .filter(|c| c.is_alphabetic() || *c == '\'' || *c == '\u{2019}')
        .map(|c| if c == '\u{2019}' { '\'' } else { c })
        .flat_map(char::to_lowercase)
        .collect();
    let geez = t.chars().filter(|c| is_geez(*c)).count();
    let latin = t.chars().filter(|c| c.is_ascii_alphabetic()).count();
    let script = if geez == 0 && latin == 0 {
        ScriptKind::Other
    } else if geez >= latin {
        ScriptKind::Geez
    } else {
        ScriptKind::Latin
    };
    (t, script)
}

fn softmax(scores: &[f32]) -> Vec<f32> {
    let max = scores.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    let exps: Vec<f32> = scores.iter().map(|s| (s - max).exp()).collect();
    let sum: f32 = exps.iter().sum();
    exps.into_iter().map(|e| e / sum).collect()
}

type Lexicons = [(LangTag, Arc<Lexicon>)];

impl LangIdDetector {
    fn from_model(model: Model) -> Arc<Self> {
        Arc::new(LangIdDetector {
            model,
            lexicons: RwLock::new(Vec::new()),
        })
    }

    /// Posterior over the `allowed` class indices for one normalised word, with an optional
    /// per-language prior mass from context.
    fn posterior(
        &self,
        word: &str,
        allowed: &[usize],
        prior: Option<&[f32; 4]>,
        lex: &Lexicons,
    ) -> Vec<f32> {
        let ll = self.model.log_likelihoods(word);
        let uniform = 1.0 / allowed.len().max(1) as f32;
        let scores: Vec<f32> = allowed
            .iter()
            .map(|&i| {
                let tag = self.model.classes[i];
                let mut s = ll[i] * LIKELIHOOD_SCALE;
                if lex
                    .iter()
                    .any(|(t, l)| *t == tag && l.contains(word.to_string()))
                {
                    s += LEXICON_BONUS;
                }
                if let Some(p) = prior {
                    let m =
                        (1.0 - CONTEXT_WEIGHT) * uniform + CONTEXT_WEIGHT * p[tag.lang().index()];
                    s += m.max(1e-6).ln();
                }
                s
            })
            .collect();
        softmax(&scores)
    }

    fn allowed(&self, script: ScriptKind, enabled: &[LangTag]) -> Vec<usize> {
        (0..self.model.classes.len())
            .filter(|&i| {
                let t = self.model.classes[i];
                t.script() == script && (enabled.is_empty() || enabled.contains(&t))
            })
            .collect()
    }

    /// Decayed per-language evidence from the previous words, renormalised over the languages
    /// reachable from the current script. `None` when there is no usable context.
    fn context_prior(
        &self,
        context: &[String],
        allowed: &[usize],
        enabled: &[LangTag],
        lex: &Lexicons,
    ) -> Option<[f32; 4]> {
        let start = context.len().saturating_sub(MAX_CONTEXT_WORDS);
        let mut mass = [0.0f32; 4];
        let mut used = false;
        for (age, cw) in context[start..].iter().rev().enumerate() {
            let (n, s) = normalise(cw);
            let a = self.allowed(s, enabled);
            if s == ScriptKind::Other || a.is_empty() {
                continue;
            }
            used = true;
            let post = self.posterior(&n, &a, None, lex);
            let weight = CONTEXT_DECAY.powi(age as i32);
            for (&ci, p) in a.iter().zip(post) {
                mass[self.model.classes[ci].lang().index()] += weight * p;
            }
        }
        if !used {
            return None;
        }
        let mut reach = [false; 4];
        for &i in allowed {
            reach[self.model.classes[i].lang().index()] = true;
        }
        let total: f32 = (0..4).filter(|&i| reach[i]).map(|i| mass[i]).sum();
        if total <= 0.0 {
            return None;
        }
        for (i, m) in mass.iter_mut().enumerate() {
            *m = if reach[i] { *m / total } else { 0.0 };
        }
        Some(mass)
    }
}

#[uniffi::export]
impl LangIdDetector {
    /// The built-in DRAFT seed model, trained at construction from a tiny synthetic word list.
    /// For development, tests and the eval harness only; real deployments load a signed
    /// `LANGUAGE_ID_MODEL` resource with [`LangIdDetector::from_bytes`] (N9, gate G0).
    #[uniffi::constructor]
    pub fn draft_default() -> Arc<Self> {
        Self::train(
            SEED.iter()
                .map(|(lang, text)| LabeledText {
                    lang: *lang,
                    text: (*text).to_string(),
                })
                .collect(),
        )
    }

    /// Trains a model from labelled word lists (build tooling and tests). Lines starting with
    /// `#` are comments; words in the wrong script for their label are skipped.
    #[uniffi::constructor]
    pub fn train(texts: Vec<LabeledText>) -> Arc<Self> {
        let mut samples: Vec<(LangTag, String)> = Vec::new();
        for lt in &texts {
            for line in lt.text.lines().filter(|l| !l.starts_with('#')) {
                for w in line.split_whitespace() {
                    let (n, script) = normalise(w);
                    if !n.is_empty() && script == lt.lang.script() {
                        samples.push((lt.lang, n));
                    }
                }
            }
        }
        Self::from_model(Model::train(&samples))
    }

    /// Loads a serialised model. Validates everything; malformed input is an error, never a panic.
    #[uniffi::constructor]
    pub fn from_bytes(bytes: Vec<u8>) -> Result<Arc<Self>, LangIdError> {
        Ok(Self::from_model(Model::from_bytes(&bytes)?))
    }

    /// Serialises the n-gram model (not the lexicons).
    pub fn to_bytes(&self) -> Vec<u8> {
        self.model.to_bytes()
    }

    /// Attaches (or replaces) the lexicon whose hits support `lang`.
    pub fn set_lexicon(&self, lang: LangTag, lexicon: Arc<Lexicon>) {
        let mut g = self.lexicons.write().unwrap_or_else(|e| e.into_inner());
        g.retain(|(t, _)| *t != lang);
        g.push((lang, lexicon));
    }

    /// Ranks candidate languages for `word`.
    ///
    /// * `context`: the words typed before it (oldest first; only the last
    ///   [`MAX_CONTEXT_WORDS`] are used).
    /// * `enabled`: languages the user enabled; empty means all.
    pub fn detect_word(
        &self,
        word: String,
        context: Vec<String>,
        enabled: Vec<LangTag>,
    ) -> LangDetection {
        let lex = self
            .lexicons
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let (w, script) = normalise(&word);
        let allowed = self.allowed(script, &enabled);
        if script == ScriptKind::Other || allowed.is_empty() {
            return LangDetection {
                script,
                candidates: Vec::new(),
                ambiguous: true,
            };
        }
        let prior = self.context_prior(&context, &allowed, &enabled, &lex);
        let post = self.posterior(&w, &allowed, prior.as_ref(), &lex);
        let mut ranked: Vec<LangCandidate> = allowed
            .iter()
            .zip(post)
            .map(|(&i, confidence)| LangCandidate {
                lang: self.model.classes[i],
                confidence,
            })
            .collect();
        ranked.sort_by(|a, b| {
            b.confidence
                .total_cmp(&a.confidence)
                .then(a.lang.to_u8().cmp(&b.lang.to_u8()))
        });
        let top = ranked[0].confidence;
        let candidates: Vec<LangCandidate> = ranked
            .into_iter()
            .enumerate()
            .filter(|(i, c)| *i == 0 || c.confidence >= MIN_CANDIDATE_CONFIDENCE)
            .map(|(_, c)| c)
            .take(MAX_CANDIDATES)
            .collect();
        LangDetection {
            script,
            candidates,
            ambiguous: top < AMBIGUITY_THRESHOLD,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn det() -> Arc<LangIdDetector> {
        LangIdDetector::draft_default()
    }

    fn conf(r: &LangDetection, t: LangTag) -> f32 {
        r.candidates
            .iter()
            .find(|c| c.lang == t)
            .map_or(0.0, |c| c.confidence)
    }

    #[test]
    fn script_is_deterministic() {
        let d = det();
        let r = d.detect_word("ሰላም".into(), vec![], vec![]);
        assert_eq!(r.script, ScriptKind::Geez);
        assert!(r
            .candidates
            .iter()
            .all(|c| matches!(c.lang, LangTag::Am | LangTag::Ti)));
        let r = d.detect_word("hello".into(), vec![], vec![]);
        assert_eq!(r.script, ScriptKind::Latin);
        assert!(r
            .candidates
            .iter()
            .all(|c| !matches!(c.lang, LangTag::Am | LangTag::Ti)));
    }

    #[test]
    fn no_letters_gives_no_candidates() {
        let r = det().detect_word("123".into(), vec![], vec![]);
        assert_eq!(r.script, ScriptKind::Other);
        assert!(r.candidates.is_empty() && r.ambiguous);
    }

    #[test]
    fn confidences_sum_to_at_most_one_and_are_sorted() {
        let r = det().detect_word("selam".into(), vec![], vec![]);
        let sum: f32 = r.candidates.iter().map(|c| c.confidence).sum();
        assert!(sum <= 1.0001);
        assert!(r
            .candidates
            .windows(2)
            .all(|w| w[0].confidence >= w[1].confidence));
    }

    #[test]
    fn shared_romanised_word_keeps_both_candidates() {
        // "selam" is in both the Amharic and Tigrinya romanised seeds.
        let r = det().detect_word("selam".into(), vec![], vec![]);
        assert!(r.candidates.iter().any(|c| c.lang == LangTag::TiLatn));
        assert!(r.candidates.len() >= 2);
    }

    #[test]
    fn enabled_languages_restrict_candidates() {
        let r = det().detect_word("selam".into(), vec![], vec![LangTag::En, LangTag::Om]);
        assert!(r
            .candidates
            .iter()
            .all(|c| matches!(c.lang, LangTag::En | LangTag::Om)));
        let r = det().detect_word("ሰላም".into(), vec![], vec![LangTag::En]);
        assert!(r.candidates.is_empty());
    }

    #[test]
    fn context_shifts_the_prior() {
        let d = det();
        let plain = d.detect_word("selam".into(), vec![], vec![]);
        let ti_ctx = d.detect_word("selam".into(), vec!["ከመይ".into(), "ኣለኻ".into()], vec![]);
        assert!(conf(&ti_ctx, LangTag::TiLatn) > conf(&plain, LangTag::TiLatn));
    }

    #[test]
    fn lexicon_hit_boosts_language() {
        let d = det();
        let before = d.detect_word("zzqx".into(), vec![], vec![]);
        d.set_lexicon(LangTag::Om, Lexicon::from_wordlist_text("zzqx\n".into()));
        let after = d.detect_word("zzqx".into(), vec![], vec![]);
        assert!(conf(&after, LangTag::Om) > conf(&before, LangTag::Om));
        assert_eq!(after.candidates[0].lang, LangTag::Om);
    }

    #[test]
    fn model_bytes_round_trip() {
        let d = det();
        let bytes = d.to_bytes();
        let d2 = LangIdDetector::from_bytes(bytes.clone()).unwrap();
        assert_eq!(d2.to_bytes(), bytes);
        for w in ["ሰላም", "akkam", "hello", "betam", "ኣብ"] {
            assert_eq!(
                d.detect_word(w.into(), vec![], vec![]),
                d2.detect_word(w.into(), vec![], vec![])
            );
        }
    }

    #[test]
    fn malformed_models_are_rejected_not_panics() {
        let good = det().to_bytes();
        assert!(LangIdDetector::from_bytes(vec![]).is_err());
        assert!(LangIdDetector::from_bytes(b"XXXX".to_vec()).is_err());
        for cut in [3, 5, 9, good.len() / 2, good.len() - 1] {
            assert!(LangIdDetector::from_bytes(good[..cut].to_vec()).is_err());
        }
        let mut extra = good.clone();
        extra.push(0);
        assert!(LangIdDetector::from_bytes(extra).is_err());
        let mut bad_ver = good;
        bad_ver[4] = 99;
        assert!(LangIdDetector::from_bytes(bad_ver).is_err());
        for seed in 0u8..64 {
            let junk: Vec<u8> = (0..200u32)
                .map(|i| (i as u8).wrapping_mul(seed).wrapping_add(7))
                .collect();
            let _ = LangIdDetector::from_bytes(junk);
        }
    }
}
