#!/usr/bin/env python3
"""Benchmark regression gate for keyboard-core (plan step C1.10). Standard library only.

Runs the std-only benches (`cargo bench --bench lexicon|ranker`) N times, parses their
`label  n=.. p50=.. ms  p95=.. ms` lines, takes the MINIMUM across runs of each label's p50, and
compares it with `core/benches/baseline.json`. A label fails when

    current > baseline * (1 + tolerance)   AND   current - baseline > noise_floor_ms

(defaults: 10 % and 0.020 ms). A bench that exits non-zero in EVERY run (absolute SD 17.1 budget exceeded),
a label missing from the output, or a label missing from the baseline also fails the gate.

Why min-of-p50s: the benches' p95/p99 are dominated by scheduler and cache noise on shared
CI runners; the median latency of thousands of queries (p50) is far steadier, and taking the
minimum over N runs discards runs disturbed by other load (noise only ever adds time; observed
here: median-of-3 on a busy dev box swung a label by +38 %, which is why min is used). Limits (be honest): it detects broad slowdowns (a hot path getting >10 %
slower), not tail regressions (p95 is still guarded by the benches' absolute budgets); labels
whose p50 is within the noise floor of the baseline can never fail on the relative rule; the
baseline is machine specific.

Usage (from anywhere):
    python core/scripts/bench_gate.py                 # check against the baseline
    python core/scripts/bench_gate.py --update        # re-record the baseline (deliberate!)
    python core/scripts/bench_gate.py --runs 5 --tolerance 0.10 --noise-floor-ms 0.02
    python core/scripts/bench_gate.py --baseline-scale 0.8   # self-test: pretend baseline is 20 % faster
"""

import argparse
import datetime
import json
import os
import platform
import re
import subprocess
import sys

BENCHES = ("lexicon", "ranker")
LINE = re.compile(r"^(?P<label>.+?)\s+n=(?P<n>\d+)\s+p50=(?P<p50>[0-9.]+) ms\s+p95=(?P<p95>[0-9.]+) ms")
HERE = os.path.dirname(os.path.abspath(__file__))
CORE = os.path.dirname(HERE)
BASELINE = os.path.join(CORE, "benches", "baseline.json")


def parse_output(bench, text):
    """Returns {"bench/label": p50_ms} for every stats line of one bench run."""
    out = {}
    for raw in text.splitlines():
        m = LINE.match(raw.strip())
        if m:
            out[f"{bench}/{m.group('label').strip()}"] = float(m.group("p50"))
    return out


def run_bench(bench):
    """Runs one bench; returns (parsed p50s, exit code). Output is echoed for the CI log."""
    proc = subprocess.run(
        ["cargo", "bench", "--bench", bench],
        cwd=CORE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    sys.stdout.write(proc.stdout)
    return parse_output(bench, proc.stdout), proc.returncode


def collect(runs):
    """{label: [p50 per run]} and the list of benches that failed their own budget."""
    samples = {}
    fails = {}
    for i in range(runs):
        print(f"--- bench run {i + 1}/{runs} ---", flush=True)
        for bench in BENCHES:
            p50s, code = run_bench(bench)
            if code != 0:
                fails[bench] = fails.get(bench, 0) + 1
            for label, v in p50s.items():
                samples.setdefault(label, []).append(v)
    # Same philosophy as min-of-runs: a bench only counts as over budget when EVERY run was
    # (a single run disturbed by other load must not fail the gate).
    return samples, sorted(b for b, n in fails.items() if n == runs)


def best_of(samples):
    return {k: min(v) for k, v in samples.items()}


def compare(current, baseline, tolerance, noise_floor_ms):
    """Returns (rows, failures). Pure function (unit tested)."""
    rows, failures = [], []
    for label, base in sorted(baseline.items()):
        if label not in current:
            failures.append(f"{label}: missing from bench output")
            continue
        cur = current[label]
        limit = base * (1 + tolerance)
        regressed = cur > limit and (cur - base) > noise_floor_ms
        ratio = cur / base if base > 0 else float("inf")
        rows.append((label, base, cur, ratio, "FAIL" if regressed else "ok"))
        if regressed:
            failures.append(
                f"{label}: p50 {cur:.4f} ms vs baseline {base:.4f} ms "
                f"({(ratio - 1) * 100:+.1f} %, limit +{tolerance * 100:.0f} % and > {noise_floor_ms} ms)"
            )
    for label in sorted(set(current) - set(baseline)):
        failures.append(f"{label}: not in baseline (run with --update and commit it)")
    return rows, failures


def write_baseline(path, current, runs):
    doc = {
        "format": "qal-bench-baseline-v1",
        "statistic": "minimum over runs of per-label p50 (ms)",
        "recorded": datetime.date.today().isoformat(),
        "runs": runs,
        "machine": f"{platform.system()} {platform.machine()} ({platform.processor() or 'unknown cpu'})",
        "note": "Machine specific. Re-record on the CI runner class (see core/README.md, Benchmark gate).",
        "p50_ms": {k: round(v, 6) for k, v in sorted(current.items())},
    }
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", type=int, default=5, help="repetitions of each bench (default 5)")
    ap.add_argument("--tolerance", type=float, default=0.10, help="allowed relative slowdown (default 0.10)")
    ap.add_argument("--noise-floor-ms", type=float, default=0.020, help="ignore absolute differences below this (default 0.020)")
    ap.add_argument("--baseline", default=BASELINE)
    ap.add_argument("--update", action="store_true", help="re-record the baseline instead of checking")
    ap.add_argument("--baseline-scale", type=float, default=1.0, help="multiply baseline values (self-test of the gate)")
    args = ap.parse_args(argv)

    samples, failed = collect(args.runs)
    current = best_of(samples)
    if not current:
        print("bench_gate: no benchmark lines parsed", file=sys.stderr)
        return 2
    if args.update:
        if failed:
            print(f"bench_gate: refusing to record a baseline, bench(es) over budget: {failed}", file=sys.stderr)
            return 1
        write_baseline(args.baseline, current, args.runs)
        print(f"baseline written: {args.baseline} ({len(current)} labels)")
        return 0

    with open(args.baseline, encoding="utf-8") as f:
        baseline = {k: v * args.baseline_scale for k, v in json.load(f)["p50_ms"].items()}
    rows, failures = compare(current, baseline, args.tolerance, args.noise_floor_ms)
    print("\n{:<52} {:>10} {:>10} {:>7}  {}".format("label", "baseline", "current", "ratio", ""))
    for label, base, cur, ratio, status in rows:
        print(f"{label:<52} {base:>10.4f} {cur:>10.4f} {ratio:>7.2f}  {status}")
    for b in failed:
        failures.append(f"bench '{b}' exceeded its absolute budget (non-zero exit)")
    if failures:
        print("\nBENCH GATE FAILED:", file=sys.stderr)
        for f_ in failures:
            print("  - " + f_, file=sys.stderr)
        return 1
    print("\nbench gate passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
