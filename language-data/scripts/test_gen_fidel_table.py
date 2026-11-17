"""Staleness and sanity tests for the generated Fidel table and the draft layouts.

Run: python -m unittest discover -s language-data/scripts -p "test_*.py"
"""
import json
import subprocess
import sys
import unicodedata
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import gen_fidel_table as gen  # noqa: E402

LAYOUTS = gen.ROOT / "language-data" / "layouts"


@unittest.skipIf(unicodedata.unidata_version < "14.0.0", "needs Unicode 14 (Ethiopic Extended-B)")
class GeneratedTable(unittest.TestCase):
    def test_committed_files_are_not_stale(self):
        for path, text in gen.outputs().items():
            self.assertTrue(path.exists(), "%s missing; run gen_fidel_table.py" % path)
            self.assertEqual(
                path.read_bytes(), text.encode("utf-8"), "%s is stale; run gen_fidel_table.py" % path
            )

    def test_check_flag(self):
        r = subprocess.run([sys.executable, str(HERE / "gen_fidel_table.py"), "--check"])
        self.assertEqual(r.returncode, 0)

    def test_generation_is_deterministic(self):
        self.assertEqual(gen.render_json(gen.build()), gen.render_json(gen.build()))


class TableContent(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        t = json.loads((gen.ROOT / "language-data" / "generated" / "fidel-table-v1.json").read_text("utf-8"))
        cls.series = {s["id"]: s["forms"] for s in t["series"]}

    def ch(self, sid, order):
        return chr(int(self.series[sid][order][2:], 16))

    def test_regular_series(self):
        self.assertEqual("".join(self.ch("S", o) for o in ["A", "U", "I", "AA", "EE", "E", "O"]), "ሰሱሲሳሴስሶ")

    def test_labialised_and_labiovelar(self):
        self.assertEqual(self.ch("L", "WA"), "ሏ")
        self.assertEqual(self.ch("GW", "AA"), "ጓ")
        self.assertEqual(self.ch("QW", "AA"), "ቋ")
        self.assertEqual(self.ch("KW", "AA"), "ኳ")
        self.assertEqual(self.ch("XW", "AA"), "ኋ")
        self.assertNotIn("U", self.series["GW"])
        self.assertEqual(sorted(self.series["GW"]), sorted(["A", "I", "AA", "EE", "E"]))

    def test_tigrinya_series(self):
        self.assertEqual(self.ch("QH", "A"), "ቐ")
        self.assertEqual(self.ch("KX", "A"), "ኸ")

    def test_every_form_name_matches_its_code_point(self):
        for sid, forms in self.series.items():
            for order, cp in forms.items():
                name = unicodedata.name(chr(int(cp[2:], 16)))
                self.assertTrue(name.startswith("ETHIOPIC SYLLABLE "), name)
                self.assertTrue(name.endswith(order), (sid, order, name))


class DraftLayouts(unittest.TestCase):
    def test_layouts_reference_known_series_and_are_nfc(self):
        t = json.loads((gen.ROOT / "language-data" / "generated" / "fidel-table-v1.json").read_text("utf-8"))
        ids = {s["id"] for s in t["series"]}
        for name in ["am-fidel-draft.json", "ti-fidel-draft.json"]:
            text = (LAYOUTS / name).read_text("utf-8")
            self.assertEqual(unicodedata.normalize("NFC", text), text)
            lay = json.loads(text)
            self.assertEqual(lay["status"], "DRAFT")
            seen = set()
            for row in lay["rows"]:
                self.assertTrue(row)
                for k in row:
                    self.assertIn(k["series"], ids)
                    self.assertNotIn(k["series"], seen)
                    seen.add(k["series"])
                    if "labialized" in k:
                        self.assertIn(k["labialized"], ids)


if __name__ == "__main__":
    unittest.main()
