# Design 37 — Clear, don't allocate; dispatch on a kind, not a class

*Proposed 2026-09-15, the morning after design 35's evening run. Design 35 settled what a name
is; the readings say the settled model costs the scan-heavy rows four to thirteen per cent
against the floor and nothing since phase 3 has bought it back. This design is the performance
work that follows, in phases, each gated by a measurement of the thing it claims to change.*

*Engine only. It touches no golden. Phase 6 writes challenger configurations for the fixtures
that use a map; the fixtures' inputs and outputs do not move.*

## 1. Where the run left the engine

Points 36 to 44 against the floor `597274d25e`, one boot, run rows (design 35 §12 has the
whole table):

| row | ph3 | close `9c77725d4e` | what the row is |
|---|---|---|---|
| progressive | −8.4% | −8.0% | steps, no variables to speak of |
| regex_lines | −7.4% | −5.2% | regex per line, one or two scalars |
| progressive_text | −4.4% | −4.1% | steps, text output |
| ausearch | −9.7% | −12.9% | map declared on the envelope, `get` on every field |
| log_sessions | −1.9% | −1.2% | the sequence-and-fold row |
| apache_httpd | — | +8.1% | reference-heavy, scalars |
| csv_header | — | −4.6% | reference-heavy, scalars |
| element_storm | +3.0% | +3.6% | XML, few variables |
| win_sec_strict | +1.5% | +1.2% | 63 templates, dispatch-heavy, control |

Three facts fall out of that table and set the order of this design.

**The scan rows regressed at phase 3 and stayed there.** Phase 3 put three things on the hot
path at once: the restore-on-exit registry (`push`/`pop` with an undo log per declared name),
the typed slot (`TypedValue[]` where a `byte[]`-carrying store had been), and the accessor
dispatch in `CompiledRefs` (`switch (ref)` grew a `Counter` and an `Accessor` arm). `progressive`
declares almost nothing, so on that row the cost is not the registry's *work*, it is the *shape*
of the code around it — which the streamlining attempt of 2026-09-15 then confirmed the hard
way: `PrintInlining` at the close showed `CompiledRefs.write` at 336 bytes against a
`FreqInlineSize` of 325 (*hot method too big*; 303 bytes at the floor), `lookup` at 89 (33 at
the floor). A per-arm rewrite (`0e852742e4`) gave `csv_header` +19% and `ausearch` +15% and cost
`progressive` −12% and `regex_lines` −20%; three hypotheses for the second half failed
(`7e9a44e130`, `85e959280e`, `e2de038753`), and the bisect by piece — §12 has the reading — puts
it on the `write`/`resolveValue` rewrite alone. **And the direction is the wrong way round**:
the close's `write` is the *big* one, all seven arms in one switch at 336 bytes, not inlined;
the rewrite was the *small* one, three hot arms and a default out of line, under the budget —
and the small one is the one that cost 20%. So "get under the inlining threshold" is not a
rule, it is one variable in a tree: a method that inlines takes its bytes into its caller, and
what the caller then fails to inline is the cost. **Inlining shape is a first-order effect on
these rows, it is not visible in the source, and it has to be read for the whole call tree,
not one method.** Phases 4 and 5 are built on that. The `lookup` and `set` pieces of the
rewrite measured harmless on `regex_lines` and were the ones that paid on `csv_header` (+19%)
and `ausearch` (+15%); they are kept, with the close's `write` and `resolveValue` restored
(2026-09-15, in the working tree), and read on the four rows before anything else moves.

**`ausearch` is the map row and it is the worst.** Every field on that row is a `get` on a map
declared on the envelope: the map is made on entry, filled, read, and dropped on exit for every
record, and each `get` hashes a `TypedValue` key into a `LinkedHashMap`. Phases 2, 3 and 6 are
that row's.

**The reference rows are fine.** `apache_httpd` +8.1% says the slot model (points 31, 32, 35)
still pays once a shape that defeated it was refused. Nothing here is aimed at those rows;
they are the control for not making them worse.

## 2. Method

Each phase names the measurement that decides it *before* the code is written, and the cheap
check comes before the benchmark (`design/benchmarks/points.md`, *the cheap check gates the
benchmark*):

