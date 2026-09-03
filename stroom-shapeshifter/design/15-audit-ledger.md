# The adversarial audit ledger

Status: working document, opened 2026-08-21. The audit's brief: correctness, hygiene,
javadoc, clarity, elegance, porting defects and inaccuracies — everything reading as
deliberate blank-page Java, no porting artefacts, performance/issue comments kept.
Eight parallel auditors swept all 91 main-source files (~19k lines); every finding below
was re-verified against the code before being fixed or listed. Pinned behaviours
(ISSUES.md E-entries, KnownDivergenceTest, design rulings) were excluded by brief.

Each item: `[status]` file — claim (severity). Statuses: **fixed** (with batch),
**pending** (verified, awaiting fix), **decision** (needs a ruling, not a fix),
**rejected** (did not survive verification — none yet).

## Fixed in batch 1 (`751b041a3e`) — engine facade + text

- [fixed] PatternInfo — text-sniff scan crashed on patterns ending `(?<` (reproduced); replaced by BytePattern.groupNames() accessor; groupCount component dropped; catch narrowed to PatternCompileException (high/med).
- [fixed] Shapeshifter.run javadoc — promised pre-E13 chunked behaviour (high).
- [fixed] Encoding — dead codeUnitSize deleted; charset resolved once and cached; charset() null contract corrected; ASCII pass-through documented as deliberate; fallback-chain approximation documented, E22 opened (med/low).
- [fixed] Instrument — UNLOCATABLE halving rationale, normalised-capture-bytes note, missing @param (low).
- [fixed] Message/package-info — port-era clause trimmed; literal backticks → {@code} (low).

## Fixed in batch 2 (`0b75bccbd4`) — high-severity correctness

- [fixed] exec/Executor.call() — parameters wrote through the pushed scope to the global registry when named like any capture; now resolves args in caller scope, then shadows arg+param names; regression pinned (HIGH).
- [fixed] regex/StreamMatcher — infinite loop on empty-matchable patterns at EOS (reproduced); EOF special case swallowed final empty match on empty input; complete-window NEED_MORE now throws; buffer-doubling int overflow (HIGH/low).
- [fixed] regex/ByteWindow+ByteMatcher — contextEnd added: region end vs valid-context end disentangled; streaming windows no longer consult stale bytes past fill; bounded searches keep whole-haystack context (HIGH).
- [fixed] internal/Backtracker+FancyBacktracker+NodeTree — anchored search continued past a continuation-byte start; now breaks, agreeing with PikeVm (HIGH).
- [fixed] internal/PikeVm — NEED_MORE detection was unreachable (threads died one iteration before the check); rebuilt as an edge latch on starved threads and unjudgeable edge starts (HIGH).

## Fixed in batch 3 (`dd438cad83` engine, `6a04a82138` regex) — the five-fixer sweep

Five fixers ran with disjoint file ownership; two were cut off mid-run by a session limit,
but both had already written their edits. The tree compiles and 437 tests pass (188 regex,
249 engine, 0 failures). Verified present in the working tree:

