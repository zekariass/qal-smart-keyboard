//! Composing-buffer state machines: keystrokes in, composing text / commits / deletions out.
//!
//! - Direct Fidel (C1.3): [`DirectFidel`] drives the consonant-key layouts described by a
//!   `KEYBOARD_LAYOUT` pack ([`layout`], `docs/specs/keyboard-layout-v1.md`).
//! - Phonetic composing buffer (C1.4): `crate::translit::PhoneticSession` (data-driven profiles).
//!
//! The syllable table is generated from Unicode character names by
//! `language-data/scripts/gen_fidel_table.py`, never computed from code point offsets (the
//! Ethiopic blocks have irregular gaps).

mod direct;
mod fidel_table;
pub mod layout;

pub(crate) use direct::sanitize;
pub use direct::DirectFidel;
pub use layout::{FidelDigit, FidelForm, FidelKey, FidelLayout, FidelVowelKey, Layout};

use fidel_table::{ORDERS, SERIES};

/// Order number of the labialised "wa" form (the eighth order).
pub const ORDER_LABIALIZED: u8 = 8;
/// Highest order number in the table.
pub const MAX_ORDER: u8 = 9;
/// Order numbers of a key's labialised-series forms in [`FidelForm::order`] are offset by this
/// (`11` = labialised series, order 1; `14` = ጓ-like "waa").
pub const LABIALIZED_FORM_OFFSET: u8 = 10;

/// Index of a consonant series in the generated table.
pub(crate) fn series_index(id: &str) -> Option<usize> {
    SERIES.iter().position(|(s, _)| *s == id)
}

/// The syllable for `series_id` and vowel order number `order` (1..=9), if Unicode has it.
///
/// Order 6 is the sixth order: `syllable("G", 6) == Some('ግ')`.
pub fn syllable(series_id: &str, order: u8) -> Option<char> {
    form(series_index(series_id)?, order)
}

pub(crate) fn series_id(series: usize) -> &'static str {
    SERIES.get(series).map_or("", |(s, _)| s)
}

pub(crate) fn order_id(order: u8) -> Option<&'static str> {
    ORDERS.iter().find(|(_, n)| *n == order).map(|(id, _)| *id)
}

pub(crate) fn order_number(id: &str) -> Option<u8> {
    ORDERS.iter().find(|(o, _)| *o == id).map(|(_, n)| *n)
}

/// Form of series `series` (a table index) in vowel order `order`.
pub(crate) fn form(series: usize, order: u8) -> Option<char> {
    let oid = order_id(order)?;
    let (_, forms) = SERIES.get(series)?;
    forms.iter().find(|(o, _)| *o == oid).map(|(_, c)| *c)
}

/// All `(order id, character)` forms of a series.
pub(crate) fn forms(series: usize) -> &'static [(&'static str, char)] {
    SERIES.get(series).map_or(&[], |(_, f)| f)
}

/// Resolves a vowel-key order for a consonant key. Order 8 means "labialised": the series' own
/// eighth order if it has one (ሏ), otherwise the "waa" form of its labialised series (ጓ).
pub(crate) fn resolve(series: usize, labialized: Option<usize>, order: u8) -> Option<char> {
    if order == ORDER_LABIALIZED {
        return form(series, order).or_else(|| labialized.and_then(|l| form(l, 4)));
    }
    form(series, order)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_order_of_regular_series() {
        let s: String = (1..=7).filter_map(|o| syllable("S", o)).collect();
        assert_eq!(s, "ሰሱሲሳሴስሶ");
        let h: String = (1..=7).filter_map(|o| syllable("H", o)).collect();
        assert_eq!(h, "ሀሁሂሃሄህሆ");
    }

    #[test]
    fn labialised_and_labiovelar() {
        assert_eq!(syllable("L", 8), Some('ሏ'));
        assert_eq!(syllable("GW", 4), Some('ጓ'));
        assert_eq!(syllable("QW", 4), Some('ቋ'));
        assert_eq!(syllable("KW", 4), Some('ኳ'));
        assert_eq!(syllable("XW", 4), Some('ኋ'));
        // Labiovelar series have no U or O order.
        assert_eq!(syllable("GW", 2), None);
        assert_eq!(syllable("GW", 7), None);
        let gw: String = [1, 3, 4, 5, 6]
            .iter()
            .filter_map(|o| syllable("GW", *o))
            .collect();
        assert_eq!(gw, "ጐጒጓጔጕ");
    }

    #[test]
    fn tigrinya_series_and_irregular_blocks() {
        assert_eq!(syllable("QH", 1), Some('ቐ'));
        assert_eq!(syllable("QH", 5), Some('ቔ'));
        assert_eq!(syllable("KX", 1), Some('ኸ'));
        assert_eq!(syllable("GLOTTAL", 8), Some('ኧ'));
        assert_eq!(syllable("PHARYNGEAL", 1), Some('ዐ'));
        // Extended block form of a series whose base block has 8 forms.
        assert_eq!(syllable("L", 9), Some('\u{2D80}'));
    }

    #[test]
    fn unknown_inputs() {
        assert_eq!(syllable("NOPE", 1), None);
        assert_eq!(syllable("S", 0), None);
        assert_eq!(syllable("S", 10), None);
        assert_eq!(series_index("S").map(super::forms).map(<[_]>::len), Some(9));
    }

    #[test]
    fn order_eight_resolution() {
        let g = series_index("G").unwrap();
        let gw = series_index("GW");
        assert_eq!(resolve(g, gw, 8), Some('ጓ'));
        assert_eq!(resolve(g, None, 8), None);
        let l = series_index("L").unwrap();
        assert_eq!(resolve(l, None, 8), Some('ሏ'));
    }
}
