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

*Entry review 2026-09-05, three read-only passes, one per package group; closed by phase 8.*
Severity: **line** is fixed in phase 0; **P**n is assigned to that phase; **note** is recorded.

### 5.1 `config.json` and the root package's output side

| Class | Lines | Purpose | Javadoc | Findings |
|---|---|---|---|---|
| `ProjectJson` | 1,835 | Reads and writes the whole model as strict, explicitly shaped JSON, one reader and writer per type | good on what and why; class javadoc and four helpers carry port residue; five "phase N audit" tags | 22 |
| `json/package-info` | 24 | The serialiser seam the model does not depend on | good; stale after phase 7 | 1 |
| `OutputSink` | 140 | The interface every write goes through; structure calls resolved by the innermost open container | good; two methods undocumented | 4 |
| `XmlByteSink` | 443 | Serialises the sink's structure into bytes as Saxon would (D41, E35) | good | 5 |
| `SaxEventSink` | 330 | Forwards the structure as SAX events, counting events as position | good; one orphan javadoc | 4 |
| `CharacterSink` | 127 | A text configuration's writes as `characters` events, decoded across write boundaries (D42) | good | 2 |
| `Utf8` | 40 | How many trailing bytes begin an unfinished UTF-8 sequence | good | 2 |
| `Shapeshifter` | 126 | The facade: compile once, run many | thin on the six-argument `run` | 4 |
| `Message`, `Severity`, `Instrument`, `PatternInfo`, root `package-info` | 40, 30, 142, 85, 28 | the face | good; `PatternInfo` one tag and three inline FQNs; root `package-info` future tense, names no packages | 1, 0, 0, 3, 2 |

**`ProjectJson`** — *line:* 732 "Store" comment says "serde carries it as an alias and so must we"; keep the corpus fact (3,172 `Store` against 2,413 `capture`), drop the port. 790, 814, 884, 928 "(phase N audit)" provenance tags; the rules stay. 902 javadoc says "an ordering's cast" on `readCast`, which `min` and `max` also call. 962 and 1010 `readOperand`/`writeOperand` re-implement `readCast`/`writeCast` inline. 1087 the `try` around `Severity.valueOf` also encloses `readRef`, the width 884's own comment warns against. 474 `PatternRef` parses its id with raw `UUID.fromString`, escaping as `IllegalArgumentException` where `uuid()` gives a `ConfigException`. 857 `is-first`/`is-last` are the only payload-less variants read without `checkFields`. 1111, 1118 `has(x) && get(x).asBoolean()` where the file's idiom is `path(x).asBoolean(false)`. 1412 writes `"prefix": null` for a default namespace where everything else uses `putIfPresent`. 1642, 1690, 1698, 1811 four javadocs explain the wire shape by "serde" or "Rust's enum names"; the shape is this format's rule, state it so. *P7:* 65 "in one file" and the class javadoc's serde framing become `JsonFields`'s javadoc, correcting its claim that fields "stay snake_case throughout" (`with-param`, `omit-if-empty`, `key-value` are kebab). 867 to 919 `sequenceAndName`, `readSort`, `readCast` sit under the conditions banner and are output or shared. 141, 882, 909, 966, 1089 four hand-rolled lowercase-enum parsers with four message spellings, one `JsonFields` pair. 1032 `is-first` is written as `{}` where every other payload-less variant is written bare; read accepts both; decide under the round-trip gate. 459, 684, 413, 557 `asInt()` on an unchecked body relies on Jackson's coercion; verify before calling it a bug. *note:* 1261 `distinct-values` requires `name` on read and writes it optionally, benign. 1755 `expectObject` has one caller.

