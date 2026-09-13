# Design 35 — One binding, declared in a scope, with a type

*Opened 2026-09-11 after E49 was built, measured and reverted. Reshaped 2026-09-12 on the owner's
model: lexical declaration, two scopes, var types, counters left alone.*

**Status: open. Nothing is built. §9 lists what has to be ruled first.**

## 0. What is fixed, and what is not

**Shapeshifter has never been released, so there is no backwards compatibility to keep.** The
model, the configuration format and run-time behaviour are all free to change. Two things are not:

1. **Every fixture's input and expected output.** A fixture's `project.json` may be rewritten
   however this design needs; its `.in` and `.out.xml` may not move a byte.
2. **DS3 migration.** A migrated configuration must keep producing what DS3 produces, and DS3's
   source is the oracle for what that is.

*This is what E49's revert enforced and it is the right gate.* E49 was not reverted because it
changed the model — it may — but because four golden **outputs** changed. That test is unaffected
by how much of the configuration format this design rewrites.

So the question to ask of anything below is never "how much would have to change", only whether
the golden outputs still match and DS3 migration still reproduces DS3.

## 1. The model already half-agrees, and leaves out the members that needed it

Everything that binds a name writes through `vars.store(name)` or `vars.put(name, store)` into one
`VarRegistry`, one slot space, one `Store`. The compiler's own refusal says so: *"no capture,
variable, transform bind or parameter has that name."* Four binders, one namespace, and a reference
cannot tell which wrote what it reads.

**And the configuration model already says it too, for most of them.** `OutputNode` declares

```java
sealed interface Binding extends OutputNode
        permits Transform, Variable, Sequence, Key, KeyGet, Count, Sum, Avg, Min, Max,
                DistinctValues, ValueMap {
    String name();
```

Eleven instructions, each of which binds a name rather than writing output. Somebody already saw
that these are one kind of thing.

**Two sit outside it, and they are the two that caused everything in this design.**

