# Open issues — regex module

Everything open against this module, in one place, in the engine `ISSUES.md`'s manner: each
entry says what was seen, where to see it again, and what resolving it would involve. Opened
2026-08-22 by the confirmation pass over the adversarial audit
([design/15-audit-ledger.md](../design/15-audit-ledger.md)), which verified every one of the
audit's regex findings against the tree: of the sixteen listed pending, twelve were fixed by
the audit batches, and what follows is what survives.

Performance candidates are deliberately **not** here — they live in
[design/06-performance-plan.md](../design/06-performance-plan.md) (§6 holds the end-anchor
programme: the tail window and reverse matching, planned in phases on 2026-08-24 and gated
behind `EndAnchoredSearchBenchmark`). Behavioural divergences from `java.util.regex` live in
`KnownDivergenceTest`, which pins the one that remains (captures under doubly-nested
repetition) and the two that turned out to be defects and are now pinned agreements.

**Status** is one of: `open` — decided against nothing yet; `accepted` — a measured cost,
kept knowingly, with the reason; `superseded` — an accepted cost whose reasoning a later
measurement overturned, kept in place with the correction above it, because an entry that
was believed and acted on is part of the record.

As of 2026-08-25 one item is `open`: the tail-window machinery's cost on buffer
workloads, found by the first full-suite run since Phase 2 landed and bisected to it. R1
landed with its benchmark gate (accepted costs below), R2 and R3 were fixed outright, and
R4's two callers were migrated to `compileForcing(Engine.SIMULATE, ...)` —
machine-for-machine identical for every affected pattern — and the deprecated method
deleted. What remains in this file is the accepted-cost record and the one open item.

---

## Accepted costs — measured, kept, and why

**The tail-window machinery costs −12–14% on buffer workloads that never jump
(`open`, measured 2026-08-25 — supersedes the accepted entry below).** The nightly
gate's first full-suite run since Phase 2 found buffer CSV at −11.3%, per-match `quoted`
−8.0%, `datetime` −5.0%, with `shapeshifterTree` and every JDK row flat on the same
workloads; an independent re-check pair reproduced it (−12.7%, −4.4%, −3.8%). A
per-commit bisect of the day put the whole step on Phase 2 (`986453c675`): 3,475–3,507
ops/s through R1, R4, Phase 0 and Phase 1, then 3,017, and flat after. CSV is
`^([^,]+),([^,]+),([^,]+),([^,]+)$` under MULTILINE — no END_INPUT anchor, no finite
maximum — so it can never jump. It pays and buys nothing.

The cost is not the branch, and the accepted entry below is wrong about where it lives.
Six variants, measured back to back: stripping both entry points to the pre-Phase-2
`run(from, anchoring)` gives 3,026; the early-return hoist 3,069; the three fields moved
below the mutable ones 3,101; one boolean in place of three fields 3,044; control 3,121.
Removing the machinery from `ByteMatcher` entirely — fields, constructor computation,
`endgameSearch`, entry ternary — restores it exactly: 3,512 ±10 against the pre-Phase-2
3,488 ±13. The discriminator is a pair with identical hot-path bytecode: the strip build
and the fields-out build both compile `match(byte[],int,int,Anchoring)` to 81 bytecodes,
and differ only in three instance fields — 3,026 against 3,512.

`PrintInlining` across a fast build and a slow one returns the same verdict for every
method in the hot chain — `run` (110 bytes), `searchPlan` (204), `attempt` (64),
`PlanRunner::run` (992) — so no inlining decision flips; only `match` itself grows 82→114
bytecodes when the ternary is present, and the strip build shows that growth is not the
cost either. What the per-fork numbers show is a mode change: before Phase 2, 3483 3470
3507 3481 3499; machinery out, 3511 3516 3499 3511 3523; control, 3046 3187 3022 3184
3168 — two clusters, the higher one still short of clean. It is R1's ninth argument
again, one layer down: instance state this time rather than a stack slot.

