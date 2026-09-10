# Design 30 — The compiled model holds references, not names

*Proposed 2026-09-09, from design 29 phase 4's measurement, which went looking at conditions and
found something larger underneath them. Engine only. Touches no golden and no configuration:
every change here replaces a lookup with the thing it was looking up.*

*Three of its four maps are closed as of the same day (§9). The fourth — the variable store — is
off the table by ruling until it is counted properly, and §5 is what that counting is for.*

## 1. Where it stands

Design 29 moved decisions from run time to compile time and left one shape untouched, because
its survey had not seen it: a compiled node that knows *which* thing it means, and finds it by
name anyway.

The measurement is E44's. On `apache_httpd`, `VarRegistry.get` — a walk outwards through a stack
of hash maps — is about **7.2% of sampled run time**, and **4.7 of those points are reached
through `CompiledRefs`, the compiled path** that bodies and captures already use. The reference
is compiled; the store it names is not. That is design 10's third change carried one step short,
and it survived design 29 untouched because §3.2 listed lookups *by text* and this one is by
name, inside the resolver that was supposed to have fixed them.

**The rule this design is for:** a map consulted at run time is a defect when its key is already
known at compile time. Where the key is *data* — a switch's selected value, a value map's
subject, a grouping's key — a map is the right answer and stays.

## 2. What was already done, so the design does not claim it

Design 29 phase 3 closed the two lookups that most resemble these: **templates by mode** and
**templates by name**. `BodyCompiler` links them when the project compiles, and the survey behind
E44 confirmed they had no run-time caller left. That is the shape this design generalises, and
it is the evidence that the shape works.

## 3. The sites

Every map in the engine, the regex library and the pipeline was read for E44. Four qualified.

| Site | Key | Frequency | Status |
|---|---|---|---|
| `CompiledProject.templatesByMode`, `templatesByName` | a mode or template name, authored | link time only | **closed** — §9 phase 1 |
| `Conditions` — `patterns.get(PatternKey.ofValue(...))` | a pattern's **text**, authored | 624 per operation on `apache_httpd`, none elsewhere | **closed** — §9 phase 2 |
| `VarRegistry.get`, via `Refs.lookup` and `CompiledRefs.lookup` | a variable name, authored | 24,960 resolutions per operation on `apache_httpd` | **open** — §5, and the reason this design is not finished |
| `Body.keyIndexes` — `put(name)` / `getOrDefault(key)` | a key's name, authored | only `key`/`key-get` configurations, none in the suite | **open**, unmeasured; the *inner* index is data-keyed and stays a map |

**Read and cleared**, recorded so the next survey need not repeat the walk: `Switch.cases`,
`ValueMap.entries`, the grouping maps in `Body.file`, `FunctionRuntime.state` and the pipeline's
function caches are keyed by data, which is what a map is for. `MatchCompiler.patterns`,
`Encoding.BY_LABEL`, `RegexEncodings.CACHE`, `UnicodeClasses.CACHE`, `Lowering.library` and
`ByteForm.inverse` are consulted at compile time only. Named-group resolution looked like a
candidate — `BytePattern.groupIndex` is a linear `List.indexOf` — and is not: `Replacer` resolves
it at compile time and nothing calls the by-name form at run time.

## 4. What this does not touch

- **The references inside a condition.** A compiled condition still resolves its
  `RefExpression` operands through `Refs`. That is E39's other half, deferred on design 29 phase
  4's measurement of 0.4% and 0.7%, and compiling the condition *tree* does not reopen it.
- **Nodes that run themselves.** The obvious follow-on to a compiled condition is
  `CompiledCondition.evaluate(...)` on each record, retiring the switch. *Ruled against, 2026-09-09,
  for two reasons and not the one usually given.* First, evaluating needs `VarRegistry`,
  `MatchResult` and the match count — run state — and design 27 ruling 2 refused exactly that for
  the ops: it "would put run state into every op's signature or into the op, which is the per-run
  mirror D35 refuted". Second, and structural: `CompiledCondition` is in `compile`, the run state
  is in `exec`, and `exec` already imports `compile` — so the method would reverse an edge and
  reinstate the package cycle ruling 8 spent a phase removing. This is the same wall that put
  `CompiledSteps` in `match` rather than `compile` (design 29 phase 2). The performance argument
  is the weakest of the three and should not be leant on: the conditions path measures at 0.4% to
  0.7%, so neither dispatch shape costs anything there. If it is ever done it should be for the
  whole instruction set at once, with the vocabulary moved somewhere both packages can see, which
  is a structural design and not a phase.
