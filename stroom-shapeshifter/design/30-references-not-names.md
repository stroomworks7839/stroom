# Design 30 — The compiled model holds references, not names

*Proposed 2026-09-09, from design 29 phase 4's measurement, which went looking at conditions and
found something larger underneath them. Engine only. Touches no golden and no configuration:
every change here replaces a lookup with the thing it was looking up.*

*All four of its maps are closed, and the design is done as of 2026-09-10 (§9). Three went on
the first day; the fourth — the variable store, the one the design was opened for — needed the
counting §5 ruled it could not proceed without, and then the frame model and the interner that
§5.2 and §5.5 designed. It is worth **+15.9%** on `apache_httpd` and **+11.9%** on
`log_sessions`. E44 in the engine's ledger carries the closing record.*

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
| `VarRegistry.get`, via `Refs.lookup` and `CompiledRefs.lookup` | a variable name, authored | 24,960 resolutions per operation on `apache_httpd` | **closed** — §9 phases 4 and 5; the registry is an array and `Refs` is deleted |
| `Body.keyIndexes` — `put(name)` / `getOrDefault(key)` | a key's name, authored | `log_sessions` runs two `key` and one `key-get`; no other workload | **closed** — §9 phase 7. The *inner* index is data-keyed and stays a map |

**Read and cleared**, recorded so the next survey need not repeat the walk: `Switch.cases`,
`ValueMap.entries`, the grouping maps in `Body.file`, `FunctionRuntime.state` and the pipeline's
function caches are keyed by data, which is what a map is for. `MatchCompiler.patterns`,
`Encoding.BY_LABEL`, `RegexEncodings.CACHE`, `UnicodeClasses.CACHE`, `Lowering.library` and
`ByteForm.inverse` are consulted at compile time only. Named-group resolution looked like a
candidate — `BytePattern.groupIndex` is a linear `List.indexOf` — and is not: `Replacer` resolves
it at compile time and nothing calls the by-name form at run time.

## 4. What this does not touch

- **The references inside a condition.** ~~A compiled condition still resolves its
  `RefExpression` operands through `Refs`.~~ *Held until 2026-09-10, when phase 5 changed the
  argument rather than the measurement: those references became the last run-time name
  resolution whose key the compiler knew. §6 phase 6 is the design.* The reasoning that kept it
  here was sound and is worth keeping in the record — compiling the condition tree does not by
  itself reopen the refs, and 0.4% to 0.7% is not a reason to move them.
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

### 5.5 Phase 5's design: a name is a slot, and a scope is not an object

*Built 2026-09-10; §9 phase 5 is the record.*

*Written 2026-09-10, from three questions raised after phase 4 landed: should a scope be a type
rather than a raw map, should `push` hand that type back so a caller can shadow on it directly,
and should names become compile-time objects carrying an index so the registry can be an array.
The aim behind them is explicit — **no map in the compiled runtime whose key the compiler
already knew** — and it is the right aim. What follows is what the code says about how to reach
it, including one place where the first two questions and the third pull in opposite
directions.*

#### What the research found

**Every push's shadow set is a compile-time constant.** There are six pushes, and the observation
that prompted this is not just true but stronger than it was put:

| site | what it shadows | known when? |
|---|---|---|
| `forEachGroup` | `__group` | a constant |
| `forEach`, `sorted` | the loop's `as`, or nothing | on the op |
| `variable` | the variable's name | on the op |
| `callTemplate` | every argument's name, then every unsupplied parameter's | on the op, the parameters settled at link time |
| `apply`, when recursive | `recursiveShadow`, already a precomputed array | at link time |

So the pairing is not "a push is usually followed by shadows" — it is **"a push shadows a set the
compiler can hand it"**. Two sites shadow the empty set (`forEach` and `sorted` with no `as`),
which is why the strict reading of the observation fails and the stronger one still holds.

**Exactly one name in the whole engine is not known until a record runs.** A key-value capture
resolves its own name out of the data (`Level.bindCaptures`), which is the DS3 shape where a
field's name and its value are both read from the input. Everywhere else — every op, every
capture, every reference — the name is authored. That single site is what stops the registry
being *only* an array, and it is also the site this design's own rule (§1) exempts: its key is
data, and a map is what a data key is for.

