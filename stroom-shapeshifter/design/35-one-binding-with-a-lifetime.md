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

## 6. Counters stay as they are

`MATCH_COUNT`, `MATCH_INDEX`, `INDEX`, `POSITION`, `LAST`, `GROUP_KEY`, `GROUP`, `GROUP_SIZE`
remain inherent properties of the execution, read from `Frames` as they are now (design 30 phase
4). They can be **captured into a variable** where one is wanted — which is what gives the index
var the CSV list needs.

*This is deliberate and it is the main performance decision in the design.* `Frames` exists
because these are hot, and the 2026-09-11 reading says where the throughput lives: points 31, 32
and 35 are all run-state access and account for **+26.7 of `apache_httpd`'s +42.8**. Turning
counters into general variables would put them back on the name path that work just took them off.
Leaving them alone costs nothing and removes the risk.

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

## 8. What it costs, and where it must not

Most of this is compile-time: which slot each declaration owns, which slot each reference resolves
to, and which template clears which slots on exit. The run time left over is small and it is worth
being precise about, because design 33 has just shown that run-state access is where this engine's
throughput lives — points 31, 32 and 35 are **+26.7 of `apache_httpd`'s +42.8**.

- **Clear on exit** — a walk of the declaring template's slot array. Nothing for a template that
  declares none, which is most of them.
- **The lazy stack** — one branch on write to test whether a slot is direct or stacked. The
  promotion itself happens only on a second live declaration of the same site, which no fixture
  currently does at all.

**And it may take something away.** `VarRegistry` today pushes a scope for grouping, for-each,
variables, calls and recursive applies, with an undo log restoring on unwind. Some of those are
genuinely nested and bounded and will keep it; but if template lifetime is per-var rather than per
frame, it is worth asking which of the remaining pushes are still earning their place rather than
assuming the stack stays as it is.

## 9. What has to be ruled before anything is built

1. **What does a configuration that declares nothing get?** Implicit global is lenient; a compile
   error matches how the engine already treats an unwritable name. *Not* a question about how much
   the fixtures have to change — §0 says they may be rewritten freely — but about what a
   hand-written configuration should mean.
2. **Does the DS3 migration declare everything global, or only what DS3's semantics require?**
   Global is faithful and blunt; narrower is more useful and needs proof per case.
3. **Are the four var types all needed at once**, or is scalar-plus-list enough to close the open
   issues, with map and set following?
   *Note §5's map is what removes the data-name map, so it is not optional if that is wanted.*
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