- [fixed] config — transforms refuse a second select (CompiledOp.single, decision 2 taken as the ledger assumed); Translate javadoc corrected to ordered whole-substring replace; Codec "first six" carried by the JDK; regex advance validated (negative at the record, > groupCount at the Compiler); Template.ignoreErrors and Project.bufferSize javadoc corrected; MatchByte clones in and out; params(Map) deleted; Dispatch.ANY documented as shipped; the Rust-crate dialect claims now point at BytePattern; range validation across MatchLimits/Repeat/TakeN/bufferSize/maxDepth/StepRef.Literal; Project.source defaulted.
- [fixed] config/json — checkFields throws on non-object nodes (the strictness hole); version, greater-than and less-than values are now required rather than silently defaulted; compiled_idx removed.
- [fixed] compile — dispatchChecks and notYet javadoc corrected; Apply's redundant disjunct collapsed and select evaluated once; "twelve transform functions" corrected; CompiledMatch.Regex points at LeadingAnchor, keeping the 3.6x citation.
- [fixed] exec — UncheckedIOException mid-run now appends FATAL and returns the accumulated messages; anyLevel delegates to processMatch instead of re-implementing it (with reportSkips carrying DS3's rule); onMatch reports the match span, not the consumption length; the BOM counts toward absolute offsets; zero-advance errors name their offset space; TypedValue.Float renamed Real; Steps honours the template charset for tag and take-until literals (E3) and the effective encoding for AnyChar (E5).
- [fixed] ds3 — the regex advance attribute is read and threaded; unknown attributes are refused rather than dropped; @ references recognised in data attributes.
- [fixed] regex — the embedded-regex named-backreference defect (Parser seeds outer names and offsets by firstGroupIndex); Analysis.byteLength saturates both bounds in long; Nfa's fancy sweep covers CLASS_STAR; the dead nine-arg Nfa constructor removed; UnicodeClasses.build shared with Words and scriptOrBlock renamed script; Words owns the one assertionHolds every engine delegates to; characterBefore bounded against malformed input and delegating to Utf8.decode; MatcherLibrary.names() defensively copied; PatternCompileException made final; supplementary-safe takeUntil/takeThrough overloads.

## Fixed in batch 4 (`dd438cad83`) — the rulings of 2026-08-21

Jon ruled three of the six queued decisions; each is applied with its regression pinned.

- [fixed] ds3 children() — sibling expressions now share one mode and one dispatch, as they already did at the root and inside a group. Decision 6. `003`'s messages golden regenerated: eleven to one, and the surviving message is a true one (the first record's stray `----`, which the configuration's own `^----\n` cannot match once the split has stripped the newline). The output golden did not move a byte, which was the stop condition. E1's closing text corrected — it had called the eleven "true errors" (HIGH).
- [fixed] compile/Compiler — call-template targets and apply-templates template refs are resolved against the templates that exist, and an unresolved name is a ConfigException naming both the referrer and the missing template. Decision 3. Pinned by `EngineBehaviourTest.refusesACallToATemplateThatDoesNotExist` (med).
- [fixed] exec/Splitter — the closing-container trim is gated on the field having opened with one, so an unquoted field that merely ends in the container byte keeps it (`a"b"` stays `a"b"`). Decision 4. Pinned by `EngineBehaviourTest.trimsAClosingContainerOnlyForAFieldThatOpenedWithOne` (med).

Suite after batch 4: 439 tests, 0 failures (188 regex, 251 engine, 3 skipped).

## Fixed in batch 5 (`dd438cad83` engine, `6a04a82138` regex) — the rulings of 2026-08-21, second pass

- [fixed] regex/NodeTree + FancyBacktracker — a lookbehind pinned its body's *window* as well as its end, which got the meaning wrong in both directions: a nested lookahead could never see the text it was looking for, and `$` read the cursor as the end of input and held there. Now only the end is pinned. Decision 5, ruled to match the JDK. Probing thirteen shapes against `java.util.regex` found five diverging, not the one reported — including `(?<!a(?=bc))bc`, which matched when it should not have, and `(?<=a$)b`, which matched when the JDK finds nothing. All thirteen agree now and are pinned in `KnownDivergenceTest.lookbehindBodySeesPastTheCursorAndAgrees`. `recordEdge` is live inside a lookbehind again: a bounded body cannot consume past the cursor, so only a nested lookahead reaches the window edge, and there the contact is real (med).
- [fixed] `KnownDivergenceTest.assertAgrees` — asserted on group 0 before checking a match was found, so a case where both engines agree there is *no* match could not be written. Agreeing on no-match now returns early (low, found by writing the above).
- [fixed] text/Encoding + compile/Compiler — E22 resolved. The approximating fallbacks are gone (`SHIFT_JIS` no longer falls back to windows-31j, `WINDOWS_874` no longer to TIS-620; both are distinct charsets, whereas the surviving multi-name entries are alternative spellings of one). A declared encoding this runtime lacks is now refused at compile time from the template path too — the hole was real: the source path refused it, `encoding=` on a template did not, and would have reached `new String(bytes, null)` at run time (med).

Suite after batch 5: 440 tests, 0 failures (189 regex, 251 engine, 3 skipped).

## Second-pass audit of the regex module's diff (`6a04a82138`, benchmark guard `08459a5c50`)

Jon asked how much the regex library was being touched, and then for an audit of the day's
changes to it before benchmarking them. Every modified regex file was re-read line by line;
the two findings were in the newest work, not the fixers'.

- [fixed] NodeTree + FancyBacktracker — the batch-5 lookbehind comments claimed the nearest
  candidate start is `cursor - max` and that only a nested lookahead can reach past the cursor.
  Both false: starts run from `cursor - min` down to `cursor - max`, and a consuming path from
  any nearer start can overshoot the cursor by up to `max - min` before the end-pin fails it —
  including touching the window edge. The *behaviour* was verified sound against `search()`'s
  return handling (a match with `hitEnd` latched on an incomplete window returns NEED_MORE, so
  the spurious contact costs buffering, never a wrong answer; a negative lookbehind cut off by
  the edge is likewise withheld, not wrongly affirmed). The comments now describe the overshoot
  and why the conservatism is sound (low — but a lying comment on a HIGH-severity fix).
- [fixed] The HIGH embedded-backreference fix was unpinned: FancyTest's `\k<name>` cases are
  standalone patterns, which the unseeded-list defect never touched. Three tests added to
  CombinatorTest: first-name resolution (was refused), later-name resolution (was silently the
  wrong group — pinned via `11,22=11`, which the old resolution would have matched), and an
  embedded name colliding with an outer label (refused).
- [verified] Parser: `countGroups` rewrite correct at the trailing-backslash, `\Q...\E` and
  escaped-`]`-in-class edges; `\x{...}` overflow/malformed handling; `parseBound`; the
  three-arg parse contract consistent with Lowering's clear-and-replace
  (`firstGroupIndex = outerNames.size() - 1` recovers the old seeding exactly).
- [verified] ByteMatcher rewiring: `pinned == SIMULATE` builds no tree, so the removed
  `forced() != SIMULATE` guard was dead; `pinned == BACKTRACK` builds only the backtracker and
  slot sizing reads the NFA directly; the budget change from silent fall-through to refusal is
  confined to pinned runs and documented.
- [verified] PatternCompileException accessor renames have no callers outside the module's own
  tests (all updated); Analysis saturation is sound including the sentinel collision (a max
  that saturates to `UNBOUNDED_LENGTH` makes lookbehind refuse conservatively);
  PikeVm/PlanRunner/Backtracker `assertionHolds` moves are literal; `Words.characterBefore`'s
  bounded walk is correct for four-byte sequences; comb defensive copies, the four-octet ipv4
  and the supplementary `takeUntil`/`takeThrough` overloads are pinned by new tests.
- [fixed] bench/BranchOrderBenchmark — its `@Setup` guard asserted tier *ordinals* and went
  stale at D32 (the simulation moved from 1 to 2), after which the guard threw on every run and
  JMH silently dropped the whole class from the recorded results; the last recorded run has no
  BranchOrder rows. Now asserts named engines (med — a benchmark that silently stopped running).

## The benchmark gate closes on batches 2–5 (2026-08-22)

The full regex suite ran on the audited tree (`2026-08-21-2325-08459a5c50.json`, 159 results
— 27 more than the last recorded run, the recovered BranchOrder rows). Against
`2026-08-21-0037-e61a4317e6.json`, with the untouched `javaRegex` rows as drift control
(+1–2% favourable, so regressions read slightly understated): every real-workload row —
corpus, pattern corpus, baselines, every fancy-tier row the lookbehind change touched — is
neutral to better. Two rows flagged worse, and the investigation that followed disproved two
hypotheses before finding the truth; the chain is recorded because guessing wrong twice in
public is what the corpus is for.

- Hypothesis 1, profile pollution from the `assertionHolds` dedup: restoring a
  PlanRunner-local switch reproduced the dedup's numbers to the digit
  (`-anchored-planrunner-restore.json`). Disproven; the restoration was reverted and the
  dedup stands. `floating_miss`'s pattern (`BEGIN:`) contains no assertions at all, so the
  hypothesis was doubly dead there.
- Hypothesis 2, cross-boot drift: the baseline *code* re-run on the current boot
  (`-e61a4317e6-anchored-bootctl.json`) reproduced the baseline *numbers* — 362M vs 357M on
  `anchored_miss`. Disproven: the regression is real code.
- The bisect (`-0b75bccbd4-anchored-bisect.json`) split it in two:
  - **Batch 2, cost of correctness, accepted.** `scan_plan anchored_miss` −9.4% is one field
    store (`validTo`) added to `match()` setup by the stale-byte-window fix — ~0.3 ns on a
    2.8 ns operation; the same tax on `anchored_hit` at 17 ns is inside the error bars, which
    is exactly what was measured. `simulate anchored_hit` −2.3% is PikeVm's NEED_MORE edge
    latch — the fix for detection that was previously unreachable. Both are the price of
    right answers on paths that were wrong at full speed; neither shows in any real-workload
    row.
  - **`6a04a82138`, layout sensitivity, recorded not chased.** `scan_plan floating_miss`
    −4.1%: the commit does not touch that loop — no semantic change exists in its path — and
    a ~0.02 ns/byte shift from class-body reorganisation is the alignment disease D21
    documented. Chasing it would mean tuning method order against one microbench.
- `CorpusBenchmark tree/QUOTED` −5.4% resolved as noise: the re-run
  (`-quoted-corpus-rerun.json`) overlaps the baseline (7446 ± 452), no mechanism connects the
  QUOTED patterns (no lookbehind among them) to any change, and the first verification of it
  hit the wrong benchmark (PatternCorpus `quoted`, never flagged) — noted so the record shows
  the check was redone right.

Method note for the next reader: at single-digit-nanosecond operations, compare across boots
only through a same-boot control at the baseline commit — the drift-control rows alone said
"clean" while hiding a real 9% regression under a favourable boot.

## Verified still open after batch 5

Re-checked against the working tree, not carried over on trust:

- [resolved 2026-08-24] Gate harmonisation: landed as one designed change — see "R1 lands" below for what the benchmark gate caught on the way in.
- [pending] Template.RegexFlags is still nested in Template (med).
- [pending] RefExpression.MatchIndex is still a four-way union flattened into flag fields (med, model+codec surgery).
- [pending] Compiler still lacks the shared charsetFor(Encoding) extraction (med).
- [pending] The "__rec_" prefix is still spelt independently in Compiler and Executor (low).
- [pending] The remaining low-severity nits listed per package below needed a confirmation pass — delivered 2026-08-22; see "The confirmation pass" section for the verdicts.

## The confirmation pass (2026-08-22) — the per-package pending lists, retired

The detailed per-package pending sections that used to sit here went stale the day batches
3–5 landed: they still listed as pending what those batches fixed, including the
embedded-backreference HIGH and a "duplicate" that had since been deleted, benchmarked, and
vindicated. On 2026-08-22 every item was re-verified against the tree — greps and reads, no
trust — and the sections are retired in favour of the verdicts:

**Fixed and verified present** (the batch summaries above describe them): all of
engine/config except the two model refactors below; all of config/json + compile except the
extraction and constant below; all of engine/exec except the one decision below — including
the depth≥2 nested-combinator concat (`Steps.java` line ~322, StepOutput javadoc rewritten),
which batch 3's summary under-reported; all of engine/ds3 — the parser refuses malformed
refs, bounds bufferSize, blames the right party for ParserConfigurationException, and routes
nested groups to group(); and twelve of the regex module's sixteen, including the whole
BytePattern javadoc cluster, the `Refs.varRef` fallback (fixed by documentation, which is
what the finding asked for), and the KeyValue ignored-name surprise (documented; the lint
idea was not pursued).

**Still open — engine module:**

- [pending] Template.RegexFlags nested in Template, which never uses it; hoist (med).
- [pending] RefExpression.MatchIndex flattens a four-way union into flag fields against the
  package's sealed idiom (med, model+codec surgery).
- [pending] Compiler: extract the shared charsetFor(Encoding) resolution; the duplicated
  ternary appears twice in compile() (med).
- [pending] The "__rec_" synthetic-mode prefix spelt independently in Compiler and Executor
  — one constant (low).
- [pending] Strict-level line-anchor lint misses the root level when no document template
  exists (low).
- [decision] Lax-level unanchored eater consumes a searched-past prefix with no skip report —
  report, or extend the licence comment (low, D34-adjacent; Jon's call when it next
  surfaces).

**Still open — regex module:** moved to the module's own
[ISSUES.md](../stroom-shapeshifter-regex/ISSUES.md) (R1 gate harmonisation, R2 Plan
ellipsis, R3 residual FQNs, R4 compileForcingNfa retirement, plus the accepted measured
costs). One half-item was dropped as unreconstructable: "literal single-byte CharClass
inversion undocumented" — its acceptance-surface half is fixed in parseClassExpression, and
no concrete referent for the inversion half survived re-reading.

## Decisions surfaced (for Jon) — all six ruled, 2026-08-21/22

1. **E22** — ruled: the prototype parity no longer binds and exotic encodings are external concerns;
   fallbacks deleted, refusal made uniform. Resolved in ISSUES.md (batch 5).
2. **Transforms' plural select** — ruled as the ledger assumed: compile-time error
   (CompiledOp.single, batch 3, confirmed 2026-08-21).
3. **Silent call-template misses** — ruled: compile-time resolution (batch 4, pinned).
4. **Splitter quote-trim** — ruled: gate on an opened container (batch 4, pinned).
5. **NodeTree lookbehind window** — ruled after discussion: match the JDK, the only
   reference with an opinion on a construct Rust does not have (batch 5, thirteen shapes
   pinned).
6. **ds3 003 golden regeneration** — ruled: fix the shape, regenerate the golden (batch 4;
   output golden unmoved, messages 11→1, E1 corrected).

## R1 lands (2026-08-24) — the gate is one designed thing, and the signature was the cost

The audit's last designed change went in: `Utf8.splitsCharacter(data, at, to, complete,
contextEnd)` is now the single start gate all four engines ask, the beyond-region probe is
bounded by `contextEnd` everywhere `PikeVm` used to consult `data.length` (stale garbage on
a stream window), and the two lookbehind gates (`FancyBacktracker.matchBehind`,
`NodeTree`'s behind node) lost the `regionFrom` exemption the search gates never had. 443
tests green both modules; the divergence surface is unchanged.

The benchmark gate earned its keep twice:

- **The reboot invalidated Friday's baseline**, exactly as the method note above predicts —
  so the day started with a fresh same-boot baseline at the unchanged commit
  (`2026-08-24-0801-13b371cdac-anchored-r1-baseline.json`).
- **The natural design regressed a real workload, and the body was innocent.** Threading
  `contextEnd` as a ninth `search` argument cost −8.6% on `simulate line_miss` (1,742→1,586
  ops/s, error bars ±4/±6). The bisect (`-anchored-r1-bisect-*.json`) removed the shared
  helper: no change. Restored the old `data.length` body under the new signature: no
  change. Added the unused ninth parameter alone to an otherwise untouched `PikeVm`:
  −8.4%. Eight values already fill the call's registers; the ninth goes to the stack, and
  the frame it grows is the whole story — `PrintInlining` shows the same inline decisions
  both sides. On the tree engine the same ninth argument turned `anchored_miss` bimodal:
  two forks in five dropped from ~127M to ~100M ops/s
  (`-anchored-r1-param-engines-after.json`), an inlining coin-flip the baseline never
  lost.
- **The resolution: `contextEnd` is bound window state, not a search argument.** Every
  engine now carries it in a `setContextEnd` field set beside the `search` call, the same
  shape as the matcher's own `validTo`. Real-workload rows returned to baseline; what
  remains is a deterministic ~0.35 ns store, visible only on the tree engine's 8–15 ns
  instant-rejection rows (−2.3% `anchored_hit`, −4.3% `anchored_miss`, forks uniform),
  accepted and recorded in the module's `ISSUES.md`. One non-finding for the record:
  `scan_plan line_miss` read +56% after — but the unchanged commit reran that row at
  9.3k and 11.4k ops/s on the same boot, so the row is bimodal on its own and credits
  nothing. The lesson
  joins 2026-08-22's: on these paths the *shape of the call* is a measured quantity — a
  parameter is not free, a field is not free, and the only way to know which one a row
  can afford is to run the benchmark either side.

## The audit of R1's diff (2026-08-24) — eight angles, no defects, seven polishes

`3538ef8c08` was reviewed by eight independent angles (line-by-line, removed-behavior,
cross-file trace, reuse, simplification, altitude, efficiency, conventions). No correctness
finding survived. The verdicts worth keeping:

- **The lookbehind exemption removal is behavior-neutral for any compilable pattern.** The
  line-by-line scan constructed the one nameable scenario — a lookbehind body whose first
  byte is a bare continuation byte, over binary input — and the removed-behavior audit
  refuted it: every compiled node's first byte is ASCII or a UTF-8 lead byte (chars ≥ 0x80
  compile lead-byte-first; captures start on character boundaries), so no body could ever
  have matched at a continuation byte. The old exemption was dead code wearing a comment.
- **The `at == to` tightening in the three formerly-`at < to` engines is the unification's
  point, not a regression**: an empty match can no longer seed mid-character at a complete
  region's edge, which is the answer the simulation and the plan path already gave.
- **Call-site discipline verified independently three times**: all six `search` sites bind
  `setContextEnd` in the same basic block; no test, benchmark, or other production code
  reaches an engine directly.

Seven polishes were applied on the back of it, none touching behaviour: the six-site bind
convention is now stated in `PikeVm#search`'s contract and enforced by
`assert contextEnd >= to` in three engines (free under JIT, fatal under the test JVM's
`-ea` — a forgotten seventh call site now fails loudly instead of silently reproducing the
pre-R1 gate). `PikeVm` is exempted by measurement: the ~18 bytes the assert adds to
`search` cost −2% on `anchored_hit` and destabilised its forks
(`-anchored-r1-audit-fixes*.json`), so there the contract sentence carries the guard
alone — the week's recurring lesson, that method size on these paths is a measured
quantity, demonstrated once more; `ByteMatcher`'s `validTo` is renamed `contextEnd`, ending the one-class
vocabulary seam; the wrapper's duplicate contract javadoc now points at
`Utf8.splitsCharacter`, which owns it; that javadoc's "every engine" claim is scoped to
search starts and names the two deliberately hand-rolled lookbehind gates (their probe
sits below the cursor, inside consumed input, so the beyond-region clause cannot apply);
the field-and-setter block sits in the same place in all four engines; and
`FancyBacktracker`'s setter says what the cross-file trace proved — root only, children
never search. The batch went through the anchored gate like everything else on these
paths.

