import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench_gate as g  # noqa: E402

SAMPLE = """\
bigram build + load (20k pairs): 12 ms
rank, prefixes                     n=4000   p50=0.0300 ms  p95=0.2000 ms  p99=0.3 ms  max=1.0 ms
worst p95 = 0.2 ms
"""


class Gate(unittest.TestCase):
    def test_parse(self):
        self.assertEqual(g.parse_output("ranker", SAMPLE), {"ranker/rank, prefixes": 0.03})

    def test_within_tolerance_passes(self):
        _, f = g.compare({"a": 1.09}, {"a": 1.0}, 0.10, 0.0)
        self.assertEqual(f, [])

    def test_regression_fails(self):
        _, f = g.compare({"a": 1.2}, {"a": 1.0}, 0.10, 0.0)
        self.assertEqual(len(f), 1)

    def test_noise_floor_suppresses_tiny_absolute_change(self):
        _, f = g.compare({"a": 0.0030}, {"a": 0.0020}, 0.10, 0.010)
        self.assertEqual(f, [])

    def test_missing_and_new_labels_fail(self):
        _, f = g.compare({"b": 1.0}, {"a": 1.0}, 0.10, 0.0)
        self.assertEqual(len(f), 2)

    def test_improvement_passes(self):
        _, f = g.compare({"a": 0.5}, {"a": 1.0}, 0.10, 0.0)
        self.assertEqual(f, [])


if __name__ == "__main__":
    unittest.main()
