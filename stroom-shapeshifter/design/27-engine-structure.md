# Design 27 — The engine's structure: the executor dissolves, the passes separate, the module reads as one

*Proposed and ruled 2026-09-05 (D45), every ruling as recommended. Amended the same day, on the
user's question whether the plan locates code into packages as well as classes: §1.5, §2.5 to
§2.7 and phase 5 are the amendment; rulings 6 and 7 were made the same day, as recommended.* The
engine is correct, pinned at three levels, and its
biggest class is a 2,166-line interpreter that Checkstyle warns about on every build. This design
says what the module should look like when every class has one purpose, what moves where to get
there, and how each move is gated so that nothing about the engine's behaviour changes on the way.
It is structure only: no semantics move, no output changes, every golden stays byte-identical.

## 1. What is there today

Measured 2026-09-05, main sources only:

| Package | Files | Lines | Largest |
|---|---|---|---|
| `engine` (root) | 11 | 1,531 | `XmlByteSink` 443, `SaxEventSink` 330 |
| `engine.exec` | 17 | 5,447 | **`Executor` 2,166**, `Steps` 596, `Transforms` 542 |
| `engine.compile` | 9 | 2,439 | **`Compiler` 1,302**, `CompiledOp` 621 |
| `engine.config` | 19 | 2,284 | `OutputNode` 799 |
| `engine.config.json` | 2 | 1,859 | **`ProjectJson` 1,835** |
| `engine.ds3` | 5 | 1,226 | `Ds3Migration` 540 |
| `engine.function` | 12 | 550 | — |
| `engine.text` | 4 | 651 | `Encoding` 355 |

Three files carry the weight, and they have the same shape: several passes or regions that the
author separated with banner comments rather than classes. Everything else in the module is
already a collaborator with one purpose and a javadoc that says it — `Steps`, `Transforms`,
`Splitter`, `Codecs`, `Conditions`, `Store`, `VarRegistry`, `Comparisons`, `Numbers`, `Dates` —
which is the shape the three big files should be brought to, not a new shape.

### 1.1 `Executor`, by region

The class has four banner regions. Counting which fields each region touches says how separable
they are:

| Region | Lines | What it is | Fields it reaches |
|---|---|---|---|
| Header and functions | 1–264 | construction, `bindFunctions`, the `FunctionContext` the run hands to functions, `AbortRun` | `services`, `library`, `functionState`, `notRunInPreview`, `callOffset`, `callLength`, `records`, `messages` |
| The stream | 264–469 | the design 23 window loop: fill, compact, blank tail, exhaustion probe, root split around the apply-templates | `compiled`, `output`, `messages`, `encoding`, `chunkedRoot` |
| One level against one region | 469–1157 | `level`, `anyLevel`, `classify`, `processMatch`, `processEater`, `match`, `regexMatch`, `bindCaptures` | `instrument` (12 uses), `messages` (11), `vars` (9), `encoding` |
| Captures and body | 1157–2141 | `body` — the switch over every `CompiledOp` — and its handlers: emit, transform, call, fold, for-each, grouping, distinct, sort, key index, variable, call-template, apply | `vars` (74 uses), `keyIndexes`, `warnedNumeric`, `library`, `mode` |
| Input | 2141–2166 | `locate`, `read` | none |

`vars` is used 74 times in the body region and 9 times outside it; `instrument` 12 times in
dispatch and twice elsewhere; the function-runtime fields are used nowhere but the header and
the one `call` handler. The seams are where the banners already are.

### 1.2 `Compiler`, by pass

`compile()` runs, in order: encoding resolution; per template, the eater and encoding checks,
pattern interning, match compilation, body compilation (which already lives in
`CompiledOp.compile`); then `resolveTemplateNames`; then `bodyChecks`, which runs two inner
classes — `Structure` (150 lines: attributes after content, structure inside attribute values)
and `BodyScan` (590 lines: every reference read, every name written, sequences, keys, the
iteration-only and group-only warnings, the version gate on substring starts, E37). The passes
share nothing but the project and the warnings list.

