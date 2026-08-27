# Sequences: iteration, grouping, sorting and aggregation

Status: **decided 2026-08-25, in full — all eight decisions ruled (§15), two against the
draft's recommendation: `xsl:key` is built rather than deferred (§8), and the
`is-first`/`is-last` conditions return alongside the engine variables (§4.3).** Written
2026-08-25 against [14-xslt-coverage-matrix.md](14-xslt-coverage-matrix.md)'s first gap
family — *whole-input state before output*: `xsl:sort`, `for-each-group group-by`, `xsl:key`,
`sum`/`count`/`avg`/`min`/`max`, `distinct-values`. The matrix called it "one architectural
question wearing five names". This document answers it, and the answer is smaller than the
framing suggested. Implementation tracked as E23. The rulings at the end are the user's.

Scope note: the goal is not XSLT parity for its own sake. It is everything a transformation
engine needs to usefully do for log transformation and data extraction — which is why
`for-each`, aggregation, grouping and keys are in.

## 1. The finding that reframes the gap

The matrix said this family "needs whole-input state before first output byte" and treated
that as an architectural question. Reading the engine says otherwise: **the whole-input state
already exists, and the ordering already works.** What is missing is iteration.

Three facts, each checkable in the source:

1. **Stores already accumulate across a level's whole run.** `exec/Store.java` is a sparse
   list indexed by match number, and `Executor.processMatch` clears a template's capture
   stores only when `matchCount == 1` — once per *level invocation*, not once per match. At
   the root, `Executor.stream()` keeps its counters "for the whole stream, as DS3's do", so a
   root template's captures accumulate one entry per record across the entire input. The
   accumulator the matrix said did not exist is the store, and it has been there since the
   port.

2. **Post-level ordering already works.** In `Executor.body()`, `CompiledOp.Apply` calls
   `level()` synchronously, so every instruction *after* an `apply-templates` in the same body
   already runs after the dispatched level has finished. The document template is the special
   case: `Executor.run()` splits its body at the apply into a prologue written once at the
   start and an epilogue written once at the end of the stream. "Emit nothing per record, then
   emit a summary at the end" is already a shape the engine can express.

3. **A non-recursive apply does not push a scope.** `Executor.apply()` pushes and pops only
   for the recursive form (`template_ref`, or a `__rec_` mode). Every capture name is
   registered in the global scope by `run()` before matching starts. So captures made by a
   child level survive into the parent's post-apply instructions, which is exactly where a
   summary would be written.

So `<cats>` … records … `</cats>` with a grouped summary in between is already an expressible
*shape*. What cannot be written is the middle: **nothing iterates a store.**

The second finding follows from the first and is the one that dissolves the expensive-sounding
half of the family. Because iteration would be over **indices into stores that are already in
memory**, `xsl:sort` sorts an `int[]`. There is no output reorder buffer to build and no
serialisation to defer. Sorting is not an architectural problem here; it is an array.

## 2. The store is the sequence type

**No new value type.** The temptation is to add a `Seq` variant to `TypedValue` so that
`tokenize`, `distinct-values` and `current-group()` all return "a sequence". That would be a
third thing between the model and the values it holds, which is the shape D35 exists to
refuse, and it would leave the engine with two ways to hold many values — a store and a
sequence — that mean the same thing.

A store is already a sequence: an ordered, sparse, indexed collection of `TypedValue`. Every
piece of this design reads it as one.

A store carries values under one of two indexing disciplines, and both already exist:

| Discipline | Index means | Sparse? | Produced by |
|---|---|---|---|
| **capture-indexed** | the match number that produced the value | yes — a capture that did not match leaves a hole, deliberately (E19) | template captures |
| **dense** | position, from 1 | no | `append` (§9), `distinct-values`, the `__group` sequence |

Iteration is defined over both by one rule: **populated entries, in ascending index order.**
Holes are skipped; index is not position. The two are separately available inside a body
(§4) because they answer different questions — the index reaches sibling data, the position
is what `position()` means.

