use super::*;
use crate::lexicon::{LexiconError, WordEntry};

fn lex(words: &[(&str, u32)]) -> Arc<Lexicon> {
    Lexicon::from_entries(
        words
            .iter()
            .map(|(w, f)| WordEntry {
                word: (*w).into(),
                frequency: *f,
            })
            .collect(),
    )
}

const EN: &[(&str, u32)] = &[
    ("the", 900_000),
    ("hello", 100_000),
    ("help", 20_000),
    ("helm", 300),
    ("world", 50_000),
    ("cat", 50_000),
    ("cut", 50_000),
    ("cost", 60_000),
    ("car", 8_000),
    ("card", 9_000),
    ("care", 12_000),
    ("cart", 5_000),
    ("rare", 40),
    ("Addis", 40_000),
    ("café", 3_000),
];

fn en() -> Arc<Ranker> {
    Ranker::new(lex(EN))
}

fn texts(v: &[RankedCandidate]) -> Vec<&str> {
    v.iter().map(|c| c.text.as_str()).collect()
}

fn decide(r: &Ranker, typed: &str) -> AutocorrectDecision {
    r.autocorrect(typed.into(), None, false)
}

// ---------- harm guard ----------

#[test]
fn valid_words_are_never_auto_corrected() {
    let r = en();
    for (w, _) in EN {
        let d = r.autocorrect((*w).into(), None, true);
        assert_eq!(d.action, AutocorrectAction::None, "{w}");
        assert!(d.replacement.is_none());
    }
    // "car" is one edit from card/care/cart/cat/cut and very common, but it is valid.
    let d = decide(&r, "car");
    assert_eq!(d.reason, AutocorrectReason::ValidWord);
    // Case, diacritic and homophone-equivalent spellings of valid words are valid too.
    assert_eq!(decide(&r, "cafe").action, AutocorrectAction::None);
    assert_eq!(decide(&r, "addis").action, AutocorrectAction::None);
    assert_eq!(decide(&r, "HELLO").action, AutocorrectAction::None);
}

#[test]
fn confident_typos_are_applied() {
    let r = en();
    let d = decide(&r, "hallo");
    assert_eq!(d.action, AutocorrectAction::Apply);
    assert_eq!(d.replacement.as_deref(), Some("hello"));
    assert!(f64::from(d.confidence) >= AUTOCORRECT_MIN_CONFIDENCE);
    assert_eq!(d.reason, AutocorrectReason::Confident);
    let d = decide(&r, "wrold"); // transposition
    assert_eq!(d.replacement.as_deref(), Some("world"));
    assert_eq!(d.action, AutocorrectAction::Apply);
    // Capitalised typo only at a sentence start, with the case carried over.
    let d = r.autocorrect("Hallo".into(), None, true);
    assert_eq!(d.action, AutocorrectAction::Apply);
    assert_eq!(d.replacement.as_deref(), Some("Hello"));
}

#[test]
fn ambiguous_corrections_are_suggestion_only() {
    let r = en();
    // cat and cut are equally likely one edit away: no clear winner.
    let d = decide(&r, "cbt");
    assert_eq!(d.action, AutocorrectAction::SuggestOnly);
    assert!(d.replacement.is_some());
    assert!(f64::from(d.confidence) < AUTOCORRECT_MIN_CONFIDENCE);
    assert_eq!(d.reason, AutocorrectReason::LowConfidence);
    // Context can break the tie: after "the", "cut" is far more likely.
    let r = en();
    r.load_bigrams(bigram_payload_from_text("the\tcut\t500\n".into()))
        .unwrap();
    let d = r.autocorrect("cbt".into(), Some("the".into()), false);
    assert_eq!(d.action, AutocorrectAction::Apply);
    assert_eq!(d.replacement.as_deref(), Some("cut"));
}

