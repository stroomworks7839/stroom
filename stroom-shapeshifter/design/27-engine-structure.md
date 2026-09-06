# Design 27 — The engine's structure: the executor dissolves, the passes separate, the module reads as one

*Proposed and ruled 2026-09-05 (D45), every ruling as recommended. Amended the same day, on the
user's question whether the plan locates code into packages as well as classes: §1.5, §2.5 to
§2.7 and phase 6 are the amendment; rulings 6 and 7 were made the same day, as recommended.* The
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
| The run | 264–469 | `execute`, `run`, the root split around the apply-templates, `AbortRun` (the banner says "the stream"; the entry review corrected the row) | `compiled`, `output`, `messages`, `encoding`, `chunkedRoot` |
| One level against one region, and the window | 469–1157 | `level`, `anyLevel`, `classify`, `processMatch`, `processEater`, `match`, `regexMatch`, `bindCaptures`; and `stream`, the design 23 window loop, one method with a window half and a level half, with `fill`, `compact`, `fillAndBlankTail`, `probeExhausted` beside it | `instrument` (12 uses), `messages` (11), `vars` (9), `encoding`, `records` |
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
| `InputWindow` | The sliding window over the stream: fill, compact, blank the tail, probe exhaustion with one byte of pushback, the byte-order mark read once. Owns the byte array and the cursor; knows nothing about templates. | `stream`'s window half, `fill`, `fillAndBlankTail`, `compact`, `probeExhausted`, `read`, the two byte-order-mark sites |
| `FunctionRuntime` | The bound function library for one run: binding at start, the `FunctionContext` each function sees, the preview gate, per-call offset and length, the shared state map, the service lookup. | header region, `Context`, `bindFunctions`, `describe`, the preview branch of `call` |
| `Level` | Dispatching one level's templates against one region, and the root level against the window: iterated ordered choice, strict and lexer and classify and any dispatch, skipping reported, eaters, match limits, instrument hooks, capture binding, `locate`. `stream` stays a sibling loop of `dispatch` — its refill decisions are the window's — and the seven rules the two duplicated are one method each. | the "one level against one region" region, `stream`'s level half, `bindCaptures`, `locate` |
| `Body` | Running a template's body: the `CompiledOp` switch and every handler, the variable registry, key indexes, the once-per-site warnings, casting, emit-or-bind. | the "captures and body" region |
| `Run` | One run of a compiled configuration over one input: owns the collaborators above and the message list, settles the byte-order mark, applies the root split, wires `Level` and `Body` to each other, turns `AbortRun` into the last message (`AbortRun` package-visible; the structure rule lives on the body, which the run calls for the elements it opens). Two entry points, `stream` and `whole`, each taking the mode and the services; the facade passes the defaults. What `Executor` was, at a size that says what it does. | `run`, `execute`, `RootSplit`, `applyDirective`, `applyMark`, `AbortRun` |

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
| `Compiler` | The pass pipeline: encoding, per-template refusals and compilation, then the uses walk, name resolution and the dispatch lint through `TemplateUses`, then the body checks, zipped per template as today so the error a doubly faulty configuration reports does not change; builds the `CompiledProject`, with `structured` computed by the structure check rather than in the graph's constructor. 203 lines as built. |
| `TemplateUses` | What one template's body refers to, the templates it calls and the applies it makes, collected in one walk per body, and the two checks that read it: every name must exist, and the dispatch lint must know which modes are strict. |
| `MatchCompiler` | Pattern interning (with a step's flags in the key, ruling 9), step resolution, once, and pre-encoding, `compileMatch`, the codec requirement; owns `patterns` as an instance. The not-yet refusal is `ConfigException.notYet`, since a capture refusal uses it too. The E29 block that cannot fire is deleted, its rationale one sentence on `RegexEncodings.forMatch`. 278 lines. |
| `Containers` | Which instructions hold bodies, said once and exhaustively; the two walks that look for something anywhere inside a body — patterns to intern, templates referred to — recurse through it, so neither can stop short of an iteration again. 116 lines. |
| `StructureCheck` | Today's `Structure`: attributes and namespaces after content, structure inside attribute values, `producesContent`. |
| `ReferenceCheck` | Today's `BodyScan`: reads and writes, the unknown-reference refusal, sequences and keys, iteration and group hazards, the substring version gate, E37's document-template rules. |
| `CompiledOp` | The ops, with `compile(body)` staying beside them — it is the body's compilation and already lives here. |

### 2.4 `config.json` after the split

| Class | Purpose |
|---|---|
| `ProjectJson` | Project, source, templates, template parameter declarations, match limits; the public `readProject`/`writeProject`; delegates the families below. |
| `MatchJson` | Match expressions, regex flags, the combinator pattern library, steps, step references, predicates, character sets. |
| `ReferenceJson` | Captures, capture sources, references, parts, match indexes. |
| `ConditionJson` | Conditions, comparisons, operands. |
| `OutputJson` | Output nodes, sorts, branches, cases, entries, `with-param` parameters, apply directives. |
| `JsonFields` | The shared helpers: tag and wrap, `checkFields`, required and optional fields, ids, lists and arrays, PascalCase constants, and one lowercase-enum pair that dispatch, casts, orders and severities all use. |

Reader and writer for one family stay together, because the round-trip pin (`EveryVariantTest`)
is the property that matters and it is easiest to keep when the two halves are in one place.

### 2.5 The packages after the split

| Package | Holds | Depends on |
|---|---|---|
| `engine` | The face: `Shapeshifter`, `Message`, `Severity`, `Instrument`, `OutputSink`, `PatternInfo` | the facade fronts `compile`, `config`, `exec` and `function`, and `PatternInfo` the regex module; nothing below depends on the root except through `OutputSink`, `Instrument`, `Message` and `Severity`, and the sink factory moves to `XmlByteSink` (ruling 8) so that stays true |
| `engine.output` | The sinks: `XmlByteSink`, `SaxEventSink`, `CharacterSink`, `Utf8` | `engine` (for `OutputSink`), the JDK's `org.xml.sax` |
| `engine.value` | `TypedValue`, `Numbers`, `Transforms`, `Dates`, `Comparisons` | `config` (`Cast`, `ConfigException`), regex |
| `engine.match` | `MatchResult`, `Steps`, `Splitter`, `Codecs` | `value`, `config`, `compile.PatternKey`, `text`, regex |
| `engine.exec` | The run: `Run`, `Level`, `Body`, `InputWindow`, `FunctionRuntime`, `Conditions`, `Refs`, `CompiledRefs`, `Store`, `VarRegistry`, `EngineVars` | `match`, `value`, `compile`, `function`, `text`, `output` (the variable body's buffer sink, until its follow-on) |
| `engine.compile`, `engine.config`, `engine.config.json`, `engine.ds3`, `engine.text`, `engine.function` | as today, with the class splits of §2.3 and §2.4 | as today |

The dependency column is the layering §1.4 measured, now enforced by the package line: a value
knows nothing of a match, a match knows nothing of a run. `Conditions` stays with the run
because it resolves references against the registry. `MatchResult` goes with matching because
it is what a match produces and `Steps` and `Splitter` fill it; the run reads it.

**What it costs outside the engine.** The pipeline module imports `TypedValue` in thirty-four
places and each sink once; the app tests import the sinks; inside the engine, seven test
classes move with their classes and `CompareSpineTest`, `CompiledOp` and `Compiler` carry
eleven more imports. All of it is import churn, which is why the moves are one phase of their
own (phase 6) and not mixed into a hot-path commit.

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
so in its class javadoc, naming design 10 §2. The compile of conditions is filed as E39, so
the duplication has an owner and an exit rather than a shrug.

## 3. Phasing

The compiler goes first because phase 0 gave it a number and nothing else depends on its
shape; then the executor dissolves, then the packages move, then the JSON. Every phase is a
pure move — no behaviour change — and every phase is gated the same way:
engine, pipeline and app-level Shapeshifter suites green; the fixture ledger unchanged; goldens
byte-identical; checkstyle clean with no `FileLength` warning at the end. Phases 3 and 4 touch the
hot path and add a benchmark gate (§3.9). Each phase is audited before the next, as designs 23
to 26 were.

### Phase 0 — The entry review — Done 2026-09-05

*As built:* §5's ledger from three read-only passes, §5.5's corrections applied to the tables
above, §5.4's one-line fixes landed and gated, rulings 8 to 10 taken, and §3.10's baseline
taken at seven points. Audited in the doing by the gate: engine 555, pipeline 154, app 5.

*As written:*

Before anything moves: a ledger, one row per class in `exec`, `compile` and `config.json`, with
its purpose in one sentence, its size, its documentation state (class javadoc says what and why;
each public method documented; no narration; no port residue), and any finding — a name that
means something only against the prototype, a comment that narrates, a helper nothing calls, a
duplicated rule. The ledger is design 15's shape. Findings that are fixable in a line are fixed in
this phase; the rest are assigned to the phase that touches the class. The package layout of
§2.5 is confirmed against the ledger — a class the ledger shows reaching across the line is
either moved to the right side or the line is redrawn, before anything else moves. *Output:*
§5 of this document filled in; the benchmark baseline of §3.9 taken.

### Phase 1 — The compiler's passes — Done 2026-09-05

*As built:* `7bf64c5d90`. `Compiler` (309 lines) is the pipeline and nothing else: the source
encoding, then per template the two capture refusals, the E3 encoding rules, the match through
`MatchCompiler` and the body through `CompiledOp.compile`; then one walk collecting what each
body calls and applies to, read by name resolution and by the dispatch lint; then the body
checks, `ReferenceCheck`'s walk and `StructureCheck`'s zipped per template as before, and
`ReferenceCheck.report()` last. Every pass runs where it ran, so the error a doubly faulty
configuration reports and the order of its warnings are unchanged; the gate said so. `MatchCompiler`
(301) owns the interned patterns as state and resolves a progressive template's steps once,
where they were resolved three times; the E29 block that could not fire is gone and its
reason is one sentence on `RegexEncodings.forMatch`. `StructureCheck` (186) answers whether a
template writes structure from the walk it already makes, and the graph is handed the flag
instead of walking the authored bodies in its constructor. `ReferenceCheck` (604) is
`BodyScan` at top level, unchanged inside. Four passes, five walks per body — interning, body
compilation, the uses walk, the reference walk, the structure walk, the last two zipped at the
loop, not merged — where there were eight. A progressive regex step's flags are part of the pattern key
(ruling 9), and the pin was written first and failed on exactly the flag. The target of "under
250 lines" for `Compiler` did not close: the E3 encoding rules are forty lines of refusals with
their messages, and they belong to the pipeline, not to a pass. Engine 556, pipeline 154, app 5.

*The gate.* Five forks at `7bf64c5d90`, then at `b3c403b8cb` after the finding below, against
the phase 0 column (`design/benchmarks/2026-09-05-19*`). The run rows did not move: every row
inside both intervals. The compile rows: `csv_header` up 9 to 12%, the walks; every other row
inside its interval except `progressive`, down 5.5% and then 8.6%. The first reading was taken
for the new flag set on the pattern key — an enum set built and copied per key, and keys are
built per progressive regex step at match time as well — and the set became one of four shared
immutable values, which is right regardless. The second reading said that was not the cause,
so the row was probed: three forks with the allocation profiler at both commits give 3,000
bytes per compile at each, identical, and 3.09 against 3.02 million ops/s, inside each other's
intervals. The phase 0 column's 3.27 million for the same baseline commit is therefore that
row's run-to-run spread, not a difference between commits: a 300-nanosecond operation whose
99.9% interval within one run understates its variance across runs by a factor of two or
three. The phase passes the gate on that evidence, and the bar for that row from here is the
probe's spread, not the column's interval. The probe outputs are in
`/home/dev1/engine-bench/probe-*-progressive-compile.json`.

*Audited 2026-09-05.* Eight claims tested against the old compiler side by side, all held:
the pass order, the per-template order and which error wins, the deleted block provably dead,
the structure flag equivalent to the walk it replaced, the uses walk equal to the two it
replaced, `ReferenceCheck` two hunks from `BodyScan`, every pattern key built the same way on
the compile side and the lookup side. **Fixed:** two javadocs stale on the key's shape; the
not-yet refusal lived on the match side and served the capture refusal too, so it is a
`ConfigException` factory now; and the uses walk moved out to `TemplateUses` with its two
readers, which brings `Compiler` under the target after all. **Fixed, with a
pin:** the two walks the uses walk replaced never descended a `for-each` or `for-each-group`
body, so a `call-template` to a missing name inside an iteration was never refused at compile
time; the walk descends them now. **Named and assigned:** a UTF-16 byte-order mark on a source
declared UTF-8 moves the run's encoding to UTF-16 (`Executor` 360, 737), for which the regex
library has no lowering, so a progressive regex step then throws "Pattern was not compiled" at
match time — the compile-time refusal's proof does not reach the run's byte-order-mark rule;
that is the window's, and phase 2 owns it. Five FQN `regex.Encoding` remain in `MatchCompiler`
because `text.Encoding` holds the import; the ledger's note stands. Engine 557, pipeline 154,
app 5.

*Audited a second time, 2026-09-05, independently, on the final commit.* The first audit's
own changes held as a pure move plus the two arms and three renames, the not-yet message is
byte-identical to what the tests pin, and the design's claims held except two, corrected
above: the walks are five, not four, and the line count was one out. **Found, the same gap a
third time:** the interning walk had the same container list as the two the uses walk
replaced, so a regex `replace` or a `matches` condition inside an iteration's body was never
interned — the replace failed at compile time with an internal exception, the condition at run
time on the first record. Three walks, three private lists of what holds a body, each with a
`default` arm that could not tell a leaf from a forgotten container; that is a class of bug,
and it is fixed as one: `Containers.bodies` is the exhaustive statement, and the two searching
walks recurse through it. Pinned three ways — the replace and the condition inside an
iteration are interned, and a strict apply inside one feeds the dispatch lint, the new warning
the first audit's fix had produced without saying so. **Fixed besides:** `ConfigException`'s
class javadoc said a configuration exception means the document never became a project,
false for every compile-time refusal; the key's javadoc carried a number the gate had
withdrawn; the package javadoc and a parameter's wording caught up; the reference check's entry
point has its contract as javadoc. **Noted:** `Dispatch.effective` is still computed twice
per apply (ledger); `TemplateUses` as a record with static readers over a list is defensible
and stays; the `new boolean[1]` out-cell in `StructureCheck` is the ledger's phase 8 item.
Engine 560, pipeline 154, app 5. Final sizes: `Compiler` 203, `MatchCompiler` 278,
`TemplateUses` 127, `StructureCheck` 186, `ReferenceCheck` 604, `Containers` 116.

*The gate, on the final commit* (`design/benchmarks/2026-09-05-2048-03f6cac444-engine.json`,
against the phase 0 column). Run rows: every row inside both intervals; the XML workload's
−2.4% on the commit before was spread, at −0.8% here. Compile rows: `csv_header` +6.0%, the
walks' gain less the exhaustive helper's price, a one-element list per container node, which
is accepted because the exhaustiveness is what the audits were for; `progressive` −9.2%
against the column and −4% against the probe's reading of the same baseline, inside the
probe's spread; every other row inside its interval. Phase 1 passes its gate.

*As written:*
First, ahead of the executor, because the entry review gave it a number: the compile rows
drifted 9 to 24% over the history §3.10 measured, and the cause is the eight body walks and
the dead refusal block the compile ledger names, which merge only when the passes become
classes. `MatchCompiler`, `StructureCheck`, `ReferenceCheck` out of `Compiler`: interning,
body compilation and one check walk, the structure check zipped into it per template as today,
the dispatch lint reading the applies that walk collected rather than walking again, the E29
block deleted with its rationale kept as one sentence, a progressive template's steps resolved
once, `carriesStructure` computed by the structure check and handed to the graph, a step's
regex flags in the pattern key (ruling 9), pinned first. No test moves; the compile-refusal
tests already name messages, not classes. *Gate:* the three suites, and the benchmark's compile
rows before and after against the phase 0 column, with the run rows once to show they did not
move. Nothing in the executor phases depends on the compiler's shape; the two places they
touch — the recursive-apply flag phase 4 wants and the call-template op's rename — are one line
each on either side.

### Phase 2 — `InputWindow` and `FunctionRuntime` — Done 2026-09-05

*As built:* `InputWindow` (251 lines) owns the byte array, the live region, the fill level,
the end-of-input flag and the absolute count of bytes consumed; it opens over a stream by
filling once and reading the byte-order mark, and it answers the level's questions by name —
`canGrow`, `refill`, `edgeCanRecede`, `reachesEdge`, `probeExhausted`, `consume`,
`offsetOf` — where the loop had inlined the arithmetic. The whole-buffer and chunk-at-a-time
read, and the mark's detection on a chunk, are its statics too, so the two byte-order-mark
sites are one rule, `applyMark`, on the run. `FunctionRuntime` (183) owns the library, the
binding, the context a function sees, the preview gate, the call's offset and length, the
shared state map and the record count; the executor's `call` resolves arguments and emits,
and asks the runtime to `invoke`. `AbortRun` (29) is package-visible, thrown by four owners
and caught by one. `Executor` is 1,962 lines, under the warning for the first time, and its
`stream` keeps the level half for phase 3 to fold into `level`. **Two named fixes, pinned:**
the record count moved to where a top-level record begins, in `processMatch` and `classify`
at depth zero, so `recordNumber()` counts under a whole-buffer run and a chunked root, where
it had read zero; and a UTF-16 byte-order mark reaching the window on a source declared
otherwise is refused by name — declare the encoding on the source so the stream is transcoded
whole — where it had switched the run to an encoding the regex library cannot lower and let a
progressive step fail with an internal message. `WindowTailTest` calls the window. Engine 561,
pipeline 154, app 5.

*Audited 2026-09-05.* The stream loop, decision by decision against the old one — the
no-match refill, the receding edge, the full-window probe, the eater's and the counted match's
consumption, the offset in the zero-advance message, the tail report, the mark's skip — is
byte for byte equivalent, and the finals the loop now captures at the top of each iteration
are safe because every mutation of the window is followed by a `continue`. The streamed
root's record count is the same count at the same moment; the whole-buffer, classify and any
roots now count where they counted nothing. The function runtime's invoke, preview gate and
binding are the old code with the same catch order. No moved field is still referenced; every
import is used. **Fixed:** the two new count sites disagreed on when a record begins — the
match-processing one counted before the content check as the old loop had, the classify one
only when a body ran — and a record is now a top-level match in both; two window members
nothing called are gone; the array accessor states its contract, valid to the fill and
re-read after a refill; the encoding field's javadoc no longer says a mark is "better
evidence than a declaration", since a UTF-16 mark now refuses; the refusal says "declares no
encoding" for a source that declared none rather than "declared auto"; the call's javadoc
stopped restating the runtime's contract, which freed an import; the runtime takes the
definitions it binds rather than the whole graph. **Named:** the mark's skip bookkeeping is
still written at both read sites, the meaning being the one rule; the refusal pin proves no
output only because its prologue writes none, which is the same for every FATAL and is said
here so nobody reads more into it. Engine 561, pipeline 154, app 5.

*The benchmark*, run though the phase has no gate, because the stream loop now crosses a class
boundary (`design/benchmarks/2026-09-05-2249-d26a98505a-engine.json`): every run row inside
its interval against phase 1's final commit and against the phase 0 column; the compile rows
unchanged from phase 1, which touched nothing at compile time, except the progressive row's
−3.8% inside its own spread. The window's accessors cost the hot loop nothing measurable.

*As written:*
The two leaf regions, which reach nothing else. `InputWindow` takes the window array, the cursor
and the fill-compact-probe trio; `stream` calls it. `FunctionRuntime` takes binding, the context,
the preview gate and the call bookkeeping; `call` asks it. Around 400 lines leave `Executor`.
*Test:* `WindowTailTest` moves to `InputWindow`; `FunctionsTest` unchanged.

### Phase 3 — `Level` — Done 2026-09-06

*As built, in two commits so each is checkable.* First the pure move: the whole dispatch
region — `level`, `processMatch`, `processEater`, the stream loop's level half, `anyLevel`,
`classify`, `match`, `regexMatch`, `bindCaptures`, `effective`, `normalise`, `locate` — into
`Level`, constructed per run with the graph, the instrument, the messages, the registry, the
function runtime and a body callback the executor implements; the run's encoding in force is
handed in on every entry and held by the level for the duration, the same value each time. The executor opens the window and
applies the mark, then hands the window to the level's `stream`. The return values nobody
read are gone. Then the fold, inside `Level`: the guards evaluated once on the way in, three copies to
`guards`; the zero-advance error, three copies to `noProgress`, which takes whether the offset
is within the content or absolute; the minimum-match and unmatched-content reports, three
copies to `report`; the content-group selection, three copies to `content`; and a wanted
match's instrumented body run, two copies to `runBody`, which the classify mode now shares
with the ordered ones. The winner loop — the pass over the templates with the max-match skip
and the lexer's maximal munch — was folded to `pick` and then unfolded again by the gate (see
below): it is written in both loops, the one duplication the hottest path cannot afford. `anyLevel` keeps its
own loop because it excises and its zero-advance rule is not the ordered modes' (`end <=
start`, not `advance == 0`), which the fold preserves. `stream` stays a sibling loop of
`dispatch` rather than folding into it, as §2.1 first said: its refill decisions are the
window's and have no counterpart in a region, so what the two share is the six methods, not
a loop. `Executor` is 1,296 lines: the run, the
root split, the mark rule, and the body. Every message is byte for byte what it was; the
gate said so.

*The gate.* Five readings, all in `design/benchmarks/2026-09-0[56]-*`. The fold's first
reading against phase 2 had `ausearch` −6.2% and `win_sec_strict` −2.4% outside their
intervals; the winner record `pick` returned across a method the JIT does not inline was the
first suspect and became a field, which changed nothing: a second reading of the fold had
`ausearch` back inside and `win_sec_strict` still −3%, and the field version had
`regex_lines` −3.5% and `win_sec_strict` −3.1%. So the change was attributed: the pure move
alone (`9fd685800d`) reads `win_sec_strict` −2% and the one-line-record rows flat, and the fold
on top of it reads `regex_lines` −5% and `ausearch` −4%. A three-fork probe with the
allocation profiler at phase 2, the move and the fold gives 5,562,251, 5,562,365 and
5,562,387 bytes per operation on `regex_lines` — identical — and the JIT's inlining log
shows the folded loop inlined *more* (`pick` and `processMatch` where the old big methods
were refused): the cost was the compiled shape of the hot loop, not a call or an allocation.
Two reversals (`6f7603619c`): the winner loop is written inline in both loops again, and the
run implements the level's body callback itself so no lambda frame sits between a match and
its body. Reading: against the phase 0 column every run row is inside its interval —
`regex_lines` +2.4%, `ausearch` −1.1%, `win_sec_strict` −1.0%, `win_sec_xml` +0.6% — and
against phase 2 `regex_lines` is +3.1%, `ausearch` inside, `win_sec_strict` −1.4%, a whisper
outside phase 2's interval and the interface dispatch's likely residue, which phase 4's `Body`
removes when the callback becomes a class. Compile rows unchanged but for the progressive
row's known spread. Phase 3 passes its gate on the baseline; the lesson is written on the
loop: a per-pass method on this path costs what the JIT decides, and the gate, not the
structure, decides whether it stays.

*Audited 2026-09-06.* The move is token-identical apart from the licensed changes — the
callback, the dropped returns, the encoding parameter, the open and mark moved to the run —
so every message and every instrument call is what it was; the fold reproduces each of its
copies byte for byte, the lexer's last-candidate case included, and the stream loop never
dereferences a null winner. **Fixed:** the level's class javadoc claimed one method every
mode shares for what only the consuming modes share; `stream`'s javadoc lacked its
parameters; the executor's still named a method that had gone; this design said `stream`
folds into `dispatch`, which it does not, and that the encoding is not held as a field, which
it is. **Named, pre-existing, for a ruling (11):** the classify mode evaluates each template's
guard inline, after earlier templates' matches have set the engine's counters and bound
their captures — the mid-level re-read the `guards` rationale calls a mistake in the ordered
modes, and the fourth copy of the guard the ledger counted. Making it the once-on-the-way-in
rule is a behaviour change for a classify guard that reads an earlier template's capture,
so it is not this phase's to make. Engine 561, pipeline 154, app 5 (562 once ruling 11's pin
landed).

*As written:*
The dispatch region becomes its own class, constructed per run with the instrument, the
messages and the registry it binds captures into. The recursion — a body's `apply` hands a region
to a nested level — goes through `Body`, so `Level` and `Body` reference each other through the
run that owns them. *Benchmark gate.*

### Phase 4 — `Body` — Done 2026-09-06

*As built:* the whole body region — the switch over the instruction vocabulary and every
handler it reaches: conditions, text, function calls and their casting, transforms with their
once-per-site warnings, emit-or-bind, the sequence guards, entries and dense binding, folds
and extremes, the key index and grouping, sorting and distinct, iteration, structure calls,
variables, call-template and apply-templates — moved verbatim into `Body` (1,044 lines) with
the state it owns: the variable registry, the key indexes, the warned arithmetic sites and the
chunked-root flag. `Body` implements the level's callback directly, as the run did after phase
3's gate, so the callback costs what it cost. The run wires the two: the body first, the level
with the body's registry and the body as its runner, then the body attached to the level, since
each needs the other and one must be first. The run tells the body the encoding in force and
whether the root is chunked, asks it to register the configuration's captures, and runs the
document template's prologue and tail through it; the structure rule — a sink call the sink
refuses becomes the run's last message — lives on the body and the run calls it for the
elements it opens around the loop. `Executor` is 346 lines: the two entry points, the mark
rule, the root split, and the loop that hands the input to the level. Engine 562, as before.

*Audited 2026-09-06.* The move is byte-identical apart from the callback's visibility and
the structure rule's; the encoding is told to the body before any body could read it, and the
prologue runs with the declared encoding as it did; the wiring never leaves the level null.
**Fixed:** a dead `instrument` field on the run; the run's class javadoc, which still described
the window, the ordered choice and the body; two javadocs naming the executor as the body's or
the level's caller; the entry's javadoc, lost in the move; `registerCaptures` taking what the
body already holds. **And the callback interface is gone:** with the body a class, the
interface had one implementer, so the level holds the body and calls it directly — the
vocabulary was dead, and the interface dispatch was the residue phase 3 named on strict
dispatch. **Named:** `attach` remains the wiring for the cycle between body and level, a
field set once after construction, the smallest honest shape until `Run` builds both; the
ledger's phase 4 items — two methods named `call`, `index` beside `Refs.index`, the recursive
apply's name sniff, the variable body's buffer sink — carry to phases 5 and 8, this phase
having been the move.

*The gate* (`design/benchmarks/2026-09-06-08*`). The move alone: every run row inside its
interval against phase 3, and against the phase 0 column all but `win_sec_strict`, −1.2%, a
hair outside — the interface residue phase 3 named. The audited commit, with the interface
gone: every run row inside its interval against both — `win_sec_strict` 48.0 against the
column's 48.3, `csv_header` +3.4% and `regex_lines` −0.5% against it — and the compile rows
inside theirs but for `csv_header`'s standing gain and the progressive row's known spread.
Phase 4 passes its gate on the baseline, and the residue is gone.

*As written:*
The interpreter region, with the registry, the key indexes and the warned sites as its fields.
`vars` stops being reachable from anything but `Body` and the capture binding in `Level`, which is
what the 74-to-9 count says it already is. *Benchmark gate.*

### Phase 5 — `Run`, and the name goes — Done 2026-09-06

*As built:* `Executor` is `Run` (342 lines; 356 after the audit's lift) — one run of a compiled configuration over one
input — with two entry points, `stream` and `whole`, in place of a positional boolean on the
API, both taking the mode and the services (the boolean survives privately, decided once in
`dispatchInput`, which is the whole-or-chunk-or-window choice the root split sits around);
the facade's overloads pass the defaults or pass through, and it gains the whole-buffer form
that takes a mode and services, the gap the entry review found. The
`exec` package javadoc names the five classes and how a run flows through them, and says what
D35 said: the graph performs the execution, and the run and its collaborators are its state
for one input. `CompiledRefs`' class javadoc states the seam §2.7 asked for. Design 10's
"`Executor` is transitional" and D35's consequence now say it happened and when. Nothing on
the hot path changed, so no benchmark: the rename is a rename, and the entry points reach the
same private run. Engine 562, pipeline 154, app 5.

*Audited 2026-09-06.* Routing preserved overload by overload; the diff touches the entries
and nothing below them. **Fixed:** the whole-buffer entries' javadoc was thinner than the
stream entries'; six comments in six files still named the executor as a live actor; the
package javadoc ended in a fragment and omitted the value types and the abort; the input
dispatch — whole buffer, chunk at a time for the non-consuming roots, or the window — is one
method decided once rather than a flag threaded through two; §2.1's `Run` row said one entry
point and a sink the run opens, neither true; and ruling 7's follow-on, promised as filed,
was not — it is E39 now. **Named for phase 8:** design 23 §1 maps the facade to
`Executor.stream` as a live contract, and the regex module's `ByteMatcher` cites it too, the
other session's file. Engine 562, pipeline 154, app 5.

*Audited a second time, 2026-09-06, on the final commit.* The lift is statement for statement
and the routing overload for overload; the first audit's fixes hold. **Fixed:** the root
directive and mode were still named for the old stream loop, beside a public `stream` they
have nothing to do with; the two fields the run wires shared one javadoc; the facade spelt
its defaults twice in two styles; the whole-buffer preview path had no caller and now has a
pin; one present-tense "executor" survived in a test's javadoc; the design miscounted the
files and had the wrong phase against `Body`; E39 said `Refs` goes where its byte helper
must move; two rewrapped lines were wrap defects. **Noted:** `execute` keeps the old verb for
the catch-wrapper between the entries and the run, one name in a chain of four, which the
exit review may take up; the `exec` package javadoc names six classes phase 6 moves out and
will be rewritten last, as phase 6 says.

*As written:*
What remains of `Executor` is construction, the root split, the transcode wrap, abort handling and
the message list: `Run`. `Executor.java` is deleted. Design 10's "transitional" sentence and D35's
consequence are updated to say it happened. The `exec` package javadoc is rewritten to name the
five classes and how a run flows through them.

### Phase 6 — The packages

The moves of §2.5: `value`, `match` and `output` created, their classes moved, the pipeline
module's and the app tests' imports updated, a `package-info` written for each new package that
says what it holds and what it may depend on. Pure import churn, in its own commit per package
so that a bisect lands on a package and not on a class. The `exec` package javadoc is written
last, when the package holds only the run. *Gate:* the three suites; no benchmark, because no
code moves within a class.

### Phase 7 — The JSON families

`MatchJson`, `ReferenceJson`, `ConditionJson`, `OutputJson`, `JsonFields` out of `ProjectJson`.
`EveryVariantTest` is the gate, plus the corpus of fixtures read and written back.

### Phase 8 — The exit review and the documentation pass

The phase 0 ledger re-run against the result, adversarially: every class one purpose, every
class javadoc saying what and why, no method over a screen without a reason in its comment, no
narration, no residue. Package javadocs rewritten where the shape changed; the engine README's
architecture section updated; a D-number recorded. *Output:* §5 closed with the exit state.

### 3.9 The benchmark gate

Phases 3 and 4 move the hot path across class boundaries, which can change what the JIT inlines.
The gate is `EngineBenchmark` (the engine's `jmh` task, results under `design/benchmarks`), run
before and after on the same box with no other JMH session running (the shared-box rule),
targeted one-minute combinations by day and the full suite in the evening. The last engine
baseline on record is 2026-08-27, before the 2026-09-02 CPU replacement, so it is not comparable:
phase 0 takes a fresh baseline at the commit before phase 1, and that is what phases 3 and 4 are
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

*The full run* — the harness's own five forks, started at 16:59 on the user's word rather
than waiting for the evening, twelve minutes a point — at the five points and two more: the
merge of the regex session's branch (`23fecf3b05`, the library's RunLoop and the bound as a
`ByteMatcher` field again) and the phase 0 commit (`44bc1e5ac6`). The results are in
`design/benchmarks/2026-09-05-*-engine.json`. The run rows, ops/s, ± JMH's 99.9% interval:

| workload | Aug 27 | enc. plan | design 21 | design 23 | pre-merge | merge | phase 0 |
|---|---|---|---|---|---|---|---|
| regex_lines | 662.7 ± 117.8 | 719.8 ± 20.2 | 715.7 ± 12.7 | 727.8 ± 13.2 | 727.7 ± 18.0 | 719.2 ± 20.0 | 722.0 ± 13.3 |
| csv_header | 205.1 ± 3.4 | 208.0 ± 3.8 | 204.8 ± 3.7 | 196.4 ± 12.7 | 201.8 ± 3.8 | 207.8 ± 3.3 | 203.4 ± 3.4 |
| ausearch | 275.4 ± 5.1 | 265.5 ± 4.4 | 269.0 ± 6.3 | 266.2 ± 4.0 | 274.7 ± 2.4 | 263.0 ± 12.5 | 268.5 ± 3.0 |
| apache_httpd | 186.2 ± 5.9 | 186.8 ± 1.5 | 184.2 ± 2.2 | 182.4 ± 1.9 | 183.4 ± 2.6 | 181.1 ± 1.9 | 184.4 ± 3.3 |
| win_sec | 34.4 ± 1.6 | 33.0 ± 0.2 | 36.7 ± 1.3 | 34.3 ± 1.0 | 34.2 ± 1.0 | **41.0 ± 0.1** | 40.8 ± 0.4 |
| win_sec_strict | 49.1 ± 0.4 | 47.0 ± 0.4 | 48.7 ± 0.2 | 48.8 ± 0.3 | 47.6 ± 1.4 | 48.8 ± 0.2 | 48.3 ± 0.3 |
| win_sec_xml | 88.6 ± 2.8 | **101.7 ± 1.3** | 102.6 ± 0.8 | 102.6 ± 0.9 | 103.2 ± 1.2 | 102.4 ± 1.0 | 102.7 ± 1.3 |
| progressive | 331.3 ± 11.7 | 339.8 ± 4.1 | 343.8 ± 2.1 | 342.0 ± 5.7 | 342.3 ± 2.3 | 345.8 ± 1.6 | 350.8 ± 1.9 |

What it says. The engine's run path is flat across the 113 commits from August to the
pre-merge HEAD: every row inside its interval except `win_sec_xml`, up 16% at the encoding
plan and kept. The quick pass's `win_sec` scare was noise. The merge moved `win_sec` by 20%
(34.2 to 41.0), the library's RunLoop landing on the workload that scans a level of multiline
templates; nothing else in the merge moved a row. Phase 0's one-line fixes are flat against
the merge, every row inside both intervals except `progressive` by a whisker in the right
direction. The compile rows drifted down over the same history — `csv_header` −23%, the
progressive configuration −24%, `win_sec` −9% — in three steps, the encoding plan, design 21,
and designs 23 to 26 with E37: a microsecond on a once-per-load cost, which design 10 prices
as irrelevant, but the shape is the eight body walks and the dead refusal block the compile
ledger names, and phase 1 has the number to move.

**The baseline for phases 3 and 4 is the phase 0 column**, `2026-09-05-1815-44bc1e5ac6-engine.json`,
five forks on this box with the long-running processes pinned off its cores. The bar is the
interval in that column, row by row.

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

**Sinks** — *line:* `XmlByteSink` 248 to 259 `incompleteTail` is a byte-for-byte copy of `Utf8.incompleteTail`; the other two sinks call `Utf8`'s. `SaxEventSink` 298 an orphaned copy of `Utf8`'s javadoc sits on the `Element` class. `XmlByteSink` 84 to 91 and `SaxEventSink` 60 public constructors undocumented. `OutputSink` 120 to 126 `endAttribute` and `endElement` undocumented. *P6:* `XmlByteSink` 193, `SaxEventSink` 159, `CharacterSink` 61 the carry-splice-decode sequence is written three times; `SaxEventSink` 265 and `CharacterSink` 114 `SaxCall`/`sax()` duplicated verbatim; `XmlByteSink.prefixOf` (381) and `SaxEventSink.localOf` (256) are halves of one qname rule. Each is named here so phase 6 leaves them alone inside a move commit and a follow-on owns them. *note:* `XmlByteSink` and `SaxEventSink` duplicate `Element`, `Attribute`, `current`, `checkNoAttributeOpen`, "the same bookkeeping" by their own admission; a follow-on after phase 6. `CharacterSink` 31 names the pipeline's `TextWriter` from inside the engine. `Utf8` shares its simple name with the regex module's.

**The face** — *line:* `Shapeshifter` 59 to 70 the six-argument `run` has no `@return` and no memory-bound statement. `Message` 38 and `PatternInfo` 58, 69, 73 inline FQNs; `PatternInfo` 62 a provenance tag. Root `package-info` 24 says the pipeline adapter "will sit above this", future tense; 19 a lone `<p>`. *P5:* `Shapeshifter` 113 to 125 no `runWhole` takes a mode or services, so a whole-buffer run cannot call functions that need them; 69, 101, 124 a positional boolean routes whole-or-stream into the executor. *P6:* the root `package-info` must name the packages and the direction between them.

**The method map for phase 7.** Cross-family calls form a DAG: project to match, reference, condition and output; condition to reference; output to reference and condition; match and reference to themselves. `ProjectJson` can delegate downward. Four placements in §2.4 are corrected by the evidence: `RegexFlags` (273 to 287) is read and written only by the match family, so `MatchJson`; `readCast`/`writeCast` (903 to 919) are called only from the output family, so `JsonFields`, subsumed by the lowercase-enum pair; `CombinatorPattern` (168 to 182), unplaced, is a named step list, so `MatchJson`; `readDispatch`/`writeDispatch` (134 to 151) serve both the source and the apply directive, so `JsonFields`. `JsonFields` otherwise holds `NODES`, `Tagged` and `tag`, `wrap`, `checkFields`, `required`, `text`, `optionalText`, `uuid`, `putIfPresent`, `list`, `array`, `constant`, `name`. §2.4's word "parameters" names two types, `Template.ParamDecl` in `ProjectJson` and `OutputNode.Param` in `OutputJson`.

**The sinks' dependencies.** `engine.output` would depend on `engine` for `OutputSink`, the JDK and `org.xml.sax`, and on nothing else in the engine or the regex module. But the claim in §1.4 that nothing inside the engine uses the four classes is **false**: `OutputSink.of` (root, 128 to 131) constructs `XmlByteSink`, and `Executor.variable` (1996) runs every `variable` body through `OutputSink.of(buffer)`. With the sinks in `engine.output` implementing `engine.OutputSink`, an `of` that stays in the root is a package cycle. Import churn outside the engine: pipeline `EventImage` and `ShapeshifterReader`; five test classes; about thirty test call sites of `OutputSink.of`.

### 5.2 `compile`

| Class | Lines | Purpose | Javadoc | Findings |
|---|---|---|---|---|
| `Compiler` | 1,302 | Runs the passes from `Project` to `CompiledProject`, and today *is* four of them plus a dispatch lint and name resolution | good on why, thin on shape; inner classes narrate | 26 |
| `CompiledOp` | 621 | The instruction vocabulary, with `compile(body)` beside it | good; two records undocumented; two methods narrate | 12 |
| `CompiledProject` | 177 | The executable graph with its dispatch indexes | good; constructor contract incomplete; one accessor doc wrong | 5 |
| `CompiledMatch`, `CompiledRef`, `CompiledTemplate`, `Functions`, `PatternKey`, `package-info` | 127, 85, 44, 30, 29, 24 | as named | good, good, good, good, narrates, good | 3, 1, 0, 1, 2, 0 |

**`Compiler`** — *correctness:* 148 to 170 the E29 refusal is unreachable by construction (the source is forced to UTF-8 when it transcodes, unavailable labels are refused at 1297, every remaining encoding either lowers or is refused at 124), so twenty dead lines run a throw-away `resolve` and `steps` walk per progressive template; 158 picks its "offending" pattern from `HashMap` order. 1144 `intern` drops `regex.flags()` on a progressive regex step and `PatternKey` ignores flags, so `case_insensitive` or `dot_all` on a step is read, written back and silently ignored at match time (ruling 9). *line:* 1122 FQN `EnumSet`/`Flag` though imported; 1289 `encoding(String)` is public with no outside caller; 953 `"__rec_"` duplicated as a literal here and at `Executor` 2110 and 2118; 481 to 490 a `switch` over sealed `CaptureSource` ends in `default`; 626 to 630 two consecutive comments say the same thing; 181 to 185, 1272 to 1277, 205 to 209, 393 to 399, 825 to 829 narrate bug history and dates (the rationale in each is one clause and stays); 374 to 386 `BodyScan`'s javadoc says "the three checks" for a class carrying a dozen rules. *P1:* 155, 186, 1252 `resolve` runs three times per progressive template; 947, 989 `collectApplies` walks every body twice and 1004 to 1049 `collectCalls`/`collectApplies` are one walker with two leaves, repeating what `BodyScan` records at 538; 203 "in one walk (E27)" is false in aggregate, eight walks now; 951 `Dispatch.effective` applied twice per apply; 148 to 1141 nine FQN `regex.Encoding` because `text.Encoding` holds the import; 247 to 282 `new boolean[1]` as an out-cell is a borrowed `&mut`; 65, 82, 96 a static pipeline threading `patterns` and a mutable `Functions` through every signature. *note:* 1185 "refers to itself" names a UUID where the pattern has a name (message text is a golden); 312 to 370 `producesContent`'s forty `name() == null` arms recur in `BodyScan.visit` and `CompiledOp.compile`, three places per new instruction, because the model has no "binds a name" sub-interface (a sealed sub-interface is not a third layer).

**`CompiledOp`** — *line:* 28 to 29 `RefPart` imported twice; 281 `regexEncoding` is never read (`replace` hardcodes UTF-8 at 593); 452 FQN `Comparisons` and `Cast.DATE` with `Cast` imported; 144, 148 `Attribute` and `Namespace` undocumented; 57, 266, 564 to 570 narration ("as before", "has been waiting on", "which this used to allow"). *P1:* 124 and 165 `OutputNode.CallTemplate` compiles to `CompiledOp.Call` while `OutputNode.Call` compiles to `CallFunction`, the one name that flips meaning across the seam. *note:* 534 a string decides the arity rule inside a helper doing two things; 279 a static method on a public interface with a package-private type in its signature; 593 to 597 the "Pattern was not compiled" throw is the contract with the interning pass.

**`CompiledProject`** — *line:* 108 to 123 FQN `OutputNode` twelve times; 63 to 73 constructor javadoc omits two parameters; 168 `encoding()` says "declared" where it is the encoding the feed is matched in. *P1:* 88, 108 to 131 `carriesStructure` is a body walk over the authored templates in the graph's constructor, a second copy of `StructureCheck.producesContent`'s knowledge. **`CompiledMatch`** — *line:* 109 `Progressive` has no `List.copyOf` and receives a mutable list; 94 to 97 `Delimiter`'s nullable components undocumented. **`Functions`** — *P1:* 28 a record whose `used` map is a mutable accumulator written across every template. **`PatternKey`** — *line:* 21 to 26 narrates the deferral history; one sentence does it.

**The pass map for phase 1.** Encoding resolution needs the project. Per-template compilation needs `encoding`, `transcodeFrom`, the project and two mutable accumulators, `patterns` (written by interning, read by the same template's body compilation, ordered only by a comment at 171 and the throw at `CompiledOp` 596) and `functions.used()` (written by every body compilation, read at 199). Name resolution (980) needs the project and throws before any warning. **A pass the design did not list:** `dispatchChecks` (942 to 969) reads the *compiled* templates' leading-anchor facts and appends warnings, so "the passes share nothing but the project and the warnings list" is false; it needs a home. **The two checks are not sequential passes:** `bodyChecks` (215 to 222) zips `BodyScan.template` and `Structure.check` per template, and the zip decides which error a doubly faulty configuration reports; extracting them as two passes changes that unless `Compiler.bodyChecks` keeps the zip. **"Under 250 lines" does not close** with the E3 block, `bodyChecks`, `dispatchChecks`, name resolution and both walkers all in `Compiler`; it closes if the walkers merge. `CompiledOp.compile` calls no `Compiler` helper; its seam is the two accumulators.

### 5.3 `exec`

| Class | Lines | Purpose | Javadoc | Findings |
|---|---|---|---|---|
| `Executor` | 2,166 | Window, dispatch, body, function runtime and messages, in one class | good on most methods; narrates in twelve places; two contracts stale | 31 |
| `Steps` | 596 | Progressive step matching over bytes | good; class javadoc carries port residue | 5 |
| `Transforms` | 542 | The pure value functions | good; narrates twice | 6 |
| `Dates` | 317 | `parse-date` and `format-date`, compile-time construction and run-time use | good | 3 |
| `Refs`, `CompiledRefs` | 254, 161 | Authored and compiled reference resolution | good; `CompiledRefs` thin, the §2.7 seam not yet stated | 1, 2 |
| `TypedValue`, `Splitter`, `Codecs`, `Conditions`, `Comparisons`, `Numbers`, `VarRegistry`, `Store`, `EngineVars`, `MatchResult`, `package-info` | 249, 216, 209, 151, 123, 109, 108, 93, 78, 51, 24 | as named | good but `Numbers` names a test that does not exist and `EngineVars` narrates; `package-info` names the class that goes | 3, 1, 1, 4, 0, 2, 1, 0, 2, 1, 1 |

**`Executor`, correctness** — 848 `records++` happens only in `stream()`; the whole-buffer path (351 to 369, `Shapeshifter.runWhole`) and the chunked classify/any root never increment it, so `recordNumber()` reads 0 there (P2, the loop's owner). 1404 `EmitError` resolves its message with the run's `encoding` where every sibling passes `contentEncoding`, wrong under a template override (line). 1175 `CaptureSource.Field` binds nothing, silently: read by `ProjectJson` 686, refused nowhere, the silent no-op D33 refuses for codecs (ruling 10). 1471 `callOffset = inputBase + match.matchStart()` bypasses `locate()`, so a function sees `UNLOCATABLE + n` where the contract promises `UNLOCATABLE` (line). 706 and 163 two stale contracts: the full-window case is FATAL, not a warning, and whole-buffer never reaches `stream()` (line). 1084 `hasContent` classifies whitespace by ASCII byte under any encoding (note).

**`Executor`, hygiene and shape** — *line:* 1765 an unused `store` and a repeated emptiness check; 1396, 1475, 115 to 124, 1364, 1701, 1706, 1769, 1884 FQNs with the imports present; 68, 698, 935, 182 "restoring", "restored", "ported for", "Phase 6" residue. *P2:* 355 to 361 and 731 to 738 byte-order-mark detection written twice; 1436 to 1486 `call(CallFunction)` is Body at 1442 to 1469 and 1485 and FunctionRuntime at 1444 to 1450 and 1470 to 1484, the seam `FunctionRuntime.invoke(name, arguments, offset, length)`. *P3:* 489, 617, 1033, 1081 `level` returns a count nobody reads; the guard loop is written four times (518, 741, 958, 1054), the winner loop twice, the max-match skip, the zero-advance error, the min-match report and the unmatched-content report three times each, the content-group selection three times; 1064 to 1079 `classify` re-implements `processMatch`'s branch; 1165 to 1190 the capture-source switch mixes value arms with a side-effecting `KeyValue` arm then special-cases it again. *P4:* 2109 to 2118 a dead branch and a runtime sniff of `"__rec_"`, a name the compiler minted at `Compiler` 954, where `CompiledOp.Apply` should carry `recursive`; two methods named `call`, two named `preview`, and `index()` beside `Refs.index` and `Numbers.index`. *P8:* 109, 194, 1370, 1556, 1576 to 1585, 1749, 1825 to 1831, 1940 audit-history narration whose rules stay. *note:* 523, 746, 964, 1055 `MatchResult.empty()` allocates per template per level with `nothing` already in hand.

**The collaborators** — *line:* `Steps` 144 a mis-indented parameter, 427 a static field between methods; `Transforms` 52 to 61 `translate` returns empty where every sibling returns null against the class's own rule, 138, 272, 291, 404 FQNs; `Dates` 181 a dead `NumberFormatException` arm, 137 `patternHasYear` package-private with no outside caller; `TypedValue` 198 "the way Rust does" for a rule §16.8 owns, 37, 42, 84, 225 FQNs; `CompiledRefs` 68, 114, 157 `default` arms over the sealed `CompiledRef`, the exhaustiveness §2.2 chose; `Conditions` 115, 123 FQNs; `Numbers` 34 names `NumbersEquivalenceTest`, which is `TypedValueParseEquivalenceTest`; `VarRegistry` 71 `entry` re-implements `get`; `EngineVars` 63, 72, 76 FQNs. *P8:* `Steps` 47 to 54 "the Rust engine's behaviour, ported as-is" for the no-backtracking rule; `Transforms` 132, 163 to 169, 423 and `Conditions` 94, `Numbers` 27, `EngineVars` 24, `TypedValue` 143, 232 narration and origin. *P5:* `Transforms` 31 and `package-info` 20 name the executor. *note:* `Steps` 136 and `Splitter` 139 thread nine and eleven parameters, a per-match context is a later change; `Steps` 252 and `Conditions` 80 throw the same invariant twice; `Conditions` 83 allocates a matcher per evaluation, design 10's open row; `Refs` 64 and 146 the same per-part rule in two loops.

**The method map for phases 2 to 5.** Every method of `Executor` was placed. The placements that differ from §2.1: `stream` (697 to 872) is **one method with two halves**, InputWindow at 724 to 738, 783 to 803 and 818 to 838, Level at 741 to 777, 805 to 816 and 840 to 871; `locate` has no window state and every caller is Level; `structure` (1958) and `AbortRun` (267) are shared by Run, Body and FunctionRuntime and had no row; `bindCaptures` (1160) sits under the body banner and is Level's. Cross-boundary reaches §1.1 did not count: Body writes `messages` at eight sites and reads `compiled` at five, `chunkedRoot` at 1595 and `encoding` at 1404; the stream increments `records` at 848; Level and Body are mutually recursive (669, 692, 1076 down; 2127 up), so the run must wire the cycle. §1.1's "The stream, 264 to 469" row is mislabelled: those lines are `AbortRun`, `execute`, `run`, `applyDirective`, `RootSplit`, which is `Run`'s material; the window loop is 697 to 932 under the level banner. Phase 2 therefore cuts the level region, and phase 3 folds `stream`'s Level half into `level`, which is also where the seven duplicated rules go. Two `Executor.run` overloads are live and both are called by the facade; `Run` keeps one and the facade passes the defaults.

**Packages, confirmed.** Every exec-internal reference was checked; the sixteen assignments in §2.5 hold and nothing in the value or matching group reaches the run. The dependency cells need `engine.text` for `match` (`Steps` imports `text.Encoding` and `text.RegexEncodings`) and `exec` (`Transcode`, `Encoding`), and `config.ConfigException` for `value` (`Dates`, whose `compileParser`/`compileFormatter` are compile-time entry points called from `CompiledOp`). Tests move with their classes: `DatesTest`, `TransformsTest`, `TypedValueTest`, `TypedValueParseEquivalenceTest` to `value`, `StepsTest` to `match`; `CompareSpineTest`, `CompiledOp` and `Compiler` carry a further eleven imports §2.5's cost paragraph did not count.

### 5.4 Phase 0 as built, 2026-09-05

Every **line** finding in §5.1 to §5.3 is applied, in one commit, gated on the engine (555, one
new pin), pipeline and app suites and checkstyle. Of note in the doing: the two correctness
lines landed — `EmitError` resolves with the template's content encoding, and a function's
input offset goes through `locate()` so an unlocatable call reads `UNLOCATABLE` as the contract
says; the `field` capture source is refused by name in the compiler's per-template pass and
pinned in `StructureTest`, and no fixture used it; the recursive-mode name is minted once, on
`ApplyDirective`, and read by the compiler and the executor from there; `CompiledOp.compile`
lost the `regexEncoding` parameter nothing read; `CompiledRefs`' `default` arms became named
cases — Checkstyle refuses the unnamed pattern variable, which is the standard, so the two
combined labels became two cases each and the `value()` helper folded into a `lookup` of the
remote variable; `Compiler.encoding` is private; `Transforms.translate` answers null like its
siblings; every fully qualified name whose import was already present, or could be, is an
import; the narration the three reviews named is gone, and the rationale beside it is kept.
Nothing moved between classes; that is the phases.

### 5.5 What the entry review corrects in the plan

1. **`OutputSink.of` and the variable body** (ruling 8 wanted). Three exits: (a) `of` moves to `XmlByteSink` as its factory and the executor and the test sites construct the sink by name; (b) `exec` gets its own buffer sink for variable bodies, which also asks why a variable's text is serialised with Saxon's indenting layout at all; (c) the cycle is accepted. *Recommended: (a) now, as the smallest; (b)'s question filed as a follow-on, since it is a behaviour question and not this design's.*
2. **§2.5's `engine` row "depends on nothing"** is wrong as written: the facade imports `compile`, `config`, `exec` and `function`, and `PatternInfo` imports the regex module. The row should say that nothing below the root depends on it except through `OutputSink`, `Instrument`, `Message` and `Severity`.
3. **§2.4's placements** of flags, casts, the combinator pattern and dispatch, as above.
4. **§2.6 "move and nothing else"** stands for the sinks, with the three duplications above recorded as a follow-on so the move commit stays a move.
5. **§1.1's rows** are corrected as §5.3 says: the "stream" row was `Run`'s material, the window loop lives under the level banner, and `stream` is one method with two halves. Phase 2 lifts the window half; phase 3 folds the level half into `level` with the duplicated rules.
6. **§2.1's table** gains `locate` under `Level`, and `structure` and `AbortRun` under `Run` as package-visible; `Level` and `Body` are wired by the run, `Body` taking `Level` and `Level` taking the run's body callback.
7. **§1.2 and §2.3** gain the dispatch lint as a pass that runs after match compilation and before the body checks, in `Compiler`; `bodyChecks` keeps its per-template zip of the two checks so the error a doubly faulty configuration reports does not change; the E29 dead block is deleted in phase 1 with its rationale kept as one sentence; `carriesStructure` moves out of the graph's constructor into `StructureCheck`'s walk; the two walkers merge, which is what brings `Compiler` under its line target.
8. **§2.5's dependency cells** gain `engine.text` for `match` and `exec`, and `config` for `value`; the `engine` row says what item 2 says.
9. **Two correctness defects** are fixed before phase 1 rather than carried: `EmitError`'s encoding and the unlocatable call offset (both one line). `records` under whole-buffer and chunked roots is phase 2's, with the loop. The step-regex flags and the `field` capture source are rulings 9 and 10.

## 6. Rulings — all eleven ruled, each as recommended (D45)

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

*From the entry review (§5.5):*

8. **`OutputSink.of`.** The factory moves to `XmlByteSink`, the executor's variable body and
   the test sites construct the sink by name, and the question of why a variable's text takes
   Saxon's layout is filed as a follow-on.
9. **Regex flags on a progressive step.** They are read, written back and silently ignored at
   match time because the pattern key is text and encoding only. Either `PatternKey` gains the
   flags and `intern` compiles with them, or the reader refuses flags on a step. Ruled: the key
   gains the flags, in phase 1 with `MatchCompiler`, pinned first.
10. **The `field` capture source.** Read by the JSON, compiled by nothing, bound to nothing,
    silently. Ruled: a compile-time "not yet" refusal now, the shape D33 gives codecs, unless a
    corpus fixture uses it, which the gate will say.

*From the phase 3 audit, ruled 2026-09-06:*

11. **The classify mode's guards.** Evaluated per template inline, after earlier templates'
    matches have set `__match_index` and `__match_count` and bound their captures, where the
    ordered modes evaluate every guard once on the way in for the reason the `guards` javadoc
    gives. Ruled: the once-on-the-way-in rule for classify too, pinned — DS3's classify has no
    guards at all, so nothing migrated relies on the re-read, and a native configuration whose
    classify guard reads a sibling's capture is reading a coincidence of list order.

Each phase audited before the next.
