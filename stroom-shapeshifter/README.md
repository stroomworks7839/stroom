# stroom-shapeshifter

A byte-level parsing and transformation engine.

## Modules

| Module | Contents |
|---|---|
| [`stroom-shapeshifter-regex`](stroom-shapeshifter-regex/README.md) | Byte-level regex and combinator matching engine |
| `stroom-shapeshifter-engine` | Configurations, templates and transforms, built on it |

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

Supported now: the whole RE2 subset plus the constructs beyond it, over UTF-8 input —
complete views, always (D37) — on engines chosen automatically. An unambiguous pattern becomes a straight-line
scan plan with no automaton. Anything else runs a tree-walking backtracker first —
`java.util.regex`'s own architecture, rebuilt over bytes, which measured at or ahead of the
JDK across the corpus — with a linear-time Pike VM simulation beneath it as both fallback and
guarantee: whatever the engines give up on, one machine finishes in linear time, so
catastrophic backtracking is unrepresentable rather than merely unlikely. Two further flat
backtrackers stay pinnable as differential witnesses. Results are identical whichever engine
runs — `explain()` reports the engine, and `ambiguities()` says why a pattern needed an
automaton, which is often an authoring mistake worth seeing.

The matching library has **no dependencies** — only the JDK. That is enforced, not hoped:
`verifyZeroDependencies` runs with `check` and fails the build naming any dependency that
appears. Use it anywhere.

The constructs that are not regular — backreferences, lookaround, atomic groups and
possessive quantifiers, `\Q...\E` and `\G` — compile natively; writing `\1` is the opt-in.
For those patterns the linear-time guarantee is exchanged for a step budget: a pathological
pattern-input pair raises `MatchLimitException` after bounded work instead of hanging a
pipeline. This closes the capability gap with `java.util.regex` without a delegated second
dialect, and with the same Rust-style spellings throughout — Rust's own `regex` crate has no
backreferences at all, so the fancy layer follows `fancy-regex`, its ecosystem's answer.

Character classes are compiled once, at compile time, through the declared encoding — UTF-8,
any single-byte table, or RAW — so `.` and `[a-zé]` match whole characters and a span never
splits one, while ASCII-only classes and unbounded scans keep a byte-level fast path.
A match never begins inside a character, not even an empty one.

The dialect follows Rust's `regex` rather than Perl's inheritance: `$` is the end of the input,
`\w \d \s \b` are Unicode unless `(?-u)` says otherwise, `\p{...}` means Unicode properties
and the ASCII POSIX classes are spelt `[[:alpha:]]`. Rust's own warts are declined, and both the
choices and the refusals are listed in [design/01-regex-language.md](stroom-shapeshifter-regex/design/01-regex-language.md)
§2.4. Rust's test corpus runs as part of the suite, and Oniguruma's UTF-8 suite covers the
fancy tier — a backtracking engine's own corpus, dense in exactly the constructs the Rust one
deliberately has none of.

Also built: the combinator layer (`comb.Matchers`), which flattens to the same plans as the
equivalent regex. A streaming surface (`StreamMatcher`, a three-way outcome) was built and
then retired by D37 (2026-08-25): the executor never consumed it, and the library is
complete-inputs-only — byte arrays and slices.

Encodings landed on 2026-08-28 ([design/19-encoding-plan.md](design/19-encoding-plan.md)):
single-byte tables, RAW, `\B{HH}` byte escapes, and the `transcode` stage for the UTF-16
family. (A delegated `java.util.regex` dialect was once planned and is deliberately gone —
D27 made it unnecessary.)
A pattern outside the dialect is rejected with the reason — never matched approximately.

## Modules

- `stroom-shapeshifter-regex` — the matching library: everything described above.
  Zero dependencies, enforced; usable standalone.
- `stroom-shapeshifter-engine` — the layers above matching (D12): configurations, templates,
  transforms and the output side, ported from the Rust prototype (D33). Depends on the regex
  module; the reverse is forbidden by the zero-dependency check.
  [Its README](stroom-shapeshifter-engine/README.md) is the way in.
- `stroom-shapeshifter-pipeline` — planned (D10): the thin adapter that puts the engine
  into a Stroom pipeline, keeping Stroom's types out of both library modules.

## Design

The design drafts, the decisions and the measurements. Spanning both modules:

- [00-decisions.md](design/00-decisions.md) — decision log, with consequences.
- [15-audit-ledger.md](design/15-audit-ledger.md) — the adversarial audit record.
- [benchmarks/](stroom-shapeshifter-regex/design/benchmarks/README.md) — every JMH run, checked in on purpose.

