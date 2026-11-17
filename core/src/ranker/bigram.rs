//! Next-word (bigram) model and its local serialisation ("QBG1").
//!
//! ```text
//! magic "QBG1" | prev_count u32 { len u16 | prev bytes | next_count u16
//!                                 { len u16 | next bytes | count u32 }* }*   (little endian)
//! ```
//!
//! The same format carries the shipped `FREQUENCY_LIST` payload and the user's local overlay.
//! Previous words are stored under their equivalence key (`fold_key`, so ሰላም and ሠላም share
//! statistics); next words keep their own spelling. Loading validates everything and never
//! panics on malformed input.

use std::collections::HashMap;

use unicode_normalization::UnicodeNormalization;

use super::fold::fold_key;
use crate::lexicon::MAX_WORD_BYTES;

const MAGIC: &[u8; 4] = b"QBG1";
/// Most next-words kept per previous word when building a model.
pub const MAX_NEXT_PER_WORD: usize = 64;
/// Most (previous, next) pairs a model may hold.
pub const MAX_PAIRS: usize = 1_000_000;

#[derive(Debug, Clone)]
pub(super) struct Entry {
    pub(super) next: String,
    /// Equivalence key of `next`, cached so matching does not re-fold.
    pub(super) key: String,
    pub(super) count: u32,
}

#[derive(Debug, Default, Clone)]
pub(super) struct BigramModel {
    /// Folded previous word -> (next word, count), best first.
    map: HashMap<String, Vec<Entry>>,
    pairs: usize,
}

fn nfc(s: &str) -> String {
    s.nfc().collect()
}

fn sort_entries(v: &mut [Entry]) {
    v.sort_by(|a, b| b.count.cmp(&a.count).then_with(|| a.next.cmp(&b.next)));
}

fn word_ok(w: &str) -> bool {
    !w.is_empty()
        && w.len() <= MAX_WORD_BYTES
        && !w.chars().any(|c| c.is_whitespace() || c.is_control())
}

impl BigramModel {
    pub(super) fn pair_count(&self) -> usize {
        self.pairs
    }

    /// Adds `by` to (prev, next). False if either word is invalid or the model is full.
    pub(super) fn add(&mut self, prev: &str, next: &str, by: u32, cap: usize) -> bool {
        if !self.insert(prev, next, by, cap) {
            return false;
        }
        if let Some(v) = self.map.get_mut(&fold_key(&nfc(prev))) {
            sort_entries(v);
        }
        true
    }

    /// `add` without re-sorting (bulk loads call `sort_all` once at the end).
    fn insert(&mut self, prev: &str, next: &str, by: u32, cap: usize) -> bool {
        let (prev, next) = (nfc(prev), nfc(next));
        if !word_ok(&prev) || !word_ok(&next) {
            return false;
        }
        let nk = fold_key(&next);
        let v = self.map.entry(fold_key(&prev)).or_default();
        if let Some(e) = v.iter_mut().find(|e| e.key == nk) {
            e.count = e.count.saturating_add(by);
        } else {
            if self.pairs >= cap {
                return false;
            }
            v.push(Entry {
                next,
                key: nk,
                count: by,
            });
            self.pairs += 1;
        }
        true
    }

    fn sort_all(&mut self) {
        self.map.values_mut().for_each(|v| sort_entries(v));
    }

    /// Next words recorded after `prev` (any spelling in its equivalence class), best first.
    pub(super) fn after(&self, prev: &str) -> &[Entry] {
        self.map
            .get(&fold_key(&nfc(prev)))
            .map_or(&[], Vec::as_slice)
    }

    /// Builds a model, keeping the best `MAX_NEXT_PER_WORD` next words per previous word.
    pub(super) fn from_triples(triples: impl IntoIterator<Item = (String, String, u32)>) -> Self {
        let mut m = BigramModel::default();
        for (p, n, c) in triples {
            m.insert(&p, &n, c, MAX_PAIRS);
        }
        m.sort_all();
        for v in m.map.values_mut() {
            v.truncate(MAX_NEXT_PER_WORD);
        }
        m.pairs = m.map.values().map(Vec::len).sum();
        m
    }

    pub(super) fn to_bytes(&self) -> Vec<u8> {
        let mut keys: Vec<&String> = self.map.keys().collect();
        keys.sort();
        let mut out = MAGIC.to_vec();
        out.extend((keys.len() as u32).to_le_bytes());
        for k in keys {
            let v = &self.map[k];
            out.extend((k.len() as u16).to_le_bytes());
            out.extend(k.as_bytes());
            out.extend((v.len() as u16).to_le_bytes());
            for e in v {
                out.extend((e.next.len() as u16).to_le_bytes());
                out.extend(e.next.as_bytes());
                out.extend(e.count.to_le_bytes());
            }
        }
        out
    }

