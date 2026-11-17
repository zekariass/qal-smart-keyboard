//! Compact trie lexicon with prefix lookup and a user-dictionary overlay (plan step C1.6).
//!
//! * **Base lexicon**: an immutable radix trie in one flat byte buffer (`trie` submodule). The
//!   buffer is the payload of a `DICTIONARY` pack artifact: build it with
//!   [`Lexicon::from_entries`] / [`Lexicon::from_wordlist_text`] (L1.1 tooling), ship it inside a
//!   signed pack, and load it with [`Lexicon::from_bytes`], which validates the whole structure
//!   (so a malformed payload is rejected, never a panic). [`Lexicon::to_bytes`] returns it.
//! * **User overlay**: words the user added or the keyboard learned, plus words the user removed
//!   (which also hides them from the base lexicon). Local only: the core never writes files, so
//!   the host persists the opaque bytes from [`Lexicon::export_user_dictionary`] (encrypted at
//!   rest, A1.8) and restores them with [`Lexicon::import_user_dictionary`].
//!
//! Privacy (N1, N2): no function here logs, and no error text contains words. Everything is
//! in-process and offline; lookups never touch disk or network (SD 17.1).
//!
//! Ranking here is only the raw lexicon order: score descending, then word ascending (stable
//! and deterministic). Contextual ranking and autocorrect belong to the ranker (C1.7).

mod trie;
mod user;

use std::sync::{Arc, RwLock};

use unicode_normalization::UnicodeNormalization;

use user::UserState;

/// Longest word accepted by the lexicon, in bytes after NFC (longer words are ignored).
pub const MAX_WORD_BYTES: usize = trie::MAX_WORD_BYTES;
/// Most words the user overlay holds; further additions are refused.
pub const MAX_USER_WORDS: usize = 50_000;
/// How much each use of a user word adds to its score. The ranker scores on a log scale, so one
/// use lifts a word to roughly "moderately common" and about a thousand uses to "top of the
/// list"; tuned with C1.7 (pinned by `ranker` tests; revisit when real pack frequencies exist).
pub const USER_WORD_WEIGHT: u32 = 1_000;

/// Errors from lexicon payload handling. Messages are fixed strings, never word content.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum LexiconError {
    /// A lexicon or user-dictionary payload is malformed or has an unknown format.
    #[error("invalid lexicon data: {reason}")]
    InvalidData { reason: String },
}

/// One word with its corpus frequency, for building a lexicon.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WordEntry {
    pub word: String,
    pub frequency: u32,
}

/// Where a lookup result came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum LexiconSource {
    /// Only in the shipped base lexicon.
    Base,
    /// In the user dictionary (possibly also in the base lexicon).
    User,
}

/// One prefix-lookup result.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LexiconMatch {
    pub word: String,
    /// Base frequency plus `USER_WORD_WEIGHT` per user use. Only comparable within a lexicon.
    pub score: u32,
    pub source: LexiconSource,
}

/// Base lexicon plus user overlay. Thread-safe; share as an `Arc` across the FFI.
#[derive(uniffi::Object)]
pub struct Lexicon {
    base: trie::Trie,
    user: RwLock<UserState>,
}

fn nfc(s: &str) -> String {
    s.nfc().collect()
}

/// Words are single tokens: non-empty, bounded, no whitespace or control characters.
fn valid_user_word(w: &str) -> bool {
    !w.is_empty()
        && w.len() <= MAX_WORD_BYTES
        && !w.chars().any(|c| c.is_whitespace() || c.is_control())
}

impl Lexicon {
    fn from_trie(base: trie::Trie) -> Arc<Self> {
        Arc::new(Lexicon {
            base,
            user: RwLock::new(UserState::default()),
        })
    }

    fn built(entries: impl IntoIterator<Item = (String, u32)>) -> Arc<Self> {
        // Our own builder output always validates.
        let base = trie::Trie::from_bytes(trie::build(entries)).expect("builder output is valid");
        Self::from_trie(base)
    }

    fn user_read(&self) -> std::sync::RwLockReadGuard<'_, UserState> {
        self.user.read().unwrap_or_else(|e| e.into_inner())
    }

    fn user_write(&self) -> std::sync::RwLockWriteGuard<'_, UserState> {
        self.user.write().unwrap_or_else(|e| e.into_inner())
    }

    fn bump(&self, word: String, by: u32) -> bool {
        let word = nfc(&word);
        valid_user_word(&word) && self.user_write().add(word, by)
    }
}