Not a revert — the jump still buys the bounded end-anchored rows three to four orders of
magnitude. A fix is a design question: get the end-anchored state off the common
matcher's shape, which is the option the entry below dismissed as "per-pattern code
selection". Evidence: `2026-08-24-2117`/`-2252` (the gate pair), `2026-08-25-01xx`
(re-checks), `2026-08-25-07xx/08xx-*-bisect-csv.json` (the bisect),
`2026-08-25-09xx-*-variant-*.json` (the six variants).

*D37's datapoint (2026-08-25 evening):* the streaming retirement removed a field from
`ByteMatcher` and an argument from every engine signature, and the coin flipped again —
default-dispatch CSV −6.0%, DATETIME −5.1%, while forced-tree CSV moved +13.8% on the
identical workload (`-d37-before-corpus`/`-d37-after-corpus`). Same engine underneath,
opposite movement, the difference being only the dispatch wrapper: consistent with this
entry's mechanism and further evidence it is the class's shape, not any one member. Two
follow-ups also recorded from the same gate: the simulation's 256 KiB full-scan rows
(`simulate floating_miss`/`line_miss`) paid −8.2–8.6%, with the branch reshape and the
arity both cleared by probe (`-d37-pike-branchshape-probe`, `-d37-pike-dummyarg-probe`)
— D21 alignment disease by elimination, R1's precedent, recorded not chased.

**The tail-window jump costs one cycle per match on patterns that never jump
(`superseded`, 2026-08-24).** *Kept as recorded: what follows was true of the rows it
measured and wrong in its reach — the sentence "invisible everywhere real work happens"
was written from a gate that ran the anchored and end-anchored suites and never re-ran
`CorpusBenchmark`. D32's lesson, arriving a third time: confirm the tiers a change
touched, not the workloads it aimed at.* §6 Phase 2's clamp is a single guarded check
ahead of the engine dispatcher (`ByteMatcher.tailFrom`); for every pattern without an
END_INPUT anchor and a finite maximum it is one predicted-false branch, which is invisible
everywhere real work happens and −7–9% on `anchored_miss` — rows whose whole operation is
a 3–9 ns instant rejection. No cheaper placement exists: the engine prologues would pay
the same cycle in the same rows, and per-pattern code selection is not this codebase. What
the cycle buys: the bounded end-anchored rows moved from ~520 ops/s to 3.4–5.2M ops/s —
three to four orders of magnitude — while the JDK control stood still. Evidence:
`2026-08-24-14xx-*-anchored-p2-{before,after}.json` and
`-endanchored-p2-{before,after}.json`, same boot, adjacent runs.

**R1 (gate harmonisation, resolved 2026-08-24) carries −2–4% on the tree engine's
nearly-free rows (`accepted`).** The window-edge gate is now one designed thing:
`Utf8.splitsCharacter` is the single start gate all four engines ask, `contextEnd` bounds
the beyond-region probe everywhere `data.length` used to be consulted, and the lookbehind
gates lost their inconsistent `regionFrom` exemption. The cost is the delivery mechanism,
found the hard way: threading `contextEnd` as a ninth `search` argument measured −8.6% on
`simulate line_miss` *with the method body untouched* — the bisect that proved it
(`2026-08-24-082x/083x-*-anchored-r1-bisect-*.json`) pinned the whole regression on the
signature, and on the tree engine the same ninth argument made two forks in five lose an
inlining coin-flip worth −21% each (`-anchored-r1-param-engines-after.json`). Binding
`contextEnd` as engine state instead (`setContextEnd`, set beside each `search` call)
restored every real-workload row to baseline and left a deterministic store cost on the
tree engine's instant-rejection rows only: −2.3% on `anchored_hit` (14.5 ns/op), −4.3% on
`anchored_miss` (7.9 ns/op) — ~0.35 ns, one field store, uniform across forks. Evidence:
`2026-08-24-0801-*-anchored-r1-baseline.json` against
`2026-08-24-0852-*-anchored-r1-after.json`, same boot, same commit underneath.

