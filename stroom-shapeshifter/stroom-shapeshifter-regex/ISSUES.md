# Open issues — regex module

Everything open against this module, in one place, in the engine `ISSUES.md`'s manner: each
entry says what was seen, where to see it again, and what resolving it would involve. Opened
2026-08-22 by the confirmation pass over the adversarial audit
([design/15-audit-ledger.md](../design/15-audit-ledger.md)), which verified every one of the
audit's regex findings against the tree: of the sixteen listed pending, twelve were fixed by
the audit batches, and what follows is what survives.

Performance candidates are deliberately **not** here — they live in
[design/06-performance-plan.md](../design/06-performance-plan.md) (§6 holds the two open
ones: the end-anchored tail window and reverse matching, both gated behind
`EndAnchoredSearchBenchmark`). Behavioural divergences from `java.util.regex` live in
`KnownDivergenceTest`, which pins the one that remains (captures under doubly-nested
repetition) and the two that turned out to be defects and are now pinned agreements.

**Status** is one of: `open` — decided against nothing yet; `accepted` — a measured cost,
kept knowingly, with the reason.

---

## R1 — The window-edge gate is not one designed thing

**`open` — the audit's one remaining designed change (med).**

`ByteWindow.contextEnd` exists and `ByteMatcher` honours it, but the four engines behind it
still disagree about the bytes past the region end:

- `PikeVm`'s beyond-region continuation clause (`PikeVm.java`, the
  `complete && pos < data.length` gate) consults `data.length`, which on a stream window is
  stale garbage past the fill — exactly what `contextEnd` was added to rule out.
- `Backtracker`, `FancyBacktracker` and `NodeTree` lack the clause entirely, so the five
  copies of the start gate (skip / break-if-anchored / attempt) have drifted into three
  shapes.
- `FancyBacktracker.matchBehind` exempts `regionFrom` from its continuation-byte skip
  inconsistently with the others.

Resolving it means threading `contextEnd` through the four engines and extracting one shared
start-gate helper — as a single designed change, not four patches. Two constraints, both
learned since the finding was filed: these are the hottest paths in the module (the
2026-08-22 investigation measured a single added field store on this path at −9.4% on
`anchored_miss`), so the change does not land without `AnchoredSearchBenchmark` run either
side; and the shared-helper half must respect R-adjacent history — the `assertionHolds`
dedup survived its benchmark trial, but only after one (see the ledger's benchmark-gate
section for the method).

## R2 — `Plan.describe` prints an ellipsis for exactly six branches

**`open` (low).** `Plan.java`: the dispatch-table renderer shows at most six entries and
appends `", ..."` whenever `shown == 6` — including when the table has exactly six, eliding
nothing. Diagnostics-only output; the fix is remembering whether the loop stopped early.

## R3 — Residual fully-qualified names

**`open` (low, style).** `CharClass`, `Utf8`, `NfaCompiler` and `Normalise` still spell
`java.util.*`/`java.nio.*` inline where the rest of the module imports. These four files were
outside the audit's cleaned set; same treatment when next touched.

## R4 — `compileForcingNfa` is deprecated but not yet retired

**`open` (low).** `BytePattern.compileForcingNfa` is `@Deprecated` in favour of
`compileForcing(Engine, ...)` and delegates properly, but two test callers remain
(`DifferentialTest`, `BranchOrderBenchmark`). Migrate them and delete the method.

---

## Accepted costs — measured, kept, and why

**Batch 2's correctness fixes cost nanoseconds on nearly-free operations (`accepted`).**
The stale-byte-window fix adds one field store to `ByteMatcher.match()` setup: ~0.3 ns,
−9.4% on `scan_plan anchored_miss` because that whole operation is 2.8 ns, inside the error
bars everywhere the operation does real work. `PikeVm`'s NEED_MORE edge latch costs −2.3% on
`simulate anchored_hit` and is the fix for detection that was previously unreachable — the
engine was wrong at full speed. No real-workload row pays either cost. Evidence:
`design/benchmarks/2026-08-22-*-anchored-*.json` and the ledger's benchmark-gate section.

**`scan_plan floating_miss` −4.1% is code-layout sensitivity, not a defect (`accepted`).**
The commit charged with it provably does not touch that loop. Recorded rather than chased:
tuning method order against one microbench is a game with no winning move. If the number
matters later, the investigation starts from the ledger's method note — at single-digit
nanoseconds, compare across boots only through a same-boot control at the baseline commit.
