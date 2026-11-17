//! Direct Fidel key handling (plan step C1.3).
//!
//! LONG_PRESS: the host shows a key's popup forms and sends the chosen syllable as
//! `KeyEvent::Char`. TWO_KEY: `FidelConsonant` starts a composition showing the default order
//! (the sixth by default, per SD 7.2); `FidelVowel` replaces it with the chosen order and
//! commits. Output is NFC and no text is ever logged (N2).

use super::layout::{KeyDef, Layout};
use crate::engine::{EditorUpdate, KeyEvent};
use std::sync::Arc;
use unicode_normalization::UnicodeNormalization;

/// Longest passthrough text accepted in one `Char` event (in characters).
pub const MAX_CHAR_EVENT_CHARS: usize = 64;

#[derive(Debug, Clone)]
struct Pending {
    key: KeyDef,
    shown: char,
}

/// Direct-Fidel session state for one active layout.
#[derive(Debug)]
pub struct DirectFidel {
    layout: Arc<Layout>,
    pending: Option<Pending>,
}

impl DirectFidel {
    pub fn new(layout: Arc<Layout>) -> Self {
        Self {
            layout,
            pending: None,
        }
    }

    pub fn layout(&self) -> &Arc<Layout> {
        &self.layout
    }

    fn shown(&self) -> String {
        self.pending
            .as_ref()
            .map(|p| p.shown.to_string())
            .unwrap_or_default()
    }

    /// Takes the pending composition as text to commit.
    fn flush(&mut self) -> String {
        self.pending
            .take()
            .map(|p| p.shown.to_string())
            .unwrap_or_default()
    }

    /// An update that changes nothing but keeps the host's composing region in sync.
    fn unchanged(&self) -> EditorUpdate {
        EditorUpdate {
            composing: self.shown(),
            ..Default::default()
        }
    }

    pub fn reset(&mut self) {
        self.pending = None;
    }

    pub fn on_key(&mut self, event: KeyEvent) -> EditorUpdate {
        match event {
            KeyEvent::Char { text } => {
                let mut commit = self.flush();
                if let Some(t) = sanitize(&text) {
                    commit.push_str(&t);
                }
                EditorUpdate {
                    commit,
                    ..Default::default()
                }
            }
            KeyEvent::FidelConsonant { series_id } => self.consonant(&series_id),
            KeyEvent::FidelVowel { order } => self.vowel(order),
            KeyEvent::Space => self.flush_with(" "),
            KeyEvent::Enter => self.flush_with("\n"),
            KeyEvent::Commit => self.flush_with(""),
            KeyEvent::Backspace => {
                if self.pending.take().is_some() {
                    // Cancel the composition; nothing was committed yet.
                    EditorUpdate::default()
                } else {
                    // One grapheme cluster, never a UTF-16 code unit (the host counts).
                    EditorUpdate {
                        delete_before: 1,
                        ..Default::default()
                    }
                }
            }
        }
    }

    fn flush_with(&mut self, suffix: &str) -> EditorUpdate {
        let mut commit = self.flush();
        commit.push_str(suffix);
        EditorUpdate {
            commit,
            ..Default::default()
        }
    }

    fn consonant(&mut self, series_id: &str) -> EditorUpdate {
        if !self.layout.is_two_key() {
            return self.unchanged();
        }
        let Some(key) = self.layout.key(series_id).cloned() else {
            return self.unchanged();
        };
        let Some(shown) = self.layout.default_form(&key) else {
            return self.unchanged();
        };
        let commit = self.flush();
        self.pending = Some(Pending { key, shown });
        EditorUpdate {
            commit,
            composing: shown.to_string(),
            ..Default::default()
        }
    }

    fn vowel(&mut self, order: u8) -> EditorUpdate {
        let Some(p) = &self.pending else {
            return EditorUpdate::default();
        };
        if !self.layout.has_vowel_key(order) {
            return self.unchanged();
        }
        let Some(c) = self.layout.form_for(&p.key, order) else {
            // This series has no such order: keep the composition so the user can retry.
            return self.unchanged();
        };
        self.pending = None;
        EditorUpdate {
            commit: c.to_string(),
            ..Default::default()
        }
    }
}