**The name counts are small.** Distinct name-ish strings per configuration across the fixture
corpus: median **8**, and the largest is `apache_httpd` at **239**. *Ruled 2026-09-10 that real
configurations are the same order — a few hundred at most* — so the slot array is allocated flat,
with no threshold and no second code path.

`apache_httpd`'s 239 is worth breaking down, because it is not a large configuration and reading
it as one would size this wrongly. **Thirty** of those names are the author's — `clientIP`,
`url`, `status`, `monthStr` and the rest. The other **209 are `__esc_0` … `__esc_208`**,
temporaries holding one escaping step each: a `translate` of `& " < >` into their entities, bound
and then read once. So the widest configuration in the corpus is a small one with a long tail of
generated single-use temporaries, which is a shape the flat array handles for nothing — 239 slots
is under two kilobytes, once per run — and which is also **where the "209 replaces per record"
error came from** (`benchmarks/points.md`, corrected the same day: they are `translate` ops, and
this workload runs two regex replaces).

#### The shape: one array, and an undo log

The obvious reading of "an array instead of a map" is an array per scope, and it is the wrong
one. A scope would have to be `N` wide to be indexable, so every push would allocate and clear
239 slots on `apache_httpd` to hold the one or two names it actually shadows — worse than the
`HashMap` it replaces — and reads would still walk outwards.

The shape that fits what §5.3 counted is **one array for the whole run, plus an undo log**:

- `List<Store>[] current`, sized to the configuration's name count, allocated once per run.
  **A read is `current[slot]`.** No hash, and no walk — which matters because §5.3 found 83% to
  100% of resolutions were already at the innermost scope, so the walk was never the cost and an
  array-per-scope design would have bought the wrong thing.
- `push` records a mark: the height of the undo stack.
- `shadow(slot)` pushes `(slot, current[slot])` onto the undo stack and installs a fresh entry.
- `pop` unwinds to the mark **in reverse**, restoring each saved value.
- A write to a name nothing holds — today's `entry` creating in the innermost scope — is the same
  operation as a shadow, so it undoes the same way.

Reverse unwinding is what makes a name shadowed twice in one scope safe, which matters because
`Apply.recursiveShadow` flattens the capture names of every candidate template and duplicates are
likely; the intermediate save is restored, then the outer one, and the outer one is what is left.
Deduplicating that array at link time is free and should be done anyway.

#### So a `Scope` type is the thing this deletes

The first two questions want a `Scope` object, with `push` returning it so a caller shadows on it
directly. That is a genuine improvement to what is there now — but the array-and-undo-log shape
**has no per-scope object at all**: a scope is a mark, an `int`. Building `Scope` first would be
building the thing phase 5 removes.

What survives from those two questions is better than the wrapper, and it is the combined
primitive the first finding licenses:

```
void push(VarName[] shadowed)
```

one call, taking an array the compiled op already holds. That is the same idea — push and shadow
belong together — expressed as a compile-time constant rather than as an object handed back and
mutated. It also removes the `getLast()` that every `shadow` does today, which was the wrapper's
other prize.

#### What `VarName` is, and what it costs to carry

`VarName(String name, int slot)`, interned once per configuration when it compiles, living in
`compile` — `exec` already imports `compile`, and design 27 ruling 8 refuses only the reverse
edge. The name stays on it because messages, instrumentation and `toString` all want it; the slot
is what the run uses.

The surface is about **twenty fields on the compiled graph** that hold a variable name today and
would hold a `VarName`: `CompiledRef.RemoteVar.varId`, `CompiledCapture.name`,
`Apply.recursiveShadow`, `Arg.name`, `Param.name`, `Variable.name`, the eight transform targets,
`Sequence.name`, `Append.name`, `ForEach.select` and `as`, `ForEachGroup.select`, `Key.select`,
`KeyGet.name`, `Fold.select` and `name`, `DistinctValues`, `Tokenize.name`, and
`CompiledTemplate`'s `clearNames` and `captureNames`. Mechanical, wide, and each one a place a
name stops being resolved.

Two smaller consequences worth knowing before starting:

- **`MatchIndex.varRef` is the twenty-first**, and it is the awkward one: it lives on the authored
  `RefExpression` rather than on a compiled node, so `$var[$other]` still resolves a name at run
  time. Phase 4 already flagged it as residue. It needs a compiled `MatchIndex` alongside
  `CompiledRef`, which is a small design of its own.
