# Design 31 — Instructions that run themselves

*Proposed 2026-09-10. Engine only. It touches no golden and no configuration: every change here
replaces a dispatch with a call.*

*Raised as: "the big switch makes no sense as the op should know what to do by adding a single
`exec` interface — can we not just create a single object to carry the params?" That proposal
answers the objection this has been refused on three times, and two things found while checking it
say the objection was resting on a model of the code that is not what the code compiles to.*

## 1. What is being proposed

`Body.body` is a 26-arm switch over `CompiledOp`. `Steps` has 32 arms, `CompiledRefs` 12,
`Conditions` 10, `Level` 9 — about **eighty-nine arms across five interpreters**, each one a
`switch` over a sealed type. The proposal is that each compiled node carry the work instead, and
that the run state a node needs travel as **one context object** rather than as eight parameters.

## 2. What it has been refused on, and what has changed

*Three refusals, all mine or inherited, and each is now weaker than when it was written.*

**Design 27 ruling 2** — "one interpreter over sealed records, not nodes that run themselves" —
refused it because it "would put run state into every op's signature or into the op, which is the
per-run mirror D35 refuted". **The context object separates those two.** D35 refused a per-run
*mirror of the graph*: a parallel tree of per-run node objects shadowing the compiled one. A
context passed as a parameter is not a mirror — the graph stays shared and immutable, exactly one
context exists per run, and no compiled node holds anything. So the D35 half of ruling 2 does not
reach this shape, and the signature half is the thing the proposal fixes.

**Design 29 §4 and design 30 §4** both leant on the same performance premise: an interface call
over many implementations is megamorphic and gives up inlining, so the switch is worth keeping.
**That premise has never been measured.** Point 10 was recorded as its control — one
implementation taken out of `Transform.function`'s call site on "the workload that runs 209
replaces per record" — and on 2026-09-10 that turned out to be wrong: `apache_httpd` holds 244
`translate` ops and **two** `replace` ops. The control exercised the mechanism twice per record
and measured, correctly, nothing. So the rulings' *conclusion* has support and their *mechanism*
has none.

**And the switch is not what the argument assumed it was.** Compiled, `Body.body`'s dispatch is:

```
invokedynamic #0:typeSwitch:(LCompiledOp;I)I
tableswitch { // 0 to 25
```

The `tableswitch` is real but it is a jump on an index that has just been *computed* by a JDK
method-handle chain, which arrives at the index by testing the value's type. The comparison was
never "a jump table against a virtual call". It is "a type-test chain, then a jump, against a
virtual call" — and which of those wins is a question about HotSpot rather than about taste.

## 3. What is owed before anything is built

**Ask the compiler, not the benchmark.** Design 30 §7 carries this rule from E43: for a question
about dispatch and inlining, `-XX:+PrintInlining` answers in one run what three rounds can still
get wrong. Two questions, in order:

1. **What does `SwitchBootstraps.typeSwitch` cost at 26 arms**, and does HotSpot flatten it? This
   is the number the last three rulings assumed and none measured.
2. **What does a 26-implementation interface call cost at the same site?** Megamorphic dispatch is
   real; the question is whether it is worse than what is there.

A prototype of *one* interpreter is the cheap way to ask both, and `Conditions` is the one to use:
ten arms, its own file, and a path already measured at 0.4% to 0.7% so nothing rides on the answer.

**And count first**, per the same rule. Ops executed per operation, by kind, on the benchmark
corpus — because a dispatch that runs 250,000 times per operation and one that runs 600 times are
different questions, and design 30 phase 4 and phase 6 were both mispredicted by reasoning from a
number whose scope did not match the change.

## 4. The obstacle, which was removed before this design was built

*Written as the blocker; resolved 2026-09-10, the same day, by a change that turned out to be a
file move.*

