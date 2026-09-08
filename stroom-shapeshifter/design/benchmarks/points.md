# Benchmark points — the commits worth measuring across

The engine's throughput story since design 25 is a sequence of small changes, several of which
were measured only against their immediate neighbour and on a subset of rows. That is how a cost
spread thinly across every row went unseen until the full suite found it (design 25 §7, E43).
This file is the list of commits worth reading **together**, at the benchmark's own fidelity,
once design 29's phases are done — so the whole arc is measured once, properly, rather than in
adjacent pairs.

Add a row as each phase lands. Do not delete rows: a point that turned out to matter is worth
keeping, and so is one that did not.

## The points

| # | Commit | Date | What it is | Why it is a point |
|---|---|---|---|---|
| 0 | `a9ca4f2853` | 2026-09-06 | Design 28 built | The floor. Everything below is measured against it, and it is the last state before design 25 began. |
| 1 | `199328dbb7` | 2026-09-07 | D48 removal and D49 rename | Isolates what removing `template_ref` and renaming the numeric kinds cost, which a three-point run once read as a large compile-row gain and was probably drift. |
| 2 | `a46bc6e4ca` | 2026-09-07 | Design 25 complete, phases 1–4 and the write seam | The tagged value, the declaring sink and the compiled capture, together. Where the one-to-four per cent first showed. |
| 3 | `f9bcef57af` | 2026-09-08 | E43, the value's encoding in its class | The one-field UTF-8 variant. Won back most of it; `csv_header` did not recover. |
| 4 | `5dfdabc46f` | 2026-09-08 | Design 29 phase 1, the match loop | Owns `csv_header`'s unexplained two per cent. |
| 5 | `d504945cd5` | 2026-09-08 | Design 29 phase 2, the compiled step | The first phase expected to *win* rather than to recover: a per-character decoder cascade and a per-attempt encode, pattern lookup and matcher allocation all removed. `progressive` and `regex_lines` are its rows; a compile row moving here would be the doubled step tree. |

*Rows to add as they land: phase 3 (the body's ops), phase 4 (conditions), phase 5 (the sinks
and the prologue).*

## Points deliberately not on the list

The intermediate commits of design 25 — its phase 1, 2 and 3 and their audits, and the splitter
change — are measured in that design's own §7 paragraphs against their neighbours. They are
worth re-reading only if the arc above shows something those paragraphs cannot explain, and a
`git log` over the engine's `src` since `a9ca4f2853` lists them. Document-only commits are never
points: several of the shas in this repository's history are records, and the code at them is
identical to their parent's.

## How to run it

The full suite at each point, all eight workloads, run and compile rows, at the benchmark's own
annotations:

```
engine-bench-points.sh full <sha> <sha> ...
```

That builds a detached worktree per point under `/home/dev1/engine-bench/wt-<sha>` and writes
`<date>-<time>-<sha>-full.json`; copy those here. Remove the worktrees afterwards
(`git worktree remove`), and note that the script waits for the box's load to fall before each
run — the readings are worthless if anything else is on it.

**Where a difference matters, interleave rather than trusting the sequence.**
`engine-interleave.sh <tag> <shaA> <shaB> <workloads> <rounds>` alternates the two commits round
by round *and* alternates their order within a round, then take the sign across rounds. Measuring
points one after another lets the box's drift look like a delta, which it did on 2026-09-07; a
fixed order within a round does the same, which it did on 2026-09-08.

**And for a question about dispatch or inlining, ask the compiler, not the benchmark.**
`-XX:+PrintInlining` answers in one run what three rounds can still get wrong (E43).
