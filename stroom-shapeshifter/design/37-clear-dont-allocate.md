# Design 37 — Clear, don't allocate; dispatch on a kind, not a class

*Proposed 2026-09-15, the morning after design 35's evening run. Design 35 settled what a name
is; the readings say the settled model costs the scan-heavy rows four to thirteen per cent
against the floor and nothing since phase 3 has bought it back. This design is the performance
work that follows, in phases, each gated by a measurement of the thing it claims to change.*

*Engine only. It touches no golden. Phase 8 writes challenger configurations for the fixtures
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
(`7e9a44e130`, `85e959280e`, `e2de038753`), and the bisect by piece — §13 has the reading — puts
it on the `write`/`resolveValue` rewrite alone. **And the direction is the wrong way round**:
the close's `write` is the *big* one, all seven arms in one switch at 336 bytes, not inlined;
the rewrite was the *small* one, three hot arms and a default out of line, under the budget —
and the small one is the one that cost 20%. So "get under the inlining threshold" is not a
rule, it is one variable in a tree: a method that inlines takes its bytes into its caller, and
what the caller then fails to inline is the cost. **Inlining shape is a first-order effect on
these rows, it is not visible in the source, and it has to be read for the whole call tree,
not one method.** Phases 6 and 7 are built on that. The `lookup` and `set` pieces of the
rewrite measured harmless on `regex_lines` and were the ones that paid on `csv_header` (+19%)
and `ausearch` (+15%); they are kept, with the close's `write` and `resolveValue` restored
(2026-09-15, in the working tree), and read on the four rows before anything else moves.

**`ausearch` is the map row and it is the worst.** Every field on that row is a `get` on a map
declared on the envelope: the map is made on entry, filled, read, and dropped on exit for every
record, and each `get` hashes a `TypedValue` key into a `LinkedHashMap`. Phases 2, 4, 5 and 8 are
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
item 3 is phases 4 and 5; item 5 is phase 6; item 6 is phase 7; item 4 is phase 8; the
progressive investigation added afterwards is phase 9. The order was ruled 2026-09-15: the
census first because it is free and every later phase picks from it; the allocation phases
before the dispatch phases; the fixture experiments after the engine has stopped moving under
them; the progressive investigation last because it is the one whose shape is least known.

## 3. Phase 1 — the census of Java collections in `graph` and `exec`

Every `java.util` collection constructed or held in the two run-time packages, as of
`e2de038753`. The `TypedValue` collections live in `value`, are the engine's own, and are
listed at the end because phases 2, 4 and 5 are about them. *Verdict* is a first position for
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
| 21 | `TypedValue.Map` (`value`) | `java.util.LinkedHashMap<TypedValue,TypedValue>` | per declared map per scope entry | map variables | **stays as a concept; phase 2 reuses, phase 5 replaces the backing** |
| 22 | `TypedValue.Set` (`value`) | `java.util.LinkedHashSet<TypedValue>` | per declared set per scope entry | set variables | as #21 |
| 23 | `Frames.Group.members` | `TypedValue.List` | per group during a `ForEachGroup` body | the group's member positions, for `group()` | keep the frame; the list comes from #12 and follows it |

