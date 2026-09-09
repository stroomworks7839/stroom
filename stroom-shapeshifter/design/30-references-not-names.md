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

### 5.2 What the frame model would be

Three frames on the run: one for the match, and stacks for the iteration and group frames, since
loops nest. Each holds its own named fields rather than entries in a map — a `long` where a
`long` is meant, and a list for `__group`. A reference that names an engine variable compiles to
a read of the frame it belongs to, which the compiler already knows, rather than a name resolved
through the scope walk. Reading one outside its frame stays exactly what it is today: the warning
at compile time, and an absent value at run time.

What that removes is not subtle: eight names leave every scope map, the per-match and
per-iteration writes stop being `store(name)` — an `entry` miss, a walk and a create — and the
reads stop being lookups. What is left in the registry is user names, which is what it is for.

### 5.3 What phase 3 counts

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

### 5.4 The exits, in order

1. **The frame model, §5.2.** First not because it is the biggest but because it is the one that
   is *right* independently of the number: it replaces a general mechanism with the specific
   shape the thing already has, using a classification the compiler already performs.
2. **Intern the remaining names to dense integers; a scope becomes an array.** Every name in a
   configuration is known at compile time. This removes the hashing and the key comparison —
   which is what `HashMap.getNode` at 7.2% actually *is* — while keeping the walk and every
   existing semantic, so it cannot change behaviour.
3. **Lexical addressing**, a (depth, slot) pair rather than a name, which buys the walk as well.
   Only worth designing once question 3 above is answered, and a design rather than a phase.

## 6. Phasing

Phases 1 and 2 are built (§9). What remains:

**Phase 3 — the counting.** §5.3's four questions, by instrumentation reverted afterwards. Its
deliverable is a paragraph in `benchmarks/points.md` and a ruling, not a code change. **This
phase exists because of design 29 §9**: three of that design's five phases could not be judged by
the workload named for them, and counting first is the cheap fix that was learned too late to
help two of them.

**Phase 4 — the frame model** (§5.2), which stands on its own argument rather than on phase 3's
number, and whose expected call count phase 3 states before it is built.

**Phase 4b — whichever further exit phase 3 points at**, or the decision that none of them earns
its complexity, closed as measured-and-accepted the way E39 was.

**Phase 5 — the key index**, if it is still worth it. `log_sessions` is the only fixture that
exercises it and no benchmark row does, so it may close as unmeasurable rather than build.

**Phase 6 — the record.** E44 closed or restated; design 10 §2's reference-resolution row
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
   exit it wants — with the exception below, which the same day's reading changed.
4. **The engine variables as a frame** (§5.1). *Ruled 2026-09-09: the frame model is the shape to
   build.* Raised as "isn't this just a context object that moves with the execution", which is
   what the code already does with three frames and a `shadow` at each push. It is ranked first
   in §5.4 on that argument rather than on its share of the traffic, which is still to be
   counted.
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
