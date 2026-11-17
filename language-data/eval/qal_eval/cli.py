from __future__ import annotations

import argparse
import sys

from . import runner
from .config import ConfigError, load


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="qal_eval")
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run", help="run providers over test sets and write a CSV")
    r.add_argument("--config", required=True)
    r.add_argument("--out", required=True, help="results CSV path")
    r.add_argument("--items-out", help="optional per-item JSONL (debug; synthetic data only)")
    r.add_argument("--timestamp", help="override run timestamp (reproducible output)")
    a = ap.parse_args(argv)
    try:
        cfg, h = load(a.config)
        rows = runner.run(cfg, h, a.out, a.items_out, a.timestamp)
    except (ConfigError, ValueError) as e:
        print(f"config error: {e}", file=sys.stderr)
        return 2
    print(f"wrote {len(rows)} rows to {a.out}")
    return 0
