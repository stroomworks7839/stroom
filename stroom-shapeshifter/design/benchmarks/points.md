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
| 6 | `32e7840450` | 2026-09-08 | Design 29 phase 3, the body's ops | `apache_httpd`'s row: nine sites, the largest of them the regex replace holding its matcher and its parsed replacement. (The "209 replaces per record" this row originally carried is corrected below: they are `translate` ops, and this workload runs two regex replaces.) Design 10 §2 measured change 3 moving this workload only 12% and blamed transforms working in `String`, which this does not change — so a small move here is the expected result, and a large one would mean the blame was wrong. |
| 7 | `50203a9d46` | 2026-09-09 | Design 29 phase 5, the sinks and the prologue | The refusals no longer described before they are refused, the namespace scope shared until an element declares, the qualified name split once, and the prologue settled at compile time. `win_sec_xml` is its row and **cannot see it**: that row is about 40% regex and no sink frame appears in a sampled profile at all. A point so the arc is complete, not because this row is expected to move. |
| 8 | `23fc4bc52f` | 2026-09-09 | Design 30's first delivery: the graph stops carrying its linking scaffolding | Two maps off `CompiledProject`, read once at link time and never again. **Nothing reads them at run time, so nothing should move.** It is a point because a change that should move nothing and does is worth knowing about — the constructor does less and the linker does more, so the compile rows are where to look, if anywhere. |
| 9 | `8d0fd1cd65` | 2026-09-09 | Design 30: conditions compiled, the pattern map off the graph | A `matches` test holds its `BytePattern` instead of hashing the pattern's text per evaluation, and `Conditions.evaluate` stops taking the map — so it is no longer threaded into every guard evaluation on every template on every record. **624 evaluations per operation on `apache_httpd` and none anywhere else**, invisible in a sampled profile, so the run rows should not move. Compilation now walks the condition trees, so the compile rows are where a change would show. |
| 20 | `PENDING` | 2026-09-10 | The structural refusal is a `try`/`catch` rather than a `Runnable` | **`element_storm` and `win_sec_xml` are the only rows that can move** — they are the only ones that write structure — and `element_storm`'s allocation is already measured: **−3,455,105 B/op, −8.2%**, which is 153,720 capturing lambdas per operation that escape analysis was not removing. What is *not* known is whether that converts to throughput, and this is the row to find out on: point 19 cost `element_storm` 2.22%, and the two changes are in the same place, so read them together. Everything else should be flat, the **compile** rows included. |
| 19 | `b59473a506` | 2026-09-10 | Design 33 phases 1 and 1b: `Body`'s arms become methods, and its eight parameters become registers | **Two of its four rows are already measured and disagree with each other**, which is why the other seven matter: `log_sessions` +2.35% and `element_storm` −2.22%, six interleaved rounds each, every round agreeing on both signs. The full suite is the check on whether that split is a property of those two workloads or of the change. **`progressive` and `progressive_text` should be flat** — the step interpreter is untouched — and so should every **compile** row, since nothing in the compiler moved. A compile row moving here would be the third instance of point 8's phenomenon and would stop being a coincidence. The interesting rows are `csv_header` and `regex_lines`, which execute bodies heavily and have never been read against this change. |
| 18 | `c2aee70a05` | 2026-09-10 | The graph holds values: the interner moves to the compiler, `Apply.link` takes what it is given, `Replacer` moves to `graph` | **Nothing should move, and this one has a way of being wrong.** It is the third "nothing should move" point today, and unlike 14 and 16 it is not only file moves: `Compiler` was reordered so linking runs before the graph is built, and `Apply.link` stopped deriving `recursiveShadow` per apply and started being handed it. Both are compile-time, so the **compile rows** are where anything would show — and a *drop* there is as interesting as a rise, since the derivation happens once now instead of once per apply site. A run row moving means something is wrong. |
| 17 | `b38197bc13` | 2026-09-10 | Design 32: the encoding is settled before compiling, and the dual reading is gone | **`progressive` and `progressive_text` are the only rows that can move**, and they should: `forEncoding(effective(candidate))` ran on every progressive match — a call, an `Encoding.resolve` and a comparison — and is now a field read. Everything else is structure. Note what will *not* show: the step compiler no longer compiles twice, but it never did for this corpus, because every fixture declares `auto` or `utf-8` and the second reading was only built for a source that was neither. So a compile row moving here would want explaining rather than celebrating. |
| 16 | `d0c22a1d6e` | 2026-09-10 | `engine.compile` split into `graph` and `compile`, the graph stops building itself, and `match` splits four ways | **Nothing should move**, which is why it is a point — the same reason as point 8, which said that and then moved six compile rows coherently upward. This is file moves, eleven widened members and five factories relocated; the JVM sees the same code under different package names. If a run row moves, the explanation is not in this diff. |
| 15 | `c8aa548398` | 2026-09-10 | E45: a `matches` condition sees the value's bytes rather than a decoding of them | **A behaviour change first and a point second**, which is why it is here rather than folded into 14: undecodable bytes now match nothing, as D38 ruled. What it should show is a `String` and an array no longer allocated per evaluation, 624 times per operation on `apache_httpd` and on no other workload — so `apache_httpd`'s run row is the only one that can move, and the rest of the suite is the check that a behaviour change touched nothing else. |
| 14 | `739ecb5f8e` | 2026-09-10 | Design 30 phase 7: a key's index is found by slot, and no engine map is keyed by anything the compiler knew | **Recorded as a control, and as the arc's closing point.** The site it changes runs three times per record on `log_sessions` and nowhere else, so nothing should move — which is worth knowing, because the last change recorded as "nothing should move" (point 8) moved six compile rows coherently upward. The compile rows are where to look: `VarNames` now interns two namespaces. If a run row moves here, something is wrong rather than fast. |
| 13 | `4e04988298` | 2026-09-10 | Design 30 phase 6: the conditions resolve compiled references, and `Refs` is deleted | Recorded with its prediction already refuted, which is the reason to run it properly: it was expected to move nothing (E39's 0.4% to 0.7%) and `apache_httpd` moved about 7% on a targeted reading, because the measurement predicted from covered `Refs` and not the literal operands beside it. So the size of this change is not yet known on any row but that one. The engine also lost a whole class here, so the compile rows and every workload with conditions are both worth reading. |
| 12 | `351fb05d1b` | 2026-09-10 | Design 30 phase 5: a variable's name becomes a slot, and the registry an array | **The largest measured change on this list**, and the one most worth the full suite: +15.9% on `apache_httpd` and +11.9% on `log_sessions` on a targeted reading, which says nothing about the other nine rows. It touches every body, every capture and every condition, so a cost spread thinly is exactly the shape it could hide. Three things to look for: the eight rows the targeted reading did not cover; the **compile** rows, since interning happens while a configuration compiles and `ReferenceCheck` also gained a refusal in point 11; and `ausearch`, whose names come from the data and which measured flat rather than faster — a full-suite reading is the check that flat is what it stayed. |
| 11 | `b1647fbc0e` | 2026-09-10 | Design 30 phase 4: the engine's variables leave the registry for execution frames | The first point on this list with a **measured claim already attached**: +12.1% on `ausearch` and +9.7% on `log_sessions` over four interleaved rounds, which is why it needs the full suite. A targeted reading on four rows cannot see a cost spread thinly across the other seven, and that is exactly what went unseen until design 25 §7 found it. Two things to look for: the eight rows this reading did not touch, and the compile rows, since `ReferenceCheck` gained a refusal that runs over every binding in a configuration. |
| 10 | `b4b61bbb68` | 2026-09-09 | A regex replace is its own instruction, holding its replacer | The narrowest point on the list, and recorded as a control rather than a claim: the same `Replacer` is built at the same moment and called the same number of times, held by a record instead of captured by a lambda. `apache_httpd` was believed to run 209 replaces per record, so if a megamorphic `Transform.function` call site were costing anything, taking one implementation out of it was where that would show. **It runs two** — see the correction below, which is why this point answers less than it was recorded as answering. |

*Design 29 phase 4 is deliberately not a point: it measured and built nothing, so the code at it
is identical to phase 3's. Design 30 phase 3 is not a point for the same reason — it is a
counting, and its result is the section below rather than a commit worth measuring across.*

## What was owed — all of it settled 2026-09-09 evening

Three measurements were outstanding, listed together because they shared a cause: a row that does
not exercise a change measures it at zero. All three ran on the evening of 2026-09-09 and the
readings are below. **Phase 2 is worth +20.3%, phase 5 +35.8%, and the three controls moved
nothing a record does.** What follows is why each was owed, kept because the reasons are the
useful part.

1. **Phase 2 against phase 1, on `progressive_text`.** Points 4 and 5. **Ready to run:** both
   worktrees carry the row as of 2026-09-09, back-patched by
   `bin/engine-backpatch-fixture.sh`, which copies the fixture and adds the benchmark case to an
   older checkout — a row added after a point can still be measured at it. The script rebuilds
   the worktree's test classes too, because `engine-interleave.sh` runs JMH against whatever is
   already compiled and a stale build gives an *empty result table* rather than an error.

   *A hint, not a reading.* Two sequential unwarmed runs put phase 1 at 1130 ops/s and phase 2 at
   1342 — the direction phase 2 predicted for itself and never got to show. Two iterations, no
   interleaving and across runs, which is exactly the shape of evidence this file exists to
   distrust: the same commit has read 1.8% apart on identical code. It is recorded so the
   interleaved run has a prior to confirm or embarrass, and for no other purpose.
2. **Phase 5 on a sink-bound row.** Point 7. The row exists as of 2026-09-09 —
   `element_storm`, one one-pass regex per record and twenty-one structural writes — and it had
   to come with a fix to the harness. Every other fixture writes its XML as *text*, and
   `EngineBenchmark` handed the run a bare `OutputSink` whose `startElement` throws, so **no row
   had ever exercised `XmlByteSink` or `SaxEventSink` at all**. `win_sec_xml` was recorded as
   regex-bound, which was true and beside the point: the sinks were not in its path. A structured
   configuration now gets an `XmlByteSink`, as the harness and the pipeline always did.
3. **Points 8, 9 and 10, as controls.** The cheapest of the three and the least likely to say
   anything: 8 and 9 move work into compilation and neither should touch a run row, and 10
   changes where an object is held rather than what runs. Point 9 was expected to show a compile
   cost on `apache_httpd`; point 10 was the one that could say something about dispatch.

   *Read 2026-09-09.* No run row moved. Point 9's predicted compile cost did not appear —
   `apache_httpd` moved −0.2%. Point 10's dispatch question came back zero. The one thing that
   did move was point 8's compile rows, upward across six workloads, which is the constructor
   doing less. Two of the three predictions were wrong in the direction of "nothing happened",
   which is the cheapest way to be wrong.

The first two are the ones that decide whether design 29 phases 2 and 5 stay as written. The
third is a control.

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

### The probe, 21:17 to 21:21 — three hypotheses, all wrong, cause not found

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

**The probe found no cause, and it was right not to guess one.** Three hypotheses were tested
and refuted, which is worth more than a fourth that was not tested at all. The cause was found
twenty minutes later by removing a candidate rather than by reading the compiler — see below.

*An aside, since the measurement produced it:* this workload **allocates 16.2 MB per op**,
sixty-three times the 256 KiB it reads — churn, not footprint. The figure is
`gc.alloc.rate.norm`, bytes allocated per operation, almost all of them dying in the young
generation: 18 to 30 young collections and 14 to 22 ms of GC across roughly three seconds of
measurement, under one per cent. The engine's resident memory is still bounded by its buffer.
Nothing in design 29 addresses that, and no site in its survey is that large.

### The variant, 21:44 to 21:59 — the cause is the load chain

The probe exonerated the calls, so the next candidate was the *loads behind* them. Phase 1 took
the step list straight off `Progressive` and carried the encoding as a parameter, already in a
register; phase 2 walks `Progressive -> Compilation -> Decoding -> Encoding` on every match
attempt. Inlining removes a call. It cannot remove a dependent load, which is why the probe
could report every hop as an inlined accessor and the cost still be real.

A variant removing exactly that and nothing else — the common reading's steps and decoding
directly on `Progressive`, the mark as the rare branch, the encoding a parameter again —
interleaved against phase 2 over six rounds, `progressive`:

| | r1 | r2 | r3 | r4 | r5 | r6 | mean | signs |
|---|---|---|---|---|---|---|---|---|
| phase 2 | 329.88 | 334.56 | 342.48 | 345.55 | 338.87 | 348.21 | 339.92 | |
| variant | 361.28 | 359.51 | 360.80 | 360.96 | 361.25 | 350.81 | 359.10 | `++++++` |

Six of six, and the distributions do not overlap: phase 2's best round is 348.21, the variant's
worst 350.81. **The regression is the load chain**, and the lesson pairs with E43's: E43 said do
not assume a virtual call costs when the compiler devirtualises it; this says do not assume an
inlined accessor is free when it is a hop in a chain.

One caution about size. The variant's 359.10 sits on phase 1's 361.10, which reads as a full
recovery — but those two numbers come from different runs, and phase 2 itself read 346.08 in the
interleave above and 339.92 here, a 1.8% gap on identical code. The *sign* is interleaved and
sound; "recovers all of it" is a cross-run inference and should be read as "recovers most of it".

### The tidy shape, 22:48 to 23:01 — two hops of the three, and what that costs

The variant was a fudge: `match(..., Decoding, Encoding)` passed two things that must agree, so a
caller could mistag every value in the seam that decides tagging, and `marked()` returned null to
mean "read my own fields instead". Replaced by `match/CompiledSteps`, one flat object per reading
built from the steps and the reading alone, deriving the encoding from it so the two cannot
disagree. That collapses two of the three hops and keeps one, `Progressive -> CompiledSteps`.
Interleaved against the head of the day, `32e7840450`:

| | r1 | r2 | r3 | r4 | r5 | r6 | mean | signs |
|---|---|---|---|---|---|---|---|---|
| head | 342.00 | 344.09 | 343.96 | 347.00 | 340.78 | 345.98 | 343.97 | |
| tidy | 355.61 | 351.90 | 354.59 | 350.59 | 352.47 | 351.36 | 352.75 | `++++++` |

Six of six, non-overlapping, **+2.6%**. Against the fudge's recovery of the whole 4.2% that is
about 1.4% per hop, and it prices the seam: roughly 1.4% is what it costs to have an interface
whose two halves cannot contradict each other. Kept deliberately at `c2ee907c1b` — a wrong tag
is a wrong answer, and 1.4% is not worth buying one. The fudge and the tidy shape were never
measured head to head, so that 1.4% is inferred rather than read.

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

## The reading, 2026-09-09 evening — the two rows that were missing

Both of design 29's unmeasured phases are measured, on rows built the same day for the purpose.
Six interleaved rounds each, order alternating within every round, box idle.

### Phase 2 pays for itself, and the trade is a single reading

`5dfdabc46f` against `d504945cd5`, both rows in one run so the cost and the gain are not two
readings from two nights:

| workload | r1 | r2 | r3 | r4 | r5 | r6 | mean | signs |
|---|---|---|---|---|---|---|---|---|
| `progressive_text` | +16.7% | +20.4% | +22.4% | +20.9% | +20.2% | +21.4% | **+20.3%** | `++++++` |
| `progressive` | −3.1% | −3.4% | −3.2% | −4.9% | −5.3% | −3.3% | **−3.9%** | `------` |

Six of six on both, and neither pair of distributions overlaps: `progressive_text`'s worst phase-2
round is 1354.80 against phase 1's best of 1160.52, and `progressive`'s worst is 341.67 against
363.95. **Phase 2 wins 20.3% on the vocabulary it optimised and costs 3.9% on the one it does
not.**

That settles a question open since 2026-09-08, and it settles it the other way from how it read
then. The phase was recorded as "cost without return" on the strength of two workloads that ran
none of its five precomputed answers. The return was always there; the suite could not see it.
Note also that the 3.9% cost measured here is the *original* phase 2 — these points predate
`c2ee907c1b`, whose `CompiledSteps` recovered 2.6 of it — so what stands at head is roughly a
point and a third against twenty.

### Phase 5 moves the row that can see it, and not the one that cannot

`fe7680e7f2` against `50203a9d46`:

| workload | r1 | r2 | r3 | r4 | r5 | r6 | mean | signs |
|---|---|---|---|---|---|---|---|---|
| `element_storm` | +31.0% | +38.4% | +37.2% | +33.7% | +40.5% | +34.1% | **+35.8%** | `++++++` |
| `win_sec_xml` | +0.8% | −0.0% | −0.6% | −0.0% | +0.5% | −0.5% | **+0.03%** | `+---+-` |

`element_storm` is six of six with no overlap — its worst phase-5 round beats the best round
before it by 31%. `win_sec_xml` moves by three hundredths of a per cent with mixed signs and
overlapping distributions, which is what a row that does not run the code should do.

**The second row is the more useful half of that table.** It is the control: if the sink changes
had somehow moved `win_sec_xml` too, the reading would have been drift or a harness artefact
rather than the sinks. It did not, so the 35.8% is the sinks.

*Files:* `ph2text-r*-{5dfdabc46f,d504945cd5}-run.json`,
`ph5sink-r*-{fe7680e7f2,50203a9d46}-run.json`.

### What this says about the method rather than the code

Two phases were built, gated, audited and recorded as unmeasured or worthless. Both were worth
20% and 36% on the work they actually change. Nothing about either phase changed in between —
what changed is that the suite gained a row that runs the code. Design 29 §9 drew the rule from
the failures; this is the same rule with the sign flipped, and the more convincing half of it:
**a row that does not exercise a change does not measure it at zero, it measures nothing at all,
and the two are indistinguishable in a results table.**

### The controls, 19:23 to 20:27 — points 7 to 10, eleven rows, run and compile

The full suite at four adjacent points, every one run with **head's benchmark class and
fixtures**, so only the engine differs between them. Read as the step from each point to the
next, against the two readings' own error intervals combined.

**No run row moved.** Thirty-three steps; three exceeded their own intervals — `ausearch` −1.9%,
`element_storm` +2.5%, `win_sec` +4.9% — all inside the ±3.3% drift envelope this file measured
on 2026-09-08 except the last, which is barely outside its own ±4.7%. None repeats at another
point. **Points 8, 9 and 10 change nothing a record does, which is what all three predicted of
themselves.**

Two of those three flags are worth naming as a demonstration rather than a finding.
`element_storm` moved +2.5% across point 10 and has no `replace` instruction and no transform of
any kind; `element_storm` moved +2.7% on the compile row across point 9 and has no conditions.
Neither can be causal. A single flagged cell is a coin landing on its edge, and the file's own
rule — take the sign across interleaved rounds — exists because of exactly this.

**Point 8's compile rows moved, upward, coherently.** `progressive` +4.6%, `win_sec` +3.4%,
`log_sessions` +2.7%, `ausearch` +1.7%, `regex_lines` +1.5%, `progressive_text` +1.4% — six rows
in the same direction, which is what taking two map builds out of `CompiledProject`'s constructor
should do. In absolute terms it is 23 nanoseconds on `progressive` and about 90 microseconds on
`win_sec`, once per configuration. The point was recorded as "nothing should move"; nothing a
record does moved, and the thing that was made cheaper got cheaper.

**Point 9's predicted compile cost did not appear.** The row said compilation now walks the
condition trees and `apache_httpd` has the most to walk, so that was where to look.
`apache_httpd`'s compile row moved **−0.2%** from point 8 to point 9, inside noise. Compiling
every condition in the corpus's most condition-heavy configuration costs nothing measurable.

**Point 10 answers a question two rulings settled by argument — or rather, it does not.**
Design 27 ruling 2 and design 29 §4 both reasoned that an interface call over many
implementations is megamorphic and gives up inlining, and used that to keep an interpreter's
`switch`. Point 10 takes one implementation out of `Transform.function`'s call site on
`apache_httpd`, and its run row moved **−1.0%, inside ±1.7%**.

> **Corrected 2026-09-10.** This paragraph said `apache_httpd` "runs 209 replaces per record",
> and it does not. The configuration holds **244 `translate` ops and two `replace` ops**. The 209
> figure is the count of *escaping* instructions — `translate` of `& " < >` into entities, bound
> to `__esc_0` … `__esc_208` — and `translate` does its substitution with `String.replace`, which
> allocates no matcher and never reaches `Replacer`. The number was right about string-level
> replacement work and wrong about the instruction, and every later use of it narrowed it to the
> regex `replace` without rechecking. Found while answering a question about why this
> configuration has 239 distinct variable names; 209 of them are those escape temporaries.
>
> So **point 10 measured almost nothing**, and its result should be read that way: taking one
> implementation out of a call site that runs twice per record cannot say whether that call site
> is expensive. The rulings' *conclusion* — leave the interpreter alone — is untouched, and their
> *mechanism* still has no measurement. The workload that would test it is one with hundreds of
> genuine regex `replace` calls per record, which the corpus does not have.

That the reading survived four rounds of interleaving and a careful write-up says something about
where these mistakes live: not in the measurement, which was fine, but in the sentence that says
what was measured. The counting entry above exists for the same reason.

*Files:* `2026-09-09-19{23,39,55}-*-full.json`, `2026-09-09-2011-b4b61bbb68-full.json`.

## The counting, 2026-09-09 — a measurement with no benchmark in it

Design 30 phase 3 is the first entry here that is not a throughput reading. It is a **count**,
taken by instrumentation that was reverted the same hour, over each workload's own 256 KiB
operation, and it exists because design 29 §9's rule cuts both ways: if a row that does not
exercise a change measures nothing, then the way to avoid building the wrong thing is to count
what a change would touch **before** building it.

| workload | resolutions/op | engine vars | scopes open at resolution | found at innermost |
|---|---|---|---|---|
| `log_sessions` | 246,266 | 25.0% | 1:30% 2:14% 3:45% 4:11% | 83% |
| `apache_httpd` | 143,828 | 5.9% | 1:97% 2:3% | 97% |
| `ausearch` | 86,948 | 72.4% | 1:100% | 100% |
| `win_sec_strict` | 55,707 | 22.8% | 1:98% 2:2% | 98% |
| `element_storm` | 7,320 | 100% | 1:100% | 100% |

It cost an hour and it **retired an exit and reordered the other two**, which no throughput
reading on this page has managed.

**The walk was not the cost.** `VarRegistry.get` searches outwards through a stack of hash maps,
and the 7.2% E44 measured on `apache_httpd` was read as the walk. It is not: four of five rows
resolve essentially everything with one scope open, and even `log_sessions` — five iterations
and a grouping — finds 83% at the innermost. Lexical addressing, which buys the walk, was struck
out on this number alone. Also counted, because it had been raised as the thing that might make
lexical addressing impossible: **no body is ever run at more than one depth**, 0 of 10, 18, 26,
31 and 58. It was possible. It was simply not worth it.

**The engine variables' share is the finding, and it is nothing like uniform.** They are 5.9% of
`apache_httpd` — the row that produced the 7.2% and prompted the whole design — and 72.4% of
`ausearch` and 100% of `element_storm`. On those two the traffic is almost entirely
`__match_count` and `__match_idx`, which `Level` *writes* per counted match. `element_storm`'s
configuration reads neither: all 7,320 of its resolutions are bookkeeping nothing consumes. That
is not a lookup to make faster, it is a lookup to delete, and it is what design 30 phase 4
builds.

**The lesson is the cheap one.** A profile says where the time is; it does not say what shape
the work has. An hour of counting moved the design's first exit from the one worth 5.9% on the
row that prompted it to the one that removes the traffic outright — and no benchmark run would
have said so, because both exits make the same row faster by an amount neither can distinguish
from drift.

## The frame model, 2026-09-10 — where a count's *share* and its *volume* disagree

Design 30 phase 4, four interleaved rounds against `e7c6ed43ba`, order alternated within each
round. A **targeted daytime reading** on four rows, not the full-suite gate: it says what this
phase did to these rows and nothing about the other seven, which is tonight's point.

| workload | median | range | rounds agreeing |
|---|---|---|---|
| `ausearch` | **+12.1%** | +4.7 to +19.4 | 4/4 faster |
| `log_sessions` | **+9.7%** | +4.9 to +12.3 | 4/4 faster |
| `element_storm` | +1.4% | −1.0 to +3.7 | 3/4, inside the ±3.3% envelope |
| `apache_httpd` | +1.7% | −3.2 to +4.3 | 2/4 either way, no sign |

Two results and two non-results, and the non-results are reported as non-results.

**The interesting part is that the counting page above got the ranking wrong with its own
numbers.** The counting ranked these rows by the engine variables' *share* of the resolutions,
which put `element_storm` (100%) first and `log_sessions` (25%) third. Measured, `log_sessions`
gained ten per cent and `element_storm` gained nothing. Multiply the share by the volume — the
same instrumentation, one arithmetic step further — and the prediction is almost exact:

| workload | engine-variable resolutions per operation | measured |
|---|---|---|
| `ausearch` | 62,950 | +12.1% |
| `log_sessions` | 61,566 | +9.7% |
| `win_sec_strict` | 12,701 | not read here |
| `apache_httpd` | 8,485 | no sign |
| `element_storm` | 7,320 | inside the envelope |

Two rows at ~62,000 moved by ~10%; three rows at 7,000–13,000 did not move. `element_storm` is
100% engine variables because it resolves almost nothing at all — 7,320 per operation, a
thirtieth of `log_sessions` — and spends its time on 21 structural writes per record through the
sink instead.

**So the rule the counting entry drew needs its second half.** Counting before building was
right and is what retired an exit. But a share is a ratio, and a ratio cannot say how much work
there is to remove: **rank by the absolute count, and use the share only to say what fraction of
a row's own resolution traffic a change touches.** Design 29 §9's rule was that a row which does
not exercise a change measures nothing; this is its neighbour — a row can exercise a change
completely and still have nothing worth measuring in it.

*Files:* `d30ph4-r{1..4}-e7c6ed43ba-run.json` against `d30ph4-r{1..4}-frames-run.json`.

## Names become slots, 2026-09-10 — and the row that measures nothing measures the box

Design 30 phase 5, six interleaved rounds against `68fb1a592a`. A targeted daytime reading on
five rows; the full suite is the point on the list above.

| workload | map lookups per operation | median | range | rounds |
|---|---|---|---|---|
| `apache_httpd` | 140,892 → 19,440 | **+13.9%** | +13.2 to +15.7 | 6/6 faster |
| `log_sessions` | 252,553 → 8,792 | **+11.8%** | −1.3 to +15.1 | 5/6 faster |
| `win_sec_strict` | 45,023 → 4,872 | +1.3% | +0.9 to +3.7 | 6/6, inside the envelope |
| `ausearch` | 24,022 → 16,310 | −0.4% | −2.3 to +2.2 | flat, against a −0.6% control |
| `element_storm` | 0 → 0 | **control** | −1.8 to +1.5 | flat, as it must be |

`apache_httpd` is the largest gain design 30 has produced, on the row that started it. **The
count predicted both movers by absolute lookups removed** — the ranking that was wrong when read
as a share in phase 4, and right twice since. `win_sec_strict` is recorded as no measurable
change despite six rounds agreeing in sign: six agreeing rounds inside the envelope are still
inside the envelope.

**A row that cannot be affected is worth a slot in the run.** `element_storm` does no registry
work at all — phase 4 took every one of its resolutions into execution frames — so whatever it
reads on a registry change is the box, not the code. That earned its keep immediately. On the
first attempt at this reading it swung **−11.4%** in round one and **+6.2%** in round two, an
eighteen-point spread on a row that cannot move, which is what said the run was worthless and
sent it back to a settled box. On the good run it holds within ±1.8%, and it is what licenses
calling `win_sec_strict` flat and `ausearch` unchanged.

So, beside "count before building" and "rank by the absolute count": **measure a change to a
subsystem alongside a row that does not use that subsystem, in the same run.** Design 30 acquired
such a row by accident and it has now caught one worthless reading and settled two verdicts.

**And measuring caught a defect the suites could not.** The first shape of this phase consulted
the compiled name table and *then* a separate map of names read from the data — two lookups where
the previous code did one, on exactly the names a key-value configuration resolves most.
`ausearch` went from 24,022 lookups per operation to about 30,450 and measured as a regression;
one map instead of two took it to 16,310, below where it started. Every test passed throughout.
It was a benchmark row, read against a control, that said the change was wrong.

**Re-read after the audit**, which changed hot-path code and so invalidated the table above as a
description of what is in the tree. Four more rounds: `apache_httpd` **+15.9%** (4/4, +12.9 to
+19.1), `log_sessions` **+11.9%** (4/4, +9.1 to +16.5), control at −0.2%. Both movers hold, and
the higher medians are not claimed as an improvement — they are inside what the rounds spread.

*Files:* `d30ph5-r{1..6}-{68fb1a592a,slots}-run.json`; `d30ph5fix-r{1..6}-*` for the `ausearch`
re-reading after the double-lookup fix; `d30ph5audit-r{1..4}-*` for the re-read after the audit.

## Tonight's set — 2026-09-10

The list above is the whole arc since design 25 and is not what to run tonight. **Today's work is
points 11 to 18, over one floor:**

```
engine-bench-points.sh full b4b61bbb68 b1647fbc0e 351fb05d1b 4e04988298 739ecb5f8e \
    c8aa548398 d0c22a1d6e b38197bc13 c2aee70a05
```

Nine points is about two and a quarter hours at full-suite fidelity, which is long. **Three of
them — 14, 16 and 18 — are controls that should move nothing**, and if it has to be shortened
they are what to drop, 16 first and then 14. Keep 18 over those two: it is the only one of the
three that is not merely file moves, since it reorders compilation and takes a derivation out of
a per-apply path.

**The floor is point 10, `b4b61bbb68`** — the last state of 2026-09-09 in *code*. The commit
above it, `e7c6ed43ba`, is a record: design text and benchmark JSONs, with an engine identical to
its parent's, so measuring it would spend a quarter of an hour reproducing point 10. That is this
page's own rule about document-only commits, and this is the first time it has decided anything.

Eight points is about two hours at full-suite fidelity. What each is for is in its
row; read as the step from the floor to each point in turn, the four of them are one arc — phase
4 moved `ausearch` and `element_storm`, phase 5 moved `apache_httpd` and `log_sessions`, phase
6's size is unknown on nine of the eleven rows, phase 7 should move nothing, point 15 can move
`apache_httpd` alone or nothing at all, 16 should move nothing anywhere, and 17 should move the
two progressive rows and nothing else, and 18 should move nothing outside the compile rows.

Between the floor and point 14 the engine stopped resolving every name a configuration uses at
run time: `log_sessions` went from 246,266 name resolutions per operation to none at all, and
what is left anywhere is 14,140 on `ausearch` for names that arrive in the data. **That is the
arc to read, and no pair of adjacent points shows it.**

Points 0 to 10 are yesterday's and earlier, already measured at the time, and are kept for
reading rather than for running.

## The reading — the 2026-09-10 set, run 19:08 to 21:33

*Nine points, full suite, one floor. Every point completed; the box was idle throughout and no
build ran during a measurement. JSONs: `2026-09-10-19{08,24,40,56}`, `2026-09-10-20{12,28,44}`,
`2026-09-10-21{01,17}-*-full.json`.*

**The arc, floor to point 18, run rows.** This is the day, and it is the number to keep:

| workload | from the floor | covered by a daytime reading? |
|---|---|---|
| `progressive` | **+35.9%** | no |
| `csv_header` | **+31.3%** | no |
| `apache_httpd` | +25.5% | yes |
| `log_sessions` | +23.4% | yes |
| `ausearch` | +21.1% | yes |
| `regex_lines` | +19.8% | no |
| `progressive_text` | +6.2% | no |
| `win_sec_xml` | +4.7% | no |
| `win_sec` | +4.5% | no |
| `win_sec_strict` | +4.1% | yes |
| `element_storm` | +0.9% | yes |

**(1) The rows nobody was watching hold the two largest gains.** The daytime readings only ever
covered `apache_httpd`, `log_sessions`, `ausearch`, `win_sec_strict` and `element_storm`. Of the
six they never touched, `progressive` gained **+35.9%** and `csv_header` **+31.3%** — more than
any row the work was tuned against. Almost all of it arrives at **point 11**, design 30 phase 4,
in one step: `progressive` +36.7%, `regex_lines` +20.7%, `csv_header` +18.3%. That point's
attached claim was "+12.1% on `ausearch` and +9.7% on `log_sessions`", and the full suite says
`ausearch` was +15.9% and that the frame model was worth **three times more** on a row it never
measured. This is the case for the full suite, made by the suite, and it is the second time it
has been made — design 25 §7 is the first.

**(2) The compile rows moved, downward, and coherently.** Against a compile-row noise band of
about ±1.5% (what `element_storm` and `regex_lines` show across all eight points), three rows are
outside it from the floor: `progressive` **−11.6%**, `log_sessions` **−9.1%**, `csv_header`
**−7.7%**. Compilation got slower where the day added work to it — interning, the two namespaces
in `VarNames`, the `ReferenceCheck` refusal. That is what the points predicted would show there,
and it is the price of the run-row arc above: paid once per configuration, against a pool that
compiles once per encoding (design 32).

**Point 18's own prediction is refuted in direction.** It said the compile rows were where
anything would show and that "a *drop* there is as interesting as a rise, since the derivation
happens once now instead of once per apply site". Compilation got **slower**: `progressive` −5.0,
`csv_header` −3.6, `progressive_text` −2.6, `log_sessions` −1.9 across that one step, four rows
coherently downward and three of them outside the noise band. Reordering `Compiler` so linking
precedes graph construction did not pay for itself at compile time. **This is point 8's
phenomenon a second time** — a change recorded as "nothing should move" moving the compile rows
together — and it is now happened twice, so it is a pattern rather than an incident: *a
structural change to the compiler moves the compile rows even when it changes no arithmetic.*

**(3) `ausearch` is flat where it was predicted flat.** +0.5% across phase 5, +2.5% across phase
6 — both inside what §(4) below establishes as this protocol's resolution. Its +21.1% from the
floor is phase 4's, taken in one step at point 11, which is where the frame model was predicted
to reach it. Nothing here is the shape of the regression it caught in September's phase 5 draft.

**(4) The three controls moved, and that is this set's most useful result.** Points 14, 16 and 18
are file moves and a compile reorder; they cannot change a run row. They did:

| control step | largest run-row move | median run-row move |
|---|---|---|
| 13 → 14 | **4.2%** (`ausearch`) | 0.6% |
| 15 → 16 | **3.1%** (`regex_lines`) | 0.6% |
| 17 → 18 | **2.2%** (`win_sec_strict`) | 0.6% |

So a sequential full-suite reading of this kind resolves about **±4% on a single row**, while
most rows sit at 0.6%. Nothing was wrong with the code at 14, 16 or 18; what moved was the
protocol. That is the empirical version of this page's own rule — *where a difference matters,
interleave rather than trusting the sequence* — and it means **the adjacent-step column is not
readable below about four points, and the arc from the floor is.**

**(5) Point 15's one permitted row moved least.** E45 removes a `String` and an array per
evaluation, 624 times per operation, on `apache_httpd` and nowhere else. `apache_httpd` moved
**+0.6%** across that step while `ausearch` moved +3.3% and `win_sec` −2.1% — rows the change
cannot touch. The honest reading is not "E45 was worth 0.6%": it is that 624 allocations per
operation are **below this protocol's resolution**, and the rows that moved more are the noise
floor of §(4) making itself visible. E45 stands on D38's ruling, which is where it always stood.

**(6) Point 17's two permitted rows also moved least.** `progressive` +0.5% and
`progressive_text` −0.8%, against `win_sec_strict` at +3.4% in the same step. Turning
`forEncoding(effective(candidate))` from a call into a field read on every progressive match is
not visible here. And the compile rows did **not** move at 17 in the way the point warned would
want explaining — `progressive`'s compile went −6.0 to −6.6, inside the band. The prediction that
the second reading was never built for this corpus holds.

**What this set changes about how the next one is run.** Three controls cost about forty-five
minutes of the two and a quarter hours and bought the only thing that makes the rest legible: a
measured resolution. Keep them. But a point whose claim is smaller than four points on one row —
15 and 17 were both such points — cannot be settled this way at all, and should be read with
`PrintInlining` or an interleaved pair instead of being given a slot in the evening set.

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
