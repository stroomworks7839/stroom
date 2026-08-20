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
signal on all 48 in-scope fixtures. Real streaming becomes a decision of its own once the
port is green and the semantics are pinned by tests. It is the single most valuable
follow-up this port sets up, and it should not be smuggled in during the port.

**The goldens are frozen, and the regenerators do not come with them.** Eleven of the
eighteen `projects` goldens were produced by `gen_native_fixture_outputs` from ds-rs's own
output. Generation was a starting point, not a warrant: each of those eleven is *checked* for
correctness once, during phase 0, and from then on it is a golden file like any other —
committed, and changed only by a decision that says why. None of the four ignored
regenerators is ported. A fixture that can rewrite its own expectation is not a test.

**The runners assert on messages, which is one deliberate deviation.**
`005_unmatched_content_FAIL` and `014_simple_regex_min_match_FAIL` ship `.err` files that no
Rust runner reads, so the warning and error paths — unconsumed content, `min_match`
shortfall, "consumed entire buffer" — have golden data sitting unused. Our runners collect
`ParseMessage`s and assert against `.err` where one exists. This is additive: it cannot break
output parity, only add signal where the Rust suite is dark. It is a test-side deviation, not
an engine-side one, and it is the only one.

**The output side goes behind a sink from the start.** Every `write_ref` in `core.rs` and
every `OutputNode` case in `body.rs` writes to a `Write`. The port keeps that behaviour but
puts one interface at the boundary, with a byte-sink implementation as the only one built.
The reason is D10's open question: the pipeline element will want something other than a byte
stream — SAX events are the obvious candidate but not the only one, and that choice is not
being made here. Taking the seam now costs one indirection; retrofitting it means revisiting
every output case a second time.

**Records and sealed interfaces for the Rust enums.** `MatchExpression`, `MatchStep`,
`OutputNode`, `Condition`, `RefPart`, `CaptureSource` and `TypedValue` are all sum types
whose exhaustive `match` is load-bearing. On Java 25 they become sealed interfaces with
record variants and pattern-matching `switch`, which keeps exhaustiveness a compile error
rather than a runtime default branch.

## 4. Risk, retired and open

**Retired — the regex dialect matches.** The corpus's patterns are written for Rust's
`regex` and `fancy-regex`, and D19 chose that dialect for exactly this reason, but "chose it"
and "it works" are different claims. So they were extracted and compiled through
`BytePattern`. The corpus holds 208 distinct `pattern` strings, four of which belong to
`replace` instructions with `is_regex` false and are literals rather than regexes (`\t`, `\n`,
`+`, `drafts`). **All 204 genuine regexes compile** — 74 to `SCAN_PLAN`, 121 to `SIMULATE`, 9
to `TREE`, including atomic groups (`(?>…)`), `\z`, `(?m)` and inline flags. This is now
`PatternCorpusTest`, which harvests from the three places a regex can appear rather than every
field named `pattern`, so the literals are excluded by construction.

**Retired, and it cost four fixtures — the generated goldens were not all correct.** Eleven
of the eighteen `projects` goldens came from ds-rs's own output rather than an external
oracle. The phase 0 audit checked all eleven for well-formedness, record count, empty
elements, stray escapes and unescaped markup. Record counts are exact everywhere — no fixture
drops a record — but **four are wrong**: `apache_httpd`'s golden is not well-formed XML,
`xml_to_json`'s is not valid JSON, and `win_sec` and `win_sec_xml` silently drop group
identity that is plainly present in their inputs. They are quarantined rather than deleted,
and the findings are in [08-fixture-audit.md](08-fixture-audit.md). The other seven are frozen
goldens from here on. In-scope expectations are therefore **48**, not 51.

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
built first and report `n/48` from the start, so every phase moves a number.