- **`fromCurrentScope` disappears.** Its one caller shadows the very name it then asks for, so
  after the shadow the current scope's entry *is* `current[slot]`, and the method becomes a plain
  read.

#### The one map that stays, and why that is the answer rather than a gap

A key-value capture writes under a name from the data, so it needs `String → slot`: a
`Map<String, VarName>` built when the configuration compiles and consulted only there.

A data-derived name the table does not hold has no slot, and no reference can name it. **Ruled
2026-09-10: keep it anyway**, in a small overflow the key-value path alone touches. The reason is
not that anything reads it — nothing can — but that captures have to keep operating for something
outside the run to present them, which is the same requirement that made capture pruning a *mode*
rather than a deletion. Dropping the binding would be faster and would quietly empty a field a
diagnostic or a UI expects to see. The overflow costs nothing to a configuration without
key-value captures, since it is never allocated.

That leaves the end state statable in one line, which is the point of the exercise: **every map
left in the compiled runtime is keyed by data** — a `switch`'s selected value, a value map's
subject, a grouping's key, a key index's key, and a key-value capture's name. None is keyed by
something the compiler knew.

#### The count, 2026-09-10 — what the registry is asked once the frames have gone

§7's rule, and phase 4's lesson about reading a count in absolute terms rather than as a share.
Instrumentation reverted afterwards, per 256 KiB operation.

| workload | reads | writes | **hash lookups** | levels walked | shadows | pushes | distinct names |
|---|---|---|---|---|---|---|---|
| `log_sessions` | 107,419 | 77,271 | **252,553** | 248,764 | 3,782 | 3,778 | 22 |
| `apache_httpd` | 69,668 | 65,712 | **140,892** | 139,480 | 1,392 | 1,392 | 213 |
| `win_sec_strict` | 19,537 | 23,494 | **45,023** | 43,988 | 957 | 957 | 95 |
| `ausearch` | 4,136 | 19,880 | **24,022** | 24,016 | 0 | 0 | 50 |
| `element_storm` | 0 | 0 | **0** | 0 | 0 | 0 | 0 |

**`element_storm` no longer touches the registry at all.** Every one of the 7,320 resolutions
§5.3 counted on it was an engine variable, and phase 4 took all of them. Nothing to intern, and
its slot array is never allocated. That is the cleanest confirmation phase 4 could have.

**The hash column is what this phase removes, and it is large where it matters.** Phase 4 removed
61,566 resolutions from `log_sessions` and was worth +9.7%; phase 5 has **252,553** hash lookups
to remove from the same row, four times as many. `apache_httpd` keeps 140,892 — the row that
started this design with a 7.2% profile reading and that phase 4 could not touch. Ranked
absolutely, as the lesson says: `log_sessions`, `apache_httpd`, `win_sec_strict`, `ausearch`,
`element_storm`, in that order, and the first two are where an interleaved reading should go.

**Almost all of it is the walk's per-level hash** — 248,764 of `log_sessions`'s 252,553 — which
is not a contradiction of §5.3's finding that the walk is shallow. The walk *is* shallow, about
1.35 levels per lookup; it is that there are so many lookups. The array removes both the depth
and the hash, and a shallow walk is exactly the case where removing the hash is the whole win.

**Writes are between 40% and 83% of the traffic**, and on `ausearch` they are almost all of it.
A design that made reads cheap and left writes hashing would miss most of this. `current[slot]`
is the same operation for both, which is the point.

**A push shadows one name, on average.** `apache_httpd`: 1,392 pushes, 1,392 shadows.
`log_sessions`: 3,778 and 3,782, the four extra being the `sequence` instruction's standalone
shadow. So `push(VarName[])` is one call replacing two, over an array that is usually length one
— and `ausearch` makes none at all, so the primitive costs it nothing.

**And the name counts settle the array.** 22, 213, 95, 50 and 0 distinct names resolved at run
time. Flat, allocated once, as ruled.

The gate is the suites plus interleaved rounds on `log_sessions` and `apache_httpd`.

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

**Phase 5 — interning. Done 2026-09-10; §5.5 is its design and §9 its record.** +13.9% on
`apache_httpd` and +11.8% on `log_sessions`, the two rows the re-taken count ranked first and
second by absolute lookups removed.

