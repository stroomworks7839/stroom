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
