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

*This section said, when it was written, that `CompiledStep` and `CompiledSteps` would stay in
`match` and that `Steps` — 32 of this design's 89 arms — would therefore be interpreted from a
package that could not import the context. That constraint is gone too*, and by the same
discovery: `match` was holding primitives, vocabulary, a pass and an interpreter at once. The
vocabulary is in `graph`, `StepCompiler` is in `compile`, and **`Steps` is in `exec` beside the
other interpreters**. All 89 arms are now in packages that can see a context declared in `graph`.

So this design has no structural obstacle left. What decides it is §3's measurement and nothing
else, which is the position it should have been in from the start.

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

**Phase 2 — ask the compiler** (§3). *Done 2026-09-10; §8 and §9 are the record.* It did not end
the design. The chain is not flattened — it is not a chain the JIT ever sees, because at 26 arms
it is a 389-byte call the JIT refuses to inline — and the change measures +5.64% on the row that
executes the most instructions, against a flat control. `Body` is converted; `Conditions` was
converted first as §3's prototype and is a smaller version of the same shape.

**Phase 3 — the boundary** (§4), only if phase 2 says the change is worth making. An interface both
packages can see, or one package. That ruling is the owner's and is the largest structural decision
this engine has faced since design 27.

**Phase 4 — one interpreter at a time**, and *not* largest last: §9's ruling reorders it.
`Conditions` and `Body` are done. `CompiledRefs` is next — six arms, and the busiest dispatch in
the engine on five rows of seven — then `Level.match`, which runs 569,199 times per operation on
`win_sec_strict`. `Steps` is last: 32 arms is a fact about the vocabulary, and it runs on two
rows out of eleven, using four kinds on one and two on the other.

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

## 8. The count, taken first

Per 256 KiB operation, by temporary instrumentation at the five dispatch sites, reverted
afterwards. §3 required this before anything was built, and it changes what the design is about.

| workload | `Body` ops | `CompiledRefs` | `Steps` | `Level.match` | conditions | total |
|---|---|---|---|---|---|---|
| `win_sec_strict` | 11,834 | 29,312 | 0 | 569,199 | 5,568 | 625,160 |
| `progressive` | 52,431 | 209,724 | 104,862 | 52,431 | 0 | 419,448 |
| `csv_header` | 54,705 | 170,186 | 0 | 35,473 | 6,078 | 278,598 |
| `apache_httpd` | 56,402 | 141,602 | 0 | 4,237 | 30,864 | 233,729 |
| `log_sessions` | 111,211 | 78,717 | 0 | 4,409 | 4,396 | 202,934 |
| `element_storm` | 150,060 | 36,600 | 0 | 3,673 | 0 | 190,333 |
| `ausearch` | 20,617 | 84,702 | 0 | 32,109 | 2,730 | 140,158 |

**Three things follow, and two of them were not what §1 assumed.**

*The reference resolver is the busiest dispatch in the engine on five of seven rows*, at six
arms. `Body` at 26 arms leads on only two. So the arm count and the dispatch volume are close to
inversely related, and a design that ranks the interpreters by arms — as §6 phase 4 does,
"largest last" — is ranking by the wrong number.

*The step interpreter, 32 of the 89 arms, runs on two rows out of eleven*, and on those two it
uses four kinds and two kinds. Its 32 arms are a compile-time fact about the vocabulary and never
a run-time fact about a workload.

*Every site is far more monomorphic at run time than its arm count suggests.* `element_storm`
executes four op kinds, `apache_httpd` seven, `log_sessions` thirteen — out of 26. This is the
number the dispatch question actually turns on, and §3 did not think to ask for it.

## 9. Phase 2 — what the compiler said

### The type switch is a linear scan, and that is from the JDK's source rather than inferred

`SwitchBootstraps.generateTypeSwitchSkeleton` (JDK 25) emits, for a pattern switch over a sealed
type: `Objects.checkIndex`, a null check, a `tableswitch` **on the restart index**, and then a
fall-through chain of `aload; instanceof C_i; ifeq next` — one test per arm, in the order the
`case` labels appear. The `tableswitch` §2 read out of the bytecode is not the type dispatch: it
exists so a guarded pattern that fails can re-enter the chain at the next arm. For an ordinary
call it jumps to arm 0 and the chain runs from there.

So the comparison was never "a jump table against a virtual call". It is **a linear scan against
a virtual call**, and the scan's cost depends on where in the switch the arm happens to be
written. Counted against the corpus, with `Body.body`'s arm order as it stood:

| workload | the cost of the order | tests per operation |
|---|---|---|
| `log_sessions` | `Append` is 17th, `Transform` 13th, `Tokenize` 20th, `ParseDate` 25th | ≈ 750,000 |
| `element_storm` | `Element` is 8th, `Attribute` 9th | ≈ 651,000 |
| `apache_httpd` | `Transform` is 13th | ≈ 322,000 |