/// Normalises host-supplied text to NFC. Returns `None` for text that must not be committed:
/// empty, longer than [`MAX_CHAR_EVENT_CHARS`], or containing control characters other than
/// newline and tab. Ethiopic syllables, punctuation and numerals pass through unchanged
/// (they are already NFC); other text (digits, Latin, emoji) is passed through, normalised.
pub(crate) fn sanitize(text: &str) -> Option<String> {
    if text.is_empty() || text.chars().count() > MAX_CHAR_EVENT_CHARS {
        return None;
    }
    if text
        .chars()
        .any(|c| c.is_control() && c != '\n' && c != '\t')
    {
        return None;
    }
    Some(text.nfc().collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    const AM: &str = r#"{
      "format": "qal-keyboard-layout", "formatVersion": 1, "language": "am",
      "layoutId": "am-test", "layoutVersion": "0.0.1", "status": "DRAFT",
      "interaction": ["LONG_PRESS", "TWO_KEY"], "popupOrders": [1,2,3,4,5,6,7,8],
      "vowelKeys": [{"order":1,"label":"ሀ"},{"order":4,"label":"ሃ"},{"order":8,"label":"ዋ"},{"order":9,"label":"x"}],
      "rows": [[{"series":"S","label":"ሰ"},{"series":"G","label":"ገ","labialized":"GW"},
                {"series":"Q","label":"ቀ","labialized":"QW"},{"series":"L","label":"ለ"},
                {"series":"GLOTTAL","label":"አ"},{"series":"V","label":"ቨ"}]],
      "punctuation": ["።"], "digits": []
    }"#;

    fn session() -> DirectFidel {
        DirectFidel::new(Arc::new(Layout::parse(AM.as_bytes()).unwrap()))
    }
    fn cons(s: &str) -> KeyEvent {
        KeyEvent::FidelConsonant {
            series_id: s.into(),
        }
    }
    fn vowel(order: u8) -> KeyEvent {
        KeyEvent::FidelVowel { order }
    }

    #[test]
    fn two_key_commits_chosen_order() {
        let mut d = session();
        let u = d.on_key(cons("S"));
        assert_eq!((u.composing.as_str(), u.commit.as_str()), ("ስ", ""));
        let u = d.on_key(vowel(4));
        assert_eq!((u.composing.as_str(), u.commit.as_str()), ("", "ሳ"));
        assert_eq!(d.on_key(vowel(4)), EditorUpdate::default());
    }

    #[test]
    fn labialised_order_eight() {
        let mut d = session();
        d.on_key(cons("G"));
        assert_eq!(d.on_key(vowel(8)).commit, "ጓ");
        d.on_key(cons("Q"));
        assert_eq!(d.on_key(vowel(8)).commit, "ቋ");
        d.on_key(cons("L"));
        assert_eq!(d.on_key(vowel(8)).commit, "ሏ");
    }

    #[test]
    fn missing_order_keeps_composition() {
        let mut d = session();
        d.on_key(cons("V"));
        // Order 9 is a vowel key of this layout but V has no ninth order: composition stays.
        let u = d.on_key(vowel(9));
        assert_eq!((u.composing.as_str(), u.commit.as_str()), ("ቭ", ""));
        // Order 2 is not a vowel key of this layout.
        assert_eq!(d.on_key(vowel(2)).composing, "ቭ");
    }

    #[test]
    fn second_consonant_commits_first() {
        let mut d = session();
        d.on_key(cons("S"));
        let u = d.on_key(cons("L"));
        assert_eq!((u.commit.as_str(), u.composing.as_str()), ("ስ", "ል"));
        assert_eq!(d.on_key(KeyEvent::Space).commit, "ል ");
    }

    #[test]
    fn space_enter_commit_flush() {
        for (ev, want) in [
            (KeyEvent::Space, "ስ "),
            (KeyEvent::Enter, "ስ\n"),
            (KeyEvent::Commit, "ስ"),
        ] {
            let mut d = session();
            d.on_key(cons("S"));
            let u = d.on_key(ev);
            assert_eq!(u.commit, want);
            assert!(u.composing.is_empty());
        }
        assert_eq!(session().on_key(KeyEvent::Commit), EditorUpdate::default());
    }

    #[test]
    fn backspace_cancels_composition_or_deletes() {
        let mut d = session();
        d.on_key(cons("S"));
        assert_eq!(d.on_key(KeyEvent::Backspace), EditorUpdate::default());
        assert_eq!(d.on_key(KeyEvent::Backspace).delete_before, 1);
    }

    #[test]
    fn long_press_char_commits_and_flushes_pending() {
        let mut d = session();
        assert_eq!(d.on_key(KeyEvent::Char { text: "ሴ".into() }).commit, "ሴ");
        d.on_key(cons("S"));
        let u = d.on_key(KeyEvent::Char { text: "።".into() });
        assert_eq!(u.commit, "ስ።");
        assert!(u.composing.is_empty());
    }

    #[test]
    fn char_passthrough_is_safe_and_nfc() {
        let mut d = session();
        assert_eq!(
            d.on_key(KeyEvent::Char {
                text: "e\u{301}".into()
            })
            .commit,
            "\u{e9}"
        );
        assert_eq!(d.on_key(KeyEvent::Char { text: "5".into() }).commit, "5");
        for bad in ["", "\u{0}", "\u{7f}", &"ሰ".repeat(MAX_CHAR_EVENT_CHARS + 1)] {
            assert_eq!(
                d.on_key(KeyEvent::Char { text: bad.into() }),
                EditorUpdate::default(),
                "should drop {bad:?}"
            );
        }
        // A dropped Char still commits a pending composition rather than losing it.
        d.on_key(cons("S"));
        assert_eq!(
            d.on_key(KeyEvent::Char {
                text: "\u{0}".into()
            })
            .commit,
            "ስ"
        );
    }

    #[test]
    fn unknown_consonant_is_ignored() {
        let mut d = session();
        assert_eq!(d.on_key(cons("QH")), EditorUpdate::default());
        assert_eq!(d.on_key(cons("")), EditorUpdate::default());
        d.on_key(cons("S"));
        assert_eq!(d.on_key(cons("NOPE")).composing, "ስ");
    }

    #[test]
    fn long_press_only_layout_ignores_two_key_events() {
        let src = AM.replace(r#"["LONG_PRESS", "TWO_KEY"]"#, r#"["LONG_PRESS"]"#);
        let mut d = DirectFidel::new(Arc::new(Layout::parse(src.as_bytes()).unwrap()));
        assert_eq!(d.on_key(cons("S")), EditorUpdate::default());
    }

    #[test]
    fn reset_drops_composition() {
        let mut d = session();
        d.on_key(cons("S"));
        d.reset();
        assert_eq!(d.on_key(KeyEvent::Commit), EditorUpdate::default());
    }
}