**Parallel stores share indices, and that is the whole of `current-group()`.** Two captures
of the same template at match *i* belong to the same record. So a reference to `$id` at the
index currently being iterated in `$cat` reads the id of the record whose category this is.
Grouping in this engine groups the *index set*, not the values — which is how a byte engine
with no tree gets the relational reach that `current-group()` provides in XSLT.

## 3. What is genuinely missing

Four pieces, in dependency order:

- **A. Iteration** — `for-each`, with `sort` and a `for-each-group` variant (§§4–7).
- **B. Lifetime control** — `append`, and a sequence declaration, so a value captured in a
  nested level can be hoisted into an accumulation that outlives it (§9).
- **C. Folds and keys** — `count`, `sum`, `avg`, `min`, `max`, `distinct-values`, and
  `key`/`for-each-key` (§8).
- **D. Positional vocabulary** — `position` and `last`, which **E21 deleted** on 2026-08-21
  as vocabulary nothing set. E21's own words: *"delete until a case needs for-each positional
  index and count semantics"*. This is that case, and the shape recorded there is what comes
  back (§4.3) — both halves: the engine variables and the conditions.

Nothing else. No new value type, no reorder buffer, no execution-ordering change, no third
layer.

## 4. `for-each`

### 4.1 The model

```java
/** Iterate a sequence, running a body once per populated entry. */
record ForEach(String select,            // the sequence's name, not a reference
               String as,                // binds the item's value, or null
               List<Sort> sort,          // sort keys, empty for store order
               List<OutputNode> body) implements OutputNode { }

/** One sort key. */
record Sort(RefExpression by, Order order, As as) { }   // as: 17 §8's typed read, or null
```

**`select` is a name, not a `RefExpression`.** This is deliberate and it is the design's
smallest load-bearing decision. A `RefExpression` resolves to *one value* by construction —
that is what `RefExpression.MatchIndex` is for, and what `Refs.resolve` returns. Making a ref
sometimes mean "all of them" would put a second reading into the one type every instruction
already shares. A sequence instruction names its sequence; a compile-time check has nothing
to do because there is nothing to get wrong.

Where a computed sequence is wanted, it is composed: `for-each` over the source, `append` the
computed value to a derived sequence, then fold or iterate that (§9). Composition over special
cases.

### 4.2 Wire format

```json
{ "for-each": {
    "select": "cat",
    "as": "c",
    "body": [
      { "text": "<c>" },
      { "value-of": { "parts": [ { "capture": { "var_id": "c", "group": 0 } } ] } },
      { "text": "</c>" }
    ]
} }
```

Externally tagged and kebab-cased like every other output node, read by `ProjectJson` with a
`checkFields(body, "for-each", "select", "as", "sort", "body")` guard.

### 4.3 What an iteration binds

Per entry, before the body runs:

| Name | Meaning |
|---|---|
| the `as` name, if given | the item's value |
| `__index` | the item's **store index** — what reaches parallel stores |
| `__position` | 1-based position **within this iteration**, after sorting |
| `__last` | how many entries this iteration will run, known before the first |

`__index` and `__position` differ whenever the store is sparse or sorted, which is most of the
time worth iterating. `__last` being known up front is what makes a last-item test cheap and
correct — and is the fix for the limit `adjacent_groups` found at amplified scale, where the
challenger had already emitted a group's open tag before it could know the group was empty.

The three engine names join `__match_count` and `__match_idx` in the reserved `__`-prefixed
namespace.

