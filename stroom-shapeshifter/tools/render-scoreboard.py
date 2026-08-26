#!/usr/bin/env python3
"""Render the README's scoreboard charts from checked-in benchmark results.

    tools/render-scoreboard.py <buffer-run.json> <per-match-run.json>

Writes SVGs to stroom-shapeshifter-regex/design/benchmarks/charts/, light and dark variants of each, so the
README can select on prefers-color-scheme. Regenerate whenever the cited runs change;
the charts are rendered, never drawn by hand — the same rule as the tables
(stroom-shapeshifter-regex/design/benchmarks/README.md).

Ratios are shapeshifter (auto-selected engines) over javaRegexFromBytes from the SAME
run — the honest comparison for a byte pipeline, per 05-engine-benchmarks.md §7.
"""

import json
import sys
from pathlib import Path

BUFFER_ORDER = ["CSV", "SYSLOG", "WEBLOG", "KEYVALUE", "DATETIME", "NETWORK", "QUOTED",
                "ALTERNATION", "TIER1_GREEDY", "TIER1_ALTERNATION", "FANCY_BACKREF",
                "FANCY_LOOKAHEAD", "FANCY_ATOMIC", "SPARSE", "LONG_RECORD", "UNICODE"]
PERMATCH_ORDER = ["csv", "syslog", "weblog", "keyvalue", "datetime", "network", "quoted",
                  "numbers", "identifiers", "structured", "fixedwidth", "stress", "fancy",
                  "everything"]

MODES = {
    "light": {"surface": "#fcfcfb", "ink": "#0b0b0b", "muted": "#52514e",
              "grid": "#f0efec", "bar": "#2a78d6"},
    "dark": {"surface": "#1a1a19", "ink": "#ffffff", "muted": "#c3c2b7",
             "grid": "#383835", "bar": "#3987e5"},
}


def load(path, which):
    rows = {}
    for r in json.load(open(path)):
        p = r.get("params", {})
        w = p.get("workload") or p.get("category")
        if w is None:
            continue
        bench = "buffer" if "CorpusBenchmark" in r["benchmark"] and "Pattern" not in r["benchmark"] \
            else "permatch"
        if bench != which:
            continue
        m = r["benchmark"].split(".")[-1]
        rows.setdefault(w, {})[m] = r["primaryMetric"]["score"]
    return rows


def render(title, subtitle, order, rows, mode, out):
    t = MODES[mode]
    ratios = []
    for w in order:
        e = rows[w]
        ratios.append((w, e["shapeshifter"] / e["javaRegexFromBytes"]))
    ratios.sort(key=lambda x: -x[1])

    left, right, top, pitch, bar_h = 170, 60, 80, 24, 14
    max_ratio = max(r for _, r in ratios)
    plot_w = 760 - left - right
    height = top + pitch * len(ratios) + 30
    scale = plot_w / (max_ratio * 1.06)

    s = []
    s.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="760" height="{height}" '
             f'viewBox="0 0 760 {height}" font-family="system-ui, sans-serif">')
    s.append(f'<rect width="760" height="{height}" fill="{t["surface"]}" rx="6"/>')
    s.append(f'<text x="16" y="26" font-size="15" font-weight="600" fill="{t["ink"]}">{title}</text>')
    s.append(f'<text x="16" y="44" font-size="11" fill="{t["muted"]}">{subtitle}</text>')

    # Gridlines at 1x steps, recessive.
    step = 1
    x = left
    v = 0
    while v <= max_ratio * 1.06:
        x = left + v * scale
        s.append(f'<line x1="{x:.1f}" y1="{top - 8}" x2="{x:.1f}" y2="{height - 26}" '
                 f'stroke="{t["grid"]}" stroke-width="1"/>')
        s.append(f'<text x="{x:.1f}" y="{height - 12}" font-size="10" fill="{t["muted"]}" '
                 f'text-anchor="middle">{v}&#215;</text>')
        v += step

    # The reference line: parity with the JDK.
    xr = left + 1.0 * scale
    s.append(f'<line x1="{xr:.1f}" y1="{top - 10}" x2="{xr:.1f}" y2="{height - 26}" '
             f'stroke="{t["muted"]}" stroke-width="1.5" stroke-dasharray="4 3"/>')
    s.append(f'<text x="{xr:.1f}" y="{top - 15}" font-size="10" fill="{t["muted"]}" '
             f'text-anchor="middle">java.util.regex</text>')

    for i, (w, r) in enumerate(ratios):
        y = top + i * pitch
        w_px = max(r * scale, 2)
        rx = min(4.0, w_px / 2)
        # Rounded on the data end only, square at the baseline.
        s.append(f'<path d="M {left} {y} h {w_px - rx:.1f} a {rx} {rx} 0 0 1 {rx} {rx} '
                 f'v {bar_h - 2 * rx} a {rx} {rx} 0 0 1 -{rx} {rx} h -{w_px - rx:.1f} Z" '
                 f'fill="{t["bar"]}"/>')
        s.append(f'<text x="{left - 8}" y="{y + bar_h - 3}" font-size="11" fill="{t["ink"]}" '
                 f'text-anchor="end">{w}</text>')
        s.append(f'<text x="{left + w_px + 6:.1f}" y="{y + bar_h - 3}" font-size="11" '
                 f'fill="{t["muted"]}">{r:.2f}&#215;</text>')

    s.append("</svg>")
    Path(out).write_text("\n".join(s), encoding="utf-8")
    print(f"wrote {out}")


def main():
    buffer_json, permatch_json = sys.argv[1], sys.argv[2]
    out_dir = Path("stroom-shapeshifter-regex/design/benchmarks/charts")
    out_dir.mkdir(parents=True, exist_ok=True)
    buffer_rows = load(buffer_json, "buffer")
    permatch_rows = load(permatch_json, "permatch")
    b_name = Path(buffer_json).stem
    p_name = Path(permatch_json).stem
    for mode in MODES:
        render("Buffer scanning — throughput vs java.util.regex",
               f"2,000-record buffers, all capture groups extracted &#183; run {b_name} &#183; higher is better",
               BUFFER_ORDER, buffer_rows, mode, out_dir / f"buffer-{mode}.svg")
        render("Per-match — throughput vs java.util.regex",
               f"the whole accepted corpus, short records &#183; run {p_name} &#183; higher is better",
               PERMATCH_ORDER, permatch_rows, mode, out_dir / f"permatch-{mode}.svg")


if __name__ == "__main__":
    main()
