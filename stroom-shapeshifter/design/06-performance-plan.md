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

## 1. Quick wins — mechanism proven elsewhere in the codebase

| Item | Status | Notes |
|---|---|---|
| Line-anchor start gate in the fancy engine and scan plan | **Done** (§10.1 step 1) | +5.7% on dense matches; its real case is sparse scans |
| The same gate in the bounded backtracker and Pike VM | **Done** (this commit) | The two middle engines were still setting up per position for `^`-anchored patterns |
| Publish the leading anchor (`BytePattern.leadingAnchor()`) | **Done (2026-08-21)** — by user decision: the library may publish the fact, so long as the library is its single source | A dispatching caller that provably-safely asks the anchored question saves the search prologue — the template engine measured that prologue at 2–5% on its anchored-pattern workloads after retiring its text sniff. The accessor reads the same `Analysis.startAnchor` conclusion every compiled artifact carries, so caller and search can never disagree. `LeadingAnchorTest` pins the semantics, including what the sniff got wrong (`^(a|b)` and `(?s)^a` are INPUT; `[^a]b` is NONE) and one honest conservatism: the analysis does not look through alternations, so `^a\|^b` is NONE — the safe direction, pinned for celebration if it ever sharpens |
| Early exit for input-anchored patterns in unanchored search | **Done (2026-08-21)** — `anchored_miss` 46,168× (scan plan), 62,191× (Pike VM), 12,712× (tree): a failed 256 KiB `^`-search fell from 102–584 µs to 3–10 ns, cheaper than the hit, which is the correct end state. Controls flat; realistic-mix bonus: SPARSE 1.91× (error bar ±12% → ±0.4%), network 1.31×, structured/identifiers 1.30×, csv 1.21× | Baseline `2026-08-20-2126`, first cut `2026-08-20-2302`, final `2026-08-21-0037`. The first cut put the exit *inside* the anchor gate and the `line_miss` control convicted it — the tree's line-anchored walk paid 11% for a loop-invariant branch — so the exit moved out: on a complete window an input-anchored pattern clamps the walk's own bound (`lastStart` / the Pike VM's `lastSeed`) to the region start, and the hot loops are byte-identical for everything else. Growing windows keep their walk, so streaming is unchanged by construction. Watch items, both within the JDK-rows' own 0.86–1.07× drift envelope: tree FANCY_LOOKAHEAD 0.92× (its JDK pair moved +7% the other way), and scan-plan `line_miss` 2.5× *faster* — unexplained by the mechanism, consistent with this method's documented inlining-cliff sensitivity, noted for the next session rather than claimed. The template engine's caller-side sniff is now retirable |
| Minimum-length fail-fast | **Done, everywhere — restored to the scan plan after its removal proved a mistake** | The full saga, kept as a cautionary tale: shipped unattributed in D32's bundle; removed from the plan path on buffer-CSV evidence (its presence had tipped the grown `run()` over an inlining cliff — the real fix was the dispatcher split); and the removal then cost per-match datetime 43%, caught by the closing full-suite run because the removal itself was guarded on one suite only. Restored on top of the split: datetime 1.22× ahead, per-match network 1.43× ahead (its best ever), buffer CSV a wash. Two lessons, both now method notes: attribute bundles, and guard *both* suites |
| Literal runs as one instruction | **Deprioritised by architecture (D31/D32)** | Was aimed at the flat fancy engine, now fallback-only; the tree engine's `ByteSeq` already compares runs whole. Revisit only if the fallback ever shows up in a measurement |
| Literal-prefix skip (Boyer–Moore-ish) | **Deprioritised by evidence** | SPARSE measured 2.80× *ahead* of the JDK without it (`2026-08-19-1601`); revisit only if a sparse workload ever loses |

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
plan-owned workloads. Guards per touched tier, every batch.

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
| `NETWORK` at ~0.84× on the buffer suite | **Re-diagnosed (2026-08-20): methodology-sensitive, no engine deficit found** | The earlier "distributed cost" reading compared a raw-probe number against a JMH number — cross-methodology, retracted. Measured properly: a synthetic digit-run/separator slope shows our per-pair mechanics *ahead* of the JDK at every size (~15.4 vs ~17 ns/pair), and the exact workload fork-per-side reads **156 vs 160 ns/record — parity**, groups and decode included. The 0.84× is real only within JMH's harness conditions, which flatter the JDK's steady state. The fused-op candidate is withdrawn; no code change is warranted. If the JMH artifact ever matters, that investigation is a harness question, not an engine one |
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
| 0 — scan plan | **Done** | Two correctness-grade: the ASCII word-boundary streaming truncation (`endRelated` omitted the `(?-u)` kinds; `(?-u)foo\b` matched a window "food" contradicted — fixed, regression-tested) and D32's silent 24% CSV regression (above). Plus the `run()` split, a swapped javadoc pair in `Plan`, and FQN/blank-line hygiene |
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
charts render from these two runs via `tools/render-scoreboard.py`.**

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
10–20% and no benchmark had ever charged it). Still missing: a many-hundreds-of-patterns
pollution workload (§3 above), and any workload with catastrophically ambiguous input, which
only the budget tests exercise today.

