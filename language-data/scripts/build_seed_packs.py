#!/usr/bin/env python3
"""Build the L1.3 seed packs (AM, TI, OM, EN baseline) and ship them as app assets. Stdlib only.

Pipeline: text sources in language-data/seed/ --(core `qal-artifact-build`)--> payloads in
language-data/seed/artifacts/ (QLX1 lexicon, QBG1 bigrams, QLID v1 language-ID model; committed, so
`--check` can detect staleness) --(build_packs.py, licence gate N10 + dev signing)--> signed packs
--> android/ime/src/main/assets/packs/ and ios/KeyboardExtension/Resources/packs/ (the latter only
because ios/ exists) plus `seed-packs.json`, the index (key id, public key, hashes, status).

ALL packs are DRAFT / UNVERIFIED (N9): the word lists, bigrams and language-ID seeds are tiny,
hand-written by the engineering team (LicenseRef-Qal-Original) and NOT native-speaker reviewed
(human gate G0); frequencies are placeholders. releaseChannel is INTERNAL, which the host must not
expose to users. Signed with the DEV key only (language-data/keys/dev/, gitignored); production
signing is the backend publisher's job (B3.2).

  python language-data/scripts/build_seed_packs.py [--keystore DIR]   build, copy, check budgets
  python language-data/scripts/build_seed_packs.py --check            artifacts fresh, shipped packs
                                                                      consistent, size budgets met
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_packs as bp  # noqa: E402

ROOT = bp.ROOT
LD = ROOT / "language-data"
SEED = LD / "seed"
ARTIFACTS = SEED / "artifacts"
BUDGETS = SEED / "budgets.json"
CORE = ROOT / "core"
BUILD = LD / "build" / "seed"
ANDROID_DIR = ROOT / "android" / "ime" / "src" / "main" / "assets" / "packs"
IOS_DIR = ROOT / "ios" / "KeyboardExtension" / "Resources" / "packs"
INDEX = "seed-packs.json"
LANGID_TAGS = ["am", "ti", "om", "en", "am-latn", "ti-latn"]
QAL = "LicenseRef-Qal-Original"

# resourceId (fixed, lowercase UUID) per pack. Layout ids equal the ones in language-data/sources/.
IDS = {
    "am-dictionary": "30bd07a4-6adb-49bc-9f68-bb3699b76bf4",
    "ti-dictionary": "0430aae1-5674-48dd-b3b8-8671da2a1327",
    "om-dictionary": "a7dc9311-d671-438f-aaeb-c456d5c56013",
    "en-dictionary": "6f606008-9e36-457d-a723-11bb7bef3af6",
    "am-bigrams": "c82ea9a8-c8cb-42a4-80eb-ea2ddd141e9c",
    "ti-bigrams": "e41e2aaf-bfd1-4b0d-a2ae-93e42281a140",
    "om-bigrams": "fa400f9c-cbea-4134-84cf-07c2f600c508",
    "en-bigrams": "86b9aa62-9eb2-469d-acd7-8c93e109bb82",
    "am-phonetic-rules": "7ea51cf6-db4a-4908-b042-5970ab26bc13",
    "ti-phonetic-rules": "d551c9bf-6c3d-4b0a-abd5-862bb8e76112",
    "mul-langid": "1092245e-4040-4733-9193-c6b23adfb240",
}


def artifact_jobs():
    """[(artifact file, tool args)]; the tool writes to the output path given to run_artifact_tool."""
    jobs = []
    for lang in ("am", "ti", "om", "en"):
        jobs.append((ARTIFACTS / (lang + ".lexicon.qlx"), ["lexicon", str(SEED / "wordlists" / (lang + ".seed.tsv"))]))
        jobs.append((ARTIFACTS / (lang + ".bigrams.qbg"), ["bigrams", str(SEED / "bigrams" / (lang + ".seed.tsv"))]))
    langid = ["%s=%s" % (t, LD / "eval" / "langid" / ("train_%s.txt" % t)) for t in LANGID_TAGS]
    jobs.append((ARTIFACTS / "mul.langid.qlid", ["langid"] + langid))
    return jobs


def run_artifact_tool(args, out):
    cmd = ["cargo", "run", "-q", "--bin", "qal-artifact-build", "--"]
    if args[0] == "langid":
        cmd += ["langid", str(out)] + args[1:]
    else:
        cmd += [args[0], args[1], str(out)]
    r = subprocess.run(cmd, cwd=CORE, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        raise bp.BuildError("qal-artifact-build failed: " + r.stderr.strip())


def generate_artifacts(out_dir):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    for art, args in artifact_jobs():
        run_artifact_tool(args, out_dir / art.name)


def pack_table():
    """[(name, languageCode, resourceType, artifact path, [(input id, path, licence, name)])]."""
    rows = []
    for lang in ("am", "ti", "om", "en"):
        wl = SEED / "wordlists" / (lang + ".seed.tsv")
        bg = SEED / "bigrams" / (lang + ".seed.tsv")
        rows.append(("%s-dictionary" % lang, lang, "DICTIONARY", ARTIFACTS / (lang + ".lexicon.qlx"),
                     [("%s-seed-wordlist" % lang, wl, QAL, "%s DRAFT seed word list" % lang)]))
        rows.append(("%s-bigrams" % lang, lang, "FREQUENCY_LIST", ARTIFACTS / (lang + ".bigrams.qbg"),
                     [("%s-seed-bigrams" % lang, bg, QAL, "%s DRAFT seed bigrams" % lang)]))
    for lang in ("am", "ti"):
        lay = LD / "layouts" / ("%s-fidel-draft.json" % lang)
        rows.append(("%s-keyboard-layout" % lang, lang, "KEYBOARD_LAYOUT", lay,
                     [("%s-fidel-draft-layout" % lang, lay, QAL, None)]))
        prof = LD / "profiles" / ("%s-phonetic-draft.json" % lang)
        rows.append(("%s-phonetic-rules" % lang, lang, "TRANSLITERATION_RULES", prof,
                     [("%s-phonetic-draft-profile" % lang, prof, QAL, None)]))
    langid_inputs = [("langid-seed-%s" % t, LD / "eval" / "langid" / ("train_%s.txt" % t), QAL,
                      "%s DRAFT synthetic language-ID seed words" % t) for t in LANGID_TAGS]
    rows.append(("mul-langid", "mul", "LANGUAGE_ID_MODEL", ARTIFACTS / "mul.langid.qlid", langid_inputs))
    return rows


def write_sources(dir_):
    dir_ = Path(dir_)
    dir_.mkdir(parents=True, exist_ok=True)
    layout_ids = {}
    for f in (LD / "sources").glob("*-keyboard-layout.source.json"):
        d = json.loads(f.read_text("utf-8"))
        layout_ids[d["languageCode"]] = d["resourceId"]
    for name, lang, rtype, art, inputs in pack_table():
        doc = {
            "format": "qal-pack-source", "formatVersion": 1,
            "resourceId": layout_ids[lang] if rtype == "KEYBOARD_LAYOUT" else IDS[name],
            "languageCode": lang, "resourceType": rtype, "version": "0.1.0", "platform": "SHARED",
            "releaseChannel": "INTERNAL", "licenseCode": QAL,
            "artifact": {"kind": "file", "path": str(art)},
            "inputs": [dict(id=i, path=str(p), licenseCode=lic, **({"name": n} if n else {}))
                       for i, p, lic, n in inputs],
        }
        (dir_ / (name + ".source.json")).write_text(
            json.dumps(doc, indent=1, ensure_ascii=False) + "\n", "utf-8", newline="\n")


# ----------------------------------------------------------------------------- budgets


def load_budgets(path=BUDGETS):
    doc = json.loads(Path(path).read_text("utf-8"))
    if doc.get("format") != "qal-size-budgets" or doc.get("formatVersion") != 1:
        raise bp.BuildError("bad budgets file")
    return doc


def check_budgets(pack_sizes, budgets):
    """pack_sizes: {languageCode: [bytes, ...]} -> {lang: total}. Raises BuildError over budget.

    Shared language codes count against every budgeted language."""
    shared = sum(sum(v) for k, v in pack_sizes.items() if k in budgets["sharedLanguageCodes"])
    unknown = set(pack_sizes) - set(budgets["perLanguageBytes"]) - set(budgets["sharedLanguageCodes"])
    if unknown:
        raise bp.BuildError("pack language(s) %s have no size budget" % sorted(unknown))
    totals = {}
    for lang, limit in budgets["perLanguageBytes"].items():
        total = sum(pack_sizes.get(lang, [])) + shared
        totals[lang] = total
        if total > limit:
            raise bp.BuildError("size budget exceeded for %s: %d > %d bytes (SD 17.1)" % (lang, total, limit))
    return totals


# ----------------------------------------------------------------------------- build / check


def targets():
    t = [ANDROID_DIR]
    if (ROOT / "ios").is_dir():
        t.append(IOS_DIR)
    return t


def index_doc(out_dir, key_id, pub, table):
    packs = []
    for name, lang, rtype, _, inputs in table:
        m = json.loads((out_dir / (name + ".manifest.json")).read_text("utf-8"))
        pack = (out_dir / (name + ".pack")).read_bytes()
        packs.append({
            "file": name + ".pack", "languageCode": lang, "resourceType": rtype, "version": m["version"],
            "releaseChannel": m["releaseChannel"], "sizeBytes": len(pack),
            "sha256": hashlib.sha256(pack).hexdigest(), "licenseCode": m["licenseCode"],
            "datasets": [i[0] for i in inputs], "status": "DRAFT_UNVERIFIED",
        })
    return {
        "format": "qal-seed-pack-index", "formatVersion": 1,
        "status": "DRAFT_UNVERIFIED", "userExposable": False,
        "reviewRequired": "Native-speaker review (human gate G0) for am, ti, om; none of this content may be shown to users as verified (N9).",
        "signingKeyId": key_id, "signingPublicKeyHex": pub, "signingKeyIsDevOnly": True,
        "packs": sorted(packs, key=lambda p: p["file"]),
    }


def build(keystore, check_repro=False):
    generate_artifacts(ARTIFACTS)
    src = BUILD / "sources"
    out = BUILD / "packs"
    for d in (src, out):
        shutil.rmtree(d, ignore_errors=True)
    write_sources(src)
    built = bp.build_all(src, out, keystore)
    if check_repro and built != bp.build_all(src, out, keystore):
        raise bp.BuildError("build is NOT reproducible")
    table = pack_table()
    sizes = {}
    for name, lang, *_ in table:
        sizes.setdefault(lang, []).append((out / (name + ".pack")).stat().st_size)
    totals = check_budgets(sizes, load_budgets())
    key_id, _, pub = bp.load_dev_key(keystore)
    index = json.dumps(index_doc(out, key_id, pub, table), indent=1, ensure_ascii=False) + "\n"
    for tgt in targets():
        shutil.rmtree(tgt, ignore_errors=True)
        tgt.mkdir(parents=True)
        for name, *_ in table:
            shutil.copyfile(out / (name + ".pack"), tgt / (name + ".pack"))
        (tgt / INDEX).write_text(index, "utf-8", newline="\n")
    return totals


def check():
    """Checks that need no key: artifacts fresh, shipped packs match the index, budgets met."""
    tmp = BUILD / "artifacts-check"
    shutil.rmtree(tmp, ignore_errors=True)
    generate_artifacts(tmp)
    for art, _ in artifact_jobs():
        if (ARTIFACTS / art.name).read_bytes() != (tmp / art.name).read_bytes():
            raise bp.BuildError("%s is stale; rerun build_seed_packs.py" % art.name)
    texts = [(t / INDEX).read_text("utf-8") for t in targets()]
    if len(set(texts)) != 1:
        raise bp.BuildError("shipped index files differ between platforms")
    index = json.loads(texts[0])
    sizes = {}
    for p in index["packs"]:
        for t in targets():
            data = (t / p["file"]).read_bytes()
            if hashlib.sha256(data).hexdigest() != p["sha256"] or len(data) != p["sizeBytes"]:
                raise bp.BuildError("%s in %s does not match the index" % (p["file"], t))
        sizes.setdefault(p["languageCode"], []).append(p["sizeBytes"])
    return check_budgets(sizes, load_budgets())


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--keystore", default=str(bp.DEFAULT_KEYSTORE))
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--check-reproducible", action="store_true")
    a = ap.parse_args(argv)
    try:
        totals = check() if a.check else build(a.keystore, a.check_reproducible)
    except bp.BuildError as e:
        print("error:", e, file=sys.stderr)
        return 1
    budgets = load_budgets()["perLanguageBytes"]
    for lang, t in sorted(totals.items()):
        print("%-3s %8d bytes of %9d budget" % (lang, t, budgets[lang]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