- **An inline cache on the compiled reference.** The obvious trick for §5 — cache the resolved
  store on the compiled ref with a generation counter the registry bumps on push and pop — is
  refused for the first reason above. It works, and it puts per-run state on the shared graph.

## 5. The variable store: what is actually known

This is the row worth 7.2%, and it is off the table by ruling until it is counted. What follows
is what reading the code establishes, so that the counting has something to confirm or refute.

**The registry.** `VarRegistry` is a stack of scopes, each a `Map<String, List<Store>>`. The
global scope holds every capture name, registered once at the start of a run
(`Body.registerCaptures`). A scope is pushed by a for-each, a grouping and a sequence walk, and
each declares what it shadows as it pushes: the loop's own `as` name, and the engine variables.
A write to a name nothing holds creates a store in the innermost scope (`entry`).

**A store is match-indexed on purpose, and that is not overhead.** Every writer uses
`store(name)`, which is group 0, and every reader but one uses `getFirst()`, so the
`List<Store>` looks like a one-element list wrapping a single value. It is not: `Body.variable`
promotes a captured list into a variable's name, so `$var` group 1 means "group 1 of the match
that produced this variable". And the *index* inside a store is what makes a heading captured on
one match readable beside a value captured on another — the parallel store the sort-key warning
in `ReferenceCheck` already names, and the reason a CSV configuration can put a column's name
next to its value. **Neither dimension is vestigial. Do not flatten them.**

### 5.1 The engine variables are a frame, not a namespace

The eight names in `EngineVars` are in the registry because it was the mechanism to hand, not
because they are variables. They are execution context, and they come in three frames:

| Frame | Names | Written | Shape |
|---|---|---|---|
| match | `__match_count`, `__match_idx` | per counted match, by `Level` | scalars |
| iteration | `__index`, `__position`, `__last` | per iteration, by a for-each or the sequence walk | scalars |
| group | `__group`, `__group_key`, `__group_size` | per group, by a for-each-group | `__group` is a sequence of indices; the other two scalars |

Three facts make this more than a description.

**They already behave as frames.** Each is `shadow`ed at the push that creates its frame, so a
nested for-each shadows the outer `__index` without anything else being said. The scope stack is
being used to give three known frames their nesting.

**The compiler already classifies them.** `ReferenceCheck` holds `ITERATION_ONLY` and
`GROUP_ONLY` and warns when a reference reads `__position` outside a for-each or `__group_key`
outside a grouping — E21's hazard. So "this reference means the iteration frame's index" is a
compile-time fact *today*: the analysis exists, is tested, and is doing safety work. Compiling
such a reference into a frame read needs no new analysis, only a use of the one already there.

**Nothing authored can write them.** They are in `ReferenceCheck`'s `writable` set so that
reading one is not reported as an unknown name — the engine writes them, and that set is what
makes the read legitimate. There is no authored path that assigns one.

**And they never use the store's index.** Every engine write is `set(1, ...)`. The only exception
is `__group`, which `bindDense` writes as a sequence. So the match-indexed store — the thing that
exists for headings beside values — is machinery the engine variables carry and never use.

### 5.2 What the frame model would be — built 2026-09-10, §9 phase 4

Three frames on the run: one for the match, and stacks for the iteration and group frames, since
loops nest. Each holds its own named fields rather than entries in a map — a `long` where a
`long` is meant, and a list for `__group`. A reference that names an engine variable compiles to
a read of the frame it belongs to, which the compiler already knows, rather than a name resolved
through the scope walk. Reading one outside its frame stays exactly what it is today: the warning
at compile time, and an absent value at run time.

What that removes is not subtle: eight names leave every scope map, the per-match and
per-iteration writes stop being `store(name)` — an `entry` miss, a walk and a create — and the
reads stop being lookups. What is left in the registry is user names, which is what it is for.

### 5.3 What phase 3 counted, 2026-09-09

Counted by instrumentation reverted afterwards, over each workload's own 256 KiB operation.
**Two of the three exits are answered, and not the way §5.4 first ranked them.**

| workload | resolutions/op | engine vars | scopes open at resolution | found at innermost |
|---|---|---|---|---|
| `log_sessions` | 246,266 | 25.0% | 1:30% 2:14% 3:45% 4:11% | 83% |
| `apache_httpd` | 143,828 | **5.9%** | 1:97% 2:3% | 97% |
| `ausearch` | 86,948 | **72.4%** | 1:100% | 100% |
| `win_sec_strict` | 55,707 | 22.8% | 1:98% 2:2% | 98% |
| `element_storm` | 7,320 | **100%** | 1:100% | 100% |

