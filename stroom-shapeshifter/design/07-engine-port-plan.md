# Engine port plan — ds-rs to `stroom-shapeshifter-engine`

The matching layer is finished and measured ([05](05-engine-benchmarks.md), [06](06-performance-plan.md)).
This is the plan for the layer above it: porting the `shapeshifter` crate from `ds-rs`
(`/mnt/shared/ds-rs/engine`) into `stroom-shapeshifter-engine`, together with its whole test
corpus. It is the work D12 promised — "the whole engine comes to Java eventually" — and it is
a *port*, not a redesign: the Rust engine's behaviour is the specification, and its fixtures
are the acceptance test.

---

## 1. What is being ported

The `shapeshifter` crate, at 9,704 lines of source and 4,986 of test:

| Rust source | LOC | Becomes | What it is |
|---|---:|---|---|
| `project.rs` | 818 | `engine.config` | The serialisable model: `Project`, `Template`, `MatchExpression`, `MatchStep`, `OutputNode`, `Condition`, `CaptureBinding`, `MatchLimits` |
| `refs.rs` | 889 | `engine.config` | `RefExpression` / `RefPart` / `MatchIndex` — the value expressions, plus the legacy `$0`-syntax parser used only by DS3 import |
| `matcher/predicate.rs` | 273 | `engine.config` | `Predicate` and its charset mini-language, for `TakeWhile` |
| `error.rs` | 109 | `engine` | `Severity`, `Location`, `ParseMessage`, `ParseError`, `ConfigError` |
| `compiled.rs` | 1,537 | `engine.compile` | `Project` → `CompiledProject`: regex compilation and interning, pre-encoded delimiters, ref-strategy classification, dead-branch elimination |
| `engine/core.rs` | 873 | `engine.exec` | Compiled regex façade, `MatchRes`, the scoped variable registry, condition evaluation, ref resolution, delimiter splitting |
| `engine/exec.rs` | 663 | `engine.exec` | Entry points, the chunked streaming loop, source-template prologue/epilogue |
| `engine/body.rs` | 814 | `engine.exec` | Template dispatch, the match loop, capture binding, `apply-templates`, the transform functions |
| `engine/matching.rs` | 789 | `engine.exec` | Match-expression dispatch and progressive step execution |
| `engine/store.rs` | 362 | `engine.exec` | `TypedValue` and the match-indexed `Store` |
| `encoding.rs` | 649 | `engine.text` | Encoding resolution, BOM detection, decode/encode |
| `ds3_config.rs` | 579 | `engine.ds3` | DS3 XML → `DS3Config` tree |
| `ds3.rs` | 107 | `engine.ds3` | The `DS3Config` tree itself |
| `migration.rs` | 789 | `engine.ds3` | `DS3Config` → `Project`, including the `records:2` XML output shaping |
| `engine/instrument.rs` | 492 | `engine` (interface only) | The zero-cost instrumentation seam |
| `regex_info.rs` | 72 | `engine` | Capture-group introspection for authoring tools |

And the tests: **158 unit tests** inside `src`, **43 integration tests** in `tests/`, 3 doc
tests, and **56 fixture sets** (136 files) driven by three golden runners — `legacy` (DS3 XML
+ input + golden output), `native` (project.json over the legacy inputs), and `projects`
(project.json + input + expected output).

**The baseline, measured rather than assumed** (`cargo test --offline`, 2026-08-20, default
features): **200 passing, 0 failing, 4 ignored**. The four ignored tests are fixture
*regenerators*, not failures. The fixture runners report `18/18` legacy, `18/18` native and
15 of 18 projects — the other three are the binary-format fixtures, which ds-rs's own
default-features run already skips for exactly the reason we are deferring them. **51 fixture
sets are in scope, and all 51 are green in Rust today.**

A nineteenth legacy fixture, `008_invalid_xml_FAIL`, has no `.out.xml` golden — only a
`.out.tmp.xml` — so the Rust runner skips it and so will ours.

