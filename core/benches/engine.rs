//! Key-path latency of `KeyboardEngine::on_key` in phonetic mode with a dictionary loaded
//! (plan A1.3, SD 17.1). Run: `cargo bench --bench engine`.
//!
//! SD 17.1: key-down to composing update p95 <= 30 ms and candidate strip update p95 <= 30 ms
//! (mid-range device). One `on_key` now does the transliteration and the merged lexicon
//! candidates; like the ranker bench it gets 10 ms p95 on the build host as a proxy (A1.9
//! re-measures on devices), leaving the rest for UI and IPC. Exits non-zero when exceeded.
//! Not part of the C1.10 regression gate (`scripts/bench_gate.py`); the ranker bench there
//! already guards the dominant cost.
//!
//! Corpus: 200k synthetic words made by typing deterministic pseudo-random Latin syllables into
//! the DRAFT Amharic profile, so typed prefixes really reach dictionary words; bigrams over them.

use std::sync::Arc;
use std::time::Instant;

use qal_keyboard_core::lexicon::{Lexicon, WordEntry};
use qal_keyboard_core::pack::builder::{self, ManifestFields};
use qal_keyboard_core::ranker::bigram_payload_from_text;
use qal_keyboard_core::{
    EngineConfig, InputMode, KeyEvent, KeyStatus, KeyboardEngine, PackPlatform, ReleaseChannel,
    ResourceType, TrustedKey,
};

const BUDGET_P95_MS: f64 = 10.0;
const WORDS: usize = 200_000;
const PROFILE: &[u8] = include_bytes!("../../language-data/profiles/am-phonetic-draft.json");
const CONSONANTS: &[&str] = &[
    "b", "t", "ch", "n", "k", "w", "z", "y", "d", "j", "g", "f", "p", "s", "sh", "l", "m", "r",
    "h", "q",
];
const VOWELS: &[&str] = &["a", "e", "i", "o", "u", ""];

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

fn latin_word(r: &mut Rng) -> String {
    let mut w = String::new();
    for _ in 0..2 + r.below(4) {
        w.push_str(CONSONANTS[r.below(CONSONANTS.len())]);
        w.push_str(VOWELS[r.below(VOWELS.len())]);
    }
    w
}

fn engine() -> Arc<KeyboardEngine> {
    let sk = builder::signing_key_from_seed([7; 32]);
    let e = KeyboardEngine::new(EngineConfig {
        max_candidates: 8,
        trusted_keys: vec![TrustedKey {
            key_id: "bench-1".into(),
            public_key: sk.verifying_key().to_bytes().to_vec(),
            status: KeyStatus::Active,
        }],
        pack_limits: None,
    })
    .expect("engine");
    let e2 = Arc::clone(&e);
    let load = move |rt: ResourceType, artifact: &[u8]| {
        let f = ManifestFields {
            resource_id: "0f8fad5b-d9cb-469f-a165-70867728950e".into(),
            language_code: "am".into(),
            resource_type: rt,
            version: "1.0.0".into(),
            platform: PackPlatform::Shared,
            release_channel: ReleaseChannel::Stable,
            license_code: "LicenseRef-Qal-Test".into(),
            base_resource_id: None,
            min_app_version: None,
        };
        e2.load_pack(builder::build_pack(&sk, "bench-1", &f, artifact).expect("pack"))
            .expect("load");
    };
    load(ResourceType::TransliterationRules, PROFILE);
    e.set_input_mode("am".into(), InputMode::PhoneticLatin)
        .expect("phonetic");

    // Compose the corpus with the engine itself (no dictionary loaded yet).
    let mut r = Rng(0x9E37_79B9_7F4A_7C15);
    let mut entries = Vec::with_capacity(WORDS);
    for i in 0..WORDS {
        e.reset();
        let mut composed = String::new();
        for c in latin_word(&mut r).chars() {
            composed = e.on_key(KeyEvent::Char { text: c.into() }).composing;
        }
        entries.push(WordEntry {
            word: composed,
            frequency: (2_000_000 / (1 + (i as u64 * 7 % 50_000))) as u32 + 1,
        });
    }
    e.reset();
    let mut text = String::new();
    for _ in 0..20_000 {
        let a = &entries[r.below(WORDS)].word;
        let b = &entries[r.below(2_000)].word;
        text.push_str(&format!("{a}\t{b}\t{}\n", 1 + r.below(500)));
    }
    let t = Instant::now();
    load(
        ResourceType::Dictionary,
        &Lexicon::from_entries(entries).to_bytes(),
    );
    load(ResourceType::FrequencyList, &bigram_payload_from_text(text));
    println!(
        "dictionary + bigram pack load (200k words, 20k pairs): {:.0} ms",
        t.elapsed().as_secs_f64() * 1e3
    );
    e
}

fn pct(sorted: &[f64], p: f64) -> f64 {
    sorted[((sorted.len() - 1) as f64 * p) as usize]
}

fn main() {
    let e = engine();
    let mut r = Rng(42);
    // Warm up, then time every key of 3,000 words (letters, then Space to commit).
    let mut ms = Vec::new();
    let mut candidates = 0usize;
    for round in 0..3_200 {
        let word = latin_word(&mut r);
        for c in word.chars() {
            let t = Instant::now();
            let u = e.on_key(KeyEvent::Char { text: c.into() });
            let dt = t.elapsed().as_secs_f64() * 1e3;
            if round >= 200 {
                ms.push(dt);
                candidates += u.candidates.len();
            }
            std::hint::black_box(u);
        }
        std::hint::black_box(e.on_key(KeyEvent::Space));
    }
    ms.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let p95 = pct(&ms, 0.95);
    println!(
        "{:<34} n={:<6} p50={:.4} ms  p95={:.4} ms  p99={:.4} ms  max={:.4} ms",
        "on_key, phonetic + lexicon",
        ms.len(),
        pct(&ms, 0.5),
        p95,
        pct(&ms, 0.99),
        ms[ms.len() - 1]
    );
    println!(
        "mean candidates per key = {:.2}; budget (p95, host proxy) = {BUDGET_P95_MS} ms",
        candidates as f64 / ms.len() as f64
    );
    if p95 > BUDGET_P95_MS {
        eprintln!("FAIL: on_key exceeds budget");
        std::process::exit(1);
    }
}