**The walk is not the cost.** On four of five workloads essentially every resolution happens with
one scope open, and even on `log_sessions` — five iterations and a grouping — 83% hit the
innermost scope. There is almost no walking to remove. **That retires exit 3**: lexical
addressing buys the walk, and the walk is already free. What is left is the hash itself, which is
exit 2's.

**No body is ever run at more than one depth** — 0 of 10, 18, 26, 31 and 58 across the five. So
lexical addressing was *possible*; it is simply not worth it. Recorded because the question was
raised as the thing that might make it impossible, and it turned out not to be the obstacle.

**No engine variable is ever read with a match index.** The indexed reads are all user names —
`__esc_N` escape variables on `apache_httpd`, field names on `log_sessions`. **The frame model's
correctness gate passes**: a frame of plain fields is enough.

**And the engine variables' share is the opposite of what §5.1 guessed**, in a way that makes the
frame model more interesting rather than less. They are 5.9% on `apache_httpd`, the row where the
7.2% was measured — so the frame model barely touches it. But they are 72.4% of `ausearch` and
**100% of `element_storm`**, and on those rows the traffic is almost entirely
`__match_count` and `__match_idx`, which `Level` *writes* per counted match. `element_storm`'s
configuration never reads either one: all 7,320 of its resolutions are bookkeeping that nothing
consumes. On rows like that the registry is not resolving references at all — it is paying a hash
per match to store two numbers no one asks for.

### 5.3.1 What phase 3 counted, as originally specified

The counting is still worth doing, but the justification for the frame model no longer rests on
it: these do not belong in a name-keyed registry whatever their share of the traffic. What the
count decides is what to do about the *rest*.

1. **Resolutions by name**, to size what remains once the engine variables have gone.
2. **Scope depth at resolution.** If most resolutions happen at depth 1 — no loop open — the walk
   is already a single lookup and the remaining cost is the lookup itself.
3. **Whether a body is ever reached at more than one depth.** `call-template` invokes another
   body at whatever depth the *caller* has open, so "how many scopes out" is not a property of
   the called body. This decides whether lexical addressing is possible at all.
4. **Whether anything reads an engine variable with a match index.** Expected: nothing, since
   they are written at index 1. If something does, a frame field is not enough for that one.

### 5.4 The exits, reordered by the count

1. **Intern the names to dense integers; a scope becomes an array.** The count moved this to
   first. Every resolution pays the hash and almost none pays a walk, so this is where the whole
   cost is, and it applies to 100% of the traffic on every row. Every name in a configuration is
   known at compile time, and the change keeps every existing semantic, so it cannot alter
   behaviour.
2. **The frame model, §5.2.** Still right on its own argument — it replaces a general mechanism
   with the specific shape the thing already has — and the count gives it a second, better
   argument than the one §5.1 guessed. It is worth little on `apache_httpd` (5.9%) and nearly
   everything on `ausearch` (72%) and `element_storm` (100%), where the traffic is `Level`
   writing two engine variables per match that the configuration never reads. Those two are
   removed by fields, not by faster lookups.
3. ~~**Lexical addressing.**~~ **Retired 2026-09-09 by the count.** It buys the walk; there is no
   walk to buy. Kept in the record because the reasoning that made it look necessary — dynamic
   scoping, bodies reachable at several depths — was sound and simply did not describe this
   engine: no body in any measured workload runs at more than one depth, and 83% to 100% of
   resolutions never leave the innermost scope.

## 6. Phasing

Phases 1 and 2 are built (§9). What remains:

**Phase 3 — the counting. Done 2026-09-09; §5.3 is the result.** It cost an hour, retired one
of the three exits, reordered the other two, and passed the frame model's correctness gate.
**This phase existed because of design 29 §9**: three of that design's five phases could not be
judged by the workload named for them, and counting first is the cheap fix learned too late to
help two of them. It earned its place — the exit this design would have built first is worth
5.9% on the row that prompted it.

**Phase 4 — the frame model** (§5.2). *Built 2026-09-10; §9 is the record.* **Ranked second by
§5.4 and built first, which needs saying.** Value order and build order are not the same
question. Interning is a change of *representation* and the frame model is a change of
*vocabulary* — what belongs in the registry at all — and a representation should be chosen for
the vocabulary it will actually carry. Interning first would have given dense slots to eight
names, and to the three-to-eight `shadow` calls every loop push makes for them, that phase 5
then deletes; and the two numbers that decide interning's shape, how many names a scope holds
and how deep the stack goes, are both changed by removing them. So the frame model goes first
and interning is designed against what is left.

