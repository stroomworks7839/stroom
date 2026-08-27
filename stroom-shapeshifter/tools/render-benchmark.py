#!/usr/bin/env python3
"""Render a JMH JSON result file as the markdown table used in
stroom-shapeshifter-regex/design/05-engine-benchmarks.md.

    tools/render-benchmark.py stroom-shapeshifter-regex/design/benchmarks/2026-08-18-1030-abc1234.json

Given two files it renders a comparison instead, which is the form to use when arguing that a
change helped:

    tools/render-benchmark.py before.json after.json

Ratios carry an interval derived from both sides' error bars — the worst and best pairings — so
that a difference smaller than the measurement cannot be quoted as if it were a result. That is
the whole reason this script exists rather than a person copying numbers out of a terminal.

Which direction counts as better is read from the benchmark's JMH mode, never assumed: a
throughput score in ops/s improves by rising, a sample- or single-shot time in ms/op improves
by falling. Assuming the first was a real defect here — every "better" this script printed for
CaseCatalogueBenchmark and XmlBaselineBenchmark, which are ms/op, meant its opposite.
"""

import json
import sys
from collections import OrderedDict, namedtuple

#: One measurement: the score, its error bar, and the JMH mode that says which way is up.
Point = namedtuple("Point", "score error mode")

#: The JMH modes where a bigger number is a better one. Everything else — avgt, sample, ss —
#: measures time per operation, where smaller is better.
HIGHER_IS_BETTER = {"thrpt"}


def load(path):
    """{(class, label, method): (score, error)}, the label joining every @Param value.

    Every value, not the first: a benchmark declaring two params — AnchoredSearchBenchmark's
    engine and shape — keyed eleven of its twelve rows onto labels they shared, so each
    engine's four shapes overwrote one another and the table printed whichever shape JMH
    happened to write last, with nothing to say the rest had gone. The failure-path benchmark
    the performance plan leans on was the one being under-reported.
    """
    out = OrderedDict()
    for entry in json.load(open(path)):
        method = entry["benchmark"].rsplit(".", 1)[-1]
        klass = entry["benchmark"].rsplit(".", 2)[-2]
        params = entry.get("params") or {}
        label = "/".join(str(value) for value in params.values())
        metric = entry["primaryMetric"]
        error = metric.get("scoreError")
        # JMH emits the string "NaN" when there is only one fork — there is nothing to take a
        # confidence interval over. Treated as zero width, which is honest: with one fork the
        # error is unknown, not small. See D21.
        if not isinstance(error, (int, float)) or error != error:
            error = 0.0
        out[(klass, label, method)] = Point(metric["score"], error, entry.get("mode", "thrpt"))
    return out


def interval(a, b):
    """The ratio a/b as (low, high), taking both error bars into account."""
    a_score, a_err = a.score, a.error
    b_score, b_err = b.score, b.error
    low = (a_score - a_err) / (b_score + b_err) if b_score + b_err else float("nan")
    high = (a_score + a_err) / (b_score - b_err) if b_score - b_err else float("nan")
    return low, high


def render_single(path):
    data = load(path)
    labels = OrderedDict.fromkeys(k[1] for k in data)
    classes = OrderedDict.fromkeys(k[0] for k in data)
    for klass in classes:
        rows = [k for k in data if k[0] == klass]
        methods = OrderedDict.fromkeys(k[2] for k in rows)
        reference = "javaRegexFromBytes" if "javaRegexFromBytes" in methods else None
        print(f"\n### {klass}\n")
        header = "| Workload | " + " | ".join(methods) + (" | Ratio |" if reference else " |")
        print(header)
        print("|---" * (len(methods) + 1 + (1 if reference else 0)) + "|")
        for label in labels:
            if (klass, label, next(iter(methods))) not in data:
                continue
            cells = []
            for method in methods:
                point = data[(klass, label, method)]
                cells.append(f"{point.score:.0f} ± {point.error:.0f}")
            line = f"| {label} | " + " | ".join(cells)
            if reference:
                ours = data.get((klass, label, "shapeshifter"))
                base = data[(klass, label, reference)]
                low, high = interval(ours, base)
                line += f" | **{ours.score / base.score:.2f}×** ({low:.2f}–{high:.2f})"
            print(line + " |")


def render_comparison(before_path, after_path):
    before, after = load(before_path), load(after_path)
    print("| Benchmark | Workload | Before | After | Change | Verdict |")
    print("|---|---|---|---|---|---|")
    for key in after:
        if key not in before:
            continue
        klass, label, method = key
        b, a = before[key], after[key]
        change = (a.score / b.score - 1) * 100
        # Only a change whose intervals do not overlap is a result rather than an impression.
        separated = (a.score - a.error) > (b.score + b.error) \
            or (a.score + a.error) < (b.score - b.error)
        # Up is better for throughput and worse for a time per operation, so the sign alone
        # does not say which happened: the mode does.
        rose = change > 0
        improved = rose if a.mode in HIGHER_IS_BETTER else not rose
        verdict = ("better" if improved else "worse") if separated else "indistinguishable"
        print(f"| {method} | {label or klass} | {b.score:.0f} ± {b.error:.0f} "
              f"| {a.score:.0f} ± {a.error:.0f} | {change:+.1f}% | {verdict} |")
    # A row measured on only one side cannot be compared, but dropping it in silence is how a
    # whole benchmark class — one the change under test introduced, which is exactly when it
    # matters — disappears from the table without anyone noticing it was ever there.
    for side, keys in (("after", [k for k in after if k not in before]),
                       ("before", [k for k in before if k not in after])):
        if keys:
            counts = OrderedDict()
            for klass, _, _ in keys:
                counts[klass] = counts.get(klass, 0) + 1
            listed = ", ".join(f"{klass} ({n})" for klass, n in counts.items())
            print(f"\n{len(keys)} row(s) present only in the {side} file, not compared: {listed}")


if __name__ == "__main__":
    if len(sys.argv) == 2:
        render_single(sys.argv[1])
    elif len(sys.argv) == 3:
        render_comparison(sys.argv[1], sys.argv[2])
    else:
        sys.exit(__doc__)
