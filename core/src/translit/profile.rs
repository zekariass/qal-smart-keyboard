//! `TRANSLITERATION_RULES` artifact: parsing and validation of a phonetic profile.
//!
//! Profiles arrive inside verified packs but are still validated strictly: a malformed profile
//! never becomes active. Error values are fixed strings and never echo profile content (N2).

use crate::composer::{form, series_index};
use serde::Deserialize;
use std::collections::{BTreeMap, HashSet};
use unicode_normalization::is_nfc;

/// Maximum artifact size of a profile.
pub const MAX_PROFILE_BYTES: usize = 256 * 1024;
const MAX_RULES: usize = 512;
const MAX_INPUT_CHARS: usize = 4;
const MAX_ALTS: usize = 8;
const MAX_PROTECTED: usize = 4096;
const MAX_PROTECTED_CHARS: usize = 64;
const LANGUAGE_FIT: [&str; 2] = ["am", "ti"];
/// The sixth order: what a consonant shows before any vowel is typed.
const BARE_ORDER: u8 = 6;

/// Why a profile was refused. A fixed string, never profile content.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("invalid transliteration profile: {0}")]
pub struct ProfileError(pub &'static str);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawProfile {
    format: String,
    format_version: u32,
    language: String,
    profile_id: String,
    profile_version: String,
    status: String,
    #[serde(default)]
    fold_case: bool,
    #[serde(default)]
    collapse_gemination: bool,
    labial_marker: Option<String>,
    separator: Option<String>,
    #[serde(default)]
    labialized_series: BTreeMap<String, String>,
    initial: RawInitial,
    consonants: Vec<RawConsonant>,
    vowels: Vec<RawVowel>,
    #[serde(default)]
    punctuation: Vec<RawPunct>,
    #[serde(default)]
    protected: RawProtected,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawInitial {
    series: String,
    #[serde(default)]
    alt_series: Vec<String>,
    #[serde(default)]
    overrides: Vec<RawOverride>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawOverride {
    #[serde(rename = "in")]
    input: String,
    order: u8,
    #[serde(default)]
    alt_orders: Vec<u8>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawConsonant {
    #[serde(rename = "in")]
    input: String,
    series: String,
    #[serde(default)]
    alt: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawVowel {
    #[serde(rename = "in")]
    input: String,
    order: u8,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawPunct {
    #[serde(rename = "in")]
    input: String,
    out: String,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawProtected {
    #[serde(default)]
    prefixes: Vec<String>,
    #[serde(default)]
    chars: Vec<String>,
    #[serde(default)]
    terms: Vec<String>,
}

/// Review status of a profile. `Draft` profiles have not passed native-speaker review (G0) and
/// must not be presented to users as verified (N9).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileStatus {
    Draft,
    Approved,
}

/// A consonant input and the series it produces.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ConsonantRule {
    pub input: String,
    pub series: usize,
    /// Homophone series offered as alternatives (e.g. ሠ for ሰ).
    pub alt: Vec<usize>,
}

/// A vowel input and the order it selects.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct VowelRule {
    pub input: String,
    pub order: u8,
}

/// How a vowel typed without a consonant is shown (overrides the default mapping).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct InitialOverride {
    pub input: String,
    pub order: u8,
    pub alt_orders: Vec<u8>,
}

/// A validated phonetic profile.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Profile {
    pub(crate) language: String,
    pub(crate) profile_id: String,
    pub(crate) profile_version: String,
    pub(crate) status: ProfileStatus,
    pub(crate) fold_case: bool,
    pub(crate) collapse_gemination: bool,
    pub(crate) labial_marker: Option<Vec<char>>,
    pub(crate) separator: Option<char>,
    /// `(series, labiovelar series)` pairs.
    pub(crate) labialized: Vec<(usize, usize)>,
    pub(crate) initial_series: usize,
    pub(crate) initial_alt_series: Vec<usize>,
    pub(crate) initial_overrides: Vec<InitialOverride>,
    pub(crate) consonants: Vec<ConsonantRule>,
    pub(crate) vowels: Vec<VowelRule>,
    /// `(typed pair, output)`.
    pub(crate) punctuation: Vec<([char; 2], char)>,
    pub(crate) protected_prefixes: Vec<String>,
    pub(crate) protected_chars: Vec<char>,
    pub(crate) protected_terms: HashSet<String>,
    /// Every character that can be part of a letter rule (both cases), plus the separator.
    letter_keys: HashSet<char>,
}

fn ident_ok(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && s.bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// A typed rule input: 1 to 4 printable ASCII characters, no space.
fn input_ok(s: &str) -> bool {
    (1..=MAX_INPUT_CHARS).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_graphic())
}

fn single_char(s: &str) -> Option<char> {
    let mut it = s.chars();
    match (it.next(), it.next()) {
        (Some(c), None) => Some(c),
        _ => None,
    }
}

fn series_or(id: &str, msg: &'static str) -> Result<usize, ProfileError> {
    series_index(id).ok_or(ProfileError(msg))
}

fn order_ok(o: u8) -> bool {
    (1..=crate::composer::MAX_ORDER).contains(&o)
}

impl Profile {
    /// Parses and validates a profile artifact.
    pub fn parse(bytes: &[u8]) -> Result<Self, ProfileError> {
        if bytes.len() > MAX_PROFILE_BYTES {
            return Err(ProfileError("profile is too large"));
        }
        let raw: RawProfile = serde_json::from_slice(bytes)
            .map_err(|_| ProfileError("not valid profile JSON (or unknown or missing fields)"))?;
        Self::validate(raw)
    }

    pub fn language(&self) -> &str {
        &self.language
    }
    pub fn profile_id(&self) -> &str {
        &self.profile_id
    }
    pub fn profile_version(&self) -> &str {
        &self.profile_version
    }
    pub fn status(&self) -> ProfileStatus {
        self.status
    }

    pub(crate) fn is_letter_key(&self, c: char) -> bool {
        self.letter_keys.contains(&c)
    }

    pub(crate) fn is_punct_start(&self, c: char) -> bool {
        self.punctuation.iter().any(|(p, _)| p[0] == c)
    }

    pub(crate) fn punct_pair(&self, a: char, b: char) -> Option<char> {
        self.punctuation
            .iter()
            .find(|(p, _)| p[0] == a && p[1] == b)
            .map(|(_, o)| *o)
    }

    pub(crate) fn labialized_of(&self, series: usize) -> Option<usize> {
        self.labialized
            .iter()
            .find(|(s, _)| *s == series)
            .map(|(_, l)| *l)
    }

    /// The glyph for `series` in vowel `order`; with `labial`, its labialised form: the
    /// labiovelar series' form, or for the fourth order the series' own "wa" form (ሏ).
    pub(crate) fn glyph(&self, series: usize, order: u8, labial: bool) -> Option<char> {
        if !labial {
            return form(series, order);
        }
        self.labialized_of(series)
            .and_then(|l| form(l, order))
            .or_else(|| {
                (order == 4)
                    .then(|| form(series, crate::composer::ORDER_LABIALIZED))
                    .flatten()
            })
    }

    fn validate(raw: RawProfile) -> Result<Self, ProfileError> {
        let err = ProfileError;
        if raw.format != "qal-transliteration-profile" || raw.format_version != 1 {
            return Err(err("unsupported profile format or version"));
        }
        if !LANGUAGE_FIT.contains(&raw.language.as_str()) {
            return Err(err("profile language must be am or ti"));
        }
        if !ident_ok(&raw.profile_id) {
            return Err(err("invalid profileId"));
        }
        if semver::Version::parse(&raw.profile_version).is_err() {
            return Err(err("profileVersion is not a semantic version"));
        }
        let status = match raw.status.as_str() {
            "DRAFT" => ProfileStatus::Draft,
            "APPROVED" => ProfileStatus::Approved,
            _ => return Err(err("status must be DRAFT or APPROVED")),
        };
        if raw.consonants.is_empty() || raw.consonants.len() > MAX_RULES {
            return Err(err("consonants must have 1 to 512 rules"));
        }
        if raw.vowels.is_empty() || raw.vowels.len() > MAX_RULES {
            return Err(err("vowels must have 1 to 512 rules"));
        }

        let mut inputs: HashSet<String> = HashSet::new();
        let mut consonants = Vec::with_capacity(raw.consonants.len());
        for c in &raw.consonants {
            if !input_ok(&c.input) || !inputs.insert(c.input.clone()) {
                return Err(err("invalid or duplicate rule input"));
            }
            let series = series_or(&c.series, "unknown consonant series")?;
            // A consonant typed alone shows its sixth order, so the series needs one.
            if form(series, BARE_ORDER).is_none() {
                return Err(err("consonant series has no sixth order"));
            }
            if c.alt.len() > MAX_ALTS {
                return Err(err("too many alternatives"));
            }
            let mut alt = Vec::with_capacity(c.alt.len());
            for a in &c.alt {
                let i = series_or(a, "unknown alternative series")?;
                if i == series || alt.contains(&i) || form(i, BARE_ORDER).is_none() {
                    return Err(err("invalid alternative series"));
                }
                alt.push(i);
            }
            consonants.push(ConsonantRule {
                input: c.input.clone(),
                series,
                alt,
            });
        }

        let mut vowels = Vec::with_capacity(raw.vowels.len());
        for v in &raw.vowels {
            if !input_ok(&v.input) || !inputs.insert(v.input.clone()) || !order_ok(v.order) {
                return Err(err("invalid or duplicate rule input"));
            }
            vowels.push(VowelRule {
                input: v.input.clone(),
                order: v.order,
            });
        }

        let initial_series = series_or(&raw.initial.series, "unknown initial series")?;
        let mut initial_alt_series = Vec::new();
        for a in &raw.initial.alt_series {
            let i = series_or(a, "unknown initial alternative series")?;
            if i == initial_series || initial_alt_series.contains(&i) {
                return Err(err("invalid initial alternative series"));
            }
            initial_alt_series.push(i);
        }
        if initial_alt_series.len() > MAX_ALTS || raw.initial.overrides.len() > MAX_RULES {
            return Err(err("too many initial rules"));
        }
        let mut initial_overrides: Vec<InitialOverride> = Vec::new();
        for o in &raw.initial.overrides {
            let known = vowels.iter().any(|v| v.input == o.input);
            let dup = initial_overrides.iter().any(|p| p.input == o.input);
            if !known || dup || !order_ok(o.order) || !o.alt_orders.iter().all(|a| order_ok(*a)) {
                return Err(err("invalid initial override"));
            }
            if o.alt_orders.len() > MAX_ALTS {
                return Err(err("too many alternatives"));
            }
            initial_overrides.push(InitialOverride {
                input: o.input.clone(),
                order: o.order,
                alt_orders: o.alt_orders.clone(),
            });
        }

        let mut labialized = Vec::new();
        for (s, l) in &raw.labialized_series {
            let si = series_or(s, "unknown labialized key series")?;
            let li = series_or(l, "unknown labiovelar series")?;
            if si == li {
                return Err(err("labiovelar series equals its base series"));
            }
            labialized.push((si, li));
        }

        let labial_marker = match &raw.labial_marker {
            None => None,
            Some(m) if input_ok(m) && !inputs.contains(m) => Some(m.chars().collect()),
            Some(_) => return Err(err("invalid labialMarker")),
        };
        let separator = match &raw.separator {
            None => None,
            Some(s) => match single_char(s) {
                Some(c) if c.is_ascii_graphic() && !inputs.contains(s) => Some(c),
                _ => return Err(err("invalid separator")),
            },
        };

        let mut letter_keys = HashSet::new();
        let letter_inputs = inputs
            .iter()
            .chain(raw.labial_marker.iter())
            .flat_map(|s| s.chars());
        for c in letter_inputs {
            letter_keys.insert(c);
            letter_keys.insert(c.to_ascii_lowercase());
            letter_keys.insert(c.to_ascii_uppercase());
        }
        if let Some(c) = separator {
            letter_keys.insert(c);
        }

        if raw.punctuation.len() > MAX_RULES {
            return Err(err("too many punctuation rules"));
        }
        let mut punctuation: Vec<([char; 2], char)> = Vec::new();
        for p in &raw.punctuation {
            let pair: Vec<char> = p.input.chars().collect();
            let out = single_char(&p.out);
            let (Some(out), [a, b]) = (out, pair.as_slice()) else {
                return Err(err(
                    "punctuation rules map two ASCII characters to one mark",
                ));
            };
            let ethiopic = ('\u{1360}'..='\u{1368}').contains(&out);
            let ascii = a.is_ascii_punctuation() && b.is_ascii_punctuation();
            let clash = letter_keys.contains(a) || punctuation.iter().any(|(q, _)| q == &[*a, *b]);
            if !ethiopic || !ascii || clash {
                return Err(err("invalid punctuation rule"));
            }
            punctuation.push(([*a, *b], out));
        }

        let prot = &raw.protected;
        if prot.prefixes.len() + prot.terms.len() + prot.chars.len() > MAX_PROTECTED {
            return Err(err("too many protected entries"));
        }
        let word_ok = |s: &String| {
            (1..=MAX_PROTECTED_CHARS).contains(&s.chars().count())
                && is_nfc(s)
                && !s.chars().any(|c| c.is_control() || c.is_whitespace())
        };
        if !prot.prefixes.iter().all(word_ok) || !prot.terms.iter().all(word_ok) {
            return Err(err("invalid protected entry"));
        }
        let mut protected_chars = Vec::new();
        for c in &prot.chars {
            match single_char(c) {
                Some(ch) if ch.is_ascii_graphic() => protected_chars.push(ch),
                _ => return Err(err("invalid protected character")),
            }
        }

        Ok(Self {
            language: raw.language,
            profile_id: raw.profile_id,
            profile_version: raw.profile_version,
            status,
            fold_case: raw.fold_case,
            collapse_gemination: raw.collapse_gemination,
            labial_marker,
            separator,
            labialized,
            initial_series,
            initial_alt_series,
            initial_overrides,
            consonants,
            vowels,
            punctuation,
            protected_prefixes: prot.prefixes.iter().map(|p| p.to_lowercase()).collect(),
            protected_chars,
            protected_terms: prot.terms.iter().map(|t| t.to_lowercase()).collect(),
            letter_keys,
        })
    }
}
