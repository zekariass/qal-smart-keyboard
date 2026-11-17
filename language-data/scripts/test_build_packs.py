"""Tests for the pack build (plan L1.1): reproducibility, cross-signer agreement, core verification.

Run: python -m unittest discover -s language-data/scripts -p "test_*.py"
The core-verification test needs `cargo` and is skipped when it is missing.
"""
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_packs as bp  # noqa: E402
import ed25519_ref  # noqa: E402

CORE = bp.ROOT / "core"
VECTOR = CORE / "tests" / "vectors" / "pack-v1" / "valid-minimal.json"
TEST_SEED = bytes(range(32))  # public test seed, same as the core's test-active-1


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.ks = self.tmp / "ks"
        bp.init_dev_key(self.ks, seed=TEST_SEED)

    def build(self, name):
        return bp.build_all(bp.SOURCES_DIR, self.tmp / name, self.ks)


class Signer(unittest.TestCase):
    def test_rfc8032_vector_1(self):
        seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        self.assertEqual(
            ed25519_ref.public_key(seed).hex(),
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        )
        self.assertEqual(
            ed25519_ref.sign(seed, b"").hex(),
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )

    def test_matches_core_vector_byte_for_byte(self):
        v = json.loads(VECTOR.read_text("utf-8"))["input"]
        manifest = v["manifestUtf8"].encode("utf-8")
        got = bp.assemble(v["signingKeyId"], bytes.fromhex(v["signingSeedHex"]), manifest, bytes.fromhex(v["artifactHex"]))
        self.assertEqual(got.hex(), json.loads(VECTOR.read_text("utf-8"))["containerHex"])


class Build(Base):
    def test_reproducible(self):
        a, b = self.build("a"), self.build("b")
        self.assertEqual(a, b)
        for name, _ in a:
            self.assertEqual((self.tmp / "a" / (name + ".pack")).read_bytes(), (self.tmp / "b" / (name + ".pack")).read_bytes())

    def test_manifest_commits_to_artifact(self):
        built = dict(self.build("a"))
        m = json.loads((self.tmp / "a" / "am-keyboard-layout.manifest.json").read_text("utf-8"))
        art = (bp.ROOT / "language-data" / "layouts" / "am-fidel-draft.json").read_bytes()
        self.assertEqual(m["sha256"], hashlib.sha256(art).hexdigest())
        self.assertEqual(m["sizeBytes"], len(art))
        self.assertTrue(built["am-keyboard-layout"].endswith(art))

    def test_dev_key_cannot_sign_stable(self):
        src = self.tmp / "src"
        src.mkdir()
        doc = json.loads((bp.SOURCES_DIR / "am-keyboard-layout.source.json").read_text("utf-8"))
        doc["releaseChannel"] = "STABLE"
        doc["artifact"]["path"] = str(bp.ROOT / "language-data" / "layouts" / "am-fidel-draft.json")
        doc["inputs"][0]["path"] = doc["artifact"]["path"]
        (src / "x.source.json").write_text(json.dumps(doc), "utf-8")
        with self.assertRaisesRegex(bp.BuildError, "STABLE"):
            bp.build_all(src, self.tmp / "o", self.ks)

    def test_non_dev_key_refused(self):
        p = next(self.ks.glob("*.key.json"))
        d = json.loads(p.read_text("utf-8"))
        d["devOnly"] = False
        p.write_text(json.dumps(d), "utf-8")
        with self.assertRaises(bp.BuildError):
            bp.load_dev_key(self.ks)


