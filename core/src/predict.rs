//! Shared pieces of word prediction (ADR 0039): host options, the word context that follows the
//! committed text, the word-mode scripts, and the spacing rules around picked suggestions.
//!
//! Everything here is in memory only. The context holds at most a few dozen characters of
//! committed text (the previous word, the letters of the current word) for ranking; it is never
//! logged, never persisted and cleared on `reset`, mode switches and deletions it cannot follow.

use crate::engine::EditorUpdate;
use crate::latin::Orthography;
use unicode_segmentation::UnicodeSegmentation;

/// The host's choices for word prediction in the current field (ADR 0039).
///
/// Defaults keep the behaviour hosts had before ADR 0039 (suggestions while composing, nothing
/// else); the keyboard sets the options for every field from the user's settings and the field
/// type (never in password fields, never autocorrection in e-mail or URL fields).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PredictionOptions {
    /// Word candidates for the word being typed (completions, corrections, Fidel completions).
    pub suggestions: bool,
    /// Next-word predictions after a word boundary, from the bigram pack and the user's own
    /// word pairs.
    pub next_word: bool,
    /// Apply a confident correction when a word ends with a space or sentence punctuation
    /// (English and Afaan Oromoo word modes only; the ranker's harm guard decides).
    pub autocorrect: bool,
}

impl Default for PredictionOptions {
    fn default() -> Self {
        Self {
            suggestions: true,
            next_word: false,
            autocorrect: false,
        }
    }
}

/// Longest word kept as ranking context (the ranker's typed-word bound).
pub(crate) const MAX_CONTEXT_CHARS: usize = 48;

/// Most characters of host text read by `set_cursor_context` (the tail of the text before the
/// cursor is enough to find the previous word and the sentence state).
pub(crate) const MAX_CURSOR_CONTEXT_CHARS: usize = 96;

/// The last committed word, kept in memory only for bigram ranking: never logged, never
/// persisted, cleared by `reset`, `set_input_mode` and any deletion it cannot follow.
#[derive(Debug, Default)]
pub(crate) struct WordContext {
    /// The last complete word before the cursor (`None` at the start or after a sentence end).
    pub previous: Option<String>,
    /// Letters committed since the last word boundary.
    pub current: String,
    /// The cursor is at the start of a sentence: after `.`, `!`, `?`, `።`, `፧`, `؟`, a line
    /// break, or at the start of the field. False when unknown.
    pub sentence_start: bool,
    /// The last committed character, `None` when unknown.
    pub last: Option<char>,
}

impl WordContext {
    pub fn clear(&mut self) {
        self.previous = None;
        self.current.clear();
        self.sentence_start = false;
        self.last = None;
    }

    /// A context rebuilt from the host's text before the cursor (`set_cursor_context`).
    pub fn from_text(before: &str) -> Self {
        let mut c = WordContext::default();
        let start = before
            .char_indices()
            .rev()
            .nth(MAX_CURSOR_CONTEXT_CHARS.saturating_sub(1))
            .map_or(0, |(i, _)| i);
        let tail = &before[start..];
        if before.trim().is_empty() {
            c.sentence_start = true;
        }
        c.observe(&EditorUpdate {
            commit: tail.to_string(),
            ..Default::default()
        });
        // A tail cut inside a word does not know where that word began.
        if start > 0 && !c.current.is_empty() && !tail.chars().any(char::is_whitespace) {
            c.current.clear();
        }
        c
    }