**Phase 6 — the conditions' references** (E39's other half), which phase 5 turned from a
performance question into a structural one. *This design's goal is one map away.* Every run-time
string lookup left outside a key-value capture is a condition resolving an **authored**
expression: 19,440 per operation on `apache_httpd`, 8,792 on `log_sessions`, 4,872 on
`win_sec_strict`, 2,170 on `ausearch`. That is a map keyed by something the compiler knew, which
is the exact defect §1 names. Design 29 §4 and §4 above both deferred it on a 0.4%-to-0.7%
measurement; the measurement still holds and is no longer the argument.

*The design, 2026-09-10.*

**What it is.** `CompiledCondition` already mirrors the authored vocabulary kind for kind and
holds its own `BytePattern` (phase 2). It still holds authored `RefExpression`s, so
`Conditions.evaluate` calls `Refs` — the resolver that walks the authored form and finds a
variable by name. Each of those becomes a `CompiledRef`, and a `Compare`'s two operands become a
new `CompiledOperand`.

**A literal operand stops being built per evaluation.** `Condition.Operand` is a reference *or* a
literal, and the literal case allocates a `TypedValue` and applies its declared cast on every
evaluation. Both are constant. `CompiledOperand` holds the materialised, already-cast value, so
the run reads a field. That is a second prize the count did not predict, and it is on the same
path: `apache_httpd` evaluates 24 `equals` conditions per record.

**Literal text inside a reference stops being encoded per evaluation** for the same reason —
`CompiledRef.Bytes` holds a UTF-8 value made once, where `Refs` called `getBytes` each time.

**And `Refs` is deleted.** It has exactly three callers, all in `Conditions`. With those gone the
engine has **one** reference resolver rather than two, which is the structural prize and is worth
more than the microseconds: the seam design 25 phase 3 opened and E39 has owned since closes, and
`CompiledRefs` stops being "the other one".

**What this does not do**, still: give `CompiledCondition` an `evaluate` method. §4's second
bullet refuses that for the package-cycle reason and phase 6 does not reopen it — the vocabulary
stays in `compile`, the run state stays in `exec`, and the interpreter's switch stays where
design 27 ruling 2 put it.

**The correctness risk, checked before building rather than after.** The two resolvers must agree
on the *type* a value carries, or a comparison could change answer. They do: a literal text part
resolves through `Refs` to `TypedValue.utf8(bytes)` and through `CompiledRefs` to
`TypedValue.of(text)`, and both are a `Utf8Bytes`. Verified in `TypedValue` rather than assumed.

**The gate.** The suites, and an interleaved reading on `apache_httpd` — 19,440 of the remaining
lookups are its — with `element_storm` as the control, since it evaluates no conditions either.
The expected outcome is stated first, per §7: the path measures at 0.4% to 0.7%, so **the run
rows should barely move**, and the claim being made is structural. If `apache_httpd` moves more
than the envelope, the literal operands are why and that should be said rather than assumed.

**Phase 7 — the key index. Done 2026-09-10; §9 is the record.** It was the last run-time lookup
on a key the compiler knew, and it is now an array indexed by slot.

**Phase 8 — the record. Done 2026-09-10.** E44 is resolved rather than merely closed: the entry
keeps the finding, because a survey that reads it later should be able to see the shape before it
was closed, and adds what closed it, the readings, the counted lookups phase by phase, and §3's
cleared list together with §9 phase 7's residual table — so the next survey starts from a written
list rather than repeating the walk. Design 10 §2's reference-resolution row was updated in phase
6 and names phase 5 for the store behind the reference. §2's fourth row above moves to closed
with the other three, which is the last thing in this document that still said open.

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
6. **A `Scope` type, and `push` returning it.** *Raised 2026-09-10, and answered by §5.5 rather
   than ruled on:* the observation behind it is right and is stronger than it was put — every
   push's shadow set is a compile-time constant, at all six sites — but the object it asks for is
   the thing phase 5 deletes, since an array-and-undo-log registry has no per-scope object, only
   a mark. What the observation earns is `push(VarName[])`, the combined primitive, which is the
   same idea said as a constant rather than as a handle.
7. **Names as compile-time objects carrying a slot.** *Raised 2026-09-10 as the direction to go,
   and it is:* §5.5 is its design. The one place it cannot reach is a key-value capture, whose
   name is read from the data — and that is the rule of §1 holding rather than failing. The end
   state is worth stating as the goal it is: **every map left in the compiled runtime is keyed by
   data.**