- **`CaptureBinding`** — its own record, its own file, not a `Binding`. It is the one with a
  lifetime rule (E19's), the one whose key-value form invents names from the data, and the one
  whose index means three different things depending on what a match is.
- **`Param`** — `record Param(String name, RefExpression value)`, also outside.

So the model has a unification concept and omits precisely the members that needed it. And none of
the thirteen carries a **scope** or a **type**, because there was nowhere to put one.

| binder | bound when | value from | in `Binding`? |
|---|---|---|---|
| capture | before the body runs, so a later template can read it | the match | **no** |
| variable, transform, sequence, aggregate… | during the body, in order | running instructions | yes |
| parameter, call argument | at the call | the caller | **no** |

A capture is a variable whose value comes from the match and is bound early. That is a difference
of *binding time*, not of kind — and §0 means the model can finally say so.

## 2. What is missing: declaration, and type

**One namespace, four binders, one lifetime rule.** Only compiled captures have a rule — E19's
clear on the template's first match. Key-value captures are excluded outright. Variables,
parameters and transform binds have none, and E19's residual predicted they would tail-leak in the
same shape, which E28 then was.

**A scope mechanism nothing declarative reaches.** `VarRegistry` already has `push`, `pop`,
`shadow` and an undo log that restores on unwind. Every caller is `Body` — grouping, for-each,
variables, calls. Captures never participate, because nothing says *where a name belongs*.

**Three meanings for one index.** A `Store` is addressed by an int that is always "the declaring
template's match count" — but what a match *is* differs per template, so the number means which
record, which column, or which token depending on who bound it, while parameters and loop
variables write a literal 1 by convention. `latest()` is well defined in all of them and means
something in only some.

## 3. Why every rule so far has broken something else

- **E19** — clear on the template's first match, mirroring DS3's `parentMatchCount == 0`. Half
  fixed, half pinned, 2026-08-20.
- **Nothing** for key-value captures, which is why a record that *did* bind `key` can still read an
  earlier record's token.
- **E49** — clear on entering a level. Built 2026-09-11, reverted the same day: it fixed its first
  demonstration and broke four golden fixtures, because **DS3 carries captures forward and the
  goldens record it**. `DS3Parser.parse` calls `root.clear()` once per stream. The 4625 event at
  `win_sec/input.txt:165` has no `Logon Type:` line and DS3 still emits
  `<LogonType>Interactive</LogonType>`, carried from line 21.

Three rules for one question, each inferred from where a template sits rather than from what its
author meant. E49's design had already rejected "a scope per dispatch" because it destroys the CSV
headings — the mechanism could not say *this one is per-record and that one is per-stream*, so it
had to pick one and lose the other. That is what a missing concept looks like.

## 4. The model

**A variable's scope is where it is declared.** Assignment may happen deeper — in a descendant
template's execution. Re-declaring a name in a deeper scope shadows the outer one. These are the
rules every language already has, and adopting them is the point: nothing here has to be learned.

**Two scopes.**

- **Global** — visible to everything, for the whole run.
- **Template** — visible within that template and any descendant execution, and destroyed when that
  template's execution ends.

*Global is not simply "declared in the root template", and the difference is load-bearing.* Under
an ordered root the two coincide — the root's execution spans the run. Under a `classify` or `any`
root they do not: `Run.dispatchInput` sets `chunkedRoot`, the input is read in pieces and the root
is re-entered per chunk, so a root declaration would have **chunk** lifetime. `Body.guardAccumulation`
already says so about capture stores — "accumulates across records at the root level and is cleared
per chunk". Collapsing the two words would make a variable's lifetime depend on the root's dispatch
mode, which is lifetime inferred from dispatch shape, which is what this design exists to remove.
`global` means the run.

*And it closes a hole that guard names and cannot catch.* Its comment ends: "No refusal catches
it, because nothing at the read site distinguishes a capture store from a per-record binding — it
is named here rather than left for someone to find." A declared lifetime is exactly that
distinction.

**It is var scope, not template scope.** There is no frame per template execution. A template
holds the list of slots declared in it, and on exit it clears them. That is the whole run-time
cost of lifetime: a walk of a small per-template array, and nothing at all for a template that
declares nothing. A push and pop per execution would be 569,199 of them on `win_sec_strict`; this
is zero.

**Shadowing is mostly a compile-time resolution, not a stack.** Two declarations of the same name
at two declaration sites are two slots; every reference is resolved to one of them when the
configuration is compiled, exactly as design 30 resolved names to slots. Nothing shadows at run
time and nothing is pushed.

*The corpus says how common that even is:* across every fixture, **one** name is declared by two
templates — `__kv`, by `kv_pair` and `extra_kv_pair` in `ausearch` — and it is a key-value capture,
which §5 removes. Lexical shadowing is close to nonexistent.

**The one case that needs real storage is the same declaration site live twice**, which compile
time cannot resolve away because whether it happens is data-driven: which child template matches
depends on the input, and a mode graph with a cycle — the `__rec_` recursive form is the explicit
one — can re-enter a template while an outer activation is still live. One slot cannot hold both.

*So a slot holds its value directly until a second declaration of it arrives, and becomes a stack
only then.* The common case pays nothing and the rare case is correct. Whether the promotion is
decided at run time on the second declaration, or at compile time by looking for cycles in the
dispatch graph, is §9's business; the run-time test is simpler and cannot be wrong about a graph
it did not have to analyse.

*Banning shadowing outright was considered and does not help.* Refusing a second declaration of
a name at compile time removes the lexical case — which is already free, being a compile-time slot
assignment, and which the corpus does once. It does nothing about the case that actually needs
storage: **one** declaration site live at two depths, which is not a re-declaration at all. A
recursive template declares `x` once and can still be inside itself. So the ban would cost a
legal construct and leave the stack exactly where it was.

### Why declaration must be separable from capture

This is forced by the corpus, not chosen. In `win_sec`, **`event_record` reads 71 names captured
by other templates** — `$LogonType` is captured by the `LogonType` template and read by
`event_record` after the apply returns. The dominant pattern is *a descendant assigns, an ancestor
reads*.

If a capture also declared, every one of those 71 would live in the capturing template's scope and
be invisible to the reader. So the record template declares, its descendants assign, and it reads
what they wrote — which is ordinary lexical scoping and needs no new idea.

*And it is how DS3 fidelity is kept.* DS3 clears once per `parse()`, so migrated declarations are
**global**, and E19's pinned behaviour becomes a declaration rather than an inference. A native
configuration that wants per-record lifetime declares on the record template. The two stop
competing.

### One declaration, several ways to give it a value

A **declaration** carries a name, a scope (§4) and a type (§5). Everything else is a way of
supplying its value:

- from the match — what a capture is today;
- from a body run in order — `Variable`;
- from an instruction's result — `Transform`, `Count`, `Sum`, `Key`, and the rest of `Binding`;
- from the caller — `Param`.

`CaptureBinding` and `Param` fold into `Binding`. Lifetime and type then live on the declaration,
where **every** binder gets them at once, instead of on captures alone — which is the accident this
whole design has been unwinding. E19's residual predicted variables and transform results would
tail-leak in the same shape as captures, and E28 was that prediction coming true; a declaration
they all share is why that cannot recur.

*The test this must pass is that the model gets smaller.* Thirteen binding constructs with one
shared interface and two exceptions becomes one declaration with several value sources. If it does
not come out smaller, it is not this design.

**Two things not to gloss.** `Binding` is a *compile-time* construct and says nothing about the
run-time `Store`; §5's types are a different run-time shape, not merely a different declaration,
and that is where the work actually is. And folding in `Param` may be the least valuable part —
parameters are bound at a call and scoped to it, which the existing push and pop already get
right — so it wants a reason beyond uniformity.

## 5. Var types

A variable declares what it holds. **Scalar, list, map, set.**

This is the second half of the problem and it is not a lifetime question. The engine already has
something list-shaped — the match-indexed `Store` — but it is muddled, because its index is a
match count reinterpreted per binder rather than a position. An explicit list has positions; an
explicit map has keys; a scalar has one value. `latest()` becomes "the last element", which is
well defined, instead of "the highest populated index", which is well defined and meaningless.

**The CSV heading case is a list.** Today:

```json
{"capture": {"var_id": "heading", "match_index": {"var_ref": "__match_count"}}}
```

`heading` holds one value per column and `data_column` reads the Nth heading by its own match
count. As a list with an index captured from the counter, that is `heading[i]` — the same
mechanism, legible.

**The data-derived name map goes.** `Names` is `record Names(Map<String, VarName> all,
Map<String, KeyName> keys)`, and `keys` is the last data-keyed structure at run time — design 33
§11 B's "the one the owner expects to be the last, and it is". A key-value capture invents a
variable name from the data; with a **map** var it writes a key into a declared variable instead,
and the dynamic slot machinery is unnecessary. **Only two fixtures use key-value captures at
all** — `ausearch` and `text_003_multiline_regex` — and `ausearch` names the keys it wants
(`$auid`, `$success`, `$key`, `$res`), so explicit captures or a map var cover it.

That also deletes E49's third demonstration outright: `key=old` beating `key=new` *is* the dynamic
name indexed by token position, and neither survives.

### What a collection holds, and why generics never arise

A `Store` holds `TypedValue[]`, and `TypedValue` is a sealed interface — `Integer`, `Double`,
`Bool`, `Instant`, and the byte-backed forms. So **there is exactly one element type in the
engine**, and the collection types join that interface rather than sitting outside it.

`list` therefore means list-of-`TypedValue` and `map` means key-to-`TypedValue`, where a
`TypedValue` may itself be a list or a map. There is nothing to parameterise: generics, nested type
declarations and element-type inference are not questions this design has to answer, and they stay
that way *because* collections are values.

### The list

**Backed by a `TypedValue[]` and a count** — the same shape `Store` already has, and the shape
design 33 moved the whole run state to. A list is not a new run-time structure; it is the existing
one with the magic taken out.

The magic being removed is this: today a capture writes at *its template's match count*, so a
template matching repeatedly accumulates a list **as a side effect of matching**, and an unindexed
read takes the highest populated index. Nothing says "append". That is where §2's three meanings
for one index come from, and it is why `latest()` is well defined and meaningless.

Operations, all explicit:

| operation | meaning |
|---|---|
| `append(list, value)` | add at the end — what a per-column capture does today by accident |
| `insert(list, index, value)` | add at a position |
| `remove(list, index)` | drop a position |
| `get(list, index)` | read a position |
| `size(list)` | how many |
| `clear(list)` | empty it |

**Plain assignment to a list is a compile error.** There is no sensible reading of it: replacing a
whole list is `clear` then `append`, and letting `=` mean either "replace" or "append" is exactly
the kind of inference this design exists to delete.

*The CSV heading case, stated in this vocabulary.* `header_column` appends each heading;
`data_column` reads `get(heading, i)` with `i` captured from its own match counter (§6). Compare
today's `{"capture": {"var_id": "heading", "match_index": {"var_ref": "__match_count"}}}` — the
same mechanism, with the accumulation and the addressing both said out loud.

*And the DS3 migration has enough to emit it.* A DS3 var read with an index is a list appended per
match; one read without is a scalar. The migration already does exactly this kind of pre-pass —
E48 added one to collect which groups of which vars are read — so the shape is known before a
template is emitted.

### The map

**Key to `TypedValue`.** This is what removes the data-derived name map: a key-value capture
invents a *variable name* from the data and needs `Names.keys` to allocate a slot for it; a map var
writes a **key into a declared variable** and needs nothing dynamic. Design 33 §11 B's last
data-keyed run-time structure goes with it.

| operation | meaning |
|---|---|
| `put(map, key, value)` | bind a key |
| `get(map, key)` | read a key, absent if unbound |
| `has(map, key)` | whether a key is bound |
| `remove(map, key)` | unbind |
| `size(map)` | how many |
| `clear(map)` | empty it |

As with the list, plain assignment is a compile error.

*This also deletes E49's third demonstration outright.* `key=old` beating `key=new` is a dynamic
name indexed by token position; with `put(kv, key, value)` there is no dynamic name and no
positional index, so the defect has nowhere to live.

### The set

**Supported** *(ruled 2026-09-13)*. Membership without duplicates, and **insertion-ordered**,
because a set that reaches output must produce the same bytes every run — determinism is not
optional in a parser whose goldens are byte-compared.

| operation | meaning |
|---|---|
| `add(set, value)` | add if absent; no-op if present |
| `has(set, value)` | membership |
| `remove(set, value)` | drop |
| `size(set)` | how many |
| `clear(set)` | empty it |

**It subsumes an instruction, which is the point.** `DistinctValues` exists today as one of
`Binding`'s eleven — it walks a sequence, keeps `LinkedHashSet<String>` of what it has seen, and
binds the distinct values in order. That is a set, built by an instruction because there was no set
type to build it into. With one, `DistinctValues` is `add` in a loop and the instruction goes. §11's
test is that the model gets smaller; this is one of the places it does.

#### Which equality?

**The engine already has two notions of "same value" and a set forces the choice.**

- `DistinctValues` keys on `value.asString()`. Under that rule the integer `1` and the text `"1"`
  are the same member.
- `TypedValue`'s variants define real `equals`/`hashCode` — `Bytes` compares UTF-8 bytes, `Encoded`
  compares bytes **and** encoding. Under that rule they are different members, and so are the same
  text in two encodings.

Neither is obviously right. The string rule matches what an author writing a parser probably means
by "distinct"; the structural rule is what the type system already says. What is *not* acceptable
is keeping both, so that `has(set, x)` and a condition's `=` disagree about the same pair of
values. **This is the ruling that matters more than whether set exists**, because it is really a
ruling about equality everywhere — conditions, `ValueMap` lookup, map keys — and the set is just
where it becomes impossible to avoid.

#### Sets and nesting

A set of collections needs equality on collections, and the two candidates fail differently: the
string rule cannot work because `asString()` on a collection is refused (§5), and the structural
rule works but is recursive, so `add` costs the size of the element rather than a hash.

The likely answer is to **refuse collections as set members and as map keys**, which keeps
membership O(1) and costs nothing anyone has asked for. It should be a refusal at compile time,
which the declared types make possible.

### Collections are values, and therefore nest

**A collection is a `TypedValue`.** `list` and `map` join `Integer`, `Double`, `Bool`, `Instant`
and the byte-backed forms as variants of the same sealed interface. A list may hold a list, a map
may hold a list, and nothing special has to be said for that to work.

*This was first written the other way — collections flat, nesting refused — on the grounds that
allowing it would drag in generics. That was wrong and the record is worth keeping.* A list holds
`TypedValue`; making a collection **be** a `TypedValue` keeps that true, so there is still exactly
one element type and still nothing to parameterise. Nesting is free *because* collections are
values. The restriction would have bought nothing and cost the expressiveness.

**The blast radius is small, which is the other thing that was got wrong.** Consumers do not
pattern-match on `TypedValue`'s variants — there are **no** `case final TypedValue.X` arms in the
engine, across two files that mention the type at all. Everything goes through the interface:
`isEmpty()`, `asBytes()`, `asString()`. So the cost of two new variants is those three methods,
not a sweep of call sites.

**And declared types (§5) are what make it safe.** The compiler knows a variable is a list, so a
list reaching `value-of`, a cast, or a comparison is refused where it is written rather than
discovered at run time. The two rulings hold each other up: values-that-nest would be far less
attractive under inference, where the same mistakes would surface as run-time surprises.

#### What the collection variants still have to answer

These are contained, but each needs a decision and none has an obvious default:

| question | why it is not obvious |
|---|---|
| `asString()` / `asBytes()` on a list or map | there is no natural serialisation, and inventing one (join with commas?) is the kind of magic §5 removes. **A refusal is the likely answer**, made at compile time by the declared type |
| `isEmpty()` | genuinely easy — no entries |
| `Cast` applied to a collection | refuse; a cast converts a scalar's variant |
| equality and ordering in a condition | deep or by identity? Ordering of collections probably has no meaning and should be refused |
| `guardSequenceSize` and `maxSequenceEntries` | the existing size guard counts entries in a flat sequence. With nesting, "how big is this" is recursive, and an unbounded nested structure is a new way to exhaust memory on a stream of unknown length |

*The last one is the only one with teeth.* The engine's promise is that a stream of unbounded
length runs in bounded space, and design 30's unwinding is what keeps it. A nested collection that
grows per record has to be caught by the same guard, and that guard now has to walk.

#### What nesting does not have to carry

A grouped walk stays an instruction. `ForEachGroup` builds `Body`'s private
`Filed(TypedValue key, List<Integer> members)` for the duration of the walk and it is never a
variable — that remains the right shape, because the configuration wants to *walk* groups, not
hold them. Nesting being available is not a reason to rebuild grouping out of map-of-list
primitives.

### Typing: declared, not inferred

**Ruled 2026-09-13: the type is part of the declaration**, beside the name and the scope, and
every operation is checked against it.

The owner's proposal was to type on first assignment and refuse incompatible instructions at
compile time. That works, and the checking is the same either way — but the reason for preferring
it was that declaring a type opens up generics and nested types, and it does not: there is one
element type, so a declaration is one word.

Four reasons to declare it:

1. **One place to look.** A var's type is a compile-time fact, and this codebase states those once
   rather than deriving them at each site.
2. **Better refusals.** "`append` to `x`, which is declared a scalar at line 40" points at a
   declaration. Inference can only say "`append` here disagrees with `put` there", and has to pick
   which of two distant sites to blame.
3. **A declared-but-unused var still has a type.** Under inference it has none, which matters
   because the run-time shape has to be chosen anyway.
4. **The run-time shape is decided at compile time regardless.** §5 says a list and a scalar are
   different structures; the compiler must settle which before it allocates a slot. Inference does
   not avoid that work, it only moves where the answer is written down.

*Inference remains, as a check rather than as the source of truth:* the operations on a var are
walked and any that disagree with its declared type is refused.

*The alternative is recorded because it was reasonable:* typing on first assignment, with the same
compile-time checking. It was declined on the four reasons above, not on the one that motivated it
— the fear of generics — which does not apply here and does not apply under declaration either.

## 6. Counters are functions, not variables

The execution's counters — how many times this template has matched, where a walk is, how big a
group is — stay **inherent properties read from `Frames`**, exactly as design 30 phase 4 made them.
What changes is how a configuration names them.

**Today they are variable names.** `EngineVars` carries eight: `__match_count`, `__match_idx`,
`__index`, `__position`, `__last`, `__group_key`, `__group`, `__group_size`. A reference to one is a
`RefPart.Capture` like any other, and `RefCompiler` recognises it by name:

```java
final EngineVars engine = EngineVars.byName(capture.varId());
yield engine != null && engine.framed()
        ? new CompiledRef.Context(engine, index)
        : new CompiledRef.RemoteVar(names.intern(capture.varId()), index);
```

**They become functions** — `matchCount()`, `index()`, `position()` and so on — resolved to the same
`CompiledRef.Context` at compile time.

*The mechanism does not change at all.* The resolution above is already a compile-time pointer, not
a run-time lookup, so this costs nothing and gains nothing at run time. It is a change to what the
configuration says, and the reason is that under this design the current form has become an
exception to the rule.

**Why it is worth doing now, and not before.** Every variable now has a declaration, a scope and a
type (§4, §5). A counter has none of the three. Leaving it in the variable namespace makes it the
one name a reference can resolve that was never declared, has no lifetime and has no type — an
exception to the exact rule the design exists to establish. Worse, keeping it there *requires*
machinery: `ReferenceCheck` refuses any binder that takes an engine name, purely to stop
configurations colliding with things that are not variables.

**What it removes:**

- the `__` prefix convention, which exists only to carve out a namespace;
- the reservation refusal in `ReferenceCheck` and the test that pins it;
- the need for a reader to know which `$name`s are storage and which are questions about now.

And it frees the namespace: a configuration may then have a variable called `match_count`, because
nothing is reserved.

**They must be special forms, not registry functions.** The engine already has a function mechanism
— `CallFunction`, `FunctionRegistry`, `FunctionRuntime` — and these must *not* go through it.
They are recognised by the compiler and resolved to `CompiledRef.Context`, as the code above already
does; routing them through a call would put a frame read behind a dispatch on the hottest path in
the engine, which is precisely what design 30 phase 4 took them off.

**Capturing one into a variable is how a value outlives the moment.** `append(headings, ...)` with
`index()` read at the point of use is the CSV case (§5); a counter that must survive its frame is
assigned to a declared variable like anything else.

#### What has to be decided

*Naming.* `count()` alone is ambiguous — the eight are not one concept. Match count, walk index,
walk position, last index, group key, group size and the group itself are distinct questions, and
the names should say which. Whether they are eight flat names or grouped (`group.size()`,
`match.count()`) is §9's business.

*The index position.* A reference's index rule also names these —
`{"match_index": {"var_ref": "__match_count"}}` is the CSV fixture's spelling, and `RefCompiler`
resolves it through the same `EngineVars.byName`. That spelling has to change with the rest, and it
is the one place the function form has to sit inside another construct.

## 7. What this subsumes

- **E19's pinned half** — becomes "declared global", faithful by construction.
- **E49 entirely** — its first two demonstrations are E19's pinned behaviour; its third is the
  dynamic-name map, which goes. **E49 should be closed by this design, not fixed.**
- **E19's residual and E28** — `Variable` and transform results tail-leaking is the same missing
  declaration on binders that never had a rule.
- **The `latest()` ambiguity** — a type says what indexes it.
- **The data-name map** — design 33 §11 B's last data-keyed run-time structure.
- **The two constructs outside `Binding`** — `CaptureBinding` and `Param`, which is why the
  lifetime rule could only ever be written for captures.
- **The engine-name reservation** — `ReferenceCheck`'s refusal and the `__` prefix, once counters
  stop pretending to be variables (§6).

## 8. The run-time representation, and what it costs

Most of this design is compile-time: which slot each declaration owns, which slot each reference
resolves to, which template clears which slots on exit. What is left at run time is small, and the
shape it should take is largely the shape design 33 already built.

### The slot array becomes genuinely fixed

`VarRegistry.slots` is already an array indexed by slot. It is **not** fixed today for exactly one
reason: `grow(String name)` mints a slot at run time when a key-value capture invents a variable
name from the data. §5's map var removes that — a key goes into a declared variable instead — so
**the collections ruling is what makes the array fixed**, sized once from the declarations the
configuration carries, never resized and never copied.

### `Store` disappears, and a slot holds a value

`Store` exists to hold per-match history: `set(matchCount, value)`, a `TypedValue[]` that doubles,
`latest()` reading the highest populated index. That history **is** the magic §5 removes — an
explicit list says what it accumulates.

And because collections are now `TypedValue` variants (§5), a slot can hold the value directly:

| | today | under this design |
|---|---|---|
| slot array | `Store[]` | `TypedValue[]` |
| a scalar var | a `Store` wrapping a `TypedValue[2]` | the value |
| a list var | the same `Store`, indexed by match count | a list-valued `TypedValue` |
| undo log | `Store[] undoSaved` | `TypedValue[]` |

That removes an object and an indirection from **every reference resolution**, which is the
hottest read in the engine. Point 31 measured +16.7 percentage points on `apache_httpd` from making
`Store.values` an array; deleting `Store` should be at least as good, and that is an expectation,
not a measurement — §10 names the row.

### The frame stack

Already right, and worth writing down so it is not redesigned: a flat undo log — `undoSlot`,
`undoOwner`, `undoSaved`, `undoCount` — with `marks[depth]` recording where each scope began, and
`pop()` walking back to the mark. It grows by doubling and never shrinks, and `depth` is the
pointer.

*A flat log beats a per-frame array of restorations*, which is the other way to build this: one
allocation that amortises rather than one per frame to sit idle, and nothing to reuse or reset
because `undoCount` is the only cursor.

### What is left to pay

- **Clear on exit** — a walk of the declaring template's slot array. Nothing for a template that
  declares none, which is most of them.
- **The lazy stack** (§4) — one branch on write to test direct-or-stacked. Promotion happens only
  on a second live declaration of the same site, which no fixture currently does.

### The hole problem, which this representation raises

Today an unmatched capture calls `store.remove(matchCount)`, leaving a **hole** so that positions
stay aligned with match numbers — design 25 §9's rule that an unmatched capture reads as empty
rather than as whatever was there before.

With an explicit `append`, a capture that does not match simply does not append, and every later
position shifts. The CSV case breaks on exactly this: `data_column` reads the Nth heading by its own
match count, and if one heading capture failed, N no longer lines up.

So either an append must be able to append *absent* — which is the configuration saying what a
failed capture means, and is in keeping with the rest of §5 — or alignment must stop being
positional. **This is the one place the explicit model is harder than the magic**, and it is §9's
ninth ruling.

## 9. What has to be ruled before anything is built

1. **What does a configuration that declares nothing get?** Implicit global is lenient; a compile
   error matches how the engine already treats an unwritable name. *Not* a question about how much
   the fixtures have to change — §0 says they may be rewritten freely — but about what a
   hand-written configuration should mean.
2. **Does the DS3 migration declare everything global, or only what DS3's semantics require?**
   Global is faithful and blunt; narrower is more useful and needs proof per case.
3. **Which equality does a set use, and therefore which does the engine use?** `DistinctValues`
   keys on `asString()`; `TypedValue` defines structural `equals`. Keeping both would let
   `has(set, x)` and a condition's `=` disagree. §5 says this is really a ruling about equality
   everywhere, and the set is only where it becomes unavoidable.
   *(Ruled 2026-09-13: all four types are supported — scalar, list, map, set.)*
*(Ruled 2026-09-13: types are declared; collections are values that nest; all four types are
supported; counters become functions — §5, §6.)*
9. **What happens to a position when a capture does not match?** §8 — today a hole keeps positions
   aligned with match numbers; with an explicit `append` there is no hole unless something appends
   absence. The CSV heading case depends on the answer.
8. **What are the counter functions called?** §6 — eight distinct questions, so one `count()` will
   not do, and whether they are flat names or grouped is open.
7. **What does the size guard count once collections nest?** §5's last question, and the only one
   of the five that threatens the bounded-space promise rather than merely needing a refusal.
4. **Is the lazy stack promoted at run time or decided at compile time?** A cycle search over the
   dispatch graph could mark the slots that can ever need stacking and leave every other slot a
   plain field. The run-time test is simpler and cannot be wrong about an analysis it never made.
5. **Which of `VarRegistry`'s existing pushes survive?** §8's last paragraph — this design may
   shrink the scope stack rather than add to it.

## 10. How it would be gated

E49 passed every unit test it was given and was caught only by four golden fixtures; what settled
it was reading DS3's source. So:

- **Every lifetime rule needs a fixture that distinguishes it from the others.** E49's three
  demonstrations are the start of that set.
- **DS3's source is the oracle for anything a migration emits**, not our reading of what is
  reasonable.
- **No golden fixture's output may change**, though its configuration may be rewritten as much as
  the design needs (§0). If an output does move, either the rule is wrong or the divergence is
  deliberate and ruled — E19 is the precedent for recording that.
- **`win_sec_strict` is the performance gate**, for §8's push-per-execution.

## 11. What would make this a mistake

- **If declaration turns out to be a burden authors cannot carry.** DS3 authors never declared
  anything; they got DS3's lifetime, and the migration will keep giving it to them. The risk is to
  *hand-written* configurations: a declaration every one must carry, and most get wrong, is worse
  than a default that occasionally surprises. This is a usability question, not a compatibility
  one.
- **If the explicit operations turn out to be a worse configuration to write than the magic.**
  DS3 accumulated a list by matching repeatedly and said nothing; this makes every append a
  written instruction. That is more honest and it is more to write, and a CSV heading row is the
  most common thing anyone configures. If the explicit form is materially worse to author, the
  magic was buying something and this trades correctness for ergonomics.
- **If the hole problem has no clean answer.** §8's last subsection. If keeping positions aligned
  under an explicit `append` needs a rule as subtle as the magic it replaced, this design has moved
  the complexity rather than removed it, and the CSV heading case is where that would show.
- **If nesting breaks the bounded-space promise.** The engine's guarantee is that a stream of
  unbounded length runs in bounded space, and `guardSequenceSize` is what enforces it today over a
  flat sequence. A nested collection that grows per record is a new way to exhaust memory, and the
  guard has to walk to catch it. §9's seventh ruling; of everything nesting costs, this is the one
  that is not merely a refusal to write.
- **If the model does not come out smaller.** §4 sets that as the test: thirteen binding
  constructs with two exceptions should become one declaration with several value sources. A
  unification that adds a concept and keeps the old ones has failed on its own terms.
- **If two scopes are not enough.** The CSV headings are global and the record fields are
  template-scoped, but a third case — something per-dispatch rather than per-execution — would mean
  the model is under-powered and the special cases come back.
- **If the lazy stack is not lazy enough.** The whole case for a direct slot that becomes a stack
  on a second declaration is that the second declaration is rare — one name in the corpus, in a
  feature §5 removes. If real configurations shadow routinely, every write pays the branch and the
  promotion, and a plain stack would have been the honest choice from the start.
- **If clear-on-exit is not where the cost went.** Design 33 bought +26.7 points on
  `apache_httpd` from run-state access. A lifetime model that spends it back has not earned its
  correctness, and §10 names `win_sec_strict` as the row that would say so.
