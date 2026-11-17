"""L1.3 tests: size budgets (SD 17.1), shipped assets consistent with the index, DRAFT status,
and no private key material in the shipped files. Run: python language-data/scripts/test_seed_packs.py"""
import json
import shutil
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_packs as bp  # noqa: E402
import build_seed_packs as sp  # noqa: E402


class Budgets(unittest.TestCase):
    def setUp(self):
        self.b = sp.load_budgets()

    def test_budgets_match_sd_17_1(self):
        self.assertEqual(self.b["perLanguageBytes"]["am"], 15 * 1024 * 1024)
        self.assertEqual(self.b["perLanguageBytes"]["ti"], 15 * 1024 * 1024)
        self.assertEqual(self.b["perLanguageBytes"]["en"], 25 * 1024 * 1024)

    def test_over_budget_fails(self):
        with self.assertRaisesRegex(bp.BuildError, "budget exceeded for am"):
            sp.check_budgets({"am": [15 * 1024 * 1024 + 1]}, self.b)

    def test_shared_pack_counts_against_every_language(self):
        with self.assertRaisesRegex(bp.BuildError, "budget exceeded"):
            sp.check_budgets({"mul": [16 * 1024 * 1024]}, self.b)

    def test_language_without_budget_fails(self):
        with self.assertRaisesRegex(bp.BuildError, "no size budget"):
            sp.check_budgets({"xx": [1]}, self.b)

    def test_within_budget_passes(self):
        t = sp.check_budgets({"am": [10], "mul": [5], "en": [1]}, self.b)
        self.assertEqual((t["am"], t["en"], t["om"]), (15, 6, 5))


class ShippedAssets(unittest.TestCase):
    def test_android_assets_exist_and_match_index(self):
        for t in sp.targets():
            index = json.loads((t / sp.INDEX).read_text("utf-8"))
            self.assertEqual(index["status"], "DRAFT_UNVERIFIED")
            self.assertIs(index["userExposable"], False)
            langs = {p["languageCode"] for p in index["packs"]}
            self.assertEqual(langs, {"am", "ti", "om", "en", "mul"})
            for p in index["packs"]:
                self.assertEqual(p["releaseChannel"], "INTERNAL")
                self.assertEqual(p["status"], "DRAFT_UNVERIFIED")
                self.assertTrue((t / p["file"]).is_file())

    def test_check_passes_and_budgets_hold(self):
        totals = sp.check()
        for lang, total in totals.items():
            self.assertLessEqual(total, self.__class__.budget(lang))

    @staticmethod
    def budget(lang):
        return sp.load_budgets()["perLanguageBytes"][lang]

    def test_no_private_key_material_shipped(self):
        for t in sp.targets():
            for f in t.iterdir():
                data = f.read_bytes()
                self.assertNotIn(b"seedHex", data, f.name)
                self.assertNotIn(b"qal-dev-signing-key", data, f.name)

    def test_platform_copies_identical(self):
        ts = sp.targets()
        for f in ts[0].iterdir():
            for other in ts[1:]:
                self.assertEqual(f.read_bytes(), (other / f.name).read_bytes(), f.name)

    def test_every_source_input_is_licence_gated(self):
        # The gate runs in build_all; here we also assert the table only uses allow-listed codes.
        lic = bp.load_licenses()
        for name, _, _, _, inputs in sp.pack_table():
            for _, _, code, _ in inputs:
                bp.check_license(code, lic, name)


if __name__ == "__main__":
    unittest.main()
