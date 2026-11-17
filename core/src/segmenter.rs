//! Grapheme-cluster-safe text segmentation for Ethiopic and Latin text (C1.10).
//!
//! Wraps Unicode extended grapheme cluster boundaries (UAX #29, the `unicode-segmentation`
//! crate, the de-facto reference implementation; no hand-rolled tables). Everything in the core
//! that counts, deletes or truncates "one character" must go through here so a combining mark
//! (Latin diacritic, Ethiopic gemination U+135F, ZWJ emoji sequence, ...) is never separated
//! from its base. Pure functions, no state, no text is logged (N2).
//!
//! Ge'ez syllables (U+1200..) are precomposed single code points, so each is one cluster;
//! combining marks attach to the preceding cluster.

use unicode_segmentation::UnicodeSegmentation;

/// The extended grapheme clusters of `text`, in order. Concatenating them gives `text` back.
pub fn graphemes(text: &str) -> Vec<&str> {
    text.graphemes(true).collect()
}

/// Number of grapheme clusters in `text`.
pub fn grapheme_count(text: &str) -> usize {
    text.graphemes(true).count()
}

/// Byte offsets of every cluster boundary of `text`, including `0` and `text.len()`.
pub fn boundaries(text: &str) -> Vec<usize> {
    let mut v: Vec<usize> = text.grapheme_indices(true).map(|(i, _)| i).collect();
    v.push(text.len());
    v
}

/// `text` without its last grapheme cluster (what Backspace deletes after commit).
pub fn without_last_grapheme(text: &str) -> &str {
    match text.grapheme_indices(true).next_back() {
        Some((i, _)) => &text[..i],
        None => text,
    }
}

/// The first `n` grapheme clusters of `text` (the whole text if it has fewer).
pub fn take_graphemes(text: &str, n: usize) -> &str {
    match text.grapheme_indices(true).nth(n) {
        Some((i, _)) => &text[..i],
        None => text,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ethiopic_and_combining_examples() {
        // Precomposed syllables are one cluster each; a combining mark joins its base.
        assert_eq!(graphemes("ሰላም"), vec!["ሰ", "ላ", "ም"]);
        assert_eq!(graphemes("ሰ\u{135F}ላ"), vec!["ሰ\u{135F}", "ላ"]);
        assert_eq!(graphemes("e\u{301}x"), vec!["e\u{301}", "x"]);
        assert_eq!(graphemes("\r\n"), vec!["\r\n"]);
        assert_eq!(grapheme_count("\u{1F469}\u{200D}\u{1F469}"), 1);
        assert_eq!(without_last_grapheme("ae\u{301}"), "a");
        assert_eq!(without_last_grapheme(""), "");
        assert_eq!(take_graphemes("ሰ\u{135F}ላም", 2), "ሰ\u{135F}ላ");
        assert_eq!(take_graphemes("ab", 5), "ab");
        assert_eq!(boundaries("ab"), vec![0, 1, 2]);
    }
}
