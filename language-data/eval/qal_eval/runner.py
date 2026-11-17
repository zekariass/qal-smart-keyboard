from __future__ import annotations

import csv
import datetime
import json
import time
from collections import OrderedDict

from . import adapters, metrics
from .adapters.base import AdapterError
from .testsets import load_items

CSV_FIELDS = ["provider", "kind", "model", "testset", "source_lang", "target_lang", "n", "n_ok",
              "chrF", "chrF++", "WER", "CER", "script_ok_rate", "latency_p50_ms", "latency_p95_ms",
              "errors", "timeouts", "run_timestamp", "config_hash"]


def _fmt(x):
    return "" if x is None else (f"{x:.4f}" if isinstance(x, float) else x)


def _mean(xs):
    return sum(xs) / len(xs) if xs else None


def run(cfg: dict, cfg_hash: str, out_csv: str, items_jsonl: str | None = None,
        timestamp: str | None = None, log=print) -> list[dict]:
    ts = timestamp or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    pairs = set(cfg.get("language_pairs") or [])
    # build adapters and load test sets first so config errors surface before any request
    provs = [adapters.create(spec) for spec in cfg["providers"]]
    sets = [(s, load_items(s["path"], s["task"])) for s in cfg["testsets"]]
    rows, detail = [], []
    for ad in provs:  # config order = deterministic
        for s, items in sets:
            if s["task"] != ("asr" if ad.kind == "asr" else "translation"):
                continue
            groups = OrderedDict()
            for it in items:
                if pairs and f"{it['source_lang']}-{it['target_lang']}" not in pairs:
                    continue
                groups.setdefault((it["source_lang"], it["target_lang"]), []).append(it)
            for (sl, tl), group in sorted(groups.items()):
                group.sort(key=lambda i: str(i["id"]))
                log(f"[{ad.name}] {s['name']} {sl}->{tl} ({len(group)} items)")
                lat, chrfs, chrfpp, wers, cers, scripts = [], [], [], [], [], []
                errors = timeouts = 0
                for it in group:
                    t0 = time.perf_counter()
                    out, err = None, None
                    try:
                        if ad.kind == "asr":
                            out = ad.transcribe(it["audio"], sl)
                        else:
                            out = ad.translate(it["source"], sl, tl)
                    except AdapterError as e:
                        err = e
                    except Exception as e:  # an adapter bug must not abort the run
                        err = AdapterError(f"{type(e).__name__}: {e}")
                    ms = (time.perf_counter() - t0) * 1000
                    base = {"provider": ad.name, "testset": s["name"], "id": it["id"],
                            "source_lang": sl, "target_lang": tl, "latency_ms": round(ms, 1)}
                    if err is not None:
                        errors += 1
                        timeouts += 1 if err.timeout else 0
                        detail.append({**base, "error": str(err)})
                        continue
                    lat.append(ms)
                    scripts.append(metrics.script_ok_rate(out, tl))
                    if ad.kind == "asr":
                        wers.append(metrics.wer(it["reference"], out))
                        cers.append(metrics.cer(it["reference"], out))
                    else:
                        chrfs.append(metrics.chrf(it["reference"], out))
                        chrfpp.append(metrics.chrf(it["reference"], out, word_order=2))
                    detail.append({**base, "output": out, "reference": it["reference"]})
                rows.append({
                    "provider": ad.name, "kind": ad.kind, "model": ad.last_model or ad.model,
                    "testset": s["name"], "source_lang": sl, "target_lang": tl,
                    "n": len(group), "n_ok": len(lat),
                    "chrF": _mean(chrfs), "chrF++": _mean(chrfpp), "WER": _mean(wers), "CER": _mean(cers),
                    "script_ok_rate": _mean(scripts),
                    "latency_p50_ms": metrics.percentile(lat, 50) if lat else None,
                    "latency_p95_ms": metrics.percentile(lat, 95) if lat else None,
                    "errors": errors, "timeouts": timeouts,
                    "run_timestamp": ts, "config_hash": cfg_hash})
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=CSV_FIELDS, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({k: _fmt(r.get(k)) for k in CSV_FIELDS})
    if items_jsonl:
        with open(items_jsonl, "w", encoding="utf-8") as f:
            for d in detail:
                f.write(json.dumps(d, ensure_ascii=False) + "\n")
    return rows
