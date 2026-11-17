//! `KEYBOARD_LAYOUT` artifact format v1 (`docs/specs/keyboard-layout-v1.md`): parsing,
//! validation and the FFI view handed to the host UI.
//!
//! Layouts arrive inside verified packs, but are still validated strictly: a malformed layout
//! never becomes active. Error values are fixed strings and never echo layout content (N2).

use super::{
    form, forms, order_number, resolve, series_id, series_index, LABIALIZED_FORM_OFFSET, MAX_ORDER,
    ORDER_LABIALIZED,
};
use serde::Deserialize;
use unicode_normalization::is_nfc;

/// Maximum artifact size of a layout.
pub const MAX_LAYOUT_BYTES: usize = 64 * 1024;
const MAX_ROWS: usize = 8;
const MAX_KEYS_PER_ROW: usize = 16;
const MAX_KEYS: usize = 128;
const MAX_LABEL_CHARS: usize = 4;
const MAX_PUNCTUATION: usize = 16;
const MAX_ALTERNATIVES: usize = 8;
const DEFAULT_ORDER: u8 = 6;

/// Why a layout was refused. A fixed string, never layout content.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("invalid keyboard layout: {0}")]
pub struct LayoutError(pub &'static str);

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawLayout {
    format: String,
    format_version: u32,
    language: String,
    layout_id: String,
    layout_version: String,
    status: String,
    interaction: Vec<String>,
    default_order: Option<u8>,
    #[serde(default)]
    popup_orders: Vec<u8>,
    #[serde(default)]
    vowel_keys: Vec<RawVowelKey>,
    rows: Vec<Vec<RawKey>>,
    #[serde(default)]
    punctuation: Vec<String>,
    #[serde(default)]
    digits: Vec<RawDigit>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawVowelKey {
    order: u8,
    label: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawKey {
    series: String,
    label: String,
    labialized: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawDigit {
    digit: String,
    alternatives: Vec<String>,
}

/// Review status of a layout. `Draft` layouts have not passed native-speaker review (gate G0).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LayoutStatus {
    Draft,
    Approved,
}

/// A consonant key of a validated layout.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct KeyDef {
    /// Index into the generated series table.
    pub series: usize,
    pub label: String,
    /// Index of the labiovelar series appended to this key's popup, if any.
    pub labialized: Option<usize>,
}

/// A validated direct-Fidel layout.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    pub(crate) language: String,
    pub(crate) layout_id: String,
    pub(crate) layout_version: String,
    pub(crate) status: LayoutStatus,
    pub(crate) long_press: bool,
    pub(crate) two_key: bool,
    pub(crate) default_order: u8,
    pub(crate) popup_orders: Vec<u8>,
    pub(crate) vowel_keys: Vec<(u8, String)>,
    pub(crate) rows: Vec<Vec<KeyDef>>,
    pub(crate) punctuation: Vec<String>,
    pub(crate) digits: Vec<(String, Vec<String>)>,
}

fn ok_label(s: &str) -> bool {
    let n = s.chars().count();
    (1..=MAX_LABEL_CHARS).contains(&n) && is_nfc(s) && !s.chars().any(char::is_control)
}

fn single_char(s: &str) -> Option<char> {
    let mut it = s.chars();
    match (it.next(), it.next()) {
        (Some(c), None) => Some(c),
        _ => None,
    }
}

fn is_ident(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && s.bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

fn unique<T: PartialEq>(items: &[T]) -> bool {
    items
        .iter()
        .enumerate()
        .all(|(i, a)| !items[..i].contains(a))
}

impl Layout {
    /// Parses and validates a layout artifact.
    pub fn parse(bytes: &[u8]) -> Result<Self, LayoutError> {
        if bytes.len() > MAX_LAYOUT_BYTES {
            return Err(LayoutError("layout is too large"));
        }
        let raw: RawLayout = serde_json::from_slice(bytes)
            .map_err(|_| LayoutError("not valid layout JSON (or unknown or missing fields)"))?;
        Self::validate(raw)
    }

    fn validate(raw: RawLayout) -> Result<Self, LayoutError> {
        let err = LayoutError;
        if raw.format != "qal-keyboard-layout" || raw.format_version != 1 {
            return Err(err("unsupported layout format or version"));
        }
        let lang_ok = (2..=3).contains(&raw.language.len())
            && raw.language.bytes().all(|b| b.is_ascii_lowercase());
        if !lang_ok {
            return Err(err("invalid language code"));
        }
        if !is_ident(&raw.layout_id) {
            return Err(err("invalid layoutId"));
        }
        if semver::Version::parse(&raw.layout_version).is_err() {
            return Err(err("layoutVersion is not a semantic version"));
        }
        let status = match raw.status.as_str() {
            "DRAFT" => LayoutStatus::Draft,
            "APPROVED" => LayoutStatus::Approved,
            _ => return Err(err("status must be DRAFT or APPROVED")),
        };

        let (mut long_press, mut two_key) = (false, false);
        for i in &raw.interaction {
            match i.as_str() {
                "LONG_PRESS" => long_press = true,
                "TWO_KEY" => two_key = true,
                _ => return Err(err("unknown interaction")),
            }
        }
        if !unique(&raw.interaction) || !(long_press || two_key) {
            return Err(err(
                "interaction must list LONG_PRESS and/or TWO_KEY once each",
            ));
        }

        let default_order = raw.default_order.unwrap_or(DEFAULT_ORDER);
        if !(1..=MAX_ORDER).contains(&default_order) {
            return Err(err("defaultOrder out of range"));
        }
        let order_ok = |o: &u8| (1..=MAX_ORDER).contains(o);
        if !raw.popup_orders.iter().all(order_ok) || !unique(&raw.popup_orders) {
            return Err(err("popupOrders must be unique orders 1 to 9"));
        }
        if long_press && raw.popup_orders.is_empty() {
            return Err(err("LONG_PRESS needs popupOrders"));
        }

        let mut vowel_keys = Vec::with_capacity(raw.vowel_keys.len());
        for v in &raw.vowel_keys {
            if !order_ok(&v.order) || !ok_label(&v.label) {
                return Err(err("invalid vowel key"));
            }
            vowel_keys.push((v.order, v.label.clone()));
        }
        let orders: Vec<u8> = vowel_keys.iter().map(|(o, _)| *o).collect();
        if !unique(&orders) {
            return Err(err("duplicate vowel key order"));
        }
        if two_key && vowel_keys.is_empty() {
            return Err(err("TWO_KEY needs vowelKeys"));
        }

        if raw.rows.is_empty() || raw.rows.len() > MAX_ROWS {
            return Err(err("rows must have 1 to 8 rows"));
        }
        let mut rows = Vec::with_capacity(raw.rows.len());
        let mut seen: Vec<usize> = Vec::new();
        for r in &raw.rows {
            if r.is_empty() || r.len() > MAX_KEYS_PER_ROW {
                return Err(err("a row must have 1 to 16 keys"));
            }
            let mut row = Vec::with_capacity(r.len());
            for k in r {
                let series = series_index(&k.series).ok_or(err("unknown series"))?;
                if seen.contains(&series) {
                    return Err(err("duplicate key"));
                }
                seen.push(series);
                if !ok_label(&k.label) {
                    return Err(err("invalid key label"));
                }
                let labialized = match &k.labialized {
                    None => None,
                    Some(l) => {
                        let i = series_index(l).ok_or(err("unknown labialized series"))?;
                        if i == series {
                            return Err(err("labialized series equals the key series"));
                        }
                        Some(i)
                    }
                };
                row.push(KeyDef {
                    series,
                    label: k.label.clone(),
                    labialized,
                });
            }
            rows.push(row);
        }
        if seen.len() > MAX_KEYS {
            return Err(err("too many keys"));
        }

        if raw.punctuation.len() > MAX_PUNCTUATION || !unique(&raw.punctuation) {
            return Err(err("invalid punctuation list"));
        }
        // Ethiopic punctuation U+1360..=U+1368.
        if !raw
            .punctuation
            .iter()
            .all(|p| single_char(p).is_some_and(|c| ('\u{1360}'..='\u{1368}').contains(&c)))
        {
            return Err(err("punctuation must be single Ethiopic punctuation marks"));
        }

        let mut digits = Vec::with_capacity(raw.digits.len());
        for d in &raw.digits {
            if single_char(&d.digit).is_none_or(|c| !c.is_ascii_digit()) {
                return Err(err("digit must be one ASCII digit"));
            }
            if d.alternatives.is_empty()
                || d.alternatives.len() > MAX_ALTERNATIVES
                || !unique(&d.alternatives)
            {
                return Err(err("invalid digit alternatives"));
            }
            // Ethiopic numerals U+1369..=U+137C.
            if !d
                .alternatives
                .iter()
                .all(|a| single_char(a).is_some_and(|c| ('\u{1369}'..='\u{137C}').contains(&c)))
            {
                return Err(err("digit alternatives must be Ethiopic numerals"));
            }
            digits.push((d.digit.clone(), d.alternatives.clone()));
        }
        let digit_keys: Vec<&String> = digits.iter().map(|(d, _)| d).collect();
        if !unique(&digit_keys) {
            return Err(err("duplicate digit key"));
        }

        Ok(Self {
            language: raw.language,
            layout_id: raw.layout_id,
            layout_version: raw.layout_version,
            status,
            long_press,
            two_key,
            default_order,
            popup_orders: raw.popup_orders,
            vowel_keys,
            rows,
            punctuation: raw.punctuation,
            digits,
        })
    }

    pub fn language(&self) -> &str {
        &self.language
    }

    pub fn is_two_key(&self) -> bool {
        self.two_key
    }

    pub(crate) fn key(&self, series_id: &str) -> Option<&KeyDef> {
        let series = series_index(series_id)?;
        self.rows.iter().flatten().find(|k| k.series == series)
    }

    pub(crate) fn has_vowel_key(&self, order: u8) -> bool {
        self.vowel_keys.iter().any(|(o, _)| *o == order)
    }

    /// The form shown when a consonant key is pressed (the default order, else the first form).
    pub(crate) fn default_form(&self, key: &KeyDef) -> Option<char> {
        form(key.series, self.default_order).or_else(|| forms(key.series).first().map(|(_, c)| *c))
    }

    /// The committed form for `order` on `key` (order 8 resolves through the labialised series).
    pub(crate) fn form_for(&self, key: &KeyDef, order: u8) -> Option<char> {
        resolve(key.series, key.labialized, order)
    }

    /// The host-facing description of the layout.
    pub fn to_ffi(&self) -> FidelLayout {
        let rows = self
            .rows
            .iter()
            .map(|r| r.iter().map(|k| self.ffi_key(k)).collect())
            .collect();
        FidelLayout {
            language_code: self.language.clone(),
            layout_id: self.layout_id.clone(),
            layout_version: self.layout_version.clone(),
            status: match self.status {
                LayoutStatus::Draft => "DRAFT",
                LayoutStatus::Approved => "APPROVED",
            }
            .to_string(),
            long_press: self.long_press,
            two_key: self.two_key,
            default_order: self.default_order,
            rows,
            vowel_keys: self
                .vowel_keys
                .iter()
                .map(|(order, label)| FidelVowelKey {
                    order: *order,
                    label: label.clone(),
                })
                .collect(),
            punctuation: self.punctuation.clone(),
            digits: self
                .digits
                .iter()
                .map(|(digit, alternatives)| FidelDigit {
                    digit: digit.clone(),
                    alternatives: alternatives.clone(),
                })
                .collect(),
        }
    }

    fn ffi_key(&self, k: &KeyDef) -> FidelKey {
        let mut popup = Vec::new();
        for &order in &self.popup_orders {
            // Order 8 in the popup is the series' own labialised form only; the labiovelar
            // series forms are appended below.
            let c = if order == ORDER_LABIALIZED {
                form(k.series, order)
            } else {
                resolve(k.series, None, order)
            };
            if let Some(c) = c {
                popup.push(FidelForm {
                    order,
                    label: c.to_string(),
                    text: c.to_string(),
                });
            }
        }
        if let Some(l) = k.labialized {
            for (oid, c) in forms(l) {
                if let Some(n) = order_number(oid) {
                    popup.push(FidelForm {
                        order: n + LABIALIZED_FORM_OFFSET,
                        label: c.to_string(),
                        text: c.to_string(),
                    });
                }
            }
        }
        FidelKey {
            series_id: series_id(k.series).to_string(),
            label: k.label.clone(),
            tap_text: self
                .default_form(k)
                .map(|c| c.to_string())
                .unwrap_or_default(),
            forms: popup,
        }
    }
}

/// One form in a key's long-press popup.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FidelForm {
    /// Vowel order 1..=9; labialised-series forms are `11..=19` (order + 10).
    pub order: u8,
    /// Text to draw in the popup.
    pub label: String,
    /// Text to send as `KeyEvent::Char` when the user picks this form.
    pub text: String,
}

/// A consonant key.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FidelKey {
    /// Series id to send in `KeyEvent::FidelConsonant` (TWO_KEY).
    pub series_id: String,
    /// Text drawn on the key.
    pub label: String,
    /// Text a tap commits in LONG_PRESS-only layouts (the default-order form).
    pub tap_text: String,
    /// Long-press popup contents in display order.
    pub forms: Vec<FidelForm>,
}

/// A vowel-order key for TWO_KEY entry.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FidelVowelKey {
    /// Send in `KeyEvent::FidelVowel`.
    pub order: u8,
    pub label: String,
}

/// An Ethiopic-numeral long-press alternative set for a digit key.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FidelDigit {
    pub digit: String,
    /// Send the chosen text as `KeyEvent::Char`.
    pub alternatives: Vec<String>,
}

/// Everything the host needs to draw a direct Fidel keyboard.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct FidelLayout {
    pub language_code: String,
    pub layout_id: String,
    pub layout_version: String,
    /// `DRAFT` (not yet approved by native-speaker review) or `APPROVED`.
    pub status: String,
    /// Host shows the long-press popup of `FidelKey::forms`.
    pub long_press: bool,
    /// Host sends `FidelConsonant` / `FidelVowel` and shows `vowel_keys`.
    pub two_key: bool,
    /// Order shown when a consonant key starts a TWO_KEY composition.
    pub default_order: u8,
    pub rows: Vec<Vec<FidelKey>>,
    pub vowel_keys: Vec<FidelVowelKey>,
    /// Ethiopic punctuation marks to offer (e.g. on long-press of the period key).
    pub punctuation: Vec<String>,
    pub digits: Vec<FidelDigit>,
}
