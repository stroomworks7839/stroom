# The regex ledger plan — every open row, phased and gated

As of 2026-09-03 the library is ahead of `java.util.regex` on 28 of 30 scoreboard variants,
at parity on two, and behind on one shape it does not optimise ([README](../README.md)). What
remains is not a deficit to close but a ledger to work through: rows spread across
[06](06-performance-plan.md) §1, §2, §5 and §6, the open entries in [ISSUES.md](../ISSUES.md),
two benchmark rows that read wrong, and the one cliff a ported configuration could still
fall off. This document gathers them into an order and gives each an exit. It plans nothing
new; every item below already exists somewhere with a measurement behind it.

The method is the fixed one. One change at a time, measured on this machine — every file
dated before 2026-09-02 is from hardware that no longer exists
([benchmarks/README](benchmarks/README.md)). Any edit touching a search or match path gates on
the buffer-CSV and per-match-datetime canaries, paired against the previous commit minutes
apart — same boot is not the same hour. Claims wait for numbers. Statuses move here, and the
rows in 06 and ISSUES.md move with them.

A fourth lesson, from the first overnight chain over this plan's work (2026-09-04): **a probe
set must carry every row the change's mechanism can reach, not only the rows that motivated
it.** The search split was probed on `anchored_miss` and weblog and won there; it touched every
tree search, and the tree's end-anchored `BOUNDED_MISS` lost 21.6% unmeasured. D39 was probed
on seven rows and cleared; it touched every scan-plan candidate position, and `line_miss` —
the seeding-gate fix's own row — fell from 22,909 to 3,074 unmeasured. Both were found by the
full set, bisected in the morning, and are remedied below; neither would have shipped past a
probe set drawn from the mechanism rather than the motivation. Before any commit under this
plan: name the paths the edit touches, and take one row from each.

A stopping rule, **proposed 2026-09-04** after a morning spent attributing three sub-8% rows to
code generation: a cost below ~5% on a synthetic row is attributed to a commit and recorded,
and not probed further unless a real workload, the pollution harness, or a full-set step shows
it moving. The rows that repaid chasing this week were the 5.5×, the 51×, the 8–12% and the
26–39%; the ones that did not were SPARSE, UNICODE and the coins. Ruling awaited.

Three lessons from the last two days set the order. **Probe, don't reason**: all three of the
encoding plan's costs were invisible to reading, to the stack profiler and to bytecode sizes,
and each fell to a bisect plus a single-edit variant. **Instruments first**: a row that reads
wrong misleads every decision after it. **Shape is a cost**: two of the three were C2
geometry — an inlining threshold and a lost value range — not algorithms.

---

## Phase 0 — The row of record *(tonight's slot; no code)* — **Run 2026-09-03/04; read 2026-09-04**

The paired full set `4aa6181941 → 98a0132736` on one boot: the pre-plan tree against the
encoding plan with its three fixes and D39. It does two jobs. It **acquits** the fixes across all 223
rows — the probe rows and canaries convicted the problems; only the full set can clear the
cure — and it becomes the **row of record on this machine**, the baseline every phase below is
paired against. Read it the way the last one was read: row-set diff first, JDK controls next,
per-fork spread before any single number.

**Read.** Four points on one boot, 18:28 to 04:26: `4aa6181941` → `98a0132736` → `7a4149d8b5`
→ `b81229148b`; row sets identical (223) at the first three and 287 at the last, the 64 polluted
rows one-sided by design; JDK controls at median +0.08%, +0.09%, −0.23% per step. Steps 2 and 3
acquit `RunLoop` and the harness across the full set. Step 1 did **not** acquit the fix batch:
scan-plan `line_miss` read 3,074 — the seeding-gate fix's own row, undone — and with it SPARSE
−8.1%, UNICODE −7.7%, the tree's `BOUNDED_MISS` −18.1%. A bisect of the four fix commits on
those rows gave three different answers: `line_miss` was D39 (remedied the same morning by
`ByteMatcher` keeping the bound as a field, `5bd6636429`, which also took buffer CSV's scan plan
+13.7%); `BOUNDED_MISS` was the search split's inlining topology (ruled kept by Jon, 2026-09-04 — an accepted cost in ISSUES.md);
SPARSE and UNICODE were inside the plan all along — phase 4 and phase 3 respectively — cleared
of every probe-reachable mechanism and deprioritised at 5.2× and parity. The chain's files are
the run of record; the README's charts render from its last point.

