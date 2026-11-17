"""Pure-Python metrics. All text is NFC-normalised and handled as Unicode code points.

Ge'ez note: Ethiopic syllables (fidel) are single code points (e.g. U+1230), so
character-level metrics (CER, chrF character n-grams) operate per syllable, not per
consonant/vowel. Scores are therefore not directly comparable with Latin-script CER.
"""
from __future__ import annotations

import math
import unicodedata
from collections import Counter

ETHIOPIC_RANGES = (
    (0x1200, 0x139F),  # Ethiopic + Ethiopic Supplement
    (0x2D80, 0x2DDF),  # Ethiopic Extended
    (0xAB00, 0xAB2F),  # Ethiopic Extended-A
)


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s or "")


def _ngrams(seq, n):
    return Counter(tuple(seq[i:i + n]) if not isinstance(seq, str) else seq[i:i + n]
                   for i in range(len(seq) - n + 1))


def chrf(reference: str, hypothesis: str, char_order: int = 6, word_order: int = 0,
         beta: float = 2.0) -> float:
    """chrF (word_order=0, chrF2 with beta=2) or chrF++ (word_order=2). Returns 0..100.

    Character n-grams 1..char_order over text with whitespace removed; precision and
    recall are averaged over n-gram orders, then combined as an F-beta score.
    """
    ref, hyp = nfc(reference), nfc(hypothesis)
    if ref.strip() == hyp.strip():
        return 100.0
    rc = "".join(ref.split())
    hc = "".join(hyp.split())
    precs, recs = [], []

    def add(rn, hn):
        match = sum((rn & hn).values())
        h, r = sum(hn.values()), sum(rn.values())
        if not h and not r:
            return  # order longer than both strings: skip (effective order), not a penalty
        precs.append(match / h if h else 0.0)
        recs.append(match / r if r else 0.0)

    for n in range(1, char_order + 1):
        add(_ngrams(rc, n), _ngrams(hc, n))
    rw, hw = ref.split(), hyp.split()
    for n in range(1, word_order + 1):
        add(_ngrams(rw, n), _ngrams(hw, n))
    p = sum(precs) / len(precs)
    r = sum(recs) / len(recs)
    if p + r == 0:
        return 0.0
    b2 = beta * beta
    return 100.0 * (1 + b2) * p * r / (b2 * p + r)


def levenshtein(a, b) -> int:
    """Edit distance between two sequences (strings or lists)."""
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(reference: str, hypothesis: str) -> float:
    """Word error rate (can exceed 1). Whitespace tokenisation after NFC."""
    r = nfc(reference).split()
    h = nfc(hypothesis).split()
    if not r:
        return 0.0 if not h else 1.0
    return levenshtein(r, h) / len(r)


def cer(reference: str, hypothesis: str) -> float:
    """Character error rate over code points, whitespace removed (per syllable for Ge'ez)."""
    r = "".join(nfc(reference).split())
    h = "".join(nfc(hypothesis).split())
    if not r:
        return 0.0 if not h else 1.0
    return levenshtein(r, h) / len(r)


def is_ethiopic(ch: str) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in ETHIOPIC_RANGES)


def script_ok_rate(text: str, lang: str) -> float:
    """Share (0..1) of letter characters in the expected script for `lang`.

    am/ti -> Ethiopic; om/en -> Latin. Only Unicode letters (category L*) count, so
    whitespace, digits and punctuation are ignored. No letters -> 0.0.
    """
    letters = [c for c in nfc(text) if unicodedata.category(c).startswith("L")]
    if not letters:
        return 0.0
    if lang in ("am", "ti"):
        ok = sum(1 for c in letters if is_ethiopic(c))
    elif lang in ("om", "en"):
        ok = sum(1 for c in letters if unicodedata.name(c, "").startswith("LATIN"))
    else:
        raise ValueError(f"no script rule for language {lang!r}")
    return ok / len(letters)


def percentile(values, pct: float) -> float:
    """Nearest-rank percentile; empty -> nan."""
    if not values:
        return float("nan")
    s = sorted(values)
    k = max(1, math.ceil(pct / 100.0 * len(s)))
    return s[min(k, len(s)) - 1]