The regex library's design record lives with the module it describes, indexed at
[stroom-shapeshifter-regex/design/](stroom-shapeshifter-regex/design/README.md):

- [01-regex-language.md](stroom-shapeshifter-regex/design/01-regex-language.md) — the matching language: RE2-style
  regex dialect, encoding-aware character classes, atoms and nom-style combinators.
- [02-engine-design.md](stroom-shapeshifter-regex/design/02-engine-design.md) — engine architecture: the encoding
  compiler, the one-pass scan plan and Pike VM execution tiers, streaming (since retired:
  D37), and the test strategy.
- [03-baseline-results.md](stroom-shapeshifter-regex/design/03-baseline-results.md) — JMH measurements taken before any
  engine code, confirming one design assumption and refuting two.
- [04-corpus-analysis.md](stroom-shapeshifter-regex/design/04-corpus-analysis.md) — which execution tier real DS3
  patterns would land on, and why.
- [05-engine-benchmarks.md](stroom-shapeshifter-regex/design/05-engine-benchmarks.md) — the engine measured against
  `java.util.regex` on realistic patterns, what the pattern corpora cover, and every
  divergence found so far.
- [06-performance-plan.md](stroom-shapeshifter-regex/design/06-performance-plan.md) — the performance work and its
  standing, the per-tier audit record, and the method notes; future sessions start here.
The template engine's, in the parent design folder:

- [07-engine-port-plan.md](design/07-engine-port-plan.md) — the port of the ds-rs template
  engine into `stroom-shapeshifter-engine`: inventory, the eight phases and what each found,
  what is deliberately absent, and the findings and decisions it leaves open. Complete.
- [08-fixture-audit.md](design/08-fixture-audit.md) — the phase 0 check of the generated
  fixture goldens, and the four found wrong.
- [10-engine-compilation.md](design/10-engine-compilation.md) — the engine's compilation
  stage: what is compiled, what is interpreted, and the measurement-first plan.
- [09-engine-semantics.md](design/09-engine-semantics.md) — what the engine's matching,
  dispatch and transformation layers mean, where the port diverges from real DS3, and D34's
  resolution.

The Rust project's own design documents are vendored unedited at
[stroom-shapeshifter-engine/docs/](stroom-shapeshifter-engine/docs), with an index marking
which of them apply to the port.

Prior art for both: the Rust `ds-rs`/shapeshifter prototype, and Stroom's existing DS3
implementation at `stroom-pipeline/src/main/java/stroom/pipeline/xml/converter/ds3/`.

## Benchmarks

```
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='.*PatternCorpus.*'
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh --args='-prof gc'
```

Two benchmarks measure different things and neither is the headline alone: `CorpusBenchmark`
runs sixteen workloads over 2,000-record buffers, measuring sustained scanning across every
engine and shape — including a never-matching workload, two-kilobyte records, accented text
and the fancy constructs; `PatternCorpusBenchmark` runs all 114 corpus patterns (every one of
them accepted) over short inputs, measuring per-match overhead at the engine mix a real
configuration would have, plus the whole corpus in one JVM.

### Where it stands

Against `java.util.regex` reading the same bytes, same run, best engine chosen automatically —
**ahead on 28 of 30 measured variants**, every per-match category included, and at parity on
the other two: buffer `KEYVALUE` inside its error bars, and buffer `NETWORK`, which measures at
parity in controlled fork-per-side comparison and is a documented harness artifact
([06-performance-plan.md](stroom-shapeshifter-regex/design/06-performance-plan.md)). The one
shape it was behind on — a lazy run spelt as a nested line loop over 64 KiB regions — closed
on 2026-09-03; the bounds on the claim are stated in the module's own
[README](stroom-shapeshifter-regex/README.md). Charts below are from
`2026-09-02-2146-75f6bbaa1c-chain-after-plan.json`, the first run on the current machine.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="stroom-shapeshifter-regex/design/benchmarks/charts/buffer-dark.svg">
  <img alt="Buffer-scanning throughput relative to java.util.regex, per workload" src="stroom-shapeshifter-regex/design/benchmarks/charts/buffer-light.svg">
</picture>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="stroom-shapeshifter-regex/design/benchmarks/charts/permatch-dark.svg">
  <img alt="Per-match throughput relative to java.util.regex, per corpus category" src="stroom-shapeshifter-regex/design/benchmarks/charts/permatch-light.svg">
</picture>

The charts are rendered from the checked-in runs by `tools/render-scoreboard.py`, the same
rule as the tables: a number in this file can always be traced to the run that produced it.

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
