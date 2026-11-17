"""Writes seed corpora for both fuzz targets from ../tests/vectors/pack-v1/*.json.

Usage (from core/fuzz): python3 -I seed_corpus.py
"""
import glob
import json
import os
import struct

here = os.path.dirname(os.path.abspath(__file__))
vectors = sorted(glob.glob(os.path.join(here, "..", "tests", "vectors", "pack-v1", "*.json")))
key_selector = {"test-active-1": 0, "test-retiring-1": 1, "test-revoked-1": 2}
for target in ("pack_container", "pack_signed_manifest"):
    os.makedirs(os.path.join(here, "corpus", target), exist_ok=True)
for path in vectors:
    with open(path, encoding="utf-8") as f:
        v = json.load(f)
    name = v["name"]
    with open(os.path.join(here, "corpus", "pack_container", name), "wb") as f:
        f.write(bytes.fromhex(v["containerHex"]))
    i = v.get("input")
    if i and i["signingKeyId"] in key_selector:
        manifest = i["manifestUtf8"].encode("utf-8")
        seed = bytes([key_selector[i["signingKeyId"]]]) + struct.pack(">H", len(manifest))
        seed += manifest + bytes.fromhex(i["artifactHex"])
        with open(os.path.join(here, "corpus", "pack_signed_manifest", name), "wb") as f:
            f.write(seed)
print(f"seeded from {len(vectors)} vectors")
