#!/usr/bin/env python3
"""Builds the keyboard's emoji assets (android/ime/src/main/assets/emoji/) from Unicode and CLDR data.

Inputs (not checked in; the script can download them):
  emoji-test.txt                      https://www.unicode.org/Public/emoji/latest/emoji-test.txt
  cldr-json annotations (per locale)  cldr-annotations-full + cldr-annotations-derived-full
                                      https://github.com/unicode-org/cldr-json

Usage:
  python -X utf8 android/ime/scripts/build_emoji_data.py --download
  python -X utf8 android/ime/scripts/build_emoji_data.py --emoji-test emoji-test.txt --cldr-dir <dir>
    (<dir> holds annotations-<lang>.json and annotationsDerived-<lang>.json for en, am, ti, om)

Outputs (UTF-8, LF, tab separated; lines starting with '#' are comments):
  emoji.tsv        '@Group' lines start a category; every other line is one fully-qualified emoji, in CLDR order:
                   text <TAB> English name <TAB> English keywords ('|' separated) <TAB> skin-tone variants (space separated, light..dark)
  emoji-<lang>.tsv one line per emoji.tsv entry, in the same order: localised name <TAB> keywords ('|' separated)
                   (languages: am, ti, om; an entry CLDR has no text for is a single TAB)
Only fully-qualified emoji are kept; the 'Component' group (bare skin-tone swatches, hair components) is dropped.
Skin tones: an emoji whose five uniform-tone forms exist is stored once with those variants; sequences that mix tones are dropped.
Licence: Unicode License v3 (see UNICODE-LICENSE.txt next to the outputs); keep it with the data.
"""
import argparse
import json
import os
import re
import sys
import tempfile
import urllib.request

LANGS = ["am", "ti", "om"]
TONES = [0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF]
EMOJI_TEST_URL = "https://www.unicode.org/Public/emoji/latest/emoji-test.txt"
CLDR_BASE = "https://raw.githubusercontent.com/unicode-org/cldr-json/main/cldr-json/"
LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*(\S+)\s*#\s*(\S+)\s+E\d+\.\d+\s+(.*)$")
FE0F = "️"


def download(dest):
    os.makedirs(dest, exist_ok=True)
    p = os.path.join(dest, "emoji-test.txt")
    urllib.request.urlretrieve(EMOJI_TEST_URL, p)
    for lang in ["en"] + LANGS:
        for kind, folder in (("annotations", "cldr-annotations-full/annotations"),
                             ("annotationsDerived", "cldr-annotations-derived-full/annotationsDerived")):
            urllib.request.urlretrieve(f"{CLDR_BASE}{folder}/{lang}/annotations.json", os.path.join(dest, f"{kind}-{lang}.json"))
    return p, dest


def parse_emoji_test(path):
    """-> (version, groups); groups = ordered list of (group name, [(text, name)]), fully-qualified only, tone forms included."""
    version = ""
    groups = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("# Version:"):
                version = line.split(":", 1)[1].strip()
            elif line.startswith("# group:"):
                groups.append((line.split(":", 1)[1].strip(), []))
            elif line and not line.startswith("#"):
                m = LINE.match(line)
                if m and m.group(2) == "fully-qualified" and groups:
                    groups[-1][1].append((m.group(3), m.group(4)))
    return version, [g for g in groups if g[0] != "Component"]


def norm(s):
    return s.replace(FE0F, "")


def load_annotations(cldr_dir, lang):
    """-> (cldr version, {normalised emoji: (name, [keywords])}); main annotations win over derived ones."""
    out = {}
    version = ""
    for kind in ("annotationsDerived", "annotations"):
        p = os.path.join(cldr_dir, f"{kind}-{lang}.json")
        if not os.path.exists(p):
            continue
        with open(p, encoding="utf-8") as f:
            data = json.load(f)[kind]
        version = data.get("identity", {}).get("version", {}).get("_cldrVersion", version)
        for text, a in data["annotations"].items():
            tts = (a.get("tts") or [""])[0]
            kws = [k for k in a.get("default", []) if k != tts]
            out[norm(text)] = (tts, kws)
    return version, out


def clean(s):
    return s.replace("\t", " ").replace("|", "/").replace("\n", " ").strip()


def build(emoji_test, cldr_dir, out_dir):
    version, groups = parse_emoji_test(emoji_test)
    en_version, en = load_annotations(cldr_dir, "en")
    langs = {l: load_annotations(cldr_dir, l)[1] for l in LANGS}

    entries = []  # (group, text, name, tone variants)
    for gname, items in groups:
        variants = {}
        bases = []
        for text, name in items:
            tones = {ord(c) for c in text if ord(c) in TONES}
            if not tones:
                bases.append((text, name))
            elif len(tones) == 1:
                stripped = "".join(c for c in text if ord(c) not in TONES)
                variants.setdefault(stripped, {})[next(iter(tones))] = text
        for text, name in bases:
            vs = variants.get(text, {})
            entries.append((gname, text, name, [vs[t] for t in TONES] if len(vs) == 5 else []))

    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "emoji.tsv")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Qal emoji data. Generated by android/ime/scripts/build_emoji_data.py; do not edit.\n")
        f.write(f"# Source: Unicode emoji-test.txt v{version}; CLDR annotations (en, cldr-json main). Unicode License v3, see UNICODE-LICENSE.txt.\n")
        f.write("# text<TAB>name<TAB>keywords(|)<TAB>skin-tone variants\n")
        cur = None
        for gname, text, name, vlist in entries:
            if gname != cur:
                f.write(f"@{gname}\n")
                cur = gname
            ename, ekw = en.get(norm(text), (name, []))
            f.write(f"{text}\t{clean(ename or name)}\t{'|'.join(clean(k) for k in ekw)}\t{' '.join(vlist)}\n")
    sizes = {"emoji.tsv": os.path.getsize(path)}
    for lang, ann in langs.items():
        name = f"emoji-{lang}.tsv"
        covered = 0
        path = os.path.join(out_dir, name)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(f"# Qal emoji annotations ({lang}), same order as emoji.tsv. Source: CLDR annotations (cldr-json main). Unicode License v3.\n")
            for _, text, _, _ in entries:
                n, kws = ann.get(norm(text), ("", []))
                covered += 1 if n else 0
                f.write(f"{clean(n)}\t{'|'.join(clean(k) for k in kws)}\n")
        sizes[name] = os.path.getsize(path)
        print(f"{name}: {covered}/{len(entries)} entries have a name", file=sys.stderr)
    print(f"{len(entries)} emoji, Unicode {version}; sizes {sizes}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--emoji-test")
    ap.add_argument("--cldr-dir")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "assets", "emoji"))
    a = ap.parse_args()
    if a.download:
        emoji_test, cldr = download(tempfile.mkdtemp(prefix="qal-emoji-"))
    elif a.emoji_test and a.cldr_dir:
        emoji_test, cldr = a.emoji_test, a.cldr_dir
    else:
        ap.error("give --download, or --emoji-test and --cldr-dir")
    build(emoji_test, cldr, a.out)


if __name__ == "__main__":
    main()
