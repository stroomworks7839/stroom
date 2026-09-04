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
| `ByteMatcher` allocated per match attempt | allocation on the hottest call the engine makes | a matcher held as a *field* of the compiled node — structure, not a cache *(added from the baseline, §5)* |
| Unanchored `(?m)^` patterns each scan the region per dispatch pass | a level of N multiline templates scans the same bytes up to N times per pass — `win_sec`'s whole 5.8 MiB/s story (§8) | none — ds-rs had the same cost. A compiled dispatch could know these patterns only match at line starts and find the next `\n` once for the level *(added after change 3)* |
| Transform functions work in `String` | every transform resolves bytes → `String`, transforms, re-encodes — `apache_httpd` runs 209 string-level replaces per record, and this is why change 3 moved it only 12% (§8) | none — ds-rs transformed strings too. Byte-level transforms, or at least single-conversion pipelines, would be new ground *(added after change 3)* |

**Resolved so far** — change 1 (§6): anchored dispatch, matcher as field. Change 2 (§7): mode
dispatch tables, `call-template` resolution. Change 3 (§8): reference strategies, pre-encoded
literals and `Text` bytes, and pattern-by-text for regex `replace` — though a `matches`
condition still looks its pattern up by text, so that row stays half-open with conditions.
Still open: step `Tag`/`TakeUntil` pre-encoding, `TakeWhile` byte tables, compiled
conditions/guards, capture elimination, and the two rows above.

The regex library already proves the end state on its own layer; the engine's job is the same
move for dispatch, references, bodies and steps. And the shape is **two layers, never three**
([D35](00-decisions.md)) — settled 2026-08-20 after a false start that imported `BytePattern`'s
shared-immutable contract up a level where nothing needs it. The `Project` is the model the user edits; the
`CompiledProject` **is the executable graph**, and matchers and stores are *fields of its
nodes* — structure, not a cache, because nothing is looked up when state has an owner. The
sharing that actually matters, pattern compilation, already lives in the immutable
`BytePattern` values the graph holds; DS3's factory tree is an instantiation convenience, not
an architectural layer; and the baseline (§5) prices the consequence at milliseconds —
concurrency is "compile one per instance", exactly as Stroom gives each pipeline element its
own parser.

Two contracts follow. A `CompiledProject` executes one run at a time and is reusable
sequentially — a `ByteMatcher`'s contract, one level up — which means it needs a defined reset
between streams: DS3's `Node.clear()`, and the same lifecycle E19 already settled for capture
stores. And `Executor` is transitional: as bodies and dispatch become compiled structures, it
dissolves into the graph, because "performs the execution" is the compiled object's job
description. Within that shape, `CompiledTemplate` grows per-mode dispatch tables, references
become classified strategies with pre-encoded literals, and bodies become a compiled
instruction list rather than a walked model — but shape follows measurement, not the other way
round.

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

## 6. Change 1, measured: the node owns its matcher and knows its anchoring

One structural change (`6582e96cd9`): `CompiledMatch.Regex` holds its `ByteMatcher` as a field
and decides its anchoring at compile time — provably start-anchored patterns dispatch
`ANCHORED`, one attempt at the cursor. Correctness gate first: all 52 fixtures and 242 tests
unchanged, plus a new pin that `(?m)^` is never claimed. Then the measurement
([benchmarks/2026-08-20-1736-6582e96cd9-engine.json](benchmarks/2026-08-20-1736-6582e96cd9-engine.json)):

| Workload | before MiB/s | after MiB/s | ratio |
|---|---:|---:|---:|
| `win_sec_xml` | 1.5 | **16.2** | **10.5×** |
| `ausearch` | 10.5 | 34.0 | 3.25× |
| `apache_httpd` | 21.2 | 29.4 | 1.38× |
| `regex_lines` | 67.6 | 74.6 | 1.10× |
| `csv_header` | 30.2 | 30.6 | 1.01× |
| `win_sec` | 5.5 | 5.5 | 0.99× |
| `progressive` | 47.8 | 46.1 | 0.96× |

The predicted order of magnitude arrived where it was predicted: `win_sec_xml` at 10.5×, and
the A/B now points the way D34 said it should — the anchored configuration beats its unanchored
sibling threefold instead of trailing it. Two bonuses were not predicted but are the same
mechanism: `ausearch` and `apache_httpd` carry provably start-anchored patterns of their own.

