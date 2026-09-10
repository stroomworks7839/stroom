# Design 33 — A compiled body is a program

*Proposed 2026-09-10, from design 31 phase 2's measurement and from the owner's reading of it:
"the big switch was better… is there another way we could optimise a switch like this with maybe
an array of ops and an op index?" That is the shape this design takes, and the reason it is a
design rather than a preference is that the same question was measured four ways first.*

*Engine only. It touches no golden and no configuration.*

## 1. What is being proposed

**A compiled body becomes an `int[]` of opcodes beside a `CompiledOp[]` of operands**, dispatched
by an `int` switch:

```java
for (int pc = 0; pc < codes.length; pc++) {
    switch (codes[pc]) {
        case OP_TEXT -> out.write(((CompiledOp.Text) ops[pc]).value());
        ...
        default -> throw new IllegalStateException();
    }
}
```

One interpreter, over one sealed vocabulary, exactly as design 27 ruling 2 requires. Nothing
moves onto the nodes, no visitor interface appears, the records stay records. What changes is the
*selector*: an `int` the compiler wrote, rather than the node's identity discovered at run time.

## 2. Why the type switch is the thing being replaced, and not a baseline to return to

Design 31 §9 has the evidence; this is what it amounts to.

**A pattern switch over a sealed type is an `instanceof` chain.** `SwitchBootstraps
.generateTypeSwitchSkeleton` (JDK 25) emits `aload; instanceof C_i; ifeq next` per arm, in case
order. The `tableswitch` visible in the bytecode selects on the *restart index*, not on the type
— it exists so a guarded pattern that fails can re-enter the chain. So the cost is linear in the
number of arms and depends on the order the arms were written in.

**At 26 arms the chain is a call the JIT will not inline.** `Body$$TypeSwitch::typeSwitch` is 389
bytes, past `FreqInlineSize`, and `PrintInlining` reports "hot method too big" inside `Body::body`
itself. Smaller ones do inline: `Level`'s is 69 bytes, `CompiledRefs`' 109, `Conditions`' 165.
**The size depends on how many arms are declared, not on how many kinds occur** — so a 26-arm
switch pays the un-inlinable call even on a workload that only ever executes four kinds, and no
amount of arm reordering removes it.

Priced on their own, per dispatch, over 1,024 nodes:

| kinds at the site | type switch | **op index + `tableswitch`** | interface call |
|---|---|---|---|
| 1 | 1.717 | 1.585 | 0.364 |
| 4 | 2.146 | **1.617** | 4.996 |
| 8 | 2.704 | 3.566 | 5.253 |
| 26 | 3.664 | **1.893** | 1.467 |
| 32 | 4.153 | **1.858** | 1.522 |

The op index beats the type switch at every arm count sampled except eight, where both micro
readings are anomalous and neither is explained (design 31 §9 records the hole rather than
rounding it off). It has no cliff, because there is no generated method to outgrow.

## 3. The precedent, which is one module away

**The regex library already does this.** `Plan` holds `int[] op` with parallel `int[] a`, `b`, `c`
operand arrays and side tables (`literals`, `classes`, `charClasses`, `branchTables`), and
`PlanRunner`, `Nfa`, `Backtracker`, `FancyBacktracker`, `Closures` and `PikeVm` all dispatch on
`switch (op[pc])`. Seven interpreters, six to fourteen arms each, all of them the shape proposed
here.

That is the elegance argument, and it is not borrowed from VM folklore: **the engine would be
dispatching the way its own regex library already dispatches.** It also means the pattern has a
worked answer in this codebase for the questions that come next — how operands travel beside the
opcodes, how side tables are indexed, how a program is built by a compiler pass.

## 4. The survey: every switch in the codebase, classified

135 switches. 68 are over a sealed type — 609 arms. The rest select on an enum, an `int` or a
`String`, and are **not** this design's business: an enum switch is already a `tableswitch` on an
ordinal with no type test, which is what this design is trying to obtain.