## 2. What is not being ported, and why

Decided 2026-08-20, with the user:

- **Avro, Parquet and Protobuf matchers, and the snappy/zstd/lz4 codecs.** Each needs a
  large third-party library. The `MatchExpression` variants are still modelled so configs
  parse, but compilation rejects them with a clear "not supported in this build" message.
  Cost: 3 of 18 `projects` fixtures (`avro_users`, `parquet_cities`, `protobuf_events`) are
  skipped, and recorded as skipped rather than quietly dropped. Base64, hex, URL-encoding,
  gzip and deflate are all in the JDK, and the one fixture that uses a codec
  (`progressive_embedded_codec`) uses Base64.
- **`RecordingInstrument` and its timing report** (492 lines, plus 1,052 lines of tests).
  Its only consumer is the `ds-rs` node editor UI, which is not being ported. The
  `Instrument` interface and its no-op implementation *are* ported, because the engine is
  written against that seam and removing it would change every signature.
- **The `server` and `shared` crates and the node editor.** Out of scope; D10's pipeline
  element is the Java equivalent and is a separate module.

## 3. Decisions taken up front

**Jackson for the config binding.** `project.json` is a serde document with roughly forty
externally-tagged, kebab-cased variants. Jackson 3 is already in the version catalogue and
Stroom's standard. This does mean `stroom-shapeshifter-engine` does not inherit the regex
module's zero-dependency promise — that promise is specifically the matching layer's, is
enforced by `verifyZeroDependencies`, and stays true. Reading stays behind one
`ProjectReader` seam so a JDK-only reader remains possible later without touching the model.

**Chunk semantics ported faithfully.** `ds-rs` reads fixed-size buffers and never lets a
match span a chunk boundary; it emits a "consumed entire buffer" warning instead. Our
matching layer can do better — `StreamMatcher`'s three-way outcome exists precisely for this
— but porting the limitation first is what makes golden-output parity a clean pass/fail
signal on all 51 in-scope fixtures. Real streaming becomes a decision of its own once the
port is green and the semantics are pinned by tests. It is the single most valuable
follow-up this port sets up, and it should not be smuggled in during the port.

**Records and sealed interfaces for the Rust enums.** `MatchExpression`, `MatchStep`,
`OutputNode`, `Condition`, `RefPart`, `CaptureSource` and `TypedValue` are all sum types
whose exhaustive `match` is load-bearing. On Java 25 they become sealed interfaces with
record variants and pattern-matching `switch`, which keeps exhaustiveness a compile error
rather than a runtime default branch.

## 4. Risk, retired and open

**Retired — the regex dialect matches.** The corpus's patterns are written for Rust's
`regex` and `fancy-regex`, and D19 chose that dialect for exactly this reason, but "chose it"
and "it works" are different claims. So: all 208 distinct `pattern` values across the three
fixture families were extracted and compiled through `BytePattern`. **207 compile** — 77 to
`SCAN_PLAN`, 121 to `SIMULATE`, 9 to `TREE`, including atomic groups (`(?>…)`), `\z`, `(?m)`
and inline flags. The single failure is `"+"`, which is a literal `replace` pattern with
`is_regex` false, so it is not a regex at all and Rust rejects it too. This probe becomes a
permanent test in phase 0, not a one-off.

**Open — the goldens are not all the same kind of evidence.** The `legacy` `.out.xml` files
came from Java Stroom's DS3 and are a genuine external oracle. But `gen_native_fixture_outputs`,
one of the four ignored regenerators, regenerates the goldens for eleven of the eighteen
`projects` fixtures *from ds-rs's own output*. For those, parity asserts "same as ds-rs" —
which is precisely what a port wants, and precisely why they cannot catch a bug ds-rs already
has. Worth knowing which fixtures are which when one of them disagrees.

**Open — the API shapes differ in three places** that will need real work rather than
transliteration:

