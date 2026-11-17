//! Builds pack payloads from text sources with the core's own builders (plan step L1.3), so the
//! bytes are exactly what the core loads. Public API only. Output is deterministic.
//!
//! Usage:
//!   qal-artifact-build lexicon <wordlist.tsv> <out.qlx>      `word<TAB>frequency` lines -> "QLX1"
//!   qal-artifact-build bigrams <pairs.tsv> <out.qbg>         `prev<TAB>next<TAB>count`  -> "QBG1"
//!   qal-artifact-build langid <out.qlid> <tag>=<file>...     labelled word lists        -> "QLID" v1
use qal_keyboard_core::langid::{LabeledText, LangIdDetector, LangTag};
use qal_keyboard_core::lexicon::Lexicon;
use qal_keyboard_core::ranker::bigram_payload_from_text;

fn die(msg: &str) -> ! {
    eprintln!("error: {msg}");
    std::process::exit(2);
}

fn read(path: &str) -> String {
    std::fs::read_to_string(path).unwrap_or_else(|e| die(&format!("cannot read {path}: {e}")))
}

fn write(path: &str, bytes: &[u8]) {
    std::fs::write(path, bytes).unwrap_or_else(|e| die(&format!("cannot write {path}: {e}")));
}

fn tag(code: &str) -> LangTag {
    match code {
        "am" => LangTag::Am,
        "ti" => LangTag::Ti,
        "om" => LangTag::Om,
        "en" => LangTag::En,
        "am-latn" => LangTag::AmLatn,
        "ti-latn" => LangTag::TiLatn,
        _ => die(&format!("unknown language tag {code}")),
    }
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    match a.first().map(String::as_str) {
        Some("lexicon") if a.len() == 3 => {
            let lex = Lexicon::from_wordlist_text(read(&a[1]));
            write(&a[2], &lex.to_bytes());
            println!("lexicon {} words -> {}", lex.base_word_count(), a[2]);
        }
        Some("bigrams") if a.len() == 3 => {
            let bytes = bigram_payload_from_text(read(&a[1]));
            write(&a[2], &bytes);
            println!("bigrams {} bytes -> {}", bytes.len(), a[2]);
        }
        Some("langid") if a.len() >= 3 => {
            let texts: Vec<LabeledText> = a[2..]
                .iter()
                .map(|s| {
                    let (t, f) = s
                        .split_once('=')
                        .unwrap_or_else(|| die("expected tag=file"));
                    LabeledText {
                        lang: tag(t),
                        text: read(f),
                    }
                })
                .collect();
            let bytes = LangIdDetector::train(texts).to_bytes();
            write(&a[1], &bytes);
            println!("langid {} bytes -> {}", bytes.len(), a[1]);
        }
        _ => die("usage: qal-artifact-build lexicon|bigrams|langid ... (see source header)"),
    }
}