**Sinks** — *line:* `XmlByteSink` 248 to 259 `incompleteTail` is a byte-for-byte copy of `Utf8.incompleteTail`; the other two sinks call `Utf8`'s. `SaxEventSink` 298 an orphaned copy of `Utf8`'s javadoc sits on the `Element` class. `XmlByteSink` 84 to 91 and `SaxEventSink` 60 public constructors undocumented. `OutputSink` 120 to 126 `endAttribute` and `endElement` undocumented. *P5:* `XmlByteSink` 193, `SaxEventSink` 159, `CharacterSink` 61 the carry-splice-decode sequence is written three times; `SaxEventSink` 265 and `CharacterSink` 114 `SaxCall`/`sax()` duplicated verbatim; `XmlByteSink.prefixOf` (381) and `SaxEventSink.localOf` (256) are halves of one qname rule. Each is named here so phase 5 leaves them alone inside a move commit and a follow-on owns them. *note:* `XmlByteSink` and `SaxEventSink` duplicate `Element`, `Attribute`, `current`, `checkNoAttributeOpen`, "the same bookkeeping" by their own admission; a follow-on after phase 5. `CharacterSink` 31 names the pipeline's `TextWriter` from inside the engine. `Utf8` shares its simple name with the regex module's.

**The face** — *line:* `Shapeshifter` 59 to 70 the six-argument `run` has no `@return` and no memory-bound statement. `Message` 38 and `PatternInfo` 58, 69, 73 inline FQNs; `PatternInfo` 62 a provenance tag. Root `package-info` 24 says the pipeline adapter "will sit above this", future tense; 19 a lone `<p>`. *P4:* `Shapeshifter` 113 to 125 no `runWhole` takes a mode or services, so a whole-buffer run cannot call functions that need them; 69, 101, 124 a positional boolean routes whole-or-stream into the executor. *P5:* the root `package-info` must name the packages and the direction between them.

**The method map for phase 7.** Cross-family calls form a DAG: project to match, reference, condition and output; condition to reference; output to reference and condition; match and reference to themselves. `ProjectJson` can delegate downward. Four placements in §2.4 are corrected by the evidence: `RegexFlags` (273 to 287) is read and written only by the match family, so `MatchJson`; `readCast`/`writeCast` (903 to 919) are called only from the output family, so `JsonFields`, subsumed by the lowercase-enum pair; `CombinatorPattern` (168 to 182), unplaced, is a named step list, so `MatchJson`; `readDispatch`/`writeDispatch` (134 to 151) serve both the source and the apply directive, so `JsonFields`. `JsonFields` otherwise holds `NODES`, `Tagged` and `tag`, `wrap`, `checkFields`, `required`, `text`, `optionalText`, `uuid`, `putIfPresent`, `list`, `array`, `constant`, `name`. §2.4's word "parameters" names two types, `Template.ParamDecl` in `ProjectJson` and `OutputNode.Param` in `OutputJson`.

**The sinks' dependencies.** `engine.output` would depend on `engine` for `OutputSink`, the JDK and `org.xml.sax`, and on nothing else in the engine or the regex module. But the claim in §1.4 that nothing inside the engine uses the four classes is **false**: `OutputSink.of` (root, 128 to 131) constructs `XmlByteSink`, and `Executor.variable` (1996) runs every `variable` body through `OutputSink.of(buffer)`. With the sinks in `engine.output` implementing `engine.OutputSink`, an `of` that stays in the root is a package cycle. Import churn outside the engine: pipeline `EventImage` and `ShapeshifterReader`; five test classes; about thirty test call sites of `OutputSink.of`.

### 5.3 `compile`

| Class | Lines | Purpose | Javadoc | Findings |
|---|---|---|---|---|
| `Compiler` | 1,302 | Runs the passes from `Project` to `CompiledProject`, and today *is* four of them plus a dispatch lint and name resolution | good on why, thin on shape; inner classes narrate | 26 |
| `CompiledOp` | 621 | The instruction vocabulary, with `compile(body)` beside it | good; two records undocumented; two methods narrate | 12 |
| `CompiledProject` | 177 | The executable graph with its dispatch indexes | good; constructor contract incomplete; one accessor doc wrong | 5 |
| `CompiledMatch`, `CompiledRef`, `CompiledTemplate`, `Functions`, `PatternKey`, `package-info` | 127, 85, 44, 30, 29, 24 | as named | good, good, good, good, narrates, good | 3, 1, 0, 1, 2, 0 |