Also settled by the set: the tree's per-match datetime, which the 09-03 canary had read at −1.8%
against the edge of its own spread, moved +0.9% pre-plan → after-fixes with the JDK rows flat —
noise, as recorded, not a cost.

**Exit (reached):** a checked-in chain, the charts re-rendered, and the named rows that did not
hold are each attributed, remedied, ruled on or recorded — none left as a number without a cause.

## Phase 1 — Make the two rows read true *(the pollution workload; harness, then engine)* — **harness landed 2026-09-03; the rows read 2.1× and 2.1× polluted**

Buffer `NETWORK` reads 0.77× and `KEYVALUE` 0.99×. Both diagnoses are on record and they are
different: NETWORK **measures at parity fork-per-side** (156 vs 160 ns/record) and its 0.77×
"exists only inside JMH's harness conditions, which flatter the JDK's steady state" (06 §2);
KEYVALUE is described as the engine's honest weak case — many capture groups over very short
fields, dominated by per-group slot writes ([05 §2.1](05-engine-benchmarks.md)). Both were
diagnosed on the old CPU.

The harness condition that flatters the JDK has a name in 06 §5's own list of blind spots: a
JMH fork runs **one pattern per JVM**, which hands `java.util.regex` monomorphic type profiles
that no real pipeline — dozens of templates, hundreds of patterns — ever gives it, while these
engines are interpreter-shaped and pollution-immune. The missing "many-hundreds-of-patterns
pollution workload" and the NETWORK artifact are one item.

1. **Re-establish both diagnoses on this machine** before touching anything: the fork-per-side
   NETWORK pair, and a KEYVALUE probe with the group count varied (the same pattern with two,
   four and six groups) to see whether the cost scales with slot writes as 05 says.
2. **Build the polluted variant of `CorpusBenchmark`**: a `@Setup` that compiles and warms the
   whole pattern corpus through both `java.util.regex` and `BytePattern` before the measured
   loop — the per-match suite's `everything` already does this shape. Report it beside the
   clean row, not instead of it: the clean row is what a single hot template sees; the polluted
   row is what a pipeline sees. The scoreboard shows the polluted one, footnoted the other way.
3. **If KEYVALUE is real, price the slot writes.** Every search attempt does
   `Arrays.fill(slots, -1)` and every match writes 2×(groups+1) ints; for six groups over
   twelve-byte fields that is a measurable share. Candidates, each a single-edit probe: clear
   slots lazily on first capture rather than per attempt; skip the fill when the previous
   attempt never captured. Gate: KEYVALUE and NETWORK against the row of record, canaries
   paired.

**Landed (step 2), 2026-09-03.** `PollutedCorpusBenchmark`: `CorpusBenchmark`'s workloads and
methods after a `@Setup` that compiles and runs all 114 corpus patterns through both libraries
for 200 passes. Polluted against clean, same hour, same boot — the JDK rows fall **−53 to −62%**
(NETWORK 6,927 → 3,203; KEYVALUE 6,867 → 3,203; CSV 2,359 → 902), so the clean harness's
monomorphic flattery is real and large. The two rows read true: **NETWORK 0.78× → 2.13×,
KEYVALUE 1.01× → 2.06×**, CSV 2.94× → 5.78×. Neither row was ever the engine's weak case on a
pipeline's JVM. What the run also found, and 06 did not predict: our engines are **not**
pollution-immune — the scan plan lost 18% on CSV and the tree 26–39% across the three,
where "interpreter-shaped and pollution-immune" was the recorded belief. Less polluted than
the JDK by a wide margin, but polluted. Recorded as a new row in 06 §1 rather than chased:
the tree's `Node.match` dispatch is the obvious suspect and it is a probe, not a reading.
Step 1's fork-per-side re-diagnosis and step 3's slot-write pricing are moot for the rows
they were for — KEYVALUE is 2× ahead where it matters — and stay recorded for the clean row.

