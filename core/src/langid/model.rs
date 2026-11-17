//! Character n-gram naive-Bayes tables for the language-ID detector (C1.8).
//!
//! Features are character 1..3-grams of the lower-cased word with `^`/`$` boundary markers.
//! Each feature stores one quantised log-probability (1/256 nat, `i16`) per class; features a
//! class never saw score that class's smoothing floor. The serialised form is the payload of a
//! `LANGUAGE_ID_MODEL` pack resource (SD 7.5) and is fully validated on load.

use std::collections::HashMap;

use super::{LangIdError, LangTag};

const MAGIC: &[u8; 4] = b"QLID";
const VERSION: u8 = 1;
const SCALE: f32 = 256.0;
const ALPHA: f64 = 0.5;
const MAX_CLASSES: usize = 16;
const MAX_FEATURES: u32 = 2_000_000;
const MAX_NGRAM_CHARS: usize = 3;

pub(super) struct Model {
    pub classes: Vec<LangTag>,
    floors: Vec<i16>,
    table: HashMap<String, Vec<i16>>,
}

fn invalid(reason: &str) -> LangIdError {
    LangIdError::InvalidData {
        reason: reason.to_string(),
    }
}

/// Calls `f` for every feature of `word` (already normalised).
fn for_each_feature(word: &str, mut f: impl FnMut(&str)) {
    let mut chars: Vec<char> = Vec::with_capacity(word.chars().count() + 2);
    chars.push('^');
    chars.extend(word.chars());
    chars.push('$');
    let mut buf = String::new();
    for n in 1..=MAX_NGRAM_CHARS {
        if chars.len() < n {
            break;
        }
        for w in chars.windows(n) {
            buf.clear();
            buf.extend(w);
            f(&buf);
        }
    }
}

impl Model {
    /// Trains from labelled words. Classes are the distinct tags, in first-seen order.
    pub fn train(samples: &[(LangTag, String)]) -> Model {
        let mut classes: Vec<LangTag> = Vec::new();
        for (t, _) in samples {
            if !classes.contains(t) {
                classes.push(*t);
            }
        }
        let k = classes.len();
        let mut counts: HashMap<String, Vec<u32>> = HashMap::new();
        let mut totals = vec![0u64; k];
        for (tag, word) in samples {
            let ci = classes.iter().position(|c| c == tag).unwrap_or(0);
            for_each_feature(word, |feat| {
                counts.entry(feat.to_string()).or_insert_with(|| vec![0; k])[ci] += 1;
                totals[ci] += 1;
            });
        }
        let vocab = counts.len() as f64;
        let q = |p: f64| (p.ln() as f32 * SCALE).round().clamp(-32768.0, 0.0) as i16;
        // Every class is rescaled to the same total mass so unequal seed sizes do not bias the
        // posterior (an unseen feature would otherwise favour the smallest class).
        let mean = totals.iter().sum::<u64>() as f64 / k.max(1) as f64;
        let scale = |ci: usize| mean / (totals[ci].max(1) as f64);
        let denom = mean + ALPHA * vocab;
        let floors = (0..k).map(|_| q(ALPHA / denom)).collect();
        let table = counts
            .into_iter()
            .map(|(feat, c)| {
                let v = (0..k)
                    .map(|ci| q((f64::from(c[ci]) * scale(ci) + ALPHA) / denom))
                    .collect();
                (feat, v)
            })
            .collect();
        Model {
            classes,
            floors,
            table,
        }
    }

    /// Log-likelihood (nats) of `word` under each class.
    pub fn log_likelihoods(&self, word: &str) -> Vec<f32> {
        let mut sums: Vec<i32> = vec![0; self.classes.len()];
        for_each_feature(word, |feat| match self.table.get(feat) {
            Some(v) => {
                for (s, x) in sums.iter_mut().zip(v) {
                    *s += i32::from(*x);
                }
            }
            None => {
                for (s, x) in sums.iter_mut().zip(&self.floors) {
                    *s += i32::from(*x);
                }
            }
        });
        sums.into_iter().map(|s| s as f32 / SCALE).collect()
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let k = self.classes.len();
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.push(k as u8);
        out.extend(self.classes.iter().map(|c| c.to_u8()));
        for f in &self.floors {
            out.extend_from_slice(&f.to_le_bytes());
        }
        out.extend_from_slice(&(self.table.len() as u32).to_le_bytes());
        let mut keys: Vec<&String> = self.table.keys().collect();
        keys.sort(); // deterministic output
        for key in keys {
            out.push(key.len() as u8);
            out.extend_from_slice(key.as_bytes());
            for x in &self.table[key] {
                out.extend_from_slice(&x.to_le_bytes());
            }
        }
        out
    }

    pub fn from_bytes(b: &[u8]) -> Result<Model, LangIdError> {
        let mut r = Reader { b, pos: 0 };
        if r.take(4)? != MAGIC {
            return Err(invalid("bad magic"));
        }
        if r.u8()? != VERSION {
            return Err(invalid("unsupported version"));
        }
        let k = usize::from(r.u8()?);
        if k == 0 || k > MAX_CLASSES {
            return Err(invalid("bad class count"));
        }
        let mut classes = Vec::with_capacity(k);
        for _ in 0..k {
            let t = LangTag::from_u8(r.u8()?).ok_or_else(|| invalid("unknown class"))?;
            if classes.contains(&t) {
                return Err(invalid("duplicate class"));
            }
            classes.push(t);
        }
        let floors = (0..k).map(|_| r.i16()).collect::<Result<Vec<_>, _>>()?;
        let n = r.u32()?;
        if n > MAX_FEATURES {
            return Err(invalid("too many features"));
        }
        let mut table = HashMap::new();
        for _ in 0..n {
            let len = usize::from(r.u8()?);
            if len == 0 {
                return Err(invalid("empty feature"));
            }
            let key = std::str::from_utf8(r.take(len)?)
                .map_err(|_| invalid("feature is not utf-8"))?
                .to_string();
            let v = (0..k).map(|_| r.i16()).collect::<Result<Vec<_>, _>>()?;
            if table.insert(key, v).is_some() {
                return Err(invalid("duplicate feature"));
            }
        }
        if r.pos != b.len() {
            return Err(invalid("trailing bytes"));
        }
        Ok(Model {
            classes,
            floors,
            table,
        })
    }
}

struct Reader<'a> {
    b: &'a [u8],
    pos: usize,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], LangIdError> {
        let end = self
            .pos
            .checked_add(n)
            .ok_or_else(|| invalid("truncated"))?;
        let s = self
            .b
            .get(self.pos..end)
            .ok_or_else(|| invalid("truncated"))?;
        self.pos = end;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8, LangIdError> {
        Ok(self.take(1)?[0])
    }
    fn i16(&mut self) -> Result<i16, LangIdError> {
        let s = self.take(2)?;
        Ok(i16::from_le_bytes([s[0], s[1]]))
    }
    fn u32(&mut self) -> Result<u32, LangIdError> {
        let s = self.take(4)?;
        Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }
}