| # | Phase | Acceptance |
|---|---|---|
| 0 | **Harness first — done.** 132 fixture files vendored with provenance; the ledger, the three golden runners and the message goldens built; the eleven generated goldens audited and four quarantined; the pattern probe made a test | `0/48`, 3 skipped, 4 quarantined; 204 corpus patterns compiling |
| 1 | **Model and binding — done.** The `config` package as records and sealed interfaces; the whole wire format in one `ProjectJson`, behind `ProjectReader` | All 36 configurations round-trip; every variant of all 8 sum types round-trips, checked against the sealed permits list |
| 2 | **Vertical slice — done.** UTF-8 only; the output sink and its byte implementation; `TypedValue`, `Store`, `VarRegistry`, `Refs`, `Splitter`; compile and run `Regex`, `Delimiter`, `Source`, `All`; body limited to `Text`, `ValueOf`, `ApplyTemplates` | `6/48` — native 004, 006, 010, 012, 013 and `projects/json_to_xml`, which is every fixture the slice can reach |
| 3 | **The rest of the body — done.** All fourteen conditions, `If`/`Choose`/`Switch`, `Variable`, `CallTemplate`, `ValueMap`, the twelve transform functions, and template guards | `25/48` — all 18 `native` and all 7 non-progressive `projects` fixtures |
| 4 | **DS3 import — done.** `ds3_config`, `migration` and the legacy `$`-syntax reference parser, including `records:2` output shaping | `44/48` — all 19 `legacy` entries, output *and* message goldens, rejection case included |
| 5 | **Progressive matching — done.** All 24 `MatchStep` kinds, `StepRef` resolution, pattern-reference inlining, and the JDK codecs | `48/48` — every in-scope fixture |
| 6 | **Encodings — done.** All 29 named encodings, byte-order-mark detection, inheritance, and conversion at the two boundaries that need it | The Rust suite's encoding assertions ported (18 tests), plus 6 that run non-UTF-8 input end to end |
| 7 | **Unit test port — done.** The Rust suite's assertions for `refs`, `store`, the compiler and the parts of `exec_tests` the fixtures cannot reach | 184 tests green, and a cycle-detection defect found and fixed |
| 8 | **Instrumentation seam — done.** `Instrument` with a do-nothing default, wired through matching, capture and output; `PatternInfo` for authoring | 198 tests green; a recorder checks the reported offsets really point at the bytes |

Phases 5 and 6 are ordered after 4 deliberately: a full ledger is the milestone that
proves the architecture, and progressive matching and exotic encodings are each self-contained
enough to follow it without re-opening anything.

**What phase 8 found: two more of my own expectations wrong, and neither the engine's.** A
root template runs at depth 0, not 1 — the document template's `apply-templates` *is* the
streaming loop rather than a dispatch into one. And the match loop stops when the input is
exhausted rather than making one more attempt that fails, so a failing attempt has to be
provoked by a template that cannot match rather than by running out of input.

**What phase 7 found: a missing guard.** Porting `compiled.rs`'s tests turned up that pattern
reference inlining had no cycle detection — a pattern referring to itself would have inlined
until the stack ran out. ds-rs carries a visited set through the recursion and unwinds it on the
way back, so a pattern used twice in different branches is fine and only one reached from inside
itself is a cycle. Fixed, with tests for both halves of that distinction.

Also settled: `ds-rs`'s compile-time optimiser — unused-capture elimination and dead-branch
pruning — is **not** ported. It changes no output, only work, and D33 rules out performance work
during a port. Its fourteen tests are therefore not ported either, and the omission is recorded
here rather than left to be noticed.

**What phase 6 found: a field nobody reads.** `Template.encoding` is in the model, is written
by the format, and is never looked at by the Rust engine — an encoding override per template
does not work and never has. It is ported as modelled, because a port that silently dropped a
field would be worse, and recorded here as something to decide rather than to fix in flight.

Worth noting about the phase's shape: no fixture reaches any of this. Every configuration in the
corpus is UTF-8 or `auto`, so the encoding path could have been wired up backwards and all 48
would still pass. Hence `EncodedInputTest`, which runs Latin-1, Windows-1252 and a
byte-order-marked stream through the whole engine — including a non-ASCII *delimiter*, which is
the case that proves encoding reaches compilation and not just output.

**What phase 5 settled: the combinator layer is the wrong home, for a reason worth writing
down.** `stroom.shapeshifter.regex.comb` has almost exactly the progressive vocabulary — `Tag`,
`Sequence`, `Choice`, `Repeat`, `Ref`, and `Characters` covering `takeWhile`/`takeN`/`anyChar` —
and D8 says compose at authoring time and flatten at compile time, which is what lowering these
steps onto it would do. It would also be faster, since they would reach the tiered engines
instead of an interpreter.

It would also change the language. The step interpreter is greedy and **does not backtrack**: a
`Choice` takes the first alternative that matches and never reconsiders, a `Repeat` never gives
anything back. A compiled pattern does both. So `Repeat(Tag("ab")) Tag("ab")` against `abab`
fails as steps and matches as a pattern — same configuration, different answer. Lowering is a
decision about semantics, not an optimisation, and it is recorded here rather than taken.

