# Engine compilation

The intended shape — stated in DS3, restated in ds-rs, and confirmed as this engine's target on
2026-08-20 — is the regex library's shape one layer up: a **model the user edits** (a `Project`,
as a pattern is text) and a **compiled object graph that executes it as optimally as possible**
(as a pattern becomes a `BytePattern` with its tiers and plans). ds-rs's own design record shows
how far this principle was taken there: its hot-path audit declares compile-time lifting
"exhaustive", with a table of every decision moved out of the loop — predicate lookup tables,
pre-filtered child indices, ref strategy classification — and a closing list of what remains at
runtime because it is irreducible (`docs/ds3_design_document.md`, the compiled-state tables;
`docs/template_engine_architecture.md`, the performance design).

This document is the honest status of our compilation stage against that target, written before
the first benchmark so that the gap list is a hypothesis sheet rather than a memory.

## 1. What is compiled today

The port built a real compiler pass (`compile/Compiler.java`), and what it compiles, it
compiles properly:

| Compiled once | Where |
|---|---|
| Match patterns → `BytePattern` — the deepest compilation in the system: tier selection, scan plans, NFA, all of it | the matching layer |
| Delimiters and their escapes/containers → bytes, in the source encoding | `CompiledMatch.Delimiter` |
| Patterns inside bodies and conditions → compiled and interned, so a bad one fails before input is read | `CompiledProject.patterns` |
| `PatternRef`s → inlined, with cycle detection | `Compiler.resolve` |
| Unsupported constructs → rejected with the template's name | `Compiler.notYet` |

## 2. What is interpreted today — the gap list

Everything else executes off the *authored* model, per match. Each row is a compilation
candidate, and none may be acted on before a benchmark says which matter (§4):

| Interpreted per use | The cost, concretely | ds-rs's answer |
|---|---|---|
| Mode dispatch: `apply()` filters the whole template list by mode **on every call** | O(all templates) per dispatch, per match — `win_sec` runs a 57-template level once per record | pre-computed index lists per mode |
| Reference resolution: `Refs` walks `RefExpression` parts every time | per-part dispatch, and **literal text is re-encoded to UTF-8 on every write** | `RefStrategy` classification: `SimpleLocal` / `SimpleRemote` / `LiteralBytes` (pre-encoded) / `Complex` |
| `OutputNode.Text` and every `sink.write(String)` | `String.getBytes` per write | pre-encoded bytes on the compiled node |
| Body/condition pattern lookup by pattern *text* | string hash per `matches`/regex-`replace` evaluation | `compiled_idx` — an array index |
| `call-template` | linear name search per call | resolved at compile time |
| Step atoms: `Tag`/`TakeUntil` text | `.getBytes` **per match attempt** | pre-encoded bytes per compiled step |
| `TakeWhile` predicates | switch dispatch per byte | 256-entry boolean lookup table |
| Conditions and guards | config-tree walk per evaluation | compiled once |
| Captures | every group copied out of the buffer whether or not anything reads it | unused-capture elimination + dead-branch pruning (the E10 optimiser, deliberately unported during the port) |
| `^`-anchored patterns dispatched as **unanchored searches** | a failing anchored template scans the whole remaining region instead of testing one position — ~55 times per element in `win_sec_xml` | detect start-anchored patterns at compile time and dispatch them `Anchoring.ANCHORED` *(added from the baseline, §5)* |
| `ByteMatcher` allocated per match attempt | allocation on the hottest call the engine makes | cache one matcher per compiled template per executor *(added from the baseline, §5)* |

The regex library already proves the end state on its own layer; the engine's job is the same
move for dispatch, references, bodies and steps. The likely shape is what D34 already implies:
`CompiledTemplate` grows per-mode dispatch tables, references become classified strategies with
pre-encoded literals, and bodies become a compiled instruction list rather than a walked model —
but shape follows measurement, not the other way round.

## 3. Decoration: measurement and IO capture as a compile-time choice

The requirement: during model *development*, stages and blocks are wrapped with per-stage timing
and IO capture so an editor can show what each part did; during normal runtime those wrappers do
not exist — not "are cheap", but are **absent from the compiled graph**.