**`Compiler`** — *correctness:* 148 to 170 the E29 refusal is unreachable by construction (the source is forced to UTF-8 when it transcodes, unavailable labels are refused at 1297, every remaining encoding either lowers or is refused at 124), so twenty dead lines run a throw-away `resolve` and `steps` walk per progressive template; 158 picks its "offending" pattern from `HashMap` order. 1144 `intern` drops `regex.flags()` on a progressive regex step and `PatternKey` ignores flags, so `case_insensitive` or `dot_all` on a step is read, written back and silently ignored at match time (ruling 9). *line:* 1122 FQN `EnumSet`/`Flag` though imported; 1289 `encoding(String)` is public with no outside caller; 953 `"__rec_"` duplicated as a literal here and at `Executor` 2110 and 2118; 481 to 490 a `switch` over sealed `CaptureSource` ends in `default`; 626 to 630 two consecutive comments say the same thing; 181 to 185, 1272 to 1277, 205 to 209, 393 to 399, 825 to 829 narrate bug history and dates (the rationale in each is one clause and stays); 374 to 386 `BodyScan`'s javadoc says "the three checks" for a class carrying a dozen rules. *P6:* 155, 186, 1252 `resolve` runs three times per progressive template; 947, 989 `collectApplies` walks every body twice and 1004 to 1049 `collectCalls`/`collectApplies` are one walker with two leaves, repeating what `BodyScan` records at 538; 203 "in one walk (E27)" is false in aggregate, eight walks now; 951 `Dispatch.effective` applied twice per apply; 148 to 1141 nine FQN `regex.Encoding` because `text.Encoding` holds the import; 247 to 282 `new boolean[1]` as an out-cell is a borrowed `&mut`; 65, 82, 96 a static pipeline threading `patterns` and a mutable `Functions` through every signature. *note:* 1185 "refers to itself" names a UUID where the pattern has a name (message text is a golden); 312 to 370 `producesContent`'s forty `name() == null` arms recur in `BodyScan.visit` and `CompiledOp.compile`, three places per new instruction, because the model has no "binds a name" sub-interface (a sealed sub-interface is not a third layer).

**`CompiledOp`** — *line:* 28 to 29 `RefPart` imported twice; 281 `regexEncoding` is never read (`replace` hardcodes UTF-8 at 593); 452 FQN `Comparisons` and `Cast.DATE` with `Cast` imported; 144, 148 `Attribute` and `Namespace` undocumented; 57, 266, 564 to 570 narration ("as before", "has been waiting on", "which this used to allow"). *P6:* 124 and 165 `OutputNode.CallTemplate` compiles to `CompiledOp.Call` while `OutputNode.Call` compiles to `CallFunction`, the one name that flips meaning across the seam. *note:* 534 a string decides the arity rule inside a helper doing two things; 279 a static method on a public interface with a package-private type in its signature; 593 to 597 the "Pattern was not compiled" throw is the contract with the interning pass.

**`CompiledProject`** — *line:* 108 to 123 FQN `OutputNode` twelve times; 63 to 73 constructor javadoc omits two parameters; 168 `encoding()` says "declared" where it is the encoding the feed is matched in. *P6:* 88, 108 to 131 `carriesStructure` is a body walk over the authored templates in the graph's constructor, a second copy of `StructureCheck.producesContent`'s knowledge. **`CompiledMatch`** — *line:* 109 `Progressive` has no `List.copyOf` and receives a mutable list; 94 to 97 `Delimiter`'s nullable components undocumented. **`Functions`** — *P6:* 28 a record whose `used` map is a mutable accumulator written across every template. **`PatternKey`** — *line:* 21 to 26 narrates the deferral history; one sentence does it.

