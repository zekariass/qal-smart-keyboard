#!/usr/bin/env python3
"""Build signed Qal language packs from source manifests (plan step L1.1). Stdlib only.

  language-data/sources/*.source.json  --(this script)-->  <out>/<name>.pack + <name>.manifest.json

Container and signature: docs/specs/pack-format-v1.md (ADR 0002). The manifest is emitted as
compact JSON in the same member order as the Rust builder, and signed byte for byte as written.
Builds are reproducible: same source files + same key => byte-identical `.pack` (Ed25519 is
deterministic; nothing time- or host-dependent is embedded; sources are processed sorted by name).

Source manifest (`format: qal-pack-source`, formatVersion 1):
  resourceId, languageCode, resourceType, version, platform, releaseChannel, licenseCode   required
  minAppVersion                                                                          optional
  artifact {kind: "file", path}   the artifact is the file's bytes verbatim (path relative to the
                                  source manifest). Other kinds (e.g. a lexicon built from a
                                  wordlist, C1.6) are added here when their artifact format exists.
  inputs[] {id, path, licenseCode}   every raw input the artifact derives from, with its licence.
                                  L1.1 only records it; L1.2 makes the build REFUSE a missing or
                                  non-commercial licence (N10) using this field. The pack-level
                                  `licenseCode` is already mandatory because the signed manifest
                                  needs it.

Keys: ONLY a dev keystore (default language-data/keys/dev/, gitignored) is supported. It is created
on demand with a random seed (`--init-dev-key`) and refuses to sign STABLE packs. Production keys
live in KMS/HSM and are used by the backend publisher (B3.2), never by this script.

Usage:
  python language-data/scripts/build_packs.py --init-dev-key          # once per checkout
  python language-data/scripts/build_packs.py [--out DIR] [--keystore DIR] [--check-reproducible]
"""
import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import ed25519_ref  # noqa: E402

ROOT = HERE.parents[1]
SOURCES_DIR = ROOT / "language-data" / "sources"
DEFAULT_KEYSTORE = ROOT / "language-data" / "keys" / "dev"
DEFAULT_OUT = ROOT / "language-data" / "build" / "packs"

MAGIC = b"QALPACK\x00"
FORMAT_VERSION = 1
SIGN_CONTEXT = b"QAL-PACK-MANIFEST-v1\x00"
MAX_ARTIFACT = 32 * 1024 * 1024
MAX_MANIFEST = 64 * 1024

RESOURCE_TYPES = {
    "KEYBOARD_LAYOUT", "TRANSLITERATION_RULES", "DICTIONARY", "FREQUENCY_LIST", "PREDICTION_MODEL",
    "AUTOCORRECTION_MODEL", "PROTECTED_TERMS", "PRONUNCIATION", "EMOJI_KEYWORDS", "GLOSSARY",
    "SPELL_MODEL", "LANGUAGE_ID_MODEL", "TRANSLATION_MODEL", "ASR_MODEL", "TTS_VOICE",
    "ON_DEVICE_LLM", "OTHER",
}
PLATFORMS = {"SHARED", "ANDROID", "IOS"}
CHANNELS = {"INTERNAL", "BETA", "STABLE"}

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
LANG_RE = re.compile(r"^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
KEY_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,120}$")
_ID = r"(0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
SEMVER_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-%s(\.%s)*)?$" % (_ID, _ID))


class BuildError(Exception):
    pass


# --------------------------------------------------------------------------------- keystore


def init_dev_key(keystore, seed=None):
    """Create a dev keystore file. `seed` is for tests only; normally random."""
    keystore = Path(keystore)
    seed = os.urandom(32) if seed is None else seed
    pub = ed25519_ref.public_key(seed)
    key_id = "qal-dev-" + pub.hex()[:8]
    keystore.mkdir(parents=True, exist_ok=True)
    path = keystore / (key_id + ".key.json")
    if path.exists():
        raise BuildError("%s already exists" % path)
    doc = {
        "format": "qal-dev-signing-key",
        "devOnly": True,
        "keyId": key_id,
        "publicKeyHex": pub.hex(),
        "seedHex": seed.hex(),
        "note": "DEV ONLY. Not a production secret; never trusted by production builds.",
    }
    path.write_text(json.dumps(doc, indent=1) + "\n", encoding="utf-8", newline="\n")
    return path


def load_dev_key(keystore):
    """Returns (key_id, seed, public_key_hex) of the single dev key in `keystore`."""
    keystore = Path(keystore)
    files = sorted(keystore.glob("*.key.json")) if keystore.is_dir() else []
    if not files:
        raise BuildError("no dev key in %s; run with --init-dev-key" % keystore)
    if len(files) > 1:
        raise BuildError("several keys in %s; keep exactly one" % keystore)
    doc = json.loads(files[0].read_text(encoding="utf-8"))
    if doc.get("devOnly") is not True or doc.get("format") != "qal-dev-signing-key":
        raise BuildError("%s is not a dev-only key; production keys are never used here" % files[0])
    if not KEY_ID_RE.match(doc["keyId"]) or not doc["keyId"].startswith("qal-dev-"):
        raise BuildError("dev key id must be qal-dev-*")
    seed = bytes.fromhex(doc["seedHex"])
    if ed25519_ref.public_key(seed).hex() != doc["publicKeyHex"]:
        raise BuildError("dev key file is inconsistent")
    return doc["keyId"], seed, doc["publicKeyHex"]


# --------------------------------------------------------------------------------- sources


def _req(doc, key, where):
    if key not in doc or doc[key] in (None, ""):
        raise BuildError("%s: missing %s" % (where, key))
    return doc[key]


