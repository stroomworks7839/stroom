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
| Literal-prefix skip (Boyer–Moore-ish) | Open | Generalises `firstBytes`. Only pays on sparse scans — measure against the SPARSE workload first |

## 2. Diagnosis needed — measure before touching anything

| Item | Evidence | Suspects (unverified) |
|---|---|---|
| Per-match short-record gap, 0.5–0.9× vs the JDK | `2026-08-18-1948` per-match categories — **stale**: predates every optimisation of 2026-08-19 | Re-measure first; then per-`find` fixed costs across engines |
| `NETWORK` at 0.79× on the buffer suite | Every full run | D19's residual Unicode-`\d` price on the scan plan |
| `TIER1_ALTERNATION` on the tree engine at 0.69× | `2026-08-19-1409` | No visible sin in the audit; needs the §8 same-pattern-both-engines method |

## 3. Architecture decisions — D30's gate, not fixes

| Item | What it unlocks |
|---|---|
| Recursion-depth guard for `Engine.TREE` | Long records without `StackOverflowError` — precondition for everything below |
| Budgeted TREE with simulation fallback for ambiguous patterns | The 5.5×/12.5× TIER1 wins in the default path, linear-time promise kept |
| Oniguruma corpus through TREE; pollution at hundreds of patterns | The evidence D30 requires before the tier map is redrawn |

## 4. Benchmark blind spots — partially closed, the rest recorded

The 2026-08-19 audit observed that every workload was dense-match, short-record, pure-ASCII.
Three workloads now close part of that: **SPARSE** (a never-matching pattern — pure scanning
cost, where the JDK's Boyer–Moore should shine and the literal-prefix item above gets its
fair test), **LONG_RECORD** (two-kilobyte records — where the tree engine's recursion depth
becomes measurable), and **UNICODE** (accented text — where D19 priced the Unicode default at
10–20% and no benchmark had ever charged it). Still missing: a many-hundreds-of-patterns
pollution workload (§3 above), and any workload with catastrophically ambiguous input, which
only the budget tests exercise today.