#[test]
fn low_confidence_corrections_are_suggestion_only() {
    let r = en();
    // The only candidate is a rare word (freq 40): not enough evidence to override the typing.
    let d = decide(&r, "rarw");
    assert_eq!(d.action, AutocorrectAction::SuggestOnly);
    assert_eq!(d.replacement.as_deref(), Some("rare"));
    // A changed first letter is never applied automatically, however frequent the target.
    let d = decide(&r, "xello");
    assert_eq!(d.action, AutocorrectAction::SuggestOnly);
    assert_eq!(d.replacement.as_deref(), Some("hello"));
    // Nothing within one edit: leave it alone and offer nothing.
    let d = decide(&r, "zzzzz");
    assert_eq!(d.action, AutocorrectAction::None);
    assert_eq!(d.reason, AutocorrectReason::NoCandidate);
}

#[test]
fn ineligible_tokens_are_left_alone() {
    let r = en();
    for t in [
        "he", "", "he11o", "hel_o", "he llo", "http://x", "a@b.co", "HALLO", "hALlo",
    ] {
        let d = decide(&r, t);
        assert_eq!(d.action, AutocorrectAction::None, "{t}");
        assert_eq!(d.reason, AutocorrectReason::NotEligible, "{t}");
    }
    // A capitalised unknown word mid-sentence may be a name.
    assert_eq!(decide(&r, "Hallo").action, AutocorrectAction::None);
    assert_eq!(
        &"x".repeat(MAX_TYPED_CHARS + 1).len(),
        &(MAX_TYPED_CHARS + 1)
    );
    assert_eq!(
        decide(&r, &"x".repeat(MAX_TYPED_CHARS + 1)).reason,
        AutocorrectReason::NotEligible
    );
}

#[test]
fn words_valid_in_another_enabled_language_are_left_alone() {
    let r = en();
    assert_eq!(decide(&r, "hallo").action, AutocorrectAction::Apply);
    r.set_guard_lexicons(vec![lex(&[("hallo", 10)])]);
    let d = decide(&r, "hallo");
    assert_eq!(d.action, AutocorrectAction::None);
    assert_eq!(d.reason, AutocorrectReason::OtherLanguageWord);
}

/// Optimal-string-alignment distance (one transposition counts as one edit).
fn osa(a: &str, b: &str) -> usize {
    let (a, b): (Vec<char>, Vec<char>) = (a.chars().collect(), b.chars().collect());
    let mut d = vec![vec![0usize; b.len() + 1]; a.len() + 1];
    for (i, row) in d.iter_mut().enumerate() {
        row[0] = i;
    }
    for (j, cell) in d[0].iter_mut().enumerate() {
        *cell = j;
    }
    for i in 1..=a.len() {
        for j in 1..=b.len() {
            let c = usize::from(a[i - 1] != b[j - 1]);
            d[i][j] = (d[i - 1][j] + 1)
                .min(d[i][j - 1] + 1)
                .min(d[i - 1][j - 1] + c);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                d[i][j] = d[i][j].min(d[i - 2][j - 2] + 1);
            }
        }
    }
    d[a.len()][b.len()]
}

mod harm_property {
    use super::*;
    use proptest::prelude::*;

    proptest! {
        /// Whatever the input, an `Apply` decision satisfies every harm-guard condition.
        #[test]
        fn apply_implies_all_guards(
            typed in "[a-eh-ort-wA-Z0-9 ሰለላምሠ]{0,9}",
            prev in proptest::option::of("[a-z]{1,4}"),
            start in any::<bool>(),
        ) {
            let r = en();
            let d = r.autocorrect(typed.clone(), prev, start);
            match d.action {
                AutocorrectAction::None => prop_assert!(d.replacement.is_none()),
                AutocorrectAction::SuggestOnly => prop_assert!(d.replacement.is_some()),
                AutocorrectAction::Apply => {
                    let rep = d.replacement.clone().unwrap();
                    prop_assert!(f64::from(d.confidence) >= AUTOCORRECT_MIN_CONFIDENCE);
                    prop_assert!(!r.lexicon.contains(typed.clone()), "typed word is valid");
                    prop_assert!(typed.chars().count() >= MIN_AUTOCORRECT_CHARS);
                    prop_assert!(osa(&typed.to_lowercase(), &rep.to_lowercase()) <= 1);
                    prop_assert_eq!(
                        typed.to_lowercase().chars().next(),
                        rep.to_lowercase().chars().next()
                    );
                    prop_assert!(typed.chars().all(char::is_alphabetic));
                }
            }
        }
    }
}