- **Allocation** is read with `-prof gc` on the row the phase claims — bytes per operation,
  not ops per second. A phase that says "fewer objects" shows the B/op fall first; only then
  does it get an ops/s reading.
- **Dispatch and inlining** are read with `-XX:+PrintInlining` on one run, and with `javap -c`
  where the question is what the bytecode *is* (a `typeSwitch` bootstrap versus a
  `tableswitch`). Three benchmark rounds can still get this wrong; the compiler cannot.
- **Throughput** is read by interleaving (`engine-interleave.sh`) on the rows the phase names
  plus `progressive` and `regex_lines` as the canaries, three rounds, sign across rounds.
  Daytime runs are one-minute targeted combos; the full suite is an evening point.
- **Every phase is audited against this document before its commit**, as design 35's were.

The owner's list, as sent, maps onto the phases as: item 2 is phase 1; item 1 is phase 2;
item 3 is phases 3a and 3b; item 5 is phase 4; item 6 is phase 5; item 4 is phase 6; the
progressive investigation added afterwards is phase 7. The order was ruled 2026-09-15: the
census first because it is free and every later phase picks from it; the allocation phases
before the dispatch phases; the fixture experiments after the engine has stopped moving under
them; the progressive investigation last because it is the one whose shape is least known.

## 3. Phase 1 — the census of Java collections in `graph` and `exec`

Every `java.util` collection constructed or held in the two run-time packages, as of
`e2de038753`. The `TypedValue` collections live in `value`, are the engine's own, and are
listed at the end because phases 2 and 3 are about them. *Verdict* is a first position for
discussion, not a ruling.

