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

**`scan_plan floating_miss` −4.1% is code-layout sensitivity, not a defect (`accepted`).**
The commit charged with it provably does not touch that loop. Recorded rather than chased:
tuning method order against one microbench is a game with no winning move. If the number
matters later, the investigation starts from the ledger's method note — at single-digit
nanoseconds, compare across boots only through a same-boot control at the baseline commit.
