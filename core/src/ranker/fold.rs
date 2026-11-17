//! Matching equivalences (case, Latin diacritics, Ge'ez homophone letter families) and the
//! edit neighbourhood used for corrections. Pure functions over `str`; no lexicon access.
//!
//! Equivalence is for *matching and ranking only*: callers always return the lexicon's own
//! spelling and never rewrite one valid spelling into another.

use unicode_normalization::UnicodeNormalization;

/// Homophone letter families, as the first code point of each 8-aligned series block.
/// Each series occupies `base..base+8` in the Ethiopic block with the same vowel order at the
/// same offset, so an equivalence maps `base + offset` across the family. The first entry of
/// each family is its canonical (folding) member. Families (SD 7.2): ሀ/ሐ/ኀ, ሰ/ሠ, አ/ዐ, ጸ/ፀ.
const FAMILIES: [&[u32]; 4] = [
    &[0x1200, 0x1210, 0x1280],
    &[0x1230, 0x1220],
    &[0x12A0, 0x12D0],
    &[0x1338, 0x1340],
];

pub(super) fn is_ethiopic(c: char) -> bool {
    matches!(c as u32, 0x1200..=0x139F | 0x2D80..=0x2DDF | 0xAB00..=0xAB2F)
}

fn family_of(c: char) -> Option<(&'static [u32], u32)> {
    let cp = c as u32;
    if !(0x1200..=0x1347).contains(&cp) {
        return None;
    }
    let base = cp & !7;
    FAMILIES
        .iter()
        .find(|f| f.contains(&base))
        .map(|f| (*f, cp & 7))
}

/// Maps a homophone-family letter to its family's canonical member; other chars unchanged.
pub(super) fn homophone_canon(c: char) -> char {
    match family_of(c) {
        Some((f, off)) => char::from_u32(f[0] + off).unwrap_or(c),
        None => c,
    }
}

/// The other members of `c`'s homophone family at the same vowel order (empty if none).
fn homophone_alternatives(c: char) -> Vec<char> {
    match family_of(c) {
        Some((f, off)) => f
            .iter()
            .filter_map(|b| char::from_u32(b + off))
            .filter(|x| *x != c)
            .collect(),
        None => Vec::new(),
    }
}

fn is_latin_diacritic(c: char) -> bool {
    matches!(c as u32, 0x300..=0x36F)
}

/// NFC text with Latin combining diacritics removed (Ethiopic marks are kept).
pub(super) fn strip_diacritics(s: &str) -> String {
    s.nfd().filter(|c| !is_latin_diacritic(*c)).nfc().collect()
}

/// Equivalence key: lower-case, no Latin diacritics, homophone families folded to canonical.
/// Two words with the same key are "the same word" for matching and for next-word statistics.
pub(super) fn fold_key(s: &str) -> String {
    s.nfd()
        .filter(|c| !is_latin_diacritic(*c))
        .flat_map(char::to_lowercase)
        .map(homophone_canon)
        .nfc()
        .collect()
}

pub(super) fn title_case(s: &str) -> String {
    let mut it = s.chars();
    match it.next() {
        Some(f) => f.to_uppercase().chain(it).collect(),
        None => String::new(),
    }
}

/// Penalty (in edit-cost units) for matching through each equivalence.
pub(super) const CASE_PENALTY: f64 = 0.05;
pub(super) const DIACRITIC_PENALTY: f64 = 0.15;
/// Tiny: the exact spelling must win ties, but a homophone variant is not a typo.
pub(super) const HOMOPHONE_PENALTY: f64 = 0.05;
/// Cap on spelling variants tried per typed word (bounds work for long homophone-rich input).
pub(super) const MAX_VARIANTS: usize = 72;

/// Accented forms offered when the typed word has none (users on plain keyboards omit them,
/// e.g. `cafe` for `café`). Latin only; one accented letter per variant.
fn accents(c: char) -> &'static str {
    match c {
        'a' => "áàâäãå",
        'e' => "éèêë",
        'i' => "íìîï",
        'o' => "óòôöõ",
        'u' => "úùûü",
        'c' => "ç",
        'n' => "ñ",
        'y' => "ýÿ",
        _ => "",
    }
}

/// Most accent variants added per typed word.
const MAX_ACCENT_VARIANTS: usize = 24;