`CompiledOp` was in `compile` beside the compiler, the run state is in `exec`, and putting
`run(context)` on an op reinstated the cycle design 27 ruling 8 spent a phase removing — because
the context is `VarRegistry`, `Output`, `Level` and `Body`, all of which are `exec`. This section
costed the way out as an interface both packages could see, and said honestly that it would be
about twenty methods existing to preserve a boundary, which is a boundary paying for itself in
ceremony.

**That was the wrong boundary.** `compile` held the passes *and* the vocabulary they build, and
splitting those two — `engine.graph` for the vocabulary, `engine.compile` for the passes — was
almost free, because it described what was already true: no compiled class named a compiler class
and `exec` imported none of the passes. Design 27 §2.5.1 records it.

So the interface is no longer ceremony. **The context is declared in `graph`, `exec` implements
it, and `graph` names neither the compiler nor the interpreter.** An instruction can be given a
`run` method without any package learning about a package it should not know. What remains to
decide is the interface's *shape*, which is a real design question rather than a workaround — and
§7's test still applies to it: every method on it should be called by more than one instruction,
or it has become the per-run mirror wearing a parameter list.

One thing the split did **not** put in `graph`: `CompiledStep` and `CompiledSteps` are compiled
vocabulary that stays in `match`, because `graph` reaches into `match` and the reverse edge is
ruling 8's cycle again. `Steps` is 32 of this design's 89 arms, so whichever way §4's interface is
shaped has to work for a package that cannot import it. **That is now the sharpest constraint on
the design, and it is a better one than the cycle was**, because it is about the vocabulary rather
than about where files sit.

## 5. `Switch.cases`, which prompted this and is a different problem

The map is the visible half and the smaller one:

```java
final String selected = textOf(value.select(), match, matchCount);
final List<CompiledOp> taken = value.cases().get(selected);
```

**The decode comes first.** `textOf` resolves the value and builds a `String` — an allocation and
a UTF-8 decode — and only then is the string hashed. That is the same shape as E45, where a
`matches` subject was decoded so a byte matcher could re-encode it, and the decode was the larger
waste and also a behaviour bug.

So a compiled `switch` should match the selected value's **bytes** against its cases: the cases
are authored literals, known at compile time, so they can be a length-bucketed comparison, a
sorted array, or a byte trie. None of them decodes, none of them hashes, and all of them keep the
authored order that decides ties. The map goes as a consequence rather than as the goal.

*This is separable from §1 and much smaller.* It should be done first, and on its own, so that
whatever the dispatch question turns out to be, it is not entangled with a value-decoding
question that has already been answered elsewhere in the engine.

## 6. Phasing

**Phase 1 — the compiled switch** (§5). Bytes against compiled cases, no decode and no hash. Its
own count first: `switch` executions per operation, which is currently unknown.

**Phase 2 — ask the compiler** (§3). `PrintInlining` over the interpreter as it stands, and over a
`Conditions` prototype. **This phase may end the design**, and should be allowed to: if the
type-test chain is flattened and a megamorphic call is not, the right answer is to write down that
the switch is correct and stop.

**Phase 3 — the boundary** (§4), only if phase 2 says the change is worth making. An interface both
packages can see, or one package. That ruling is the owner's and is the largest structural decision
this engine has faced since design 27.

**Phase 4 — one interpreter at a time**, largest last. `Steps` at 32 arms is the biggest and the
hottest; `Body` at 26 is the most tangled. `Conditions` is already the prototype.

## 7. What would make this a mistake

Written down first, so it is checkable rather than arguable afterwards.

- **If phase 2 says the switch is faster**, the design ends and design 27 ruling 2 stands with a
  measurement behind it at last, which is worth having on its own.
- **If the context object grows past the interpreter's real surface** — if it starts carrying
  things only one arm needs — it has become the per-run mirror D35 refused, wearing a parameter
  list. The test is whether every method on it is called by more than one instruction.
- **If the boundary interface has to expose `Body` or `Level` themselves** rather than operations,
  then the vocabulary and the run state were never separable and §4's alternative was the right
  answer all along.