class LicenceGate(Base):
    """L1.2 / N10: the build refuses a missing, unknown or non-commercial licence."""

    def make(self, mutate):
        src = self.tmp / "src"
        src.mkdir(exist_ok=True)
        doc = json.loads((bp.SOURCES_DIR / "am-keyboard-layout.source.json").read_text("utf-8"))
        layout = str(bp.ROOT / "language-data" / "layouts" / "am-fidel-draft.json")
        doc["artifact"]["path"] = layout
        doc["inputs"][0]["path"] = layout
        mutate(doc)
        (src / "x.source.json").write_text(json.dumps(doc), "utf-8")
        return src

    def refused(self, mutate, pattern):
        src = self.make(mutate)
        out = self.tmp / "o"
        with self.assertRaisesRegex(bp.BuildError, pattern):
            bp.build_all(src, out, self.ks)
        self.assertFalse(out.exists(), "nothing may be written when the gate fails")

    def test_passing_case_writes_dataset_manifest(self):
        self.build("a")
        doc = json.loads((self.tmp / "a" / "datasets.manifest.json").read_text("utf-8"))
        self.assertEqual(doc["format"], "qal-datasets-manifest")
        ids = [d["datasetCode"] for d in doc["datasets"]]
        self.assertEqual(ids, sorted(ids))
        self.assertEqual(len(ids), 2)
        d = doc["datasets"][0]
        art = (bp.ROOT / "language-data" / "layouts" / "am-fidel-draft.json").read_bytes()
        self.assertEqual(d["sha256"], hashlib.sha256(art).hexdigest())
        self.assertEqual(d["licenseCode"], "LicenseRef-Qal-Original")
        self.assertEqual(d["permittedUses"], ["COMMERCIAL"])
        self.assertEqual(d["consentBasis"], "OWN_CREATION")
        self.assertEqual(d["reviewStatus"], "PENDING_REVIEW")
        self.assertEqual(d["packs"], ["am-keyboard-layout"])
        self.assertEqual(d["path"], "language-data/layouts/am-fidel-draft.json")

    def test_dataset_manifest_reproducible(self):
        self.build("a")
        self.build("b")
        self.assertEqual((self.tmp / "a" / "datasets.manifest.json").read_bytes(), (self.tmp / "b" / "datasets.manifest.json").read_bytes())

    def test_pack_licence_missing(self):
        self.refused(lambda d: d.pop("licenseCode"), "missing licenseCode")

    def test_pack_licence_unknown_fails_closed(self):
        self.refused(lambda d: d.update(licenseCode="LicenseRef-Mystery"), "not in the allow-list")

    def test_pack_licence_non_commercial(self):
        self.refused(lambda d: d.update(licenseCode="CC-BY-NC-4.0"), "does not permit commercial use")

    def test_input_licence_missing(self):
        self.refused(lambda d: d["inputs"][0].pop("licenseCode"), "licence missing")

    def test_input_licence_empty(self):
        self.refused(lambda d: d["inputs"][0].update(licenseCode=" "), "licence missing")

    def test_input_licence_unknown(self):
        self.refused(lambda d: d["inputs"][0].update(licenseCode="GPL-9000"), "not in the allow-list")

    def test_input_licence_non_commercial_while_pack_is_fine(self):
        self.refused(lambda d: d["inputs"][0].update(licenseCode="CC-BY-NC-SA-4.0"), "does not permit commercial use")

    def test_inputs_missing_or_empty(self):
        self.refused(lambda d: d.pop("inputs"), "inputs")
        self.refused(lambda d: d.update(inputs=[]), "inputs")

    def test_nc_code_refused_even_if_allow_list_says_commercial(self):
        lic = self.tmp / "lic.json"
        doc = json.loads(bp.LICENSES_FILE.read_text("utf-8"))
        doc["licenses"]["CC-BY-NC-4.0"]["commercialUse"] = True
        lic.write_text(json.dumps(doc), "utf-8")
        src = self.make(lambda d: d["inputs"][0].update(licenseCode="CC-BY-NC-4.0"))
        with self.assertRaisesRegex(bp.BuildError, "does not permit commercial use"):
            bp.build_all(src, self.tmp / "o", self.ks, licenses_file=lic)

    def test_shipped_allow_list_is_sane(self):
        for code, e in bp.load_licenses().items():
            self.assertIn(e["commercialUse"], (True, False), code)
            if "-NC" in code or code in ("UNKNOWN", "PROPRIETARY"):
                self.assertFalse(e["commercialUse"], code)

    def test_cli_exits_nonzero_on_unlicensed_source(self):
        src = self.make(lambda d: d["inputs"][0].pop("licenseCode"))
        r = subprocess.run(
            [sys.executable, str(HERE / "build_packs.py"), "--sources", str(src), "--out", str(self.tmp / "o"), "--keystore", str(self.ks)],
            capture_output=True, text=True, encoding="utf-8",
        )
        self.assertEqual(r.returncode, 1)
        self.assertIn("licence missing", r.stderr)


@unittest.skipUnless(shutil.which("cargo"), "cargo not installed")
class CoreVerifies(Base):
    def run_verify(self, *packs):
        key_id, _, pub = bp.load_dev_key(self.ks)
        return subprocess.run(
            ["cargo", "run", "-q", "--bin", "qal-pack-verify", "--", key_id, pub, *map(str, packs)],
            cwd=CORE, capture_output=True, text=True, encoding="utf-8",
        )

    def test_core_accepts_and_rejects_tampered(self):
        self.build("p")
        packs = sorted((self.tmp / "p").glob("*.pack"))
        r = self.run_verify(*packs)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(r.stdout.count("OK "), len(packs))
        bad = self.tmp / "bad.pack"
        data = bytearray(packs[0].read_bytes())
        data[-1] ^= 1
        bad.write_bytes(bytes(data))
        r = self.run_verify(bad)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("FAIL", r.stdout)


if __name__ == "__main__":
    unittest.main()
