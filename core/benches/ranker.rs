//! Ranker latency benchmark (plan C1.7, SD 17.1). Run: `cargo bench --bench ranker`.
//!
//! SD 17.1: candidate strip update p95 <= 30 ms (mid-range device). `Ranker::rank` includes the
//! lexicon lookups (their 5 ms share is gated by the lexicon bench); this gives the whole call
//! 10 ms p95 on the build host (a proxy; A1.9 re-measures on devices), leaving the rest of
//! the 30 ms for UI and IPC. Exits non-zero when exceeded.
//!
//! Corpus: the same deterministic synthetic 200k-word list as the lexicon bench (60% Ge'ez).
//! Queries: corpus prefixes, corpus words with one typo, and unknown words (worst case: the
//! full correction neighbourhood is searched).

use std::time::Instant;

use qal_keyboard_core::lexicon::{Lexicon, WordEntry};
use qal_keyboard_core::ranker::{bigram_payload_from_text, Ranker};

const BUDGET_P95_MS: f64 = 10.0;
const WORDS: usize = 200_000;

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

fn corpus() -> Vec<WordEntry> {
    let mut r = Rng(0x9E37_79B9_7F4A_7C15);
    let mut out = Vec::with_capacity(WORDS);
    for i in 0..WORDS {
        let mut w = String::new();
        if r.below(10) < 6 {
            for _ in 0..2 + r.below(7) {
                let cp = 0x1200 + (r.below(0x47) * r.below(0x47) / 0x47) as u32;
                w.push(char::from_u32(cp).unwrap());
            }
        } else {
            for _ in 0..3 + r.below(8) {
                let c = if r.below(3) == 0 {
                    b"aeiou"[r.below(5) as usize]
                } else {
                    b'a' + r.below(26) as u8
                };
                w.push(c as char);
            }
        }
        let freq = (2_000_000 / (1 + (i as u64 * 7 % 50_000))) as u32 + 1;
        out.push(WordEntry {
            word: w,
            frequency: freq,
        });
    }
    out
}

fn pct(sorted: &[f64], p: f64) -> f64 {
    sorted[((sorted.len() - 1) as f64 * p) as usize]
}

fn measure(label: &str, queries: &[String], mut f: impl FnMut(&str)) -> f64 {
    for q in queries.iter().take(200) {
        f(q);
    }
    let mut ms = Vec::with_capacity(queries.len());
    for q in queries {
        let t = Instant::now();
        f(q);
        ms.push(t.elapsed().as_secs_f64() * 1e3);
    }
    ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let p95 = pct(&ms, 0.95);
    println!(
        "{label:<34} n={:<6} p50={:.4} ms  p95={:.4} ms  p99={:.4} ms  max={:.4} ms",
        ms.len(),
        pct(&ms, 0.5),
        p95,
        pct(&ms, 0.99),
        ms[ms.len() - 1]
    );
    p95
}

fn main() {
    let entries = corpus();
    let lex = Lexicon::from_entries(entries.clone());
    let ranker = Ranker::new(lex.clone());
    let mut r = Rng(7);
    let mut text = String::new();
    for _ in 0..20_000 {
        let a = &entries[r.below(WORDS as u64) as usize].word;
        let b = &entries[r.below(2_000) as usize].word;
        text.push_str(&format!("{a}\t{b}\t{}\n", 1 + r.below(500)));
    }
    let t = Instant::now();
    ranker
        .load_bigrams(bigram_payload_from_text(text))
        .expect("valid");
    println!(
        "bigram build + load (20k pairs): {:.0} ms",
        t.elapsed().as_secs_f64() * 1e3
    );

    let mut prefixes = Vec::new();
    let mut typos = Vec::new();
    let mut unknown = Vec::new();
    for _ in 0..4_000 {
        let w: Vec<char> = entries[r.below(WORDS as u64) as usize]
            .word
            .chars()
            .collect();
        let n = 1 + r.below(w.len() as u64) as usize;
        prefixes.push(w[..n].iter().collect::<String>());
        if w.len() >= 3 {
            let mut t = w.clone();
            let i = r.below(w.len() as u64 - 1) as usize;
            t.swap(i, i + 1);
            typos.push(t.iter().collect::<String>());
        }
        let mut u = w.clone();
        u.extend(['q', 'x', 'z']);
        unknown.push(u.iter().collect::<String>());
    }
    let prev = Some(entries[0].word.clone());
    let mut worst: f64 = 0.0;
    worst = worst.max(measure("rank, prefixes", &prefixes, |q| {
        std::hint::black_box(ranker.rank(q.into(), prev.clone(), 5));
    }));
    worst = worst.max(measure("rank, one-swap typos", &typos, |q| {
        std::hint::black_box(ranker.rank(q.into(), prev.clone(), 5));
    }));
    worst = worst.max(measure("rank, unknown (worst case)", &unknown, |q| {
        std::hint::black_box(ranker.rank(q.into(), prev.clone(), 5));
    }));
    worst = worst.max(measure("autocorrect, typos", &typos, |q| {
        std::hint::black_box(ranker.autocorrect(q.into(), prev.clone(), false));
    }));
    let nexts: Vec<String> = entries.iter().take(2_000).map(|e| e.word.clone()).collect();
    worst = worst.max(measure("next_words", &nexts, |q| {
        std::hint::black_box(ranker.next_words(q.into(), 5));
    }));
    println!(
        "worst p95 = {worst:.4} ms; budget (ranker share of the 30 ms strip) = {BUDGET_P95_MS} ms"
    );
    if worst > BUDGET_P95_MS {
        eprintln!("FAIL: ranker exceeds budget");
        std::process::exit(1);
    }
}