| # | where | type | lifetime | what it is for | verdict |
|---|---|---|---|---|---|
| 1 | `CompiledProject.functions`, `.warnings` | `List` | compile product | the function definitions and compile warnings, read once per run | keep — not on the hot path |
| 2 | `Replacer.parse` `pieces` | `ArrayList` | compile | splits a replacement into `Piece[]` once | keep — compile time |
| 3 | `Names` | two `Map<String,…>` | compile | name → slot, name → declared type | keep — compile time; the run uses `VarName.slot()` |
| 4 | `CompiledOp.Switch.cases` | `Map<String, CompiledOp[]>` | graph, **read per match** | `Body:213` does `cases().get(selected)` with a `String` key — the selected value is rendered to a `String` and hashed on every `Switch` executed | **candidate** — key by bytes (a small sorted `byte[][]` with a binary search, or a `Utf8Bytes`-keyed table) so no `String` is made per match |
| 5 | `CompiledOp.Function.function` | `Function<List<TypedValue>, TypedValue>` | graph, called per match | the transform function's signature demands a `List` argument, which forces #8 | **candidate** — `TypedValue[]` signature |
| 6 | `Run.messages`, `Level.messages`, `Body.messages` | one `ArrayList` | per run | run messages | keep |
| 7 | `Body.warnedNumeric` | identity `Set` | per `Body` | warn-once guard per transform op | keep — written only on the first warning |
| 8 | `Body.inputs` (`:243`, `:451`, `:473`) | `ArrayList` | **per op per match** | the resolved inputs of a `Transform`/`Function` op | **candidate** — a reusable `TypedValue[]` on `Body` sized at compile time to the widest `select`; depends on #5 |
| 9 | `Body` function call `values`, `raw`, `sequences` (`:396–400`) | three `ArrayList`s | per function call | argument marshalling for user functions | candidate, lower — function calls are rare on the corpus |
| 10 | `Body.entries` (`:574`) | `ArrayList` | per call with a list argument | the populated entries of a list, hole-free, for a function's sequence argument | candidate with #9 |
| 11 | `Body.bindDense` (`:590`) | `TypedValue.List` | per bind | a dense copy for a parameter | keep the copy (ownership, design 35 §11); reuse under phase 2 |
| 12 | `Body.file` (`:748`) | `LinkedHashMap<String, Filed>` + one `ArrayList<Integer>` and one `Filed` per group | **per `ForEachGroup`** | files positions by group key, first-seen order | **candidate** — keys are rendered to `String` (a `TypedValue` is already a hash key); a reusable filing table on `Body`, cleared per walk, with positions in an `int[]` per group |
| 13 | `Body.items` (`:832`), `Item` | `ArrayList<Item>`, one `Item` per element | **per `ForEach`** | the elements of a collection as (key, value) pairs for walking | **candidate** — walk the collection directly; `Item` is only needed when sorting |
| 14 | `Body.sorted` `order` (`:904`) | `ArrayList<Integer>` | per sorted `ForEach` | the sort permutation | candidate — `int[]` |
| 15 | `Body` template call `resolved` (`:1104`) | `ArrayList<byte[]>` | per call op | arguments resolved in the caller's scope before the callee's opens | candidate — `byte[][]` |
| 16 | `CompiledRefs.members` (`:374`) + `Folds` | `ArrayList` | per `sum`/`avg`/`min`/`max` | copies a collection's members to a list to fold over | **candidate** — fold over the collection directly; `Folds` takes the collection |
| 17 | `Steps.match` `outputs`, `sequence` `local`, `concat` `all` | three `ArrayList`s | **per progressive match and per nested combinator** | step outputs, addressed by index into the concatenation | **leave — design 34 built this as one buffer on 2026-09-11 and reverted it on 2026-09-12** (`7e06e6c529`): `progressive` −4.09%, `progressive_text` −3.36%, twelve readings of twelve negative, cause unknown (E50). Nothing in the corpus nests, so the allocation it would save is not on any row; listed so it is not tried a third time without a new reason |
| 18 | `FunctionRuntime.state`, `.notRunInPreview` | `HashMap`, `HashSet` | per run | user-function state and preview guard | keep |
| 19 | `VarRegistry` | arrays only (`slots`, `marks`, undo log, `owner`, `types`) | per run, grown | the registry | keep — already allocation-free after warm-up except the collections it makes (#20–22) |
| 20 | `TypedValue.List` (`value`) | own `TypedValue[]`, ×2 growth from 4 | per declared list per scope entry | list variables | **stays as a concept; phase 2 reuses the object** |
| 21 | `TypedValue.Map` (`value`) | `java.util.LinkedHashMap<TypedValue,TypedValue>` | per declared map per scope entry | map variables | **stays as a concept; phase 2 reuses, phase 3 replaces the backing** |
| 22 | `TypedValue.Set` (`value`) | `java.util.LinkedHashSet<TypedValue>` | per declared set per scope entry | set variables | as #21 |
| 23 | `Frames.Group.members` | `TypedValue.List` | per group during a `ForEachGroup` body | the group's member positions, for `group()` | keep the frame; the list comes from #12 and follows it |

*What is not in the table because it is not there:* `Frames`, `Conditions`, `Output`,
`InputWindow`, `Level` and the whole of `graph` apart from #4 and #5 hold no `java.util`
collection at run time. Design 33's sweep did its work; what remains is the one it named as a
redesign and which has since been tried and reverted (#17), and what design 35 added (#12,
#13, #16, #20–22).

## 4. Phase 2 — a collection is cleared on exit, not remade on entry

**What happens now.** A declared list, map or set is not made at `push`; `list(name)`,
`map(name)`, `setOf(name)` make it on first use in the slot (`VarRegistry:270–303`). On `pop`
the slot is restored to what it held outside — for a declared name that is null — and the
object is dropped for the collector. A map declared *with entries* is worse: `push(declared,
initial)` calls `set`, which copies the initial table into a new `LinkedHashMap` on every
entry. `ausearch` does this per record; `win_sec` does it per event on a 16-template
configuration where several templates declare a map.

**The rule.** A slot that a declaration owns keeps its collection object across entries. On
`pop`, a collection in a slot whose saved outer value is null is *cleared* and *parked* in a
per-slot spare (`spare[slot]`); `list(name)` and its siblings take the spare before making a
new one; `push(declared, initial)` fills the spare from the initial table instead of copying
into a new object. The live-element count is unchanged: `release` subtracts what the
collection held, `clear` leaves it empty, and the next fill counts again.

**Why it is safe, and the one place it would not be.** Design 35 §11 ruled copy-on-store: a
collection has one owner, so no second name reaches the object after its scope closes. Every
read that hands a value out — `last`, `get`, `head`, a bound `as` — hands out an *element*, and
elements are scalars by the run-time check in `Body.bind`; `keys()`/`values()` answer fresh
lists; a mutation on a function's answer is refused. The one holder that survives a scope is
`Frames.Group.members` (#23), and its frame closes with the walk that made it, inside the
scope. So nothing observes the reuse — **unless a nested collection value is parked**: a list
stored under a map key is a copy owned by the map, and `clear` on the map drops it. Those
inner copies are *not* pooled in this phase; they are the collector's, as before. Pooling
them is a later question and the census will say whether any corpus row makes one.

**What `clear` costs.** `LinkedHashMap.clear` walks the table and nulls it — O(capacity), and
a table that once grew large stays large. That is the right trade on these rows (the same
template sees the same shape of record every time), and phase 3's own map makes `clear`
O(size). `TypedValue.List` clears by `size = 0` and nulling the used prefix.

**Measure.** `-prof gc` on `ausearch` and `win_sec` before and after — the claim is B/op
falls by the collections' share and nothing else moves; then interleave the two rows with the
canaries. `progressive` should not move at all: it declares nothing.

## 5. Phase 3 — what backs a map, and whether order is worth its node

**The question as put:** `LinkedHashMap` keeps insertion order, which keeps fixture output
deterministic when a configuration walks a map without a sort, and costs something for it. Is
the something worth it?

**What it costs, exactly.** A `LinkedHashMap.Entry` is a `HashMap.Node` (hash, key, value,
next) plus `before` and `after`: 56 bytes against 40 with compressed oops, and two pointer
writes on insert and on remove. `get` is identical to `HashMap.get`. So the order costs a
third more per entry and nothing per read — and `ausearch` is a read row. The order is not
what that row pays for; the *node* is. Every `put` allocates one, whichever map it is, and a
`Utf8Bytes` key is hashed byte by byte on every `get` because its hash is uncached (design 35
kept it uncached for object size, 2026-09-15, measured no change either way).

**So the choice is not `LinkedHashMap` versus `HashMap`.** Dropping order would break the
determinism the fixtures depend on and buy 16 bytes per entry and nothing per read. The
choice is between the JDK's node-per-entry map and a map of the engine's own with no node at
all: dense parallel arrays `keys[]`, `values[]`, `hashes[]` in insertion order and a sparse
`int[] index` open-addressed over them — the shape CPython's `dict` took in 3.6, where order
is a by-product of density rather than a linked list. Insert is an append plus one index
write; `get` is a hash, a probe in `index`, one key compare; `clear` is `size = 0` plus a
fill of `index`; `remove` leaves a tombstone in the dense arrays and compacts when they are
mostly holes. Nothing is allocated per entry; the whole map is three arrays that phase 2
keeps across entries. The set is the same structure without `values[]`.

**What it needs from the key.** A `TypedValue` key's `hashCode` and `equals`, which the map
already uses. Whether to cache the `Utf8Bytes` hash returns as a question here rather than in
the registry: with `hashes[]` in the map, the hash is computed once per *put* and once per
*get* key — and the `get` key is usually a `Bytes` freshly matched from the input, so caching
on the object would not help it anyway. The map's `hashes[]` is the cache that matters.

**Two steps, ruled 2026-09-15.** *3a* puts a number on the order: a probe branch swaps
`LinkedHashMap` for `HashMap` (and the set likewise), the fixtures that walk a map without a
sort fail — which is the point, and they are listed — and `ausearch`, `win_sec`,
`log_sessions` and `apache_httpd` are interleaved against the head. If the order costs
nothing measurable, the case for 3b rests on the node alone, and the reading says how much
that is worth. *3b* is the engine's own ordered map and set as a phase of its own, with the
code reviewed and the structure tested as a whole before it goes near the registry: a
micro-benchmark of `put`/`get`/`clear` against `LinkedHashMap` at the corpus's sizes (the
`ausearch` map holds a few dozen keys; `win_sec` a handful), the collection tests run against
both backings, then `-prof gc` and the interleave on the four rows. The fixtures' outputs pin
insertion order and pass unchanged, or the map is wrong.

## 6. Phase 4 — a kind on every value and instruction

**The claim as put:** an `int` type identifier on objects would remove `instanceof` from
switches, tests and `equals`, and might beat the class comparison some `equals` methods use.

**Where the gain is and is not.** `instanceof` against a *final* class — every `TypedValue`
class is final — compiles to a klass-pointer load and one compare, which is what an `int`
field load and compare is; and `other.getClass() == List.class` (`TypedValue:763`, `:871`,
`:947`) is the same instruction sequence against a constant. So an `int` in `equals` and in a
single `instanceof` test buys nothing measurable, and this phase does not claim it does.

**The gain is in the switches.** `javap -c` on `CompiledRefs` as built (JDK 25) shows seven
`invokedynamic … typeSwitch` sites — three over `CompiledRef`, four over `Collection` — each
followed by a `tableswitch` on the `int` the bootstrap answers. So the switch itself is already
a jump table; what sits in front of it is a call into a bootstrap-generated method that tests
the receiver against the arms' classes in order and answers the arm number. C2 can inline that
method, and when it does the arms' `instanceof` chain is paid on every dispatch and counts
against the inlining budget of the method that holds the switch; when it does not, it is a
call. `switch (ref.kind())` on an `int` field is one field load and the same `tableswitch`,
with no bootstrap, no chain and a few bytes — and that is the budget `write` blew at 336. The
`checkcast` that follows in each arm is one compare that C2 usually folds. The switches in
question are the ones §3's grep found on the hot path: `CompiledRefs` (`:72`, `:120`, `:145` —
`write`, `resolveValue`, `exists`), `Body.run` (`:183` over the op), `Steps.step` (`:153`),
`Conditions` (`:52`), `Frames.value` (`:168`), and the collection switches in
`CompiledRefs.accessor` and `Body.items`.

**How the kind is carried.** Three ways, and the choice is the phase's first decision:

1. *A method* `int kind()` returning a constant per class. Cheapest to write; at a call site
   that sees many receiver classes it is a megamorphic virtual call, which is what the
   `typeSwitch` was avoiding. Ruled out for the hot switches.
2. *A field* in the object, set in the constructor. A `TypedValue` is an interface, so this
   needs an abstract base class carrying one `final byte kind` — a change to every value's
   header (no size change: the byte fits the padding of every class in the hierarchy today).
   For `CompiledRef` and `CompiledOp`, which are records, the kind is a record component set
   by the canonical constructor.
3. *A kind on the compiled instruction only*, not on values. `CompiledRef` and `CompiledOp` are
   the switches that run per match; `TypedValue` switches run per *collection* operation. Start
   here, measure, and extend to values only if the accessor switches show up in phase 5's
   census.

Recommendation: 3, then 2 if the census says so. And the kind is where phase 5's category
split hangs: `kind >> 4` is the category, `kind & 15` the member.

**Measure.** `javap -c` on `CompiledRefs` before and after shows `typeSwitch` gone and
`tableswitch` present — that is the check that the mechanism is real. Then `PrintInlining`
shows `write` and `resolveValue` under 325 bytes, and the `progressive`/`regex_lines`
interleave shows the sign — this is the phase that is *for* those two rows.

## 7. Phase 5 — an inline census, and switches split by category

**The census first.** One run per corpus row with `-XX:+PrintInlining -XX:+UnlockDiagnosticVMOptions`,
filtered to `stroom.shapeshifter`, tabulated by method and by reason: *hot method too big*,
*callee is too large*, *already compiled into a big method*, *too many arguments*, *not
inlineable*. The output is a table in this section: every run-time method over 325 bytes,
every hot call site that failed to inline and why, per row. `write` at 336 and `lookup` at 89
are two lines of it; the streamlining attempt shows why the whole table is needed before the
next line of `CompiledRefs` is touched.

**Then the split.** The rule the earlier work reached — a switch with many arms must be big,
so it cannot inline, so leave it — is true of a switch with many arms *in one method*. It is
not true of two switches. `Body.run`'s op switch has arms for output leaves, mutations, walks,
captures, calls, conditionals; a first `switch (op.category())` with six arms dispatches to
six methods each small enough to inline where it is hot and stay out of line where it is not.
The hot category on `progressive` is *output*; on `ausearch` it is *output* and *read*; the
walk and mutation arms are cold on both and today sit in the same method as the hot ones,
counting against its budget. The same applies to `CompiledRefs.write`: a `Bytes`/`LocalGroup`
/`RemoteVar` fast path in one method under the budget, and everything else behind one call.
That is what `0e852742e4` tried by hand and got half right; with the kind from phase 4 the
fast path is a `tableswitch` and the bytes it costs are known before the benchmark runs.

**Measure.** The census table before and after, then the interleave on every row. The gate
is the table: a method that was *too big* is under budget, and no method that inlined has
stopped.

## 8. Phase 6 — the shape of a configuration that uses a map

**Why last.** A fixture's configuration is what the benchmark measures; changing it while the
engine is moving confounds both readings. And it is not engine work — it is finding out what
the surface makes cheap, so that migration and authors choose the cheap shape.

**The rows.** `win_sec` (three maps), `apache_httpd` (one), `ausearch` (one, read per field),
`log_sessions` (one, `put`/`get` across records), and `keys_lookup` in the XML bench. Each
gets a *challenger* configuration beside its current one, as the XML bench already does, and
the same input and output.

**The three shapes, as put, and what each is for:**

1. *Specific variables where the keys are known.* When a configuration knows every key it will
   read — `ausearch` knows its field names; `apache_httpd` knows its escapes — a scalar per key,
   declared on the template that produces it, is a slot write and a slot read with no hash at
   all. This is the shape design 35 made cheap and the one the final audit moved
   `apache_httpd` to; `ausearch` has not been. Expect the largest gain here, and expect it to
   say that a map is for keys the configuration *does not* know.
2. *Two lists, one of keys and one of values, on a shared index.* For source key names that
   are not known in advance but are read positionally or walked, not looked up — two
   `append`s per pair and a `ForEach` with `index()` to pair them. No hashing on write; a read
   by name is a linear scan, so this shape is for walking, not for `get`.
3. *Capture the pair's bytes and split on use.* One capture per pair of the whole `key=value`
   run, and a split at the point of output. This is the shape DS3 configurations often already
   have and the one migration could emit without a declaration at all; it costs nothing on
   the match and a scan on each use.

**Measure.** Each challenger on its row against the current configuration, interleaved, on
the engine as it stands after phase 5. The result is a table of *shape → cost* per row and a
paragraph in design 35 §5 saying which shape a map is for.

## 9. Phase 7 — the progressive match, reconsidered from what it is for

**Ruled 2026-09-15: think wider first.** The row furthest from the floor is `progressive`; the
one obvious thing about it — the lists — was tried by design 34 and lost 4% for a reason still
unknown (E50); and nothing in the corpus nests, so the flat path is the whole of what the
benchmark sees. Before chasing the per-match costs listed below, this phase asks what a
progressive match *is for*, what the engine is trying to achieve with it, and whether the
approach — a general interpreter walking a step program with a switch per step and a value per
output — is the right one for that. It may end in its own design.

**The questions, in order.**

1. *What is a progressive match for?* Binary and length-prefixed structure — varints, fixed
   widths, endianness, embedded codecs, seeks — where a regex cannot go; and byte-level text
   scanning (`Tag`, `TakeWhile`, `TakeUntil`) where a regex could but a cursor is cheaper.
   These are two uses with different shapes: the first reads a *record* and hands its fields
   out; the second *tokenises*. Are they one instruction set, or two?
2. *What does it produce, and who consumes it?* Today a `MatchResult` with a `groups[]` — one
   value per step, plus a copy of the whole span as group 0 — so that the step outputs can be
   referred to like regex groups. Design 35 made every binding a declaration; do the step
   outputs need to become groups at all, or can a step write straight to the declaration that
   names it, with no intermediate value for the steps nobody reads?
3. *Is a general interpreter the right execution?* A step program is fixed at compile time.
   The alternatives to a `switch` per step are: fusing adjacent steps at compile (a `Tag`
   followed by `TakeUntil` is one scan); specialising the program into a chain of step objects
   each with one `int run(cursor)` — which is a virtual call per step, monomorphic per site
   only if the chain is unrolled; or generating a straight-line method per program (a hidden
   class via `MethodHandles.Lookup.defineHiddenClass`), which is what a regex engine's compiled
   form is, and which makes the program the JIT's unit rather than the interpreter. Each is a
   different order of work and this phase costs them, it does not pick one blind.
4. *What is the cursor?* Position, high-water mark, decoding and encoding are threaded through
   nine arguments today. A cursor object that owns them, allocated once per run rather than
   once per match, is the small version of question 3 and may be most of the gain.
5. *What do the rows need?* The five progressive fixtures use fourteen of twenty-four step
   kinds and no combinator (design 34 §5). Whatever is proposed is read on those, and the
   combinator path keeps the seventeen tests from `1df2fc959c` as its pin.

**What the flat path does per match today**, kept as the cost inventory the questions are
asked against (`Steps.match`, 52,431 matches per `progressive` operation): an `outputs` list
and its array; per step, two `instanceof` tests for the backward seeks before the dispatch, a
nine-argument call with an empty `List.of()`, and a `Result` record for `(output, consumed)`
— the return type is an allocation; a boxed `Integer` from `count(...)` so that null can mean
failure; at the end a `groups[]`, **a copy of the whole matched span into group 0** whether or
not it is read, and a `MatchResult`. The `Result` per step and the group-0 copy per match are
the two that scale with the row, and neither is a list — but whether either is worth a commit
depends on the answer to question 2, which may remove both.

**Output.** A section here — or a design of its own — with the answers, a cost per alternative
in question 3, and a proposal. Then, if the proposal is built, one commit per change, each
interleaved six rounds on `progressive` and `progressive_text` as design 34 was, so the reading
is comparable to the one that reverted it.

## 10. Rulings sought

| question | position taken here | ruled |
|---|---|---|
| Order of phases | census, reuse, map backing, kind, inline split, fixture shapes, progressive | **ruled 2026-09-15: reuse first**, then the map, before the kind |
| Phase 2 pools nested collection values (a list under a map key) | no — inner copies stay the collector's | — |
| Phase 3 keeps insertion order | yes — the fixtures pin it; the saving is the node, not the order | **ruled 2026-09-15: measure `HashMap` first on a probe branch, so the order's cost is a number; the engine's own ordered map is then a phase of its own (3b) so the code can be inspected and tested as a whole** |
| Phase 4 carries the kind on instructions first, values second | yes | **ruled 2026-09-15: yes** |
| Phase 6 replaces a fixture's configuration or adds a challenger | challenger, beside it | **ruled 2026-09-15: challengers stay beside the current configuration**, measured every run, as the XML bench does |
| Phase 7 may make group 0 lazy (a view materialised on read) | yes — nothing observable changes; the copy moves to the reader | **superseded 2026-09-15: phase 7 first asks what a progressive match is for and whether the approach is right, before any per-match cost is chased** (§9) |
| The `lookup` and `set` pieces of the streamlining are kept now | yes — restore the close's `write`/`resolveValue` onto head, read the four rows | **ruled 2026-09-15: yes** |
| Cadence | daytime targeted one-minute interleaves per phase on its rows plus the two canaries when the box is quiet; full-suite points collected into one evening run per two or three phases | **ruled 2026-09-15** |
| Target for the scan rows | the floor `597274d25e` is the reference, not a gate: design 35 corrected behaviour rather than chasing speed, so its cost cannot be insisted away; every improvement counts | **ruled 2026-09-15** |

## 11. What would make this a mistake

- **Reuse that is observed.** If any holder keeps a collection past its scope, phase 2 turns
  a dropped object into shared mutable state. The census of holders in §4 is the argument;
  a test that parks, re-enters and reads through every read form is the pin.
- **A kind that is a second truth.** If the kind can disagree with the class — set wrong in a
  constructor, missed on a new subtype — the switch is wrong silently where `instanceof`
  could not be. The kind is assigned in one place (the sealed hierarchy's constructor), and
  a test asserts kind ↔ class over every permitted subtype.
- **Optimising the census instead of the profile.** Twenty-three rows in §3 and most are
  cold. The B/op reading decides which matter; the ones marked *candidate, lower* are
  written down so that they are not rediscovered, not so that they are done.
- **A design 34 again.** That buffer removed allocation nothing in the corpus performed and
  cost the flat path 4% for a reason nobody found (E50). Every allocation phase here names a
  row that *performs* the allocation it removes, and reads B/op on that row before ops/s on
  any.
- **Reading the wrong row.** Every phase names its row and the two canaries. A gain on
  `ausearch` with a loss on `progressive` is the 2026-09-15 result again, and it is a loss.

## 12. The plan, in phases

Each phase: build, audit against this section, commit when asked, benchmark point when asked.

### Phase 1 — census

§3 is the census; this phase is its review and the B/op reading of the rows it names
(`-prof gc` on `progressive`, `ausearch`, `win_sec`, `log_sessions`, `regex_lines`) so that
the *candidate* verdicts become *do* or *leave* on evidence. No code.

### Phase 2 — clear, don't allocate

§4. Gate: B/op on `ausearch` and `win_sec` falls; `progressive` unchanged, since it declares
nothing; outputs unchanged.

### Phase 3a — what insertion order costs

§5. A probe branch, not a commit on the line: `HashMap` and `HashSet` behind the map and set,
interleaved on the four map rows. Output: the number, the list of fixtures that depend on
order, and a go/no-go for 3b written into §5.

### Phase 3b — the engine's own ordered map and set

§5. Its own phase: the structure, its tests against both backings, the micro-benchmark, review
of the code as a whole; only then the swap. Gate: `ausearch` B/op and ops/s; every map fixture
passes byte-for-byte.

### Phase 4 — the kind

§6, instructions first; values only if phase 5's census names a value switch over budget. Gate: `javap` shows `tableswitch`; `PrintInlining` shows `write`
and `resolveValue` under budget; `progressive` and `regex_lines` recover toward the floor.

### Phase 5 — inline census and the category split

§7. Gate: the census table, before and after.

### Phase 6 — fixture shapes

§8. Gate: the shape → cost table, and a sentence in design 35 §5.

### Phase 7 — the progressive match, reconsidered

§9. A thinking phase first: what a progressive match is for, what it produces, whether the
interpreter is the right execution, costed per alternative. Output is a proposal, possibly a
design of its own. Anything then built is one commit per change, six interleaved rounds on
`progressive` and `progressive_text`; a negative reading is reverted as design 34 was and the
reason goes into E50's successor.

### Before phase 1 — keep what the streamlining got right

The close's `write` and `resolveValue` restored onto head with the `lookup` and `set` pieces
kept (§1). Gate: a one-minute interleave against the close on `csv_header`, `ausearch`,
`progressive`, `regex_lines`, three rounds — the first two hold their gain, the second two
read at zero.

*Read 2026-09-15 13:00*, `808c9e9ffd` (this code, less the comment) against the close, three
rounds, four workloads per leg:

| row | r1 | r2 | r3 | sign |
|---|---|---|---|---|
| csv_header | −0.1% | +1.4% | +1.4% | 2/3 |
| ausearch | +9.1% | +14.8% | +12.9% | 3/3 |
| progressive | −0.2% | −0.0% | +0.0% | 1/3 |
| regex_lines | −2.8% | −2.5% | −3.0% | 0/3 |

Half of the gate held. `ausearch` keeps its gain, which is the `lookup` fast path on the map
row's `get`s. `csv_header`'s +19% is gone: it belonged to the `write` rewrite, the same piece
that cost `regex_lines` 20%, so those two rows want opposite things from that method and phase
4 has to find the shape that serves both. `progressive` is flat. And `regex_lines` reads
−2.5% to −3.0% on all three rounds from `lookup` and `set` alone — small, consistent, and not
what the single-workload bisect legs showed (+3.6%, −0.1%, −14.1%), which were noisier. Kept
as ruled, with that 3% written down as phase 4's to recover along with the rest; whether it is
`lookup` or `set` is one more one-minute interleave and is asked for before phase 4 starts.

### Where the bisect stood when this was written

The 2026-09-15 bisect of the streamlining commit `0e852742e4` by piece, interleaved with the
close `9c77725d4e` on `regex_lines`, three rounds each: with the `write`/`resolveValue`
rewrite removed and the other two pieces kept (`808c9e9ffd`) the row reads +3.6%, −0.1%,
−14.1% — noise around zero; with the `lookup` rewrite removed instead (`44f8374844`) it reads
−23.2%, −22.8%, −12.2%; with the `set` rewrite removed (`90204f3ace`) it reads −28.2%,
−20.4%, −25.9%. The regression is the `write`/`resolveValue` rewrite alone, and the `lookup` and `set`
pieces are keepable. Phase 4 is where `write` is redone, on a kind, with the byte count
read before the benchmark.
