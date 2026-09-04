# Performance plan

The open performance work, recorded after the 2026-08-19 session so that any future session
starts from this list rather than from folklore. Statuses move here as work lands; evidence
citations are the benchmark files in [benchmarks/](benchmarks) and the sections of
[05-engine-benchmarks.md](05-engine-benchmarks.md). The method is fixed and non-negotiable:
one change at a time, a measurement after each, same machine, checked-in results —
the discipline that took FANCY_LOOKAHEAD from 0.15× to parity (§10.1) and that refuted
four confident hypotheses on the way. Amended 2026-08-22: at single-digit-nanosecond
operations *same machine* is not enough — a cross-boot comparison hid a real 9% regression
under a favourable boot while its drift-control rows read clean. Comparing across boots means
re-running the baseline commit on the current boot first (the ledger's benchmark-gate section
holds the worked example, two disproven hypotheses included).

**The open rows here, in §2, §5 and §6, and ISSUES.md's open entries are sequenced with gates
and exits in [07-ledger-plan.md](07-ledger-plan.md) (2026-09-03). Statuses still move here.**

## 1. Quick wins — mechanism proven elsewhere in the codebase

| Item | Status | Notes |
|---|---|---|
| Line-anchor start gate in the fancy engine and scan plan | **Done** (§10.1 step 1) | +5.7% on dense matches; its real case is sparse scans |
| The same gate in the bounded backtracker and Pike VM | **Done** (this commit) | The two middle engines were still setting up per position for `^`-anchored patterns |
| Publish the leading anchor (`BytePattern.leadingAnchor()`) | **Done (2026-08-21)** — by user decision: the library may publish the fact, so long as the library is its single source | A dispatching caller that provably-safely asks the anchored question saves the search prologue — the template engine measured that prologue at 2–5% on its anchored-pattern workloads after retiring its text sniff. The accessor reads the same `Analysis.startAnchor` conclusion every compiled artifact carries, so caller and search can never disagree. `LeadingAnchorTest` pins the semantics, including what the sniff got wrong (`^(a|b)` and `(?s)^a` are INPUT; `[^a]b` is NONE) and one honest conservatism: the analysis does not look through alternations, so `^a\|^b` is NONE — the safe direction, pinned for celebration if it ever sharpens |
| Early exit for input-anchored patterns in unanchored search | **Done (2026-08-21)** — `anchored_miss` 46,168× (scan plan), 62,191× (Pike VM), 12,712× (tree): a failed 256 KiB `^`-search fell from 102–584 µs to 3–10 ns, cheaper than the hit, which is the correct end state. Controls flat; realistic-mix bonus: SPARSE 1.91× (error bar ±12% → ±0.4%), network 1.31×, structured/identifiers 1.30×, csv 1.21× | Baseline `2026-08-20-2126`, first cut `2026-08-20-2302`, final `2026-08-21-0037`. The first cut put the exit *inside* the anchor gate and the `line_miss` control convicted it — the tree's line-anchored walk paid 11% for a loop-invariant branch — so the exit moved out: on a complete window an input-anchored pattern clamps the walk's own bound (`lastStart` / the Pike VM's `lastSeed`) to the region start, and the hot loops are byte-identical for everything else. Growing windows keep their walk, so streaming is unchanged by construction (that walk retired with the streaming surface, D37). Watch items, both within the JDK-rows' own 0.86–1.07× drift envelope: tree FANCY_LOOKAHEAD 0.92× (its JDK pair moved +7% the other way), and scan-plan `line_miss` 2.5× *faster* — unexplained by the mechanism, consistent with this method's documented inlining-cliff sensitivity, noted for the next session rather than claimed (**explained 2026-09-03**: the row is inlining-cliff sensitive exactly as suspected — the encoding plan's polymorphic `splitsCharacter` gate later pushed it 5.5× the *other* way, and devirtualising the gate settled it at ~23,000 ops/s with the bimodality gone; see the gate row below). The template engine's caller-side sniff is now retirable |
| Minimum-length fail-fast | **Done, everywhere — restored to the scan plan after its removal proved a mistake** | The full saga, kept as a cautionary tale: shipped unattributed in D32's bundle; removed from the plan path on buffer-CSV evidence (its presence had tipped the grown `run()` over an inlining cliff — the real fix was the dispatcher split); and the removal then cost per-match datetime 43%, caught by the closing full-suite run because the removal itself was guarded on one suite only. Restored on top of the split: datetime 1.22× ahead, per-match network 1.43× ahead (its best ever), buffer CSV a wash. Two lessons, both now method notes: attribute bundles, and guard *both* suites |
| Literal runs as one instruction | **Deprioritised by architecture (D31/D32)** | Was aimed at the flat fancy engine, now fallback-only; the tree engine's `ByteSeq` already compares runs whole. Revisit only if the fallback ever shows up in a measurement |
| Literal-prefix skip (Boyer–Moore-ish) | **Done (2026-08-28) — landed as the lazy-run skip, `39ff2f88a4`** | Was: deprioritised because SPARSE measured 2.80× *ahead* of the JDK without it (`2026-08-19-1601`), "revisit only if a sparse workload ever loses". One has, two layers up. The XML catalogue's `nasty_xml` is the head-to-head's only loss to Saxon, and pricing it by variant ([13](../../design/13-xml-xslt-benchmark.md)) puts **54% of its runtime** in one shape: a lazy run followed by a literal, `((?s).*?)  </batch>`, which tries the tail at every byte of the block. Rewriting the *config* to scan by line instead of by byte takes the case from 940 ms to 435 ms on 100k units, byte-identical output, turning a 0.65× loss into a 1.43× win — so the cost is real, it is in this module, and an author currently has to know to avoid it. The wanted mechanism is the tail variant of this row: when a lazy run is followed by a literal, find the literal rather than stepping onto it. Note the original evidence still stands on its own terms — a literal **prefix** skip was not what SPARSE needed; this is the same machinery pointed at a run's tail. **Landed:** every node can be asked what single byte it must consume first (`leadingByte()` — a `ByteSeq`'s first byte, a one-code-point `OneChar`'s lead byte, resolved through group heads and tails so a capture boundary does not block it), and `StarClass`'s lazy branch skips the positions that byte is not at. The skip **walks** with the run's own stepping rule rather than searching for the byte, so every position it can stop at is by construction one the unfiltered loop would have visited — the whole correctness argument, and it holds on invalid UTF-8 where a memchr's would not — and the bytes passed over are still charged to the step budget, so pathological patterns are refused on the same evidence as before. Measured: `LazyRunBenchmark` dot-all rows 2.04–3.19×, line-form rows flat as the control (`2026-08-28-1005/1009` against the paired base `1014/1017` — the stored overnight baseline read 2–6% low on every row *including the JDK controls*; a rebooted box, not the commit — see `86b989e7d3`'s message, and note the JDK pays 6.5× for the dot-all idiom against its own line-form on the same rows, so `java.util.regex` demonstrably lacks this skip). The catalogue's `nasty_xml`, the loss that reopened this row: 0.65× → **1.21×** in its own JMH harness (`2026-08-28-1029`, both engines in one run so box drift cancels). This is the convergent technique across engines — .NET 7 rewrites a lazy loop before a literal into vectorised `IndexOf`; Rust's lazy DFA memchrs out of states whose only exits are a few bytes; Hyperscan's whole architecture is literal factors first — ours is the conservative member: one byte not a byte set, a walk not a search, class runs (`StarClass`) not the stateful `Loop`. Each generalisation stays unopened until a workload loses on it: memchr where the class accepts every byte (exactly there the walk provably equals the search), a small byte-set (Rust accelerates up to three), the whole literal by memmem-and-verify |
| The 64 KiB line-idiom cliff — `RunLoop`, one frame for the whole lazy unit loop | **Done (2026-09-03)** — `LazyRunBenchmark` FAR_LINE 901 → 46,140 ops/s (51×), MISS_LINE 904 → 48,764 (54×); ENTRY_LINE +11.2%, BATCH_LINE +17.6%; dot-all rows and fancy FAR_LINE flat; canaries inside their bands. Against the JDK: FAR_LINE 1.26×, MISS_LINE 6.9×, ENTRY_LINE 1.02×, BATCH_LINE 1.19× — the one behind-shape on the scoreboard is ahead on all four rows | Was: deprioritised 2026-08-28 pending a real config, and diagnosed as the lazy-run skip's missing `Loop` variant. The diagnosis was wrong about the mechanism: the general lazy `Loop` recurses once per iteration under `LOOP_DEPTH_LIMIT` = 1,024, a 64 KiB region of forty-byte lines is ~1,600 iterations, the guard bails and the simulation finishes 8–42× behind the JDK. A `leadingByte()` filter would have trimmed doomed offers and left the depth untouched. The fix is a node: `RunLoop`, the lazy repetition of "a class run then one terminator byte the class rejects" walked iteratively, compiled in `compileRepeat` when the body has that exact shape (through the non-capturing group the parser wraps it in; `[^\n]*\n` qualifies, `.*\n` does not — its run would swallow the terminator). Offers are filtered by the continuation's `leadingByte()` as `StarClass` does; the unit scan is `StarClass.scan`'s walk; greedy unit loops stay on `Loop`. `RunLoopTest` pins agreement with the JDK and the simulation — captures, laziness, missing terminators, non-ASCII runs, empty lines — and the 1,700-line region, where the oracle is the simulation because `java.util.regex` itself overflows a default stack there (`Pattern$LazyLoop`, 1,024 frames): the JDK's own recursion is the same cliff one level up. Evidence: `2026-09-03-*-p2-*.json` |
| The engines under profile pollution — the tree loses 26–39% | **Diagnosed (2026-09-04) — our own dispatch, not shared JIT state; the design is 07 Phase 8 step 3, unscheduled** | `PollutedCorpusBenchmark` (07 Phase 1) measures `CorpusBenchmark`'s rows in a JVM that has run all 114 corpus patterns through both libraries first. The JDK falls 53–62% and the scoreboard's two parity rows become 2.1× wins — that was the point. The surprise is ours: scan-plan CSV −18%, tree NETWORK −26%, KEYVALUE −35%, CSV −39% (6,608 → 4,906; 8,860 → 5,768; 4,147 → 2,530), where 06 §2 had recorded these engines as pollution-immune. They are less polluted than the JDK, not immune. Verified 2026-09-04 (07 Phase 8): polluting with our own patterns alone costs the tree −27% / −35% while the JDK's patterns alone cost it nothing; `PrintInlining` shows the node chain that compiles as one unit clean breaking into separately compiled pieces polluted, virtual-call mentions 18 → 67. The `Node.match` sites go megamorphic across the corpus and lose their inline caches. The scan plan's opcode switch loses 5–7% on the same rows — hardier, as suspected. The design is a kind-switch walker at the hot node boundaries; a week's work, scheduled by direction. Evidence: `2026-09-03-*-p1-*.json` |
| The search split's inlining topology — tree `BOUNDED_MISS` −21.6% | **Ruled kept by Jon, 2026-09-04** — the split stays; the tree's bounded-tail rows carry the cost as accepted (ISSUES.md) | The chain read the tree's end-anchored `BOUNDED_MISS` −18.1% and `BOUNDED_HIT` −6.5% between pre-plan and after-fixes; the fix bisect put the whole step on the search split `7b6301a1a7` (7,266,258 → 5,700,239). A different cut (`v3`: gates inline, the attempt out of line, `search` 255 bytes) reads the same 5.84M, so it is not the cut. `PrintInlining` either side says what it is: before, `search` at 330 bytes failed to inline ("hot method too big" ×7) and compiled as its own unit with `OneChar.match` inside it; after, at 255 it inlines "hot" ×5 into `runPinnedTree`/`run`/`match` and the benchmark stub, and `ByteMatcher.match` reports "already compiled into a big method". The end-anchored rows do one short search near the region's end per record — all prologue — and pay ~20% for living inside the larger unit; `anchored_miss`, the same class of tiny operation, gains 11% from it, and weblog's tree-routed patterns gain their 5–7% back. No cut avoids the choice: the phase-3 cost *was* `search` not inlining. Both sides are real rows; Ruled kept on the precedent of the tree's hit trade (2026-08-26): the rows that win are the ones where both engines do real work, the rows that lose are early-exit rows still 7,000× ahead of the JDK, and no cut avoids the choice. Evidence: `2026-09-04-*-split-bmiss-*`, `*-splitrecut-v3-*` |
| Scan-plan SPARSE −7.6% at phase 4 | **Deprioritised (2026-09-04) — bisected to `b7ffa4c09e`; code generation, no lever this box can show** | The never-matching row (`^FATAL: (.*)$`, pure scanning) lost 7.6% between phase 3 and phase 4 of the encoding plan (121,154 → 111,902, spread ≤ 1.03 at every point) and nothing since moves it: the seeding-gate fix, the search split, the accept fix, D39 and its remedy all read ~111k. Its search loop refutes almost every position at the anchor gate before the form gate is asked, so the gate's call form is not the obvious cause — yet phase 4's only change on this path is that gate. Hoisting the guard and bound to locals above the loop (`v4`) reads −6.6% *worse* on this row while +3.5% on `line_miss`, so the loop's shape is the lever and it cuts both ways. One loop per form (`v5`), the multi-byte loop carrying the pre-phase-4 bare static call verbatim, reads −6.7% *too* — and `line_miss` −11% bimodal with it. So it is not the gate's spelling: the row is hypersensitive to `searchPlan`'s compiled shape and every edit to that loop since phase 4 has read the same −7%, with the pre-phase-4 121k not reproducible from the loop's text alone. Still 5.2× ahead of the JDK. The compiled plan for `^FATAL: (.*)$` is the identical seven ops either side of phase 4, so the loss is the search loop's code generation and nothing above it. **Deprioritised**: 5.2× ahead, every lever tried moves it the same −7%, and the next instrument is machine code, which this box cannot show (no `perf`, no hsdis). Revisit if a real never-matching workload loses |
| Scan-plan UNICODE −6.5% at phase 3 (regex side) | **Deprioritised (2026-09-04) — bisected to `b0154e52e2`; every probe-reachable mechanism cleared; parity on the clean scoreboard, 2.2× polluted** | `^([\p{L}0-9]+): (\S+) (\d+)$` over accented text lost 6.5% at the commit that threaded `ByteForm` through the parser, the HIR and the compilers (5,255 → 4,858; flat at the engine-side and audit commits, flat since). The runtime class walk (`CharClass.matchAt`) did not change; `Plan` gained a `form` field and the class tables are built through the form. It takes the row from 1.13× ahead of the JDK to 1.05× — parity by the interval. A `Plan` field-order probe reads flat (4,862), and `PrintInlining` either side of the commit flips no verdict on our classes (`PlanRunner.run` 754 → 758 bytes, never inlined either way; `Words.assertionHolds` 221 → 229, inlined either way). `ByteForm.UTF8.sequences` builds byte-identical tables to `Utf8.sequences` for all three classes (828, 25 and 71 alternatives), so it is not the class walk either. Every mechanism a probe could reach is cleared; what remains is code generation. **Deprioritised** at parity with the JDK on the clean harness and 2.2× ahead polluted; the scoreboard sentence carries the parity. Revisit with machine-code tooling or a real accented-text workload |
| Tier-0 strictness on contract-violating input | **Deprioritised (2026-08-28) — revisit only if composed pipelines cannot in practice supply the validity contract** | D38 licenses the scan plan's byte ops by contract: strict on validly encoded input, permissive off it, pinned by `GreedyRunRawBytesTest`. Closing the deviation without forfeiting `SCAN_UNTIL_BYTE` has a known bounded shape — validate the scanned span only when it contained a byte ≥ 0x80, so the all-ASCII hot path (buffer CSV) never pays — but it is work for a problem composition is supposed to make unreachable, so it waits for evidence that composition does not |
| First-byte refutation for dispatching callers | **Open (2026-08-28) — shape undecided, and the anchoring arc already ruled on the order to try** | The engine's E12 names its next dispatch fix as a first-byte candidate table: strict dispatch tries ~57 anchored attempts per line in `win_sec_strict`, most refuted by the first byte, and wants each position dispatched only to the templates that could match. The table is an **unverified suspect**, and the anchoring arc (engine design 10 §9–10) already ruled on the method: try the library-internal shape first — the `ANCHORED` entry refutes on the first byte before any setup, the library acting on its own fact with nothing published — and only if measurement then shows the per-refuted-call scaffolding is what remains does the fact cross the seam as `firstBytes()`, with the parser's signature, for a caller-side candidate table. The analysis exists either way (`Utf8.leadBytes`, the plan compiler's class-byte tables). Synergy noted: encoding-plan phase 2 (design 19) byte-compiles the tree's classes, after which every engine carries the first-byte fact in compiled form and either shape is a read, not a computation |
| The `splitsCharacter` gate's polymorphic spelling — the encoding plan's one real cost | **Done (2026-09-03)** — `line_miss` 3,076 → 22,919 ops/s, above the 18,609 it had before the plan; `BOUNDED_HIT` −14.4% → −1.3%; per-match weblog unmoved | The encoding plan (design [19](19-encoding-plan.md)) was gated by a paired full set on one boot, `4aa6181941` → `75f6bbaa1c`, 223 rows each side, JDK controls flat at median +0.08% so the box held still. It showed `CorpusBenchmark` engine rows at median −4.33% against their controls' +0.37%, and a bisect of the six phase boundaries on four probe rows split that into three unrelated costs: phase 2 −7.7% on `LazyRunBenchmark.natural` BATCH_DOTALL, phase 3 −6.3% on per-match weblog, and phase 4 **−84.9% on `AnchoredSearchBenchmark` scan_plan/line_miss** with −15.2% on `EndAnchoredSearchBenchmark` BOUNDED_HIT. Phases 5 and 6 were free. The phase-4 cost was the audit's own correctness fix, spelled `pattern.form().splitsCharacter(...)`: a sealed-interface call in a gate that runs once per candidate start position — for a `(?m)` miss it *is* the search loop. Proved causally rather than inferred: reverting only that call restored line_miss to +0.1% and BOUNDED_HIT to +0.5%, and left weblog at exactly phase 3's residual −4.9%. Caching the form in a `final` field recovered **none** of line_miss and a third of BOUNDED_HIT, so the cost is the call failing to inline, not the field hop. Landed as a static call guarded by the form's own `singleByte()` fact — the same answer by the sealed set (the two single-byte forms answer `false` unconditionally; the only multi-byte form is UTF-8), which is what `ByteFormInvariantTest` pins so a fourth form has to visit this gate. The phase-4 audit's correctness fix is preserved, not reverted: a match under RAW or a table still starts at bytes 0x80–0xBF. Three repeat runs put line_miss at 22,919 / 23,236 / 23,151 with fork spread ≤ 1.04, so the row also **stopped being bimodal** — which retires the watch item two rows up |
| Phase 2's cost — one line in the audit, and a value-range fact C2 lost | **Done (2026-09-03)** — BATCH_DOTALL −7.8% → −0.7%, buffer shapeshifterTree NETWORK −11.2% → −3.8%, tree/anchored_miss −2.4%, phase 2's UNICODE win kept at +6.6% — the shipped tree, same boot as the paired run (probe `vd` had read −0.8% / −1.8%) | Bisecting inside phase 2 exonerated the byte-compilation itself: `886066ec82` (strict decode) −1.1%/−1.8% and `80309fa254` (the tree's classes byte-compile) −1.8%/−0.5% on BATCH_DOTALL/NETWORK, both within noise. The whole cost arrived with the phase-2 *audit*, `ce36be5db7`, which deleted the phase's own range walk in favour of `CharClass` — and, in passing, simplified `OneChar.accept`'s ASCII return from `ascii[lead] != 0 ? 1 : 0` to `return ascii[lead]`. Same value; not the same program. Three shape probes at HEAD settled which of the audit's edits it was: restoring the branchy return (`vd`) recovered both rows (−0.8% / −1.8%); moving the multi-byte path out of `accept` (`ve`) did nothing (−8.9% / −13.0%); both together matched `vd`. Restoring the retired `allNonAscii` scan branch and the old decode-and-contains path were also tried and were irrelevant — the input is ASCII and never reaches either. The mechanism: from the branch, C2 knows `accept` yields 0 or 1 and folds every caller's `end += advanced` and `advanced == 0` on that fact; a raw `byte` load carries no range, and the lazy-run, greedy-scan and counted loops around it all compile worse. The cost was invisible to the stack profiler (everything inlines into `StarClass.match` on both sides) and to bytecode sizes (`accept` *shrank*, 86 → 65), which is why it took a bisect and a probe rather than a reading. The `CharClass` delegation itself stays: it is what gives wide classes the lead-indexed walk, and it measured free. Canaries for this and the search split together, paired against the tip minutes apart (the ten-hour-old tip numbers had drifted −3% and first read as a datetime regression): buffer CSV scan-plan +0.4%, tree **+10.6%** — the buffer cluster coming back — and per-match datetime −0.8% / tree −1.8% with controls at +0.5% / +0.2%; the tree datetime value sits at the edge of its own fork spread (1.05×) and is noted, not claimed either way |
| D39 — the `contextEnd` seam deleted | **Done (2026-09-03)** — tree `anchored_hit` +0.9%, `anchored_miss` +1.8%, scan-plan `anchored_miss` +3.0%, weblog +1.6%; buffer CSV scan-plan −3.2% recorded open (ISSUES.md), simulate `line_miss` −6.2% the coin | The seam's only consumer was the one-byte look past the region end; its value was `data.length` on every path; the tighter bound it kept expressible was ruled moot 2026-08-27. Ruled D39 and deleted: field, setter and assert from four engines, six stores from `ByteMatcher`, the fifth argument from `ReverseScanner.findStart`, the parameter from both `splitsCharacter`s. The one cost is a shape effect, isolated by three probes (padding in `ByteMatcher`, padding in `PikeVm`, forced alignment): placement accounts for the bimodal −8 to −14% first read, the residual is `ByteMatcher`'s shape, and the class-shape entry owns it. Phase 3's Pike VM gate, probed on top, was drift (see the Phase 3 row). **2026-09-04:** the overnight chain convicted D39 on scan-plan `line_miss` — 22,909 → 3,074, the seeding-gate fix undone — a row its probe set lacked; bisected to `c0409bebb4` exactly, and remedied by `ByteMatcher` keeping the bound as a field (22,948; a hoisted local reads 19,739) — which also took buffer CSV's scan plan from 6,357 to 7,228 (+13.7%, spread 1.01), closing the −3.2% and the bimodality recorded against D39. The engines' deletions stand. Lesson recorded in 07: a probe set must carry every row the change's mechanism can reach, not only the rows that motivated it |
| ~~The other engines' gates as guarded statics (07 Phase 3)~~ | **Measured, no effect (2026-09-03) — reverted** | `ByteMatcher`'s gate cost 5.5× as an interface call, so the same spelling was tried in `PikeVm`, `Backtracker`, `FancyBacktracker` (gate and four `continuation` loops), `ReverseScanner` and the tree's `cannotStartAt`. Paired against the previous commit minutes apart: simulate ENTRY_DOTALL −0.5%, BATCH_LINE +2.1%, fancy ENTRY_DOTALL +1.1%, tree/simulate KV_HIT −0.7% / −1.2%, datetime canaries flat — every engine row inside its own spread — and tree `anchored_miss` −5.5% with the after side bimodal. The earlier +3.7% on the Pike VM (probed on top of D39 against a ten-hour-old full-set value) was drift, the same lesson as the morning's datetime reading. So the premise held for one gate and not the others: those interface calls inline well enough in practice, and the row that moved was the coin. Code reverted; the plan's Phase 3 closes as measured. The one file the phase left behind is `ByteFormInvariantTest`, which still guards `ByteMatcher`'s spelling. Evidence: `2026-09-03-*-p3probe-*.json` |
| Phase 3's cost — the tree's search loop crossed the inlining threshold | **Done (2026-09-03, `7b6301a1a7`)** — tree/anchored_miss −11.0% → −1.3%, per-match weblog −7.3% → −2.4%, controls flat | Threading `ByteForm` through the artifacts put `ctx.form.splitsCharacter(...)` into `NodeTree.Machine.search`, taking it from 321 to 330 bytecodes. C2's `FreqInlineSize` is 325, so the tree's whole search loop stopped inlining into `ByteMatcher.runLinear` — the `PrintInlining` log says "hot method too big" ten times where the phase-2 build says "inline (hot)" — and appeared as its own 3.5% frame in the stack profile. On tier 0 nothing changed at runtime (`CharClass.matchAt` is untouched, `^`/`$` never reach the form), so per-match weblog's loss was its tree-routed patterns. The fix is geometry only: the three position-rejection tests move into `cannotStartAt` (85 bytes, inlines straight back) and `search` is 255. The form gate stays a form gate. What remains on weblog, −2.4%, is the `ByteMatcher` gate's guard inside the inlined `searchPlan` loop — measured twice at 2.2% against the bare static call, both tight — and hoisting it to a local above the loop read −1.5% then −7.2% on consecutive runs (fork spread 1.10), so that row has a bimodal JIT state at the noise floor and the hoist is not worth shipping. The other four engines' gate-bearing methods (`PikeVm.search` 587 bytes, `Backtracker.attempt` 483, `FancyBacktracker.attempt` 1318, `ReverseScanner.findStart` 376) were never near the threshold; whether their interface call costs on its own is unmeasured |

**A method note from the early-exit miss (2026-08-20):** benchmark the failure path, not
only the success path. Every corpus workload measures searches that mostly match, so a search
that is guaranteed to fail over a large region — the shape a *dispatching caller* produces
dozens of times per record — was never on the board, and the gates shipped without their exit.
The miss surfaced two layers up, as a 3.6× inversion in the template engine's baseline, and
took a why-chain across both projects to trace home. `AnchoredSearchBenchmark` now keeps the
failure shape measured permanently.

**A third method note, from the tier 0 audit:** a batch's confirmation run must re-measure
the tiers it *touched*, not only the workloads it targeted. D32's confirmation measured its
target categories and shipped a silent 24% regression on buffer CSV — an inlining cliff from
`ByteMatcher.run()`'s accumulated growth — caught only when the audit's guard re-ran
plan-owned workloads. Guards per touched tier, every batch. *Ruled into standing practice
(2026-08-25), after the lesson arrived a third time:* Phase 2's gate ran the anchored and
end-anchored suites and shipped −12–14% on buffer CSV, invisible for a day until the
nightly full-suite pair caught it (the ledger's 2026-08-25 section holds the bisect). Any
change that touches `ByteMatcher` or an engine's search path gates on **buffer CSV and
per-match datetime** — the two canaries that have now caught three regressions the
targeted suites missed — alongside whatever suite the change aims at.

**A second method note (2026-08-20):** never compare a probe number against a JMH number —
the NETWORK "distributed cost" diagnosis made exactly that error and had to be retracted.
Probe-vs-probe and JMH-vs-JMH are each valid; the cross is not.

**A method note from the NETWORK diagnosis (2026-08-19, late):** single-JVM interleaved
probes systematically *understate the JDK* — warming both engines in one process poisons the
JDK's type profiles (the §10.2 asymmetry working in this library's favour) while these
engines' interpreters are pollution-immune. Locators comparing against the JDK must fork per
side, or their flattery must be discounted. The tree-vs-flat probes are unaffected: both
sides pollute alike.

| ~~Lazy `StarClass` per-character `accept()`~~ | **Measured, no effect (2026-08-20)** — 112 → 112 ns/find on `keyvalue`'s lazy pattern. The row's own caveat was right: the downstream attempt per extension dominates. Change reverted |
| ~~`CountedClass` `accept()` scan~~ | **Measured, no effect (2026-08-20)** — 82 → 84 ns/find on `\S{1,10}`. Reverted with the above |

## 2. Diagnosis needed — measure before touching anything

| Item | Evidence | Suspects (unverified) |
|---|---|---|
| ~~Per-match short-record gap~~ | **Resolved (D32)** — tree-first per-match: datetime 1.20× and fixedwidth 2.0× *ahead*, csv/syslog ~2.1× | The gap was the engine choice, not a fixed cost |
| ~~`NETWORK` at ~0.84× on the buffer suite~~ | **Closed (2026-09-03) by the pollution harness — 2.07× in a pipeline-shaped JVM; was: re-diagnosed 2026-08-20 as methodology-sensitive, no engine deficit found** | The earlier "distributed cost" reading compared a raw-probe number against a JMH number — cross-methodology, retracted. Measured properly: a synthetic digit-run/separator slope shows our per-pair mechanics *ahead* of the JDK at every size (~15.4 vs ~17 ns/pair), and the exact workload fork-per-side reads **156 vs 160 ns/record — parity**, groups and decode included. The 0.84× is real only within JMH's harness conditions, which flatter the JDK's steady state. The fused-op candidate is withdrawn; no code change is warranted. If the JMH artifact ever matters, that investigation is a harness question, not an engine one |
| ~~`TIER1_ALTERNATION` on the tree engine at 0.69×~~ | **Resolved (2026-08-19, late — `2026-08-19-1947`)** | Fork-per-side decomposition proved the alternation innocent: the gap was constant down to `^(.*)$` alone — `StarClass`'s per-character `accept()` call, ~4 ns/char against a table loop's ~1. A byte-safe fast scan (CLASS_STAR's trick) took the workload from 0.65× to **1.68× ahead**, and roughly doubled every star-bearing tree workload with it: TIER1_GREEDY 2.16×, FANCY_ATOMIC 1.99×, FANCY_LOOKAHEAD 1.53× |
| ~~Per-match `datetime` at 0.67×~~ | **Resolved (D32)** — one pattern carried the category; tree-first took it to 1.20× ahead | |
| ~~The scan plan on Unicode classes: ~5× off~~ | **Resolved as a misdiagnosis** ([05 §10.5](05-engine-benchmarks.md)) | The workload's pattern was ambiguous and measured the simulation, not the plan. Corrected and re-measured (`2026-08-19-1735`): the plan is **1.24× ahead** of the JDK on accented text. No work to do |
| ~~The tree engine on fixedwidth~~ | **Fixed (D32)** — `CountedClass` compiles bounded class repeats to one node; 2,841 → 99 ns on `\S{1,10}`, category now 2.0× ahead | LONG_RECORD stays the plan's (one-pass), and the depth guard covers the tree's remaining long-record shape |

## 3. Architecture decisions — D30's gate, closed by D31

| Item | Status |
|---|---|
| Recursion-depth guard for `Engine.TREE` | **Done** — loop-depth limit 1,024 + `StackOverflowError` backstop; structural bailout falls back, pinned use contains |
| Budgeted TREE with simulation fallback for ambiguous patterns | **Done (D31)** — default path measured at 3.9×/7.7× on the TIER1 workloads, linear-time promise kept |
| Oniguruma corpus through TREE; pollution at hundreds of patterns | **Done** — 622/622 identical; per-category stable at full sweep; `everything` (114 patterns, one JVM) showed the mixed policy beating pinned-tree 617 vs 354, settling the larger point |

## 4. Tier audits (2026-08-19/20)

Adversarial per-tier review: hygiene, correctness, javadoc truth, structure — performance
guarded by JMH after every change.

| Tier | Status | Findings |
|---|---|---|
| 0 — scan plan | **Done** | Two correctness-grade: the ASCII word-boundary streaming truncation (`endRelated` omitted the `(?-u)` kinds — both the bug and `endRelated` retired with D37; `(?-u)foo\b` matched a window "food" contradicted — fixed, regression-tested) and D32's silent 24% CSV regression (above). Plus the `run()` split, a swapped javadoc pair in `Plan`, and FQN/blank-line hygiene |
| 1 — bounded backtracker | **Done** | **A latent missed-match bug**: the visited set's generation-wrap clear was sized to the current search, so a small search's wrap stranded stale marks that a different input met 126 searches later as false "already visited" — a real match silently lost. Needed one matcher, mixed input sizes and 254 searches to reproduce: a pipeline's exact shape and no test's, until now. Fixed (whole-array clear), regression-tested. Plus the D26-era javadoc brought to D32 truth, and the redundant MATCH span writes removed. No perf guard needed: pinned-only engine, the differential suite is its exercise |
| 2 — simulation | **Done** | Tier 1's disease found at its next scale: `ThreadList.clear()` runs per input byte and its `int` generation overflows after ~4 GB of input through one matcher, letting stale dedup stamps read as current — same false-"already seen", same potential lost match. Fixed with one predictable compare (guard measured neutral: 1,644 → 1,673 ns/record, probe noise). Plus three truth fixes: the "restored by the closure" seed comment described machinery that does not exist, the suspendability claim now says what shipped (re-run on growth) versus what the property enables, and `assertionHolds` credits all three borrowing engines. `Closures` reviewed clean |
| 3 — flat fancy | **Done** | No correctness findings — the audit's first clean tier on that axis. The backreference comparison (exact walk, folded walk, JDK folding rules) existed twice, here and in the tree engine: extracted to one shared `Backrefs`, because duplicated folding semantics is how differential bugs get born. Guarded fork-per-side: FANCY 216 → 181 ns/record (the smaller method inlines better), TREE within probe jitter. Class doc caught up with D31 (fallback role) and with the capture-aware journalling its own text predated; the lazy CLASS_STAR edge condition got its intent named |
| 4 — tree | **Done** | Clean on correctness — the structural re-verification (Loop locals, lookbehind context stack, `requireEnd` clearing, snapshot-pool pairing, budget coverage) all held, as the differential suite that policed it from birth suggested it would. Two rot items: the class doc still introduced the *primary engine for most of the corpus* as "experimental… never selected by the compiler" (two decisions stale), and `StarClass` built a duplicate of its item's ASCII table. Both fixed; guard neutral within probe jitter |
| shared (parser, analysis, API) | **Done** | A systematic rot-sweep (`grep` for stale tier/engine/"come later" claims) found seven lies and one silent approximation. The lies, all fixed: `BytePattern` still said streaming "comes later" days after it shipped, and described D26's engine selection; `Nfa` called itself "the tier 1 representation, executed by PikeVm" when three engines share it; `MatchLimitException` claimed only `FANCY` raises it; `Engine.BACKTRACK` said "chosen per search"; plus three smaller tier-numbering fossils. The approximation: `\Q` inside a character class silently matched a literal Q — now refused, per the parser's own "never a silent approximation" contract. `Analysis`, `Utf8`, `Words`, `CodePointSet`, `Closures` reviewed clean (one recorded leniency: `Utf8.decode` accepts overlong forms, harmless because both sides of every comparison decode identically) |

## 5. Benchmark blind spots — partially closed, the rest recorded

**All three paid off on their first run** — see [05-engine-benchmarks.md §10.4](05-engine-benchmarks.md):
SPARSE retired the Boyer–Moore item, LONG_RECORD turned the tree engine's depth risk into a
number, and UNICODE found a 5× scan-plan gap nobody suspected.

**Standing after the closing runs (`2026-08-19-2144` buffer, `2026-08-19-2320` per-match,
post-restoration): 29 of 30 measured variants ahead outright — all fourteen per-match
categories among them, 1.23× to 2.29× — and the only sub-parity line anywhere is buffer
NETWORK, the diagnosed JMH-conditions artifact that measures at raw parity. The README's
charts render from these two runs via `../../tools/render-scoreboard.py`.**

**Standing after the 2026-08-20 round: no engine deficit remains anywhere.** NETWORK — the
scoreboard's last behind-line — measures at parity under controlled fork-per-side comparison;
its 0.84× exists only inside JMH's harness conditions and is recorded as such above. On the
JMH scoreboard's own terms it stays the one sub-parity row, honestly footnoted. Everything
else is at parity or ahead, most of it well ahead.

The 2026-08-19 audit observed that every workload was dense-match, short-record, pure-ASCII.
Three workloads now close part of that: **SPARSE** (a never-matching pattern — pure scanning
cost, where the JDK's Boyer–Moore should shine and the literal-prefix item above gets its
fair test), **LONG_RECORD** (two-kilobyte records — where the tree engine's recursion depth
becomes measurable), and **UNICODE** (accented text — where D19 priced the Unicode default at
10–20% and no benchmark had ever charged it). Still missing: any workload with catastrophically ambiguous input, which only the budget
tests exercise today. The many-patterns pollution workload landed 2026-09-03 as
`PollutedCorpusBenchmark` (07 Phase 1): it moved NETWORK and KEYVALUE from parity to 2.1× and
found the tree losing 26–39% under pollution — see §1's open row.

**A fourth blind spot, found and closed 2026-08-22: a benchmark can stop running and nothing
notices.** `BranchOrderBenchmark`'s setup guard asserted tier *ordinals*; D32 moved the
simulation from 1 to 2, the guard threw on every run after, and JMH drops a failing benchmark
from its results without ceremony — so every recorded run since simply had no BranchOrder
rows, and the absence was the only evidence. The guard now asserts named engines. The lesson
generalises: a result file proves what ran, not what was supposed to run — when comparing
runs, diff the *row sets* as well as the scores.

## 6. The end-anchor programme — planned 2026-08-24, phased and gated

What stood here as two general-purpose candidate rows (the end-anchored tail window and
reverse matching, recorded 2026-08-20 with prior art from rust regex-automata, .NET
`RightToLeft`, Hyperscan and GNU grep) graduated to a phased plan on 2026-08-24, after Jon
supplied the workload the corpus lacked: Stroom parses text like
`ip address=1.1.1.1 name=bob create time=2026-01-01:00:00:00`, where the key before an `=`
can only be told apart by walking *backwards* from it — `create time` versus `time` is
undecidable going forwards. Stroom DS grew its "reverse" engine feature for exactly this,
because the JDK cannot walk backwards efficiently; that feature stays regardless, but the
workload is real, general, and end-shaped.

Three facts make the programme cheaper than it looks. The dialect already ruled the hard
part out: bare `$` parses to `END_INPUT` — identical to `\z` — and `\Z` ("end, except a
final line terminator") is refused at parse time, so "end-anchored" means exactly one thing
and no JDK final-newline nuance survives to honour. `Analysis.byteLength` already returns
`[min, max]` with an unbounded sentinel, and its javadoc already states the theorem the
tail window needs — candidate starts for a match ending at the cursor are exactly
`[cursor − max, cursor − min]`; it is what makes bounded lookbehind work. And every search
loop already computes `lastSeed = to − minLength` on complete windows: the tail window is
the same argument one anchor stronger, applied to the *first* seed instead of the last.

One scope note, verified against the executor 2026-08-24: the engine module never uses the
regex library's streaming API — no `ByteWindow`, no `NEED_MORE` handling anywhere in it.
`Executor.stream` buffers and refills itself and always calls the complete-array entry, so
every production input is a complete view. Both features below therefore exclude growing
windows outright, at zero production cost. (The streaming surface itself is exercised only
by the library's own tests; whether to retire it is a decision for its own D-number, not
assumed here. Since ruled: D37, executed 2026-08-25 — the surface retired.)

**Phase 0 — `EndAnchoredSearchBenchmark`, the gate and the decision point.** The
failure-shaped twin of `AnchoredSearchBenchmark`, per the 2026-08-20 method note: both
features live on paths the success-path suites never exercise. Rows mined from real shapes,
not invented: the bounded WEBLOG tail `(\d{3}) (\d+)$` run miss- and late-match-shaped
over a large region (what the tail window fixes); the unbounded `([^\\]+)$` filename shape
(what only reverse fixes); and a key=value row shaped like Jon's text above, so the
workload that motivated DS's reverse feature is represented from day one. All three
engines, plus JDK drift rows. Nothing below lands until this exists and has a same-boot
baseline; its numbers decide Phase 3.

*Landed 2026-08-24* (`2026-08-24-1356-aed598c2f4-endanchored-baseline.json`, 22 rows,
fixture-pinned by `EndAnchoredSearchBenchmarkFixtureTest`). Two findings from the landing:
the tree engine cannot run the unbounded filename shape at 256 KiB at all — its step budget
refuses with `MatchLimitException`, so those rows are absent by the engine's own design (the
bounded backtracker's manner of absence from `AnchoredSearchBenchmark`), and the refusal is
itself pinned so the omission cannot go stale. And every row is millisecond-scale with the
engines already at or ahead of the JDK — 90–719 ops/s across the board — which sharpens what
the programme is for: not catching up, but the orders of magnitude that skipping the region
walk entirely would buy. The KV rows' leftmost capture (`"bob create time"`, JDK-agreed) is
pinned so no phase can change what the row measures unnoticed.

**Phase 1 — the trailing-anchor fact.** `Analysis.trailingAnchor(Hir)`, the mirror of the
leading analysis: concat takes the last element's anchor (weakened past empty-matchable
tails), alternation the weakest branch, groups and atomics recurse. Published the way
`leadingAnchor()` is — computed at compile time, carried on the compiled artifacts, the
parser its single source, no caller ever sniffing pattern text. Zero hot-path cost. It has
a second customer before any engine changes: `Executor.stream` today *warns* when a match
consumes a full window with input unread — for an `END_INPUT`-anchored pattern that match
is guaranteed to mean "matched end of buffer, not end of stream", and the fact lets the
executor upgrade that warning to an error for exactly those patterns, with the leading
anchor's D35 usage as the precedent.

*Landed 2026-08-24.* `Analysis.trailingAnchor` walks the normalised parse; the published
`BytePattern.trailingAnchor()` is computed once there, so every artifact agrees by
construction (`TrailingAnchorTest` pins it on all four forced engines). The pins include
the one deliberate conservatism, with its reason spelled out: the backward concat walk
looks only through pure zero-width tails, because looking through an empty-capable
consumer — sound for `END_INPUT`, where nothing is consumable past the region end — is
provably unsound for `END_LINE` (`(?m)a$
?` can end just past a line end); one rule, the
safe direction. Unlike its mirror, the analysis does look through alternations — the
weakest branch governs. And the executor customer landed with it: an end-anchored match
consuming a full buffer with input unread is now an error naming the certainty, not a
warning hedging it (`EngineBehaviourTest.refusesAnEndAnchoredMatchAgainstAFullBuffersEdge`).
No benchmark gate was run, with reason: the fact is computed at compile time, and the
executor branch sits inside the already-cold truncation case — nothing here touches a
measured path.

**Phase 2 — the tail-window jump.** Unanchored search, complete window,
`trailingAnchor == INPUT`, finite `max`: the first candidate start becomes
`max(from, to − max)`. Soundness is `byteLength`'s own theorem — no match ending at `to`
can start earlier, so leftmost-within-the-window is leftmost overall and captures are
untouched. Two constraints from this month's scar tissue: **one site, not five** — R1
existed because five copies of a gate drifted, so the clamp goes in `ByteMatcher` before
engine dispatch (the R1 audit proved it is the engines' only caller), never into each
loop; and **placement is itself benchmark-gated** — `run()` sits on the 2.8 ns
instant-rejection rows and the contextEnd saga priced a single branch there, so whether
the clamp lives in the unanchored branches or precomputes pattern-side is decided by the
gate, both anchored suites either side. Expected shape of the win: a 256 KiB miss stops
scanning the region and inspects a few dozen tail bytes.

*Landed 2026-08-24*, and the gate earned its keep twice on the way. First it convicted the
plan itself: the WEBLOG tail this section had called "bounded" since 2026-08-20 is not —
`\d+` has no maximum — so none of the gate's original six shapes could jump at all, and the
first after-run measured the phase against rows it could never move. The benchmark gained
`BOUNDED_*` rows (`(\d{3}) (\d{1,9})$`, the same shape with the bound stated), and the
fixture now checks every shape's boundedness claim against the published facts
(`BytePattern.maxLength()`, made public for exactly that) instead of trusting a label.
Second, it priced the clamp: one predicted-false branch ahead of the dispatcher
(`ByteMatcher.tailFrom`, the one site) costs −7–9% on the 3–9 ns `anchored_miss`
instant-rejection rows and nothing anywhere real work happens — accepted in the module's
`ISSUES.md`, with the reasoning that no placement escapes the cycle. What it buys: the
bounded rows went from ~520 ops/s to 3.35–5.07M ops/s across all three engines — three to
four orders of magnitude, the JDK control unmoved at ~500 — and `TailWindowTest` pins the
off-by-one edges and every exclusion (line anchors, unbounded, anchored questions, growing
windows). Evidence: `-anchored-p2-{before,after}.json`,
`-endanchored-p2-{before,after}.json`, same boot, adjacent runs.

**Phase 3 — the decision.** Phase 0's unbounded rows, before and after Phase 2, say
whether reverse matching is worth an architecture piece. The corpus alone never justified
it; the key=value row may.

*The numbers are in (2026-08-24), and they say the prize is real.* The unbounded rows did
not move through Phase 2 — they cannot — and sit at 90–700 ops/s: 1.4 to 11 milliseconds
of forward walk per 256 KiB search, on all three engines, with the JDK no better. Their
bounded twin just demonstrated what skipping the walk is worth: three to four orders of
magnitude. The filename shape is the cleanest case for reverse (a backwards walk touches
only the match's own bytes and stops at the first backslash), and the key=value shape is
the DS workload itself. Queued for Jon's ruling: whether Phase 4 proceeds, and if so
whether v1's scope — refuse any pattern whose byte structure is not cleanly reversible,
fall back to the forward scan — is acceptable as the starting line.

**Phase 4 — reverse start-finding, if the numbers say go.** The rust-regex two-pass
design, as an *outer construct, not a tier*: no new `Engine` value, no change to the
proven machinery. A reverse-compiled, capture-stripped program answers one question — the
smallest start of a match ending at `to` — by a single scan walking the original bytes
right-to-left (the input is never copied or physically reversed), seeded once at `to`;
for `([^\\]+)$` it touches only the match's own bytes and stops at the first backslash,
where the forward cost is seeding a candidate at every position in the region. Captures
then come from the machinery already trusted: an ordinary forward *anchored* attempt at
the found start. Selection is by published facts (complete window, unanchored search,
`trailingAnchor == INPUT`, unbounded max, non-fancy — lookarounds and backreferences fall
back), and `explain()` names the strategy the way it names tiers. Known sharp edges, named
now so they are pinned rather than discovered: reversal must reach *byte* level — a
multi-byte character's bytes must be consumed in reverse order, and non-ASCII class
structures are the part rust-regex considers genuinely hard, so v1 refuses any pattern
whose byte structure is not cleanly reversible and falls back to the normal scan; a
*wrong* proposed start is harmless (the forward verify fails and the search falls back —
it is never trusted as NO_MATCH), but a *missed* start is a silent wrong answer, so the
differential suite gains a generator biased toward end-anchored shapes before the strategy
is trusted; and word-boundary asserts read the byte on the far side, which in reverse is
the byte *before* the scan position — the same contextEnd-class window edge R1 just
harmonised, to be handled with the same care.

*Phase 4 ruled go by Jon (2026-08-24), ASCII-first v1 scope, and landed the same day.*
`Reverse` reverses the HIR (concat order, literal byte order — exact bytes reverse
trivially even mid-UTF-8; it is classes that cannot) and compiles it through the ordinary
`NfaCompiler`, captures stripped; `ReverseScanner` is the Pike discipline run right to
left, seeded once at the region end, recording the smallest start where MATCH is live that
does not split a character. Assertions keep their original kinds — their predicates are
positional and the reversed walk visits the same positions. Selection is compile-time
(`BytePattern.reverseProgram`: END_INPUT tail, unbounded max, not input-anchored in front,
cleanly reversible) and `explain()` names the strategy; `ByteMatcher.dispatch` folds it
under the same one `endgame` branch the tail window already paid for, so the per-match tax
did not grow. The v1 refusal rule surfaced one honest scope note the day it landed: `\d`
and `\S` are Unicode classes — neither ASCII-only nor containing every non-ASCII code
point — so the corpus's `(\d{3}) (\d+)$` spelling stays unaccelerated while the ASCII
spellings qualify; sharpening the licence to Unicode classes is the recorded candidate.
Two designed dividends confirmed themselves: the finder made the unbounded filename shape
feasible on the tree engine — the step budget no longer sees the forward walk, and the
fixture pin guarding that engine's refusal fired exactly as designed, so the benchmark's
tree rows now cover every shape (a row-set comparability note); and a proposed start the
forward attempt refuses falls back to the plain scan, so only a finder miss is trusted —
warranted by `ReverseTest`'s differential sweep (six qualified shapes × adversarial edges
× 2,400 randomised inputs, misses included, all agreeing with the JDK).

The gate then convicted the first integration, in the dispatcher split's own words: routing
every match through a grown `dispatch` method cost the scan-plan rows — the one path whose
search lives inside `ByteMatcher` — up to −46%, tree and simulate untouched. The endgame
body moved out of line behind the one inline-tested flag and the rows returned to their
Phase 2 marks. The win, measured before the fix and unchanged by it: the unbounded
filename and key=value rows moved by 4,700× to 528,000× — misses at ~50M ops/s, one
backwards walk that dies at the first excluded byte — while the bounded rows kept their
tail window, the Unicode-spelled WEBLOG rows sat still exactly as v1 scoped, and the JDK
controls moved only within their documented ms-row noise.

One watch item rides to the first evening run: by late afternoon the box had grown noisy —
JDK drift rows at ±15%, unchanged-path rows moving both directions — and `scan_plan
line_miss` recorded a 7.4k mode twice, below even its documented 9.3–14.5k same-commit
range. The endgame branch is not in that row's path (line-anchored, `endgame` false, the
entry shape unchanged in count), so the verdict is deferred to a quiet slot per the
evening policy rather than chased through the noise.

**Phase 5 — `ReverseSuffix`, deferred and recorded.** Scan forward for a distinguishing
suffix literal (the `=` in key=value), verify the key backwards from each hit — the
strategy closest to how DS's reverse feature is actually used, and the reason the
key=value benchmark row exists. It needs Phase 4's reverse program as a prerequisite, so
it stays a recorded row until that exists and a workload asks. Prior art:
rust regex-automata's `ReverseSuffix`, Hyperscan's suffix acceleration.

The corpus today still contains no pattern that would move — win_sec's `(.+)$` fields are
all `(?m)` line-ends (`END_LINE` earns nothing from either feature: line ends are
everywhere), and `filename_extract` is cold. That is why Phase 0 is the gate and not a
formality: the general-purpose argument earns the programme its phases; only its numbers
earn each phase a change.