### 1.3 `ProjectJson`, by family

One reader and one writer per model type, in one file: project and source (150 lines), match
expressions and steps and predicates and character sets (400), captures and references (120),
conditions and operands (260), output nodes (550, the largest pair), directives and small parts
(120), and the shared field helpers (130). Each family is closed over its own types; the file is
one because it started as one.

### 1.4 The packages, measured

The first draft of this design left the packages alone. Measured, three things are wrong with
that.

**`exec` holds two kinds of thing.** After the class split it would hold the run machinery —
`Run`, `Level`, `Body`, the window, the function runtime, the registry, the stores — and the
libraries a body calls: transforms, dates, numbers, comparisons, codecs, the splitter, the step
interpreter. Twenty-one files with no line between the machinery and the vocabulary. The
dependencies among them, counted 2026-09-05, already draw the line:

| Group | Classes | Depends on |
|---|---|---|
| Values | `TypedValue`, `Numbers`, `Transforms`, `Dates`, `Comparisons` | each other only (`TypedValue` and `Numbers` are mutual), `config.Cast`, the regex library |
| Matching | `MatchResult`, `Steps`, `Splitter`, `Codecs` | values; `config` step types; `compile.PatternKey`; the regex library |
| The run | `Executor`, `Conditions`, `Refs`, `CompiledRefs`, `Store`, `VarRegistry`, `EngineVars` | matching and values |

Nothing in the values group reaches the run; nothing in matching reaches the run. The groups
are layers already, unnamed.

**The root package mixes the facade with the sinks.** `Shapeshifter`, `Message`, `Severity`,
`Instrument`, `OutputSink` and `PatternInfo` are the module's face and everything imports them.
`XmlByteSink`, `SaxEventSink`, `CharacterSink` and `Utf8` are the output side's
implementations; nothing inside the engine uses them — their users are the pipeline module,
once each, and the tests.

**Two reference resolvers are live.** `CompiledRefs` resolves compiled references for bodies,
design 10's third change. `Refs` still resolves the *authored* `RefExpression` at run time for
conditions and for capture binding — the row design 10 §2 left half open ("a `matches`
condition still looks its pattern up by text"). Both have the same two ways in, write and
resolve, with the same rules. That is a duplication with a reason, and the plan has to say
which way it goes rather than carry both into `Body` and call it structure.

### 1.5 What the designs already decided

- **D35, two layers, never three.** The `Project` and the `CompiledProject` are the only
  artefacts. Its stated consequence: "`Executor` is transitional and dissolves into the graph as
  compilation deepens." Design 10 §2 says the same in more words: "performs the execution" is the
  compiled object's job description. So the direction is settled; this design is the plan for it.
- **What D35 forbids is a third artefact** — a cache, a factory tree, a shared-immutable middle,
  a per-run mirror graph. It does not forbid the run having state of its own: the window, the
  registry, the messages, the sink. That state exists today as the executor's fields; it will
  exist afterwards as the fields of whatever owns a run. Naming that owner is not adding a layer.
- **Design 10's open rows** (compiled conditions and guards, step pre-encoding, capture
  elimination) are performance work that shape follows, "not the other way round". This design
  does not do them. It does leave the graph in a shape where each is a local change.
- **The code standard** (2026-08-21): blank-page Java, no port residue, keep performance and
  issue rationale in comments, lint rules are the standard. The review in §4 holds the result to
  exactly that.

## 2. The target shape

### 2.1 `exec` after the split