The controls behaved: `csv_header` (delimiter path, untouched) is flat at 1.01×, `win_sec`
(all `(?m)` patterns, correctly left unanchored) at 0.99×. `progressive` at 0.96× sits just
outside its error bar and is recorded as probable noise, with the flat csv row as evidence the
box was quiet. Compile cost is unchanged where it is measured meaningfully (the ms-scale rows,
0.98–1.08×); the two sub-microsecond compile rows moved but their baseline error bars were
30–40% and no claim rests on them.

**Next, from §2, in evidence order:** `win_sec` is now the slowest workload and its costs are
the ones this change could not touch — the 57-template pass over unanchored `(?m)` patterns,
and per-dispatch template filtering. Mode dispatch tables and reference strategies are the next
candidates, one measured change at a time.

## 7. Change 2, measured: the graph owns its dispatch indexes

One structural change (`d4935f1ddc`): `CompiledProject` computes per-mode and per-name dispatch
tables at construction; `apply()` and `call-template` read fields instead of filtering the
template list per call. Correctness gate unchanged (52/52, 242 tests). Measurement
([benchmarks/2026-08-20-1828-d4935f1ddc-engine.json](benchmarks/2026-08-20-1828-d4935f1ddc-engine.json)):

| Workload | before MiB/s | after MiB/s | ratio | cumulative vs baseline |
|---|---:|---:|---:|---:|
| `ausearch` | 34.0 | 40.0 | 1.18× | 3.8× |
| `regex_lines` | 74.6 | 85.1 | 1.14× | 1.26× |
| `apache_httpd` | 29.4 | 31.7 | 1.08× | 1.50× |
| `win_sec_xml` | 16.2 | 17.2 | 1.06× | 11.2× |
| `progressive` | 46.1 | 48.7 | 1.06× | 1.02× |
| `win_sec` | 5.5 | 5.7 | 1.04× | 1.04× |
| `csv_header` | 30.6 | 31.3 | 1.02× | 1.04× |

The modest prediction was the right one: 2–18% everywhere, no regressions. `regex_lines` at
1.14× is the informative surprise — it dispatches one mode per *line*, so the per-call filter
was a real cost even on the simplest workload. `progressive` swung 0.96× → 1.06× across the two
runs, netting ~flat; it is a slightly noisy workload and is read as such.

One honest cost, visible only where nothing else exists to hide it: index construction adds
~170ns to compile, which halves the *rate* of the two sub-microsecond compile rows
(`csv_header` 0.50×, `progressive` 0.63×) while the millisecond rows — the ones that matter —
are flat to better (0.97–1.10×). Milliseconds remain the compile budget and it is nowhere near
spent.

`win_sec` confirms its diagnosis by barely moving: its cost is the unanchored `(?m)` scans, not
dispatch bookkeeping. **Next: change 3 — reference strategies and pre-encoded literals** (the
`String.getBytes`-per-write class), aimed at `apache_httpd`'s heavy bodies, and the first step
of the compiled-body direction.

## 8. Change 3, measured: bodies compile

One structural change (`08879108ae`): `CompiledRef` and `CompiledOp` — pre-encoded literals,
classified references, transforms closed over their parameters, regex replaces holding their
`BytePattern`, applies knowing their whole-parent-content answer. Correctness gate unchanged
(52/52, 242 tests). Measurement
([benchmarks/2026-08-20-1853-08879108ae-engine.json](benchmarks/2026-08-20-1853-08879108ae-engine.json)):

| Workload | before MiB/s | after MiB/s | ratio | cumulative vs baseline |
|---|---:|---:|---:|---:|
| `regex_lines` | 85.1 | **111.8** | 1.31× | 1.65× |
| `progressive` | 48.7 | 56.9 | 1.17× | 1.19× |
| `csv_header` | 31.3 | 36.3 | 1.16× | 1.20× |
| `apache_httpd` | 31.7 | 35.5 | 1.12× | 1.67× |
| `ausearch` | 40.0 | 41.5 | 1.04× | 3.97× |
| `win_sec` | 5.7 | 5.8 | 1.02× | 1.06× |
| `win_sec_xml` | 17.2 | 16.8 | 0.98× | 10.97× |

