from __future__ import annotations

import hashlib
import json
import os

KINDS = ("mt", "llm", "asr")
TASKS = ("translation", "asr")
LANGS = ("am", "ti", "om", "en")


class ConfigError(Exception):
    pass


def load(path: str) -> tuple[dict, str]:
    """Return (config, short sha256 of canonical JSON). Testset paths resolve relative to the file."""
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError) as e:
        raise ConfigError(f"cannot read config {path}: {e}") from None
    if not isinstance(cfg, dict):
        raise ConfigError("config must be a JSON object")
    canon = json.dumps(cfg, sort_keys=True, ensure_ascii=False)
    provs = cfg.get("providers")
    sets = cfg.get("testsets")
    if not isinstance(provs, list) or not provs:
        raise ConfigError("'providers' must be a non-empty list")
    if not isinstance(sets, list) or not sets:
        raise ConfigError("'testsets' must be a non-empty list")
    seen = set()
    for p in provs:
        if not isinstance(p, dict):
            raise ConfigError(f"provider must be an object: {p!r}")
        for k in ("name", "kind", "adapter"):
            if not isinstance(p.get(k), str) or not p[k]:
                raise ConfigError(f"provider missing '{k}': {p}")
        if p["kind"] not in KINDS:
            raise ConfigError(f"provider {p['name']}: kind must be one of {KINDS}")
        if p["name"] in seen:
            raise ConfigError(f"duplicate provider name {p['name']}")
        seen.add(p["name"])
        if "api_key" in p:
            raise ConfigError(f"provider {p['name']}: never store keys in config; use 'api_key_env'")
    for s in sets:
        if not isinstance(s, dict):
            raise ConfigError(f"testset must be an object: {s!r}")
        for k in ("name", "path", "task"):
            if not isinstance(s.get(k), str) or not s[k]:
                raise ConfigError(f"testset missing '{k}': {s}")
        if s["task"] not in TASKS:
            raise ConfigError(f"testset {s['name']}: task must be one of {TASKS}")
    for pair in cfg.get("language_pairs", []):
        if not (isinstance(pair, str) and pair.count("-") == 1):
            raise ConfigError(f"language_pairs entries look like 'en-am': {pair!r}")
    base = os.path.dirname(os.path.abspath(path))
    for s in sets:
        if not os.path.isabs(s["path"]):
            s["path"] = os.path.normpath(os.path.join(base, s["path"]))
    return cfg, hashlib.sha256(canon.encode("utf-8")).hexdigest()[:12]