An engine that removes a map lookup because the compiler knew the key (design 30) should not have
a dispatch whose cost is decided by the order someone typed the arms in.

### The switch is a call the JIT will not inline, and it poisons what it contains

`-XX:+PrintInlining`, `apache_httpd`, one fork. Three findings, all in the same run:

- **`Body$$TypeSwitch::typeSwitch` is 389 bytes and never inlines** — "failed to inline: hot
  method too big" inside `Body::body`'s own hot compilation. Every op executed calls it. The
  smaller ones do inline: `Level`'s is 69 bytes, `CompiledRefs`' 109, `Conditions`' 165 and
  inlined when hot. The cliff is `FreqInlineSize` at 325 bytes, which lands at roughly twenty
  arms — so `Steps` at 32 is past it too, and `Body` at 26 is the case that was measured.
- **The interpreters are themselves far past inlining**: `Body::body` **1,596 bytes**,
  `Conditions::evaluate` 621, `CompiledRefs::resolveValue` 327. The last is two bytes over.
- **The giant method spoils the inlining of its own contents.** `CompiledRefs::write` is 304
  bytes, comfortably inlinable, and fails inside `Body::body` with "already compiled into a big
  method". This is the finding that matters most, because it is a cost the switch imposes on
  code that is not the switch.

### The dispatch shapes, priced on their own

A sealed vocabulary of 32 kinds, 1,024 nodes drawn from a chosen number of them, four
dispatches over the same nodes. Nanoseconds per dispatch, three forks each:

| kinds | order | switch | interface | opcode via `kind()` | opcode via `int[]` |
|---|---|---|---|---|---|
| 1 | first | 1.717 | **0.364** | 0.835 | 1.585 |
| 2 | first | 1.955 | **0.369** | 0.917 | 1.593 |
| 2 | last | 3.222 | **0.377** | 0.944 | 1.603 |
| 4 | first | 2.146 | 4.996 | 8.330 | **1.617** |
| 8 | first | 2.704 | 5.253 | 8.713 | **3.566** |
| 26 | first | 3.664 | **1.467** | 13.594 | 1.893 |
| 26 | last | 4.173 | **1.680** | 13.461 | 1.920 |
| 32 | first | 4.153 | **1.522** | 14.101 | 1.858 |

**The switch grows with both arm count and arm position**, 1.72 ns to 4.15, and at two kinds
moving the hot arm from first to last costs 65%. That is the chain, measured, and it agrees with
what the JDK generates.

**At 26 arms the interface call is about 2.5× cheaper than the switch.** That is §3's second
question answered.

**And there is a hole in the middle that the design has to carry rather than round off.** At four
and eight kinds the interface call is *worse* than the switch and worse than it is at 26. The
working explanation — moderate polymorphism gets guarded inlining with a type check that
mispredicts, where 26 receivers make C2 give up and emit a plain itable call — is a hypothesis
and is not verified here. It matters because **§8's count puts every real workload in that
band**: four kinds on `element_storm`, seven on `apache_httpd`, thirteen on `log_sessions`. The
micro's headline is not the regime the engine runs in, so it does not decide this. The in-situ
measurement does.

**The opcode variants are closed.** Asking the node for its opcode is catastrophic — it puts a
megamorphic call in front of the switch. Holding opcodes in an `int[]` beside the ops is
respectable and never best at high arity, and it would cost the compiler a parallel array to
build and keep in step with the ops it indexes.

### The change, built and measured in place

The micro could not decide it — §8's count puts every workload in the band where the micro is
worst, so the only measurement that means anything is the engine's own. `Body` was converted:
`CompiledOp.exec(Ctx)`, `Ctx` declared in `graph` and implemented by `Body` in `exec`, the 26-arm
switch replaced by `for (op : ops) op.exec(this)`, and the eight parameters that were threaded
through every arm became the machine's registers, saved and restored by the entry so that a
nested dispatch cannot leave the body around it reading someone else's state.

*Six interleaved rounds against `ebc10ff1f5`, order alternated within each round, live tree
against a baseline worktree.*

| workload | median | min | max | agreed | body ops per operation | kinds used |
|---|---|---|---|---|---|---|
| `log_sessions` | **+5.64%** | +3.60 | +6.82 | **6/6 faster** | 111,211 | 13 |
| `element_storm` | +1.56% | −1.00 | +2.60 | 5/6 faster | 150,060 | 4 |
| `apache_httpd` | −1.10% | −2.60 | +0.11 | 4/6 slower | 56,402 | 7 |
| `win_sec_strict` | +0.06% | −0.94 | +0.93 | 3/6 — the control | 11,834 | 8 |