## The audit of the end-anchor programme (2026-08-24) — three real bugs, one of them critical

The same eight-angle review that cleared R1's diff was run over everything after it —
R2–R4 and the programme's four phases — and this time it drew blood. Three findings, all
reproduced at runtime by two or three finders independently, all fixed the same evening
with the reproductions pinned as tests:

- **CRITICAL — the reverse finder was unsound on non-ASCII input.** `Reverse` borrowed
  `NfaCompiler`'s byte-level licence for byte-safe repeats but compiled non-fancy, where
  that licence is never applied: the reversed `[^x]`-style repeats came out as
  lead-byte-first character tries, and the backwards walk died at the first continuation
  byte. `([^\\]+)$` returned a wrong NO_MATCH over `"café"` — a trusted miss, the worst
  class — and a wrong span over `"Cé.txt"` that the safety valve could not catch, because
  the too-late proposal genuinely verifies forward. The differential warrant had an
  ASCII-only alphabet, which is why it swore the finder was sound. Fixed by
  `NfaCompiler.compileByteLevel` — the licence enforced, not assumed: byte-safe classes
  emit single-byte tables, which over-approximate (safe: proposals are verified, and
  over-approximation cannot manufacture the false miss). `ReverseTest` now sweeps
  non-ASCII adversarial cases and a randomised multi-byte alphabet.