*What is not in the table because it is not there:* `Frames`, `Conditions`, `Output`,
`InputWindow`, `Level` and the whole of `graph` apart from #4 and #5 hold no `java.util`
collection at run time. Design 33's sweep did its work; what remains is the one it named as a
redesign and which has since been tried and reverted (#17), and what design 35 added (#12,
#13, #16, #20–22).

### What phase 1 read — 2026-09-15

`-prof gc` on head (`54a5dcdf94`) and on the floor, one fork, five rows; then JFR allocation
samples on head, by class and by the first engine frame that allocated. Files under
`/home/dev1/engine-bench/gcprof/`.

| row | floor B/op | head B/op | Δ bytes | Δ ops/s, same runs |
|---|---|---|---|---|
| progressive | 13.69 MB | 13.69 MB | 0.0% | −8.0% |
| ausearch | 9.44 MB | 9.17 MB | −2.8% | −8.2% |
| win_sec | 3.77 MB | 3.95 MB | +4.9% | +55.3% |
| log_sessions | 22.45 MB | 21.75 MB | −3.1% | +0.6% |
| regex_lines | 3.92 MB | 3.92 MB | 0.0% | −11.6% |

**The scan rows' regression is not allocation.** `progressive` and `regex_lines` allocate
byte-for-byte what the floor allocated and read 8% and 12% slower. Whatever design 35 cost
them is in the code, not the heap, which is phases 6 and 7's brief and is why they, not phase
2, are the scan rows' phases.

**What the bytes are**, by share of allocated bytes on head:

| row | largest | second | third | the collections (#20–22) |
|---|---|---|---|---|
| ausearch | `byte[]` copied by `ByteMatcher.groupBytes` 55% | `byte[]` in `resolveValue` (composite) 10% | `Utf8Bytes` wrappers 12% | `LinkedHashMap.Entry` + table + map **4.3%** |
| win_sec | `Utf8Bytes` wrappers 49% (40% from `TypedValue.utf8`) | `groupBytes` copies 23% | `MatchResult` + groups array 10% | **4.6%**; `Body.inputs` (#8) 2.6% |
| progressive | input buffer `InputWindow.read` 38% | `Steps.Result` 13% | `Utf8Bytes` 11%, `Integer` from `unsigned` 8%, groups array 7.5%, list + backing 10%, group 0 copy 5%, `MatchResult` 3% | none |
| log_sessions | `groupBytes` copies 37% | `java.time` parsing (`Parsed`, `LocalTime`, `LocalDate`, `HashMap`) ~20% | `Body.Item` (#13) 5.5%, `String` from `asString` 6.5%, `Pattern` compiled per `Transforms.split` 2.7% | small |
| regex_lines | `groupBytes` copies 61% | `Splitter` copies 12% | `Utf8Bytes` 6%, groups array 10%, `MatchResult` 6% | none |

**What that does to the verdicts.**

- *Phase 2 is worth about four per cent of the bytes on the rows it is for.* The map per record
  on `ausearch` is 4.3% of allocation, on `win_sec` 4.6%. It is still ruled and still cheap,
  and the expectation is now written down: a few per cent of allocation, not the row's deficit.
- *The largest allocator on every regex row is a copy of bytes that are already private.*
  `ByteMatcher`'s own javadoc: "groups are returned as offsets into the caller's array — no
  copying; call `groupBytes` only when a copy is genuinely needed." `Level.regexMatch` (`:696`)
  calls it for every group of every match and wraps each copy in a `Utf8Bytes`. The JFR stacks
  say which entry point each copy sits under — `Level.stream` is the root over the window,
  `Level.dispatch` is what a body's nested match uses over a resolved array:

  | row | copies under a nested dispatch | copies at the root |
  |---|---|---|
  | regex_lines | 61% | 22% (the line splitter) |
  | ausearch | 63% | small |
  | log_sessions | 0% | 37% |
  | win_sec | 16% | 7% |

  A nested match runs over a `byte[]` a body resolved (`Body:1150`), which is a group's copy or a
  composite's fresh array — private, stable, and copied *again* by every group the nested match
  produces. That is phase 3, and it is the largest lever in this table by a factor of ten.

- *Two small things fall out for free.* `Transforms.split` compiles a `Pattern` per call
  (`Pattern.quote` then `String.split`), 2.7% of `log_sessions`; and `Body.items` (#13) is
  5.5% there — both move to *do*.
- *Progressive's inventory is confirmed and priced* for phase 9: of the 62% that is not the
  input buffer, `Result` is the single largest at 13%, then the wrapper and boxing of every
  step's output (`Utf8Bytes` 11%, `Integer` 8%), then the two arrays and the list per match.
  Design 34 went after the list, which is 10%.

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
template sees the same shape of record every time), and phase 5's own map makes `clear`
O(size). `TypedValue.List` clears by `size = 0` and nulling the used prefix.

**Measure.** `-prof gc` on `ausearch` and `win_sec` before and after — the claim is B/op
falls by the collections' share and nothing else moves; then interleave the two rows with the
canaries. `progressive` should not move at all: it declares nothing.

### What was built — 2026-09-15

*Built as ruled, with two departures the audit records.* `VarRegistry` gains `spare[]`, one
parked collection per slot; `discard(slot)` — every place a slot's collection is let go: the
exit, a bind over it, an adopt over it, a global redeclaration — subtracts its elements,
clears it and parks it; `list`/`map`/`setOf` take the spare before making; and a store of a
collection fills the spare in place through a new `copyFrom` on each collection class
(`copy()` is now `new` plus `copyFrom`, one truth), so a map declared with entries refills the
parked map from its table on every entry. The copy is made before the old value is discarded,
because the value may be reachable through it — a list held under one of the map's keys,
stored over the map — and a name set to what it already holds is a no-op. Both are pinned.

*Departure one: the rule parks every discarded collection, not only one whose saved outer value
is null.* A collection let go is let go whatever is restored; the narrower condition bought
nothing and would have left a bind-over-a-collection allocating.

*Departure two, and the one the audit was for: the holder the design missed.* §4 above said the
only holder surviving a scope was the group frame. `Body.variable` is another: a variable whose
body captures into its own name reads the inner scope's list, pops, and stores it outside — and
the exit now parks and clears that list, which `VariablePromotionTest` caught on the first run
(`first= second= latest=`). The fix is better than the old code: `VarRegistry.detach(name)`
takes the collection out of its slot uncounted, the scope exits, and `adopt` installs it
outside — a move, where before it was a copy. Nothing else crosses an exit: every other read
hands out an element, `keys()`/`values()` answer fresh lists, and the frames' group list is
built by the walk, not taken from a slot.

*What it read.* B/op, one fork: `ausearch` −2.2%, `win_sec` −2.1%, `log_sessions` and
`progressive` 0.0%. Less than the 4.3% the census gave the map, because what is reused is the
map object and its table; the `LinkedHashMap.Entry` per `put` is still made and dropped on
every record, and that is phase 5's.

*The daytime interleave was not a reading.* Three rounds against `160443c430` on `ausearch`,
`win_sec`, `progressive`, `regex_lines` with the box's load near 1.0 and three other sessions
active: signs 1/3, 2/3, 2/3, 2/3, per-leg scores wandering further than the phase could move
them (`progressive` 368 to 430 ops/s across legs with identical bytes, and one leg where every
row rose together). A two per cent effect needs a quiet box; the throughput reading goes to
the evening run as a point.

## 5. Phase 3 — the root copies, everything below slices

*Proposed 2026-09-15 from phase 1's reading; the shape is the owner's, on the observation that
the only volatile bytes are the ones the window presents to a root match.*

**The rule.** Bytes reach a match by exactly two routes. From the input window, through
`Level.stream`: those bytes are volatile, because the window moves, and a match over them
copies what it keeps. From a value a body resolved, through a nested `Level.dispatch`: those
bytes are already a private array that nothing overwrites, and a match over them keeps
*slices* — array, from, to — not copies. The engine knows which route it is on structurally,
by the entry point, so nothing is flagged at run time. The root copies once; every level below
it slices what the root copied.

**What that changes and what it does not.** Today every level copies every group. The nested
levels' copies — 61% of allocated bytes on `regex_lines`, 63% on `ausearch` — stop. The root's
copies are the stable array the nested levels already run over, so the cost of "keeping the
top-level bytes so that slices can refer to them" is not new: it is being paid now, and the
question at the root is only whether one span copy per match beats one copy per group, which
depends on how much of the span the groups cover (`log_sessions`, whose copies are all at the
root, is where that is read).

**What a slice needs.** `ByteSlice` (named 2026-09-15): a third `Bytes` implementation beside
`Utf8Bytes` and `EncodedBytes`, over `(array, from, to, encoding)`, with `equals`, `hashCode`,
`isEmpty`, `asString`, the encoding conversions and the output write all working on the range.
Its own class, not a range added to the two that exist, so the whole-array path stays exactly
as it is and the sealed hierarchy grows by one arm. It carries its encoding because a group
matched from input has the match's, and one class serves both encodings as `EncodedBytes`
serves any whole array. **Equality and hash are by text across all three**: the value class
already holds `Utf8Bytes` and `EncodedBytes` equal when their text is, and a slice joins that
rule — a slice used as a map key must hash as the whole-array value of the same text does, or a
`get` with a freshly matched slice misses a key that was put as a copy; that pin is written
before the class is. Fifteen call sites take a whole `byte[]` today (`asUtf8()`), eleven of
them inside the value class; the output writer and `Comparisons` are the ones that must take a
range, or the slice materialises on use and the copy is back. A nested dispatch's content
(`Body:1150`) becomes a range too, so a slice is matched over without being materialised.

**The two costs, and where each is bounded.**

1. *Retention.* A slice stored into a run-lifetime variable pins its parent array, which is at
   most one root match's span — one record. Where that matters, the store into a run-lifetime
   slot compacts the slice into its own array; that is the copy-on-store seam design 35 §11
   already has for collections, applied to a scalar on the source template only. Per-execution
   slots never need it: their lifetime is inside the match's.
2. *Materialisation on demand.* A transform that needs a whole array, or a function argument,
   copies the slice when it asks. How often that happens per row is a count, taken before the
   design is finished: one instrumented run of the fixtures classifying every group value's
   use as written to output, stored per execution, stored for the run, or handed to a
   transform. The first two never copy; the third copies once; the fourth is the row's cost.

**3a, the count — 2026-09-15.** A throwaway probe at the four places a group value leaves
its match, one operation of each benchmark row, reverted afterwards. Counts are group values
per operation:

| row | output | body content | capture → scalar | capture → list | capture → map | apply select | transform | condition |
|---|---|---|---|---|---|---|---|---|
| regex_lines | 24,576 | 16,384 | — | — | — | 8,192 | — | — |
| ausearch | — | 31,466 | 1,120 | — | 14,140 | 1,995 | — | — |
| apache_httpd | — | 4,224 | 19,104 | — | — | — | — | — |
| csv_header | 24,312 | 35,460 | — | 4 | — | 6,078 | — | — |
| log_sessions | — | 4,396 | — | 30,772 | — | — | — | — |
| win_sec | — | 12,057 | 18,959 | — | — | — | — | — |
| win_sec_strict | — | 17,416 | 8,658 | — | — | — | — | — |
| progressive | 314,586 | 314,586 | — | — | — | — | — | — |
| progressive_text | 242,560 | 48,512 | — | — | — | — | — | — |
| element_storm | 73,200 | 7,320 | — | — | — | — | — | — |
| win_sec_xml | — | 8,691 | 4,322 | — | — | — | — | — |

*What it says.* A group value goes to exactly four places on the corpus, and two of them never
need a whole array: **written to output** (a range write) and **handed to a body as its
content** (`Level.content`, group 0 or the delimiter's field, which today is materialised to a
`byte[]` for every match and becomes a range into `Level.dispatch`). The other two are stores
— **a capture into a scalar, a list or a map** — and the *apply select*, which is a nested
dispatch's content resolved from a group and is a range for the same reason as body content.
**No row hands a raw group to a transform or a condition.** Transforms and conditions read the
*stored* scalar afterwards, so a stored slice materialises when a transform asks for its text
(the `…Text` escapes on `apache_httpd` and `win_sec`, the date parses on `log_sessions`) and
not when it is written out. That bounds materialisation at the number of transform reads of
captured values, which is a subset of the capture column, and it is zero on the four rows that
only write.

*What it says about 3d.* `log_sessions` captures 30,772 group values per operation into lists
declared on the **source** template — run-lifetime slots — and those groups come from root
matches. Under 3c they are still copies, because the root copies. Under 3d, where the root
copies its span once and the groups are slices of it, every one of those stored slices would
pin its record's span for the run: the retention grows from the group's bytes to the record's.
So the run-lifetime compaction is *not* dropped; it is 3d's prerequisite, built with it and
not before.

**3b, built — 2026-09-15.** `ByteSlice` over `(array, from, to, encoding)`, the third arm of
`Bytes`; `Bytes` gains the UTF-8 range accessors `utf8Array`/`utf8Offset`/`utf8Length` (a whole
value's are its array from zero) and `slice(from, to)` — the owner's seam: the thing a match
runs over answers `slice`, an immutable byte value with a range of itself, and (in 3c) the
window with a copy. Equality across the three variants is one rule, `sameText`, over the two
ranges; `Utf8Bytes.equals` keeps its one-class-compare fast path for a key against a key; the
hash of a slice is `Arrays.hashCode` over its range. `Comparisons.compare` reads the ranges.
Nothing makes a slice yet; seven pins in `ByteSliceTest`, the text-equality-and-hash one
first, including a slice looking up a map key that was put as a copy.

*What the control caught.* The first cut put the range write into `Output.write` as a type
test — `if (utf8 && value instanceof Bytes) sink.write(range) else sink.write(bytes)` — and
`PrintInlining` read it at 67 bytes against 20 before, no longer inlined at nine of its eleven
sites. The fix is the value class's own shape (E43): `TypedValue.writeTo(sink, encoding)`,
which `Bytes` overrides to write its range to a UTF-8 sink, and `Output.write` is one call at
15 bytes, inlined at all eleven sites as before; `Bytes.writeTo` inlines at the two hot ones.
Every other hot method on `regex_lines` and `progressive` reads the same size and the same
verdicts before and after. The point (evening) must read flat.

*What the audit found, 2026-09-15.* `PrintInlining` on two rows cannot see a method those rows
do not call, so the audit read bytecode sizes with `javap` for every method the diff touched:
`Utf8Bytes.equals` had grown from 32 to 54 bytes with the other two variants written into it,
over the 35-byte limit for a call site that is not hot — its hot site, the map lookup, would
still have inlined, but the control's rule is that the type costs nothing in shape. It is 30
bytes now: one `instanceof` on the final class for a key against a key, and the other two
variants behind a private call. `Comparisons.compare` had grown from 234 to 272 and is 224,
the range compare living beside `sameText` as `compareText`. `EncodedBytes.equals` shrank, 67
to 59. `Output.write` is 15. Also noted and left: `value` now imports `OutputSink` from the
engine's root package, which already depends on `value` — a cycle at the package level, taken
because a value writing itself to a sink is the class's own shape (E43) and the alternative
was a type test on the hot write path; `slice(from, to)` checks no bounds, because its caller
is a matcher reporting positions in the array it was given, and the code standard keeps
defensive checks off hot paths.

**3c, built — 2026-09-15.** The seam is `ByteSource`, in `value`: *what a match runs over,
answering for a range of itself as a value*. Two implementations and no more, because the
call that makes a group is on the hottest path and two receiver classes is what the JIT still
inlines: `Copying` over bytes that move — the input window (which holds one for the run), a
chunk of a whole-buffer run, and the `any`-mode working buffer, which is compacted in place
under its matches and so must copy whatever the caller's source was — and `Slicing` over
bytes that never move, which is a value's UTF-8 form. `Bytes.source()` answers a `Slicing`
over `utf8Array()`, since the nested level runs over the UTF-8 form as it always has; the
3b `slice(from, to)` on `Bytes` is gone, because a source's positions are absolute in the
array the matcher reported them in and a second convention beside it was a trap. `Level`
threads the source from `dispatch` and `stream` through `match` to the three arms that make
groups — the regex (`matcher.start(i)`/`end(i)` instead of `groupBytes`), the delimiter (five
copies in `Splitter` become `source.slice`; a field with escapes stripped is a new array and
stays whole) and `All`. The progressive match still copies (phase 9). A body's content is the
value, not its bytes — six `Body` signatures and the eater's — so `apply` hands a nested
dispatch the content's UTF-8 range and its source with no array made; a non-byte content (a
number, a composite) is made whole once. Every golden passes byte-for-byte.

*What the bytes taught.* B/op, one fork: `regex_lines` 3.92 → 3.43 MB (−12.5%), `ausearch`
8.97 → 7.88 (−12.2%), `win_sec` 3.87 → 3.38 (−12.7%), `log_sessions` and `progressive` 0.0%
as they must (root matches only). Not the 61% the nested share promised, and the reason is
worth keeping: **the groups on these rows are short**. A copied group was a 16-byte wrapper
plus a small array — about 48 bytes for a ten-byte field — and a slice is one 32-byte object.
So the nested share was *count* times a small size, and slicing saves a third of those bytes
and half the objects, not the array. JFR on 3c shows what is left: the `ByteSlice` objects
themselves (57% of samples), and the root splitter's copies — which copy every line **twice**,
as group 0 with its delimiter and group 1 without. That is 3d's question, and it is bigger
than this phase was. Two follow-ups fall out for 3d to weigh: a slice at 24 bytes rather than
32 (drop the memo, and for UTF-8 the encoding — a fourth variant, or a null encoding meaning
UTF-8), and the `Slicing` record per nested dispatch (16 bytes; 4% of `regex_lines`' bytes),
which the `Bytes` classes could answer directly at the cost of a third receiver type at the
group-making site.

*What single-fork throughput does not tell.* The same runs read `progressive` +18.5% with
identical bytes: the phase 2 profile was taken on a slow box. Ops/s across time is not a
reading; the interleave is — and the daytime interleave against point 47 (load 1.2 to 1.7,
three rounds) read `regex_lines` −12.7%, −2.2%, +7.3% and `ausearch` −3.0%, +5.1%, +12.7%,
with point 47's own `regex_lines` legs ranging 807 to 887 ops/s: the box, not the code. The
canaries `apache_httpd` and `progressive` were flat within a point on every round but one, so
the seam costs nothing where nothing slices. The throughput reading is point 48, evening.

**3d, built — 2026-09-15.** One rule for every arm: **a match makes one value, its span, and
every group is a range of that value.** Over the window the span is the one copy the root
makes; over a value it is a slice; the groups are slices either way, through `Bytes.range(from,
to)` — positions relative to the value, in its bytes as read, its encoding — which is the 3b
`slice(from, to)` back under a name that says what it is beside the source's absolute one.
The regex arm takes the span's bounds over every group that took part, not from group 0,
because a group inside a look-around lies outside the match (pinned by `SpanGroupsTest` at the
root and one level down; no fixture does it). The delimiter arm's fast path makes one value
for the field and its delimiter and the field is a range of it — one copy over the window
where there were two — and its container path likewise; a field with escapes stripped is
still a new array. `All` is one group and unchanged. The root regex on `log_sessions` made
eight copies of a sixty-byte line per match and makes one.

*What the bytes read*, one fork, against 3c: `regex_lines` −8.6%, `apache_httpd` −4.0%,
`ausearch` −3.4%, `csv_header` −2.5%, `log_sessions` −1.6%, `win_sec` −0.1%, `progressive`
0.0%. Cumulative from point 46: `regex_lines` 3.92 → 3.14 MB (−20%), `ausearch` 8.97 → 7.61
(−15%), `win_sec` 3.87 → 3.38 (−13%). `log_sessions`' small number is its size: 21 MB per
operation, most of it date parsing and `String`s, so eight copies to one per line is a
sixtieth of it.

*The run-lifetime compaction was not built, and the ruling should be re-read against the
numbers.* Under 3d a stored root group pins its line rather than its own bytes. On
`log_sessions`, the row 3a named, seven groups of each line are stored for the run: seven
32-byte slices sharing one 80-byte line is 304 bytes where seven 48-byte copies were 336 —
*less* retention, not more, and a compacting store would have to make the seven copies 3d
just removed. Retention grows only where *few* groups of a *long* record are stored for the
run — one ten-byte group of a kilobyte line pins the kilobyte — and no fixture does that.
The bound is one record per stored value, which the live-element counter already bounds in
count. So: no compaction, the trade written down here, and a heuristic (compact when a
slice is a small fraction of its array) is the answer if a real feed ever shows the case.
**This departs from the 2026-09-15 ruling that compaction is 3d's prerequisite, on the
evidence above, and is flagged for the owner.**

*Left on the table, priced.* A slice is 32 bytes and is now the largest allocation on the
sliced rows; a UTF-8-only variant without the encoding and the memo would be 24. And the
`Slicing` record per nested dispatch (16 bytes).

*What the audit found, 2026-09-15.* `javap`: `regexMatch` had grown from 166 to 319 bytes —
six under the hot-inline limit, on a method that inlines hot into the match loop on every
regex row — because the span's bounds were a loop over the groups with a checked call per
bound. The bounds belong to the matcher, which holds every group's slots in one array:
`ByteMatcher.spanStart()`/`spanEnd()` answer them in one pass with no per-call check, and
`regexMatch` is 261. Pinned in the regex module for a look-ahead, a look-behind and a group
that did not take part. The splitter's container path was checked for bounds — the content
end never passes the match end — and its fast path is pinned to share one array between the
field and its delimiter. The encoded case was traced: a range of a span is over the bytes as
read with the span's encoding, so a Latin-1 root's groups decode exactly as their copies did.
Every method the diff touched: `match` 232 → 236, `splitOnByte` 127 → 134, `build` 196 → 197.

**Progressive is the same rule.** `Steps.take` copies (4.7% of `progressive`) and group 0 is a
copy of the span (5.3%); over a root window they stay copies, over a nested value they are
slices. Phase 9 takes that as given.

**Measure.** The use-classification count first, then `-prof gc` on `regex_lines` and
`ausearch` — the claim is that most of the 61% and 63% goes — then the interleave on every
regex row with `progressive` as the canary, and `apache_httpd` and `csv_header` because they
are reference-heavy and every reference now reads through a range.

## 6. Phases 4 and 5 — what backs a map, and whether order is worth its node

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

**Two steps, ruled 2026-09-15.** *Phase 4* puts a number on the order: a probe branch swaps
`LinkedHashMap` for `HashMap` (and the set likewise), the fixtures that walk a map without a
sort fail — which is the point, and they are listed — and `ausearch`, `win_sec`,
`log_sessions` and `apache_httpd` are interleaved against the head. If the order costs
nothing measurable, the case for phase 5 rests on the node alone, and the reading says how much
that is worth. *Phase 5* is the engine's own ordered map and set as a phase of its own, with the
code reviewed and the structure tested as a whole before it goes near the registry: a
micro-benchmark of `put`/`get`/`clear` against `LinkedHashMap` at the corpus's sizes (the
`ausearch` map holds a few dozen keys; `win_sec` a handful), the collection tests run against
both backings, then `-prof gc` and the interleave on the four rows. The fixtures' outputs pin
insertion order and pass unchanged, or the map is wrong.

## 7. Phase 6 — a kind on every value and instruction

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
   here, measure, and extend to values only if the accessor switches show up in phase 7's
   census.

Recommendation: 3, then 2 if the census says so. And the kind is where phase 7's category
split hangs: `kind >> 4` is the category, `kind & 15` the member.

**Measure.** `javap -c` on `CompiledRefs` before and after shows `typeSwitch` gone and
`tableswitch` present — that is the check that the mechanism is real. Then `PrintInlining`
shows `write` and `resolveValue` under 325 bytes, and the `progressive`/`regex_lines`
interleave shows the sign — this is the phase that is *for* those two rows.

## 8. Phase 7 — an inline census, and switches split by category

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
That is what `0e852742e4` tried by hand and got half right; with the kind from phase 6 the
fast path is a `tableswitch` and the bytes it costs are known before the benchmark runs.

**Measure.** The census table before and after, then the interleave on every row. The gate
is the table: a method that was *too big* is under budget, and no method that inlined has
stopped.

## 9. Phase 8 — the shape of a configuration that uses a map

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
the engine as it stands after phase 7. The result is a table of *shape → cost* per row and a
paragraph in design 35 §5 saying which shape a map is for.

## 10. Phase 9 — the progressive match, reconsidered from what it is for

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

## 11. Rulings sought

| question | position taken here | ruled |
|---|---|---|
| Order of phases | census, reuse, slices, order's cost, own map, kind, inline split, fixture shapes, progressive | **ruled 2026-09-15: reuse first**, then the map, before the kind; **re-ruled 2026-09-15: the sections are in implementation order, slices directly after reuse** |
| Phase 2 pools nested collection values (a list under a map key) | no — inner copies stay the collector's | — |
| Phase 5 keeps insertion order | yes — the fixtures pin it; the saving is the node, not the order | **ruled 2026-09-15: measure `HashMap` first on a probe branch, so the order's cost is a number; the engine's own ordered map is then a phase of its own (phase 5) so the code can be inspected and tested as a whole** |
| Phase 6 carries the kind on instructions first, values second | yes | **ruled 2026-09-15: yes** |
| Phase 8 replaces a fixture's configuration or adds a challenger | challenger, beside it | **ruled 2026-09-15: challengers stay beside the current configuration**, measured every run, as the XML bench does |
| Phase 9 may make group 0 lazy (a view materialised on read) | yes — nothing observable changes; the copy moves to the reader | **superseded 2026-09-15: phase 9 first asks what a progressive match is for and whether the approach is right, before any per-match cost is chased** (§10) |
| Phase 3: the root copies, everything below slices | proposed 2026-09-15 from phase 1's reading, shaped by the owner (§6); the largest allocation on every regex row, and the owner expects a large gain | **shape ruled 2026-09-15; placed directly after phase 2** — ahead of the map phases, because it is the larger lever and touches neither the registry nor the map |
| The `lookup` and `set` pieces of the streamlining are kept now | yes — restore the close's `write`/`resolveValue` onto head, read the four rows | **ruled 2026-09-15: yes** |
| Cadence | daytime targeted one-minute interleaves per phase on its rows plus the two canaries when the box is quiet; full-suite points collected into one evening run per two or three phases | **ruled 2026-09-15** |
| Target for the scan rows | the floor `597274d25e` is the reference, not a gate: design 35 corrected behaviour rather than chasing speed, so its cost cannot be insisted away; every improvement counts | **ruled 2026-09-15** |

## 12. What would make this a mistake

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

## 13. The plan, in phases

Each phase: build, audit against this section, commit when asked, benchmark point when asked.

### Phase 1 — census

§3 is the census; this phase is its review and the B/op reading of the rows it names
(`-prof gc` on `progressive`, `ausearch`, `win_sec`, `log_sessions`, `regex_lines`) so that
the *candidate* verdicts become *do* or *leave* on evidence. No code.

**Done 2026-09-15**; the reading is in §3 under *What phase 1 read*. Two results change the
plan: the scan rows' regression is not allocation, and the largest allocation everywhere is
the group copy the matcher says not to make (phase 3).

### Phase 2 — clear, don't allocate

§4. Gate: B/op on `ausearch` and `win_sec` falls; `progressive` unchanged, since it declares
nothing; outputs unchanged.

### Phase 3 — the root copies, everything below slices

§5, in four sub-phases, each its own commit and — for the three that change code — its own
point with its expectation written first, because a new arm in a sealed hierarchy changes the
shape of every switch over it and shape has bitten twice.

- **3a, the count.** Done 2026-09-15; the table is in §5. Every group value goes to output,
  body content, a capture store, or an apply select; none to a transform or a condition.
- **3b, `ByteSlice` exists and nothing makes one.** The class; the text-equality and hash pin
  across all three `Bytes` kinds, written first; the output writer and `Comparisons` taking a
  range; `asUtf8` materialising for everyone else. Behaviour unchanged, every golden
  byte-for-byte. Gate: `PrintInlining` on `regex_lines` and `progressive` before and after
  shows the hot methods the same size, and the point reads flat. The control that separates
  the type's cost from the slicing's gain.
- **3c, nested matches produce slices.** `Level.regexMatch` slices when entered from a body's
  dispatch and copies when entered from the window; `Level.content` and the apply select pass
  a range into `Level.dispatch`. The payoff. Gate: B/op on `regex_lines` and `ausearch` falls by
  most of the nested share (61%, 63%); interleave on every regex row with `progressive`,
  `apache_httpd` and `csv_header` as canaries.
- **3d, a match makes one value and its groups are ranges of it.** Built 2026-09-15 (§5).
  The run-lifetime compaction was not built: on the row 3a named, seven stored groups sharing
  one line retain less than seven copies did, and compaction would remake the copies; the
  trade and the case that would change it are in §5, and the departure from the ruling is
  flagged. Its own point.

### Phase 4 — what insertion order costs

§6. A probe branch, not a commit on the line: `HashMap` and `HashSet` behind the map and set,
interleaved on the four map rows. Output: the number, the list of fixtures that depend on
order, and a go/no-go for phase 5 written into §6.

### Phase 5 — the engine's own ordered map and set

§6. Its own phase: the structure, its tests against both backings, the micro-benchmark, review
of the code as a whole; only then the swap. Gate: `ausearch` B/op and ops/s; every map fixture
passes byte-for-byte.

### Phase 6 — the kind

§7, instructions first; values only if phase 7's census names a value switch over budget. Gate: `javap` shows `tableswitch`; `PrintInlining` shows `write`
and `resolveValue` under budget; `progressive` and `regex_lines` recover toward the floor.

### Phase 7 — inline census and the category split

§8. Gate: the census table, before and after.

### Phase 8 — fixture shapes

§9. Gate: the shape → cost table, and a sentence in design 35 §5.

### Phase 9 — the progressive match, reconsidered

§10. A thinking phase first: what a progressive match is for, what it produces, whether the
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
as ruled, with that 3% written down as phase 6's to recover along with the rest; whether it is
`lookup` or `set` is one more one-minute interleave and is asked for before phase 6 starts.

### Where the bisect stood when this was written

The 2026-09-15 bisect of the streamlining commit `0e852742e4` by piece, interleaved with the
close `9c77725d4e` on `regex_lines`, three rounds each: with the `write`/`resolveValue`
rewrite removed and the other two pieces kept (`808c9e9ffd`) the row reads +3.6%, −0.1%,
−14.1% — noise around zero; with the `lookup` rewrite removed instead (`44f8374844`) it reads
−23.2%, −22.8%, −12.2%; with the `set` rewrite removed (`90204f3ace`) it reads −28.2%,
−20.4%, −25.9%. The regression is the `write`/`resolveValue` rewrite alone, and the `lookup` and `set`
pieces are keepable. Phase 6 is where `write` is redone, on a kind, with the byte count
read before the benchmark.