What the regex library *is* used for here is the `Regex` step itself, compiled and interned like
any other pattern. The predicates deliberately are not: ds-rs takes its byte path for every
byte-oriented encoding including UTF-8, so its predicates are ASCII in practice, and the
Unicode-aware classes the regex library would bring would have been an improvement rather than a
port.

**What phase 4 found: nothing.** All nineteen legacy entries went green on the first run,
output and messages both — which is worth stating because it is the only phase that did. The
message goldens make that a real claim rather than a lucky one: they pin 38 warnings and errors
across the eighteen configurations, and a one-word edit to a single golden was checked to fail
the suite before the result was believed. The reason it went cleanly is that DS3's semantics are
almost entirely *conversion*: once the tree is right, the engine underneath had already been
tested by twenty-five fixtures.

**What phase 3 found.** `native/001_csv_with_header` was the last of the twenty-five to go
green, and it failed on something no other fixture could see: an `apply-templates` whose
`select` is group 0 must be handed the content the parent template *selected*, not group 0 of
the parent's match. For a delimiter template those differ — its content is the field, its group
0 includes the delimiter — so resolving group 0 hands the separator down to the child, and a CSV
header's last column name comes out as `what\n`. The Rust engine has the same special case with
a comment explaining it; the fixture is what forced reading the comment properly.

**A correction from phase 2.** This table originally named `native/001_csv_with_header` as the
slice's second green fixture. It is not reachable by a slice: it needs `Variable` for scope
collection, `If` with an `Exists` condition, and the relative match-index machinery that lines a
header column up with the data column beneath it — all phase 3. The fixtures a
`Text`/`ValueOf`/`ApplyTemplates` body can actually reach were then derived from the corpus
rather than guessed, and all six of them are green.

## 6. The port is complete

All eight phases are done. 48 of 48 in-scope fixtures pass, output and — for the legacy family —
messages; 198 tests run in the module; both it and the matching layer check clean.

What is deliberately not here, each with its reason recorded above: the three binary formats and
their heavy dependencies (D33); the four goldens the phase 0 audit found wrong, quarantined until
somebody produces corrected ones; ds-rs's compile-time optimiser, which changes work rather than
output; and `RecordingInstrument`, whose only consumer was an editor that is not being ported —
the seam it needed is here, the implementation is not.

**What the port turned up, for deciding separately.** None of these were fixed in flight, because
a port that improves things as it goes cannot be checked against the thing it is porting:

| Finding | Where |
|---|---|
| A `maxMatch` template raises a false "did not consume all content" warning against everything after the line it was told to stop at | fixtures README, `001` |
| Both fixtures that exist to test `ignoreErrors` emit a warning anyway | fixtures README, `011`/`012` |
| `Template.encoding` is modelled, written by the format, and never read — a per-template encoding override does not work | phase 6 |
| `win_sec` and `win_sec_xml` drop group identity that is plainly in their input | [08-fixture-audit.md](08-fixture-audit.md) |
| `apache_httpd`'s golden is not well-formed XML; `xml_to_json`'s is not valid JSON | [08-fixture-audit.md](08-fixture-audit.md) |
| A `Regex` step searches forward but consumes only its own match, leaving the skipped bytes unread | `StepsTest` |
| `TakeWhile`'s predicates are ASCII even on UTF-8 input | `StepsTest` |

**The decisions the port sets up**, in the order they are likely to matter: whether matches may
span buffers now that the limitation is pinned by tests; whether the progressive steps should be
lowered onto the combinator layer and gain backtracking; and what the output sink's other
implementation is — which is D10's question, still open, with one place to answer it.

## 7. Method

The same discipline as the matching layer, adapted:

- **The fixtures are the specification.** Where the Rust behaviour is surprising, port the
  surprise and note it; do not improve it silently. Anything that looks like a bug in `ds-rs`
  gets recorded here and decided separately.
- **A skipped fixture is a reported number, not a deleted file.** The three binary-format
  fixtures and the four quarantined ones stay vendored and stay listed, so the gap is visible
  in the same file that says what passes.
- **The Rust design documents are vendored, unedited**, at
  [../stroom-shapeshifter-engine/docs/](../stroom-shapeshifter-engine/docs) with an index that
  says which apply. They are history and intent; where they and the Rust source disagree, the
  source wins.
- **No performance work during the port.** The matching layer's numbers came from
  change-then-measure with checked-in results; guessing at hot paths while the semantics are
  still moving would produce neither. Once the suite is green, the engine gets its own
  benchmark set and its own plan.