**The `is-first` and `is-last` conditions return with them (ruled 2026-08-25, against the
draft's recommendation).** E21 deleted them because nothing set the flags they read; a
`for-each` now genuinely does — `is-first` is `__position == 1`, `is-last` is
`__position == __last` — so the conditions are restored as the direct spellings of the two
questions iteration is most often asked, per the shape E21's entry recorded for exactly this
day. Outside an iteration nothing sets `__position`, and the conditions are false there —
which is E21's original hazard, so the compiler warns when either condition appears in a body
with no enclosing `for-each` or `for-each-group`. The `equals`-on-a-counter idiom stays valid
and `adjacent_groups` keeps proving it; the conditions are the authorable form, not a
replacement.

Reaching a parallel store uses the existing machinery unchanged —
`match_index.var_ref` already means "read the index out of another variable at run time":

```json
{ "value-of": { "parts": [ { "capture": {
    "var_id": "id", "group": 0, "match_index": { "var_ref": "__index" } } } ] } }
```

**A bare `$id` inside a for-each still means "the latest value of `id`", not "the value at the
current index."** Rebinding bare references would be convenient and is refused: it would make
the meaning of a reference depend on how far away its enclosing instruction is, which is the
kind of action-at-a-distance E19 already cost a day to find once. The index is written down.
(Decision 3.)

### 4.4 Execution

`for-each` is an output-side instruction. It consumes no input, moves no cursor, and can
appear anywhere a body can — including inside a match template's body, after an
`apply-templates` whose child level filled the store it iterates.

It runs in a pushed scope so that the `as` binding and the three engine names do not outlive
it, and so a nested `for-each` shadows rather than overwrites them. The `MatchResult` and
`matchCount` in force are the enclosing body's, unchanged: a reference to a match group inside
a `for-each` means the enclosing match's group, exactly as it does outside. In the document
template's epilogue there is no current match, and `MatchResult.empty()` is what a group
reference resolves against — as it already does for every other epilogue instruction.

Values read from a store are not part of the input, so content handed onward from inside a
`for-each` is `UNLOCATABLE` for instrumentation, exactly as `apply()` already marks content
resolved from a variable.

## 5. Sorting

`sort` is a list of keys on `for-each`. Each key's `by` is a `RefExpression` evaluated once
per entry with `__index` bound — so it can read the item, a parallel store, or a literal-and-
capture concatenation.

- **Stable**, and ties fall back to ascending store index, so equal keys keep data order.
- `order`: `ascending` (default) | `descending`.
- `as`: `string` | `number` | `date` — the same explicit cast every typed read in the engine
  uses (17's ruling replaced the draft's `data_type` with it). An uncast key orders by the
  values' string forms, the one total cast. Comparison semantics — including a value that
  fails its cast going absent and sorting last in both directions — are defined once in
  [17-value-computation.md §8](17-value-computation.md) and shared with `min`/`max` rather
  than reinvented here.

Mechanically: build `int[]` of the populated indices, evaluate one key array per sort key,
`Arrays.sort` with a comparator, iterate. The body sees the sorted order via `__position`
while `__index` still points at the right record. No output is buffered at any point.

## 6. Grouping

```java
record ForEachGroup(String select,           // the sequence whose indices are grouped
                    RefExpression groupBy,   // the key, per item; null = the item's own value
                    List<Sort> sort,         // sort the groups, not the members
                    List<OutputNode> body) implements OutputNode { }
```

Groups form in **order of first appearance** — XSLT's rule for `for-each-group`, and the one
a log summary wants. A `LinkedHashMap<String, int[]>` built in one pass is the whole
implementation.

Per group, before the body runs:

| Name | Meaning |
|---|---|
| `__group_key` | the grouping key |
| `__group` | a **dense sequence of the member store indices**, in ascending order |
| `__group_size` | how many members — known before the open tag is written |

`__group` being a sequence of *indices* is what makes members reachable with no new
vocabulary: a nested `for-each` over `__group` binds each index as a value, and
`match_index.var_ref` turns that value into a lookup.

Grouping by a *different* store than the one selected is the `group_by` ref's job:
`select: "id"` with a `group_by` reading `$cat[$__index]` groups records by category while
iterating the id sequence's index set. Both spellings reach the same place; the default
(`group_by` absent, key = the item's own value) is the common one.

`group-adjacent`, `group-starting-with` and `group-ending-with` stay **expressible** by
dispatch, as the matrix has them — adjacency is dispatch's native gait and does not need a
buffer. Native grouping does, however, close `adjacent_groups`' found limit for anyone who
prefers to write it this way, because `__group_size` is known before emission.

## 7. Worked example: the `keys_grouping` wall

`xmlbench/cases/keys_grouping/` has an `input.xml` and a `transform.xsl` and, deliberately, no
`challenger.project.json` — it is the executable wall the suite trips the day this lands. The
stylesheet:

```xml
<xsl:for-each-group select="order" group-by="@cat">
  <cat name="{current-grouping-key()}" count="{count(current-group())}">
    <xsl:for-each select="current-group()"><o><xsl:value-of select="@id"/></o></xsl:for-each>
  </cat>
</xsl:for-each-group>
```

The challenger, in full shape:

- **Document template.** Prologue writes `<cats>`; `apply-templates` runs the orders level;
  epilogue does the grouping and writes `</cats>`.
- **Order template** (root mode). Matches one `<order …/>` line, captures `id` and `cat`,
  **emits nothing**. Its two stores accumulate one entry per order, at the same indices.
- **Epilogue:**

```
for-each-group select="cat"                     # groups 1,3 / 2,5 / 4 by value
    text        "<cat name=\""
    value-of    $__group_key
    text        "\" count=\""
    value-of    $__group_size
    text        "\">"
    for-each select="__group" as="i"            # i = 1, then 3
        text     "<o>"
        value-of $id[ $i ]                      # match_index: { var_ref: "i" }
        text     "</o>"
    text        "</cat>"
text "</cats>"
```

Line for line against the XSLT, with `current-grouping-key()` → `__group_key`,
`count(current-group())` → `__group_size`, and `current-group()` → `__group` plus an indexed
read. Nothing in it needs a tree, an expression language or a reorder buffer.

## 8. Folds and keys

Five aggregates and one sequence-producer, each an instruction taking a sequence **name** and
either writing its result or binding it — the same `name` convention every transform already
uses:

| Instruction | Result | Notes |
|---|---|---|
| `count` | `Int` | populated entries, holes not counted |
| `sum` | `Int` or `Real` | see [17 §11](17-value-computation.md) for promotion and overflow |
| `avg` | `Real` | absent for an empty sequence, not zero |
| `min`, `max` | as input | `as` selects the comparison, uncast = string forms (17 §8) |
| `distinct-values` | binds a **dense sequence** | first appearance order, keeping the first index |

`avg` of nothing is absent rather than zero because absent is the engine's existing word for
"there was no value" (`Refs`: *empty is absent*), and a zero would be a number that looks like
an answer.

Non-numeric entries under `sum`/`avg` follow 17's absent-propagation rule rather than a rule
invented here.

**`xsl:key` / `key()`: built (ruled 2026-08-25, against the draft's recommendation to
defer).** The index is the same `Map<String, int[]>` that `for-each-group` builds; what `key`
adds is *random access* to it — read the members for one known key without iterating the
groups. Two instructions, on the same shapes as everything above:

```json
{ "key":      { "name": "by_user", "select": "id",
                "group_by": { "parts": [ { "capture": { "var_id": "user", "group": 0 } } ] } } }
{ "key-get":  { "key": "by_user",
                "select": { "parts": [ { "capture": { "var_id": "target", "group": 0 } } ] },
                "name": "hits" } }
```

- **`key`** declares and builds the index over a sequence, with `group_by` evaluated per entry
  exactly as `for-each-group`'s is (default: the item's own value). It is an instruction, not
  a project-level declaration, so it runs where its inputs are ready — typically the epilogue —
  and its cost is visible where it is paid. Key names live in their own namespace; a `key`
  and a sequence may share a name without colliding.
- **`key-get`** resolves a lookup value and binds the matching members — a **dense sequence of
  store indices**, the same shape as `__group`, so a nested `for-each` plus
  `match_index.var_ref` reads the records with no vocabulary beyond §4's. A key with no such
  entry binds an empty sequence, over which iteration runs zero times and `count` is 0 — the
  same non-answer XSLT's `key()` gives.

Since no stylesheet in the repository's 97 uses `xsl:key`, the proving case is authored:
`keys_grouping`'s stylesheet grows a genuine `xsl:key`/`key()` use (its name finally earned),
or a sibling `keys_lookup` case is added — implementer's choice, recorded in the catalogue.

The memory contract treats a key's index as what it is — an accumulation: its entry count is
subject to `max_sequence_entries` (§10) like any sequence, and building one under a chunked
root trips §10's refusal identically.

## 9. `append`, and the lifetime problem

Everything above assumes the values are in a store that is still populated when the iteration
runs. For a **root-level** capture that is already true. For a capture made in a **nested**
level it is not, and this is the one place the existing machinery genuinely falls short.

A nested level is a fresh `level()` call per parent match, so its templates' counters restart
at 1, and `processMatch`'s first-match clearing wipes their stores. A field captured inside a
record is therefore cleared at the start of every record. That clearing is correct — it is
E19's fix, and without it record two reads record one's values — so the answer is not to
weaken it but to give an author a way to say *keep this one*:

```json
{ "sequence": { "name": "cats" } }
{ "append":   { "name": "cats",
                "select": { "parts": [ { "capture": { "var_id": "cat", "group": 0 } } ] } } }
```

- **`sequence`** declares the name in the current scope and empties it. Declaring is required:
  it is what makes the lifetime visible at the point an author can see it, and it gives the
  compiler somewhere to stand.
- **`append`** evaluates a reference and adds the value at the next free index of the named
  sequence, searching outward through scopes exactly as `VarRegistry.entry` already does. An
  absent value appends nothing — it does not append a hole, because in a dense sequence a hole
  means nothing.

Two compile-time checks, both of the kind the engine already prefers to make before a run
starts:

1. **`append` to an undeclared name is an error**, naming the instruction. Otherwise it would
   silently create the sequence in the innermost scope and lose it on the way out — a
   configuration that appears to work and produces nothing.
2. **A `sequence` name that collides with any template's capture name is an error.** Capture
   names are registered globally by `run()`, so a collision would let a template's first-match
   clearing empty an accumulation mid-run.

XSLT's equivalent of `append` is `<xsl:variable select="for $x in … return …"/>` — a sequence
expression. The engine has no expression language and refuses one by design (D35, and
[17 §4](17-value-computation.md)). Appending is the declarative spelling of the same intent,
and it is the spelling a streaming engine can actually execute: one value at a time, as the
input goes past.

## 10. The memory contract — the one real cost

This is the part of the family that is genuinely architectural, and it should be stated
plainly rather than discovered in production.

The engine's contract today is that **memory is bounded by `buffer_size`** — the sliding
window is the whole of the working set, which is what makes an input of unbounded length
processable. An accumulation across the whole input breaks that contract *by definition*: a
group-by cannot emit its first byte until the last record has been seen, whatever engine runs
it. Saxon has the same property; it just has more memory to lose.

So the contract becomes conditional, and the condition is authored:

- A configuration that declares no `sequence` and iterates no store keeps today's bound
  exactly. Pay for what you use.
- A configuration that accumulates is bounded by what it accumulates, and says so.

**`max_sequence_entries` on `SourceConfig`**, defaulting to 100,000 entries per sequence,
alongside `buffer_size`. Exceeding it is **`FATAL`**, naming the sequence and the limit.

Fatal, not a warning-and-truncate, and the reason is worth writing down: a truncated
aggregation is not a partial answer, it is a **wrong** answer that looks right. `<cat
count="100000"/>` where the truth is 143,000 is indistinguishable from a correct result at the
point of reading. The engine's usual instinct — *one bad record in a million is a message, not
a failure* — is about a record the run can carry on without. An aggregate silently missing its
tail is the opposite: the run carries on and lies. (Decision 5.)

**One hazard to rule on.** Accumulation across the whole input is well defined on the paths
that keep their counters for the whole stream: the sliding-window path (`STRICT`, `LAX`,
`LEXER` at the root) and whole-buffer runs. A root dispatched `CLASSIFY` or `ANY` is read
chunk-at-a-time by repeated `level()` calls, so counters reset and stores clear **per chunk** —
an accumulation there would silently summarise the last chunk. Proposal: a run-time `FATAL` on
the first `append` or sequence iteration under a chunked root, saying exactly that. Cheap to
detect, impossible to notice otherwise. (Decision 7.)

## 11. What this does not solve

Named so the matrix can be updated honestly when it lands:

- **`result-document` / multiple sinks** — untouched; still D10/E15, still walled by
  `dual_output`.
- **Upward and sideways axes** — still out of scope. Parallel-store indexing reaches *sibling
  data of the same record*, which is what `current-group()` needs; it is not an ancestor axis
  and does not become one.
- **Shallow `xsl:copy`, `strip-space`, serializer configuration, `generate-id`, `document()`** —
  unchanged refusals, all resting on "there is no tree".
- **Tunnel parameters** — unchanged gap.
- **Value computation** — every function in [17](17-value-computation.md). `sum` needs 17's
  promotion rules; `sort` and `min`/`max` need its comparison spine. **17 lands first**, or
  this design lands with those two points open.

## 12. Compilation and performance

Per D35, the new instructions compile like every other: `OutputNode.ForEach` →
`CompiledOp.ForEach` with its body compiled once, its sort keys compiled to `CompiledRef`, and
its select resolved to a name at compile time. Nothing is looked up per iteration that could
have been looked up once.

Costs, stated as expectations to be measured rather than claims (D21, and
[06-performance-plan.md](../stroom-shapeshifter-regex/design/06-performance-plan.md)'s method notes):

- A configuration that uses none of this should measure **unchanged**. The instructions are new
  `CompiledOp` variants in a `switch` that is already a jump table; nothing on the existing
  path moves. This is the first thing to check, and a regression here is a defect, not a
  trade-off.
- `for-each` per entry is a scope push, three store writes and a body call. The scope push is
  the suspect; if it measures, the three engine names can live in fields of the iteration
  frame instead of the registry.
- Sorting is `Arrays.sort` over an `int[]` plus one key array per sort key. It should be
  invisible beside the matching it summarises.
- Grouping is one `LinkedHashMap` pass.

The comparison to want is against Saxon on the same job — the `CaseCatalogueBenchmark` rows
that come free once `keys_grouping` has a challenger. Saxon must build a tree to group;
shapeshifter groups an index array over values it already captured. If the hypothesis behind
this engine holds anywhere, it holds here, and if it does not, that is the finding.

## 13. Proving cases

The catalogue's contract is unchanged: a resource directory of `input.xml`, `transform.xsl`
and `challenger.project.json`, byte-identical to Saxon run live, engine messages clean.

| Case | Proves | Status |
|---|---|---|
| `keys_grouping` | `for-each-group`, `__group`/`__group_key`/`__group_size`, nested iteration | **exists as a wall** — promote |
| `sequence_basics` | `sequence`/`append`, `for-each`, `as`, `__position`/`__last`, `is-first`/`is-last` | new |
| `aggregate` | `count`/`sum`/`avg`/`min`/`max`/`distinct-values` in one summary block | new |
| `sort` | multi-key sort, descending, numeric, stability under ties | new |
| `keys_lookup` | `key`/`key-get` against a genuine `xsl:key`/`key()` stylesheet, including a lookup that finds nothing | new (authored — no production stylesheet to adapt; §8) |
| `adjacent_groups` | unchanged; the trailing-empty-group note in [14](14-xslt-coverage-matrix.md) gets its second, native answer | exists ✓ |

Each amplified case must also pass `CaseAmplifierTest`, which is the licence for a benchmark
row — and amplification is the honest stress here, because it is where an accumulation's size
stops being hypothetical.

## 14. Implementation phases

Each phase ends green with its case passing, in the ledger's usual ratchet.

*Refreshed 2026-08-27, after [17-value-computation.md](17-value-computation.md) shipped and
E26/E27 landed. Nothing here is re-ruled; the phases absorb what changed underneath them
while this design sat unbuilt.*

### 14.1 What the ground looks like now

Four things are true today that were not when §15's decisions were taken, and three of them
would stop phase 1 on its first afternoon.

**The unknown-reference refusal will reject this design's own vocabulary.** Design/17 §10
made a read of a name nothing writes a compile-time *error*, and `BodyScan` seeds exactly
two engine names: `__match_count` and `__match_idx`. This design introduces six more —
`__index`, `__position`, `__last`, `__group`, `__group_key`, `__group_size` — and §7's
worked `keys_grouping` challenger reads `$id[$__index]`. **Until those are seeded, this
design's own acceptance test cannot compile.** Phase 1's first commit, before any iteration
runs.

**Sequence names are a second kind of name, and the refusal has to be taught the
difference.** `for-each select="cat"` *names* a sequence rather than referencing one (§4.1's
ruled decision 2), `append` writes one, `sequence` declares one. §9's two compile-time checks
— append-to-undeclared is an error, a sequence name colliding with a capture name is an error
— are unchanged, but where they live is: **inside the single `BodyScan` walk**, not as new
passes. E27 merged three walks into one the week before this starts, and adding two more
would undo it immediately.

**The compiler will not let an instruction be forgotten.** `BodyScan.visit` is exhaustive
over the sealed `OutputNode` hierarchy with no `default` arm (E27), so every instruction this
design adds is a **compile error until its reads, writes and lints are considered**. That is
the property E27 exists for and it is welcome here — but it means each phase's model change
arrives with a compiler-enforced obligation attached, which is worth expecting rather than
discovering.

**The folds start fast.** `sum`, `avg`, `min` and `max` read numbers out of stores through
`TypedValue`'s casts, which E26 made non-throwing. They inherit that, rather than needing
their own E26 when a case eventually feeds them a malformed field.

### 14.2 E27's measurement pins to a commit, not to the calendar

*Corrected 2026-08-27, the day it was written: this section first said the quiet-box run had
to happen **before** phase 1, on the grounds that every phase below adds checks to the walk
E27 repaired and would confound its recovery. The constraint is real; the ordering is not.
A benchmark pins to a **commit** — design/17's own phase 0 established exactly this, and used
it four times — so the run checks out `4160bf7c1c` (E27 audited, E23 unstarted) in a
worktree whenever the box is quiet, however much has landed on top by then.*

**Still owed, unblocking nothing:** compare `csv_header` and `progressive` compile at
`4160bf7c1c` against `2026-08-27-0738-37825da20d-engine`, and record under E27. If they have
not recovered, E27's entry says where to reopen from.

### 14.3 The phases

1. **Iteration.** `__index`, `__position` and `__last` seeded in `BodyScan`, and the
   sequence namespace with §9's two checks, both inside the existing walk. Then `for-each`,
   `sequence`, `append`, the `as` binding, the restored `is-first`/`is-last` conditions with
   their outside-iteration lint, and the wire format both ways.
   Case: `sequence_basics`.

   *Three names, not §14.1's six: a name seeded before anything sets it compiles and then
   reads absent for ever, which is precisely E21's dead-vocabulary trap. `__group`,
   `__group_key` and `__group_size` are seeded by phase 4, in the commit that makes them
   mean something.*
2. **Folds and sequence producers.** The five aggregates and `distinct-values` — and
   **`tokenize` binding a dense sequence**, which design/17 §16.4 ruled and left blocked on
   exactly this phase, its one open sweep item. Ordering comes from 17 §8's spine, which
   shipped.
   Cases: `aggregate`, and `string_functions` extended to prove `tokenize`'s new shape.
3. **Sorting.** `sort` on `for-each`, keyed by the `as` cast (§5) — not the draft's
   `data_type`, which 17's ruling replaced.
   Case: `sort`.
4. **Grouping.** `for-each-group`, and `__group`/`__group_key`/`__group_size` — seeded here,
   where they are also set.
   Case: `keys_grouping` promoted from wall to passing — **the acceptance test for this whole
   design**, and the case §7 is written against.
5. **Keys.** `key`/`key-get` on the grouping index machinery (§8, ruled built rather than
   deferred). Case: `keys_lookup`, authored — no production stylesheet supplies one.
6. **The contract.** `max_sequence_entries`, the fatal on overflow, the chunked-root refusal.
7. **Close.** The A/B against the phase-0 measurement, then
   [14-xslt-coverage-matrix.md](14-xslt-coverage-matrix.md): every row of §2 and the
   aggregation and sequence rows of §§3–4 move out of *gap*, and **§5's first gap family
   closes** — the last one, since 17 closed the second and D10/E15 own the third. E23
   resolved in ISSUES with pointers here.

### 14.4 Mechanics worth knowing before the first case

- A case is registered in **two** places — `CaseCorpus.UNITS` and
  `CaseCatalogueBenchmark`'s `@Param` — because JMH needs a compile-time constant.
  `CaseCatalogueBenchmarkParamsTest` fails by name when they disagree, so this is enforced
  rather than remembered. It exists because four cases were once proved correct and then
  measured by nothing.
- Promoting `keys_grouping` means moving it from `CaseCatalogueTest`'s `WALLS` to its
  `CASES`. The wall test fails the day a challenger appears without that move, which is the
  point of it.
- **Author new challengers at version 5**, where `substring` is 1-based (17 §7).
- Catalogue rows added by this design have **no *before*** and never can, exactly as 17's
  four did: the baseline engine cannot run instructions that do not exist in it. Their value
  is the within-run Saxon ratio, and the benchmarks README's comparability-breaks section is
  where that gets said.

## 15. Decisions — ruled 2026-08-25

All eight ruled by the user; 4 and 6 against the draft's recommendation, and the sections
above are rewritten to the rulings.

1. **The store is the sequence type** — no `Seq` variant on `TypedValue`, no second
   collection. **Ruled: yes**, as recommended.
2. **A sequence instruction's `select` is a name, not a `RefExpression`** — a ref keeps
   meaning exactly one value. **Ruled: yes**, as recommended.
3. **Bare references do not rebind inside a `for-each`** — the current index is written down
   (`match_index.var_ref: "__index"`), not implied. **Ruled: yes**, as recommended, at a cost
   in verbosity that an authoring tool can hide and a debugging session cannot.
4. **E21 is reversed in full** — `__position` and `__last` return as engine variables **and**
   the `is-first`/`is-last` conditions return with them (§4.3), set by iteration, with a
   compile-time warning outside one. **Ruled: restore both** — the draft recommended variables
   only; the ruling takes the direct spellings too, this being exactly the case E21's entry
   said to wait for.
5. **Exceeding `max_sequence_entries` is FATAL**, not truncate-with-a-warning, because a
   truncated aggregate is a wrong answer rather than a partial one. Default 100,000 per
   sequence. **Ruled: yes — per-sequence**, not the per-run alternative.
6. **`xsl:key` / `key()` is built** (§8) — `key`/`key-get` on the grouping index, with an
   authored proving case since no production stylesheet supplies one. **Ruled: build** — the
   draft recommended deferral.
7. **A chunked root (`CLASSIFY`/`ANY`, not whole-buffer) refuses accumulation at run time**,
   fatally, rather than silently summarising the last chunk. **Ruled: yes**, as recommended.
8. **17 lands first.** `sum`'s promotion and `sort`'s ordering are defined there; building
   them here would define them twice. 16's phase 1 depends on neither and can start alongside.
   **Ruled: yes**, as recommended.