Two honest corrections to the prediction. First, `apache_httpd` was named the biggest mover and
was not: its cost sits inside the transform *functions* — string-level replaces — not in
literal writes around them, so it gained 12% while literal-write-dominated `regex_lines` gained
31%. Second, `progressive` was named the control and moved 17%: its body writes literals too,
so it was never a control for this change. The workloads that genuinely could not benefit —
the two scan-dominated `win_sec` variants — sat at 0.98–1.02×, and serve as the quiet-box
evidence instead. `win_sec_xml`'s −2% is just outside its error bar and is recorded as noise
unless it recurs.

Compile: the millisecond rows are flat (0.96–1.00×); the sub-microsecond rows halve again
(`csv_header` now 0.28× of baseline — ~630ns absolute) because compilation now also builds the
ops. Cumulative compile cost for trivial configurations has tripled in nanoseconds; the budget
is milliseconds and remains untouched.

**The scoreboard after three changes:** every workload but one is at 35–112 MiB/s. The outlier
is `win_sec` at 5.8 — the unanchored `(?m)` pattern style scanning per dispatch — and the
remaining gap-list rows (step `Tag` pre-encoding, compiled conditions, capture copies) are
small next to it. The next decision is strategic rather than incremental: attack the
unanchored-scan cost itself, or price E13's buffer-spanning matches against this now-solid
baseline.

## 9. Change 4, measured: the engine forgets what anchoring is

The regex library now exits early for input-anchored patterns on its own parsed knowledge
([06-performance-plan.md §1, Done 2026-08-21](../stroom-shapeshifter-regex/design/06-performance-plan.md)), so change 1's
caller-side sniff retired: `CompiledMatch.Regex` lost its `anchoring` field, and the executor
asks the one honest question every time — DS3's own shape, restored on evidence. Correctness
gate unchanged (52/52, 242 tests). Measured against a fresh same-night baseline
(`2026-08-21-0211`, itself flat 0.97–1.01× against the change-3 run):

| Workload | before | after | ratio | cumulative vs day-1 baseline |
|---|---:|---:|---:|---:|
| `win_sec` | 22.7 | 23.7 | 1.05× | ~flat |
| `csv_header` / `progressive` / `apache_httpd` / `ausearch` | — | — | 0.99–1.01× | 1.2× / 1.2× / 1.7× / 4.0× |
| `win_sec_xml` | 68.2 | 66.7 | 0.98× | **10.9×** |
| `regex_lines` | 442.0 | 419.1 | 0.95× | 1.5× |

The honest asterisk: the two workloads whose patterns the sniff fast-pathed paid a whisker —
`regex_lines` −5% and `win_sec_xml` −2%, both just outside their error bars. The mechanism is
real and small: an unanchored search of an anchored pattern is now clamp + one attempt + the
search loop's scaffolding, where the anchored question was the attempt alone. That is the
price of each layer keeping its own job, it is bounded by the scaffolding of one empty loop,
and it bought the cases the sniff could never prove (anchored alternations, flag groups) plus
the deletion of caller-side pattern-text parsing. If a future measurement ever shows the
scaffolding mattering, the fix belongs in the library — a search that clamps to one position
could collapse to the anchored path internally — not in a returning sniff.

## 10. Change 5, measured: the anchoring fact returns, wearing the parser's signature

The user's actual requirement, stated once the trade-off had a price: publishing the fact is
fine so long as the library makes the determination — the objection was never to the fast
path, it was to a caller-side sniff that could disagree with the parser. So the library
publishes `BytePattern.leadingAnchor()` (its 06-performance-plan §1, Done 2026-08-21), read
from the same analysis its own search loops act on, and `CompiledMatch.Regex` consumes it:
INPUT asks the anchored question with its bare prologue, everything else asks the honest
search. Nothing engine-side reads pattern text. The sniff's refusals became gains — `^(a|b)`
and `(?s)^a` now take the fast path it denied them.

Measured against the retirement run (`2026-08-21-0718` vs `2026-08-21-0221`): `regex_lines`
1.05× and `win_sec_xml` 1.02× — the exact prologue change 4 knowingly paid, recovered to the
decimal (441.3 vs the pre-retirement 442.0; 68.2 vs 68.2) — with the other five workloads
flat at 0.99–1.01×. Cumulative from the day-one baseline: **`win_sec_xml` 11.1×**.