**The pass map for phase 6.** Encoding resolution needs the project. Per-template compilation needs `encoding`, `transcodeFrom`, the project and two mutable accumulators, `patterns` (written by interning, read by the same template's body compilation, ordered only by a comment at 171 and the throw at `CompiledOp` 596) and `functions.used()` (written by every body compilation, read at 199). Name resolution (980) needs the project and throws before any warning. **A pass the design did not list:** `dispatchChecks` (942 to 969) reads the *compiled* templates' leading-anchor facts and appends warnings, so "the passes share nothing but the project and the warnings list" is false; it needs a home. **The two checks are not sequential passes:** `bodyChecks` (215 to 222) zips `BodyScan.template` and `Structure.check` per template, and the zip decides which error a doubly faulty configuration reports; extracting them as two passes changes that unless `Compiler.bodyChecks` keeps the zip. **"Under 250 lines" does not close** with the E3 block, `bodyChecks`, `dispatchChecks`, name resolution and both walkers all in `Compiler`; it closes if the walkers merge. `CompiledOp.compile` calls no `Compiler` helper; its seam is the two accumulators.

### 5.4 `exec`

| Class | Lines | Purpose | Javadoc | Findings |
|---|---|---|---|---|
| `Executor` | 2,166 | Window, dispatch, body, function runtime and messages, in one class | good on most methods; narrates in twelve places; two contracts stale | 31 |
| `Steps` | 596 | Progressive step matching over bytes | good; class javadoc carries port residue | 5 |
| `Transforms` | 542 | The pure value functions | good; narrates twice | 6 |
| `Dates` | 317 | `parse-date` and `format-date`, compile-time construction and run-time use | good | 3 |
| `Refs`, `CompiledRefs` | 254, 161 | Authored and compiled reference resolution | good; `CompiledRefs` thin, the §2.7 seam not yet stated | 1, 2 |
| `TypedValue`, `Splitter`, `Codecs`, `Conditions`, `Comparisons`, `Numbers`, `VarRegistry`, `Store`, `EngineVars`, `MatchResult`, `package-info` | 249, 216, 209, 151, 123, 109, 108, 93, 78, 51, 24 | as named | good but `Numbers` names a test that does not exist and `EngineVars` narrates; `package-info` names the class that goes | 3, 1, 1, 4, 0, 2, 1, 0, 2, 1, 1 |

**`Executor`, correctness** — 848 `records++` happens only in `stream()`; the whole-buffer path (351 to 369, `Shapeshifter.runWhole`) and the chunked classify/any root never increment it, so `recordNumber()` reads 0 there (P1, the loop's owner). 1404 `EmitError` resolves its message with the run's `encoding` where every sibling passes `contentEncoding`, wrong under a template override (line). 1175 `CaptureSource.Field` binds nothing, silently: read by `ProjectJson` 686, refused nowhere, the silent no-op D33 refuses for codecs (ruling 10). 1471 `callOffset = inputBase + match.matchStart()` bypasses `locate()`, so a function sees `UNLOCATABLE + n` where the contract promises `UNLOCATABLE` (line). 706 and 163 two stale contracts: the full-window case is FATAL, not a warning, and whole-buffer never reaches `stream()` (line). 1084 `hasContent` classifies whitespace by ASCII byte under any encoding (note).

**`Executor`, hygiene and shape** — *line:* 1765 an unused `store` and a repeated emptiness check; 1396, 1475, 115 to 124, 1364, 1701, 1706, 1769, 1884 FQNs with the imports present; 68, 698, 935, 182 "restoring", "restored", "ported for", "Phase 6" residue. *P1:* 355 to 361 and 731 to 738 byte-order-mark detection written twice; 1436 to 1486 `call(CallFunction)` is Body at 1442 to 1469 and 1485 and FunctionRuntime at 1444 to 1450 and 1470 to 1484, the seam `FunctionRuntime.invoke(name, arguments, offset, length)`. *P2:* 489, 617, 1033, 1081 `level` returns a count nobody reads; the guard loop is written four times (518, 741, 958, 1054), the winner loop twice, the max-match skip, the zero-advance error, the min-match report and the unmatched-content report three times each, the content-group selection three times; 1064 to 1079 `classify` re-implements `processMatch`'s branch; 1165 to 1190 the capture-source switch mixes value arms with a side-effecting `KeyValue` arm then special-cases it again. *P3:* 2109 to 2118 a dead branch and a runtime sniff of `"__rec_"`, a name the compiler minted at `Compiler` 954, where `CompiledOp.Apply` should carry `recursive`; two methods named `call`, two named `preview`, and `index()` beside `Refs.index` and `Numbers.index`. *P8:* 109, 194, 1370, 1556, 1576 to 1585, 1749, 1825 to 1831, 1940 audit-history narration whose rules stay. *note:* 523, 746, 964, 1055 `MatchResult.empty()` allocates per template per level with `nothing` already in hand.

**The collaborators** — *line:* `Steps` 144 a mis-indented parameter, 427 a static field between methods; `Transforms` 52 to 61 `translate` returns empty where every sibling returns null against the class's own rule, 138, 272, 291, 404 FQNs; `Dates` 181 a dead `NumberFormatException` arm, 137 `patternHasYear` package-private with no outside caller; `TypedValue` 198 "the way Rust does" for a rule §16.8 owns, 37, 42, 84, 225 FQNs; `CompiledRefs` 68, 114, 157 `default` arms over the sealed `CompiledRef`, the exhaustiveness §2.2 chose; `Conditions` 115, 123 FQNs; `Numbers` 34 names `NumbersEquivalenceTest`, which is `TypedValueParseEquivalenceTest`; `VarRegistry` 71 `entry` re-implements `get`; `EngineVars` 63, 72, 76 FQNs. *P8:* `Steps` 47 to 54 "the Rust engine's behaviour, ported as-is" for the no-backtracking rule; `Transforms` 132, 163 to 169, 423 and `Conditions` 94, `Numbers` 27, `EngineVars` 24, `TypedValue` 143, 232 narration and origin. *P4:* `Transforms` 31 and `package-info` 20 name the executor. *note:* `Steps` 136 and `Splitter` 139 thread nine and eleven parameters, a per-match context is a later change; `Steps` 252 and `Conditions` 80 throw the same invariant twice; `Conditions` 83 allocates a matcher per evaluation, design 10's open row; `Refs` 64 and 146 the same per-part rule in two loops.

**The method map for phases 1 to 4.** Every method of `Executor` was placed. The placements that differ from §2.1: `stream` (697 to 872) is **one method with two halves**, InputWindow at 724 to 738, 783 to 803 and 818 to 838, Level at 741 to 777, 805 to 816 and 840 to 871; `locate` has no window state and every caller is Level; `structure` (1958) and `AbortRun` (267) are shared by Run, Body and FunctionRuntime and had no row; `bindCaptures` (1160) sits under the body banner and is Level's. Cross-boundary reaches §1.1 did not count: Body writes `messages` at eight sites and reads `compiled` at five, `chunkedRoot` at 1595 and `encoding` at 1404; the stream increments `records` at 848; Level and Body are mutually recursive (669, 692, 1076 down; 2127 up), so the run must wire the cycle. §1.1's "The stream, 264 to 469" row is mislabelled: those lines are `AbortRun`, `execute`, `run`, `applyDirective`, `RootSplit`, which is `Run`'s material; the window loop is 697 to 932 under the level banner. Phase 1 therefore cuts the level region, and phase 2 folds `stream`'s Level half into `level`, which is also where the seven duplicated rules go. Two `Executor.run` overloads are live and both are called by the facade; `Run` keeps one and the facade passes the defaults.

**Packages, confirmed.** Every exec-internal reference was checked; the sixteen assignments in §2.5 hold and nothing in the value or matching group reaches the run. The dependency cells need `engine.text` for `match` (`Steps` imports `text.Encoding` and `text.RegexEncodings`) and `exec` (`Transcode`, `Encoding`), and `config.ConfigException` for `value` (`Dates`, whose `compileParser`/`compileFormatter` are compile-time entry points called from `CompiledOp`). Tests move with their classes: `DatesTest`, `TransformsTest`, `TypedValueTest`, `TypedValueParseEquivalenceTest` to `value`, `StepsTest` to `match`; `CompareSpineTest`, `CompiledOp` and `Compiler` carry a further eleven imports §2.5's cost paragraph did not count.

### 5.5 What the entry review corrects in the plan

1. **`OutputSink.of` and the variable body** (ruling 8 wanted). Three exits: (a) `of` moves to `XmlByteSink` as its factory and the executor and the test sites construct the sink by name; (b) `exec` gets its own buffer sink for variable bodies, which also asks why a variable's text is serialised with Saxon's indenting layout at all; (c) the cycle is accepted. *Recommended: (a) now, as the smallest; (b)'s question filed as a follow-on, since it is a behaviour question and not this design's.*
2. **§2.5's `engine` row "depends on nothing"** is wrong as written: the facade imports `compile`, `config`, `exec` and `function`, and `PatternInfo` imports the regex module. The row should say that nothing below the root depends on it except through `OutputSink`, `Instrument`, `Message` and `Severity`.
3. **§2.4's placements** of flags, casts, the combinator pattern and dispatch, as above.
4. **§2.6 "move and nothing else"** stands for the sinks, with the three duplications above recorded as a follow-on so the move commit stays a move.
5. **§1.1's rows** are corrected as §5.4 says: the "stream" row was `Run`'s material, the window loop lives under the level banner, and `stream` is one method with two halves. Phase 1 lifts the window half; phase 2 folds the level half into `level` with the duplicated rules.
6. **§2.1's table** gains `locate` under `Level`, and `structure` and `AbortRun` under `Run` as package-visible; `Level` and `Body` are wired by the run, `Body` taking `Level` and `Level` taking the run's body callback.
7. **§1.2 and §2.3** gain the dispatch lint as a pass that runs after match compilation and before the body checks, in `Compiler`; `bodyChecks` keeps its per-template zip of the two checks so the error a doubly faulty configuration reports does not change; the E29 dead block is deleted in phase 6 with its rationale kept as one sentence; `carriesStructure` moves out of the graph's constructor into `StructureCheck`'s walk; the two walkers merge, which is what brings `Compiler` under its line target.
8. **§2.5's dependency cells** gain `engine.text` for `match` and `exec`, and `config` for `value`; the `engine` row says what §5.5 item 2 says.
9. **Two correctness defects** are fixed before phase 1 rather than carried: `EmitError`'s encoding and the unlocatable call offset (both one line). `records` under whole-buffer and chunked roots is phase 1's, with the loop. The step-regex flags and the `field` capture source are rulings 9 and 10.

## 6. Rulings — 1 to 7 ruled 2026-09-05, each as recommended (D45); 8 to 10 wanted

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

*Wanted, from the entry review (§5.5):*

8. **`OutputSink.of`.** The factory moves to `XmlByteSink`, the executor's variable body and
   the test sites construct the sink by name, and the question of why a variable's text takes
   Saxon's layout is filed as a follow-on. *Recommended: yes.*
9. **Regex flags on a progressive step.** They are read, written back and silently ignored at
   match time because the pattern key is text and encoding only. Either `PatternKey` gains the
   flags and `intern` compiles with them, or the reader refuses flags on a step. *Recommended:
   the key gains the flags — it is what the configuration says, and a refusal would invent a
   limitation the model does not have. Done in phase 6 with `MatchCompiler`, pinned first.*
10. **The `field` capture source.** Read by the JSON, compiled by nothing, bound to nothing,
    silently. *Recommended: a compile-time "not yet" refusal now, the shape D33 gives codecs,
    unless a corpus fixture uses it, which the gate will say.*

Each phase audited before the next.
