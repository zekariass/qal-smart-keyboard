import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from qal_eval import metrics as m  # noqa: E402


class TestMetrics(unittest.TestCase):
    def test_chrf_identical(self):
        self.assertEqual(m.chrf("ሰላም እንዴት ነህ", "ሰላም እንዴት ነህ"), 100.0)
        self.assertEqual(m.chrf("Hello world", "Hello world"), 100.0)

    def test_chrf_disjoint_and_empty(self):
        self.assertEqual(m.chrf("abc", "xyz"), 0.0)
        self.assertEqual(m.chrf("abc", ""), 0.0)

    def test_chrf_known_value(self):
        # ref "abcd", hyp "abc": whitespace-free; ngram orders 1..6.
        # n=1: match 3, P=3/3, R=3/4; n=2: 2, P=1, R=2/3; n=3: 1, P=1, R=1/2;
        # n=4: ref has 1, hyp 0 -> P=R=0; n=5,6: neither has n-grams -> skipped.
        # avgP = 3/4, avgR = (.75+.6667+.5+0)/4
        p = 3 / 4
        r = (0.75 + 2 / 3 + 0.5) / 4
        expected = 100 * 5 * p * r / (4 * p + r)
        self.assertAlmostEqual(m.chrf("abcd", "abc"), expected, places=6)

    def test_chrf_ignores_whitespace(self):
        # strings differ only in spaces: n-gram statistics identical -> 100
        self.assertAlmostEqual(m.chrf("ab cd ef", "abcdef"), 100.0, places=6)

    def test_chrf_geez(self):
        s = m.chrf("ሰላም እንዴት ነህ", "ሰላም እንዴት ነሽ")
        self.assertTrue(0 < s < 100)

    def test_chrf_plus_plus_identical_and_differs(self):
        self.assertEqual(m.chrf("a b", "a b", word_order=2), 100.0)
        self.assertNotEqual(m.chrf("the cat sat", "sat the cat"), m.chrf("the cat sat", "sat the cat", word_order=2))

    def test_wer_identical(self):
        self.assertEqual(m.wer("a b c", "a b c"), 0.0)

    def test_wer_hand_computed(self):
        # ref 4 words; hyp: one substitution + one deletion = 2 edits -> 0.5
        self.assertAlmostEqual(m.wer("the cat sat down", "the dog sat"), 0.5)
        # one insertion over 3 ref words
        self.assertAlmostEqual(m.wer("a b c", "a x b c"), 1 / 3)

    def test_wer_empty_ref(self):
        self.assertEqual(m.wer("", ""), 0.0)
        self.assertEqual(m.wer("", "x"), 1.0)

    def test_cer_geez_per_syllable(self):
        # ref ሰላምነህ = 5 syllables (whitespace ignored); ነህ -> ነሽ is one substitution: 1/5
        self.assertAlmostEqual(m.cer("ሰላም ነህ", "ሰላም ነሽ"), 1 / 5)
        self.assertEqual(m.cer("ሰላም", "ሰላም"), 0.0)

    def test_cer_nfc(self):
        self.assertEqual(m.cer("é", "é"), 0.0)

    def test_levenshtein(self):
        self.assertEqual(m.levenshtein("kitten", "sitting"), 3)

    def test_script_ok(self):
        self.assertEqual(m.script_ok_rate("ሰላም፣ እንዴት ነህ? 123", "am"), 1.0)
        self.assertEqual(m.script_ok_rate("ሰላም", "om"), 0.0)
        self.assertEqual(m.script_ok_rate("Akkam bulte", "om"), 1.0)
        self.assertAlmostEqual(m.script_ok_rate("ሰላም ab", "am"), 3 / 5)
        self.assertEqual(m.script_ok_rate("", "am"), 0.0)
        self.assertEqual(m.script_ok_rate("123 !!", "en"), 0.0)

    def test_percentile(self):
        self.assertEqual(m.percentile([10, 20, 30, 40], 50), 20)
        self.assertEqual(m.percentile([10, 20, 30, 40], 95), 40)
        self.assertEqual(m.percentile([5], 50), 5)


if __name__ == "__main__":
    unittest.main()