**Run-time, per record — this design's subject.** Ranked by dispatches per 256 KiB operation
(design 31 §8's count), not by arms:

| site | arms | busiest row | dispatches per operation |
|---|---|---|---|
| `CompiledRefs.write` / `.resolveValue` | 6 + 6 | `progressive` | 209,724 |
| `Level.match` | 6 | `win_sec_strict` | 569,199 |
| `Body.body` | 26 | `element_storm` | 150,060 |
| `Steps.step` | 23 | `progressive` | 104,862 |
| `Conditions.evaluate` | 10 | `apache_httpd` | 30,864 |
| `Predicates.matches` | 7 | with `take-while` steps | unmeasured |
| `Comparisons` (`cast`, `compare`) | 8 + 2 | every comparison | unmeasured |
| `Level` capture sources | 3 | per capture bound | unmeasured |

*Design 31 §1's arithmetic was wrong and is corrected here*: `Steps` has **23** arms, not 32, and
`Level.match` has **6**, not 9 — those figures counted nested switches with their parents. The
five interpreters hold **71** arms, not 89.

**Compile-time, per compiled configuration.** Not this design's subject for dispatch cost, but
named because the benchmark has compile rows and they are measurable: `BodyCompiler` (54),
`ReferenceCheck` (28 + 10 + 5), `StepCompiler` (24), `StructureCheck` (12), `ConditionCompiler`
(11 + 4), `MatchCompiler` (9 + 7 + 5 + 4), `CaptureCompiler` (5), `TemplateUses` (5).

**Configuration parsing, per read.** `OutputJson` (54), `MatchJson` (24 + 9 + 7 + 2),
`ConditionJson` (10 + 4), `ReferenceJson` (5 + 2), `Ds3Migration` (7 + several).

**The regex library's own compile-time passes**: `Analysis` (eleven switches), `Normalise`,
`Reverse`, `NfaCompiler`, `PlanCompiler`, `NodeTree`, `Lowering`, `BytePattern`. Its *run-time*
interpreters already dispatch on `int` — see §3.

**Other dispatch shapes, for completeness.** There are exactly two `else if (… instanceof …)`
chains in the whole codebase, so the type switch is the only pattern of its kind at scale. And
one compiled node already carries its behaviour as data rather than as a case:
`CompiledOp.Transform` holds a `Function<List<TypedValue>, TypedValue>`, which is a megamorphic
call per execution — the opposite pattern, worth measuring under E46's heading rather than this
one.

## 5. Phasing, with B as the control that separates two effects

**Design 31's A/B changed two things at once and cannot separate them**, which is the honest
reason phase 1 exists. It replaced the dispatch *and* dissolved a 1,596-byte method into 26 small
ones. `PrintInlining` shows the second mattered on its own: `Body::emit` is 50 bytes and was
failing to inline with "already compiled into a big method" — blocked by the method it sat in,
not by anything about itself — and it inlined once the method was split. So the +5.64% measured
on `log_sessions` is of unknown composition.

**Phase 1 — B, the control.** Keep the type switch exactly as it is; extract each arm into a
method, so the arms become `case final CompiledOp.Text text -> text(text);`. A small, safe change
that dissolves the giant method and nothing else. Measure it against the same floor. Whatever it
gains is the *inlining* half, and it is available whichever dispatch wins.

*The prediction, written before the run, as this project's benchmark page requires.* Asking the
compiler first: `Body::body` goes from **1,596 bytes to 758** — halved, and still over
`FreqInlineSize` at 325, so it is still a method the JIT will not inline. `Body$$TypeSwitch` is
untouched at 389 bytes and still refuses to inline, and the count of "already compiled into a big
method" across a `log_sessions` run falls only from 62 to 57. So the mechanism §5 assumed —
lifting the budget exhaustion — is *not* what B delivers. What it can still deliver is different:
C2 inlines only the arms a workload actually executes (`text` at 9 bytes and `valueOf` at 17 both
inline hot), so the compiled hot path shrinks even though the bytecode does not.

**Predicted: a small gain on `log_sessions`, one to three points, and less than design 31 phase
2's +5.64%.** If B lands near zero, the +5.64% was dispatch and phase 3 should collect it. If B
lands near +5.64%, the dispatch was never the problem and phase 3 can be dropped — which is §7's
first bullet, and the outcome this design should be happiest to find.

### Phase 1's result, 2026-09-10: the prediction held and the design's premise did not

*Six interleaved rounds against `8b49ffd3ec`, order alternated within each round.*

| workload | median | range | agreed | body ops per operation |
|---|---|---|---|---|
| `log_sessions` | **+2.35%** | +0.76 to +3.18 | **6/6 faster** | 111,211 |
| `element_storm` | **−2.22%** | −5.17 to −0.71 | **6/6 slower** | 150,060 |
| `apache_httpd` | +0.15% | −0.85 to +0.86 | 3/6 — flat | 56,402 |
| `win_sec_strict` | +0.11% | −0.78 to +1.27 | 4/6 — flat | 11,834 |

**The prediction was right about `log_sessions`** — one to three points, and less than design 31
phase 2's +5.64% — which attributes roughly 40% of that figure to the method split and leaves the
rest to the dispatch and the registers. **It said nothing about `element_storm`, which lost 2.22%
with every round agreeing.** Six rounds on either side of zero is not drift.

**Why, from `PrintInlining` rather than from reasoning.** `Body::element` is **66 bytes** — a
fifth of `FreqInlineSize` — and fails to inline with "already compiled into a big method". The
budget exhaustion this phase existed to remove did not lift; it **moved**, off `CompiledRefs
::write` and onto the extracted arms. `element_storm`'s two hot instructions are `Element` and
`Attribute`, so code that had been inline inside `body` became a call that often is not, and the
`structure()` lambda that escape analysis had been scalar-replacing now escapes — 76,860 times per
operation on that row.

**What that establishes, and it changes this design's shape.** `Body::body` is still 758 bytes
after extraction, and what keeps it there is not the dispatch: it is **26 arms each passing eight
arguments**. An `int` switch would be no smaller. So phase 3 cannot fix this either, and design 31
phase 2 was small — 130 bytes — for a reason this design had assigned to the wrong cause: not
because the ops carried their own dispatch, but because the eight parameters had become
**registers**, leaving each arm a one-argument call.

**A phase is therefore inserted, and it is the one that isolates the parameters.**

**Phase 1b — the registers.** Keep the type switch and keep `List<CompiledOp>`; collapse the eight
parameters into fields of `Body`, saved and restored by the entry as design 31 phase 2 did.

*The estimate written here first — "twenty-six one-argument calls is roughly 200 bytes, which is
under the threshold" — was wrong, and the check it prescribed is what caught it.* Built, the
entry `body()` is **130 bytes** and inlines; the loop `run()` is **591**, still over
`FreqInlineSize` and still "hot method too big". A 26-entry `tableswitch` table plus 26 casts and
calls does not fit under 325 however few arguments each call takes. Design 31 phase 2's loop was
37 bytes because it contained **no switch at all**, which is the part of that result this design
had not accounted for: some of what looked like the register change was the absence of the table.

`element` and `attribute` still fail inside `run` with "already compiled into a big method", once
each where phase 1 showed twice — the exhaustion halved rather than lifted.

**1b was built, gated at 1,157 tests, and deliberately not benchmarked.** The check that was
written to decide whether the reading was worth taking said no, and the owner stopped the run on
the same ground. That is the rule working rather than being skipped: half an hour of box time
would have produced a number whose mechanism had already been refuted, and the project has spent
enough evenings measuring things whose explanation arrived afterwards.

*What this establishes independently of the benchmark:* **no arrangement of a 26-arm switch is
inlinable**, whatever it switches on and however many arguments its arms take. Phase 3's `int`
dispatch will not change that either. If an inlinable interpreter loop is worth having, the only
shape that delivers one is a loop with no switch in it — which is what design 31 built and what
this design rejected on other grounds.

*Phase 1's own disposition is undecided and deliberately so.* It is +2.35 and −2.22 on the two
rows that execute the most instructions, which is not a change worth keeping for itself. It is
kept in the tree as phase 1b's base, because 1b's shrinkage only works on top of it, and the pair
must be measured together as well as apart.

### The structural refusal stops being a closure, 2026-09-10

*Found by phase 1's regression rather than looked for, and separable from the rest of this
design: it is an allocation question, not a dispatch one.*

`Body.structure(Runnable, kind, name)` wrapped every `startElement`, `endElement`,
`startAttribute`, `endAttribute` and `namespace` call so that the sink's structural refusal became
the run's last message naming the instruction that broke it. **That shape is right** — the sink
knows the rule, the body knows the instruction, and deferring means the message is built only on
the path that is refused. It is also free *when it inlines*, and `PrintInlining` showed
`structure` (52 bytes) inlining at five of its ten sites and failing at the other five.

It is now a `try`/`catch` at each call, with the message built by a `refused(kind, name, e)`
helper. Identical message, identical semantics, no capturing lambda.

**Measured before the change was kept, as the gate for it:** `element_storm` allocated
**42,376,495.8 B/op** and now allocates **38,921,390.2** — **−3,455,105 bytes per operation,
−8.2%**. That row writes 40,260 elements and 36,600 attributes, at two capturing sites each, so
153,720 lambdas per operation at about 22.5 bytes apiece. The estimate offered beforehand as a
*ceiling* — "at most 3.7 MB" — turned out to be the actual figure, which means escape analysis
was scalar-replacing approximately none of them.

**The catch is at each call rather than around the interpreter's loop, and that is not
stylistic.** A loop-level handler would enclose the nested body an element runs, so a *text*
placement the sink refuses three levels down would be caught by the enclosing element's handler
and reported as that element's fault. The version first proposed in conversation had exactly that
defect; catching at the call keeps the instruction named correctly, and leaves a refusal from any
other instruction to reach `Run`, which reports it without naming one — which is what it did
before.

**Phase 2 — the ops as an array.** `List<CompiledOp>` becomes `CompiledOp[]`, with the type
switch still in place. Measure against phase 1. This is the collection half, and it is separable
from the dispatch by construction — which is the point of doing it on its own.

**Phase 3 — C, the program.** The derived `int[]` of codes, and `switch (codes[pc])`, on `Body`
first. Measure against phase 2, so what is being measured is the dispatch and nothing else.

*Three phases for three effects, because design 31 phase 2 changed two at once and could not
attribute what it measured.* That is this design's whole procedural lesson from its predecessor,
and skipping a phase to save an evening would reproduce the mistake exactly.

**Phase 4 — the rest, ranked by §4's volumes**: `CompiledRefs` and `Level.match` before
`Body`-sized work, because they are the busiest and their switches are small enough to inline
today — which means they may *not* gain, and finding that out is the point.

**Phase 5 — the record**, and a ruling on whether the compile-time and JSON switches are worth
the same treatment. The prior is no: they run once per configuration, and 54 arms of `BodyCompiler`
cost nothing per record.

## 6. What has to be decided

**Two choices, and they are independent.** *What* is switched on — a type, or an ordinal — and
*where* the ordinal comes from. The ops are in an array in every variant; a body is already a
`List<CompiledOp>`, so "an array of ops" is not the alternative to the int switch, it is what
holds the operands in all of them.

*On the ordinal's type:* an `int` and an enum are both a `tableswitch`. An enum costs one further
dereference to reach the ordinal, and buys type safety and a readable case label. There is no
reason to prefer the bare `int` here — the regex library uses one because its opcodes are packed
beside three parallel `int` operand arrays and are read in the same breath as them.

*On where it lives:* a **field on the node** (`op.opcode`) is a `getfield` with no dispatch, and a
record cannot have one — records implicitly extend `java.lang.Record`, so there is no base class
to hold it, and a record's components are read back through accessors. A **virtual accessor**
(`op.kind()`) is not an option at all: it puts a megamorphic call in front of the switch, which is
what §2 is removing, and it measured worst of the four shapes. A `static final int CODE` per
record is not an option either — statics are not virtual, so it cannot be reached through
`CompiledOp`.

**Ruled 2026-09-10: an `int`, derived, in an array beside the ops — and the ops as an array too.**

```java
static final int OP_TEXT = 0;
static final int OP_VALUE_OF = 1;
...

static int codeOf(final CompiledOp op) {
    return switch (op) {              // a sealed switch expression: javac checks exhaustiveness
        case CompiledOp.Text ignored -> OP_TEXT;
        ...
    };
}
```

The type switch survives, runs **once per op at link time**, and the run dispatches on
`codes[pc]`. Three things follow, and the first two answer objections this document raised
against the array shape in earlier drafts:

*The codes cannot drift from the ops*, because they are computed from them rather than authored.

*The compile-time exhaustiveness check survives.* It was only ever a **hand-written** code that
lost it: `codeOf` is a switch expression over a sealed type, so a 27th kind fails to compile
there. An enum was considered for this and is not needed — a compile-time `int` constant is a
legal case label, so `case OP_TEXT ->` reads exactly as `case TEXT ->` would, without the
ordinal dereference an enum switch pays.

*The codes must be contiguous from zero.* javac emits a `tableswitch` for a dense set of case
constants and a `lookupswitch` — a binary search — for a sparse one. Assigning `OP_*` from zero
with no gaps is what makes the dispatch O(1), and it is an invariant worth a comment and a test
rather than an accident of how the constants were typed.

**And the ops travel as `CompiledOp[]`, not `List<CompiledOp>`.** This is a separate ruling with
its own evidence: `PrintInlining` on `apache_httpd` reports `java.util.List::size` failing to
inline at **81** sites with "no static binding", `List::get` at 68, `List::add` at 57,
`List::iterator` at 21 and `List::isEmpty` at 17. Those are interface calls the JIT cannot bind
because the receiver profile is polluted across `List` implementations engine-wide; some sites do
resolve to `ImmutableCollections$List12` or `ListItr` and inline, and many do not. An array
turns every one of them into an `aaload` or an `arraylength`, pairs naturally with `codes[pc]`,
and removes the iterator a body allocates per execution. It is not free to make: `List<CompiledOp>`
appears throughout the vocabulary — `If.then`, `When.body`, `Element.body`, `Choose.otherwise`
and the rest — and in the passes that build them.

*The alternative, if the boilerplate is judged worth the strictness:* `abstract sealed class
CompiledOp` with a `public final int opcode` set by each subclass's constructor. It cannot drift
either, needs no second array, and leaves the compiler's signatures alone — at the cost of the
vocabulary's records, about eight lines per kind, and ~70 kinds if this extends past `Body` to
`CompiledStep`, `CompiledRef`, `CompiledCondition` and `CompiledMatch`. What records are buying
was checked rather than assumed: the only `Set<CompiledOp.…>` is `Body.warnedNumeric` and it is
built on an `IdentityHashMap`, so record `equals` is deliberately unwanted; no deconstruction
pattern exists anywhere; no test compares a compiled op by value. Concision, and nothing else in
use.

**What is lost.** The compiler no longer checks exhaustiveness: a sealed type switch fails to
compile when a kind is added, and an `int` switch does not. The mitigation is a `default` that
throws and a test asserting every kind maps to an opcode — which is weaker than the compiler and
must be said out loud rather than discovered.

**What is gained beyond speed.** The order of the arms stops being a performance property. Design
30 removed a map lookup because the compiler knew the key; this removes a linear scan for the
same reason — the compiler knows which instruction it emitted.

## 7. What would make this a mistake

- **If phase 1 (B) collects most of the gain**, then the dispatch was never the problem, the
  giant method was, and the right answer is B alone — a change of a few dozen lines that keeps
  the sealed switch and its exhaustiveness check. This is the outcome the design should be
  happiest to find, and phase 1 is ordered first so that it can.
- **If phase 2 shows no movement over phase 1**, the same conclusion follows for the opcode
  array, and design 27 ruling 2 stands with a measurement behind it.
- **If the opcode and the node can drift apart** — an `ops[pc]` whose `codes[pc]` says something
  else — the design has traded a compiler-checked invariant for a silent wrong answer, which is
  worse than the cost it removes. neither §6 shape can do this — a derived
  code array is computed from the ops, and a field is set by the node's own constructor. The
  failure mode survives only if the codes are ever authored by hand, which is why §6 derives
  them.
