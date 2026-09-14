# Design 35 — One binding, declared in a scope, with a type

*Opened 2026-09-11 after E49 was built, measured and reverted. Reshaped 2026-09-12 on the owner's
model: declaration, one scope rule, dynamic resolution, var types, counters as functions.*

**Status: settled, nothing built. §9 has no open rulings; §12 is the plan.**

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

Twelve instructions, each of which binds a name rather than writing output. Somebody already saw
that these are one kind of thing.

**Two sit outside it, and they are the two that caused everything in this design.**

- **`CaptureBinding`** — its own record, its own file, not a `Binding`. It is the one with a
  lifetime rule (E19's), the one whose key-value form invents names from the data, and the one
  whose index means three different things depending on what a match is.
- **`Param`** — `record Param(String name, RefExpression value)`, also outside.

So the model has a unification concept and omits precisely the members that needed it. And none of
the fourteen carries a **scope** or a **type**, because there was nowhere to put one.

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
template's execution. A name is declared once per namespace (below), so the only shadowing is a
recursive execution re-entering its own declaration. Familiar rules, deliberately: nothing here has
to be learned.

**Where a declaration is written is its scope, and that is the whole rule.** It is never spelled as
an attribute, so a run-long variable cannot be written by omission and two templates cannot declare
the same one differently.

- **On the source template** — outside every other template, so nothing encloses it and it lasts
  the run. This is "global" in effect without an author ever saying the word.
- **On any other template** — visible within it and any descendant execution, destroyed when that
  execution ends.

*There is one scope rule, not two scope kinds.* An author never marks anything "global"; they write
it on the source, outside every other template, and the effect is global because nothing encloses
it. That is how file scope works in any language and it needs no second concept.

`Run.document()` is what makes it true: it runs **once per stream** — prologues, then
`dispatchInput`, then tails — and the chunk loop lives *inside* `dispatchInput`. So the source
template's execution spans the whole run however the input is read.

**The sharp edge is the root templates, not the source.** Under a `classify` or `any` root,
`dispatchInput` reads the input in pieces and re-enters the **root-mode** templates per chunk, so a
declaration on one of *those* has **chunk** lifetime, not run lifetime.
`Body.guardAccumulation` already says as much about capture stores — *"accumulates across records
at the root level and is cleared per chunk"*. That is a real trap: `source` and a root template look
adjacent in a configuration and are not, and an accumulation meant to span the stream must be
declared on the source.

*It also closes a hole that guard names and cannot catch.* Its comment ends: *"No refusal catches
it, because nothing at the read site distinguishes a capture store from a per-record binding — it
is named here rather than left for someone to find."* A declared lifetime is exactly that
distinction, and a declaration on a root template under a chunked root is something the compiler
can see and refuse or warn about.

### One declaration per name, per namespace

**A name may be declared once.** Because resolution follows the dispatch chain, two declarations of
one name resolve differently depending on which template matched — invisible in the configuration
text and exactly the kind of thing that hides for months. Refusing the second costs nothing: across
every fixture it happens once, `__kv` in `ausearch`, in a feature §5 deletes.

**Written as *per namespace*, because template libraries are coming.** A once-per-configuration
rule would not survive importing a library that happens to declare `heading`. But the flat
namespace is what breaks there, not the once-rule — and it breaks whether or not re-declaration is
allowed:

*With re-declaration permitted, a library is worse off, not better.* A library template that
declares `x` and assigns to it would, under dynamic resolution, silently write to the **importer's**
`x` whenever one is live on the dispatch chain. A collision that produces wrong output beats a
collision that produces a message, and not in a good way.

*And it is bigger than variables.* `Template.mode` is a plain `String`, so a library's modes would
collide the same way, and nothing namespaces those either. Whatever the library work does, it has
to qualify names and modes together.

**XSLT's answer is the one to take**, consistently with §5's naming: variables, modes and functions
are all QNames there. Qualified, `lib:heading` and `heading` are different names that cannot clobber
one another, declare-once holds within each namespace, and a library's internals stay its own.

*None of that is built now* — there is no import or library concept in the configuration, and one
implicit namespace, so the rule today reads "once per configuration". It is stated per namespace so
that libraries do not have to reopen it.

### Scopes are per block, not per template *(ruled 2026-09-14)*

The rule is *a declaration's scope is the execution it is written in*, and a template execution is
not the only kind. A `for-each` body, a `call-template` body and a `variable` body are executions
too, so the rule covers them and there is no template-granularity exception.

**All six of `Body`'s pushes become the one mechanism** — they already share `VarRegistry`'s
stack — but they stop being special forms:

| push | today shadows | becomes |
|---|---|---|
| `forEach()`, `sorted()` | the loop variable `as` | a declaration scoped to the loop body |
| `forEachGroup()` | `groupMembers` and a group frame | the same, plus the frame §6 keeps |
| `callTemplate()` | arguments and parameters | declarations scoped to the callee's body |
| `variable()` | the name being computed | a shadow over its own body, so the computation cannot read a half-built value |
| `apply()` | `recursiveShadow()` — every capture name of every candidate | **gone**: recursion shadows by re-declaring, which is precise where this was coarse |

*`recursiveShadow()` going is worth its own line.* It flattens the capture names of **all**
candidates in a mode and shadows the lot, because it could not know which would match. Declaration
on entry shadows exactly what the entered template declares, so the coarse version is replaced by a
correct one rather than merely relocated.

*What this obliges the model to say:* where a block declaration is written. A loop's `as` and a
template's `param` list are the declaration sites, so they already exist — the work is treating them
as declarations with a type and a scope rather than as bare names.

**Declaring and shadowing are the same mechanism, and it is already built.** Entering a template
that declares names pushes a scope over exactly those names; leaving it pops. `VarRegistry` already
has this — `push(VarName[] shadowed)`, whose javadoc reads *"Enter a new scope shadowing a set of
names settled at compile time"* — and `bind` already logs a slot's previous value and installs a
fresh one, with `pop` restoring it.

So there is no separate clearing rule and no per-slot machinery. **Restore-on-exit gives
clear-on-exit for free:** a record's declarations restore to what was there before the record, which
was unset, so the next record starts unset. And it gives correct shadowing for recursion for free
too, because an inner execution's declaration logs and restores just the same.

**Only templates that declare pay anything.** A template declaring nothing does not push. In
`win_sec` that means `event_record` pushes once per record over its 71 names while every field
template pushes nothing — so the cost tracks declarations, not the 569,199 dispatches
`win_sec_strict` makes.

**Shadowing is a run-time stack, not a compile-time resolution.** *This was first written the other
way and the correction matters more than the claim.*

The wrong version said two declarations of the same name at two sites are two slots, each reference
resolved to one of them when the configuration compiles. That cannot work, because **templates are
not lexically nested**. `Project` holds a flat `List<Template>` wired together by modes, so the
template enclosing an execution is *whichever one dispatched it*, and that is a match outcome
decided by the data.

Concretely: `A` declares `x`, `B` declares `x`, both apply into mode `M`, and `C` sits in `M` and
reads `x`. Whether `C` sees `A`'s or `B`'s depends on which of them matched. No compile-time
analysis has that answer.

**So there is one slot per name**, which is what `Names` already is —
`Map<String, VarName>`, name to slot — and a reference resolves to that slot. Which *declaration's*
value is in it is whatever the innermost live declaration put there, maintained by the frame stack
(§8). Resolution is therefore **dynamic**, in the precise sense that the scope chain is the dispatch
chain rather than a nesting in the configuration text.

*Lifetime stays static, and the distinction is the whole reason this is tolerable.* A variable
declared on template `T` lives for `T`'s execution — that is fixed by where it is written and does
not depend on who dispatched `T`. What is dynamic is only *which* declaration a reference lands on
when a name is declared in more than one place.

**E49's design rejected dynamic scoping, and this is not the thing it rejected.** It ruled out
*binding into the enclosing scope*, which would have made a template's captures live as long as
whoever called it — the same template getting different lifetimes from different call sites. Here
the lifetime is the declaring template's, always. Only resolution follows the dispatch chain, and
only when a name has two declarations.

### When a declaration happens, and what nested executions see

**A declaration is an action on entry** to the declaring template's execution. The variable comes
into being there and is destroyed when that execution ends — entry to exit, not per dispatch and
not per match of some other template.

**A descendant that does not re-declare the name shares the variable and may mutate it.** That is
how accumulation works: a nested template's `append` adds to the ancestor's list, because there is
one variable and the nested execution never made another.

*So a template cannot accumulate into a variable it declares itself.* Eight matches are eight
executions and eight declarations. A list being filled by a repeating template is declared by that
template's **parent**, and the repeating one appends to it.

**Recursion shadows, and this is exactly why.** A template that declares `x` and then re-enters
itself declares `x` again at the inner entry, so the inner execution gets its own — which is the
frame restore of §8, and this pins when stacking actually occurs: **at a declaration made inside a
cycle**, not at any re-entry. A template that declares nothing and recurses costs nothing, and a
variable declared *above* the recursion is shared and mutable by every level, which is usually what
a recursive walk wants.

*Both motivating cases fall out.* `event_record` declares, its descendants assign, it reads after
the apply returns, and everything dies with the record — E49's fix by construction rather than by a
clearing rule. And the CSV headings cannot live on `header_column`, which runs once per column;
they would go on `header_row`, except that they must outlive it to reach the data rows, so they are
declared **on the source** — which §4 already said and this confirms from the other direction.

### Why declaration must be separable from capture

This is forced by the corpus, not chosen. In `win_sec`, **`event_record` reads 71 names captured
by other templates** — `$LogonType` is captured by the `LogonType` template and read by
`event_record` after the apply returns. The dominant pattern is *a descendant assigns, an ancestor
reads*.

If a capture also declared, every one of those 71 would live in the capturing template's scope and
be invisible to the reader. So the record template declares, its descendants assign, and it reads
what they wrote — declare above, assign below — and it needs no new idea.

*And it is how DS3 fidelity is kept.* DS3 clears once per `parse()`, so migrated declarations go
**on the source**, and E19's pinned behaviour becomes a declaration rather than an inference. A native
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

*The test this must pass is that the model gets smaller.* Fourteen binding constructs — twelve in
`Binding` and two outside it — become one declaration with several value sources. If it does not
come out smaller, it is not this design.

**Two things not to gloss.** `Binding` is a *compile-time* construct and says nothing about the
run-time `Store`; §5's types are a different run-time shape, not merely a different declaration,
and that is where the work actually is. And `Param` folds in too *(ruled 2026-09-14, reversing
2026-09-13)*. It was kept out while scopes were per template, because a parameter's lifetime is a
call and the existing push already got it right. Block scope supplies the reason that was missing:
a parameter **is** a declaration scoped to the callee's body, and excluding it would leave one
binder whose lifetime the general rule can express but does not. No binder remains outside.

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

### The operation surface

**Named after XPath 3.1's `array:` and `map:` libraries**, because this configuration language
already follows XSLT deliberately — `KeyGet` is *"XSLT's `key()`"*, `Sum` is *"XPath's answer for
`sum(())`"*, and `OutputNode` cites one or the other a dozen times. Inventing a parallel vocabulary
for the same operations would be the port artefact this codebase avoids.

**Mutations are instructions; accessors are functions.** Both are spelled like calls, but a
mutation is a statement in a body — where `Append` already sits, as a `Leaf` rather than a
`Binding` — and an accessor appears inside a reference expression. Nothing mutates from inside an
expression.

#### Mutations — statements in a body

| list | map | set | after |
|---|---|---|---|
| `append(list, value)` | | `add(set, value)` | `array:append` |
| `insert(list, position, value)` | | | `array:insert-before` |
| `put(list, position, value)` — replaces | `put(map, key, value)` | | `array:put`, `map:put` |
| `remove(list, position)` | `remove(map, key)` | `remove(set, value)` | `array:remove`, `map:remove` |
| `clear(list)` | `clear(map)` | `clear(set)` | — |

#### Accessors — functions in an expression

| list | map | set | after |
|---|---|---|---|
| `get(list, position)` | `get(map, key)` | | `array:get`, `map:get` |
| `size(list)` | `size(map)` | `size(set)` | `array:size`, `map:size` |
| `contains(list, value)` | `contains(map, key)` | `contains(set, value)` | `map:contains` |
| `last(list)` — §8: does not skip absence | `keys(map)` → list | `values(set)` → list | `map:keys` |

#### Folds

`sum()`, `avg()`, `min()`, `max()` — design 16's folds, now taking a reference because a reference
can denote a collection. **`count()` is not among them: it is `size()`.** Design 16's `Count` counts
a sequence's entries, which is what `size` does, and `size` applies to all three types. **Nor is
`distinct()`:** `DistinctValues` becomes a set, and `values(set)` is the list of distinct entries in
first-appearance order.

#### What following XPath settled

- **`append`, not `add`, for a list.** `array:append` is the standard's name and `Append` is
  already the engine's. `add` would have been Java's word in an XSLT-shaped language.
- **`put` covers replace-at-a-position and bind-a-key**, which read as one idea — *put this value
  at this location* — and are `array:put` and `map:put` respectively. This deletes `replace`, and
  with it the collision against the set *type* that `set(list, i, v)` would have had.
- **`remove` is positional on a list**, as `array:remove` is. That resolves the ambiguity by
  convention rather than by inventing `removeAt`. **There is deliberately no remove-by-value for a
  list** — XPath has none either, and a filter-and-rebuild is its answer. If one is wanted it needs
  its own name, `removeValue`, rather than an overload nobody can read.
- **`contains`, not `has`**, after `map:contains`.

#### Positions are 1-based

`array:get($a, 1)` is the first member, and the engine's own match counts already start at 1 —
`counts[winner]++` before `set(matchCount, …)`. So 1-based indexing is both the standard's and the
engine's existing convention, and a 0-based collection beside a 1-based match count would be a
trap.

#### `for-each` over a map binds two names

XPath offers both readings: `map:keys($m)` to walk the keys and look each value up, and
`map:for-each($m, function($k, $v) {…})` to walk entries with both bound. **The second is the
better fit** — `ForEach(select, as, …)` gains a second binding, so a map walk has its key and value
without a lookup per entry. `keys(map)` remains for when only the keys are wanted.

#### Verified against Saxon

*Checked 2026-09-14 against Saxon-HE 11.4's function-set classes, which this repository already
depends on.* Every name above exists: `ArrayAppend`, `ArrayInsertBefore`, `ArrayPut`,
`ArrayRemove`, `ArrayGet`, `ArraySize`; `MapPut`, `MapGet`, `MapRemove`, `MapContains`, `MapKeys`,
`MapSize`, `MapForEach`. The `array:` library also has `head`, `tail`, `subarray`, `reverse`,
`sort`, `filter`, `fold-left`, `fold-right`, `join` and `flatten`, and `map:` has `entry`,
`entries`, `merge` and `find` — worth knowing as the vocabulary to reach for if any of those are
wanted later, rather than inventing a name.

#### Why XSLT is immutable, and why this is not

Worth setting down, because "we diverged from XSLT" reads better with what the divergence costs.

**XSLT's immutability buys the processor freedom, not the author safety.** `xsl:variable` binds a
name to a value; it does not allocate a cell. The language is declarative and descends from a
Scheme-based ancestor, and it deliberately leaves evaluation order to the processor. That is what
pays for:

- **laziness and reordering** — a variable can be evaluated when first read, or never, or hoisted;
  Saxon leans on this heavily;
- **streamability** — XSLT 3.0's streaming analysis has to prove a stylesheet processes its input
  in one downward pass, which mutation would defeat;
- **parallel evaluation** — a mutable accumulator across a parallel `for-each` is a data race;
- **referential transparency** — a name means the same thing everywhere in its scope, so a reader
  and an optimiser can both reason locally.

**The cost lands on exactly the tasks a parser is made of.** Accumulating a list means recursion,
sequence construction, or `fold-left`; counting means `position()` or a recursive template;
building a lookup means `xsl:key`, a special form, rather than a map you fill. *How do I increment
a counter* is a perennial XSLT question with an unsatisfying answer, and that is not an accident —
it is the trade being paid.

**This engine has already declined every freedom that trade buys.** `Body.body(CompiledOp[] ops)`
walks its ops in array order: no laziness, no reordering, no parallelism, and a bounded-space
promise enforced by explicit guards and chunking rather than by a streamability analysis. So
immutability here would cost the author the same and buy the processor nothing.

**And accumulation is the job, not an edge case.** XSLT transforms a tree that already exists;
this engine builds structure out of a byte stream, where a CSV heading row, a session grouping and
a key index are the ordinary shapes. Design 16 hit the wall from the other side and said so —
`Sequence`'s javadoc reads *"XSLT has no equivalent: it is what makes a value captured in a nested
level outlive the level"*. The immutable model had no way to express the engine's central act.

**What is actually given up.** Not performance and not clarity of order, but referential
transparency: a reference to a name no longer means the same thing at two points in a run. That is
a real loss and it is why §4 spends so much on declarations and scopes — **making lifetime visible
in the configuration is the compensation for a value that can change.** It is also where the one
open hazard comes from: §11's live-element counter can drift under aliasing, and an immutable
collection could not alias.

#### Two places this deliberately diverges from XPath

**XPath's arrays and maps are immutable; these are not.** `array:append` *returns a new array*;
`map:put` *returns a new map*. Ours mutate in place, which is why they are instructions rather than
functions (§5) and why `clear` exists at all — XPath needs no `clear` because you rebind instead.

*The divergence is forced by what this engine does.* A parser accumulates: a CSV heading row
appends once per column, a session groups rows per record. Immutable collections copy on every
append, so accumulating n values costs O(n²) and the bounded-space promise goes with it. Borrowing
the names while mutating is the trade, and it is visible at the call site because a mutation is a
statement and an accessor is an expression.

**`last(list)` is not XPath's.** XPath has `array:head` for the first member and no `last` — it
would write `array:get($a, array:size($a))`. §8 keeps `last` because the most recent value is the
commonest read in this engine and spelling it out every time would be worse; `head` should probably
come with it for symmetry.

### Design 16 already built this, for one type

Worth stating plainly, because it changes what this design is claiming. `OutputNode` already has
`Sequence(name)` — *"Declare a sequence, and empty it"* — and `Append(name, select)`. Its javadoc
gives the same justification this design gives:

> *"Declaring is required rather than implied. It is what puts the accumulation's lifetime where an
> author can see it, and it gives the compiler somewhere to stand: an `append` to a name no
> `sequence` declares is refused."*

So declared accumulation with an explicit append is **not new**. It was built for sequences, and it
works. What design 16 could not do was generalise it, and the reason is written down too.

**The constraint that stopped it: "a reference resolves to exactly one value by construction."**
That is why the folds — `Count`, `Sum`, `Avg`, `Min`, `Max` — take a **sequence name** rather than a
reference, and why `DistinctValues` does too. They are functions over a collection that could not be
spelled as functions, because a reference could not denote a collection. So each became an
instruction instead.

**Collections being values (§5) removes that constraint**, and the twelve `Binding` constructs
collapse accordingly. That is where §11's get-smaller test is won, and it is won against a
limitation the codebase already documented rather than against a design preference.

### The collapse, stated *(ruled 2026-09-14)*

Design 16's constructs fold into the declaration and its types. Ten of `Binding`'s twelve go, and
so do two separate namespaces.

| design 16 | becomes |
|---|---|
| `Sequence(name)` — declare and empty | **declare a list** (§4, §5) |
| `Append(name, select)` | **`append(list, value)`** — an operation, not an instruction |
| `DistinctValues(select, name)` | **a set** |
| `Count`, `Sum`, `Avg`, `Min`, `Max` | **functions over a collection** — `size()`, `sum()`, `avg()`, `min()`, `max()` |
| `Key(name, select, groupBy)` | **a map of key to list of positions** — which needs the nesting §5 rules in |
| `KeyGet(key, select, name)` | **`get(map, key)`** |
| `ValueMap(select, entries, default, name)` | **a map declared with initial entries**, read with a default |

**`Transform` and `Variable` stay**, because they are not collection-shaped. Under §4 they are not
binders either — they are *value sources*, ways of supplying a declaration's value, alongside the
match and the caller.

**Two namespaces dissolve with them.** `Key`'s javadoc: *"Key names are their own namespace: a key
and a sequence may share a name without colliding, because nothing can confuse the two at a use
site."* Sequences were a third. One namespace with declared types replaces all of it, and the
collision rules each namespace needed go with them — including `Sequence`'s refusal of a name that
collides with a capture, which existed because *"a template's first-match clearing would empty the
accumulation underneath it mid-run (design/16 §9)"*. Scoped declarations remove the clearing that
refusal was defending against.

**One property to preserve deliberately.** `Key` is *"an instruction rather than a project-level
declaration, so it runs where its inputs are ready — typically the epilogue, once the level that
fills the sequence has finished — and its cost is paid somewhere an author can see."* Building an
index is expensive and design 16 made that visible on purpose. A map filled by a written `for-each`
and `put` keeps the cost visible in the same way; a map that filled itself would not, and would be
the magic §5 removes.

### Two things design 16 decided that this design changes

**`Append` today refuses to append absence.** Its javadoc: *"An absent value appends nothing — not a
hole. A dense sequence's index is its position, so a hole in one would mean nothing at all; the
sparse reading belongs to capture-indexed stores, where an index is a match number and a gap is
meaningful."*

Design 16 kept two readings apart: a **dense sequence**, where an index is a position, and a
**capture-indexed store**, where an index is a match number and a gap means "did not match". §8
merges them — one list type, and a failed capture appends absence to keep positions aligned. That
is a deliberate reversal of design 16's rule, and it is what lets one type serve both readings
instead of the engine carrying two.

**`DistinctValues` compares by string form**, which §5's equality ruling replaces with canonical per
type. So `1` and `"1"` stop being the same entry. That is a behaviour change with a golden-output
gate on it (§10), and it is the same change the equality ruling makes everywhere else.

### The list

**Backed by a `TypedValue[]` and a count** — the same shape `Store` already has, and the shape
design 33 moved the whole run state to. A list is not a new run-time structure; it is the existing
one with the magic taken out.

The magic being removed is this: today a capture writes at *its template's match count*, so a
template matching repeatedly accumulates a list **as a side effect of matching**, and an unindexed
read takes the highest populated index. Nothing says "append". That is where §2's three meanings
for one index come from, and it is why `latest()` is well defined and meaningless.

The operations are named in *The operation surface* above, which is the one place they are listed.
The point here is only that accumulation is **said** rather than inferred: `append` is written, and
a per-column capture no longer grows a list as a side effect of matching.

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

As with the list, the operations are listed once in *The operation surface*, and plain assignment
is a compile error.

*This also deletes E49's third demonstration outright.* `key=old` beating `key=new` is a dynamic
name indexed by token position; with `put(kv, key, value)` there is no dynamic name and no
positional index, so the defect has nowhere to live.

### The set

**Supported** *(ruled 2026-09-13)*. Membership without duplicates, and **insertion-ordered**,
because a set that reaches output must produce the same bytes every run — determinism is not
optional in a parser whose goldens are byte-compared.

**It subsumes an instruction, which is the point.** `DistinctValues` exists today as one of
`Binding`'s twelve — it walks a sequence, keeps `LinkedHashSet<String>` of what it has seen, and
binds the distinct values in order. That is a set, built by an instruction because there was no set
type to build it into. With one, `DistinctValues` is `add` in a loop and the instruction goes. §11's
test is that the model gets smaller; this is one of the places it does.

#### Which equality?

**The engine already has two notions of "same value" and a set forces the choice.**

- `DistinctValues` keys on `value.asString()`. Under that rule the integer `1` and the text `"1"`
  are the same member.
- `TypedValue`'s variants define real `equals`/`hashCode`. `Encoded.equals` short-cuts on identical
  bytes *and* encoding, then falls through to `Arrays.equals(asUtf8(), …)` — so it already compares
  text by decoded content, and the same text in two encodings is **one** value. Under that rule
  `1` and `"1"` are different members, because an `Integer` is not `Bytes`. *Phase 1 found two
  places the records' generated equality disagreed with `Comparisons.compare` on the same pair:
  `Integer(7)` ordered equal to `Double(7.0)` but was not `equals` to it, and two `Instant`s of one
  moment through different offsets ordered equal but were not `equals`. Both now agree.*
  *(Phase 0 corrected this paragraph: it had claimed the structural rule distinguished encodings,
  and `EqualityCharacterisationTest.sameTextInTwoEncodingsIsOneValue` shows it does not.)*

**Ruled 2026-09-13: neither. Equality is canonical per type, everywhere.** Numbers compare
numerically; text compares by its decoded string, so the same text in two encodings is one value;
different types are never equal, so `1` and `"1"` are two. That governs set membership, map keys,
conditions and `ValueMap` lookup alike — one rule, so `contains(set, x)` and a condition's `=`
cannot disagree about the same pair.

*What it costs, corrected by phase 0's characterisation:* less than first thought. The structural
`equals` already compares text by decoded content and already tells a number from its text, and the
typed `eq` condition does too — `EqualityCharacterisationTest` pins both. What actually changes is
the **string-form** family: `DistinctValues` treats `1` and `"1"` as one entry and will not,
`ValueMap` matches a number against a text entry and will not, and the legacy `equals` alias — the
one condition comparing string forms, by documented design (design 17 §8) — is **retired**: a string
comparison is written as `eq` with `as: string`, and the migration emits that. Any of the three may
move a golden, and §10 is the gate.

#### Sets and nesting

A set of collections needs equality on collections, and the two candidates fail differently: the
string rule cannot work because `asString()` on a collection is refused (§5), and the structural
rule works but is recursive, so `add` costs the size of the element rather than a hash.

The answer is to **refuse collections as set members and as map keys**, which keeps membership
O(1) and costs nothing anyone has asked for. It is a compile-time refusal, which the declared
types (§5) make possible.

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

#### Type checking is one level deep, and that is the price of no generics

A declaration says `map`, not `map of map` — that is what §5's *no generics* means. So the compiler
knows the type of a **declared variable** and nothing about what its entries hold.

```
get(x, "a")              x is declared a map, so this is checked
get(get(x, "a"), "b")    the inner get returns a TypedValue; whether it is a map
                         is a run-time fact, so the outer get cannot be checked
```

**Nesting therefore works at run time and is typed only at the first level.** `map` of `map`,
`map` of `list`, `list` of `list` — all legal, all constructible, all readable. What degrades is
the refusal: applying a map operation to something that turns out to be a scalar is caught where it
happens rather than where it is written.

*This is the trade §5 made deliberately and it should be visible.* Parameterised declarations —
`map<string, map<string, string>>` — would restore compile-time checking at depth and bring
generics, nested type declarations and recursive checking with them. A flat `map` keeps the model
small and pays for it with a run-time failure at depth two. Given the shapes this engine actually
builds — a list of headings, a map of fields, a key index of positions — depth two is already
unusual and depth three is unknown.

**Keys stay scalar.** A collection is refused as a map key and as a set member (below), so
`map` of `map` means a map whose *values* are maps. There is no nesting on the key side and no
reason to want it.

#### What the collection variants still have to answer

These are contained, but each needs a decision and none has an obvious default:

| question | why it is not obvious |
|---|---|
| `asString()` / `asBytes()` on a list or map | there is no natural serialisation, and inventing one (join with commas?) is the kind of magic §5 removes. **A refusal is the likely answer**, made at compile time by the declared type |
| `isEmpty()` | genuinely easy — no entries |
| `Cast` applied to a collection | refuse; a cast converts a scalar's variant |
| equality and ordering in a condition | deep or by identity? Ordering of collections probably has no meaning and should be refused |
| `guardSequenceSize` and `maxSequenceEntries` | **ruled**: one run-wide live-element counter, incremented on every `append` and `put` and decremented in O(1) on clear or scope-exit from each collection's cached total. Nesting is then irrelevant — the counter measures total live elements, which is what the bounded-space promise is actually about |

*The last one had teeth and is now ruled.* The engine's promise is that a stream of unbounded
length runs in bounded space. A recursive walk at the guard would be O(size) per check and so
quadratic over the growth path it watches; a run-wide counter is O(1) and bounds the thing the
promise names.

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

*Naming, ruled 2026-09-13: eight flat, explicit names.* `matchCount()`, `matchIndex()`, `index()`,
`position()`, `last()`, `groupKey()`, `group()`, `groupSize()`. One name per question, nothing to
learn, trivially resolved at compile time. A dotted form (`group.size()`) would carry which context
a counter belongs to, at the cost of a namespace in the expression syntax; the flat names are
unambiguous without it.

*The index position.* A reference's index rule also names these —
`{"match_index": {"var_ref": "__match_count"}}` is the CSV fixture's spelling, and `RefCompiler`
resolves it through the same `EngineVars.byName`. That spelling has to change with the rest, and it
is the one place the function form has to sit inside another construct.

## 7. What this subsumes

- **E19's pinned half** — becomes "declared on the source", faithful by construction.
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
resolves to, which template's entry declares which slots and restores them on exit. What is left
at run time is small, and the
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
`pop()` walking back to the mark. It grows by doubling, never shrinks, and is never copied or
rebuilt; `depth` and `undoCount` are the only state that moves.

*A flat log beats a per-frame array of restorations*, which is the other way to build this: one
allocation that amortises rather than one per frame to sit idle, and nothing to reuse or reset
because `undoCount` is the only cursor.

### What is left to pay, precisely

A scope is **an index, not an object**. Entering one is `marks[depth++] = undoCount` and nothing
else. The stack arrays are never rebuilt, never copied and never shrunk — they double when they
fill and stay that size for the run, with `depth` and `undoCount` as the only cursors.

Per declared slot, entry costs three array writes — the slot number, the owner it had, the value it
held — and one write to set the slot unset. Exit walks back to the mark and restores, four array
operations per entry. There is no search, no allocation and no per-slot structure.

**And under this design it becomes allocation-free.** `bind()` today does
`slots[slot] = new Store()`, two objects per declaration. With a slot holding a `TypedValue`
directly, declaring writes null. So a scope that declares five names is an index increment and
twenty array writes, and allocates nothing at all.

The cost therefore tracks **declarations**, not dispatches: a template that declares nothing does
not touch the stack, and `win_sec`'s field templates would not.

**The stack is bounded by construction and cannot overflow.** Every push is matched by a pop — six
of each in `Body` — so the log unwinds as the run descends and returns; and dispatch depth is
already capped, `Body` refusing at `directive.maxDepth()`, 64 by default, which is the runaway
guard for recursion. So `marks` and the undo log reach a high-water mark of roughly
*max depth × declarations per scope* and stay there for the run. No additional cap is needed, and
a depth cap would not catch §11's concern anyway, because that concern is not about depth.

### Holes: absence is appended *(ruled 2026-09-13)*

Today an unmatched capture calls `store.remove(matchCount)`, leaving a **hole** so positions stay
aligned with match numbers — design 25 §9's rule that an unmatched capture reads as empty rather
than as whatever the previous record left there, and that a failed cast is absent the same way.

With an explicit `append`, a capture that does not match would simply not append, and every later
position would shift — which breaks the CSV case, where `data_column` reads the Nth heading by its
own match count.

**So a failed capture appends absence.** Alignment is preserved, and it is preserved by the
configuration saying what a failed capture means rather than by a hole appearing as a side effect.
That is the same move as `append` itself.

#### `last` does not skip absence *(ruled 2026-09-13)*

Today "the latest value" is *the latest present value*: `Store.lastIndex()` walks back past nulls,
and — phase 0 found — a failed **final** capture is not recorded at all, since `Store.remove` does
not grow the store. Either way a template that matched three times with the third capture failing
reads the second match's value (`TrailingHoleReadTest`).

**Ruled: it does not skip.** `last(list)` returns the last element, absent
included. Skipping is the magic this design removes, and a read that walks back to an earlier match
is returning a value from a position the data did not fill — the same shape of defect as E49, where
a record with no `k=` answered with the previous record's `v`.

*It may move a golden, and that is the gate.* The difference only shows when the **final** match's
capture failed; every other case reads the same either way, because E19's clear already removes
holes left by a previous record. If a golden output does move, §10 applies: either the rule is
wrong or the divergence is deliberate and ruled, and E19 is the precedent for recording it.

*If the skipping behaviour is wanted*, it should be a separate named operation rather than the
default, so that a configuration asking for "the last one that matched" says so.

## 9. Rulings

### Settled *(2026-09-13 and 2026-09-14)*

| | ruling | where |
|---|---|---|
| **Declaration** | A variable must be declared, with a scope and a type. Using an undeclared name is a compile error, as an unwritable name already is — so scope and type are never inferred from position. Every native fixture gains declarations, which §0 permits and which is real work. | §4 |
| **Typing** | The type is part of the declaration, not inferred from first assignment. Inference survives as a check. | §5 |
| **Types** | All four: scalar, list, map, set. | §5 |
| **Collections** | Are `TypedValue`s, and therefore nest. The earlier flat restriction was wrong and its reasoning is kept in §5. | §5 |
| **Equality** | **Canonical per type, everywhere** — set membership, map keys, conditions, `ValueMap` lookup. Numbers compare numerically; text compares by decoded string, so encoding is irrelevant; different types are never equal, so `1` and `"1"` differ. *Phase 0 found the structural `equals` and the typed `eq` already behave this way*; what changes is the string-form family — `DistinctValues`, `ValueMap`, and the legacy `equals` alias, which needs its own ruling. | §5 |
| **Nesting and keys** | Collections nest as *values* — `map` of `map`, `map` of `list`, `list` of `list`. They are refused as set members and map keys, at compile time. Type checking is one level deep: a declaration says `map`, not `map of map`, so a nested read is a run-time fact. That is the price of no generics, and it is paid where the shapes this engine builds are one level deep anyway. | §5 |
| **Counters** | Become functions resolved to `CompiledRef.Context` at compile time, not reserved `__` variable names. Special forms, never registry functions. | §6 |
| **Holes** | A failed capture appends absence, so positions stay aligned by the configuration saying so rather than by a hole appearing as a side effect. | §8 |
| **`last`** | Does not skip absence — it returns the last element. Can only move a golden when the *final* match's capture failed; §10 is the gate and E19 the precedent for recording a divergence. | §8 |
| **One declaration per name** | Once per namespace — today, once per configuration, since there is one implicit namespace. Makes dynamic resolution unambiguous. Stated per namespace so template libraries survive it; libraries will need names *and* modes qualified, as XSLT's QNames do. | §4 |
| **Resolution** | Dynamic, in the precise sense that the scope chain is the *dispatch* chain: templates are a flat list wired by modes, so the enclosing execution is whoever dispatched you. One slot per name, as `Names` already is; the frame stack decides whose value is in it. Lifetime stays static — a variable lives for its declaring template's execution regardless of who dispatched it. | §4 |
| **Declaration timing** | A declaration is an action on entry to the declaring template's execution; the variable lives entry to exit. A descendant that does not re-declare shares it and may mutate it, which is how accumulation works — so a template cannot accumulate into a variable it declares itself. Recursion shadows because a recursive execution re-declares, which is the existing frame restore rather than anything new. | §4 |
| **Where declared** | Where a declaration is written *is* its scope; there is no scope attribute and no second scope kind. The source template is the outermost execution — `Run.document()` runs once per stream with the chunk loop inside it — so declaring there lasts the run. Declaring on a **root-mode** template under a `classify` or `any` root gives *chunk* lifetime, which the compiler can see and should refuse or warn. | §4 |
| **Counter names** | Eight flat, explicit names: `matchCount()`, `matchIndex()`, `index()`, `position()`, `last()`, `groupKey()`, `group()`, `groupSize()`. | §6 |
| **Block scope** | A declaration's scope is the execution it is written in, and a `for-each`, `call-template` or `variable` body is an execution. All six of `Body`'s pushes become the one declaration mechanism; `recursiveShadow()` goes, since declaration-on-entry shadows exactly what the entered template declares where that flattened every candidate's capture names. | §4 |
| **`Param`** | Folds into the unified declaration *(reversing the 2026-09-13 ruling)*: under block scope a parameter is a declaration scoped to the callee's body. No binder remains outside. | §4 |
| **Size guard** | One run-wide live-element counter: every `append` or `put` increments it, every collection caches its own total so a clear or scope-exit decrements in O(1). Nesting is irrelevant because the counter measures exactly what the promise is about — total live elements — whatever shape they are in. | §5, §8 |
| **Operation names** | After XPath 3.1's `array:` and `map:` libraries, which this language already follows: `append`, `insert`, `put`, `remove`, `get`, `size`, `contains`, `keys`, plus `add` for a set, which XPath has no equivalent of. Positions are 1-based, as XPath's are and as the engine's match counts already are. `for-each` over a map binds two names, after `map:for-each`. | §5 |
| **The collapse** | Design 16's `Sequence`, `Append`, `DistinctValues`, the five folds, `Key`, `KeyGet` and `ValueMap` fold into declarations, collection types, operations and functions — ten of `Binding`'s twelve, plus the separate key and sequence namespaces. `Transform` and `Variable` remain as *value sources*, not binders. | §5 |
| **`equals`, `not-equals`, `ref-equals`** | **Retired** *(ruled 2026-09-14)* — the whole string-form family design 17 §8 names together. `ref-equals` compared two references with both-absent-is-equal, the DS3-era rule the strict `eq` rejects; it had no users, no writer arm and no migration emitting it. A string comparison is now written as `eq` with `as: string` on the operands — said, not implied by a spelling — and `equals($x, "")` keeps compiling to `not(exists($x))`. Phase 0 checked the blast radius: `Ds3Migration` never emits `equals`, so the legacy goldens are untouched; twelve native fixtures and four unit tests use it and are rewritten in phase 1. One equality rule, no exceptions. | §5 |
| **DS3 migration** | Declares **everything on the source**, which is run lifetime. `root.clear()` runs once per parse and never between records, so that is provably faithful and is the only option that cannot move a golden. Migrated configurations will not demonstrate the new scoping, which is a cost worth paying for correctness by construction. | §4 |

### Nothing open

The last two questions are answered: which of `VarRegistry`'s pushes survive, by the block-scope
ruling in §4; and the legacy `equals` alias, retired (table above). **The model is settled and so is
its implementation shape.** What remains is building it, and §10 is the gate.

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
- **`win_sec_strict` is the performance gate**, for §8's cost, which scales with declarations ×
  executions.

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
- **If absence turns out to need more than one kind.** §8 appends absence to keep positions
  aligned, which is clean. But the engine already distinguishes a hole from an empty value, and a
  failed cast from an unmatched capture; if configurations need to tell those apart after the fact,
  one appended absence is not enough and the distinction has to be carried in the value.
- **If the live-element counter drifts.** A single run-wide count is only a bound while every
  increment has a matching decrement. Aliasing — the same collection value reachable from two
  places — would double-count on append and under-decrement on clear, so either aliasing is
  refused or the counter is wrong in a way nothing would notice until a long stream ran out of
  memory.
- **If the model does not come out smaller.** §4 sets that as the test: fourteen binding
  constructs — twelve in `Binding` and two outside it — should become one declaration with several
  value sources. A
  unification that adds a concept and keeps the old ones has failed on its own terms.
- **If one scope rule is not enough.** The CSV headings live on the source and the record fields
  on the record template, both of which the one rule covers. A case wanting something *per
  dispatch* rather than per execution would mean the model is under-powered and the special cases
  come back — and the chunked root is the near miss, since a root-mode declaration is per chunk by
  position rather than by choice.
- **If declarations end up everywhere rather than where they are needed — a throughput concern,
  not a memory one.** Entry is an index increment and a few array writes per declared slot: cheap
  per declaration, but it scales with *declarations × executions*. Seventy names declared on a
  template entered 569,199 times is tens of millions of log writes per operation while the stack
  stays two deep, because each execution pushes and pops. **A depth cap would not catch this**, and
  §8 explains why the stack cannot overflow in the first place. The mitigation is measurement, not
  a limit: §10 names `win_sec_strict` as the row, and the answer if it shows is to move the
  declarations rather than to bound the mechanism.
- **If restore-on-exit is not where the cost went.** Design 33 bought +26.7 points on
  `apache_httpd` from run-state access. A lifetime model that spends it back has not earned its
  correctness, and §10 names `win_sec_strict` as the row that would say so.

## 12. The plan, in phases

**Six phases, each a commit and a benchmark point, each leaving every golden output byte-identical.**
The ordering follows three rules that this codebase has paid for learning:

1. **Characterise before rewriting** (design 34 §5, §8). Every path a phase will change gets a test
   of its *current* behaviour first, and the test is sabotaged to prove it holds. The corpus does
   not run most of what this design touches — `sequence`, the folds, `Key`, key-value captures,
   recursive applies — so the goldens alone will not catch a wrong phase.
2. **The run time changes once.** `Store` going, slots becoming `TypedValue[]`, declaration
   push/pop and the live-element counter are one coherent unit and land together, so there is one
   hot-path change to measure rather than four half-states to reason about.
3. **A fixture is rewritten in the phase that removes what it used**, and the DS3 migration is
   updated in the phase that changes the shape it emits — never a phase later, because the legacy
   goldens run through the migration and would move.

### Phase 0 — Tests for what the corpus does not run

*Nothing changes.* Characterisation tests, at configuration level, for every construct a later
phase rewrites: each of `Binding`'s twelve, `Append`, key-value captures, `Param` binding, all six
of `Body`'s push sites including a recursive apply, `latest()` skipping a hole, `DistinctValues`'
string-form equality, and the chunked-root accumulation guard. Each test sabotaged: the construct
disabled, the test fails.

*Gate:* the count of new tests and their sabotage table, recorded here. *Point:* none — no code
moves.

**Done 2026-09-14, uncommitted pending review.** Existing coverage was far broader than this list
assumed: `SequenceIterationTest` already pins `sequence`/`append` (including absence appending
nothing), all five folds, `key`/`key-get`, grouping, sorting and the chunked-root guard;
`EngineBehaviourTest` pins params (including shadowing captures), recursion's structure, `value-map`'s
default, and E19; `StoreTest` pins `latest()` over a middle hole. So the gaps were narrower —
**twelve new tests in five classes**, each held by at least one sabotage:

| test | sabotage | failing |
|---|---|---|
| `KeyValueCaptureTest` ×3 — pairs readable; E49 demo 2; E49 demo 3 | key-value branch in `bindCaptures` disabled | 3 |
| `variableBodyCannotReadTheValueItIsComputing` | `variable()`'s self-shadow removed | 1 |
| `recursionLevelsKeepTheirOwnCaptures` | `recursiveShadow()` not pushed | 1 |
| `distinctValuesTreatsANumberAndItsTextAsOneEntry` | keyed by type as well as string | 1 |
| `equalsAliasComparesStringForms` | operand cast `STRING` → typed | 1 |
| `eqAlreadyTellsANumberFromItsText` | mixed types compare equal | 1 |
| `valueMapMatchesANumberAgainstATextEntry` | lookup key altered | 1 |
| `sameTextInTwoEncodingsIsOneValue` | `Bytes.equals` by identity | 1 |
| `readAfterAFailedFinalCaptureWalksBackToThePreviousMatch` | failed cast writes a marker | 1 |
| `namedTransformBindsRatherThanWrites` | `emit()` writes even when named | 1 |

*Three of these deliberately pin behaviour phase 3 flips* — E49's demonstrations 2 and 3, and the
trailing-capture read — and say so in their javadoc, so that the flip is an edit of a failing test.

*And characterising corrected the design twice.* Structural `equals` already compares text by decoded
content and the typed `eq` already tells a number from its text (§5 had claimed otherwise); what
canonical-per-type actually changes is the string-form family. And a failed *final* capture is not
recorded at all — `Store.remove` does not grow the store — so today's read lands on the previous
match by absence of a record, not by skipping a hole (§8 wording).

### Phase 1 — Collections are values, and equality is canonical

`TypedValue.List`, `TypedValue.Map`, `TypedValue.Set` join the sealed interface. `isEmpty()` is
"no entries"; `asBytes()` and `asString()` refuse; `Cast` refuses; ordering in a condition refuses.
Equality becomes **canonical per type** on every variant. Phase 0 found the structural `equals` and
the typed `eq` already behave that way, so what changes is the string-form family: `DistinctValues`
stops keying on `asString()`, `ValueMap` stops matching a number against a text entry, and the
legacy `equals` condition is **retired** — the twelve native fixtures and four tests using it move
to `eq` with `as: string` (the migration never emitted it), and `equals($x, "")` keeps compiling to
`not(exists)`. `Set` is insertion-ordered.
Nothing declares or uses the new variants yet.

*What can move a golden:* the equality change, exactly where `1` met `"1"` or the same text met
itself in two encodings. If one moves, E19 is the precedent for ruling it. *Point:* conditions are
evaluated per record — `apache_httpd` runs 7,152 `and` per operation — so that row is the control
for the equality path; expect flat.

**Done 2026-09-14, uncommitted pending review.** No golden moved: 1,223 tests across the four
modules, 0 failures, byte-identical output through both the native and the migrated families.

*What was built.* `TypedValue.Collection`, sealed over `List`, `Map` and `Set`: mutable, ordered,
canonically keyed, no text form (`asString`/`asBytes` refuse), no numeric reading (absent), a
collection refused as a key or member, and `List.last()` returning absence rather than skipping.
Equality made canonical on the variants that were not: `Integer` and `Double` are equal when the
double is exactly that whole number and hash alike, and `Instant` ignores its carried offset —
**both were already how `Comparisons.compare` ordered them, so `equals` had disagreed with `<` on
the same pairs.** `Comparisons.cast` refuses a collection; `DistinctValues` keys on the value;
`ValueMap` looks up by value. The `equals`/`not-equals`/`ref-equals` reader cases and `stringEquality` are gone — `ref-equals` on
the owner's ruling once it was found: the third string-form alias, comparing two references with
both-absent-is-equal, used by nothing, written by nothing.

*The rewrite.* Eighteen JSON resources carried the alias — thirteen fixtures, the E17 original
`win_sec` resource, three pipeline configs, one xmlbench case — 281 fixture uses and a handful more, rewritten JSON-aware with each file's own indent and
trailing-newline convention so nothing else in them moved. Two things the rewrite had to know
that a naive `equals → eq` would have got wrong: an empty literal compiled to `not(exists)` /
`exists`, and **`not-equals` compiled to `not(eq)`, not `ne`** — they differ on an absent field,
and `CompareSpineTest` had pinned it. Three alias tests in that class go with the alias; three
others move to the `eq` spelling.

*Sabotage, each against the engine suite:*

| behaviour | sabotage | failing |
|---|---|---|
| `distinct-values` canonical | keyed on string form again | 1 |
| `value-map` by value | looked up by string form again | 1 |
| `Integer` equals whole `Double` | cross-kind branch removed | 1 |
| `Instant` ignores offset | offset compared again | 1 |
| collection refused as key/member | guard disabled | 1 |
| cast refuses a collection | guard disabled | 1 (the `NUMBER` cast; a `STRING` cast refuses through `asString` anyway) |
| `last()` does not skip absence | made to skip | 1 |

*Two things the sabotage corrected.* A guard in `Comparisons.compare` refusing collections was
redundant — an unknown pair already falls through to null — and is replaced by a comment on the
fallthrough. And the cast guard matters only for the non-text casts, which would otherwise answer
*absent* silently; the test now casts to a number.

*Equality tests classes, not interfaces.* On the owner's request the `equals` methods use exact
class compares rather than `instanceof`. The honest scope: every variant is final, and on a final
class the two forms are the same klass-word compare — the change can only matter where the test
was against an *interface*, `Bytes` and `Collection`, which is a secondary-supers lookup. `Bytes`
equality now writes its two classes out (`isBytes`), and the four rewritten equalities are each
re-held by a sabotage: `isBytes` accepting one variant, the whole-`Double` branch removed, the
`Instant` offset compared again, a `List` ignoring a size difference — one test failing each.
Unmeasured, like the rest of this phase.

*Audited.* The eighteen rewritten JSON files are structurally identical to `HEAD` everywhere
except at the condition nodes (a path-walking diff, after a first marker-based diff flagged
`apache_httpd` for an original `not(equals(…))` its marker could not see through); no file had a
duplicate key for `json.loads` to collapse; no `value-map` entry has an empty `from` for an absent
selection to have matched; no import in the six edited main files is unused; and
`CompareSpineTest`'s helpers all still have callers after its three alias tests went.

*Not measured.* The benchmark point waits for a quiet box, as every point in this sequence has.

### Phase 2 — Declarations, in the model and the compiler

`Declaration(name, type)` on a template and on the source; the JSON to read and write it.
`ReferenceCheck` gains three refusals: an undeclared name; a second declaration of a name; an
operation that disagrees with a declared type. Counters become functions here too — `matchCount()`
and the rest, resolved to `CompiledRef.Context` as today — because the reservation refusal lives in
the same class and goes in the same edit, and because it changes the same fixture text.

**The compiler still targets today's run time.** A declaration compiles to a registered slot and
nothing else, so this phase is compile-time only. *Every native fixture gains declarations* and
every `__match_count` spelling changes, in one pass, with output byte-identical. The DS3 migration
emits its declarations on the source.

*Gate:* every fixture passes; a fixture with a declaration removed fails at compile time with a
message naming the reference. *Point:* the compile rows may rise; every run row must be flat — this
phase is a control, and a run row moving means something leaked.

**Done 2026-09-14, uncommitted pending review.** No golden moved: 1,193 tests across the four
modules, 0 failures, byte-identical output through both the native and the migrated families.
Thirty fewer tests than phase 1 counted: the reservation refusal's forty pinned cases went with the
refusal, and nine declaration tests came.

*What was built.* `Declaration(name, type)` on a template — `scalar`, `list`, `map` or `set` —
read and written between `param` and `match`; the compiler interns each as a slot and nothing
else. `ReferenceCheck` gained the three refusals: a name bound or read that no declaration names;
a second declaration of a name, naming both templates; and an operation disagreeing with the
declared type — a walk, fold, append, `sequence`, `distinct-values` or `key-get` name must be a
list, a key built or looked up must be a map, an indexed read must be into a list. A parameter, an
argument and a loop's `as` are declarations in place (§4) and owe no other. A key-value capture
stands the rule down, as it already stood the read check down. Counters became functions:
`EngineVars` carries the eight function names, `RefPart.Counter` and `MatchIndex.counter` carry a
reference to one, and the JSON spells them `{"function": {"name": "matchCount"}}` and, in an index
rule, `"function": "matchCount"` beside `var_ref`. `group()` is interned under its own spelling,
so a `for-each` whose select is `group()` resolves to the same slot with no special case, and a
declaration refuses parentheses so nothing can take that name. The reservation refusal,
`EngineVars.ALL` and the `__` prefix are gone from the engine's own names; author names that
happen to carry the prefix (`__record_body__`) are untouched, because nothing is reserved now. The
DS3 migration declares every bound name once, on the envelope, as a list: DS3 clears its stores
once per parse and indexes them by the parent's match count, which is a run-lifetime list.

*Two scope decisions, deferred to phase 4 rather than guessed here.* A scalar bind — a capture, a
`variable`, a transform — on a list-declared name is accepted: it is what today's match-indexed
store does, and what it should mean is the operation surface's question. A bare read of a list is
accepted for the same reason.

*The rewrite.* Forty-six JSON resources gained 851 declarations, placed by an analysis of the
dispatch graph: a name is declared on the lowest template from which every template that binds or
reads it is reachable; a `sequence` or `key` instruction's own template wins, because design 16
already put the lifetime where the author wanted it; a guard reading a name its own template binds
reads the previous execution's value, so that name is placed above. Fourteen resources and
twenty-six test classes had the eight spellings changed; four fixture files not in the canonical
layout were reformatted (019, avro, parquet, protobuf) and everything else moved only at the
declarations and the function parts, checked structurally against `HEAD` — 46 of 46.

*What the rewrite found.* Fixture 019 has two templates both named `unnamed`; the analysis,
keyed by name, gave both the block, and the declared-twice refusal caught it — the first thing
the new refusal refused. `keyAndASequenceMayShareAName` asserted design 16 §9's separate key
namespace, which the one-namespace rule retires; it now asserts the type refusal instead. And
several test blocks bind names in Java-supplied fragments the analysis cannot see: those
declarations were added by hand from the refusals, which is the gate working as described.

*Sabotage, each against the engine suite:*

| refusal | sabotage | failing |
|---|---|---|
| a bound name must be declared | check disabled | 1 |
| a read name must be declared | check disabled | 1 |
| a name is declared once | check disabled | 1 |
| an operation agrees with the type | check disabled | 1 |

The gate's own case is pinned as a test: a native fixture compiles as shipped, and the same
fixture with its declarations removed fails at compile time naming the template and the name.

*Audited.* The forty-six rewritten JSON files are structurally identical to `HEAD` once their
declarations are removed and the function respelling applied; checkstyle is clean on main and
test in every module; no engine spelling with the prefix survives in main code, where only
javadoc changed; and the pipeline and xmlbench suites pass without a change to either module's
code.

*What the audit found.* The in-place declarations — a parameter, a loop's `as` — were held as a
configuration-wide set, so a parameter named `x` in one template let an undeclared `x` pass in
any other; and a `with-param` was marked as the *caller's* in-place declaration, though it names
the callee's parameter. Both are now per template, and an argument only makes the name writable.
The placement analysis had the same leak, and had hidden five cases behind it: three
`EngineBehaviourTest` configurations whose called template read a name its own `param` list never
declared (the caller's argument had stood in as the declaration, which §4 retires — the callee now
declares the parameter), and the `sequence_basics` and `sort` xmlbench cases, whose `v` and `at`
the analysis had skipped because a loop elsewhere used the same name; both were regenerated from
`HEAD` with the analysis corrected. Four javadoc lines that still described keys as their own
namespace, or an index rule's function as a variable, were brought up to date.

*Not measured.* The benchmark point waits for a quiet box. This phase is the control: the compile
rows may rise, and every run row must be flat.

### Phase 3 — The run time changes once

The payoff phase, and the one that must be measured rather than reasoned about.

- `VarRegistry.slots` becomes `TypedValue[]`; `undoSaved` with it; `Store` is deleted.
- Entering a declaring block is `push(declared)`; leaving is `pop`; `recursiveShadow()` goes.
- **Captures become value sources.** A capture assigns to a declared scalar, or appends to a
  declared list — appending absence when it does not match (§8). E19's first-match clear in
  `Level.processMatch` goes, because restore-on-exit replaces it.
- **Key-value captures put into a declared map.** `Names.keys`, `grow(String)` and the dynamic slot
  path go; the slot array is fixed from the declarations.
- The live-element counter replaces `guardSequenceSize`'s flat count.
- `append` and `put` exist as instructions because captures need them; the rest of the surface
  waits for phase 4.

*Gate:* every golden byte-identical, including the legacy family through the migration — this is
where E49's four fixtures would move if the declared lifetime were wrong. Sabotage the restore on
exit, the absence append, and the counter's decrement. *Point:* **the reference-heavy rows** —
`apache_httpd`, `log_sessions`, `csv_header`, `ausearch` — for the `Store` indirection going; and
**`win_sec_strict`** for the push per declaring execution, which is §10's gate. Expect the first
four up and the last flat; if `win_sec_strict` falls, §11's throughput risk is real and the answer
is to move declarations, not to bound the mechanism.

**Done 2026-09-14, uncommitted pending review; not yet measured.** No golden moved: 1,181 tests
across the four modules, 0 failures, byte-identical output through both families — the legacy
family through the migration's envelope declarations included, which is where E49's four fixtures
would have moved.

*What was built.* `VarRegistry.slots` is `TypedValue[]`, `undoSaved` with it, and `Store` is
deleted: a scalar's slot holds the value, a list's a `TypedValue.List`, a map's a
`TypedValue.Map`, made on first mutation in the slot the declaration owns — so declaring writes
null and allocates nothing. Entering an execution that declares is `push(declared)` and leaving
is `pop`, for templates (`Level`, before the captures bind, so a recursive template binds its
own level's), the source (`Run`, spanning the run), calls, loops and `variable` bodies; a
template declaring nothing pushes nothing, and `recursiveShadow()` is gone. Captures are value
sources: a capture assigns the scalar it names, or puts at this match's position in the list it
names — absence included, so a failed capture appends absence and positions stay aligned (§8) —
or puts into the map it names, which is where a key-value pair now goes. `Names.keys` stays for
the key indexes (phase 4's collapse); `grow(String)`, the extended table and every string lookup
at run time are gone, and the slot array is fixed from the declarations. One run-wide live-element
counter replaces `guardSequenceSize`: every append, positional put and new map key increments it,
and a clear, a replaced collection or a scope exit decrements by the collection's size.

*Three things the corpus corrected, recorded rather than reasoned away.*

1. **The first-match clear stays, as the capture's own rule on a list.** The plan said
   restore-on-exit replaces it, and it does for a list declared on the capturing template's
   parent — but the migration and the DS3-derived fixtures declare their lists for the run, and
   there a template that matches fewer times than the sequence before would leave the earlier
   tail past its own length, which is exactly E19's "real divergence, fixed" and DS3's
   `parentMatchCount == 0` clear. So a template's first match of a sequence restarts the lists
   its captures fill (`Compiler` decides which names those are, from the declared types) and
   nothing else; a scalar is assigned and needs no clearing. Held by a list-declared twin of the
   E19 test.
2. **A map needs one read this phase.** The plan gave captures `put` and left the surface to
   phase 4, but `ausearch` reads its pairs back. `{"get": {"var_id": m, "key": k}}` arrives now —
   XPath's name, a literal key, compiled to an `Entry` read — and the check requires the name
   declared as a map. The key-value stand-down in `ReferenceCheck` is gone with it: nothing
   arrives from the data that a declaration did not foresee.
3. **Placement now decides lifetime, and the phase-2 analysis had placed for reachability.**
   Phase 2's gate could not see it. The DS3-derived native configurations — the `win_*`
   family, E17's original, `019` — declare on the source, because DS3's lifetime is the run and
   their goldens record it (§4, §10); `ausearch`'s `__kv` map likewise, since the dynamic slots
   it replaces were never cleared. Six test configurations moved a name above the template that
   captured it, each found by its own test: a list the source walks or indexes after the lines
   that fill it, a capture reading its own previous row's value, the E19 pin whose leak is a
   declaration on the source now. None of these is a golden moving; each is a declaration
   saying where the value was always meant to live.

*Two smaller things.* The two phase-0 pins flipped as promised — a name a record did not write
reads nothing, and this record's binding is what this record reads — and the trailing-capture
read is absence. And a list written from position one carries an absent position zero until
phase 4 moves positions, which the live-element counter counts honestly; the one limit test
says so.

*Sabotage, each against the engine suite:*

| mechanism | sabotage | failing |
|---|---|---|
| restore on exit | `pop` does not restore | 6 (2 behaviour, 1 scope, 3 registry) |
| absence appended | a failed capture into a list writes nothing | 1 |
| the counter's decrement | released collections not subtracted | 2 |
| the first-match clear (correction 1) | lists never restart | 1 |
| declaration on entry | templates do not push | 17 goldens and 103 tests |

*Audited.* The eight JSON files this phase touched are structurally identical to `HEAD` once
declarations are set aside and `ausearch`'s four reads are mapped to their `get` form; checkstyle
is clean on main and test in every module; nothing constructs or names a `Store`.

*What the audit found.* A reference to the whole of a map — a `capture` part on a map-declared
name — resolved to the map itself, and the sink's `asBytes` threw at run time; nothing had refused
it at compile. It is refused now, by name, with the get spelled out, and pinned. The same shape
remains for a nested collection reached through a list's last element or a loop's `as`, and that
is phase 4's, where nesting gets its surface. Eight places still described stores — `Names`
claiming the table is consulted at run time, `Level`'s first-match wording, `CompiledTemplate`'s
`clearNames`, the package note, `CompiledRef`, `CompiledRefs`, `Frames`, `Body`'s chunked-root
notes — and say what is there now. The counter's balance and the restore order were traced by
hand: a value re-installed in its own scope is counted twice while the log holds it and released
once at exit, which nets to zero, and the promotion path releases the inner list on its pop and
counts it again when the outer slot takes it.

*Not measured, and this is the phase that must be.* The point waits for a quiet box: the
reference-heavy rows for the indirection going, `win_sec_strict` for the push per declaring
execution — its 95 names are on the source now, so it pushes once per run, which is the shape §11
asks for.

### Phase 4 — The operation surface, and design 16 folds in

The rest of §5: `insert`, `put(list, …)`, `remove`, `clear`; `get`, `size`, `contains`, `keys`,
`values`, `last`, `head`; `sum`, `avg`, `min`, `max` as functions over a reference; `for-each` over
a map binding two names. Then the removals — `Sequence`, `Append` (the instruction), `DistinctValues`,
the five folds, `Key`, `KeyGet`, `ValueMap` — and the rewrite of every fixture that used them:
`log_sessions`, `text_021_trimmed_values_exact`, `text_022_empty_input_exact`, `ausearch`, and
whichever `Key`/`ValueMap` users phase 0 finds.

*Gate:* the model is smaller — `Binding` is gone or holds only `Transform` and `Variable` as value
sources — or §11 says this is not the design. *Point:* `log_sessions` is the sequence-and-fold row.

### Phase 5 — Closing

`win_sec` rewritten under the new model, **as the test of §11's existential risk**: if 60 templates
and 71 variables become materially fewer, the complexity was the fixture's; if not, it is the
domain's, and that is worth knowing either way. E19 and E28 gain closing addenda. The full point set
is read together, points 1 to 5 against the floor at phase 0's commit.

### Out of scope, deliberately

Namespaces and libraries (§4 records what they will need); `removeValue` and any remove-by-value
on a list; the ambiguity tooling design 36 §4 names; `head`/`tail` beyond `head`; and any
declaration syntax richer than name, type and where it sits.