    /// Follows the text the host commits. Whitespace and the Ethiopic wordspace end a word;
    /// sentence punctuation starts a new sentence; any other punctuation ends the context.
    pub fn observe(&mut self, update: &EditorUpdate) {
        if update.delete_before > 0 {
            self.delete(update.delete_before);
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
                self.sentence_start = false;
            } else if c == '\n' {
                self.clear();
                self.sentence_start = true;
            } else if c.is_whitespace() || c == '\u{1361}' {
                let word = self.current.trim_end_matches(is_word_apostrophe);
                if !word.is_empty() {
                    self.previous = Some(word.to_string());
                }
                self.current.clear();
            } else if is_sentence_end(c) {
                self.clear();
                self.sentence_start = true;
            } else {
                self.clear();
            }
            self.last = Some(c);
        }
    }

    /// Deletes `n` grapheme clusters before the cursor: inside the current word they are
    /// followed exactly; anything further back forgets the context (the host's text is unknown).
    fn delete(&mut self, n: u32) {
        let n = usize::try_from(n).unwrap_or(usize::MAX);
        let clusters: Vec<&str> = self.current.graphemes(true).collect();
        if n <= clusters.len() && n > 0 {
            let keep: String = clusters[..clusters.len() - n].concat();
            self.current = keep;
            self.last = self.current.chars().last();
            self.sentence_start = false;
        } else {
            self.clear();
        }
    }

    /// A word that starts now begins at a word boundary: after whitespace, an opening bracket or
    /// quote, or at an unknown position. Words glued to `@`, `/`, `.`, digits and the like are
    /// parts of addresses, numbers or URLs and are never autocorrected.
    pub fn at_word_boundary(&self) -> bool {
        self.current.is_empty()
            && self
                .last
                .is_none_or(|c| c.is_whitespace() || is_opening(c) || c == '\u{1361}')
    }
}

/// The apostrophe characters a Latin word may contain (U+0027, U+2019, U+02BC; `crate::latin`).
pub(crate) fn is_word_apostrophe(c: char) -> bool {
    matches!(c, '\'' | '\u{2019}' | '\u{02BC}')
}

pub(crate) fn is_combining_mark(c: char) -> bool {
    matches!(c, '\u{0300}'..='\u{036F}' | '\u{135D}'..='\u{135F}') || is_arabic_mark(c)
}

/// Arabic harakat, shadda, sukun, superscript alef and the other Arabic combining marks.
pub(crate) fn is_arabic_mark(c: char) -> bool {
    matches!(
        c,
        '\u{0610}'..='\u{061A}'
            | '\u{064B}'..='\u{065F}'
            | '\u{0670}'
            | '\u{06D6}'..='\u{06DC}'
            | '\u{06DF}'..='\u{06E4}'
            | '\u{06E7}'..='\u{06E8}'
            | '\u{06EA}'..='\u{06ED}'
    )
}

/// A character of an Arabic word: an Arabic letter (incl. tatweel) or an Arabic mark.
pub(crate) fn is_arabic_word_char(c: char) -> bool {
    is_arabic_mark(c)
        || (c.is_alphabetic()
            && matches!(c, '\u{0620}'..='\u{064A}' | '\u{066E}'..='\u{06D3}' | '\u{06D5}' | '\u{06EE}'..='\u{06FF}' | '\u{0750}'..='\u{077F}' | '\u{08A0}'..='\u{08FF}'))
}

/// Sentence-final punctuation: Latin, Ethiopic (። ፧) and Arabic (؟ ۔).
pub(crate) fn is_sentence_end(c: char) -> bool {
    matches!(
        c,
        '.' | '!' | '?' | '\u{1362}' | '\u{1367}' | '\u{061F}' | '\u{06D4}'
    )
}

fn is_opening(c: char) -> bool {
    matches!(
        c,
        '(' | '[' | '{' | '"' | '\'' | '\u{201C}' | '\u{2018}' | '\u{00AB}' | '\u{2039}'
    )
}

/// Punctuation that attaches to the word before it: typed right after the space the engine added
/// behind a picked suggestion, it replaces that space (`word ` + `,` gives `word,`).
pub(crate) fn attaches_to_word(text: &str) -> bool {
    let mut it = text.chars();
    let (Some(c), None) = (it.next(), it.next()) else {
        return false;
    };
    matches!(
        c,
        '.' | ','
            | '!'
            | '?'
            | ';'
            | ':'
            | ')'
            | ']'
            | '}'
            | '%'
            | '\u{201D}'
            | '\u{2019}'
            | '\u{00BB}'
            | '\u{1361}'..='\u{1368}' | '\u{060C}' | '\u{061B}' | '\u{061F}' | '\u{06D4}'
    )
}