8. **A data-derived capture name with no slot.** *Ruled 2026-09-10: kept, in an overflow*, on the
   same ground as the capture-pruning mode — a capture nothing consumes still has to be there for
   something outside the run to show. Dropping it would be faster and would silently empty a
   field.
9. **How wide a configuration gets.** *Ruled 2026-09-10: a few hundred names at most*, so the
   slot array is flat with no threshold. Recorded because it is an assumption about
   configurations this repository has not seen rather than a measurement, and a production
   configuration an order of magnitude wider would want rechecking rather than a surprise.
10. **The conditions' references, once more.** *Ruled 2026-09-10: compile them*, and built the
    same day (§9 phase 6). Deferred twice on the ground that the path measures at 0.4% to 0.7%,
    which was still true and was not the question — after phase 5 they were the only run-time name
    resolution left whose key the compiler knew. E39 is closed on the structural half it had
    itself left open. *The measurement it was deferred on turned out to under-describe the
    change*: it covered `Refs` resolution and not the literal operands beside it, which is why
    §9's record spends more words on the prediction than on the result.
11. **The key index** (§3's fourth site, §6 phase 7). *Ruled 2026-09-10: close it*, and built the
    same day. With it gone the claim §8 ruling 7 set as the goal can be made, **for the engine**
    — and the qualifier is the point, since the last time it was made without one it was wrong.

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
an implementation out of `Transform.function`'s call site on the workload believed to run 209
replaces per record — which, corrected 2026-09-10, runs two — which is the question design 27 ruling 2 and design 29 §4 both settled by argument.

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
reference to that name reads the frame: written, and unreadable forever. That is precisely the
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

### Phase 5 — a name is a slot

`compile/VarName` and `compile/VarNames`; a rewritten `exec/VarRegistry`; about twenty fields
across the compiled graph that held a string now hold an interned name. Names are interned as the
graph is built — whatever compiles a node that names a variable asks for the `VarName`, and the
first ask assigns the slot — so there is no second walk to keep in step with the compiler's
existing one.

`compile/CompiledIndex` is the twenty-first, and the one §5.5 called awkward: `$var[$other]`
resolved its *index* variable by name on every such reference, because the rule lived on the
authored `RefExpression` rather than on a compiled node. It is compiled now, and it says which of
the two places holds that variable — a registry slot, or one of phase 4's frames.

*The registry is one array and an undo log.* A read is `slots.get(name.slot())`: no hash, and no
walk. A push records the log's height, a shadow saves a slot's binding and installs a fresh one,
and a pop unwinds to the mark **in reverse**, which is what makes a name shadowed twice in one
scope come back to the binding outside it rather than the one in between. **A scope is an
`int`** — nothing is allocated to open one. `owner`, holding the depth that installed each
binding, is what keeps the log bounded: shadowing a name the scope already holds is the no-op the
per-scope map made it, and without that a `sequence` declared inside a loop would log once per
iteration.

*`push(VarName[])` is the combined primitive* §5.5 argued for in place of a `Scope` object. The
count said a push shadows one name on average — `apache_httpd`, 1,392 pushes and 1,392 shadows —
so it is one call replacing two over an array usually of length one, and `link` deduplicates that
array because two candidate templates can declare the same capture name.

#### What measuring found, twice

**A defect, and it was mine.** `ausearch` binds 14,140 names per operation out of the data. The
first shape consulted the compiled table — which *misses* for exactly those names — and then a
separate map of data-derived ones, which hits. Two lookups where phase 4 did one, on that row's
hottest path: 24,022 map lookups per operation became about **30,450**, and it measured as a
regression. One map instead of two fixes it: on the first name that arrives from the data the
table is copied once, and every lookup after that is a single hit. A configuration without
key-value captures never copies. `ausearch` is now 16,310, below where it started.

**A floor, and it is structural.** Even fixed, `ausearch` does not gain, and cannot: its names
genuinely arrive as strings, so the map lookup is irreducible and the slot indirection is added
after it. The right result for that row is *no change*, and that is what it now measures. Worth
stating because it is the boundary of this design's own rule — §1 exempts a key whose value is
data, and this is what that exemption costs where the data-keyed path is the whole workload.

#### The reading, 2026-09-10

Six interleaved rounds against `68fb1a592a`, order alternated within each round, plus a targeted
six on `ausearch` after the fix.

| workload | lookups removed | median | range | rounds |
|---|---|---|---|---|
| `apache_httpd` | 140,892 → 19,440 | **+13.9%** | +13.2 to +15.7 | 6/6 faster |
| `log_sessions` | 252,553 → 8,792 | **+11.8%** | −1.3 to +15.1 | 5/6 faster |
| `win_sec_strict` | 45,023 → 4,872 | +1.3% | +0.9 to +3.7 | 6/6, inside the envelope |
| `ausearch` | 24,022 → 16,310 | −0.4% | −2.3 to +2.2 | flat against a −0.6% control |
| `element_storm` | 0 → 0 | **the control** | −1.8 to +1.5 | flat, as it must be |

`apache_httpd` is the largest gain this design has produced, on the row that started it: E44's
7.2% profile reading, which phase 4 could not touch because only 5.9% of its resolutions were
engine variables. **The count predicted both movers**, by absolute lookups removed — the same
ranking that was wrong when read as a share in phase 4, and right twice since.

`win_sec_strict` is reported as no measurable change despite six rounds agreeing in sign, because
+1.3% is inside a ±2% envelope. Six agreeing rounds inside the envelope is still inside the
envelope.

#### The control row, which is the method note

**`element_storm` does no registry work at all**, because phase 4 took every one of its
resolutions into frames. That makes it a free noise gauge for any registry change: whatever it
reads is the box, not the code. It earned that in the first attempt at this reading, where it
swung **−11.4%** in round one and **+6.2%** in round two — an eighteen-point spread on a row that
cannot move — which is what said the whole run was worthless and sent the reading back to a
settled box. On the good run it holds within ±1.8%.

It should have been *declared* a control before the first run rather than noticed afterwards.
That is the rule worth keeping: **a change to a subsystem should be measured with a row that does
not use that subsystem in the same run**, and this design acquired one by accident.

#### The audit, 2026-09-10

*One over-claim of my own, one hardening, one hot-path slip, and two tightenings.*

**A defence justified by a failure that cannot happen.** Compiling a condition interns the names
its operands read, and the javadoc said this stops a name written by a capture and read only by a
guard from missing the table, taking a slot of its own and reading nothing — silently. It does
not, because that cannot occur: **whatever writes a name interns it, and the compiler refuses a
read of a name nothing writes**, so a guard's name is always in the table already. Where it is
not — a key-value capture's data-derived name — the write and the read go through the *same*
run-time map and agree on the slot they invent. Proved by disabling the interning and re-running
the test written to guard it, which still passed. The interning stays, on the honest ground that
it makes the table mean *every name the configuration mentions*; the javadoc and the test now say
that instead. **A test that passes with the code removed is not a test**, and this one was written
before it was checked.

**`VarNames` was mutable and shared, and a late `intern` would have been silent.** A compiled
project outlives the runs that use it (D35) and each of those sizes its slot array from this
table, so a name interned afterwards has a slot past the end of every one of them. `Compiler`
freezes the table once linking has interned the last names, and `intern` throws after that.

**The index rule was resolved before there was anything to index.** The walk this replaced
resolved it only after establishing that the variable had stores; the first version passed it as
an argument, so it ran on the absent path too. No behaviour difference — resolving an index reads
and nothing more — but "empty is absent" makes the absent path the common one. Restored to the
original order in both resolvers.

**Two tightenings.** `VarNames.all()` handed out its live map, and is now unmodifiable;
`CompiledRef.of` was public with no caller outside its package.

**Checked and clear.** The undo log's reverse unwind and `owner` keeping it bounded;
`bind` not logging at the global scope; both parallel arrays growing together; `fromCurrentScope`
matching the old per-scope map exactly; and `grow` not structurally modifying the map it is
called from inside `computeIfAbsent`. The strongest evidence is the probe rather than the
reading: **only two run-time name resolutions survive in the engine**, conditions and key-value
captures, and four of the five workloads create no run-time slot at all.

Both resolvers' class javadocs were stale in the same way — they justified the two-resolver seam
as a deferred optimisation. It is now this design's last loose end, so they carry the count.

*Re-measured after the audit, because it changed hot-path code:* four more interleaved rounds,
`apache_httpd` **+15.9%** (4/4, +12.9 to +19.1) and `log_sessions` **+11.9%** (4/4, +9.1 to
+16.5), against a control at −0.2%. Both hold; the earlier figures are not restated as improved,
because the difference is inside what the rounds themselves spread.

### Phase 6 — the conditions resolve compiled references

`CompiledCondition` holds `CompiledRef`s, a `Compare`'s operands are `CompiledOperand`s, and
**`Refs` is deleted**: it had exactly three callers, all in `Conditions`, and with them gone the
engine has one reference resolver rather than two. That is what E39 said would happen when it was
done, and it closes design 10 §2's reference-resolution row, open since that design was written.

A literal operand is finished when it compiles. The authored form allocated its `TypedValue` and
applied its declared cast on every evaluation, and both are constant — safe to move because
`Comparisons.cast` reads only the value and the cast, with no zone or locale, and its date reading
requires an explicit offset. A compact constructor refuses a literal that still carries a cast, so
the invariant cannot rot into a cast the run silently ignores.

#### The prediction was wrong, and the reason is not the one phase 4 taught

§6 stated the expectation before the run, as §7 requires: the path measures at 0.4% to 0.7%, so
**the run rows should barely move**. `apache_httpd` moved about **7%**.

Counted afterwards, per 256 KiB operation:

| workload | condition evaluations | literal operands | ref operands |
|---|---|---|---|
| `apache_httpd` | 31,488 | **13,920** | 13,920 |
| `win_sec_strict` | 5,568 | 4,872 | 4,872 |
| `log_sessions` | 4,396 | 4,396 | 4,396 |
| `ausearch` | 2,730 | 2,170 | 2,170 |

Every comparison in the corpus is a reference against a literal, so `apache_httpd` was allocating
13,920 values and running 13,920 casts per operation for things that never change.

**The 0.4%-to-0.7% figure is a sampled share of `Refs` resolution.** The literal side was never in
`Refs` — it was in `Conditions.operand` — so the measurement predicted from never covered half the
work the change touches. Its *scope* was narrower than the change.

That is the mirror of phase 4's error rather than a repeat of it. There the measurement was right
and it was read as a share where volume was needed. Here it was read correctly and was measuring
the wrong extent. Both feel like "the count predicted it" failing, and neither is: **check that
the number being predicted from covers everything the change touches**, which is a different
question from whether it is the right kind of number.

#### What this does not finish, said plainly

Phase 6 removes the last run-time lookup on a compiler-known key **that the corpus exercises**.
It does not remove the last one that exists. `Body.keyIndexes` still hashes a key's *name* —
`Key.name` on the write and `KeyGet.key` on the read, both authored strings — and the phase 5
probe read zero for every workload because it instrumented the variable registry, which that map
is not part of. `log_sessions` runs two `key` instructions and one `key-get`, so the site is
exercised and was simply not being watched.

**That is design 29 §9's rule arriving a third time**, and the third time was in a probe rather
than a benchmark: an instrument that does not cover a thing reports nothing about it, and nothing
reads as zero. Phase 7 is the site, and unlike when §6 was written there is a workload that runs
it.

*The maps that remain, and are meant to.* Every other run-time map in the engine is keyed by
data, which §1 exempts: `VarRegistry`'s table extended with key-value capture names read from the
input (14,140 per operation on `ausearch`), a `switch`'s selected value, a value map's subject,
and the grouping and key indexes keyed by the key's value. `FunctionRuntime.state` is an
extension function's own state bag rather than engine dispatch. The compile-time tables —
`MatchCompiler.patterns`, `MatcherLibrary.definitions`, `Encoding.BY_LABEL`, the unicode and
regex-encoding caches — are not consulted while a record runs.

#### Two findings surfaced rather than folded in

**E45** — a `matches` condition resolves its subject to a `String` and immediately re-encodes it
for a byte matcher that already had the bytes, 624 times per operation on `apache_httpd`. Left
because it is not only an optimisation: the round trip is lossy, so malformed bytes reach the
pattern as U+FFFD rather than as themselves, and which is correct is a ruling about what a
`matches` test sees. D38 points at the direct path; nothing in the corpus distinguishes them, so
it needs a fixture and a decision rather than a quiet fix inside a change about names.

**E46** — `and` and `or` allocate a stream and a capturing lambda per evaluation. Pre-existing and
unmeasured, recorded because it sits on the path this phase has just claimed to improve.

### Phase 7 — the key index

`compile/KeyName`, a second interner on `VarNames`, and `Body.keyIndexes` becomes a list indexed
by slot. `CompiledOp.Key` and `CompiledOp.KeyGet` hold a `KeyName` where they held a string.

**A separate type from `VarName`, deliberately.** Keys are their own namespace — the compiler
keeps a separate declared set for them, and a key and a variable may share a name without meaning
the same thing — so they get their own table and their own slot space. Two slot numbers indexing
different arrays should not share a type: getting them the wrong way round would read a real index
and answer confidently, which is the failure this design keeps trying to make impossible.

*Small, and only findable because of a question.* This was §3's fourth site, listed on the first
day and carried through five phases as "open, unmeasured", and the phase 5 probe read **zero
lookups on every workload** while `log_sessions` was running two `key` instructions and a
`key-get` throughout. The probe instrumented the variable registry, and this map was not part of
it. Design 29 §9's rule for a third time, and the first time it caught an *instrument* rather than
a benchmark row: a probe that does not cover a thing reports nothing about it, and nothing reads
as zero.

#### The goal, stated with the qualifier that makes it true

**Within the engine, every run-time map lookup that remains is keyed by data.** Read rather than
probed, because after this phase there is nothing left to count:

| site | key | why it stays |
|---|---|---|
| `VarRegistry`'s extended table | a key-value capture's name, from the input | 14,140 per operation on `ausearch`; §1 exempts a data key, and §8 ruling 8 keeps the binding |
| `Switch.cases` | the selected value | data |
| `ValueMap.entries` | the subject value | data |
| the grouping index in `Body.file`, and a key's inner index | the grouping or key value | data |
| `FunctionRuntime.state` | whatever an extension function chooses | a state bag published to extension authors, not engine dispatch |

Everything else — `MatchCompiler.patterns`, `MatcherLibrary.definitions`, `Encoding.BY_LABEL`, the
unicode and regex-encoding caches, and the interner's own table (`Interner` in `compile`, which
hands `Names` to the graph) — is consulted while a configuration compiles and not while a record
runs.

