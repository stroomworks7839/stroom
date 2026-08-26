# Benchmark results — the engine and the XML head-to-head

One JSON file per JMH run, written automatically by the `jmh` Gradle task of
`stroom-shapeshifter-engine` (suffix `-engine`) or `stroom-shapeshifter-xmlbench` (suffix
`-xml`), and named `<date>-<time>-<commit>-<suffix>.json`.

They are checked in on purpose. A throughput figure that exists only in a terminal scrollback
cannot be compared with the next one, and comparing runs is the entire point:
[D21](../00-decisions.md) records a case where a single-fork harness moved 25% between runs of
the same binary, which was only visible because the earlier numbers could be re-read.

```
# the engine suite, or the XML head-to-head
./gradlew :stroom-shapeshifter:stroom-shapeshifter-engine:jmh
./gradlew :stroom-shapeshifter:stroom-shapeshifter-xmlbench:jmh

# render one run, or compare two
../../tools/render-benchmark.py design/benchmarks/<file>.json
../../tools/render-benchmark.py design/benchmarks/<before>.json design/benchmarks/<after>.json
```

The comparison form marks any difference whose error bars overlap as **indistinguishable**.

## What each benchmark measures

| Class | Shape | Answers |
|---|---|---|
| `EngineBenchmark` | Whole fixture configurations over their own inputs, repeated to a fixed volume (D22), plus `compile` | Throughput on real configurations, with pairs chosen to isolate questions — `win_sec`/`win_sec_xml` A/B dispatch cost, `apache_httpd` the heaviest bodies, `progressive` the step interpreter alone; the *before* for [10-engine-compilation.md](../10-engine-compilation.md)'s optimisation work |
| `XmlBaselineBenchmark` | The events workload at 10k/100k/1M records | The XML head-to-head's decomposition: SAX floor, Saxon identity, Saxon proper, shapeshifter ([13](../13-xml-xslt-benchmark.md)) |
| `CaseCatalogueBenchmark` | Seven catalogue cases amplified to ~10k/~100k units | Per-capability-family A/B, Saxon vs shapeshifter, parity-licensed by `CaseAmplifierTest` |

Runs are machine-specific. Compare files from the same machine, or not at all.

## Comparability breaks

- **2026-08-21-1555 ran on a non-idle machine**: `saxParse` — code no commit touched —
  sat −55% against both its neighbours (`2026-08-21-1543` before, `2026-08-21-1753` after,
  which agree with each other within ~2% on every untouched row), with error bars to match
  (±204 on a 484 ms score; identity at 1M ±5094 on 8225). Cross-run comparisons against it
  measure the machine, not the engine; within-run ratios stand.

The regex library's runs — the corpus, baseline, anchored and end-anchored suites, and the
scoreboard charts — live with their module, in
[stroom-shapeshifter-regex/design/benchmarks/](../../stroom-shapeshifter-regex/design/benchmarks/README.md).