**Exit (reached for the rows):** the two rows read what a pipeline sees, beside the clean
reading. The pollution cost on our own tree is Phase 8.

## Phase 2 — The cliff *(was: the lazy-run skip for the stateful `Loop`)* — **Done 2026-09-03: `RunLoop`, FAR_LINE 51×, MISS_LINE 54×, ahead of the JDK on all four line rows**

`LazyRunBenchmark` FAR_LINE / MISS_LINE at 64 KiB: the JDK is 8–42× ahead, because the line
idiom `((?:[^\n]*\n)*?)lit` is a lazy run the tree cannot walk at that distance within its step
budget, so the simulation pays. Recorded 2026-08-28 as condition-gated on "a real config spells
a lazy run as a nested loop over large regions". It is un-gated here by direction, and the case
for doing it now rather than waiting is that the idiom is exactly the hand-optimisation a DS3
author *did* reach for before the dot-all spelling was fast — so a ported configuration is the
likeliest thing to carry it, and it is the one row on the scoreboard's terms where the library
loses outright.

The mechanism turned out not to be the offer count. Reading `Loop` (2026-09-03): the general
lazy loop **recurses once per iteration** — `next.match || body.match`, the body's tail
calling back into the loop — under `LOOP_DEPTH_LIMIT` = 1,024. A 64 KiB region of forty-byte
lines is ~1,600 iterations; the guard bails, and the simulation finishes 8–42× slower than
the JDK. A `leadingByte()` filter on the offers would trim doomed calls and leave the depth
untouched. So the fix is a node, not a filter: `RunLoop`, the lazy repetition of "a class
run then one terminator byte the class rejects" walked **iteratively** — one frame for the
whole loop, the way `StarClass` is one frame for a run. Compiled in `compileRepeat` when
the body has exactly that shape (`[^\n]*\n` qualifies; `.*\n` does not, because its run
would swallow the terminator and the unit boundary would be ambiguous). The offer is still
filtered by the continuation's `leadingByte()`, as `StarClass` does; the unit's scan is
`StarClass.scan`'s walk; semantics are the lazy loop's exactly, pinned against the JDK and
the simulation by `RunLoopTest`, including the 1,700-line region. Greedy unit loops stay on
the general `Loop` — they need the unit boundaries kept for back-off — and no row loses on
them.

**Audited 2026-09-03**, adversarially, with mutation checks. Findings: the auto path still
completes on a never-matching region — `RunLoop` never trips the depth guard, so it walks to
the region's end from every candidate start until the step budget stops it, and `runLinear`
catches that `MatchLimitException` and hands over to the simulation (pinned, the tree refuses
with the same exception it used to raise for depth; both pinned by test). Captures inside the
unit, a unit with a minimum, nested non-capturing groups, a preceding lazy run, and the
zero-iteration offer all agree with the JDK. Two audit corrections to the tests themselves:
the terminator-rejection guard's test used `.` under default flags, where `.` excludes `\n`
and the guard is never asked — its mutant lived until the test said `(?s)`; and the
`min() != 0` guard is reachable only through `{n,}`, because the parser spells `+` as
`X X*` — its mutant lived until a `{2,}` case existed. A third mutant survived twice for a
worse reason: it did not compile, and the stale report read green — mutation runs now check
the class file's timestamp moved. The `leadingByte()` filter's mutant is equivalent by
design and recorded as such. Verified clean: `data[end]` reads are bounded by the scan loop
and the short-circuit; lookbehind bodies are bounded-length by the dialect, so a `RunLoop`
never sits inside one; the reverse program compiles from the Hir and is untouched.