/// Spellings to look up for `typed`: itself, case and diacritic variants, then every homophone
/// substitution of those (capped). Each carries the penalty for the equivalence used.
pub(super) fn variants(typed: &str) -> Vec<(String, f64)> {
    let mut bases: Vec<(String, f64)> = vec![(typed.to_string(), 0.0)];
    let add = |s: String, p: f64, bases: &mut Vec<(String, f64)>| {
        if !bases.iter().any(|(b, _)| *b == s) {
            bases.push((s, p));
        }
    };
    let lower: String = typed.chars().flat_map(char::to_lowercase).collect();
    add(lower.clone(), CASE_PENALTY, &mut bases);
    add(strip_diacritics(&lower), DIACRITIC_PENALTY, &mut bases);
    if !typed.chars().any(char::is_uppercase) {
        add(title_case(&lower), CASE_PENALTY, &mut bases);
        add(
            title_case(&strip_diacritics(&lower)),
            DIACRITIC_PENALTY,
            &mut bases,
        );
    }
    // Re-add accents the user may have left out (only when the typed word has none).
    let plain = strip_diacritics(&lower);
    if plain == lower {
        let chars: Vec<char> = plain.chars().collect();
        let mut added = 0;
        'acc: for (i, c) in chars.iter().enumerate() {
            for a in accents(*c).chars() {
                if added >= MAX_ACCENT_VARIANTS {
                    break 'acc;
                }
                let mut v = chars.clone();
                v[i] = a;
                let s: String = v.into_iter().collect();
                add(s.clone(), DIACRITIC_PENALTY, &mut bases);
                if !typed.chars().any(char::is_uppercase) {
                    add(title_case(&s), DIACRITIC_PENALTY, &mut bases);
                }
                added += 1;
            }
        }
    }
    let mut out = bases.clone();
    'bases: for (b, pen) in &bases {
        let chars: Vec<char> = b.chars().collect();
        let mut forms: Vec<Vec<char>> = vec![chars.clone()];
        for (i, c) in chars.iter().enumerate() {
            let alts = homophone_alternatives(*c);
            if alts.is_empty() {
                continue;
            }
            let snapshot = forms.clone();
            for f in snapshot {
                for a in &alts {
                    if out.len() + forms.len() > MAX_VARIANTS {
                        break 'bases;
                    }
                    let mut g = f.clone();
                    g[i] = *a;
                    forms.push(g);
                }
            }
        }
        for f in forms.into_iter().skip(1) {
            let s: String = f.into_iter().collect();
            add(s, pen + HOMOPHONE_PENALTY, &mut out);
        }
        if out.len() >= MAX_VARIANTS {
            break;
        }
    }
    out
}

const ROWS: [&str; 3] = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
const ROW_OFFSET: [f64; 3] = [0.0, 0.25, 0.75];

fn key_pos(c: char) -> Option<(f64, f64)> {
    ROWS.iter()
        .enumerate()
        .find_map(|(r, row)| row.find(c).map(|i| (i as f64 + ROW_OFFSET[r], r as f64)))
}

/// True when two QWERTY letter keys touch (staggered-row geometry, lower-case ASCII only).
pub(super) fn keys_adjacent(a: char, b: char) -> bool {
    match (key_pos(a), key_pos(b)) {
        (Some((x1, y1)), Some((x2, y2))) if a != b => (x1 - x2).powi(2) + (y1 - y2).powi(2) < 1.8,
        _ => false,
    }
}

/// Cost of substituting a neighbouring key / a different vowel order of the same Fidel series.
pub(super) const SUB_NEAR_COST: f64 = 0.7;
pub(super) const SUB_COST: f64 = 1.0;
pub(super) const INDEL_COST: f64 = 1.0;
pub(super) const TRANSPOSE_COST: f64 = 0.8;

