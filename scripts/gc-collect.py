#!/usr/bin/env python3
"""Assemble one GC-experiment result file from the raw outputs of a gc-run.sh run.

Inputs: the k6 --summary-export JSON, one Prometheus instant-query response per metric (named
<metric>.json in --prom-dir, see experiments/gc/queries.json), the pod's stdout (GC log lines mixed
with the application's JSON logs) and a small meta JSON written by gc-run.sh. Output: one JSON file
that gc-frontier.py reads. Standard library only.
"""
import argparse
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path

# -Xlog:gc*:stdout:time,uptime,level,tags lines look like
#   [2026-10-06T10:00:01.123+0000][12.345s][info][gc] GC(3) Pause Young (Normal) ... 3.456ms
# G1 and Serial print one such line per pause; ZGC and Shenandoah print one per pause phase.
GC_LINE = re.compile(r"^\[(?P<time>[^\]]+)\]\[[^\]]*\]\[[^\]]*\]\[(?P<tags>[^\]]*)\]\s+GC\((?P<id>\d+)\)\s+(?P<text>.*?)\s*$")
PAUSE_MS = re.compile(r"^(?P<what>.*\bPause\b.*?)\s(?P<ms>\d+(?:\.\d+)?)ms$")
TIME_FORMAT = "%Y-%m-%dT%H:%M:%S.%f%z"


def percentile(values, q):
    """Nearest-rank percentile (q in 0..100) of a non-empty list."""
    ordered = sorted(values)
    rank = max(1, math.ceil(q / 100 * len(ordered)))
    return ordered[rank - 1]


def prom_value(path):
    """The single value of a Prometheus instant-query response, or None (missing, empty, NaN)."""
    try:
        body = json.loads(Path(path).read_text())
        result = body["data"]["result"]
        if not result:
            return None
        value = float(result[0]["value"][1])
    except (OSError, ValueError, KeyError, IndexError, TypeError):
        return None
    return None if math.isnan(value) or math.isinf(value) else value


def gc_pauses(log_text, start, end):
    """Pause durations (ms) logged between the epoch seconds start and end, and the collector's
    own name from the 'Using ...' line when present."""
    pauses = []
    seen = set()
    collector = None
    for line in log_text.splitlines():
        if not line.startswith("["):
            continue  # the application's JSON logs
        if collector is None and "Using " in line and "[gc" in line:
            collector = line.split("Using ", 1)[1].strip()
        m = GC_LINE.match(line)
        if not m:
            continue
        p = PAUSE_MS.match(m.group("text"))
        if not p:
            continue
        try:
            at = datetime.strptime(m.group("time"), TIME_FORMAT).timestamp()
        except ValueError:
            continue
        if not start <= at <= end:
            continue
        key = (m.group("id"), p.group("what"), p.group("ms"))
        if key in seen:  # the same pause can be logged under two tags
            continue
        seen.add(key)
        pauses.append(float(p.group("ms")))
    stats = {"collector": collector, "pause_count": len(pauses)}
    if pauses:
        stats.update(pause_total_ms=round(sum(pauses), 3), pause_max_ms=max(pauses),
                     pause_p99_ms=percentile(pauses, 99))
    return stats


def k6_stats(summary):
    metrics = summary.get("metrics", {})
    latency = metrics.get("grpc_req_duration{scenario:measure}") or {}
    dropped = (metrics.get("dropped_iterations{scenario:measure}") or {}).get("count", 0)
    checks = (metrics.get("checks") or {}).get("value")
    # k6 v2 summary-export: a threshold maps to true when it was crossed (failed).
    failed = [name for name, crossed in (latency.get("thresholds") or {}).items() if crossed]
    return {
        "p50_ms": latency.get("med"),
        "p95_ms": latency.get("p(95)"),
        "p99_ms": latency.get("p(99)"),
        "p999_ms": latency.get("p(99.9)"),
        "max_ms": latency.get("max"),
        "dropped_iterations": dropped,
        "checks_rate": checks,
        "failed_thresholds": failed,
        "has_measure_window": bool(latency),
    }


def derive(prom, window_s):
    d = {}
    requests = prom.get("server_requests")
    if requests:
        if prom.get("cpu_seconds") is not None:
            d["cpu_seconds_per_1k_requests"] = prom["cpu_seconds"] / requests * 1000
        if prom.get("server_errors") is not None:
            d["error_rate"] = prom["server_errors"] / requests
        d["achieved_rps"] = requests / window_s
    if prom.get("cpu_periods"):
        d["throttled_fraction"] = (prom.get("cpu_throttled_periods") or 0) / prom["cpu_periods"]
    if prom.get("working_set_max_bytes") is not None:
        d["working_set_max_mib"] = prom["working_set_max_bytes"] / 1048576
    return d


def build(variant, summary, prom_dir, gc_log_text, meta, start, end):
    window_s = end - start
    prom = {p.stem: prom_value(p) for p in sorted(Path(prom_dir).glob("*.json"))}
    k6 = k6_stats(summary)
    result = {
        "variant": variant,
        "window": {"start": start, "end": end, "seconds": window_s},
        "meta": meta,
        "k6": k6,
        "prometheus": prom,
        "derived": derive(prom, window_s),
        "gc_log": gc_pauses(gc_log_text, start, end),
    }
    # A run only counts toward a frontier if the measured window exists, nothing was dropped and
    # the k6 thresholds (including the p99 limit, when one was set) held.
    result["valid"] = k6["has_measure_window"] and not k6["dropped_iterations"]
    result["slo_pass"] = result["valid"] and not k6["failed_thresholds"]
    return result


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--variant", required=True)
    ap.add_argument("--k6-summary", required=True)
    ap.add_argument("--prom-dir", required=True)
    ap.add_argument("--gc-log", required=True)
    ap.add_argument("--meta", required=True)
    ap.add_argument("--window-start", type=float, required=True, help="epoch seconds")
    ap.add_argument("--window-end", type=float, required=True, help="epoch seconds")
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)
    result = build(
        args.variant,
        json.loads(Path(args.k6_summary).read_text()),
        args.prom_dir,
        Path(args.gc_log).read_text(errors="replace"),
        json.loads(Path(args.meta).read_text()),
        args.window_start,
        args.window_end,
    )
    Path(args.out).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(f"wrote {args.out} (valid={result['valid']}, slo_pass={result['slo_pass']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
