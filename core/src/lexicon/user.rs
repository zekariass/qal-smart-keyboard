//! User-dictionary overlay state and its local serialisation ("QUD1").
//!
//! ```text
//! magic "QUD1" | word_count varint { len varint | bytes | count varint }*
//!              | blocked_count varint { len varint | bytes }*
//! ```

use std::collections::{BTreeMap, BTreeSet};

use super::trie::{get_varint, put_varint};
use super::{valid_user_word, MAX_USER_WORDS};

const MAGIC: &[u8; 4] = b"QUD1";

#[derive(Debug, Default)]
pub(super) struct UserState {
    /// Added/learned words and their use counts (0 = added but never used).
    words: BTreeMap<String, u32>,
    /// Words removed by the user; hidden from the base lexicon.
    blocked: BTreeSet<String>,
}

impl UserState {
    pub(super) fn len(&self) -> usize {
        self.words.len()
    }

    pub(super) fn count(&self, word: &str) -> Option<u32> {
        self.words.get(word).copied()
    }

    pub(super) fn is_blocked(&self, word: &str) -> bool {
        self.blocked.contains(word)
    }

    /// User words starting with `prefix`, in alphabetical order.
    pub(super) fn with_prefix<'a>(
        &'a self,
        prefix: &'a str,
    ) -> impl Iterator<Item = (&'a str, u32)> + 'a {
        self.words
            .range::<str, _>((
                std::ops::Bound::Included(prefix),
                std::ops::Bound::Unbounded,
            ))
            .take_while(move |(w, _)| w.starts_with(prefix))
            .map(|(w, c)| (w.as_str(), *c))
    }

    /// Adds `word` (un-blocking it) and raises its count by `by`. False when full.
    pub(super) fn add(&mut self, word: String, by: u32) -> bool {
        if !self.words.contains_key(&word) && self.words.len() >= MAX_USER_WORDS {
            return false;
        }
        self.blocked.remove(&word);
        let c = self.words.entry(word).or_insert(0);
        *c = c.saturating_add(by);
        true
    }

    /// Removes `word`; blocks it when it also exists in the base lexicon.
    pub(super) fn remove(&mut self, word: String, in_base: bool) -> bool {
        let was_user = self.words.remove(&word).is_some();
        let was_visible = in_base && !self.blocked.contains(&word);
        if in_base {
            self.blocked.insert(word);
        }
        was_user || was_visible
    }

    pub(super) fn to_bytes(&self) -> Vec<u8> {
        let mut out = MAGIC.to_vec();
        put_varint(&mut out, self.words.len() as u64);
        for (w, c) in &self.words {
            put_varint(&mut out, w.len() as u64);
            out.extend_from_slice(w.as_bytes());
            put_varint(&mut out, u64::from(*c));
        }
        put_varint(&mut out, self.blocked.len() as u64);
        for w in &self.blocked {
            put_varint(&mut out, w.len() as u64);
            out.extend_from_slice(w.as_bytes());
        }
        out
    }

    pub(super) fn from_bytes(buf: &[u8]) -> Result<Self, &'static str> {
        const BAD: &str = "malformed user dictionary";
        if buf.len() < 4 || &buf[..4] != MAGIC {
            return Err("not a user dictionary");
        }
        let mut pos = 4;
        let read_word = |pos: &mut usize| -> Result<String, &'static str> {
            let len = usize::try_from(get_varint(buf, pos).ok_or(BAD)?).map_err(|_| BAD)?;
            let end = pos.checked_add(len).ok_or(BAD)?;
            let s = std::str::from_utf8(buf.get(*pos..end).ok_or(BAD)?).map_err(|_| BAD)?;
            *pos = end;
            if !valid_user_word(s) {
                return Err(BAD);
            }
            Ok(s.to_string())
        };
        let mut st = UserState::default();
        let n = get_varint(buf, &mut pos).ok_or(BAD)?;
        if n > MAX_USER_WORDS as u64 {
            return Err(BAD);
        }
        for _ in 0..n {
            let w = read_word(&mut pos)?;
            let c = u32::try_from(get_varint(buf, &mut pos).ok_or(BAD)?).map_err(|_| BAD)?;
            st.words.insert(w, c);
        }
        let m = get_varint(buf, &mut pos).ok_or(BAD)?;
        if m > MAX_USER_WORDS as u64 {
            return Err(BAD);
        }
        for _ in 0..m {
            st.blocked.insert(read_word(&mut pos)?);
        }
        if pos != buf.len() {
            return Err(BAD);
        }
        Ok(st)
    }
}