- **HIGH — the tail-window jump moved `\G`.** The jump rewrites the search start, and
  `\G` is the one anchor defined relative to it: `\Gabc$` matched over `"xxabc"` where
  the JDK refuses. The published facts gained `anchorsToSearchStart` and the jump refuses
  such patterns; the reverse finder refuses them too, which also fixed the forced-TREE
  crash where a reverse program containing `\G` reached an evaluator that throws on it.
- **HIGH — the executor's end-anchored refusal dropped correct records when the input was
  exactly the buffer's capacity.** `eof` only meant the stream's end had not been
  *observed*; a one-byte pushback probe now settles the question before anything is said,
  and `ignore_errors` regains its contract — it downgrades the refusal to the warning, the
  same escape hatch the unmatched-content error honours.

The cleanup angles landed four polishes: `explain()`'s accel line moved above the tree
early-return it was unreachable behind (which had also weakened the exclusion pins that
used its absence as evidence); the finder's per-position MATCH scan became a sticky flag
set on add, per PikeVm's own idiom; the benchmark's tree-only state class, dead since
Phase 4 made every shape tree-feasible, was deleted with its constant-true predicate and
reflection guard; and `BytePattern`'s telescoping constructors — three signatures whose
trailing nulls invited a same-typed `Nfa` transposition — collapsed to one constructor
that every factory calls in full. The lesson worth the ink: the warrant for a trusted
answer is only as wide as its test alphabet, and the audit that checked the alphabet was
the only thing standing between "fifty million misses a second" and "wrong about café".