**Phase 5 — interning**, which §5.4 ranks first on the count: every resolution pays a hash and
almost none pays a walk. Behaviour-preserving by construction, so its gate is the suites plus an
interleaved reading on `apache_httpd` and `log_sessions`, the two rows with the most traffic —
and phase 4 will have taken the engine variables out of both counts, so the expected call count
is to be re-taken rather than reused.

**Phase 6 — the key index**, if it is still worth it. `log_sessions` became a benchmark row on
2026-09-09, so it is measurable now where it was not; whether it is worth measuring is phase 4's
number to decide.

**Phase 7 — the record.** E44 closed or restated; design 10 §2's reference-resolution row
updated; §3's cleared list carried into the ledger so the next survey starts from it.

## 7. The gate, and the method

The suites as always, and design 29 §6's method, which this design inherits without change: the
full suite before any claim about a regression, interleaved rounds where a difference matters,
and the compiler asked directly where the question is about inlining.

One addition, from design 29 §9. **Before a phase is built, count what it will change.** Every
phase here states its expected call count per operation before it is built, and the record says
whether the count was right. Phase 3 is nothing but that.

## 8. Questions for the ruling

1. **The two template indexes.** Leave them, move them into the linker, or compute on demand?
   *Ruled 2026-09-09: move them into the linker*, so that nothing can later misread a map on the
   graph as a run-time lookup.
2. **The conditions pattern**, given it will measure at nothing. *Ruled 2026-09-09: bind it.* The
   recommendation this design first carried was wrong about the cost — it said "one field", and
   binding a pattern that can nest inside `and`, `or` and `not` needs a compiled condition tree,
   which is E39's build. Ruled with that correction in hand: a rule with one exception is a rule
   nobody can apply, and the map leaves the run-time API with it.
3. **The variable store.** *Ruled 2026-09-09: off the table* until §5.3's counting says which
   exit it wants — with the exception below, which the same day's reading changed. **The counting
   is done (§5.3) and it says interning.** Whether to build it is the open question this design
   now rests on.
4. **The engine variables as a frame** (§5.1). *Ruled 2026-09-09: the frame model is the shape to
   build*, and **built 2026-09-10** (§9 phase 4). Raised as "isn't this just a context object
   that moves with the execution", which is what the code already does with three frames and a
   `shadow` at each push. The count then ranked it *second* by value (§5.4) and it was built
   first anyway, for the reason §6 gives: it decides what the registry holds, and interning
   decides how the registry holds it, so the vocabulary settles before the representation.
5. **A sink-bound and reference-heavy benchmark row.** Still open, and shared with design 29's
   outstanding measurements.

## 9. Record

### Phase 1 — the template indexes

`23fc4bc52f`. `CompiledProject` held templates by mode and by name, built in its constructor and
read exactly once, microseconds later, by `BodyCompiler.link`. Both are locals in `link` now.
Not a performance change — nothing read them at run time, which is the point; a map on the
executable graph invites the reading that something still looks a template up while a record is
running.

Two behaviours preserved because they were the lookups' meanings rather than accidents: an apply
whose mode answers to nothing gets an empty list, and a duplicated name means its first bearer.
`link` takes the templates rather than the graph they belong to, which is all it needs — and with
that, **`BodyCompiler` no longer references `CompiledProject` at all**. The class that compiles
bodies depended on the graph type purely to reach two indexes that existed for its benefit.

### Phase 2 — the conditions pattern

`8d0fd1cd65`, and `b4b61bbb68` behind it.

`compile/CompiledCondition` mirrors the authored vocabulary kind for kind, with one difference:
a `matches` test holds its `BytePattern`. `CompiledOp.If` and `When` hold it, and
`CompiledTemplate` holds a compiled guard where it held a boolean saying it had one. The prize is
larger than the hash: `Conditions.evaluate` loses its fifth parameter, so the map is no longer
threaded through `Body.test` and `Level.guards` into every guard evaluation on every template on
every record — and with no run-time reader left, `CompiledProject` stops carrying it at all. A
pattern the collector missed now throws when the configuration compiles rather than when a record
runs.