**Gate:** FAR_LINE / MISS_LINE for the win; ENTRY_LINE / BATCH_LINE as the small-region
controls (they must not lose their 1.0×); all four dot-all rows flat; canaries paired. **Exit:**
the line idiom over 64 KiB is within 2× of the JDK or better, and 06 §1's row moves to Done
with the numbers. If the budget bails before the skip earns its keep, the row records why and
what a `Loop`-aware budget would cost.

## Phase 3 — The other engines' gates *(same finding, four more sites)* — **Measured 2026-09-03, no effect; reverted**

The encoding plan put `nfa.form.splitsCharacter(...)` into `PikeVm.search`,
`Backtracker.attempt`, `FancyBacktracker.attempt` and `ReverseScanner.findStart`, and
`form.continuation(...)` into the fancy and tree back-off loops. None of those methods was
near the 325-byte threshold (587, 483, 1318, 376 bytes), so no cliff — but `ByteMatcher`'s
gate cost 5.5× on the search loop without a cliff, from the call alone failing to inline,
and the paired run's simulate rows read −1.7 to −5.0% with nothing else to blame. The
tree's back-off `continuation` was probed and cleared (`vc`, −13.5% ≈ noise); the rest is
unmeasured.

One probe per engine, on the row that engine owns: `simulate` ENTRY_DOTALL and BATCH_LINE
for the Pike VM, `fancy` ENTRY_DOTALL for the fancy tier, the `EndAnchored` KV rows for the
reverse scanner. Where a gate costs, the fix is the one already shipped and pinned — a static
call guarded by the form's own `singleByte()` fact, equivalent by `ByteFormInvariantTest`'s
sealed-set argument — spelled once per engine.

The probe's +3.7% on simulate ENTRY_DOTALL was measured against a ten-hour-old full-set value
and was drift; paired minutes apart, the full phase — all five gates and the fancy tier's four
`continuation` loops — reads flat on every engine row (−0.5% to +2.1%, inside spread) and
−5.5% bimodal on tree `anchored_miss`. The interface calls inline well enough everywhere but
`ByteMatcher`. Reverted; recorded in 06 §1 as measured, no effect.

**Exit:** each engine's gate is measured, and either flat or fixed; the paired run's simulate
losses are attributed or dissolved.

## Phase 4 — The class-shape cost on `ByteMatcher` *(the open ISSUES entry; a design question)* — **Closed 2026-09-04: re-measured on this machine, the machinery is on the fast side**

Open since 2026-08-25: the end-anchor programme's tail-window machinery cost buffer CSV
−12–14% through nothing but three instance fields on `ByteMatcher` — identical hot-path
bytecode, every inlining verdict unchanged, two clusters of forks. D37 then flipped the coin
the other way by deleting a field. The recorded fix shape is to get the end-anchored state off
the common matcher's shape — a per-pattern matcher selection the original entry dismissed and
its supersession reinstated.

This is first re-measured, not assumed: the strip-variant probe (machinery out) against the
row of record, on this machine, on buffer CSV and the BOUNDED rows. If the cost is still
there, the design lands as a small change — the end-anchored fields move to a matcher subtype
or a side object chosen at compile time, and `ByteMatcher`'s shape returns to the one the
flat path had. If the new CPU has dissolved it, the entry closes as machine-specific with the
numbers.

**Re-measured 2026-09-04, quiet box, the strip variant against the tip.** With the tail-window
machinery removed from `ByteMatcher` — fields, constructor computation, `endgameSearch`, the
entry ternary — buffer CSV's scan plan reads **6,011 against 7,228 (−16.8%)**, both at fork
spread ≤ 1.01; the tree −2.4%; per-match weblog flat (+0.6% / +3.3%). The August finding does not
reproduce: on this CPU, with D39's `contextEnd` field restored, the current field set is the
fast cluster of the two this row has always shown, and removing three fields lands in the slow
one — the same coin the D39 remedy just flipped the other way. A design that moves the
end-anchored state off the class would cost 17% today. So: **no design.** What is recorded
instead is the rule the two measurements agree on: buffer CSV's scan plan is a two-cluster JIT
mode keyed to `ByteMatcher`'s field set, ~7.2k against ~6.0–6.4k, and any change to that class's
fields is gated on this row, paired, before it lands. The ISSUES entry is superseded with this
datapoint; the end-anchored rows keep their three orders of magnitude.

