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
| 6 | `32e7840450` | 2026-09-08 | Design 29 phase 3, the body's ops | `apache_httpd`'s row: nine sites, the largest of them the regex replace holding its matcher and its parsed replacement, at 209 replaces per record. Design 10 §2 measured change 3 moving this workload only 12% and blamed transforms working in `String`, which this does not change — so a small move here is the expected result, and a large one would mean the blame was wrong. |

*Rows to add as they land: phase 4 (conditions), phase 5 (the sinks and the prologue).*

## The reading, 2026-09-08 evening

All seven points run in one sweep, 18:37 to 20:01, full annotations, box idle. The JSONs are
here, `2026-09-08-18xx` and `-19xx`.

**The drift envelope first, because it decides what the rest means.** `a9ca4f2853` was also run
at 12:49 that day, so the same commit was measured twice, five and a half hours apart. Run rows
moved between −1.1% and **+3.3%**; compile rows between −1.0% and +1.9%. That is the box, not
the code. So nothing under about three per cent on a run row is evidence, and the comparison
script's own flags are not either — it called two rows on identical code.

**Run rows, phase 3 against the floor.** Nothing regressed.

| Workload | vs floor | Reading |
|---|---|---|
| `csv_header` | −0.8% | **The residue is closed.** −2.9% at design 25, −1.5% at E43, now inside the drift envelope. Which phase closed it cannot be said: every step between is smaller than the noise. |
| `ausearch` | +12.4% | Real — well outside drift — but the per-phase path wanders (+5.7, +7.5, +3.9, +12.4) and this row's own error bars reach ±5.6%. Direction trustworthy, size not. |
| `win_sec_strict`, `win_sec_xml` | +3.1%, +2.9% | At the edge of the envelope. Weakly positive at best. |
| everything else | within ±1.0% | Nothing. |

**Design 25's cost is fully recovered**, which is what the arc was built to answer.

**Phase 2 bought nothing measurable.** Its own named workloads — `progressive` and
`regex_lines` — sit at −1.0% and −0.9% against the floor, both inside the envelope. The
prediction written into this file when the point was added was that phase 2 would be the first
phase to *win* rather than recover. It did not. Removing a per-character decoder cascade, a
per-attempt encode, a pattern lookup and a matcher allocation moved these workloads by less than
the box's own noise.

**Phase 3 moved `apache_httpd` +0.7%**, inside the envelope — the small move this file predicted,
on the grounds that design 10 §2 blamed that workload on transforms working in `String`, which
phase 3 does not change. The prediction held, weakly.

**The compile rows fell, and it does not matter.** `progressive` −9.9% and `csv_header` −8.8%
against the floor, both far outside drift and both traceable to phases 2 and 3 — the tables,
the encoded literals and the per-node matchers are compile-time work that did not exist before.
In absolute terms those two configurations compile in 0.36 µs and 1.40 µs, and now take 0.40 µs
and 1.53 µs: forty and a hundred and thirty nanoseconds, once per configuration. The five
workloads whose compilation actually costs something — 2.0 to 2.9 **milliseconds** — all moved
within ±2%, which is to say not at all. A percentage on a sub-microsecond row is not a cost.

### The interleave, 20:38 to 20:59 — phase 2 is a 4% regression on `progressive`

Six rounds, order alternating within each, `5dfdabc46f` against `d504945cd5`:

| Workload | r1 | r2 | r3 | r4 | r5 | r6 | mean | signs |
|---|---|---|---|---|---|---|---|---|
| `progressive` | −4.5% | −3.8% | −4.7% | −4.3% | −4.7% | −3.0% | **−4.2%** | `------` |
| `regex_lines` | +0.4% | +0.3% | −2.1% | +0.9% | +1.5% | +0.7% | +0.3% | `++-+++` |

Six signs out of six, and the two distributions do not overlap: phase 1's slowest round is
357.22 ops/s, phase 2's fastest is 349.74. This is not drift.

It also agrees with the sweep, which had said the same thing where I dismissed it: the sweep's
phase-1-to-phase-2 step on `progressive` was −4.1% against the interleave's −4.2%. The reading
above called phase 2 "no gain this benchmark can see". That was too kind by half — the correct
statement is that phase 2 costs about four per cent on the workload it was written for.