*A consequence worth recording.* Removing the map left the replace-interning test with nothing to
inspect, because a compiled replace captured its `Replacer` inside a lambda. `CompiledOp.Replace`
is its own instruction now, on `ParseDate`'s precedent — that one is separate for the same reason,
because it holds a compiled parser. No behaviour changed; the same replacer is built at the same
moment and called the same number of times. Both interning tests now assert the same thing in the
same way: the pattern reached the instruction that runs it, by text, exactly. The test was the
thing that noticed the design's own rule was not being followed.

**Neither phase is measured**, and neither is expected to be: points 8, 9 and 10 are recorded as
controls in `benchmarks/points.md`. Point 10 is the one that could say something, because it takes
an implementation out of `Transform.function`'s call site on the workload that runs 209 replaces
per record — which is the question design 27 ruling 2 and design 29 §4 both settled by argument.

### Phase 4 — the engine variables are frames

New `exec/Frames`; `config/EngineVars` becomes an enum; `CompiledRef` gains an `Engine` kind.
The eight names left every scope map except one, and what they left behind is what §5.1 said
they were: a match frame, a stack of iteration frames and a stack of group frames.

*What a record actually stops doing.* `Level` wrote two stores per counted match —
`store(name)` is an outward walk, a miss, an insert and a `TypedValue` allocation, twice — and
now assigns one `long`, because `__match_idx` **is** `__match_count` less one and two names
reading one field cannot disagree. A `for-each` shadowed three names at its push and wrote two
stores per iteration; it pushes a frame and writes two fields. A grouping's filing walk and an
ordering's key evaluation pushed a whole scope to shadow a single name; they push a frame.
And **the values are made on the read rather than on the write**: a frame keeps the number and
remembers the `TypedValue` it was last asked for, so `element_storm` — which reads neither
counter — now allocates nothing for them at all.

*Four behaviours were being said by which names a push happened to shadow, and are now said
outright*, which is most of what `FramesTest` is for:

- **An index-only push inherits.** A filing walk and a key evaluation bind `__index` and leave
  `__position` and `__last` reading the enclosing walk's — deliberately, and previously
  expressed by omitting two `shadow` calls, with a comment explaining that the omission was
  meant. The frame copies them from its parent at the push, which is the same rule as an
  assignment rather than as an absence.
- **The match frame is not a stack.** A nested level overwrote its parent's `__match_count` and
  never restored it, because the store lived in the global scope and nothing shadowed it. That
  is preserved exactly, and is now a named test rather than a consequence of where a `put`
  happened to land.
- **An unopened frame is absent**, which is what `$__position` outside a `for-each` has always
  read as, and what E21's lint warns about.
- **A scalar answers to index one and to nothing else.** The store a frame replaced held one
  value, at index 1, in a list of one, so a reference reads it for the latest, for the last or
  for index one and reads nothing otherwise. The count said no engine variable is ever read
  with a match index (§5.3), so this is fidelity in a corner rather than a live path — but a
  corner that changes silently is the kind this design exists to avoid.

*`__group` stays a store, and that is the shape rather than the shortcut.* It is a sequence:
walked by a `for-each`, indexed, folded, and read through the same `select` paths an authored
sequence uses. `EngineVars.framed()` is where that exception lives, so it is one predicate and
not a scatter of special cases, and `Frames` throws rather than answering for it.

*The seam E39 owns is unchanged and is now visible.* A condition still resolves the authored
expression, so `Refs.lookup` classifies the name — one static lookup, on a path measured at
0.4% to 0.7% — while the compiled path holds a `CompiledRef.Context` and reads the frame with no
name in it. The one place that had to be found rather than reasoned about is
`$var[$__match_count]`, the CSV-heading reference whose *index* is an engine variable: the
`001_csv_with_header` golden exercises it, and it resolves its index through the frames too.

#### The audit, 2026-09-10

*One hole, two tidyings that were behaviour, and a rename.*

**A binding could take an engine name, and after this phase it would be unreadable.** Nothing
refused a capture, parameter, variable, transform target or `for-each as` called `__index`.
Before the frames such a binding landed on the engine's own store — clobbering the counter for
the rest of the run, which is worse, not better. After them it writes the registry while every
reference to that name reads the frame: written, and unreadable for ever. That is precisely the
failure this class already refuses configurations for, in its own words, "a configuration that
appears to work and quietly reads absence for ever", so it is a `ConfigException` rather than a
warning. All eleven binding sites already funnelled through one `writable.add`, so the refusal is
one method; `ReferenceCheckBindingsTest` now runs it over all thirty binding instructions and all
eight names, which is what says the refusal is not attached to a single arm. **No configuration
in the corpus binds one**, so nothing that compiled before stops compiling.