| Class | Purpose | Comes from |
|---|---|---|
| `InputWindow` | The sliding window over the stream: fill, compact, blank the tail, probe exhaustion with one byte of pushback, `locate`. Owns the byte array and the cursor; knows nothing about templates. | `stream`'s window handling, `fill`, `fillAndBlankTail`, `compact`, `probeExhausted`, `read`, `locate` |
| `FunctionRuntime` | The bound function library for one run: binding at start, the `FunctionContext` each function sees, the preview gate, per-call offset and length, the shared state map, the service lookup. | header region, `Context`, `bindFunctions`, `describe`, the preview branch of `call` |
| `Level` | Dispatching one level's templates against one region: iterated ordered choice, strict and lexer and classify and any dispatch, skipping reported, eaters, match limits, instrument hooks, capture binding. | the "one level against one region" region |
| `Body` | Running a template's body: the `CompiledOp` switch and every handler, the variable registry, key indexes, the once-per-site warnings, casting, emit-or-bind. | the "captures and body" region |
| `Run` | One run of a compiled configuration over one input: owns the collaborators above and the message list, applies the root split, opens and closes the sink, turns `AbortRun` into the last message. What `Executor` was, at a size that says what it does. | `run`, `execute`, `RootSplit`, `applyDirective` |

`Executor` as a name goes. The facade `Shapeshifter.run(...)` is unchanged, so the pipeline
module and the app do not move.

**Where the state lives afterwards, against D35.** Matchers stay fields of `CompiledMatch`
nodes, as they are. The registry stays a run's, because a variable's store is per scope, not
per node — `call-template` pushes a scope and pops it — and a store that was a node field could
not be shadowed. Key indexes and warned sites are per run. None of this is a layer; it is what a
run is.

### 2.2 The ops: one interpreter, or ops that run themselves

D35's "dissolves into the graph" can be read two ways for the body:

1. **One interpreter over sealed records.** `Body` keeps the exhaustive `switch` over
   `CompiledOp`; the ops stay immutable data records that a `CompiledProject` holds and a run
   walks. This is what the compiler already produces and what every test pins.
2. **Ops that run themselves.** Each `CompiledOp` gains `run(Body)`; the switch disappears
   into polymorphism.

*Recommended: 1.* The sealed switch is the idiomatic modern Java for a closed vocabulary, the
compiler's own checks already walk the same vocabulary the same way, and it keeps the graph
shareable data with state owned where D35 puts it: matchers on match nodes, everything per-run
on the run. Option 2 would put run state — the registry, the sink, the messages — into every
op's signature or into the op, which is the per-run mirror D35 refuted. The graph "performs the
execution" through its `CompiledTemplate` and `CompiledProject` entry points; the ops are its
instruction set.

### 2.3 `compile` after the split

| Class | Purpose |
|---|---|
| `Compiler` | The pass pipeline: encoding, per-template compilation, name resolution, checks; builds the `CompiledProject`. Under 250 lines. |
| `MatchCompiler` | Pattern interning, step resolution and pre-encoding, `compileMatch`, the codec requirement, the not-yet refusals. |
| `StructureCheck` | Today's `Structure`: attributes and namespaces after content, structure inside attribute values, `producesContent`. |
| `ReferenceCheck` | Today's `BodyScan`: reads and writes, the unknown-reference refusal, sequences and keys, iteration and group hazards, the substring version gate, E37's document-template rules. |
| `CompiledOp` | The ops, with `compile(body)` staying beside them — it is the body's compilation and already lives here. |

### 2.4 `config.json` after the split

| Class | Purpose |
|---|---|
| `ProjectJson` | Project, source, dispatch, templates, parameters, limits, flags; the public `readProject`/`writeProject`; delegates the families below. |
| `MatchJson` | Match expressions, steps, step references, predicates, character sets. |
| `ReferenceJson` | Captures, capture sources, references, parts, match indexes. |
| `ConditionJson` | Conditions, comparisons, operands, casts. |
| `OutputJson` | Output nodes, sorts, branches, cases, entries, parameters, apply directives. |
| `JsonFields` | The shared helpers: tag and wrap, `checkFields`, required and optional fields, lists and arrays, enum constants. |

Reader and writer for one family stay together, because the round-trip pin (`EveryVariantTest`)
is the property that matters and it is easiest to keep when the two halves are in one place.

### 2.5 The packages after the split