/// Punctuation that ends a word and may trigger an autocorrection (a space does too).
pub(crate) fn ends_sentence_part(text: &str) -> bool {
    let mut it = text.chars();
    matches!(
        (it.next(), it.next()),
        (Some('.' | ',' | '!' | '?' | ';' | ':'), None)
    )
}

/// Number of extended grapheme clusters (what the host deletes per `delete_before` unit).
pub(crate) fn graphemes(s: &str) -> u32 {
    u32::try_from(s.graphemes(true).count()).unwrap_or(u32::MAX)
}

/// The script rules of a word-composing mode: Latin (Afaan Oromoo, English) or Arabic.
pub(crate) enum WordScript<'a> {
    Latin(&'a Orthography),
    Arabic,
}

impl WordScript<'_> {
    pub fn is_letter(&self, c: char) -> bool {
        match self {
            WordScript::Latin(o) => o.is_letter(c),
            WordScript::Arabic => is_arabic_word_char(c),
        }
    }

    pub fn is_apostrophe(&self, c: char) -> bool {
        match self {
            WordScript::Latin(o) => o.is_apostrophe(c),
            WordScript::Arabic => false,
        }
    }

    /// The lexicon lookup key of a composed word (Latin: apostrophes folded to the canonical one).
    pub fn key(&self, word: &str) -> String {
        match self {
            WordScript::Latin(o) => o.fold_apostrophes(word),
            WordScript::Arabic => word.to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert!(c.sentence_start);
        c.observe(&commit("ሰላም "));
        assert!(!c.sentence_start);
        c.observe(&EditorUpdate {
            delete_before: 1,
            ..Default::default()
        });
        assert_eq!(c.previous, None);
        assert!(c.current.is_empty());
    }

    #[test]
    fn deletions_inside_the_current_word_are_followed() {
        let mut c = WordContext::default();
        c.observe(&commit("hello wor"));
        c.observe(&EditorUpdate {
            delete_before: 2,
            ..Default::default()
        });
        assert_eq!(c.current, "w");
        assert_eq!(c.previous.as_deref(), Some("hello"));
        c.observe(&EditorUpdate {
            delete_before: 2,
            ..Default::default()
        });
        assert_eq!(c.previous, None);
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
    fn context_from_host_text() {
        let c = WordContext::from_text("");
        assert!(c.sentence_start && c.previous.is_none());
        let c = WordContext::from_text("Good morning ");
        assert_eq!(c.previous.as_deref(), Some("morning"));
        assert!(c.at_word_boundary());
        let c = WordContext::from_text("It works. ");
        assert!(c.sentence_start);
        let c = WordContext::from_text("mail me at a@b");
        assert!(!c.at_word_boundary());
        let c = WordContext::from_text("ሰላም ነ");
        assert_eq!(c.current, "ነ");
        assert_eq!(c.previous.as_deref(), Some("ሰላም"));
        let long = format!("{} tail", "x".repeat(500));
        assert_eq!(WordContext::from_text(&long).current, "tail");
        // A tail that starts inside a long word does not pretend to know the word.
        assert_eq!(WordContext::from_text(&"y".repeat(500)).current, "");
    }

    #[test]
    fn punctuation_classes() {
        assert!(attaches_to_word(","));
        assert!(attaches_to_word("።"));
        assert!(!attaches_to_word("("));
        assert!(!attaches_to_word(".."));
        assert!(ends_sentence_part("."));
        assert!(!ends_sentence_part("@"));
        assert!(is_arabic_word_char('ب'));
        assert!(is_arabic_word_char('\u{064E}'));
        assert!(!is_arabic_word_char('١'));
        assert_eq!(graphemes("ሰላም "), 4);
    }
}