## The closing audit (2026-08-24) — the fixes to the fixes, and the module's record closes

The last unreviewed diff in the tree was the audit-fix commit itself, so the eight angles
ran once more over it. No correctness defect survived — the removed-behavior and
line-by-line angles verified every deletion's invariant re-established and every
constructor call site transposition-free — but the pass earned its cost twice over:

- **The pushback probe could block.** On a live source (a socket peer waiting for output),
  the unconditional probe before the truncation block would stall an already-matched
  record indefinitely — a deadlock the pre-probe code could not produce. The probe now
  runs only inside the refusal-eligible branch, where a hard refusal genuinely needs the
  certainty; the hedged warning stays hedged and never blocks. The refusal also honours
  `template.ignoreErrors()` beside the run-level flag, as the skip error always has,
  and its comment now says what the downgrade actually does: restore the port's kept
  limitation, not mirror the unmatched-content hatch.
- **Three latent single-source violations, retired before they could bite:** the byte-level
  licence had grown a third spelling (now one — `NfaCompiler.byteSafe`, package-visible,
  called by the mode branch and by `Reverse`); the `\G` fact had grown a third encoding
  (now one — the published `anchorsToSearchStart`, consumed at `reverseProgram`'s single
  qualification site); and `compileByteLevel` had copy-pasted the compile wrapper this
  very commit's constructor collapse existed to condemn (now a one-line delegate through
  the one body). `compileSub` also stopped pre-wiring the byte-level mode into lookaround
  sub-programs, where the over-approximation argument inverts under negation — unreachable
  today, and now not a trap for the v2 that might not notice.