**Exit (reached):** the ISSUES entry moves to `superseded` with a paired measurement from this
machine; the end-anchored rows keep their three orders of magnitude.

## Phase 5 — The cheap unlocks *(each an afternoon; each opens a recorded row)*

- **`ReverseSuffix`'s trigger, read correctly** (06 §6 phase 5). Its prerequisite, the reverse
  program, landed the same day it was deferred, and the key=value row already exists
  (`EndAnchoredSearchBenchmark` KV_HIT / KV_MISS — corrected 2026-09-03; this plan first said
  the row was missing). What 06 actually gates on is *a workload asking*: the corpus holds no
  pattern the suffix strategy would move. So the unlock is not a row but evidence — a real
  configuration using DS's `reverse` feature over a key=value shape — and the row stays parked
  with the standing rows below until one arrives.
- **First-byte refutation — a library-internal fix, not a published fact** (06 §1; corrected
  2026-09-04 against engine design 10 §9–11). The anchoring arc already ruled the shape of this:
  the engine's caller-side sniff was the thing that was wrong, the library exiting early on its
  own parsed knowledge was the fix, and a fact crosses the seam only with the parser's
  signature and only when measurement shows the library cannot act on it alone. Here it can.
  The scan plan's `ANCHORED` entry — `run(from, ANCHORED)` → `attempt(from)` — fills the slot
  array and enters `PlanRunner` before its first op refutes, while `plan.firstBytes()` sits
  unread on that path (the search loop reads it; the anchored entry does not; the tree's
  `cannotStartAt` does). One table lookup before the fill turns strict dispatch's ~57 refuted
  attempts per line (`win_sec_strict`, design 10 §11) from a prologue each into a byte read
  each — nothing published, nothing engine-side.

  **The change, specifically.** `ByteMatcher.run(from, anchoring)`, the last branch —
  `return !splitsCharacter(from) && attempt(from) >= 0;` — is the only anchored entry in the
  library that does not consult the pattern's first-byte table: `PikeVm`, `Backtracker`,
  `FancyBacktracker` and `NodeTree.Machine` each read theirs before any setup. It becomes

  ```java
  final byte[] firstBytes = plan.firstBytes();
  if (firstBytes != null && (from >= regionTo || firstBytes[data[from] & 0xFF] == 0)) {
      return false;   // a non-nullable pattern cannot begin here
  }
  return !splitsCharacter(from) && attempt(from) >= 0;
  ```

  The bounds argument is the search loop's: a first-byte table exists only for a non-nullable
  pattern, so `minLength >= 1`, a match needs at least one byte, and `from >= regionTo` is a
  refusal without a read (the D37 audit's proof, reused). Cheapest test first — the table
  lookup before `splitsCharacter` — since both are pure. `attempt()` itself stays as it is;
  `Arrays.fill(slots, -1)` is what the refuted attempts were paying for. Tests: the anchored
  differential suites already pin the answers; add one pin that an anchored match against an
  empty region of a non-nullable pattern is `false` (the `from == regionTo` edge), and one that
  a nullable pattern (no table) is unaffected. **Gate**, paired against the previous commit: the
  library's own row for this — `AnchoredSearchBenchmark` scan_plan `anchored_miss` (a failed
  anchored attempt is exactly what changes) with `anchored_hit` as the must-not-lose control;
  both canaries (the entry is a search path); and the engine benchmark's `win_sec_strict` and
  `win_sec_xml`, where design 10 §11 priced the ~57 attempts per line. It is a `ByteMatcher`
  method-shape change, so the 325-byte threshold on `run()` and the CSV coin rule both apply:
  `run()` is the tiny dispatcher the tier-0 audit says must stay tiny — if the branch grows it
  past a cliff, the check moves into a private method, and the CSV row is read before landing.

  `firstBytes()` crossing the seam for an engine-side candidate table stays where the arc left
  it: only if, after this, the per-call scaffolding is what remains — and the engine's record
  now says so (design 10 §11, corrected 2026-09-04).
