# stroom-shapeshifter-regex

A byte-level regular-expression and combinator matching library. No dependencies beyond the
JDK — enforced by `verifyZeroDependencies`, which runs with `check`.

```java
final BytePattern pattern = BytePattern.compile("^([^,]+),(\\d+)$");
final ByteMatcher matcher = pattern.matcher();          // one per thread, reusable
if (matcher.find(bytes)) {
    ByteSpan name = matcher.group(1);                   // offsets into bytes, not a copy
}
```

## Where it stands — 2026-09-03

Two statements, each with its bounds, because a claim without them is how a benchmark table
turns into folklore.

**Performance.** Against `java.util.regex` reading the same bytes in the same run, with the
engine chosen automatically, the library is **ahead on every per-match category** (14 of 14,
1.33–2.78×) and on **fourteen of the sixteen buffer workloads** (1.09–5.27×). The other two
are at parity: `KEYVALUE` at 0.99× inside its error bars, and `NETWORK` at 0.77× as recorded,
which measures at parity fork-per-side and is a documented JMH-harness artifact
([06 §1](design/06-performance-plan.md)). The one shape it was behind on — a lazy run spelt
as a nested line loop over large regions, `LazyRunBenchmark` `FAR_LINE` / `MISS_LINE` at
64 KiB, where the JDK was 8–42× ahead — closed on 2026-09-03 (`RunLoop`, 06 §1): those rows
now read 1.26× and 6.9× ahead, and the natural dot-all spelling of the same job 2.5–3.7×. These are the benchmark corpus's numbers ([04](design/04-corpus-analysis.md) says what
it contains); real DS3 configurations are the evidence that would revise them. Run of record:
[`2026-09-04-0123-b81229148b-chain-phase1-polluted.json`](design/benchmarks/2026-09-04-0123-b81229148b-chain-phase1-polluted.json) — the last point of the
2026-09-03 overnight chain (pre-plan → plan and fixes → `RunLoop` → the pollution harness),
read in 07 Phase 0.

**Bytes.** Once compiled, matching is bytes all the way down. A pattern is authored in code
points and lowered **once, at compile time**, through its declared encoding — UTF-8, any
single-byte table, or RAW ([`Encoding`](src/main/java/stroom/shapeshifter/regex/Encoding.java)) —
into byte automata; every engine then matches `byte[]`: spans are byte offsets, classes are
byte-range walks, literals are byte sequences, and the scan plan's fast paths are byte
operations. Exactly **two constructs read characters back out of the bytes at match time**,
by design and named in `ByteForm`'s contract: backreference comparison and Unicode word
boundaries. Semantics are strict over well-formed characters of the declared encoding
([D38](../design/00-decisions.md)): undecodable bytes are matchable by nothing spelt in code
points, and the scan plan's byte ops are licensed to diverge from that only off-contract —
validity is composition's job, upstream. Encodings the library does not lower (the UTF-16
family) are transcoded to UTF-8 bytes before matching by the engine's `transcode` stage
([19](../design/19-encoding-plan.md)).

## Reading further

- [design/](design/README.md) — the language, the engines, and every measurement that shaped
  them; [06-performance-plan.md](design/06-performance-plan.md) is the first thing to read
  before touching a hot path.
- [ISSUES.md](ISSUES.md) — the open items and the accepted costs, each with its measurement.
- [design/benchmarks/](design/benchmarks/README.md) — every JMH run, checked in, named by date
  and commit; note the hardware break of 2026-09-02.

```
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='.*PatternCorpus.*'
```
