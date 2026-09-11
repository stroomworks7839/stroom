# Design 34 — The step interpreter's outputs are one buffer

*Proposed 2026-09-11, from design 33 §11's sweep, which ranked the step interpreter's three lists
first by volume among the engine's remaining run-time collections and then declined to convert
them, because this is a redesign of how step outputs are addressed rather than a type change.*

*Engine only. It touches no golden and no configuration — if it does, it is wrong.*

## 1. What is there now

`Steps.match` allocates a `List<TypedValue> outputs` per progressive match. Every nested
combinator — `Sequence`, `Choice`, `Optional`, `Repeat`, `Peek`, `Not` — calls `sequence(...)`,
which allocates a second list for its own outputs and calls `concat(enclosing, callerLocal)` to
present the pair as one:

```java
final List<TypedValue> prior = concat(enclosing, callerLocal);
int pos = 0;
final List<TypedValue> local = new ArrayList<>(steps.length);
```

On `progressive` that is 52,431 matches per operation, and a `Repeat` multiplies it by its
iteration count. `concat` copies both inputs whenever both are non-empty.

## 2. The addressing rule, stated exactly

A step reference is an index into the **concatenation** of everything produced so far:

```java
private static TypedValue at(final int index, final List<TypedValue> prior, final List<TypedValue> local) {
    if (index < prior.size()) {
        return prior.get(index);
    }
    final int offset = index - prior.size();
    return offset < local.size() ? local.get(offset) : null;
}
```

So an index counts step outputs in execution order, and the split into two lists is an
implementation detail the indexing has to undo on every read.

**And the rule is narrower than its javadoc says.** `sequence(...)` returns `Integer consumed` and
nothing else: the nested `local` is **discarded on return**. Inner steps see outer outputs; a step
*after* a nested combinator cannot index what that combinator's steps produced. The combinator
contributes exactly one output — the span it consumed — and its internals are private. That is a
coherent rule and it is not the one written down; §6 corrects the comment either way.

## 3. What is proposed

**One growable buffer per match, and a mark.**

```java
TypedValue[] outputs;   // the match's outputs, in execution order
int count;              // how many are live
```

- A step reference reads `outputs[index]`. No split, no arithmetic, no `concat`.
- `sequence(...)` records `mark = count` on entry, appends as it runs, and **truncates to
  `mark` before returning**.
- The caller appends the combinator's single span output afterwards, exactly as it does today.

**Truncation is what a fresh list was doing.** Today each nested call gets its own `local`, which
is dropped on return — visible to its own steps, invisible to everyone else. A buffer with
mark-and-truncate has the same visibility, without the allocation: the inner steps see
`0 … count`, and on return everything above `mark` is gone.

*The four cases that have to come out right, and why they do:*

| combinator | today | with a mark |
|---|---|---|
| `Sequence`, `Optional` | inner `local` dropped; one span output appended | truncate to `mark`; caller appends the span |
| `Repeat` | a fresh `local` per iteration, so iteration *n+1* cannot see *n* | truncate per iteration, same isolation |
| `Choice` | a losing alternative's `local` is dropped | truncate after each failed alternative |
| `Peek`, `Not` | outputs never escape — they match without contributing | truncate; the caller appends `NOTHING` |

**Failure is the case to get right.** Every exit from `sequence` must truncate, including the
early return when a step yields null. With separate lists that was automatic — the list simply
went out of scope. With one buffer it is a line of code, and forgetting it on one path leaks a
failed alternative's outputs into the index space of whatever runs next. That is the defect this
design can introduce, and §5 is how it is held.

## 4. What it removes

Per progressive match: one `ArrayList` for the match, one per nested combinator invocation, and
the `concat` copy whenever both sides are non-empty. `progressive` runs 52,431 matches and 104,862
steps per operation; `progressive_text` exercises the four text step kinds the binary row never
reaches.

It also removes the two-list arithmetic from `at`, which runs on **every** step reference — a
compare and a subtract per read, replaced by an array index.

## 5. The corpus does not execute the path this design changes

*Established 2026-09-11, before any work, by asking which step kinds the fixtures actually use.*

Five fixtures exercise progressive matching, all in the golden suite, and between them they cover
**fourteen of the twenty-four step kinds**:

| fixture | step kinds |
|---|---|
| `progressive_len_records` | `ReadVarint`, `TakeBytes` |
| `progressive_text_steps` | `Tag`, `TakeWhile`, `TakeUntil`, `Regex` |
| `progressive_embedded_codec` | `Decode`, `ReadVarint`, `TakeBytes` |
| `progressive_mixed_endian` | `ReadNumeric`, `Seek`, `Tell` |
| `progressive_varint_zigzag` | `ReadVarintZigZag` |

**Not one of them uses `Choice`, `Optional`, `Repeat`, `Sequence`, `Peek` or `Not`** — which are
exactly the six that call `sequence(...)`, allocate the nested list, and would carry the
mark-and-truncate. The corpus never calls `sequence` at all; the two benchmark rows do not either.

Three consequences, and they change what this design is:

*It cannot be trusted to the corpus.* §6's prior was that the fixtures would miss the growth path,
which understates it: they do not run the nested path in any form. Every test of the combinators
has to be written before the change, not after it.

*It cannot be measured as things stand.* `progressive` and `progressive_text` are the only rows
that reach the step interpreter, and neither nests. A reading would show the flat-sequence saving
— one list per match, and the split arithmetic out of `at` — and nothing of the part this design
is actually about.

*And it means the nested combinators are, today, untested engine behaviour* — not merely
uncovered against a change, but never executed by any test at all. That is worth fixing whether
or not this design is ever built, and it comes first.