    pub(super) fn from_bytes(b: &[u8]) -> Result<Self, &'static str> {
        const BAD: &str = "invalid bigram data";
        if b.len() < 8 || &b[..4] != MAGIC {
            return Err(BAD);
        }
        let mut pos = 4usize;
        fn take<'a>(b: &'a [u8], pos: &mut usize, n: usize) -> Option<&'a [u8]> {
            let end = pos.checked_add(n)?;
            let s = b.get(*pos..end)?;
            *pos = end;
            Some(s)
        }
        fn u16le(b: &[u8], pos: &mut usize) -> Option<usize> {
            take(b, pos, 2).map(|s| u16::from_le_bytes([s[0], s[1]]) as usize)
        }
        fn text(b: &[u8], pos: &mut usize) -> Option<String> {
            let n = u16le(b, pos)?;
            let s = std::str::from_utf8(take(b, pos, n)?).ok()?;
            word_ok(s).then(|| s.to_string())
        }
        let prev_count = take(b, &mut pos, 4)
            .map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]) as usize)
            .ok_or(BAD)?;
        // Each entry needs at least 4 bytes, which bounds the work by the input size.
        if prev_count > (b.len() - pos) / 4 {
            return Err(BAD);
        }
        let mut m = BigramModel::default();
        for _ in 0..prev_count {
            let prev = text(b, &mut pos).ok_or(BAD)?;
            let n = u16le(b, &mut pos).ok_or(BAD)?;
            if n > (b.len() - pos) / 6 {
                return Err(BAD);
            }
            for _ in 0..n {
                let next = text(b, &mut pos).ok_or(BAD)?;
                let c = take(b, &mut pos, 4).ok_or(BAD)?;
                let count = u32::from_le_bytes([c[0], c[1], c[2], c[3]]);
                if !m.insert(&prev, &next, count, MAX_PAIRS) {
                    return Err(BAD);
                }
            }
        }
        m.sort_all();
        if pos != b.len() {
            return Err(BAD);
        }
        Ok(m)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn model() -> BigramModel {
        BigramModel::from_triples([
            ("ሰላም".to_string(), "ነው".to_string(), 50),
            ("ሠላም".to_string(), "ነው".to_string(), 10),
            ("ሰላም".to_string(), "ለእናንተ".to_string(), 70),
            ("good".to_string(), "morning".to_string(), 9),
        ])
    }

    #[test]
    fn homophone_prevs_share_statistics() {
        let m = model();
        let after = m.after("ሠላም");
        assert_eq!((after[0].next.as_str(), after[0].count), ("ለእናንተ", 70));
        assert_eq!((after[1].next.as_str(), after[1].count), ("ነው", 60));
        assert_eq!(m.after("Good")[0].next, "morning");
        assert!(m.after("unknown").is_empty());
    }

    #[test]
    fn round_trip_is_identical() {
        let m = model();
        let bytes = m.to_bytes();
        let m2 = BigramModel::from_bytes(&bytes).unwrap();
        assert_eq!(m2.to_bytes(), bytes);
        assert_eq!(m2.pair_count(), 3);
    }

    #[test]
    fn malformed_input_is_rejected_without_panics() {
        let good = model().to_bytes();
        assert!(BigramModel::from_bytes(&[]).is_err());
        assert!(BigramModel::from_bytes(b"QBG1").is_err());
        for n in 0..good.len() {
            assert!(BigramModel::from_bytes(&good[..n]).is_err(), "prefix {n}");
        }
        let mut trailing = good.clone();
        trailing.push(0);
        assert!(BigramModel::from_bytes(&trailing).is_err());
        for i in 0..good.len() {
            for d in [1u8, 0x80, 0xff] {
                let mut b = good.clone();
                b[i] ^= d;
                let _ = BigramModel::from_bytes(&b);
            }
        }
        // Huge declared counts fail fast.
        let mut huge = b"QBG1".to_vec();
        huge.extend(u32::MAX.to_le_bytes());
        assert!(BigramModel::from_bytes(&huge).is_err());
    }

    #[test]
    fn caps_and_validation() {
        let mut m = BigramModel::default();
        assert!(!m.add("", "x", 1, 10));
        assert!(!m.add("a b", "x", 1, 10));
        assert!(m.add("a", "b", 1, 1));
        assert!(!m.add("a", "c", 1, 1), "cap refuses a new pair");
        assert!(m.add("a", "b", 1, 1), "existing pair can still grow");
        assert_eq!(m.after("a")[0].count, 2);
        let many = (0..200).map(|i| ("p".to_string(), format!("n{i}"), i));
        assert_eq!(
            BigramModel::from_triples(many).after("p").len(),
            MAX_NEXT_PER_WORD
        );
    }
}
