# Performance plan

The open performance work, recorded after the 2026-08-19 session so that any future session
starts from this list rather than from folklore. Statuses move here as work lands; evidence
citations are the benchmark files in [benchmarks/](benchmarks) and the sections of
[05-engine-benchmarks.md](05-engine-benchmarks.md). The method is fixed and non-negotiable:
one change at a time, a measurement after each, same machine, checked-in results —
the discipline that took FANCY_LOOKAHEAD from 0.15× to parity (§10.1) and that refuted
four confident hypotheses on the way.

## 1. Quick wins — mechanism proven elsewhere in the codebase

| Item | Status | Notes |
|---|---|---|
| Line-anchor start gate in the fancy engine and scan plan | **Done** (§10.1 step 1) | +5.7% on dense matches; its real case is sparse scans |
| The same gate in the bounded backtracker and Pike VM | **Done** (this commit) | The two middle engines were still setting up per position for `^`-anchored patterns |
| Minimum-length fail-fast | Open | JDK trick: compile the minimum byte length, stop attempting when fewer bytes remain. Cheap, broad, small |
| Literal runs as one instruction | Open | `NfaCompiler` emits one `BYTE_RANGE` per literal byte — `ERROR` is five dispatches. Fancy-programs-only, the `CLASS_STAR` pattern |
| Literal-prefix skip (Boyer–Moore-ish) | **Deprioritised by evidence** | SPARSE measured 2.80× *ahead* of the JDK without it (`2026-08-19-1601`); revisit only if a sparse workload ever loses |

## 2. Diagnosis needed — measure before touching anything

| Item | Evidence | Suspects (unverified) |
|---|---|---|
| Per-match short-record gap, 0.5–0.9× vs the JDK | `2026-08-18-1948` per-match categories — **stale**: predates every optimisation of 2026-08-19 | Re-measure first; then per-`find` fixed costs across engines |
| `NETWORK` at 0.79× on the buffer suite | Every full run | D19's residual Unicode-`\d` price on the scan plan |
| `TIER1_ALTERNATION` on the tree engine at 0.69× | `2026-08-19-1409` | No visible sin in the audit; needs the §8 same-pattern-both-engines method |
| Per-match `datetime` at 0.67× | `2026-08-19-1601` | The worst per-match category on either engine; undiagnosed |
| ~~The scan plan on Unicode classes: ~5× off~~ | **Resolved as a misdiagnosis** ([05 §10.5](05-engine-benchmarks.md)) | The workload's pattern was ambiguous and measured the simulation, not the plan. Corrected and re-measured (`2026-08-19-1735`): the plan is **1.24× ahead** of the JDK on accented text. No work to do |
| The tree engine on LONG_RECORD (78 ops/s vs plan 580) and fixedwidth (62k vs 348k) | `2026-08-19-1601` | Not deficits vs the JDK — the plan wins both — but the exact shapes where D30's guard rails belong: recursion depth and bounded-quantifier chains |

## 3. Architecture decisions — D30's gate, not fixes

| Item | What it unlocks |
|---|---|
| Recursion-depth guard for `Engine.TREE` | Long records without `StackOverflowError` — precondition for everything below |
| Budgeted TREE with simulation fallback for ambiguous patterns | The 5.5×/12.5× TIER1 wins in the default path, linear-time promise kept |
| Oniguruma corpus through TREE; pollution at hundreds of patterns | The evidence D30 requires before the tier map is redrawn |

## 4. Benchmark blind spots — partially closed, the rest recorded

**All three paid off on their first run** — see [05-engine-benchmarks.md §10.4](05-engine-benchmarks.md):
SPARSE retired the Boyer–Moore item, LONG_RECORD turned the tree engine's depth risk into a
number, and UNICODE found a 5× scan-plan gap nobody suspected.

The 2026-08-19 audit observed that every workload was dense-match, short-record, pure-ASCII.
Three workloads now close part of that: **SPARSE** (a never-matching pattern — pure scanning
cost, where the JDK's Boyer–Moore should shine and the literal-prefix item above gets its
fair test), **LONG_RECORD** (two-kilobyte records — where the tree engine's recursion depth
becomes measurable), and **UNICODE** (accented text — where D19 priced the Unicode default at
10–20% and no benchmark had ever charged it). Still missing: a many-hundreds-of-patterns
pollution workload (§3 above), and any workload with catastrophically ambiguous input, which
only the budget tests exercise today.