> **Corrected 2026-09-11, during the audit — the sentence above is wrong.** It is true of the
> *fixtures*, which is what the table measures, and I extrapolated it to every test. `StepsTest`
> already held **five combinator tests** — `choiceTakesTheFirstAlternativeThatMatches`,
> `optionalAndRepeatAreGreedyAndDoNotGiveBack`, `lookaheadMatchesWithoutConsuming`,
> `nestedStepsSeeEveryOutputProducedSoFarAtAnyDepth` and `sequenceIsOneStepFromTheOutside` —
> which build `MatchStep` objects directly rather than going through configuration. They predate
> this design and were never modified by it. The first two of §5's three consequences stand: the
> corpus does not run this path and cannot measure it. The third does not. §8 records what that
> cost, because the sabotage shows those five doing most of the catching.

## 6. How it is gated

*This design's own prior is that the corpus will not catch its mistakes — §5 is why, and it is
worse than a prior: the corpus does not run the code at all.* Three array conversions
on 2026-09-11 — `Store`'s doubling, the sinks' namespace walk, `Splitter`'s escape growth — each
had a growth or edge path the golden fixtures never touched, found only by sabotage. A buffer with
mark-and-truncate is the same shape and deserves the same suspicion.

So, before the benchmark:

1. **A test per combinator** that a nested sequence's outputs are not visible after it returns,
   and that a *failed* alternative's outputs are not visible to the one that follows. Neither is
   covered today. *(Corrected during the audit: the first of the two was already covered, by
   `StepsTest.sequenceIsOneStepFromTheOutside` — see §5's correction. The second was not, and it
   is the one the sabotage in §8 shows nothing else catching.)*
2. **A growth test** — more outputs in one match than the buffer starts with, several doublings
   over.
3. **Sabotage each**: remove the truncation on the failure path, and on the success path, and
   confirm a test fails for each.

Only then a reading, and `progressive` and `progressive_text` are the only rows that can move.

## 7. What would make this a mistake

- **If the truncation cannot be made obviously correct** — if it has to appear on more than the
  two exits from `sequence` — then the buffer is carrying a discipline the type system used to
  carry, and the separate lists were buying something after all.
- **If a combinator turns out to need its inner outputs after it returns**, the whole model is
  wrong and §2's narrow rule is a bug rather than a design. Nothing in the corpus suggests it,
  but §2's javadoc claims the broader rule, so somebody once thought so.
- **If it measures flat.** `progressive` is step-bound, so this is the one remaining conversion
  with real volume behind it; if removing three allocations per match and the split arithmetic
  from every reference does nothing, then allocation churn is not what these rows are spending
  their time on, and the remaining items in design 33 §11 should be dropped rather than worked
  through.

## 8. What was built, and what the gate caught

*Built 2026-09-11.* `Outputs` is a `TypedValue[]` with a count, doubling from `max(4, steps.length)`.
`step(...)` and `sequence(...)` take one buffer where they took `prior` and `local`; `concat(...)`
and the two-list `at(index, prior, local)` are gone, and with them the subtract-against-the-first-
list arithmetic every step reference used to do. `sequence` takes a mark on entry and truncates to
it on both exits. §7's first condition is met: the truncation is on the two exits and nowhere else.

**The combinators were tested first**, as §5 required — seventeen tests over `Choice`, `Optional`,
`Repeat`, `Sequence`, `Peek` and `Not`, committed before the buffer existed, driving the
combinators through configuration rather than by building `MatchStep` objects. §5's premise for
writing them was partly wrong — see the correction there — and the sabotage below says how much
they actually added.

Then §6.3's sabotage, and it is the reason §6.3 is written down:

Each sabotage was run against the whole engine suite, with every test in place:

| sabotage | failing | pre-existing `StepsTest` | written for this design |
|---|---|---|---|
| no truncation on the **success** exit | 6 | 5 | 1 |
| growth allocates without copying the old values | 2 | 1 | 1 |
| growth removed entirely | 2 | 1 | 1 |
| no truncation on the **failure** exit | **1** | **0** | 1 |

**Two things in that table, and they point opposite ways.**

*The five combinator tests that were already there did most of the work.* Three of the four
sabotages are caught by `StepsTest` alone, which §5 did not expect because §5 had wrongly
concluded there were no such tests. The seventeen written for this design overlap them
substantially — a JSON-configured path over the same vocabulary the old ones reach directly — and
had §5 been right about what existed, far fewer would have been worth writing.

*And the one sabotage they all miss is the defect this design names for itself.* Before the test
written for it, removing the truncation from the **failure** exit broke **nothing** — not the
corpus, not the five, not the other sixteen new ones. Every one of them either fails a
combinator's first alternative before it has produced an output, or does not read an index
afterwards. It took a test built backwards from the defect: a first alternative that produces one
output and *then* fails, a second that produces the value the next step reads by index, chosen so
the leaked output and the correct one differ in type. The growth test needed the same care — its
first version read an index written *after* the last doubling and passed happily against a growth
that copied nothing.

So the lesson is not "write tests first", and it is not "there were no tests" — there were, and
they were good ones. It is that **a test written to cover a feature is not a test of the defect a
change to that feature will introduce.** Coverage of the combinators was already decent; what it
could not do was anticipate mark-and-truncate, because nothing in the old design had a mark to
forget. Only sabotage found that, and only after the defect was named.

*Unmeasured.* §6's reading is still owed, and §7's third condition still stands — `progressive` and
`progressive_text` are the only rows that can move.