Both ancestors do a version of this. DS3's `ExecutionProfiler` is implemented by its expression
nodes ("to track down problem REGEX", with top-N reporting in the parser); ds-rs monomorphises
an `Instrument` trait so the no-op version compiles to nothing, and its hot-path audit lists
capture recording as "editor features, not production".

Our current seam is the ported `Instrument` interface with a do-nothing `NONE`. It is honest but
weaker than the target on three counts: its calls sit in the hot loop always, relying on JIT
inlining of a monomorphic no-op rather than on structure; it reports events (match, capture,
output spans) but not per-stage IO snapshots; and it is not attached to a compiled graph,
because there mostly isn't one yet. The right sequencing falls out of §2: when bodies and
dispatch become compiled structures, decoration becomes what it should be — a compile option
(`compile(project, options)`) that either wraps each compiled node in a measuring/capturing
decorator or doesn't, and the production graph never contains the branch. The `Instrument`
interface likely survives as what decorators *report to*; what changes is that its presence
becomes structural rather than conditional.

## 4. Method: benchmarks first

The matching layer's discipline applies unchanged (D21, D22, `06-performance-plan.md`): checked
in JMH results, five forks, one change at a time, and the corpus as the workload rather than a
flattering subset. `EngineBenchmark` runs whole fixture configurations over inputs repeated to a
fixed size, so a number is a configuration's real throughput, not a microbenchmark's story:

- `regex_lines`, `csv_header` — the two simplest shapes, regex-per-line and delimiter+vars
- `ausearch`, `apache_httpd` — heavy bodies and transforms (apache now routes 209 capture
  writes through XML escaping, which is exactly the kind of cost that needs a number)
- `win_sec` vs `win_sec_xml` — the same data parsed unanchored vs anchored: a direct A/B on
  dispatch and anchoring cost under `(A|B|C)*`
- `progressive` — the step interpreter over binary records
- plus `compile` per workload, because compilation cost is now a product surface too

Baseline results land in `design/benchmarks/` alongside the regex module's, named by date and
commit, and the first optimisation may be attempted only after the baseline is committed.

## 5. The baseline (2026-08-20, harness commit `7ffb36f0b4`, five forks)

| Workload | run ops/s | ± | MiB/s | compile ops/s |
|---|---:|---:|---:|---:|
| `regex_lines` | 270.5 | 4.6 | 67.6 | 183,942 |
| `progressive` | 191.3 | 3.6 | 47.8 | 8,637,471 |
| `csv_header` | 120.9 | 2.4 | 30.2 | 5,754,139 |
| `apache_httpd` | 84.9 | 2.0 | 21.2 | 263 |
| `ausearch` | 41.9 | 1.4 | 10.5 | 354 |
| `win_sec` | 22.0 | 0.8 | 5.5 | 293 |
| `win_sec_xml` | 6.1 | 0.1 | **1.5** | 118 |

Raw results: [benchmarks/2026-08-20-1658-7ffb36f0b4-engine.json](benchmarks/2026-08-20-1658-7ffb36f0b4-engine.json).
The box was checked for competing JMH runs before launch, and the five-fork numbers agree with
the pre-commit smoke run (`regex_lines` 254 → 270, `win_sec` 21.9 → 22.0).

**The headline is an inversion.** The A/B built to show anchored dispatch as the cheap path
shows the opposite: `win_sec_xml`, the anchored configuration, is **3.6× slower** than its
unanchored sibling and the slowest workload measured — 45× off the simple-regex pace. The
diagnosis, read from the executor rather than guessed: every attempt allocates a fresh
`ByteMatcher`, and a `^`-anchored pattern is still dispatched as an *unanchored search*, so each
failing template — ~55 of them per element, since only one field is ever next — scans the
remaining region to prove what one test at the cursor would have proved. D34's "anchored is the
cheap path" is true of the matching layer's single attempt and false of our dispatch as
implemented; the baseline caught the gap between the principle and the code, which is what it
was for. Both fixes are compile-time items, now rows in §2, and together they are the obvious
first optimisation — with a predicted order-of-magnitude win on exactly the configuration style
D34 pushes authors toward.

Elsewhere: the step interpreter is *not* the first-order problem (47.8 MiB/s without any of
E14's lowering), and compile costs are milliseconds at worst (`win_sec_xml`, 55 patterns,
~8.5 ms) — compile-once-run-many holds with room to spare.