**Batch 2's correctness fixes cost nanoseconds on nearly-free operations (`accepted`).**
The stale-byte-window fix adds one field store to `ByteMatcher.match()` setup: ~0.3 ns,
−9.4% on `scan_plan anchored_miss` because that whole operation is 2.8 ns, inside the error
bars everywhere the operation does real work. `PikeVm`'s NEED_MORE edge latch costs −2.3% on
`simulate anchored_hit` and is the fix for detection that was previously unreachable — the
engine was wrong at full speed. No real-workload row pays either cost. Evidence:
`design/benchmarks/2026-08-22-*-anchored-*.json` and the ledger's benchmark-gate section.
*(D37 note, 2026-08-25: the `PikeVm` NEED_MORE edge latch was deleted with the streaming
surface, so its −2.3% no longer accrues; the stale-byte-window store survives as the
contextEnd bind.)*

**`scan_plan floating_miss` −4.1% is code-layout sensitivity, not a defect (`accepted`).**
The commit charged with it provably does not touch that loop. Recorded rather than chased:
tuning method order against one microbench is a game with no winning move. If the number
matters later, the investigation starts from the ledger's method note — at single-digit
nanoseconds, compare across boots only through a same-boot control at the baseline commit.

**The D37 audit's measure-first follow-ups (`open`, 2026-08-25).** The eight-angle audit
of the streaming retirement cleared the diff of correctness defects (line-by-line: none;
every fold's `!complete` guard verified) and left a list of candidate simplifications in
the hot loops, none applied because each changes a measured method's shape:

- ~~The `at == to` / `start == regionTo` disjunct in all four first-byte gates~~ —
  **landed 2026-08-26** as three single-engine commits with per-commit overnight gates:
  scan-plan `floating_miss` +25.7%, tree `floating_miss` +50.3% and `anchored_miss`
  +11.5%, real-workload weblog +8.0% / structured +5.0%; costs where the coin lives —
  tree `anchored_hit` −5.4%, tree `BOUNDED_*` −5.5/−7.5%, simulate `line_miss` −7.7%
  (the row's fourth ±8% flip this week). Bracketing full pair
  `2026-08-26-0039`/`-0236-*-edge-*-full.json`; per-commit files `*-edge-<sha>-*.json`.
  The tree's hit trade **ruled kept by Jon (2026-08-26)**: the wins are mechanism, the
  costs are the layout coin, and real workloads are miss-dominated.
- The anchor gates' `at < to &&` exemption survives its deleted reason (the edge
  iteration's bookkeeping); dropping it only forces one doomed attempt fewer on
  `minLength == 0` patterns.
- `PikeVm`: the `pos > to ||` exit disjunct is subsumed by `pos > lastSeed`;
  `canStartAt`'s `pos < to &&` is constant-true; BYTE_RANGE's `value >= 0 &&` is subsumed
  by the range compare (BYTE_CLASS/BYTE_DISPATCH must keep theirs — −1 would index a
  table).
- `PlanRunner` MATCH_LITERAL still compares bytes of a literal that cannot fit before
  failing; an up-front length check is simpler and skips the doomed loop.
- `contextEnd` is now constant-per-call equal to `data.length` on every path, so the
  per-engine field, five binding stores, and `ReverseScanner`'s fifth argument plumb a
  value the engines could read off `data` — **but deleting the seam forecloses the
  tighter-context bound below, so it needs the ruling first.**
- The engines' int-end returns feed nothing but `end >= 0` (every engine already publishes
  the end through `slots[1]`), so `search` could return boolean; `Backrefs.TRUNCATED` is
  indistinguishable from `MISMATCH` at both call sites and could collapse.

**A complete view with a clipped context is inexpressible (`open` — needs a ruling,
2026-08-25).** Both surviving entries bind `contextEnd = data.length`; the deleted window
entry was the only way to pin the context at a reused buffer's fill point, so the
beyond-region probe can consult stale bytes past the fill (a stale continuation byte at
`data[to]` falsely vetoes a legal match at the region end). The executor's records rarely
abut the fill, and pre-D37 array-entry callers had the same exposure — D37 removed the
*expression* of the tighter bound, not the safety of existing callers. Options if ruled
worth fixing: a `contextEnd` overload on the five-argument entry, or the
constant-propagation above in the opposite direction. Recorded by the D37 audit's
removed-behavior angle.