**The arc closes where D35 pointed, one seam clearer than before:** the model knows patterns
as text, the graph owns matchers as fields, and the regex library — alone — determines what
patterns mean, publishing the facts callers may act on. A fact crosses the seam only with the
parser's signature on it.

## 11. The strict-vs-lax A/B: first attempt contaminated, prediction under review

The first measurement of `win_sec_strict` against `win_sec`
([benchmarks/2026-08-21-1303-f0ded903af-engine.json](benchmarks/2026-08-21-1303-f0ded903af-engine.json))
ran on a saturated box — load average 25.9, untouched workloads drifting to 0.45–0.70× with
error bars ten times their usual width — and is checked in as **contaminated, no verdict**.
The clean rerun is owed before anything is concluded.

What survives contamination is the *same-run* relative reading, since both sides suffered
the same weather: **strict 1.4× over lax** (30.1 vs 21.2 ops/s). That is far from §1's
order-of-magnitude expectation, and the mechanism analysis says the expectation may simply
be stale: the prediction priced lax's failed searches at their pre-library-fix cost, but
this week's regex work (first-byte tables, anchor gates, the early exit) already cheapened
exactly those searches — the design's performance claim was partly *pre-paid* by the library.
Meanwhile strict pays ~57 anchored attempts per line — every template tried at every line
start, ~25ns each — which is the same shape of waste one level up: the level knows most
templates cannot match a line that starts with this byte, and asks them anyway.

That is a new §2-style row, recorded now so it is not rediscovered:

| Interpreted per use | The cost, concretely | The compiled answer |
|---|---|---|
| Strict/lexer dispatch tries **every template at every position** | ~57 anchored attempts per line in `win_sec_strict`, most refuted by the first byte | *Was:* a first-byte candidate table on the compiled level, needing the library to publish a pattern's first-byte set. *Corrected 2026-09-04:* the library can refute these itself — its scan plan's `ANCHORED` entry fills the slot array and enters the runner before its first op says no, with its own `firstBytes` table unread on that path (regex 07 Phase 5). One lookup there makes a refuted attempt a byte read, with nothing published and nothing engine-side; the candidate table returns to the list only if the per-call scaffolding is what remains after that |

**The clean rerun spoke (`2026-08-21-1323`, load 1.43, drift controls 0.96–0.97): strict is
1.36× over lax — 7.8 vs 5.7 MiB/s.** The first possibility was the truth: the library already
ate the feast. Lax's failed searches, priced at milliseconds when the design was written, now
cost first-byte-table-accelerated scans; strict's at-cursor attempts cost ~57 per line. The
order-of-magnitude claim in 11-strict-dispatch.md §1 is amended to the measured truth: strict
buys a solid third on this workload *today*, its real payload is semantic (no silent
skipping), and the next performance meal is the library's own anchored entry refuting on its
first byte before any setup (regex 07 Phase 5, corrected 2026-09-04 from the candidate table
first written here) — which makes strict's ~57 per-line attempts nearly free without the
engine learning anything about pattern shape, and is the only row on the list with a measured
workload waiting for it.

## 12. E13's price: 8% on one workload, convicted properly, mechanism still at large

The sliding window (E13) regressed `apache_httpd` and only `apache_httpd`. Convicted by the
strongest evidence this box can produce — a same-hour, same-box A/B of HEAD against the
pre-E13 commit (`2026-08-21-1443` vs `-1446`): apache 125.1 vs 136.2 (0.92×) with the
`csv_header` control at 1.00×. Two theories eliminated on the way: it is not the sliding
mechanics (`regex_lines` and `ausearch` share the same 20 KB window, slide through *more*
refill cycles, and are flat), and it is not the duplicated winner-processing (deduplicated
into one shared `processMatch` — kept as hygiene — with no recovery). What distinguishes
apache is body-heaviness, which suggests profile shape around the shared body path, but that
is a suspicion, not a finding.

Recorded as an open diagnosis rather than papered over: the next honest step is a profiler
diff (perfasm or async-profiler) of the apache workload either side of the E13 commit, which
needs tooling this session did not reach for. The trade as it stands: correctness that
cannot be configured around — records no longer fail by stream position — for 8% on one
workload, with six others flat.
