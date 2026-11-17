//! Frozen, immutable radix trie in a flat byte buffer (the `DICTIONARY` pack artifact format
//! "QLX1"). Queries run directly on the bytes (no deserialisation), so a memory-mapped buffer
//! works and load cost is one validation pass.
//!
//! Layout (all integers LEB128 varints unless noted):
//! ```text
//! magic "QLX1" | word_count u32 LE | root_offset u32 LE | body
//! node := terminal_plus_1 | max_subtree_freq | child_count
//!         { label_len | label bytes (UTF-8, non-empty) | back_delta }*
//! ```
//! `terminal_plus_1` is 0 for a non-word node, else `frequency + 1`. `back_delta` is
//! `node_start - child_start` (children are written before their parent, so the structure is
//! a tree by construction and loading validates that). `max_subtree_freq` lets prefix search
//! return the exact top-k by frequency with a best-first walk.

use std::cmp::Ordering;
use std::collections::BinaryHeap;

use unicode_normalization::UnicodeNormalization;

pub(super) const MAGIC: &[u8; 4] = b"QLX1";
const HEADER: usize = 12;
/// Words longer than this (bytes, after NFC) are skipped by the builder.
pub(crate) const MAX_WORD_BYTES: usize = 64;

pub(super) fn put_varint(out: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        out.push((v as u8 & 0x7f) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

pub(super) fn get_varint(buf: &[u8], pos: &mut usize) -> Option<u64> {
    let mut v = 0u64;
    for shift in (0..70).step_by(7) {
        let b = *buf.get(*pos)?;
        *pos += 1;
        if shift == 63 && b > 1 {
            return None;
        }
        v |= u64::from(b & 0x7f) << shift;
        if b & 0x80 == 0 {
            return Some(v);
        }
    }
    None
}

/// Builds the serialised trie. Words are NFC-normalised; duplicates keep the highest
/// frequency; empty and over-long words are dropped.
pub(super) fn build(entries: impl IntoIterator<Item = (String, u32)>) -> Vec<u8> {
    let mut list: Vec<(String, u32)> = entries
        .into_iter()
        .map(|(w, f)| (w.nfc().collect::<String>(), f))
        .filter(|(w, f)| !w.is_empty() && w.len() <= MAX_WORD_BYTES && *f < u32::MAX)
        .collect();
    list.sort_by(|a, b| a.0.cmp(&b.0).then(b.1.cmp(&a.1)));
    list.dedup_by(|later, first| later.0 == first.0);
    let mut body = Vec::new();
    let refs: Vec<(&str, u32)> = list.iter().map(|(w, f)| (w.as_str(), *f)).collect();
    let (root, _) = write_node(&mut body, &refs, 0);
    let mut out = Vec::with_capacity(HEADER + body.len());
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&(list.len() as u32).to_le_bytes());
    out.extend_from_slice(&root.to_le_bytes());
    out.extend_from_slice(&body);
    out
}

/// Writes the node for `entries` (all sharing their first `depth` bytes); returns its offset
/// and max frequency. Recursion depth is bounded by `MAX_WORD_BYTES`.
fn write_node(out: &mut Vec<u8>, entries: &[(&str, u32)], depth: usize) -> (u32, u32) {
    let mut rest = entries;
    let mut own = 0u32;
    let mut terminal = false;
    if let Some((w, f)) = rest.first() {
        if w.len() == depth {
            terminal = true;
            own = *f;
            rest = &rest[1..];
        }
    }
    let mut children: Vec<(&str, u32)> = Vec::new(); // (label, offset)
    let mut max = if terminal { own } else { 0 };
    let mut i = 0;
    while i < rest.len() {
        let first = rest[i].0[depth..].chars().next().unwrap_or('\0');
        let mut j = i + 1;
        while j < rest.len() && rest[j].0[depth..].starts_with(first) {
            j += 1;
        }
        let group = &rest[i..j];
        // Longest common prefix of the group beyond `depth`, on a char boundary. The group
        // is sorted, so the LCP of its first and last members is the group's LCP.
        let a = group[0].0;
        let z = group[group.len() - 1].0;
        let mut end = depth;
        for (ca, cz) in a[depth..].chars().zip(z[depth..].chars()) {
            if ca != cz {
                break;
            }
            end += ca.len_utf8();
        }
        let (off, m) = write_node(out, group, end);
        children.push((&a[depth..end], off));
        max = max.max(m);
        i = j;
    }
    let start = out.len();
    put_varint(out, if terminal { u64::from(own) + 1 } else { 0 });
    put_varint(out, u64::from(max));
    put_varint(out, children.len() as u64);
    for (label, off) in children {
        put_varint(out, label.len() as u64);
        out.extend_from_slice(label.as_bytes());
        put_varint(out, (start - off as usize) as u64);
    }
    (start as u32, max)
}

#[derive(Clone, Copy)]
struct Node {
    freq: Option<u32>,
    max: u32,
    children: u32,
    /// Position of the first child record in the body.
    pos: usize,
    start: usize,
}

#[derive(Debug, Clone)]
pub(super) struct Trie {
    bytes: Vec<u8>,
    words: u32,
    root: u32,
}

struct HeapItem {
    score: u32,
    word: Vec<u8>,
    node: Option<usize>,
}
impl PartialEq for HeapItem {
    fn eq(&self, o: &Self) -> bool {
        self.cmp(o) == Ordering::Equal
    }
}
impl Eq for HeapItem {}
impl PartialOrd for HeapItem {
    fn partial_cmp(&self, o: &Self) -> Option<Ordering> {
        Some(self.cmp(o))
    }
}
impl Ord for HeapItem {
    // Max-heap: higher score first, then lexicographically smaller word first. A node's key
    // (subtree max, prefix) bounds every word beneath it, so pops come out in exact order.
    fn cmp(&self, o: &Self) -> Ordering {
        self.score
            .cmp(&o.score)
            .then_with(|| o.word.cmp(&self.word))
            .then_with(|| o.node.is_some().cmp(&self.node.is_some()))
    }
}

impl Trie {
    pub(super) fn from_bytes(bytes: Vec<u8>) -> Result<Self, &'static str> {
        if bytes.len() < HEADER || &bytes[..4] != MAGIC {
            return Err("not a lexicon payload");
        }
        let words = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
        let root = u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]);
        let t = Trie { bytes, words, root };
        t.validate()?;
        Ok(t)
    }

    pub(super) fn as_bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub(super) fn word_count(&self) -> u32 {
        self.words
    }

    fn body(&self) -> &[u8] {
        &self.bytes[HEADER..]
    }

    fn node(&self, start: usize) -> Option<Node> {
        let b = self.body();
        let mut p = start;
        let t = get_varint(b, &mut p)?;
        let max = u32::try_from(get_varint(b, &mut p)?).ok()?;
        let children = u32::try_from(get_varint(b, &mut p)?).ok()?;
        let freq = match t {
            0 => None,
            n => Some(u32::try_from(n - 1).ok()?),
        };
        Some(Node {
            freq,
            max,
            children,
            pos: p,
            start,
        })
    }

    /// Reads one child record at `*pos`: (label, child offset).
    fn child(&self, node: &Node, pos: &mut usize) -> Option<(&[u8], usize)> {
        let b = self.body();
        let len = usize::try_from(get_varint(b, pos)?).ok()?;
        let label = b.get(*pos..pos.checked_add(len)?)?;
        *pos += len;
        let back = usize::try_from(get_varint(b, pos)?).ok()?;
        Some((label, node.start.checked_sub(back)?))
    }

    /// Full structural validation: a bounds-safe tree, UTF-8 labels, consistent maxima and
    /// word count. After this succeeds, queries cannot index out of range or loop.
    fn validate(&self) -> Result<(), &'static str> {
        const BAD: &str = "malformed lexicon payload";
        let body_len = self.body().len();
        let root = self.root as usize;
        if root >= body_len {
            return Err(BAD);
        }
        let mut seen = vec![0u64; body_len / 64 + 1];
        let mut stack = vec![root];
        let mut terminals = 0u64;
        while let Some(start) = stack.pop() {
            if start >= body_len || (seen[start / 64] >> (start % 64)) & 1 == 1 {
                return Err(BAD);
            }
            seen[start / 64] |= 1 << (start % 64);
            let n = self.node(start).ok_or(BAD)?;
            let mut max = n.freq.unwrap_or(0);
            terminals += u64::from(n.freq.is_some());
            let mut pos = n.pos;
            let mut prev: &[u8] = &[];
            for _ in 0..n.children {
                let (label, off) = self.child(&n, &mut pos).ok_or(BAD)?;
                if label.is_empty() || off >= start || std::str::from_utf8(label).is_err() {
                    return Err(BAD);
                }
                // Children are sorted by first char, strictly increasing (so distinct).
                let lead = lead_char(label);
                if lead <= prev {
                    return Err(BAD);
                }
                prev = lead;
                max = max.max(self.node(off).ok_or(BAD)?.max);
                stack.push(off);
            }
            if max != n.max {
                return Err(BAD);
            }
        }
        if terminals != u64::from(self.words) {
            return Err(BAD);
        }
        Ok(())
    }

    fn find_child(&self, node: &Node, lead: &[u8]) -> Option<(&[u8], usize)> {
        let mut pos = node.pos;
        for _ in 0..node.children {
            let (label, off) = self.child(node, &mut pos)?;
            if lead_char(label) == lead {
                return Some((label, off));
            }
        }
        None
    }

    /// Frequency of `word` if it is in the lexicon.
    pub(super) fn frequency(&self, word: &str) -> Option<u32> {
        let mut acc = Vec::new();
        let n = self.descend(word.as_bytes(), &mut acc)?;
        // Ending mid-label leaves `acc` longer than the word: not a word.
        if acc.len() == word.len() {
            n.freq
        } else {
            None
        }
    }

    /// Follows `prefix`; returns the node whose subtree holds all words with that prefix and
    /// appends the full path to that node (>= prefix length) to `acc`.
    fn descend(&self, prefix: &[u8], acc: &mut Vec<u8>) -> Option<Node> {
        let mut cur = self.node(self.root as usize)?;
        let mut rem = prefix;
        while !rem.is_empty() {
            let (label, off) = self.find_child(&cur, lead_char(rem))?;
            if rem.len() <= label.len() {
                if !label.starts_with(rem) {
                    return None;
                }
                acc.extend_from_slice(label);
                return self.node(off);
            }
            if !rem.starts_with(label) {
                return None;
            }
            acc.extend_from_slice(label);
            rem = &rem[label.len()..];
            cur = self.node(off)?;
        }
        Some(cur)
    }

    /// Top `limit` words starting with `prefix` by (frequency desc, word asc), skipping
    /// words for which `skip` is true.
    pub(super) fn prefix(
        &self,
        prefix: &str,
        limit: usize,
        skip: &dyn Fn(&str) -> bool,
    ) -> Vec<(String, u32)> {
        let mut out = Vec::new();
        if limit == 0 {
            return out;
        }
        let mut acc = Vec::new();
        let Some(start) = self.descend(prefix.as_bytes(), &mut acc) else {
            return out;
        };
        let mut heap = BinaryHeap::new();
        heap.push(HeapItem {
            score: start.max,
            word: acc,
            node: Some(start.start),
        });
        while let Some(item) = heap.pop() {
            let Some(off) = item.node else {
                if let Ok(w) = String::from_utf8(item.word) {
                    if !skip(&w) {
                        out.push((w, item.score));
                        if out.len() == limit {
                            break;
                        }
                    }
                }
                continue;
            };
            let Some(n) = self.node(off) else { continue };
            if let Some(f) = n.freq {
                heap.push(HeapItem {
                    score: f,
                    word: item.word.clone(),
                    node: None,
                });
            }
            let mut pos = n.pos;
            for _ in 0..n.children {
                let Some((label, coff)) = self.child(&n, &mut pos) else {
                    break;
                };
                let Some(c) = self.node(coff) else { continue };
                let mut w = Vec::with_capacity(item.word.len() + label.len());
                w.extend_from_slice(&item.word);
                w.extend_from_slice(label);
                heap.push(HeapItem {
                    score: c.max,
                    word: w,
                    node: Some(coff),
                });
            }
        }
        out
    }
}

/// Bytes of the first UTF-8 character of `s` (clamped to the slice; `s` is non-empty).
fn lead_char(s: &[u8]) -> &[u8] {
    let n = match s.first() {
        Some(b) if *b >= 0xf0 => 4,
        Some(b) if *b >= 0xe0 => 3,
        Some(b) if *b >= 0xc0 => 2,
        _ => 1,
    };
    &s[..n.min(s.len())]
}