- **The weblog guard residual**, 2.2% on a row with a bimodal JIT state: one more shape probe
  (a `singleByteForm`-specialised search loop chosen once at construction) and then either
  shipped or written off as noise-floor. It does not block anything.

**Exit:** three rows with numbers where they had conditions.

## Phase 6 — The D37 measure-first simplifications *(hot-loop hygiene, under gates)*

ISSUES.md's open list from the streaming retirement's audit: the anchor gates' `at < to`
exemption surviving its deleted reason; `PikeVm`'s subsumed exit and range disjuncts;
`PlanRunner` MATCH_LITERAL comparing bytes of a literal that cannot fit; the engines' int-end
returns and `Backrefs.TRUNCATED` collapsing to what their callers use. Each changes a
measured method's shape, which after this week is reason enough to do them one commit at a
time with the coin watched — and reason enough not to skip them, since shape cuts both ways.
One of them, the `contextEnd` seam, was flagged as **needing a ruling first** because deleting it
forecloses a tighter-context bound. Read against the record, that ruling has already been made:
the seam's only consumer on every engine is the one-byte look past the region end
(`splitsCharacter`), its value is `data.length` on both entries and nothing else ever binds it,
and the 2026-08-27 stale-byte resolution declared the bound-context API *moot* — "the contract
is the answer, not an API": the array holds the caller's data up to its length, the executor
blanks its window's tail, `RegionContextTest` and `WindowTailTest` pin both halves.
**Recommendation: delete the seam** — read `data.length` where `contextEnd` is consulted, drop
the per-engine field, the five binding stores and `ReverseScanner`'s fifth argument. It retires
R1's accepted store cost on the tree's instant-rejection rows and the `match()` setup store, and
shrinks four engines' shape; gated like every other row here because shape cuts both ways.
**Ruled and landed 2026-09-03 as [D39](../../design/00-decisions.md)**; the paired numbers are in the commit and 06 §1.

**Exit:** each landed or declined with its paired numbers; the ruling on `contextEnd`
recorded in the decision log.

## Phase 7 — The last blind spot *(a workload with catastrophically ambiguous input)*

Only the budget tests exercise the shape where the tree gives up and the simulation
finishes in linear time. A `CorpusBenchmark` workload built for it — nested quantifiers over
input designed to defeat backtracking — measures what the linear-time guarantee costs when it
is the thing actually running, and gives the step budget a number instead of a promise.

**Exit:** the row exists, is on the scoreboard, and 06 §5's list of blind spots is empty.

## Phase 8 — The tree under pollution *(found by Phase 1; the pipeline-shaped row)* — **diagnosed 2026-09-04: it is our dispatch**

Phase 1's harness found what 06 §2 had wrong: these engines are not pollution-immune. In a
JVM that has run the whole corpus, the scan plan loses 18% on CSV and the tree 26–39%
(NETWORK 6,608 → 4,906, KEYVALUE 8,860 → 5,768, CSV 4,147 → 2,530), against the JDK's
53–62%. Still 2× ahead where it matters — but this is the row a deployment actually runs
on, and the tree is the default engine for every ambiguous pattern.

Diagnosis before design, in this order:

1. **Whose pollution is it?** Three variants of the harness's `@Setup`: pollute with our
   patterns only, with the JDK's only, with both. If the tree's loss needs the JDK's patterns
   present, it is shared JIT infrastructure (the code cache, inlining budget spent elsewhere);
   if our own patterns suffice, it is our dispatch.