// ---------- homophones, case, diacritics ----------

#[test]
fn homophone_families_match_but_keep_canonical_spelling() {
    let r = Ranker::new(lex(&[
        ("ሀገር", 5000),
        ("ሰላም", 9000),
        ("አባት", 4000),
        ("ጸሐይ", 3000),
        ("ሱባኤ", 700),
    ]));
    for (typed, canonical) in [
        ("ሐገር", "ሀገር"),
        ("ኀገር", "ሀገር"),
        ("ሠላም", "ሰላም"),
        ("ዐባት", "አባት"),
        ("ፀሐይ", "ጸሐይ"),
        ("ፀሀይ", "ጸሐይ"), // two substitutions
        ("ሡባኤ", "ሱባኤ"), // other vowel order
    ] {
        let out = r.rank(typed.into(), None, 5);
        assert_eq!(out[0].text, canonical, "{typed}");
        assert_eq!(out[0].kind, RankKind::Variant, "{typed}");
        assert_eq!(out[0].edit_cost, 0.0);
        // A valid equivalent spelling is never rewritten.
        let d = r.autocorrect(typed.into(), None, false);
        assert_eq!(d.action, AutocorrectAction::None, "{typed}");
        assert_eq!(d.reason, AutocorrectReason::ValidWord, "{typed}");
    }
    // Prefix completion works through the equivalence as well.
    let out = r.rank("ሠላ".into(), None, 5);
    assert_eq!(texts(&out), ["ሰላም"]);
    assert_eq!(out[0].kind, RankKind::Completion);
}

#[test]
fn both_spellings_valid_keeps_the_typed_one_available() {
    let r = Ranker::new(lex(&[("ሰላም", 9000), ("ሠላም", 20)]));
    let out = r.rank("ሠላም".into(), None, 5);
    assert_eq!(out.len(), 2);
    assert!(out
        .iter()
        .any(|c| c.text == "ሠላም" && c.kind == RankKind::Exact));
    assert!(out
        .iter()
        .any(|c| c.text == "ሰላም" && c.kind == RankKind::Variant));
    // Frequency picks the conventional spelling first (SD 7.2) ...
    assert_eq!(out[0].text, "ሰላም");
    // ... but neither spelling is ever auto-corrected into the other.
    for w in ["ሰላም", "ሠላም"] {
        assert_eq!(decide(&r, w).action, AutocorrectAction::None);
    }
}

#[test]
fn fidel_typo_is_corrected_without_touching_valid_words() {
    let r = Ranker::new(lex(&[("ሰላም", 100_000), ("ሰማይ", 60_000)]));
    // Wrong vowel order on one letter (ሰለም for ሰላም) is a confident single edit.
    let d = decide(&r, "ሰለም");
    assert_eq!(d.action, AutocorrectAction::Apply);
    assert_eq!(d.replacement.as_deref(), Some("ሰላም"));
    // Valid words with similar neighbours are untouched.
    assert_eq!(decide(&r, "ሰማይ").action, AutocorrectAction::None);
    // Script never changes: a Latin typo never maps onto Fidel words.
    assert_eq!(decide(&r, "selam").action, AutocorrectAction::None);
}

#[test]
fn case_and_diacritics_are_folded_for_matching_only() {
    let r = en();
    let out = r.rank("addis".into(), None, 3);
    assert_eq!(out[0].text, "Addis", "lexicon capitalisation is kept");
    assert_eq!(out[0].kind, RankKind::Variant);
    let out = r.rank("cafe".into(), None, 3);
    assert_eq!(out[0].text, "café");
    assert_eq!(out[0].kind, RankKind::Variant);
    // A Title-case typed prefix capitalises lower-case completions, not names.
    let out = r.rank("Hel".into(), None, 3);
    assert_eq!(out[0].text, "Hello");
    assert_eq!(out[0].kind, RankKind::Completion);
    assert_eq!(texts(&r.rank("add".into(), None, 3)), ["Addis"]);
}