**Why, and it is not a mystery.** `progressive` runs
`fixtures/projects/progressive_len_records`, whose step vocabulary is exactly two kinds:
`ReadVarint` and `TakeBytes`. Its source is UTF-8. So of phase 2's five precomputed answers —
the encoded tag, the encoded needle, the pattern and matcher, the byte table, the resolved
decoder — **this workload uses none of them.** There is no tag, no take-until, no regex step, no
take-while, and nothing decodes a character. What it does get is the new indirection: a
`forEncoding` call with its null check and reference comparison on every match attempt, two
accessor hops to reach the steps and the reading, and the encoding fetched through a record
field where it used to be a parameter. On a match this small — a varint and a length-prefixed
take — that is measurable, and it measured.

`regex_lines` is a regex *template* match, not a regex *step*, so phase 2 does not touch its hot
path either. Its `++-+++` is noise, as expected.

**The finding is about the benchmark, not only the change.** Neither of the two workloads design
29 named for phase 2 exercises what phase 2 optimised. The benchmark's own javadoc calls
`progressive` "the step interpreter alone", which is true and misleading: it is the *binary*
step interpreter, and every one of phase 2's precomputed answers is for the *text* vocabulary.
The suite has no row where a tag, a take-until, a take-while or a regex step runs hot, so the
work phase 2 did is unmeasured by construction while its overhead is measured.

### The probe, 21:0x — three hypotheses, all wrong, cause not found

Asked the compiler rather than the benchmark (E43's rule). `-XX:+PrintInlining` on
`progressive` at both commits, then `-prof gc`:

- **The new indirection is free.** `CompiledMatch$Progressive::forEncoding` is `inline (hot)`,
  and `Compilation::steps`, `Compilation::decoding` and `Decoding::encoding` are all reported as
  `accessor` — field reads. The explanation written above, that the `forEncoding` call and its
  accessor hops cost the four per cent, is **wrong**.
- **There is no inlining cliff.** `Steps::match` is 336 bytes at phase 1 and 337 at phase 2, and
  fails to inline into `Level::match` identically at both. `Steps::step` is 1579 and 1646 bytes,
  and fails identically at both. `Level::match` is `inline (hot)` at both. The hot-path decisions
  are the same.
- **Allocation is identical.** `gc.alloc.rate.norm` is 16,203,435 B/op at phase 1 and
  16,203,436 B/op at phase 2 — a difference of one byte across six forks.

So: the same allocations, the same inlining, the same work, four per cent slower, six rounds out
of six. What is left is the kind of cause that needs `perf` or `PrintAssembly` — code layout, or
the type-switch call site in a hot 1646-byte method that grew by 67 bytes. That is a large
undertaking for a four per cent regression on a workload that exercises none of the feature.

**The cause is recorded as not found.** Three hypotheses were tested and refuted, which is worth
more than a fourth that was not tested at all.

*An aside, since the measurement produced it:* this workload **allocates 16.2 MB per op**,
sixty-three times the 256 KiB it reads — churn, not footprint. The figure is
`gc.alloc.rate.norm`, bytes allocated per operation, almost all of them dying in the young
generation: 18 to 30 young collections and 14 to 22 ms of GC across roughly three seconds of
measurement, under one per cent. The engine's resident memory is still bounded by its buffer.
Nothing in design 29 addresses that, and no site in its survey is that large.

**What follows.** Two things, and the cheap one first. The suite needs a workload whose steps
are the text vocabulary — a tag, a take-until, a take-while, a regex step — or phase 2 stays
unmeasurable by construction, and phases 4 and 5 will be judged the same way. That is a fixture,
not a benchmark run, and it is the only thing that can settle whether phase 2 pays for itself.
Only after it exists is chasing the four per cent worth the box time: if phase 2 wins clearly
where it applies, the trade is fair and the regression is a footnote; if it wins nowhere, the
phase is cost without return and should be reconsidered rather than tuned.

**The fixture exists, 2026-09-09.** `projects/progressive_text_steps` and the benchmark row
`progressive_text`: ten steps over a key-value log line — a regex step for the timestamp, four
tags, three take-whiles and two take-untils — with a text body light enough that the row reads
as the step interpreter, which is what `progressive` was for on the binary side. It runs 3032
records per op with no messages, so the row does the work rather than skipping it quietly under
`ignore_errors`.

It cannot be run at the earlier points as they stand: `engine-interleave.sh` measures from a
detached worktree per sha, and the fixture does not exist at `5dfdabc46f` or `d504945cd5`. To
read phase 2 by it, apply the fixture into both worktrees — it is test resources plus one
benchmark case, so it patches cleanly — or measure head against a variant that reverts the
precomputation alone. Until one of those runs, phase 2's only evidence is still a workload it
does not touch.

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