| Package | Holds | Depends on |
|---|---|---|
| `engine` | The face: `Shapeshifter`, `Message`, `Severity`, `Instrument`, `OutputSink`, `PatternInfo` | — |
| `engine.output` | The sinks: `XmlByteSink`, `SaxEventSink`, `CharacterSink`, `Utf8` | `engine` |
| `engine.value` | `TypedValue`, `Numbers`, `Transforms`, `Dates`, `Comparisons` | `config.Cast`, regex |
| `engine.match` | `MatchResult`, `Steps`, `Splitter`, `Codecs` | `value`, `config`, `compile.PatternKey`, regex |
| `engine.exec` | The run: `Run`, `Level`, `Body`, `InputWindow`, `FunctionRuntime`, `Conditions`, `Refs`, `CompiledRefs`, `Store`, `VarRegistry`, `EngineVars` | `match`, `value`, `compile`, `function` |
| `engine.compile`, `engine.config`, `engine.config.json`, `engine.ds3`, `engine.text`, `engine.function` | as today, with the class splits of §2.3 and §2.4 | as today |

The dependency column is the layering §1.4 measured, now enforced by the package line: a value
knows nothing of a match, a match knows nothing of a run. `Conditions` stays with the run
because it resolves references against the registry. `MatchResult` goes with matching because
it is what a match produces and `Steps` and `Splitter` fill it; the run reads it.

**What it costs outside the engine.** The pipeline module imports `TypedValue` in thirty-four
places and each sink once; the app tests import the sinks. All of it is import churn, which is
why the moves are one phase of their own (phase 5) and not mixed into a hot-path commit.

### 2.6 What does not move

`config` (the model), `ds3` (the migration), `text` and `function` are the right size with the
right purpose, and stay. The regex module is another session's checkout and is not touched.
The classes in the value and matching groups move package and nothing else; the review in §4
covers their documentation, not their shape.

### 2.7 The two resolvers

`Refs` and `CompiledRefs` both stay through this design, because compiling conditions and
capture selects is design 10's open performance row and shape follows measurement there. What
changes is that the seam is stated: `Conditions` and `Level`'s capture binding resolve authored
expressions because their compilation is not yet measured to matter, and `CompiledRefs` says
so in its class javadoc, naming design 10 §2. The compile of conditions is filed as the
follow-on when phase 4 closes, so the duplication has an owner and an exit rather than a
shrug.

## 3. Phasing

Every phase is a pure move — no behaviour change — and every phase is gated the same way:
engine, pipeline and app-level Shapeshifter suites green; the fixture ledger unchanged; goldens
byte-identical; checkstyle clean with no `FileLength` warning at the end. Phases 2 and 3 touch the
hot path and add a benchmark gate (§3.9). Each phase is audited before the next, as designs 23
to 26 were.

### Phase 0 — The entry review

Before anything moves: a ledger, one row per class in `exec`, `compile` and `config.json`, with
its purpose in one sentence, its size, its documentation state (class javadoc says what and why;
each public method documented; no narration; no port residue), and any finding — a name that
means something only against the prototype, a comment that narrates, a helper nothing calls, a
duplicated rule. The ledger is design 15's shape. Findings that are fixable in a line are fixed in
this phase; the rest are assigned to the phase that touches the class. The package layout of
§2.5 is confirmed against the ledger — a class the ledger shows reaching across the line is
either moved to the right side or the line is redrawn, before anything else moves. *Output:*
§5 of this document filled in; the benchmark baseline of §3.9 taken.

### Phase 1 — `InputWindow` and `FunctionRuntime`

The two leaf regions, which reach nothing else. `InputWindow` takes the window array, the cursor
and the fill-compact-probe trio; `stream` calls it. `FunctionRuntime` takes binding, the context,
the preview gate and the call bookkeeping; `call` asks it. Around 400 lines leave `Executor`.
*Test:* `WindowTailTest` moves to `InputWindow`; `FunctionsTest` unchanged.