/// All strings one edit away from `word` with their weighted cost (callers keep the cheapest
/// per string). Latin: delete, transpose, substitute and insert a-z (adjacent keys are cheaper).
/// Ethiopic: delete, transpose, and swap the vowel order within a series (the usual slip).
/// No insertion of Ethiopic letters (too many to enumerate cheaply).
pub(super) fn neighbours(word: &str) -> Vec<(String, f64)> {
    let chars: Vec<char> = word.chars().collect();
    let n = chars.len();
    let mut out: Vec<(String, f64)> = Vec::with_capacity(n * 60);
    let build = |v: &[char]| -> String { v.iter().collect() };
    for i in 0..n {
        let mut d = chars.clone();
        d.remove(i);
        out.push((build(&d), INDEL_COST));
    }
    for i in 0..n.saturating_sub(1) {
        if chars[i] != chars[i + 1] {
            let mut t = chars.clone();
            t.swap(i, i + 1);
            out.push((build(&t), TRANSPOSE_COST));
        }
    }
    for i in 0..n {
        let c = chars[i];
        if c.is_ascii_lowercase() {
            for s in 'a'..='z' {
                if s != c {
                    let mut v = chars.clone();
                    v[i] = s;
                    let cost = if keys_adjacent(c, s) {
                        SUB_NEAR_COST
                    } else {
                        SUB_COST
                    };
                    out.push((build(&v), cost));
                }
            }
        } else if is_ethiopic(c) && (0x1200..=0x135A).contains(&(c as u32)) {
            let base = (c as u32) & !7;
            for off in 0..8 {
                if let Some(s) = char::from_u32(base + off) {
                    if s != c {
                        let mut v = chars.clone();
                        v[i] = s;
                        out.push((build(&v), SUB_NEAR_COST));
                    }
                }
            }
        }
    }
    for i in 0..=n {
        for s in 'a'..='z' {
            let near = (i > 0 && keys_adjacent(chars[i - 1], s))
                || (i < n && keys_adjacent(chars[i], s))
                || (i > 0 && chars[i - 1] == s);
            // Only insert next to Latin text; Ethiopic words get no insertions.
            let latin_ctx =
                (i > 0 && chars[i - 1].is_ascii()) || (i < n && chars[i].is_ascii()) || n == 0;
            if !latin_ctx {
                continue;
            }
            let mut v = chars.clone();
            v.insert(i, s);
            out.push((
                build(&v),
                if near {
                    SUB_NEAR_COST + 0.1
                } else {
                    INDEL_COST
                },
            ));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn families_fold_to_canonical_for_every_vowel_order() {
        assert_eq!(fold_key("ሐ"), "ሀ");
        assert_eq!(fold_key("ኀ"), "ሀ");
        assert_eq!(fold_key("ሠ"), "ሰ");
        assert_eq!(fold_key("ዐ"), "አ");
        assert_eq!(fold_key("ፀ"), "ጸ");
        // Same vowel order maps to the same vowel order: ሑ (hu) -> ሁ, ኂ (hi) -> ሂ, ሡ -> ሱ.
        assert_eq!(fold_key("ሑ"), "ሁ");
        assert_eq!(fold_key("ኂ"), "ሂ");
        assert_eq!(fold_key("ሡ"), "ሱ");
        assert_eq!(fold_key("ዑ"), "ኡ");
        assert_eq!(fold_key("ፁ"), "ጹ");
        // Non-members are untouched, including neighbouring series.
        assert_eq!(fold_key("ለ"), "ለ");
        assert_eq!(fold_key("ሸ"), "ሸ");
        assert_eq!(fold_key("ሰላም"), fold_key("ሠላም"));
        assert_ne!(fold_key("ሰላም"), fold_key("ሰማይ"));
    }

    #[test]
    fn latin_case_and_diacritics_fold() {
        assert_eq!(fold_key("Café"), "cafe");
        assert_eq!(fold_key("cafe\u{301}"), "cafe");
        assert_eq!(strip_diacritics("na\u{ef}ve"), "naive");
        assert_eq!(title_case("addis"), "Addis");
        assert_eq!(title_case(""), "");
    }

    #[test]
    fn variants_include_homophones_and_are_capped() {
        let v: Vec<String> = variants("ሰላም").into_iter().map(|x| x.0).collect();
        assert_eq!(v[0], "ሰላም");
        assert!(v.contains(&"ሠላም".to_string()));
        let long = "ሀሀሀሀሀሀሀሀሀሀ";
        assert!(variants(long).len() <= MAX_VARIANTS + 1);
        let v = variants("Café");
        assert!(v.iter().any(|(s, _)| s == "cafe"));
        assert!(v.iter().any(|(s, _)| s == "café"));
        assert_eq!(variants("zb").len(), 2, "zb and Zb");
    }

    #[test]
    fn adjacency() {
        assert!(keys_adjacent('q', 'w'));
        assert!(keys_adjacent('q', 'a'));
        assert!(keys_adjacent('a', 'w'));
        assert!(keys_adjacent('z', 's'));
        assert!(!keys_adjacent('q', 'e'));
        assert!(!keys_adjacent('a', 'a'));
        assert!(!keys_adjacent('q', 'p'));
        assert!(!keys_adjacent('q', '1'));
    }

    #[test]
    fn neighbours_cover_each_edit_kind() {
        let n = neighbours("cat");
        let find = |s: &str| {
            n.iter()
                .filter(|(w, _)| w == s)
                .map(|x| x.1)
                .fold(9.0, f64::min)
        };
        assert_eq!(find("at"), INDEL_COST);
        assert_eq!(find("act"), TRANSPOSE_COST);
        assert_eq!(find("cst"), SUB_NEAR_COST); // s is next to a
        assert_eq!(find("cot"), SUB_COST);
        assert!(find("cart") <= INDEL_COST);
        let g = neighbours("ሰላም");
        assert!(g.iter().any(|(w, _)| w == "ሰለም"));
        assert!(g.iter().any(|(w, _)| w == "ሰም"));
        assert!(g.iter().any(|(w, _)| w == "ላሰም"));
    }
}
