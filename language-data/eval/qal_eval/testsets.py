from __future__ import annotations

import json
import os

from .config import ConfigError, LANGS


def load_items(path: str, task: str) -> list[dict]:
    """Load items.jsonl from a test set directory (or a .jsonl file). Order preserved."""
    fp = os.path.join(path, "items.jsonl") if os.path.isdir(path) else path
    base = os.path.dirname(fp)
    items = []
    try:
        with open(fp, encoding="utf-8") as f:
            for ln, line in enumerate(f, 1):
                if not line.strip() or line.startswith("#"):
                    continue
                try:
                    it = json.loads(line)
                except ValueError:
                    raise ConfigError(f"{fp}:{ln}: invalid JSON") from None
                need = ["id", "source_lang", "reference"]
                need += ["audio"] if task == "asr" else ["target_lang", "source"]
                for k in need:
                    if k not in it:
                        raise ConfigError(f"{fp}:{ln}: missing '{k}'")
                for k in ("source_lang", "target_lang"):
                    if k in it and it[k] not in LANGS:
                        raise ConfigError(f"{fp}:{ln}: unsupported language {it[k]!r}")
                if task == "asr":
                    it["target_lang"] = it["source_lang"]
                    if not os.path.isabs(it["audio"]):
                        it["audio"] = os.path.join(base, it["audio"])
                items.append(it)
    except OSError as e:
        raise ConfigError(f"cannot read test set {fp}: {e}") from None
    return items