- **Hygiene, including three of this session's own making:** an out-of-order import, the
  `fill()` javadoc stranded above `probeExhausted` (the same dangling-doc disease fixed in
  ByteMatcher that morning), fully-qualified same-package names in `ReverseTest` on the
  day R3 closed, the compile-per-iteration in the randomised sweeps, the eagerly-computed
  search-start walk on paths where `fancy()` already proves it false, and a constructor
  comment that claimed more than its call sites delivered.

Adjudicated, not changed: the silent end of processing after a full-window match under
`ignore_errors` is the port's deliberately-kept limitation (pinned by
`warnsWhenATemplateSwallowsAWholeFullBuffer`'s own comment), restored — not introduced —
by the downgrade. With this pass, every diff in the module's tree has been through the
same eight angles, the issue list is empty, and the end-anchor programme stands at Phase 4
with Phase 5 recorded behind a workload.

## The D37 audit (2026-08-25) — eight angles over the retirement, no defects, the docs owed the truth

`083cdfd3fd` was reviewed by the standing eight angles. Line-by-line found nothing; the
removed-behavior angle verified every fold's proof — each deleted latch read was
`!complete`-guarded, each NEED_MORE return folded exactly, every complete-window assertion
in the deleted tests still covered elsewhere — and the cross-file signature sweep found all
five changed signatures consistent at every caller. No correctness finding survived.