1. `CompiledRegex::replace_all_str` expands `$1`-style references in the replacement.
   `ByteMatcher` has no replace API; the port needs one, and needs it to follow Rust's
   expansion rules rather than `java.util.regex`'s.
2. `regex::bytes` returns group spans over the input; `ByteMatcher` does too, but the
   `fancy-regex` fallback in `core.rs` works over `&str` and re-derives byte offsets. Ours is
   byte-native throughout, which is simpler — the risk is where the Rust code's `str` detour
   changed behaviour on invalid UTF-8, and those places need finding rather than assuming.
3. `encoding_rs` covers about forty encodings with WHATWG semantics; the JDK's `Charset`
   registry differs at the edges (label aliases, replacement behaviour). D5 already put hard
   encodings upstream of matching, which narrows this, but the 17 encoding tests are the
   place it will show up.

## 5. Phases

Each phase has an acceptance test that is a count, not an opinion. The fixture runners are
built first and report `n/51` from the start, so every phase moves a number.

| # | Phase | Acceptance |
|---|---|---|
| 0 | **Harness first.** Vendor all 136 fixture files with their provenance; build the three golden runners (legacy, native, projects) so they run red and report a count; make the 208-pattern compile probe a test | Runners execute and report `0/51`; pattern probe green |
| 1 | **Model and binding.** `config` package, `error`, `Predicate`, `RefExpression`; Jackson binding behind `ProjectReader` | All 36 `project.json` files parse, and round-trip parse→write→parse structurally equal |
| 2 | **Vertical slice.** UTF-8 only; `Store`/`TypedValue`; ref resolution; compile and run `Regex`, `Delimiter`, `Source`, `All`; body limited to `Text`, `ValueOf`, `ApplyTemplates` | First green fixtures: `native/004_simple_regex`, `native/001_csv_with_header` |
| 3 | **The rest of the body.** Conditions, `If`/`Choose`/`Switch`, `Variable`, `CallTemplate`, `ValueMap`, and the twelve transform functions; match limits, guards, modes, `ignore_errors` and the message/warning paths | The 18 `native` and 15 in-scope `projects` fixtures green — `33/51` |
| 4 | **DS3 import.** `ds3_config` and `migration`, including `records:2` output shaping | The 18 `legacy` golden fixtures green — `51/51` overall |
| 5 | **Progressive matching.** The `MatchStep` atoms and combinators, `StepRef` resolution, the JDK codecs | The 4 progressive fixtures green; `progressive_embedded_codec` included |
| 6 | **Encodings.** Full charset resolution, BOM detection, inheritance | The 17 encoding integration tests and 22 `encoding.rs` unit tests ported and green |
| 7 | **Unit test port.** The remaining ~160 unit and integration tests, `refs` (33) and `store` (13) and `compiled` (14) and `exec_tests` (55) foremost | Whole suite green; coverage of the ported surface no worse than the Rust crate's |
| 8 | **Instrumentation seam.** `Instrument` + no-op, and `regex_info` | Engine compiles against the seam with no production cost |

Phases 5 and 6 are ordered after 4 deliberately: 51 green fixtures is the milestone that
proves the architecture, and progressive matching and exotic encodings are each self-contained
enough to follow it without re-opening anything.

## 6. Method

The same discipline as the matching layer, adapted:

- **The fixtures are the specification.** Where the Rust behaviour is surprising, port the
  surprise and note it; do not improve it silently. Anything that looks like a bug in `ds-rs`
  gets recorded here and decided separately.
- **A skipped fixture is a reported number, not a deleted file.** The three binary-format
  fixtures stay vendored and stay listed as skipped, so the gap is visible.
- **No performance work during the port.** The matching layer's numbers came from
  change-then-measure with checked-in results; guessing at hot paths while the semantics are
  still moving would produce neither. Once the suite is green, the engine gets its own
  benchmark set and its own plan.
