//! Latin-script orthography data for Qubee (Afaan Oromoo) and English (plan step C1.5).
//!
//! Typing in `Qubee`, `English` and `Abc` modes is plain passthrough (System Design 7.1): the
//! engine never transforms typed text. This module only carries the data-driven part: which
//! multi-letter units (digraphs, long vowels) are single letters, which apostrophe characters are
//! word-internal (Qubee hudhaa, English contractions; SD 7.3: the apostrophe must not break a
//! word), and folding of apostrophe variants for lexicon keys. Data lives in
//! `language-data/orthography/` and is DRAFT until native-speaker review (rule N9). Errors are
//! fixed strings and never echo content (N2).

use serde::Deserialize;
use std::collections::HashSet;
use std::ops::Range;

/// Largest accepted orthography artifact.
pub const MAX_ORTHOGRAPHY_BYTES: usize = 16 * 1024;
const MAX_UNITS: usize = 128;
const MAX_UNIT_CHARS: usize = 4;

const OM_JSON: &str = include_str!("../../language-data/orthography/om-qubee-draft.json");
const EN_JSON: &str = include_str!("../../language-data/orthography/en-latin-draft.json");

/// Why an orthography was refused. A fixed string, never data content.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("invalid orthography: {0}")]
pub struct OrthographyError(pub &'static str);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Raw {
    format: String,
    format_version: u32,
    language: String,
    orthography_id: String,
    orthography_version: String,
    status: String,
    letters: String,
    units: Vec<String>,
    apostrophes: Vec<String>,
    canonical_apostrophe: String,
}

/// A validated Latin orthography.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Orthography {
    language: String,
    id: String,
    version: String,
    draft: bool,
    letters: HashSet<char>,
    /// Multi-letter units, lower case, longest first.
    units: Vec<Vec<char>>,
    apostrophes: HashSet<char>,
    canonical_apostrophe: char,
}

fn lower(c: char) -> char {
    c.to_lowercase().next().unwrap_or(c)
}

impl Orthography {
    /// Parses and validates an orthography artifact.
    pub fn parse(bytes: &[u8]) -> Result<Self, OrthographyError> {
        if bytes.len() > MAX_ORTHOGRAPHY_BYTES {
            return Err(OrthographyError("too large"));
        }
        let raw: Raw =
            serde_json::from_slice(bytes).map_err(|_| OrthographyError("malformed JSON"))?;
        if raw.format != "qal-latin-orthography" || raw.format_version != 1 {
            return Err(OrthographyError("unsupported format"));
        }
        if !matches!(raw.language.as_str(), "om" | "en") {
            return Err(OrthographyError("unsupported language"));
        }
        if raw.orthography_id.is_empty()
            || semver::Version::parse(&raw.orthography_version).is_err()
        {
            return Err(OrthographyError("bad identity"));
        }
        let draft = match raw.status.as_str() {
            "DRAFT" => true,
            "APPROVED" => false,
            _ => return Err(OrthographyError("bad status")),
        };
        let letters: HashSet<char> = raw.letters.chars().collect();
        if letters.is_empty() || letters.iter().any(|c| !c.is_ascii_lowercase()) {
            return Err(OrthographyError("bad letters"));
        }
        if raw.units.len() > MAX_UNITS {
            return Err(OrthographyError("too many units"));
        }
        let mut units: Vec<Vec<char>> = Vec::new();
        for u in &raw.units {
            let cs: Vec<char> = u.chars().collect();
            if cs.len() < 2 || cs.len() > MAX_UNIT_CHARS || cs.iter().any(|c| !letters.contains(c))
            {
                return Err(OrthographyError("bad unit"));
            }
            if units.contains(&cs) {
                return Err(OrthographyError("duplicate unit"));
            }
            units.push(cs);
        }
        units.sort_by_key(|u| std::cmp::Reverse(u.len()));
        let mut apostrophes = HashSet::new();
        for a in &raw.apostrophes {
            let mut it = a.chars();
            match (it.next(), it.next()) {
                (Some(c), None) if !c.is_ascii_alphanumeric() && !c.is_whitespace() => {
                    apostrophes.insert(c);
                }
                _ => return Err(OrthographyError("bad apostrophe")),
            }
        }
        let mut it = raw.canonical_apostrophe.chars();
        let canonical_apostrophe = match (it.next(), it.next()) {
            (Some(c), None) if apostrophes.contains(&c) => c,
            _ => return Err(OrthographyError("bad canonical apostrophe")),
        };
        Ok(Self {
            language: raw.language,
            id: raw.orthography_id,
            version: raw.orthography_version,
            draft,
            letters,
            units,
            apostrophes,
            canonical_apostrophe,
        })
    }