def load_source(path):
    path = Path(path)
    where = path.name
    doc = json.loads(path.read_text(encoding="utf-8"))
    if doc.get("format") != "qal-pack-source" or doc.get("formatVersion") != 1:
        raise BuildError("%s: not a qal-pack-source v1 file" % where)
    s = {
        k: _req(doc, k, where)
        for k in (
            "resourceId", "languageCode", "resourceType", "version", "platform", "releaseChannel",
            "licenseCode",
        )
    }
    if not UUID_RE.match(s["resourceId"]):
        raise BuildError("%s: resourceId must be a lowercase UUID" % where)
    if not LANG_RE.match(s["languageCode"]) or len(s["languageCode"]) > 35:
        raise BuildError("%s: bad languageCode" % where)
    if s["resourceType"] not in RESOURCE_TYPES:
        raise BuildError("%s: unknown resourceType" % where)
    if not SEMVER_RE.match(s["version"]) or len(s["version"]) > 40:
        raise BuildError("%s: version must be SemVer without build metadata" % where)
    if s["platform"] not in PLATFORMS or s["releaseChannel"] not in CHANNELS:
        raise BuildError("%s: bad platform or releaseChannel" % where)
    lic = s["licenseCode"]
    if not (1 <= len(lic) <= 60 and all(0x20 <= ord(c) <= 0x7E for c in lic)):
        raise BuildError("%s: licenseCode must be 1-60 printable ASCII chars" % where)
    s["minAppVersion"] = doc.get("minAppVersion")
    if s["minAppVersion"] is not None and not SEMVER_RE.match(s["minAppVersion"]):
        raise BuildError("%s: bad minAppVersion" % where)
    art = doc.get("artifact") or {}
    if art.get("kind") != "file" or not art.get("path"):
        raise BuildError("%s: artifact must be {kind: file, path}" % where)
    s["artifactPath"] = (path.parent / art["path"]).resolve()
    s["inputs"] = doc.get("inputs", [])  # L1.2 enforces per-input licences
    s["name"] = path.name[: -len(".source.json")]
    return s


# --------------------------------------------------------------------------------- build


def manifest_bytes(src, key_id, artifact):
    # Member order matches core/src/pack/builder.rs so both signers emit identical bytes.
    m = {
        "resourceId": src["resourceId"],
        "languageCode": src["languageCode"],
        "resourceType": src["resourceType"],
        "version": src["version"],
        "platform": src["platform"],
        "releaseChannel": src["releaseChannel"],
    }
    if src["minAppVersion"] is not None:
        m["minAppVersion"] = src["minAppVersion"]
    m["sha256"] = hashlib.sha256(artifact).hexdigest()
    m["sizeBytes"] = len(artifact)
    m["baseResourceId"] = None
    m["licenseCode"] = src["licenseCode"]
    m["signatureKeyId"] = key_id
    return json.dumps(m, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def assemble(key_id, seed, manifest, artifact):
    if len(manifest) > MAX_MANIFEST or len(artifact) > MAX_ARTIFACT:
        raise BuildError("manifest or artifact over the v1 default limit")
    sig = ed25519_ref.sign(seed, SIGN_CONTEXT + manifest)
    kid = key_id.encode("ascii")
    return b"".join(
        [
            MAGIC,
            FORMAT_VERSION.to_bytes(2, "big"),
            bytes([len(kid)]),
            kid,
            sig,
            len(manifest).to_bytes(4, "big"),
            manifest,
            len(artifact).to_bytes(8, "big"),
            artifact,
        ]
    )


def build_all(sources_dir, out_dir, keystore):
    """Writes <name>.pack and <name>.manifest.json; returns [(name, pack_bytes)]."""
    key_id, seed, _ = load_dev_key(keystore)
    files = sorted(Path(sources_dir).glob("*.source.json"), key=lambda p: p.name)
    if not files:
        raise BuildError("no *.source.json in %s" % sources_dir)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    built = []
    for f in files:
        src = load_source(f)
        if src["releaseChannel"] == "STABLE":
            raise BuildError("%s: a dev key may not sign STABLE packs" % f.name)
        artifact = src["artifactPath"].read_bytes()
        manifest = manifest_bytes(src, key_id, artifact)
        pack = assemble(key_id, seed, manifest, artifact)
        (out_dir / (src["name"] + ".pack")).write_bytes(pack)
        # The exact signed manifest bytes, for the backend (spec §5.2: keep the signed bytes).
        (out_dir / (src["name"] + ".manifest.json")).write_bytes(manifest)
        built.append((src["name"], pack))
    return built


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--sources", default=str(SOURCES_DIR))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--keystore", default=str(DEFAULT_KEYSTORE))
    ap.add_argument("--init-dev-key", action="store_true", help="create a random dev key if none exists")
    ap.add_argument("--check-reproducible", action="store_true", help="build twice, require identical bytes")
    a = ap.parse_args(argv)
    try:
        if a.init_dev_key and not list(Path(a.keystore).glob("*.key.json")):
            print("created dev key:", init_dev_key(a.keystore))
        built = build_all(a.sources, a.out, a.keystore)
        if a.check_reproducible:
            if built != build_all(a.sources, a.out, a.keystore):
                raise BuildError("build is NOT reproducible")
            print("reproducible: two builds are byte-identical")
        key_id, _, pub = load_dev_key(a.keystore)
    except BuildError as e:
        print("error:", e, file=sys.stderr)
        return 1
    print("signing key %s public %s (DEV ONLY)" % (key_id, pub))
    for name, pack in built:
        print("%-30s %7d bytes  sha256 %s" % (name + ".pack", len(pack), hashlib.sha256(pack).hexdigest()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