*What the refusal cannot reach*, named rather than fixed: a key-value capture takes its names
from the data, so an input containing `__index` as a key could still bind one at run time. It
was equally wrong before this phase, and no compile-time check can see it.

**The index rule had been tidied into a different rule.** `atIndexOne` asked `isLast` before
`varRef`; the resolver it mirrors asks `varRef` first, and nothing in `MatchIndex` makes the four
forms exclusive — so which is asked first is behaviour, not style. Restored to the original
order, with a comment saying why the tidier version is wrong.

**`EngineVars.ALL` had quietly become mutable.** It was `Set.of(...)`; the enum rebuilt it as
`BY_NAME.keySet()`, which is a live view of a `HashMap`. `Set.copyOf`.

**Checked and clear.** The `TypedValue` memo copied into an index-only frame cannot go stale,
because both index-only pushes — a grouping's filing walk, an ordering's key evaluation — run to
completion before their enclosing loop advances. `popIteration` and `popGroup` are skipped when
`AbortRun` unwinds, exactly as `vars.pop()` already was. And `__match_count`'s non-restoring
behaviour was verified against where the store actually lived rather than assumed: the global
scope, never shadowed, so every level overwrote it.

**Residue, left deliberately.** A `for-each` still pushes a variable scope when it binds no
`as`, and that scope may now be empty — but a `sequence` declared in the loop body, or a write to
a name nothing registered, belongs to it and would otherwise outlive the loop. In `sorted()` the
same push is provably dead when `as` is null, since a sort key can only read; it is left alone
because removing it is tidiness rather than this phase's subject.

**And `CompiledRef.Engine` is `CompiledRef.Context`.** Its siblings — `LocalGroup`, `RemoteVar`,
`Bytes` — name *where a value comes from*; `Engine` named *who writes it*, which is a different
axis, and it sat one word from the `EngineVars` it holds. The run's context is where the value
comes from.

#### The measurement, 2026-09-10 — and the count was right in a way §5.3 read wrongly

Four interleaved rounds against `e7c6ed43ba`, order alternated within each round, on the four
rows the count ranked. A targeted daytime reading, not the full-suite gate, so it licenses a
claim about these four rows and nothing about the other seven.

| workload | engine vars | median | range | rounds agreeing |
|---|---|---|---|---|
| `ausearch` | 72.4% | **+12.1%** | +4.7 to +19.4 | 4/4 faster |
| `log_sessions` | 25.0% | **+9.7%** | +4.9 to +12.3 | 4/4 faster |
| `element_storm` | 100% | +1.4% | −1.0 to +3.7 | 3/4, inside the ±3.3% envelope |
| `apache_httpd` | 5.9% | +1.7% | −3.2 to +4.3 | 2/4 either way, no sign |

Two rows are clear results: every round agrees in sign and every round's delta clears the drift
envelope. Two are **no measurable change**, and are reported as that rather than as small wins.

**§5.3 ranked these rows by the engine variables' *share* of the traffic, and the share is the
wrong number.** By share the order is `element_storm` (100%), `ausearch` (72%), `log_sessions`
(25%), `apache_httpd` (5.9%), and the row that should have moved most moved least. By the
**absolute count of engine-variable resolutions removed** — the same instrumentation, multiplied
out — it is:

| workload | resolutions/op × engine share | measured |
|---|---|---|
| `ausearch` | 86,948 × 72.4% = **62,950** | +12.1% |
| `log_sessions` | 246,266 × 25.0% = **61,566** | +9.7% |
| `win_sec_strict` | 55,707 × 22.8% = 12,701 | not in this reading |
| `apache_httpd` | 143,828 × 5.9% = 8,485 | no sign |
| `element_storm` | 7,320 × 100% = 7,320 | inside the envelope |

Two rows at about 62,000 moved by about ten per cent; three rows at seven to thirteen thousand
did not move measurably. **The count predicted the outcome; §5.3's reading of it did not**, and
the correction is a single arithmetic step this design should have taken before ranking anything.
`element_storm` is 100% engine variables because it resolves almost nothing at all — 7,320 per
operation, a thirtieth of `log_sessions` — and its work is 21 structural writes per record
through the sink. A hundred per cent of a small number is a small number.

That also settles what the phase is worth against §5.4's ordering. The frame model was ranked
second on a share reading and has now moved two rows by ten per cent; interning's own expected
call count must be re-taken on the same footing — absolute resolutions remaining after this
phase, not percentages.