    /// The built-in DRAFT orthography for `language_code` (`om` or `en`).
    pub fn builtin(language_code: &str) -> Option<Self> {
        let json = match language_code {
            "om" => OM_JSON,
            "en" => EN_JSON,
            _ => return None,
        };
        Self::parse(json.as_bytes()).ok()
    }

    pub fn language(&self) -> &str {
        &self.language
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn version(&self) -> &str {
        &self.version
    }

    /// DRAFT data has not passed native-speaker review (N9).
    pub fn is_draft(&self) -> bool {
        self.draft
    }

    fn is_letter(&self, c: char) -> bool {
        !self.apostrophes.contains(&c) && (c.is_alphabetic() || self.letters.contains(&lower(c)))
    }

    /// True for the apostrophe characters this orthography treats as hudhaa / contraction marks.
    pub fn is_apostrophe(&self, c: char) -> bool {
        self.apostrophes.contains(&c)
    }

    /// Byte ranges of the words of `text`. An apostrophe belongs to a word only between two
    /// letters (`ca'a`, `don't`); at the start or end it is punctuation, so a quote closing a
    /// word never joins it. Digits and everything else are word boundaries.
    pub fn word_spans(&self, text: &str) -> Vec<Range<usize>> {
        let chars: Vec<(usize, char)> = text.char_indices().collect();
        let mut spans = Vec::new();
        let mut start: Option<usize> = None;
        for (i, &(pos, c)) in chars.iter().enumerate() {
            let inside = self.is_letter(c)
                || (self.is_apostrophe(c)
                    && i > 0
                    && self.is_letter(chars[i - 1].1)
                    && chars.get(i + 1).is_some_and(|&(_, n)| self.is_letter(n)));
            match (inside, start) {
                (true, None) => start = Some(pos),
                (false, Some(s)) => {
                    spans.push(s..pos);
                    start = None;
                }
                _ => {}
            }
        }
        if let Some(s) = start {
            spans.push(s..text.len());
        }
        spans
    }

    /// The words of `text` (see [`word_spans`](Self::word_spans)).
    pub fn words(&self, text: &str) -> Vec<String> {
        self.word_spans(text)
            .into_iter()
            .map(|r| text[r].to_string())
            .collect()
    }

    /// Splits a word into letter units: multi-letter units (longest match, case-insensitive) such
    /// as `dh`, long vowels such as `aa`, the apostrophe and single letters. Concatenating the
    /// result gives the input back exactly. A greedy left-to-right match: `dhh` is `dh`+`h`.
    pub fn units(&self, word: &str) -> Vec<String> {
        let chars: Vec<char> = word.chars().collect();
        let mut out = Vec::new();
        let mut i = 0;
        while i < chars.len() {
            let n = self
                .units
                .iter()
                .find(|u| {
                    i + u.len() <= chars.len()
                        && u.iter().zip(&chars[i..]).all(|(a, b)| *a == lower(*b))
                })
                .map_or(1, Vec::len);
            out.push(chars[i..i + n].iter().collect());
            i += n;
        }
        out
    }

    /// Folds every apostrophe variant to the canonical one, for lexicon keys. Never applied to
    /// committed text (ABC/Qubee/English never transform what the user typed).
    pub fn fold_apostrophes(&self, text: &str) -> String {
        text.chars()
            .map(|c| {
                if self.is_apostrophe(c) {
                    self.canonical_apostrophe
                } else {
                    c
                }
            })
            .collect()
    }

    /// Capitalises a word's first letter unit: `dhugaa` gives `Dhugaa` (not `DHugaa`).
    pub fn capitalize(&self, word: &str) -> String {
        let units = self.units(word);
        let mut out = String::with_capacity(word.len());
        for (i, u) in units.iter().enumerate() {
            if i == 0 {
                let mut cs = u.chars();
                if let Some(first) = cs.next() {
                    out.extend(first.to_uppercase());
                    out.push_str(cs.as_str());
                }
            } else {
                out.push_str(u);
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn om() -> Orthography {
        Orthography::builtin("om").unwrap()
    }

    #[test]
    fn builtin_data_parses_and_is_draft() {
        for l in ["om", "en"] {
            let o = Orthography::builtin(l).unwrap();
            assert_eq!(o.language(), l);
            assert!(o.is_draft());
            assert!(!o.id().is_empty() && !o.version().is_empty());
        }
        assert!(Orthography::builtin("am").is_none());
    }

    #[test]
    fn digraphs_and_long_vowels_are_units() {
        assert_eq!(om().units("dhugaa"), ["dh", "u", "g", "aa"]);
        assert_eq!(om().units("Shan"), ["Sh", "a", "n"]);
        assert_eq!(om().units("nyaata"), ["ny", "aa", "t", "a"]);
        assert_eq!(om().units("CHAA"), ["CH", "AA"]);
        // Greedy: no unit spans the second h.
        assert_eq!(om().units("dhh"), ["dh", "h"]);
        assert_eq!(om().units("").len(), 0);
    }

    #[test]
    fn units_concatenate_back() {
        for w in ["dhugaa", "ka'aa", "NYAATA", "ph", "x'y"] {
            assert_eq!(om().units(w).concat(), w);
        }
    }

    #[test]
    fn apostrophe_is_part_of_the_word_between_letters() {
        let o = om();
        assert_eq!(o.words("ba'a"), ["ba'a"]);
        assert_eq!(o.words("ka\u{2019}aa nyaata"), ["ka\u{2019}aa", "nyaata"]);
        assert_eq!(o.words("du\u{2bc}a"), ["du\u{2bc}a"]);
        assert_eq!(o.units("ba'a"), ["b", "a", "'", "a"]);
    }

    #[test]
    fn apostrophe_at_the_edges_is_punctuation() {
        let o = om();
        assert_eq!(o.words("'dhugaa' jedhe"), ["dhugaa", "jedhe"]);
        assert_eq!(o.word_spans("'ab'"), vec![1..3]);
        assert_eq!(o.words("a''b"), ["a", "b"]);
        assert_eq!(o.words("123 4'5"), Vec::<String>::new());
        assert_eq!(o.words("a1b"), ["a", "b"]);
    }

    #[test]
    fn english_contractions() {
        let e = Orthography::builtin("en").unwrap();
        assert_eq!(
            e.words("don't stop, it's ok"),
            ["don't", "stop", "it's", "ok"]
        );
        assert_eq!(e.units("church"), ["c", "h", "u", "r", "c", "h"]);
    }

    #[test]
    fn folding_and_capitalisation() {
        let o = om();
        assert_eq!(
            o.fold_apostrophes("ka\u{2019}a du\u{2bc}a ba'a"),
            "ka'a du'a ba'a"
        );
        assert_eq!(o.capitalize("dhugaa"), "Dhugaa");
        assert_eq!(o.capitalize("ka'a"), "Ka'a");
        assert_eq!(o.capitalize("aanaa"), "Aanaa");
        assert_eq!(o.capitalize(""), "");
    }

    #[test]
    fn rejects_bad_data() {
        let good = include_str!("../../language-data/orthography/om-qubee-draft.json");
        assert!(Orthography::parse(good.as_bytes()).is_ok());
        for (from, to) in [
            ("\"units\": [\"aa\"", "\"units\": [\"a\""),
            ("\"units\": [\"aa\"", "\"units\": [\"aa\", \"aa\""),
            ("\"units\": [\"aa\"", "\"units\": [\"a1\""),
            ("\"DRAFT\"", "\"MAYBE\""),
            ("qal-latin-orthography", "other"),
            ("\"language\": \"om\"", "\"language\": \"am\""),
            (
                "\"canonicalApostrophe\": \"'\"",
                "\"canonicalApostrophe\": \"x\"",
            ),
        ] {
            assert!(
                Orthography::parse(good.replacen(from, to, 1).as_bytes()).is_err(),
                "{from}"
            );
        }
        assert!(Orthography::parse(b"{").is_err());
        assert!(Orthography::parse(&vec![b' '; MAX_ORTHOGRAPHY_BYTES + 1]).is_err());
    }
}