#[uniffi::export]
impl Lexicon {
    /// An empty lexicon (user overlay only).
    #[uniffi::constructor]
    pub fn new_empty() -> Arc<Self> {
        Self::built(std::iter::empty())
    }

    /// Builds from word/frequency entries. Words are NFC-normalised; duplicates keep the
    /// highest frequency; empty, over-long and `u32::MAX`-frequency entries are dropped.
    #[uniffi::constructor]
    pub fn from_entries(entries: Vec<WordEntry>) -> Arc<Self> {
        Self::built(entries.into_iter().map(|e| (e.word, e.frequency)))
    }

    /// Builds from text, one entry per line: `word` or `word<TAB>frequency` (a missing or
    /// unparsable frequency counts as 1). Blank lines and lines starting with `#` are skipped.
    #[uniffi::constructor]
    pub fn from_wordlist_text(text: String) -> Arc<Self> {
        let it = text.lines().filter_map(|line| {
            let line = line.trim_end_matches('\r');
            if line.trim().is_empty() || line.starts_with('#') {
                return None;
            }
            let (w, f) = line.split_once('\t').unwrap_or((line, ""));
            Some((w.trim().to_string(), f.trim().parse::<u32>().unwrap_or(1)))
        });
        Self::built(it)
    }

    /// Loads a serialised base lexicon (a `DICTIONARY` pack artifact). Fully validated.
    #[uniffi::constructor]
    pub fn from_bytes(bytes: Vec<u8>) -> Result<Arc<Self>, LexiconError> {
        let base = trie::Trie::from_bytes(bytes).map_err(|reason| LexiconError::InvalidData {
            reason: reason.to_string(),
        })?;
        Ok(Self::from_trie(base))
    }

    /// The serialised base lexicon (without the user overlay), for packaging.
    pub fn to_bytes(&self) -> Vec<u8> {
        self.base.as_bytes().to_vec()
    }

    /// Number of words in the base lexicon.
    pub fn base_word_count(&self) -> u32 {
        self.base.word_count()
    }

    /// Up to `limit` words beginning with `prefix` (NFC-normalised, case-sensitive), best
    /// first. Merges the base lexicon with the user overlay and hides removed words.
    pub fn suggest(&self, prefix: String, limit: u32) -> Vec<LexiconMatch> {
        let limit = limit as usize;
        if limit == 0 {
            return Vec::new();
        }
        let prefix = nfc(&prefix);
        let user = self.user_read();
        // User-known words are re-added below with their boosted score.
        let mut found: Vec<LexiconMatch> = self
            .base
            .prefix(&prefix, limit, &|w| {
                user.is_blocked(w) || user.count(w).is_some()
            })
            .into_iter()
            .map(|(word, score)| LexiconMatch {
                word,
                score,
                source: LexiconSource::Base,
            })
            .collect();
        for (word, count) in user.with_prefix(&prefix) {
            let base = self.base.frequency(word).unwrap_or(0);
            found.push(LexiconMatch {
                word: word.to_string(),
                score: base.saturating_add(count.saturating_mul(USER_WORD_WEIGHT)),
                source: LexiconSource::User,
            });
        }
        found.sort_by(|a, b| b.score.cmp(&a.score).then_with(|| a.word.cmp(&b.word)));
        found.truncate(limit);
        found
    }

    /// Whether `word` is currently a known word (user dictionary, or base and not removed).
    pub fn contains(&self, word: String) -> bool {
        let word = nfc(&word);
        let user = self.user_read();
        user.count(&word).is_some()
            || (!user.is_blocked(&word) && self.base.frequency(&word).is_some())
    }

    /// Base-lexicon frequency of `word`, if present (ignores the user overlay).
    pub fn base_frequency(&self, word: String) -> Option<u32> {
        self.base.frequency(&nfc(&word))
    }