**The qualifier is "within the engine", and it is not a hedge.** The pipeline's extension
functions keep string-keyed maps by contract: `FunctionContext.state()` *is* a
`Map<String, Object>` in the published interface, and `stroom:get`, `stroom:meta` and
`stroom:dictionary` look up whatever argument they are handed, which may well be a literal the
compiler knew. §3 cleared those as out of scope on the first day and that still holds — they are
an interface for extension authors rather than the compiled model. But the claim has to carry the
boundary, because the last time it was made without one it was wrong by exactly one site.

#### The audit, 2026-09-10

**A tooling assumption that was wrong, and had been all session.** Two imports in `Body` were
dead — `KeyName`, which the code never names because it only calls `.slot()` on a returned value,
and `HashMap`, left behind when `keyIndexes` stopped being one. Neither was reported, because
**this project's checkstyle has no `UnusedImports` rule**, and several times across phases 4 to 7
"checkstyle passed" was taken as evidence that an import sweep was unnecessary. It was not
evidence of anything. Swept all 28 files changed today: those two were the only ones, so nothing
else was left behind, but the reasoning that said so was unfounded rather than lucky.

**The key/key-get agreement is guarded, and that was proved rather than assumed.** If a `key` and
a `key-get` interned into different slots the lookup would read an unbuilt index, bind an empty
sequence, and produce no output — silently, because an empty sequence is a legitimate answer.
Breaking the interning deliberately fails four tests: three key cases in `SequenceIterationTest`
and the `log_sessions` golden. So the guard exists and no new test was needed — which is the
opposite conclusion from phase 6's audit, reached the same way.

**Two tidyings.** `VarNames`' class javadoc promised "every name" and then described only
variables, so it now says there are two namespaces and why they carry different types. And the
frozen check was written out twice, once per interner; it is one method.

**Checked and clear.** `internKey` runs before `Compiler.freeze()`, since keys are interned while
bodies compile and linking is what closes the table. A `key-get` whose `key` has not run yet reads
a null slot and gets `Map.of()`, which is what `getOrDefault` gave it. The list is per run and
sized from the frozen count, and a configuration with no keys allocates an empty one and never
indexes it. `internKey` is package-private where `intern` had to be public for a test — tighter,
and worth keeping that way.
