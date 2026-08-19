# Benchmark results

One JSON file per JMH run, written automatically by the `jmh` Gradle task and named
`<date>-<time>-<commit>.json`.

They are checked in on purpose. A throughput figure that exists only in a terminal scrollback
cannot be compared with the next one, and comparing runs is the entire point:
[D21](../00-decisions.md) records a case where a single-fork harness moved 25% between runs of the
same binary, which was only visible because the earlier numbers could be re-read. Keeping the raw
files also means the tables in [05-engine-benchmarks.md](../05-engine-benchmarks.md) are rendered
rather than transcribed, so a number in the prose can always be traced back to the run that
produced it.

```
# run everything, recording a new file here
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh

# one class only
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='.*PatternCorpus.*'

# render one run, or compare two
../tools/render-benchmark.py design/benchmarks/<file>.json
../tools/render-benchmark.py design/benchmarks/<before>.json design/benchmarks/<after>.json
```

The comparison form marks any difference whose error bars overlap as **indistinguishable**, which
is the check that would have caught the false 7% result described in
[05-engine-benchmarks.md §2.0](../05-engine-benchmarks.md).

## What each benchmark measures

| Class | Shape | Answers |
|---|---|---|
| `CorpusBenchmark` | 10 hand-picked patterns, 2,000-record buffers | Sustained scanning on realistic records |
| `PatternCorpusBenchmark` | All 106 accepted corpus patterns, short inputs | Per-match overhead across the breadth the correctness suite covers, at the tier mix a real config would have |
| `MatchingBaselineBenchmark` | Hand-written scanners vs `java.util.regex` | The pre-engine baseline from [03-baseline-results.md](../03-baseline-results.md) |
| `BranchOrderBenchmark` | Alternation branch ordering | Whether branch order is worth optimising |

Runs are machine-specific. Compare files from the same machine, or not at all.

## Comparability breaks

Changes that alter what a benchmark measures, so scores from either side of them are not
comparable even on the same machine:

- **D27 (the fancy tier)** added the three `FANCY_*` workloads to `CorpusBenchmark`; runs
  recorded before it have no rows for them. The corpus's newly-accepted fancy patterns all
  sat in the `unsupported` category, which `PatternCorpusBenchmark` did not measure, so the
  measured categories were unchanged — first believed otherwise, corrected after checking the
  per-category buckets in `CorpusDifferentialTest`'s report. That category has since been
  renamed `fancy` and added to the benchmark, so files recorded after that commit carry a
  thirteenth `PatternCorpusBenchmark` workload that every earlier file — including
  `2026-08-19-1028` — lacks.
- **The 2026-08-19 audit** added the `shapeshifterTree` methods (`Engine.TREE`, forced) to
  both corpus benchmarks, and the `SPARSE`, `LONG_RECORD` and `UNICODE` workloads to
  `CorpusBenchmark` — closing the dense-match/short-record/ASCII-only blind spot recorded in
  `06-performance-plan.md` §4. Earlier files lack all of those rows.
- **D31 redrew the default engine selection** (2026-08-19): the `shapeshifter` methods'
  scores on fancy and TIER1 workloads are not comparable across that commit — the default
  path changed engines, which was the point. A 14th per-match workload, `everything`, runs
  the whole corpus in one JVM.
- **The UNICODE workload was redefined on 2026-08-19** (ambiguous first spelling measured
  the simulation, not the scan plan — 05 §10.5): its rows in `2026-08-19-1601` and earlier
  are not comparable with `2026-08-19-1735` onward.
- **2026-08-19-1028** ran on a non-idle machine: `javaRegexFromBytes` — code no commit
  touched — moved −5.0% against the previous run while `javaRegex` was flat. Cross-run
  comparisons against it measure the machine, not the engine; within-run ratios stand.