    /// The score `suggest` gives `word` if it is currently visible (user words: base plus
    /// `USER_WORD_WEIGHT` per use; removed words: `None`). Exact match only (added for C1.7).
    pub fn word_score(&self, word: String) -> Option<u32> {
        let word = nfc(&word);
        let user = self.user_read();
        match user.count(&word) {
            Some(count) => Some(
                self.base
                    .frequency(&word)
                    .unwrap_or(0)
                    .saturating_add(count.saturating_mul(USER_WORD_WEIGHT)),
            ),
            None if user.is_blocked(&word) => None,
            None => self.base.frequency(&word),
        }
    }

    /// Adds a word to the user dictionary (also un-removes it). Returns false if the word is
    /// invalid (empty, too long, whitespace/control characters) or the overlay is full.
    pub fn add_user_word(&self, word: String) -> bool {
        self.bump(word, 0)
    }

    /// Records one use of a word (learning): adds it if new, otherwise raises its count.
    /// Same refusal rules as `add_user_word`.
    pub fn learn_word(&self, word: String) -> bool {
        self.bump(word, 1)
    }

    /// Removes a word from suggestions: drops it from the user dictionary and hides it from the
    /// base lexicon. Returns true if the word was visible before.
    pub fn remove_word(&self, word: String) -> bool {
        let word = nfc(&word);
        if word.is_empty() || word.len() > MAX_WORD_BYTES {
            return false;
        }
        let in_base = self.base.frequency(&word).is_some();
        self.user_write().remove(word, in_base)
    }

    /// Number of words in the user dictionary.
    pub fn user_word_count(&self) -> u32 {
        self.user_read().len() as u32
    }

    /// Forgets everything the user added, learned or removed.
    pub fn clear_user_dictionary(&self) {
        *self.user_write() = UserState::default();
    }

    /// Serialises the user overlay for the host to store (encrypted, local only).
    pub fn export_user_dictionary(&self) -> Vec<u8> {
        self.user_read().to_bytes()
    }