What the angles did find, and what was done:

- **Two deletions the fold orphaned, now deleted:** `CharClass.mayContinue` (its only
  callers were PlanRunner's removed edge paths) and CorpusDifferentialTest's `Anchoring`
  import. The house standard is delete dead vocabulary, and the fold missed both.
- **The docs owed the truth in fifteen places, now paid:** the module README still sold
  `StreamMatcher` and "complete or streaming" as shipped; PikeVm's header had streaming
  "today"; NodeTree claimed a shared "streaming conservatism" neither engine has;
  `Backrefs` promised edge reporting no caller does; `Utf8.splitsCharacter`'s fresh javadoc
  referenced its own deleted parameter and a stale-tail case no entry can express;
  TailWindowTest promised a deleted pin; the retired "window" vocabulary survived in field
  comments and three assert strings; design/06's scope note still called the retirement
  undecided; design/01 §7 and design/02 §7 got retirement banners; design/07's streaming
  recommendation got its overtaken note; the engine module's buffer-boundary item pointed a
  future implementer at the deleted NEED_MORE answer; and the benchmarks README gained the
  `AnchoredSearchBenchmark` row it had always lacked.
- **Polish:** the new five-argument entry gained its `@param`/`@return` tags and — the
  reuse angle's point — a sentence recording that its binding block repeats the
  four-argument entry's *on purpose* (the call shape is measured; no delegation). Batch 2's
  accepted-cost entry gained its D37 supersession note. Whitespace residue where the
  latches were excised, and BytePattern's import order, fixed.
- **Not fixed, recorded:** the measure-first list (dead edge tests in four first-byte
  gates, PikeVm's subsumed compares, MATCH_LITERAL's doomed compare loop, contextEnd
  constant-propagation, boolean engine returns, the TRUNCATED collapse) and the
  clipped-context inexpressibility — both now open items in the module's ISSUES.md, the
  second needing a ruling.

Confirmation: suites green both modules after the fixes; the anchored gate re-ran flat to
a row against the D37 after-file, and corpus CSV+DATETIME flat on every row but one —
forced-tree CSV read +30.4% with a ±700 error bar on 3,412, the same bimodal coin the D37
gate had already caught moving (+13.8%), credited to nothing (comment and markdown edits,
with the three assert strings the only bytecode change, on failure paths). Evidence:
`2026-08-25-2143-*-d37-audit-anchored.json`, `2026-08-25-2151-*-d37-audit-corpus.json`.

## The tail audit (2026-08-26) — two angles over four commits, nothing found

The D37 audit's fix commit and the three edge-test commits it spawned (`2285945f11`,
`f8a343c10a`, `b16894c3b2`, `62babdda4d`) had been verified by their author, gated
per-commit overnight, and never independently reviewed — so the two correctness angles ran
over the 425-line tail, proportionate to a diff that is mostly comments around ten
executable lines. Line-by-line verified the dead-test proof's premises at every first-byte
table constructor (PlanCompiler, Nfa.computeFirstBytes, NodeTree's Compiled — including
that sub-programs' minLength 0 never meets a search gate) and every call site's bound.
Removed-behavior attacked the proof's edges — empty-branch alternations, {0,n} repeats,
backref-only paths where nullable() and byteLength's lower bound might disagree, empty
regions, anchored entries, the input-anchored clamp at regionFrom == to — and found no
input that reaches any removed branch. No findings from either angle; suites green. Every
diff in the module's tree is again through review.
