# Design 35 — One binding, declared in a scope, with a type

*Opened 2026-09-11 after E49 was built, measured and reverted. Reshaped 2026-09-12 on the owner's
model: lexical declaration, two scopes, var types, counters left alone.*

**Status: open. Nothing is built. §9 lists what has to be ruled first.**

## 1. Captures and variables are one thing

Everything that binds a name writes through `vars.store(name)` or `vars.put(name, store)` into one
`VarRegistry`, one slot space, one `Store`. The compiler's own refusal says so: *"no capture,
variable, transform bind or parameter has that name."* Four binders, one namespace, and a reference
cannot tell which wrote what it reads.

| binder | bound when | value from |
|---|---|---|
| capture | before the body runs, so a later template can read it | the match |
| variable | during the body, in order | running instructions |
| parameter, call argument | at the call | the caller |
| transform bind (`as`) | at the instruction | the transform's result |

A capture is a variable whose value comes from the match and is bound early. That is the whole
difference, and it is a difference of *binding time*, not of kind.

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
   error matches how the engine already treats an unwritable name. This decides how much every
   existing native fixture has to change.
2. **Does the DS3 migration declare everything global, or only what DS3's semantics require?**
   Global is faithful and blunt; narrower is more useful and needs proof per case.
3. **Are the four var types all needed at once**, or is scalar-plus-list enough to close the open
   issues, with map and set following?
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
- **No golden fixture may change.** If one does, either the rule is wrong or the divergence is
  deliberate and ruled — E19 is the precedent for recording that.
- **`win_sec_strict` is the performance gate**, for §8's push-per-execution.

## 11. What would make this a mistake

- **If declaration turns out to be a burden authors cannot carry.** DS3 authors never declared
  anything; they got DS3's lifetime. A declaration every configuration must write, and most get
  wrong, is worse than a default that occasionally surprises.
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
