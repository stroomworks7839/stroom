# stroom-shapeshifter

A byte-level parsing and transformation engine.

## Modules

| Module | Contents |
|---|---|
| `stroom-shapeshifter-regex` | Byte-level regex and combinator matching engine |

## Status

A first working slice of the matching engine exists: patterns compile to a byte-level scan
plan and match against `byte[]` with zero-copy capture groups.

```java
final BytePattern pattern = BytePattern.compile("^([^,]+),(\\d+)$");
final ByteMatcher matcher = pattern.matcher();          // one per thread, reusable
if (matcher.find(bytes)) {
    ByteSpan name = matcher.group(1);                   // offsets, not a copy
}
```

Supported now: the whole RE2 subset over UTF-8 input, complete or streaming, on three engines
chosen automatically. An unambiguous pattern becomes a straight-line scan plan with no automaton;
an ambiguous one becomes an NFA, run by a bounded backtracker where its memory budget allows and
simulated by a Pike VM otherwise. Both automaton engines have the same linear-time bound, so
catastrophic backtracking is unrepresentable rather than merely unlikely. Results are identical
whichever engine runs — `explain()` reports the engine, and `ambiguities()` says why a pattern
needed an automaton, which is often an authoring mistake worth seeing.

Character classes are compiled through UTF-8, so `.` and `[a-zé]` match whole characters and a
span never splits one, while ASCII-only classes and unbounded scans keep a byte-level fast path.
A match never begins inside a character, not even an empty one.

The dialect follows Rust's `regex` rather than Perl's inheritance: `$` is the end of the input,
`\w \d \s \b` are Unicode unless `(?-u)` says otherwise, `\p{...}` means Unicode properties
and the ASCII POSIX classes are spelt `[[:alpha:]]`. Rust's own warts are declined, and both the
choices and the refusals are listed in [design/01-regex-language.md](design/01-regex-language.md)
§2.4. Rust's test corpus runs as part of the suite.

Also built: streaming (`StreamMatcher`, and a three-way `MATCH`/`NO_MATCH`/`NEED_MORE_INPUT`
outcome so a partial window is never mistaken for a decided one) and the combinator layer
(`comb.Matchers`), which flattens to the same plans as the equivalent regex.

Not yet built: encodings other than UTF-8 and the `transcode` stage, and the `java.util.regex`
dialect. A pattern outside those bounds is rejected with the reason — never matched
approximately.

## Design

The design drafts, the decisions and the measurements:

- [00-decisions.md](design/00-decisions.md) — decision log, with consequences.
- [01-regex-language.md](design/01-regex-language.md) — the matching language: RE2-style
  regex dialect, encoding-aware character classes, atoms and nom-style combinators.
- [02-engine-design.md](design/02-engine-design.md) — engine architecture: the encoding
  compiler, the one-pass scan plan and Pike VM execution tiers, streaming, and the test
  strategy.
- [03-baseline-results.md](design/03-baseline-results.md) — JMH measurements taken before any
  engine code, confirming one design assumption and refuting two.
- [04-corpus-analysis.md](design/04-corpus-analysis.md) — which execution tier real DS3
  patterns would land on, and why.
- [05-engine-benchmarks.md](design/05-engine-benchmarks.md) — the engine measured against
  `java.util.regex` on realistic patterns, what the pattern corpora cover, and every
  divergence found so far.

Prior art for both: the Rust `ds-rs`/shapeshifter prototype, and Stroom's existing DS3
implementation at `stroom-pipeline/src/main/java/stroom/pipeline/xml/converter/ds3/`.

## Benchmarks

```
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='.*PatternCorpus.*'
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='-prof gc'
```

Two benchmarks measure different things and neither is the headline alone: `CorpusBenchmark`
runs ten hand-picked patterns over 2,000-record buffers, measuring sustained scanning;
`PatternCorpusBenchmark` runs all 106 accepted corpus patterns over short inputs, measuring
per-match overhead at the tier mix a real configuration would have.

Every run records its full results to [design/benchmarks](design/benchmarks), named by date and
commit, and `tools/render-benchmark.py` renders one run as a table or compares two — marking any
difference whose error bars overlap as indistinguishable. Both exist because of
[D21](design/00-decisions.md): a single-fork harness moved 25% between runs of the same binary,
and a number nobody can re-find cannot be compared with the next one.

## Re-taking the Rust `regex` corpus

The converted cases are checked in under `src/test/resources/rust-regex/`. To refresh them:

```
git clone --depth 1 https://github.com/rust-lang/regex /tmp/regex
stroom-shapeshifter/tools/convert-rust-corpus.py /tmp/regex/testdata \
    stroom-shapeshifter/stroom-shapeshifter-regex/src/test/resources/rust-regex
```

## Pattern corpus analysis

Classifies DS3 regex patterns by the execution tier they would compile to. Defaults to this
repository; point it at a content export for a representative answer.

```
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:test \
    --tests '*CorpusAnalysisTest*' --rerun-tasks -i \
    -Dshapeshifter.corpus.dir=/path/to/content
```
