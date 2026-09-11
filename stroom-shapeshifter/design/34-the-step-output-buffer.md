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

## 5. How it is gated

*This design's own prior is that the corpus will not catch its mistakes.* Three array conversions
on 2026-09-11 — `Store`'s doubling, the sinks' namespace walk, `Splitter`'s escape growth — each
had a growth or edge path the golden fixtures never touched, found only by sabotage. A buffer with
mark-and-truncate is the same shape and deserves the same suspicion.

So, before the benchmark:

1. **A test per combinator** that a nested sequence's outputs are not visible after it returns,
   and that a *failed* alternative's outputs are not visible to the one that follows. Neither is
   covered today.
2. **A growth test** — more outputs in one match than the buffer starts with, several doublings
   over.
3. **Sabotage each**: remove the truncation on the failure path, and on the success path, and
   confirm a test fails for each.

Only then a reading, and `progressive` and `progressive_text` are the only rows that can move.

## 6. What would make this a mistake

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