**`log_sessions` is the result.** Every round faster, and its slowest round is outside the
control's whole spread. It is the row whose hot instructions sat deepest in the chain — `Append`
17th, `Tokenize` 20th, `ParseDate` 25th — which is what the JDK's generated scan predicts and
what nothing else here predicts.

**`apache_httpd` is not a result.** Four rounds of six lean slow and the median is −1.10%, but the
range crosses zero and the size is the control's own spread, well inside the ±3.3% envelope. It
is recorded as *possibly slightly slower, not distinguishable from flat*, and it is the row the
micro would have picked: seven receivers, and shallow arms, so it had least to gain from losing
the scan.

**And the inlining changed in the direction the design argued it would.** `PrintInlining` on
`apache_httpd`, before and after:

| | before | after |
|---|---|---|
| `Body::body` | 1,596 bytes, never inlined anywhere | **130 bytes**, "inline (hot)" |
| the dispatch | `Body$$TypeSwitch` 389 bytes, "hot method too big" | `CompiledOp::exec`, "virtual call" |
| the loop | — | `Body::run`, 37 bytes, "inline (hot)" |
| `Body::emit` | "already compiled into a big method" | 50 bytes, **"inline (hot)"** |

The last row is the one to keep. `emit` is 50 bytes and was failing to inline **because of the
method it was in**, not because of anything about itself. Dissolving the switch dissolved that,
and it is a benefit that has nothing to do with dispatch cost — which is why the change can be
worth making on rows where the dispatch itself is a wash.

### The ruling

*The code this phase built was rolled back the same day, and the design continues as
[33](33-the-compiled-program.md). What follows is what phase 2 concluded before that; §10 is why
it did not survive contact with the owner, and none of the measurement is retracted.*

**Phase 2 does not end the design; it licenses phase 4, with its order corrected.** §6 said
"largest last" and meant arm count. §8 says arm count is nearly the opposite of dispatch volume,
and the A/B says what the gain tracks is neither: it is **how deep the hot arms sit in the
scan**, times how often they run. So the order is `CompiledRefs` next — six arms, and the busiest
dispatch in the engine on five rows of seven — and `Steps` last, not because it is largest but
because it runs on two rows out of eleven.

**What would have ended the design, and did not:** §7's first bullet, "if phase 2 says the switch
is faster". It does not. It says the switch is faster on one row by an amount that row's own
noise covers, and slower on the row that executes the most instructions by an amount that six
rounds agree on.

**What phase 2 leaves open**, and should not be rounded off: the micro's 4-to-8-kind hole is
unexplained, and it is the band the engine lives in. If it is guarded inlining mispredicting,
then every site converted from here will behave like `apache_httpd` rather than like
`log_sessions` unless its arms are deep, and the ordering above is exactly wrong. That is a
question for the next `PrintInlining` probe, and it is worth answering before `CompiledRefs`.

## 10. Rolled back, and superseded

**2026-09-10, the owner's ruling: "I don't like what we've ended up with, the big switch was
better… is there another way we could optimise a switch like this with maybe an array of ops and
an op index?"** The `Body` and `Conditions` conversions were reverted in full — nothing had been
committed — and the question moved to [design 33](33-the-compiled-program.md).

**The measurement is not retracted and nothing here is withdrawn.** The type switch is still a
linear `instanceof` chain (§9), still a 389-byte call the JIT will not inline at 26 arms, and the
converted `Body` still measured +5.64% on `log_sessions` over six agreeing rounds. What changed is
the answer to "and therefore what", and on three grounds worth writing down rather than smoothing
over:

*The proposal was never the only way to remove the chain.* §9 priced four dispatch shapes and then
reasoned about two. The op-index `tableswitch` beats the type switch at every arm count sampled
but one, keeps a single interpreter over sealed records, requires no `Ctx` interface, and is
**already how this codebase's own regex library dispatches** — `Plan.op[pc]` and its six runners.
Design 33 §3. Leading with the visitor, when the survey that would have surfaced the precedent had
not yet been done, was the error.

*The result was mixed in exactly the band the engine occupies.* `apache_httpd` leant slower, and
the micro says 4-to-8 receivers is where an interface call is worst. A change whose benefit
depends on how deep the hot arms happen to sit is a change that has replaced one accident with
another.

*And phase 2's A/B could not separate what it changed.* It replaced the dispatch and dissolved a
1,596-byte method in one step. `Body::emit` was failing to inline because of the method it sat in,
not because of itself — so an unknown share of the +5.64% belongs to splitting the method, which
is available to the switch too. Design 33 phase 1 is the control that separates them, and it
should have been phase 2's own first step.

**What carries forward**: §8's counts, §9's JDK finding and inlining evidence, and the corrected
phase ordering — by dispatch volume rather than arm count. Design 33 §4 also corrects §1's
arithmetic: the five interpreters hold 71 arms, not 89.
