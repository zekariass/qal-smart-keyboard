//! Language-ID accuracy eval (plan step C1.8).
//!
//! The eval set (`language-data/eval/langid/eval.txt`) is SYNTHETIC: a few hundred hand-written
//! tokens, not native-speaker reviewed, disjoint from the seed training words. The numbers it
//! gives are a regression tripwire for the DRAFT model, NOT a measure of real-world accuracy;
//! that needs the native-speaker corpus of human gate G0.
//!
//! Regenerate the checked-in results with
//! `QAL_LANGID_WRITE_RESULTS=1 cargo test --test langid_eval`.

use std::collections::{BTreeMap, HashSet};
use std::path::PathBuf;
use std::time::Instant;

use qal_keyboard_core::langid::{LangIdDetector, LangTag};

const TAGS: [LangTag; 6] = [
    LangTag::Am,
    LangTag::Ti,
    LangTag::Om,
    LangTag::En,
    LangTag::AmLatn,
    LangTag::TiLatn,
];

/// Recorded floors (percent, integer). The test fails if a measured figure drops below them.
/// Set a few points under the first measured values (noted per line).
const FLOOR_OVERALL_TOP1: f64 = 75.0; // measured 80.1
const FLOOR_PER_LANG_TOP1: f64 = 60.0; // lowest measured 66.0 (en)
const FLOOR_OVERALL_IN_CANDIDATES: f64 = 90.0; // measured 93.4

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../language-data/eval/langid")
}

fn tag_of(code: &str) -> LangTag {
    *TAGS
        .iter()
        .find(|t| t.code() == code)
        .unwrap_or_else(|| panic!("unknown tag in eval set: {code}"))
}

/// (tag, word, context) per token.
fn load_eval() -> Vec<(LangTag, String, Vec<String>)> {
    let text = std::fs::read_to_string(dir().join("eval.txt")).expect("eval.txt");
    let mut items = Vec::new();
    for line in text
        .lines()
        .filter(|l| !l.starts_with('#') && !l.trim().is_empty())
    {
        let mut ctx: Vec<String> = Vec::new();
        for tok in line.split_whitespace() {
            let (w, t) = tok.rsplit_once('|').expect("word|tag");
            items.push((tag_of(t), w.to_string(), ctx.clone()));
            ctx.push(w.to_string());
        }
    }
    items
}

#[derive(Default)]
struct Tally {
    n: u32,
    top1: u32,
    in_cands: u32,
    cand_count: u32,
}

fn measure() -> (BTreeMap<&'static str, Tally>, Tally) {
    let det = LangIdDetector::draft_default();
    let mut per: BTreeMap<&'static str, Tally> = BTreeMap::new();
    let mut all = Tally::default();
    for (gold, word, ctx) in load_eval() {
        let r = det.detect_word(word, ctx, vec![]);
        let top1 = r.candidates.first().map(|c| c.lang) == Some(gold);
        let inc = r.candidates.iter().any(|c| c.lang == gold);
        let n = r.candidates.len() as u32;
        for t in [per.entry(gold.code()).or_default(), &mut all] {
            t.n += 1;
            t.top1 += u32::from(top1);
            t.in_cands += u32::from(inc);
            t.cand_count += n;
        }
    }
    (per, all)
}

fn pct(a: u32, b: u32) -> f64 {
    if b == 0 {
        0.0
    } else {
        100.0 * f64::from(a) / f64::from(b)
    }
}

fn render(per: &BTreeMap<&'static str, Tally>, all: &Tally) -> String {
    let mut s = String::new();
    s.push_str("# C1.8 language-ID eval results (DRAFT seed model, SYNTHETIC eval set)\n");
    s.push_str("# Regenerate: QAL_LANGID_WRITE_RESULTS=1 cargo test --test langid_eval\n");
    s.push_str(
        "# Caveat: tiny, hand-written, not native-speaker reviewed. Regression tripwire only;\n",
    );
    s.push_str("# real accuracy needs the native-speaker corpus (human gate G0).\n");
    s.push_str(
        "# top1 = highest-ranked candidate is the gold label; in_cands = gold is among the\n",
    );
    s.push_str(
        "# returned candidates (the product contract: ambiguity gives several candidates).\n",
    );
    s.push_str("lang\ttokens\ttop1\ttop1_pct\tin_cands\tin_cands_pct\tavg_cands\n");
    let line = |name: &str, t: &Tally| {
        format!(
            "{}\t{}\t{}\t{:.1}\t{}\t{:.1}\t{:.2}\n",
            name,
            t.n,
            t.top1,
            pct(t.top1, t.n),
            t.in_cands,
            pct(t.in_cands, t.n),
            f64::from(t.cand_count) / f64::from(t.n.max(1))
        )
    };
    for tag in TAGS {
        if let Some(t) = per.get(tag.code()) {
            s.push_str(&line(tag.code(), t));
        }
    }
    s.push_str(&line("overall", all));
    s
}

#[test]
fn eval_words_are_disjoint_from_training_words() {
    let mut overlaps: Vec<String> = Vec::new();
    for tag in TAGS {
        let train = std::fs::read_to_string(dir().join(format!("train_{}.txt", tag.code())))
            .expect("train file");
        let words: HashSet<String> = train
            .lines()
            .filter(|l| !l.starts_with('#'))
            .flat_map(str::split_whitespace)
            .map(str::to_lowercase)
            .collect();
        for (gold, w, _) in load_eval() {
            if gold == tag && words.contains(&w.to_lowercase()) {
                overlaps.push(format!("{w} ({})", tag.code()));
            }
        }
    }
    assert!(
        overlaps.is_empty(),
        "eval tokens also in training: {overlaps:?}"
    );
}

#[test]
fn accuracy_is_recorded_and_above_floor() {
    let (per, all) = measure();
    let rendered = render(&per, &all);
    println!("{rendered}");
    let path = dir().join("results.tsv");
    if std::env::var_os("QAL_LANGID_WRITE_RESULTS").is_some() {
        std::fs::write(&path, &rendered).expect("write results");
    } else {
        let recorded = std::fs::read_to_string(&path).expect("results.tsv (generate it)");
        assert_eq!(
            recorded.replace("\r\n", "\n"),
            rendered,
            "results.tsv is stale: rerun with QAL_LANGID_WRITE_RESULTS=1 and commit it"
        );
    }
    assert!(all.n >= 100, "eval set too small");
    assert!(
        pct(all.top1, all.n) >= FLOOR_OVERALL_TOP1,
        "overall top-1 regressed"
    );
    assert!(
        pct(all.in_cands, all.n) >= FLOOR_OVERALL_IN_CANDIDATES,
        "overall in-candidates regressed"
    );
    for (name, t) in &per {
        assert!(
            pct(t.top1, t.n) >= FLOOR_PER_LANG_TOP1,
            "top-1 regressed for {name}"
        );
    }
}

#[test]
fn per_word_latency_is_tiny() {
    let det = LangIdDetector::draft_default();
    let items = load_eval();
    let start = Instant::now();
    let mut count = 0u32;
    for _ in 0..20 {
        for (_, w, ctx) in &items {
            let r = det.detect_word(w.clone(), ctx.clone(), vec![]);
            count += r.candidates.len() as u32;
        }
    }
    let per_word = start.elapsed().as_secs_f64() * 1e6 / (20.0 * items.len() as f64);
    println!("langid: {per_word:.1} us per word (incl. up to 3 context words), checksum {count}");
    // Generous bound so debug builds on slow CI pass; the real figure is printed above.
    assert!(per_word < 1000.0, "langid too slow: {per_word} us/word");
}