2. **Which sites?** `PrintInlining` on a polluted fork against a clean one, filtered to
   `NodeTree$*::match` and `PlanRunner::run`: the expected signature is `Node.match` call
   sites going from bimorphic (one pattern's two or three node kinds per site) to megamorphic
   (the corpus's dozen), and losing their inline caches — the same class of mechanism as
   `ByteMatcher`'s gate, spread over every node boundary.
3. **The design, only if 2 says so.** The scan plan's smaller loss points at the answer's
   shape: `PlanRunner` dispatches through one `switch` on an opcode, which no profile can
   pollute, where the tree dispatches through virtual `match` calls, which every pattern
   pollutes for every other. A tree walker whose hot boundaries — `StarClass` → `next`,
   `GroupHead`/`GroupTail`, `ByteSeq` — go through a kind switch rather than a virtual call
   is a measured-method change to the primary engine's hottest paths, gated on the full
   pair. Not attempted until 1 and 2 have made the case.

**Steps 1 and 2, run 2026-09-04 (07:46–08:13, quiet box).** Whose pollution: with the JDK's
patterns only, the tree is flat (NETWORK +0.2%, KEYVALUE −1.2%) and the JDK loses its 54%;
with ours only, the tree loses **−27.1% / −35.3%** and the JDK is flat; with both, −30.6% /
−34.3%. The scan plan reads −5.4% / −7.3% under our own patterns — hardier, not immune. So the
tree's loss needs nothing but the library's own patterns having run: it is our dispatch, not
shared JIT infrastructure. Which sites: `PrintInlining` polluted against clean on the tree's
NETWORK — the node chain that compiled as one unit in a clean fork (the stack profile sits in
`ByteSeq.match` and `GroupTail.match`, everything under them inlined) breaks apart polluted:
`CharClass.matchAt`, `runLinear` and `attempt` flip to "already compiled into a medium method"
and "callee is too large", "no static binding / virtual" mentions go 18 → 67, and the profile
spreads over `StarClass.scan`, `Machine.search` and `Assert.match` as separate frames. The
`Node.match` sites that were bimorphic for one pattern are megamorphic for the corpus, and the
inline caches they lose are the tree's whole speed. Step 3's case is made.

**Step 3 — the design, on paper (2026-09-04), unscheduled.** Three shapes, and the order to try
them is fixed by what the clean harness would lose:

1. **A kind switch at the hot boundaries.** Every `Node` carries an `int kind` set once when the
   compiler links it; the 22 `next.match(ctx, at)` sites in the fifteen node classes become
   `Node.run(next, ctx, at)`, a static method that switches on `kind` and calls the concrete
   class's `match` directly — a monomorphic call per case, which C2 can inline whatever the
   receiver profile says, the way `PlanRunner`'s opcode switch is immune. **The risk is the
   clean row, not the polluted one:** today's clean speed is inline caches folding a pattern's
   whole chain — `ByteSeq.match` → `GroupTail.match` → … — into one compilation unit, and
   `Node.run` is recursive (`run` → `ByteSeq.match` → `run` → …), which C2 inlines only one
   level deep (`MaxRecursiveInlineLevel`). A switch that stops the chain folding could cost the
   clean harness what it buys the polluted one. So the first act is a probe, not a design:
   `Node.run` at the eight hottest boundaries (`ByteSeq`, `OneChar`, `GroupHead`, `GroupTail`,
   `StarClass`, `RunLoop`, `CountedClass`, `Assert`), measured clean and polluted on the tree's
   NETWORK and KEYVALUE. If clean holds and polluted recovers, the design is this; if clean
   pays, it is not.
2. **Flatten the tree to a program.** Compile the node tree to an opcode array with an explicit
   continuation stack — the fancy backtracker's family — and give up the per-pattern JIT
   specialisation D30 chose the tree for. Polluted and clean would converge, from both sides.
   A rewrite of the primary engine; only if 1 fails and the pipeline number matters more than
   the microbenchmark one.
3. **Accept, and let the harness tell the truth.** Polluted, the tree still beats the JDK by
   1.5× on these rows, and the *scan plan* now beats the tree there (NETWORK 5,123 against
   4,906; KEYVALUE 6,369 against 5,792) — the flat engines are the pollution-hardy ones. The
   honest scoreboard is the polluted one; 06 §2's "immune" becomes "hardier than the JDK by
   a wide margin, and the tree by less than the scan plan". No code.

**The probe in 1, run 2026-09-04 (09:06–09:12).** `Node.run` at the eight hot boundaries
(ten call sites rewritten), tree tests green, then the tree's rows: clean NETWORK −9.4%,
KEYVALUE −17.0%; polluted NETWORK −8.0%, KEYVALUE −14.4%; per-match datetime −2.3%, weblog
−8.8%. It loses on both sides — the recursive switch stops the chain folding exactly as feared,
and it does not buy the polluted row back either, because what pollution takes is the inline
caches *inside* the chain, which a switch at the boundary cannot restore. **Shape 1 is
rejected.** What remains is 2 (flatten the tree to a program — a rewrite of the primary engine,
converging the two harnesses from both sides) and 3 (accept, and let the polluted scoreboard be
the honest one). Recommendation: 3, with 2 recorded as the one lever left if the pipeline
number ever needs the tree's clean speed. Ruling awaited.

**Gate:** `PollutedCorpusBenchmark`'s tree rows against `CorpusBenchmark`'s, paired, plus the
clean canaries — a fix that buys the polluted row by costing the clean one has to say so.
**Exit:** the tree's polluted loss is attributed, and either closed or accepted with its
number; 06 §2's "pollution-immune" is rewritten to what the harness measures.

## Audit record — 2026-09-03

Everything landed today was audited the same day, in the 08-28 manner. `RunLoop`'s audit is
under Phase 2. The rest, by reading, each closed: the `cannotStartAt` split (`7b6301a1a7`) is
the same three tests in the same order, each mapped to `return true`, behind one `anchored`
break — identical to the loop it left; D39's deletion leaves every engine read past the
region bounded by `to` or by `data.length` under `ByteMatcher`'s contract (backrefs and word
boundaries decode to `to`; the reverse scanner's probe at `to` reads `at < data.length`); the
Phase 3 revert is total (no engine carries a `singleByte` field; `ByteFormInvariantTest`
remains, guarding `ByteMatcher`'s spelling); and the pollution harness pollutes — 114
patterns × their inputs × 200 passes through both libraries, past C2's thresholds by two
orders, with the measured pattern compiled after. Two tests (`RunLoopTest`,
`PollutedCorpusBenchmark`) were corrected for checkstyle; nothing in production code moved.

## Standing rows — gated, and staying gated

Each stays parked behind the condition it was recorded with, restated here so nothing above
is mistaken for reopening it:

- **The lazy-run skip's extension ladder** — memchr where the class accepts every byte
  (exactly there the walk provably equals the search), a small byte-set, memmem for the whole
  literal. Trigger: a workload loses on one. Phase 2 may produce that workload.
- **Tier-0 strictness on contract-violating input** — the D38 closing move (validate the
  scanned span only when it held a high byte). Trigger: composition proves unable to supply
  the validity contract in practice.
- **Literal runs as one instruction** — fallback-engine-only since D31/D32. Trigger: the
  fallback shows up in a measurement.
- **Accepted costs** in ISSUES.md — R1's −2–4% on the tree, the D26 greedy-permissive chains,
  the simulation's Unicode-width floor — stay accepted.

## The evidence gate over everything

Every row above measures the benchmark corpus, and half the triggers in this document are
spelled "a real config …". Real DS3 configurations are the evidence that revises the
scoreboard's sentence and fires or retires those triggers; the harvest path exists
([04](04-corpus-analysis.md)), synthetic generation was deferred for exactly this reason. When
they arrive, Phase 1's polluted harness is the instrument to run them through first.
