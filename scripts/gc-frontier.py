#!/usr/bin/env python3
"""Compute and draw the Pareto frontier of the GC-experiment results written by gc-collect.py.

Every axis is minimised (p99 latency, CPU per 1k requests, memory, pause time...). A variant is
on the frontier if no other variant is at least as good on every axis and strictly better on one.
Each variant's point is the median over its valid runs; the spread (min-max) is reported beside it.
Standard library only; the chart is a plain SVG scatter of the first two axes.

  scripts/gc-frontier.py experiments/gc/results \
      --axis k6.p99_ms --axis derived.cpu_seconds_per_1k_requests --svg frontier.svg
"""
import argparse
import json
import statistics
import sys
from html import escape
from pathlib import Path

DEFAULT_AXES = ["k6.p99_ms", "derived.cpu_seconds_per_1k_requests"]


def lookup(result, dotted):
    node = result
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node if isinstance(node, (int, float)) else None


def load_results(directory):
    results = []
    for path in sorted(Path(directory).glob("*.json")):
        try:
            data = json.loads(path.read_text())
        except ValueError:
            continue
        if isinstance(data, dict) and "variant" in data and "k6" in data:
            data["_file"] = path.name
            results.append(data)
    return results


def summarise(results, axes):
    """One point per variant: the median of each axis over the variant's valid runs."""
    runs = {}
    for r in results:
        if r.get("valid") and all(lookup(r, a) is not None for a in axes):
            runs.setdefault(r["variant"], []).append(r)
    points = []
    for variant, rs in sorted(runs.items()):
        values = [[lookup(r, a) for r in rs] for a in axes]
        passes = sum(1 for r in rs if r.get("slo_pass"))
        points.append({
            "variant": variant,
            "runs": len(rs),
            "values": [statistics.median(v) for v in values],
            "spread": [(min(v), max(v)) for v in values],
            "slo_pass": passes * 2 > len(rs),  # a strict majority of the runs
        })
    return points


def dominates(a, b):
    """a dominates b: at least as good everywhere (lower is better) and strictly better somewhere."""
    return all(x <= y for x, y in zip(a, b)) and any(x < y for x, y in zip(a, b))


def frontier(points):
    return [p for p in points if not any(dominates(q["values"], p["values"]) for q in points if q is not p)]


def table(points, on_frontier, axes):
    names = {id(p) for p in on_frontier}
    header = ["variant", "runs", *axes, "SLO", "frontier"]
    rows = [header]
    for p in sorted(points, key=lambda p: p["values"]):
        cells = [f"{v:.3f} ({lo:.3f}-{hi:.3f})" for v, (lo, hi) in zip(p["values"], p["spread"])]
        rows.append([p["variant"], str(p["runs"]), *cells, "pass" if p["slo_pass"] else "FAIL",
                     "yes" if id(p) in names else "-"])
    widths = [max(len(r[i]) for r in rows) for i in range(len(header))]
    return "\n".join("  ".join(c.ljust(w) for c, w in zip(r, widths)).rstrip() for r in rows)


def svg(points, on_frontier, axes, width=720, height=480, pad=70):
    xs = [p["values"][0] for p in points]
    ys = [p["values"][1] for p in points]

    def scale(v, lo, hi, size, flip=False):
        t = 0.5 if hi == lo else (v - lo) / (hi - lo)
        t = 0.05 + 0.9 * t
        return size * (1 - t) if flip else size * t

    pw, ph = width - 2 * pad, height - 2 * pad
    front = {id(p) for p in on_frontier}
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" font-family="sans-serif" font-size="12">',
           f'<rect width="{width}" height="{height}" fill="#fff"/>',
           f'<rect x="{pad}" y="{pad}" width="{pw}" height="{ph}" fill="none" stroke="#999"/>',
           f'<text x="{width / 2}" y="{height - 20}" text-anchor="middle">{escape(axes[0])} (lower is better)</text>',
           f'<text x="18" y="{height / 2}" text-anchor="middle" transform="rotate(-90 18 {height / 2})">{escape(axes[1])} (lower is better)</text>']

    def xy(p):
        return (pad + scale(p["values"][0], min(xs), max(xs), pw), pad + scale(p["values"][1], min(ys), max(ys), ph, True))

    line = sorted((xy(p) for p in on_frontier))
    if len(line) > 1:
        out.append('<polyline fill="none" stroke="#0F7B6C" stroke-width="2" stroke-dasharray="4 3" points="'
                   + " ".join(f"{x:.1f},{y:.1f}" for x, y in line) + '"/>')
    for p in points:
        x, y = xy(p)
        colour = "#0F7B6C" if id(p) in front else "#999"
        mark = "" if p["slo_pass"] else ' stroke="#c0392b" stroke-width="2"'
        out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="6" fill="{colour}"{mark}/>')
        out.append(f'<text x="{x + 9:.1f}" y="{y - 8:.1f}">{escape(p["variant"])}</text>')
    out.append(f'<text x="{pad}" y="{pad - 12}" fill="#555">green = on the frontier, red ring = misses the SLO</text>')
    out.append("</svg>")
    return "\n".join(out) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results_dir")
    ap.add_argument("--axis", action="append", help="dotted path into a result file (repeat; default: p99 and CPU per 1k)")
    ap.add_argument("--svg", help="write an SVG scatter of the first two axes here")
    args = ap.parse_args(argv)
    axes = args.axis or DEFAULT_AXES
    if len(axes) < 2:
        ap.error("need at least two axes")
    results = load_results(args.results_dir)
    points = summarise(results, axes)
    if not points:
        print(f"no valid results with all of {axes} in {args.results_dir}", file=sys.stderr)
        return 1
    on_frontier = frontier(points)
    print(table(points, on_frontier, axes))
    if args.svg:
        Path(args.svg).write_text(svg(points, on_frontier, axes))
        print(f"wrote {args.svg}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