    /// Replaces the user overlay with previously exported bytes. On error nothing changes.
    pub fn import_user_dictionary(&self, bytes: Vec<u8>) -> Result<(), LexiconError> {
        let state = UserState::from_bytes(&bytes).map_err(|reason| LexiconError::InvalidData {
            reason: reason.to_string(),
        })?;
        *self.user_write() = state;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lex(words: &[(&str, u32)]) -> Arc<Lexicon> {
        Lexicon::from_entries(
            words
                .iter()
                .map(|(w, f)| WordEntry {
                    word: (*w).into(),
                    frequency: *f,
                })
                .collect(),
        )
    }

    fn words(m: &[LexiconMatch]) -> Vec<&str> {
        m.iter().map(|x| x.word.as_str()).collect()
    }

    #[test]
    fn prefix_order_is_frequency_then_alphabetical() {
        let l = lex(&[
            ("cart", 5),
            ("car", 10),
            ("cat", 5),
            ("cab", 5),
            ("dog", 99),
        ]);
        assert_eq!(
            words(&l.suggest("ca".into(), 10)),
            ["car", "cab", "cart", "cat"]
        );
        assert_eq!(words(&l.suggest("ca".into(), 2)), ["car", "cab"]);
        assert_eq!(words(&l.suggest("".into(), 1)), ["dog"]);
        assert!(l.suggest("x".into(), 5).is_empty());
        assert!(l.suggest("ca".into(), 0).is_empty());
        assert_eq!(words(&l.suggest("car".into(), 5)), ["car", "cart"]);
        assert!(l.suggest("carts".into(), 5).is_empty());
    }

    #[test]
    fn geez_words_and_mid_label_prefixes() {
        let l = lex(&[("ሰላም", 100), ("ሰላማዊ", 40), ("ሰማይ", 60), ("አማርኛ", 80)]);
        assert_eq!(words(&l.suggest("ሰ".into(), 10)), ["ሰላም", "ሰማይ", "ሰላማዊ"]);
        assert_eq!(words(&l.suggest("ሰላ".into(), 10)), ["ሰላም", "ሰላማዊ"]);
        assert_eq!(l.base_frequency("አማርኛ".into()), Some(80));
        assert_eq!(
            l.base_frequency("አማ".into()),
            None,
            "a prefix is not a word"
        );
        assert_eq!(l.base_word_count(), 4);
    }

    #[test]
    fn nfc_and_dedupe_and_limits() {
        // "e" + combining acute normalises to the precomposed form.
        let l = lex(&[
            ("cafe\u{301}", 3),
            ("caf\u{e9}", 9),
            ("", 5),
            (&"a".repeat(100), 1),
        ]);
        assert_eq!(l.base_word_count(), 1);
        assert_eq!(l.base_frequency("caf\u{e9}".into()), Some(9));
        assert_eq!(words(&l.suggest("cafe\u{301}".into(), 5)), ["caf\u{e9}"]);
    }

    #[test]
    fn empty_lexicon_round_trips() {
        let l = Lexicon::new_empty();
        assert!(l.suggest("a".into(), 5).is_empty());
        let l2 = Lexicon::from_bytes(l.to_bytes()).unwrap();
        assert_eq!(l2.base_word_count(), 0);
    }

    #[test]
    fn serialisation_round_trip_is_identical() {
        let l = lex(&[("a", 1), ("ab", 2), ("abc", 3), ("b", 4), ("ሰላም", 5)]);
        let bytes = l.to_bytes();
        let l2 = Lexicon::from_bytes(bytes.clone()).unwrap();
        assert_eq!(l2.to_bytes(), bytes);
        assert_eq!(l.suggest("a".into(), 9), l2.suggest("a".into(), 9));
    }

    #[test]
    fn text_wordlist_parsing() {
        let l = Lexicon::from_wordlist_text("# c\nhello\t7\r\nworld\n\nhelp\tx\n".into());
        assert_eq!(l.base_frequency("hello".into()), Some(7));
        assert_eq!(l.base_frequency("world".into()), Some(1));
        assert_eq!(l.base_frequency("help".into()), Some(1));
        assert_eq!(l.base_word_count(), 3);
    }

    #[test]
    fn malformed_payloads_are_rejected_without_panics() {
        let good = lex(&[("car", 3), ("cat", 2), ("dog", 1), ("ሰላም", 9)]).to_bytes();
        assert!(Lexicon::from_bytes(Vec::new()).is_err());
        assert!(Lexicon::from_bytes(b"QLX1".to_vec()).is_err());
        // Every truncation and every single-byte corruption either fails or yields a lexicon
        // that answers queries safely.
        for n in 0..good.len() {
            let _ = Lexicon::from_bytes(good[..n].to_vec()).map(|l| l.suggest("c".into(), 5));
        }
        for i in 0..good.len() {
            for delta in [1u8, 0x80, 0xff] {
                let mut b = good.clone();
                b[i] ^= delta;
                if let Ok(l) = Lexicon::from_bytes(b) {
                    let _ = l.suggest("".into(), 50);
                    let _ = l.suggest("c".into(), 50);
                    let _ = l.contains("car".into());
                }
            }
        }
    }

    #[test]
    fn user_add_learn_remove_overlay() {
        let l = lex(&[("hello", 10), ("help", 20), ("helm", 5)]);
        assert!(!l.contains("qal".into()));
        assert!(l.add_user_word("qal".into()));
        assert!(l.contains("qal".into()));
        assert_eq!(l.suggest("q".into(), 5)[0].source, LexiconSource::User);
        // Learning raises a base word above others and marks it as user-known.
        assert!(l.learn_word("helm".into()));
        let m = l.suggest("hel".into(), 5);
        assert_eq!(m[0].word, "helm");
        assert_eq!(m[0].score, 5 + USER_WORD_WEIGHT);
        assert_eq!(m.iter().filter(|x| x.word == "helm").count(), 1);
        // Removing hides base words and drops user ones; re-adding restores.
        assert!(l.remove_word("help".into()));
        assert!(!l.contains("help".into()));
        assert!(!words(&l.suggest("hel".into(), 5)).contains(&"help"));
        assert!(l.remove_word("qal".into()));
        assert!(!l.contains("qal".into()));
        assert!(!l.remove_word("never-seen".into()));
        assert!(l.add_user_word("help".into()));
        assert!(l.contains("help".into()));
        // The base payload is untouched by the overlay.
        assert_eq!(l.base_frequency("help".into()), Some(20));
    }

    #[test]
    fn removed_words_do_not_shrink_result_count() {
        let l = lex(&[("aa", 9), ("ab", 8), ("ac", 7), ("ad", 6)]);
        l.remove_word("aa".into());
        l.remove_word("ab".into());
        assert_eq!(words(&l.suggest("a".into(), 2)), ["ac", "ad"]);
    }

    #[test]
    fn user_word_validation_and_capacity() {
        let l = Lexicon::new_empty();
        assert!(!l.add_user_word(String::new()));
        assert!(!l.add_user_word("two words".into()));
        assert!(!l.add_user_word("tab\t".into()));
        assert!(!l.add_user_word("x".repeat(MAX_WORD_BYTES + 1)));
        assert_eq!(l.user_word_count(), 0);
        for i in 0..MAX_USER_WORDS {
            assert!(l.add_user_word(format!("w{i}")));
        }
        assert!(!l.add_user_word("overflow".into()));
        assert!(
            l.learn_word("w1".into()),
            "existing words can still be learned"
        );
        l.clear_user_dictionary();
        assert_eq!(l.user_word_count(), 0);
    }

    #[test]
    fn user_dictionary_export_import() {
        let l = lex(&[("hello", 10)]);
        l.add_user_word("qal".into());
        l.learn_word("qal".into());
        l.learn_word("ሰላም".into());
        l.remove_word("hello".into());
        let bytes = l.export_user_dictionary();
        let l2 = lex(&[("hello", 10)]);
        l2.import_user_dictionary(bytes.clone()).unwrap();
        assert!(!l2.contains("hello".into()));
        assert!(l2.contains("ሰላም".into()));
        assert_eq!(l2.suggest("q".into(), 1)[0].score, USER_WORD_WEIGHT);
        assert_eq!(l2.export_user_dictionary(), bytes);
        // Bad input leaves the overlay unchanged.
        assert!(l2.import_user_dictionary(vec![1, 2, 3]).is_err());
        assert!(l2.contains("qal".into()));
        for n in 0..bytes.len() {
            let _ = Lexicon::new_empty().import_user_dictionary(bytes[..n].to_vec());
        }
    }

    #[test]
    fn word_score_matches_suggest_and_respects_overlay() {
        let l = lex(&[("hello", 10), ("help", 20)]);
        assert_eq!(l.word_score("hello".into()), Some(10));
        assert_eq!(l.word_score("nope".into()), None);
        l.learn_word("hello".into());
        l.learn_word("qal".into());
        assert_eq!(l.word_score("hello".into()), Some(10 + USER_WORD_WEIGHT));
        assert_eq!(l.word_score("qal".into()), Some(USER_WORD_WEIGHT));
        l.remove_word("help".into());
        assert_eq!(l.word_score("help".into()), None);
        for m in l.suggest(String::new(), 10) {
            assert_eq!(l.word_score(m.word), Some(m.score));
        }
    }

    #[test]
    fn errors_carry_no_word_content() {
        let e = Lexicon::from_bytes(b"secret-typed-text".to_vec())
            .err()
            .unwrap();
        assert!(!e.to_string().contains("secret"));
    }
}

#[cfg(test)]
mod differential {
    use super::*;
    use proptest::prelude::*;

    proptest! {
        /// Trie prefix search equals a naive sort-and-filter over the same entries.
        #[test]
        fn matches_naive_model(
            raw in proptest::collection::vec(("[abሰላም]{1,6}", 0u32..50), 0..80),
            prefix in "[abሰላም]{0,3}",
            limit in 0u32..12,
        ) {
            let mut model: std::collections::BTreeMap<String, u32> = Default::default();
            for (w, f) in &raw {
                let e = model.entry(w.clone()).or_insert(0);
                *e = (*e).max(*f);
            }
            let lex = Lexicon::from_entries(
                raw.iter().map(|(w, f)| WordEntry { word: w.clone(), frequency: *f }).collect(),
            );
            let mut want: Vec<(String, u32)> =
                model.into_iter().filter(|(w, _)| w.starts_with(&prefix)).collect();
            want.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
            want.truncate(limit as usize);
            let got: Vec<(String, u32)> = lex
                .suggest(prefix.clone(), limit)
                .into_iter()
                .map(|m| (m.word, m.score))
                .collect();
            prop_assert_eq!(got, want);
            prop_assert_eq!(Lexicon::from_bytes(lex.to_bytes()).unwrap().to_bytes(), lex.to_bytes());
        }
    }
}