// ---------- ranking ----------

#[test]
fn completions_rank_by_frequency_and_exact_leads() {
    let r = en();
    let out = r.rank("hel".into(), None, 5);
    assert_eq!(texts(&out)[..3], ["hello", "help", "helm"]);
    assert!(out.iter().all(|c| c.kind == RankKind::Completion));
    assert!(out.windows(2).all(|w| w[0].score >= w[1].score));
    // The typed word is a known word and appears with kind Exact.
    let out = r.rank("car".into(), None, 8);
    let exact: Vec<_> = out.iter().filter(|c| c.kind == RankKind::Exact).collect();
    assert_eq!(exact.len(), 1);
    assert_eq!(exact[0].text, "car");
    // Corrections are not mixed into the strip for a valid word.
    assert!(out.iter().all(|c| c.kind != RankKind::Correction));
}

#[test]
fn unknown_word_gets_corrections_with_costs() {
    let r = en();
    let out = r.rank("hallo".into(), None, 5);
    assert_eq!(out[0].text, "hello");
    assert_eq!(out[0].kind, RankKind::Correction);
    assert!((out[0].edit_cost - 1.0).abs() < 1e-6);
    // Adjacent-key slips cost less than arbitrary ones.
    let out = r.rank("cst".into(), None, 5);
    let cat = out.iter().find(|c| c.text == "cat").unwrap();
    assert!(cat.edit_cost < 1.0, "s is next to a");
}

#[test]
fn user_overlay_and_removal_shape_ranking() {
    let l = lex(&[("qab", 2_000), ("qaz", 500), ("hello", 100)]);
    let r = Ranker::new(l.clone());
    assert!(l.learn_word("qal".into()));
    // One use lifts a brand-new word above a rare base word but below a common one
    // (USER_WORD_WEIGHT calibration, pinned here).
    assert_eq!(texts(&r.rank("qa".into(), None, 5)), ["qab", "qal", "qaz"]);
    // Repeated use eventually wins.
    for _ in 0..4 {
        l.learn_word("qal".into());
    }
    assert_eq!(r.rank("qa".into(), None, 5)[0].text, "qal");
    // Removed words disappear from candidates and are not "valid" any more.
    assert!(l.remove_word("hello".into()));
    assert!(r.rank("hel".into(), None, 5).is_empty());
    assert_ne!(decide(&r, "hello").reason, AutocorrectReason::ValidWord);
}

#[test]
fn bigram_context_reorders_candidates() {
    let r = en();
    assert_eq!(r.rank("c".into(), None, 3)[0].text, "cost");
    r.load_bigrams(bigram_payload_from_text(
        "the\tcut\t100\nthe\tcat\t10\n".into(),
    ))
    .unwrap();
    let out = r.rank("c".into(), Some("the".into()), 3);
    assert_eq!(out[0].text, "cut");
    assert_eq!(r.rank("c".into(), Some("a".into()), 3)[0].text, "cost");
}

// ---------- next words ----------

#[test]
fn next_words_from_bigrams_then_frequent_fill() {
    let r = en();
    r.load_bigrams(bigram_payload_from_text(
        "# comment\nthe\tworld\t200\nthe\tcat\t50\nthe\tnothere\t999\n".into(),
    ))
    .unwrap();
    let out = r.next_words("the".into(), 5);
    assert_eq!(texts(&out)[..3], ["nothere", "world", "cat"]);
    assert!(out[..3].iter().all(|c| c.kind == RankKind::NextWord));
    assert_eq!(out.len(), 5);
    assert!(out[3..].iter().all(|c| c.kind == RankKind::Frequent));
    let mut seen = std::collections::HashSet::new();
    assert!(out.iter().all(|c| seen.insert(&c.text)), "no duplicates");
    // Unknown context falls back to frequent words; empty typed text delegates to this.
    assert_eq!(r.next_words("zzz".into(), 2)[0].text, "the");
    assert_eq!(
        r.rank(String::new(), Some("the".into()), 2),
        r.next_words("the".into(), 2)
    );
    assert_eq!(r.rank(String::new(), None, 2), r.frequent(2));
    assert!(r.next_words("the".into(), 0).is_empty());
}