### Phase 2 — `Level`

The dispatch region becomes its own class, constructed per run with the instrument, the
messages and the registry it binds captures into. The recursion — a body's `apply` hands a region
to a nested level — goes through `Body`, so `Level` and `Body` reference each other through the
run that owns them. *Benchmark gate.*

### Phase 3 — `Body`

The interpreter region, with the registry, the key indexes and the warned sites as its fields.
`vars` stops being reachable from anything but `Body` and the capture binding in `Level`, which is
what the 74-to-9 count says it already is. *Benchmark gate.*

### Phase 4 — `Run`, and the name goes

What remains of `Executor` is construction, the root split, the transcode wrap, abort handling and
the message list: `Run`. `Executor.java` is deleted. Design 10's "transitional" sentence and D35's
consequence are updated to say it happened. The `exec` package javadoc is rewritten to name the
five classes and how a run flows through them.

### Phase 5 — The packages

The moves of §2.5: `value`, `match` and `output` created, their classes moved, the pipeline
module's and the app tests' imports updated, a `package-info` written for each new package that
says what it holds and what it may depend on. Pure import churn, in its own commit per package
so that a bisect lands on a package and not on a class. The `exec` package javadoc is written
last, when the package holds only the run. *Gate:* the three suites; no benchmark, because no
code moves within a class.

### Phase 6 — The compiler's passes

`MatchCompiler`, `StructureCheck`, `ReferenceCheck` out of `Compiler`. No test moves; the
compile-refusal tests already name messages, not classes.

### Phase 7 — The JSON families

`MatchJson`, `ReferenceJson`, `ConditionJson`, `OutputJson`, `JsonFields` out of `ProjectJson`.
`EveryVariantTest` is the gate, plus the corpus of fixtures read and written back.

### Phase 8 — The exit review and the documentation pass

The phase 0 ledger re-run against the result, adversarially: every class one purpose, every
class javadoc saying what and why, no method over a screen without a reason in its comment, no
narration, no residue. Package javadocs rewritten where the shape changed; the engine README's
architecture section updated; a D-number recorded. *Output:* §5 closed with the exit state.

### 3.9 The benchmark gate