**A fourth blind spot, found and closed 2026-08-22: a benchmark can stop running and nothing
notices.** `BranchOrderBenchmark`'s setup guard asserted tier *ordinals*; D32 moved the
simulation from 1 to 2, the guard threw on every run after, and JMH drops a failing benchmark
from its results without ceremony — so every recorded run since simply had no BranchOrder
rows, and the absence was the only evidence. The guard now asserts named engines. The lesson
generalises: a result file proves what ran, not what was supposed to run — when comparing
runs, diff the *row sets* as well as the scores.

## 6. General-purpose candidates — learned from other engines, awaiting a workload

The library is general-purpose, so ideas with solid prior art belong on the plan even before a
corpus workload demands them — recorded here so they are not rediscovered, and deliberately
*not* in §1, because no measurement yet says they matter. Each enters the usual way when its
time comes: a failure-shaped benchmark first (the 2026-08-20 method note — both of these live
on paths the success-path suites never exercise), then one change with JMH either side.

| Item | Mechanism | Prior art | Ingredients already here |
|---|---|---|---|
| **End-anchored tail window** ("end sampling") | An `END_INPUT`-anchored pattern with a finite maximum length can only *start* inside `[to − maxLen, to − minLen]` — an unanchored search jumps straight to that window instead of scanning forward from the cursor. `(?m)$` earns nothing (line ends are everywhere): the exact mirror of the leading gate's INPUT/LINE split | The dual of every engine's prefix reasoning; §1's minimum-length fail-fast is the same argument one anchor weaker | The parser already distinguishes `END_INPUT` from `END_LINE`; `Analysis.byteLength` already returns `[min, max]` with an unbounded sentinel. Missing: a trailing-anchor analysis (the mirror of `Nfa.startAnchor()`) and the jump itself |
| **Reverse matching for end-heavy patterns** | For *unbounded* end-anchored patterns (`.*foo\z`), run a reverse-compiled program backwards from the region end — one attempt, no forward scan at all. The cousin covers patterns whose most distinguishing part is their *suffix*: scan for the suffix literal, verify backwards from each hit | rust regex-automata's `ReverseAnchored` and `ReverseSuffix` strategies; .NET's `RightToLeft` matching; Hyperscan's suffix acceleration; GNU grep's backward tricks | None — this needs reverse compilation of the HIR, an architecture piece on the scale of a new engine mode. Recorded as an idea with named prior art; costed only when a workload asks |

The corpus today contains no pattern that would move: win_sec's `(.+)$` fields are all
`(?m)` line-ends, and `filename_extract`'s `([^\\]+)$` is end-anchored but unbounded, cold,
and would need the reverse tier. That is why these are §6 and not §1 — the general-purpose
argument earns them a row; only a measurement earns them a change.

**Planned (2026-08-20, queued behind the early-exit cycle):** `EndAnchoredSearchBenchmark`,
the failure-shaped twin of `AnchoredSearchBenchmark`, mined from the benchmark corpus's own
pattern shapes rather than invented: a bounded end-anchored tail (WEBLOG's `(\d{3}) (\d+)$`
shape) run miss- and late-match-shaped over a large region to put a number on the tail-window
row, and an unbounded `(.*)$` shape to put a number on what only the reverse tier could fix.
Landing the benchmark is the decision point — the rows above graduate to work only if its
numbers say so.
