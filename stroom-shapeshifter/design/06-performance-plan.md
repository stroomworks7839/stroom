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
| Minimum-length fail-fast | **Done (D32)** | All five engines, complete windows only; share of the batch's gains unattributed |
| Literal runs as one instruction | **Deprioritised by architecture (D31/D32)** | Was aimed at the flat fancy engine, now fallback-only; the tree engine's `ByteSeq` already compares runs whole. Revisit only if the fallback ever shows up in a measurement |
| Literal-prefix skip (Boyer–Moore-ish) | **Deprioritised by evidence** | SPARSE measured 2.80× *ahead* of the JDK without it (`2026-08-19-1601`); revisit only if a sparse workload ever loses |

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

## 4. Benchmark blind spots — partially closed, the rest recorded

**All three paid off on their first run** — see [05-engine-benchmarks.md §10.4](05-engine-benchmarks.md):
SPARSE retired the Boyer–Moore item, LONG_RECORD turned the tree engine's depth risk into a
number, and UNICODE found a 5× scan-plan gap nobody suspected.

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
