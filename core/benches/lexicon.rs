//! Lexicon latency benchmark (plan C1.6, SD 17.1). Run: `cargo bench --bench lexicon`.
//!
//! SD 17.1 gives the whole candidate-strip update p95 <= 30 ms (mid-range device). This bench
//! gives the lexicon prefix lookup 5 ms p95 of that (the rest is the ranker, UI and IPC) and
//! exits non-zero if the measured p95 on the build host exceeds it. Host numbers are a
//! proxy; A1.9 re-measures on the reference devices.
//!
//! Corpus: deterministic synthetic wordlist, 200k words, 60% Ge'ez script (U+1200 block
//! syllables, 2-8 syllables) and 40% Latin, Zipf-like frequencies.

use std::time::Instant;

use qal_keyboard_core::lexicon::{Lexicon, WordEntry};

const BUDGET_P95_MS: f64 = 5.0;
const WORDS: usize = 200_000;
const LIMIT: u32 = 10;

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
            // Skewed syllable choice so prefixes share structure like a real language.
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

fn measure(label: &str, lex: &Lexicon, prefixes: &[String]) -> f64 {
    for p in prefixes.iter().take(500) {
        std::hint::black_box(lex.suggest(p.clone(), LIMIT));
    }
    let mut ms = Vec::with_capacity(prefixes.len());
    for p in prefixes {
        let t = Instant::now();
        std::hint::black_box(lex.suggest(p.clone(), LIMIT));
        ms.push(t.elapsed().as_secs_f64() * 1e3);
    }
    ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let p95 = pct(&ms, 0.95);
    println!(
        "{label:<36} n={:<6} p50={:.4} ms  p95={:.4} ms  p99={:.4} ms  max={:.4} ms",
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
    let t = Instant::now();
    let lex = Lexicon::from_entries(entries.clone());
    println!(
        "build {} entries -> {} words in {:.0} ms",
        WORDS,
        lex.base_word_count(),
        t.elapsed().as_secs_f64() * 1e3
    );
    let bytes = lex.to_bytes();
    println!(
        "serialised size: {:.2} MiB ({:.1} bytes/word)",
        bytes.len() as f64 / 1_048_576.0,
        bytes.len() as f64 / f64::from(lex.base_word_count())
    );
    let t = Instant::now();
    let lex = Lexicon::from_bytes(bytes).expect("valid");
    println!("load + validate: {:.1} ms", t.elapsed().as_secs_f64() * 1e3);

    // Query prefixes are taken from corpus words, 1..=6 chars.
    let mut r = Rng(42);
    let mut by_len: Vec<Vec<String>> = vec![Vec::new(); 7];
    for _ in 0..20_000 {
        let w = &entries[r.below(WORDS as u64) as usize].word;
        let chars: Vec<char> = w.chars().collect();
        for (len, bucket) in by_len.iter_mut().enumerate().skip(1) {
            if chars.len() >= len {
                bucket.push(chars[..len].iter().collect());
            }
        }
    }
    let mut worst: f64 = 0.0;
    for len in [1usize, 2, 3, 6] {
        let label = format!("base only, {len}-char prefix");
        worst = worst.max(measure(&label, &lex, &by_len[len]));
    }
    let mixed: Vec<String> = by_len[1..].iter().flatten().cloned().collect();
    worst = worst.max(measure("base only, mixed 1-6 chars", &lex, &mixed));

    for i in 0..5_000 {
        lex.learn_word(entries[i * 37 % WORDS].word.clone());
        lex.add_user_word(format!("user{i}"));
    }
    worst = worst.max(measure("10k-word user overlay, mixed", &lex, &mixed));

    println!(
        "worst p95 = {worst:.4} ms; budget (lexicon share of SD 17.1 30 ms strip) = {BUDGET_P95_MS} ms"
    );
    if worst > BUDGET_P95_MS {
        eprintln!("FAIL: lexicon lookup exceeds budget");
        std::process::exit(1);
    }
    println!("PASS");
}