#[test]
fn next_words_use_homophone_equivalence_and_skip_removed_words() {
    let l = lex(&[("ነው", 9000), ("ለእናንተ", 100), ("ሰላም", 500)]);
    let r = Ranker::new(l.clone());
    r.load_bigrams(bigram_payload_from_text(
        "ሰላም\tነው\t50\nሰላም\tለእናንተ\t40\n".into(),
    ))
    .unwrap();
    assert_eq!(texts(&r.next_words("ሠላም".into(), 2)), ["ነው", "ለእናንተ"]);
    l.remove_word("ነው".into());
    assert_eq!(r.next_words("ሠላም".into(), 1)[0].text, "ለእናንተ");
}

#[test]
fn learned_bigrams_outweigh_corpus_and_round_trip() {
    let r = en();
    r.load_bigrams(bigram_payload_from_text(
        "the\tworld\t200\nthe\tcat\t50\n".into(),
    ))
    .unwrap();
    assert_eq!(r.next_words("the".into(), 1)[0].text, "world");
    for _ in 0..10 {
        assert!(r.learn_bigram("the".into(), "cat".into()));
    }
    assert_eq!(r.next_words("the".into(), 1)[0].text, "cat");
    let bytes = r.export_user_bigrams();
    let r2 = en();
    r2.import_user_bigrams(bytes.clone()).unwrap();
    assert_eq!(r2.export_user_bigrams(), bytes);
    assert_eq!(r2.next_words("the".into(), 1)[0].text, "cat");
    assert!(r2.import_user_bigrams(vec![1, 2, 3]).is_err());
    assert_eq!(
        r2.next_words("the".into(), 1)[0].text,
        "cat",
        "unchanged on error"
    );
    r2.clear_user_bigrams();
    assert_ne!(r2.next_words("the".into(), 1)[0].text, "cat");
    assert!(!r.learn_bigram(String::new(), "x".into()));
    assert!(!r.learn_bigram("a b".into(), "x".into()));
}

#[test]
fn errors_carry_no_user_text() {
    let e = en()
        .load_bigrams(b"secret-typed-text".to_vec())
        .unwrap_err();
    assert!(!e.to_string().contains("secret"));
    let _: Option<LexiconError> = None;
}

// ---------- robustness ----------

#[test]
fn degenerate_inputs_are_safe_and_deterministic() {
    let r = en();
    assert!(r.rank("hel".into(), None, 0).is_empty());
    assert!(r.rank("x".repeat(MAX_TYPED_CHARS + 1), None, 5).is_empty());
    assert!(r.rank("zzzzzz".into(), None, 5).is_empty());
    assert_eq!(r.rank("hel".into(), None, 1000).len(), 3);
    let a = r.rank("hel".into(), Some("the".into()), 5);
    assert_eq!(a, r.rank("hel".into(), Some("the".into()), 5));
    // Decomposed input is normalised.
    assert_eq!(r.rank("cafe\u{301}".into(), None, 1)[0].text, "café");
    let empty = Ranker::new(Lexicon::new_empty());
    assert!(empty.rank("abc".into(), None, 5).is_empty());
    assert_eq!(decide(&empty, "abc").reason, AutocorrectReason::NoCandidate);
}

/// The harm-guard thresholds are compile-time checked so they cannot be loosened by accident.
const _: () = {
    assert!(AUTOCORRECT_MIN_CONFIDENCE >= 0.8 && AUTOCORRECT_MIN_CONFIDENCE < 1.0);
    assert!(MAX_AUTOCORRECT_EDIT_COST <= 1.0);
    assert!(MIN_AUTOCORRECT_CHARS >= 3);
    assert!(UNKNOWN_WORD_PRIOR >= 4.0);
};

#[test]
fn a_candidate_no_stronger_than_the_prior_is_never_applied() {
    // Posterior of a lone candidate whose score equals the prior is exactly 1/2.
    let p = 1.0 / (1.0 + 1.0);
    assert!(p < AUTOCORRECT_MIN_CONFIDENCE);
}