Phases 2 and 3 move the hot path across class boundaries, which can change what the JIT inlines.
The gate is `EngineBenchmark` (the engine's `jmh` task, results under `design/benchmarks`), run
before and after on the same box with no other JMH session running (the shared-box rule),
targeted one-minute combinations by day and the full suite in the evening. The last engine
baseline on record is 2026-08-27, before the 2026-09-02 CPU replacement, so it is not comparable:
phase 0 takes a fresh baseline at the commit before phase 1, and that is what phases 2 and 3 are
measured against. The bar is no regression beyond the run-to-run noise of that baseline. A regression is investigated before the phase is called done;
it is not accepted as the price of structure.

### 3.10 Phase 0 preparation — the benchmark points, 2026-09-05

The last engine benchmark, 2026-08-27 at `45823464dc`, predates the CPU replacement, so the
phase 0 baseline needs company: the same harness at the commits that moved the paths it
measures, all on this box. The points, and why:

| Point | Commit | What moved |
|---|---|---|
| August anchor | `45823464dc` | the last recorded run; re-run here so the August history translates |
| Encoding plan complete | `75f6bbaa1c` | the decode path and the raw identity table (28 August) |
| Design 21 complete | `7ec991c2de` | the byte sink writes Saxon's bytes; structure in the model; the legacy fixtures rewritten |
| Design 23 complete | `52ce2309c5` | the window loop the streamed workloads run through |
| HEAD | `bf566871c0` | design 26's per-run function binding and record counter; E37's compile check |

*The box.* The user chose to leave the box's long-running processes up — two Stroom nodes,
their daemons, a hung test worker, a database container — so they are pinned to four cpus and
the benchmark runs on the other twelve. Nothing was stopped. The harness runs in a detached
worktree per point (`/home/dev1/bin/engine-bench-points.sh`), which must build the regex jar as
well as the engine's test classes or JMH dies at setup.

*The daytime look* — the `run` rows, one fork, two warmup and three measurement seconds, so the
99.9% intervals are wide (5 to 20%) and this is a glance, not a gate:

| workload | Aug 27 | enc. plan | design 21 | design 23 | HEAD | HEAD vs Aug 27 |
|---|---|---|---|---|---|---|
| regex_lines | 742.7 | 738.1 | 700.9 | 717.9 | 736.9 | −0.8% |
| csv_header | 207.0 | 210.9 | 211.4 | 200.7 | 208.2 | +0.6% |
| ausearch | 276.8 | 270.9 | 267.3 | 274.5 | 278.6 | +0.6% |
| apache_httpd | 187.1 | 190.1 | 180.9 | 182.4 | 186.8 | −0.2% |
| win_sec | 37.0 | 32.5 | 30.3 | 33.4 | 33.8 | −8.5% |
| win_sec_strict | 48.2 | 47.6 | 48.5 | 48.4 | 49.1 | +1.8% |
| win_sec_xml | 90.8 | 102.5 | 102.0 | 104.0 | 105.1 | +15.7% |
| progressive | 341.8 | 347.4 | 340.5 | 339.8 | 350.4 | +2.5% |

Two rows move outside their intervals. `win_sec_xml` gained 13% at the encoding plan and kept
it. `win_sec` may have lost 8% at the same point — it is inside the quick mode's interval, and
the encoding plan's gate is the regex module's `splitsCharacter` check, which the regex
session's probes were pricing on exactly this commit; the full run will say whether it is
real. Everything else is flat across 113 commits, which is what a structure design wants to
start from. The two legacy-derived rows (`regex_lines`, `csv_header`) benchmark the fixture
each commit had, and design 21 rewrote those fixtures, so they are honest per point and not
like for like across that boundary.

*The evening run* — the harness's own five forks at all five points, queued for 20:00 under
the shared-box policy, about seventy-five minutes — is the phase 0 baseline. Its HEAD row set
is what phases 2 and 3 are measured against.

## 4. The review's dimensions

From the code standard, applied per class in phases 0 and 8:

- **Purpose.** One sentence, and the class does that and nothing else — and the package it is
  in says which side of §2.5's lines it belongs to.
- **Correctness.** Nothing moved changes a golden, a message, a count.
- **Hygiene.** No dead vocabulary, no unused helpers, no duplicated rules, imports in order.
- **Javadoc.** Class: what it is and why it is shaped so. Public method: contract, not
  narration. Rationale comments for performance and pinned behaviour kept; history noise removed.
- **Clarity.** Names that mean something in this codebase without the prototype beside it.
- **Elegance.** Sealed switches over closed vocabularies; records for data, classes for owners
  of state; no flags that route control through a method that does two things.
- **Port residue.** Anything that only makes sense against the Rust origin.

## 5. The ledger

*Filled in by phase 0; closed by phase 8.*

## 6. Rulings — all seven ruled 2026-09-05, each as recommended (D45)

1. **The direction.** The executor dissolves into a run over the graph as §2.1 describes,
   rather than staying one class with helpers — D35's stated consequence, done.
2. **The ops.** One interpreter over sealed records, not ops that run themselves (§2.2).
3. **Scope.** All three big files: executor, compiler, JSON.
4. **The benchmark gate** as §3.9 states it: a fresh baseline in phase 0, and a regression
   beyond its noise blocks the phase rather than being accepted as the price of structure.
5. **The name.** `Run` for what remains of the executor.

*From the amendment:*

6. **The packages** of §2.5: `value`, `match` and `output` created, the dependency direction
   value ← match ← exec enforced by the package line, `Conditions` with the run,
   `MatchResult` with matching.
7. **The two resolvers** as §2.7 states: both stay, the seam is documented, the compile of
   conditions and capture selects is filed as the follow-on.

Each phase audited before the next.
