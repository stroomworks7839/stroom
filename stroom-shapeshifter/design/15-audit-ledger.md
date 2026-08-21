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

- [fixed] regex/NodeTree + FancyBacktracker — a lookbehind pinned its body's *window* as well as its end, which got the meaning wrong in both directions: a nested lookahead could never see the text it was looking for, and `$` read the cursor as the end of input and held there. Now only the end is pinned. Decision 5, ruled to match the JDK. Probing thirteen shapes against `java.util.regex` found five diverging, not the one reported — including `(?<!a(?=bc))bc`, which matched when it should not have, and `(?<=a$)b`, which matched when the JDK finds nothing. All thirteen agree now and are pinned in `KnownDivergenceTest.aLookbehindBodySeesPastTheCursorAndAgrees`. `recordEdge` is live inside a lookbehind again: a bounded body cannot consume past the cursor, so only a nested lookahead reaches the window edge, and there the contact is real (med).
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

## Verified still open after batch 5

Re-checked against the working tree, not carried over on trust:

- [pending] Gate harmonisation: ByteWindow.contextEnd exists and ByteMatcher reads it, but it is not threaded into the four engines — PikeVm still consults data.length in the beyond-region clause, and the other three still lack the clause (med, one designed change).
- [pending] Template.RegexFlags is still nested in Template (med).
- [pending] RefExpression.MatchIndex is still a four-way union flattened into flag fields (med, model+codec surgery).
- [pending] Compiler still lacks the shared charsetFor(Encoding) extraction (med).
- [pending] The "__rec_" prefix is still spelt independently in Compiler and Executor (low).
- [pending] The remaining low-severity nits listed per package below have not been individually re-confirmed since the sweep; they need a confirmation pass before being closed or fixed.

## Pending — engine/config (model)

- [pending] OutputNode nine transforms take List<RefExpression> select but every consumer except StringJoin reads only the first — half-translated Vec<_> shape; add compile-time error for >1 on single-input transforms (med).
- [pending] OutputNode.Translate javadoc claims XSLT translate() char semantics; implementation is ordered whole-substring replace with delete-on-missing-to (med).
- [pending] Codec javadoc "first four always available" is ds-rs feature-gating; JDK build has six (DEFLATE/GZIP via java.util.zip) (med).
- [pending] MatchExpression.Regex.advance unvalidated: negative or > groupCount silently behaves as 0; reject in constructor/Compiler, document unmatched-group fallback (med).
- [pending] Template.ignoreErrors javadoc claims unconsumed-content scope it lost to the container flag (E2/E17) (med).
- [pending] Project.bufferSize javadoc still pre-E13 ("never spans two buffers") (med).
- [pending] MatchStep.MatchByte stores/returns caller's byte[] without cloning, unlike every sibling (med).
- [pending] OutputNode.params(Map) dead code with latent ordering bug — delete (med).
- [pending] Dispatch.ANY javadoc says "Not yet implemented — E18"; it shipped 2026-08-21 (HIGH javadoc, trivial fix).
- [pending] Three javadoc sites define the pattern dialect as "Rust's regex crate"; owner is stroom.shapeshifter.regex.BytePattern (med).
- [pending] Template.RegexFlags nested in Template, which never uses it; hoist (med).
- [pending] RefExpression.MatchIndex flattens a four-way union into flag fields against the package's sealed idiom (med, model+codec surgery).
- [pending] Package-wide numeric range validation: MatchLimits negatives, Repeat max<min, TakeN<0, bufferSize<=0, maxDepth<=0, onlyMatch<1 (med).
- [pending] Project.source not defaulted in compact constructor; NPEs far from cause (low).
- [pending] Template @param order vs component order; Project version doc stale (v4 exists); Predicate "five" is six; Repeat null-vs-MatchLimits -1 unbounded conventions; EmitError/MatchByte FQNs; blank-line nits; Dispatch raw markdown link; package-info "config.json" wording; Endianness default-rationale home; KeyValue ignored-name lint (all low).

## Pending — engine/config/json + compile

- [pending] ProjectJson.checkFields returns silently on non-object nodes — the strictness hole: wrong-typed values load as all-defaults ("source": "utf-8" runs) (HIGH).
- [pending] version read as bare asInt(): missing version = 0 = legacy lax dispatch, silently (med).
- [pending] greater-than/less-than missing value defaults to 0.0; siblings throw (med).
- [pending] compiled_idx tolerated on read, never read/written, zero corpus uses — delete from checkFields lists (med).
- [pending] call-template names / template_refs never resolved at compile time; typo runs and silently emits nothing (Executor returns on null lookup) — resolve in Compiler or pin as deliberate (med).
- [pending] Compiler.compile dead method-level charset local (E3 leftover); extract shared charsetFor(Encoding) (med).
- [pending] dispatchChecks javadoc claims any is refused (E18 shipped); notYet javadoc says progressive deferred (it landed) (med/low).
- [pending] Strict-level line-anchor lint misses the root level when no document template exists (low).
- [pending] CompiledOp.Apply: isWholeParentContent || isLocalGroup redundant disjunct; select evaluated thrice (low).
- [pending] "twelve transform functions" is ten/eleven; CompiledMatch.Regex javadoc re-describes parser anchor criteria caller-side (keep the 3.6× citation, point at LeadingAnchor) (low).
- [pending] "__rec_" synthetic-mode prefix spelt in Compiler and Executor independently — one constant (low).
- [pending] ProjectJson E20-era FQNs (Dispatch, Severity, Locale.ROOT ×7); text()/list() lose owner context in errors; readCharSet raw SIOOBE/NPE on malformed input, MatchByte >255 silent truncation; unit variants accept junk object bodies; tag() error message misdescribes non-objects (low).

## Pending — engine/exec

- [pending] Steps nested combinators drop enclosing outputs when recursing (prior without local), so StepOutput indexes resolve inconsistently at depth ≥2; StepRef.StepOutput javadoc matches neither behaviour; needs concat + depth-2 test (med, port-defect).
- [pending] Steps.matches() javadoc says predicates are deliberately ASCII — false since E5 (decoded codepoints, Unicode-aware) (med).
- [pending] Steps.AnyChar measures one character in UTF-8 unconditionally, ignoring effective encoding (E5 straggler) (med).
- [pending] Steps Tag/TakeUntil literals encoded UTF-8 at runtime while Delimiter literals honour the template charset at compile time (E3 straggler; fixing also retires E12's per-match cost) (med).
- [pending] Executor: UncheckedIOException mid-run discards every accumulated message; catch in execute(), append FATAL, return the list (med).
- [pending] Executor.anyLevel re-implements processMatch inline; drift already visible (onMatch length; zero-advance exit skips minMatch reporting) (med).
- [pending] Splitter closing-quote trim runs for never-opened containers, truncating unquoted fields ending in container-end bytes — check ds-rs, then gate or pin (med).
- [pending] onMatch pairs match-start offset with consumption length — inconsistent span when a prefix was skipped (low).
- [pending] BOM excluded from the running offset: all reported absolute offsets short by BOM length (low).
- [pending] Two zero-advance errors report different offset spaces under identical wording; preview() decodes UTF-8 regardless of encoding (low).
- [pending] Lax-level unanchored eater consumes searched-past prefix with no skip report — report or extend the licence comment (low, D34-adjacent decision).
- [pending] Steps.isSpace dead (E5 orphan); E4 comment history sentence; TypedValue.format mis-renders whole doubles beyond long range; Float variant shadows java.lang.Float (rename Real/Decimal); MatchResult array identity semantics + test-only groupBytes; Refs varRef fallback magic `return 1`; FQN nits; bare block + triple blank line in level(); CompiledRefs default arms forfeit sealed exhaustiveness (all low).

## Pending — engine/ds3

- [pending] children() mints one mode per sibling expression — N siblings become N levels each rescanning full content; fixture 003's goldens freeze the false accusations (output golden parses lines its messages golden accuses); E1's closing text mischaracterises them. Fix = shared submode + regenerate 003 messages golden + correct E1 (HIGH, golden migration).
- [pending] DS3 regex advance attribute silently dropped; model field exists — read and thread it, or refuse loudly (HIGH).
- [pending] Unnamed-group collapse drops the group's ignoreErrors (med).
- [pending] isReference() omits @ which LegacyRefs.parse accepts — data attribute @refs emitted as literal text (med).
- [pending] Raw $var$N reads from var-conversion paths bypass indexVarReads and always resolve null (med).
- [pending] Nested group inside group-with-expressions routed to children() not group(): loses value/ignoreErrors/shared mode (med).
- [pending] Parser ignores unknown and known-but-unread attributes (matchOrder, advance, version, typos) silently — warn per element (med).
- [pending] Document structure unvalidated: any element as root; non-expression root children dropped without message (med).
- [pending] bufferSize: no lower bound, silent clamp (low).
- [pending] dollar() tolerates malformed refs at() rejects (unclosed [, empty varId, trailing junk, index-on-current-match) (low).
- [pending] at() raw SIOOBE on `@a]b[1` (low).
- [pending] source() sets a dead byte stream contradicting its own comment — StringReader one-liner (low).
- [pending] ParserConfigurationException blamed on the configuration (low).
- [pending] Ds3Config.Data.hasChildren duplicates !children().isEmpty() with stale Rust rationale (low).
- [pending] identifier() determinism claim false on the common (random UUID) path — derive from tree path or soften (low).
- [pending] package doc silent on what is not migrated (low).
- [pending] mode counter spent on variable names; withMode positional copy hardcodes consume=false; Set FQN (low).

## Pending — regex public + comb

- [pending] compileForcing @throws names one refusal of two; three "present only when TREE was forced" javadocs false since D31/D32; engine() "most expensive" promise broken for ambiguous patterns; BACKTRACK_BUDGET_BYTES routing claim stale (keep sizing rationale); forced-field doc stale (med).
- [pending] describe() casts code point to char — supplementary mangling; add int overloads to takeUntil/takeThrough (low).
- [pending] Missing javadoc across the public face: ByteMatcher accessors, StreamMatcher buffer-relative vs absolute offset trap, ByteWindow.of, MatcherLibrary null contract, Matchers.repeat, Matcher.Repeat/UNBOUNDED (med).
- [pending] MatcherLibrary.names() leaks live keySet (med).
- [pending] "ipv4" library matcher accepts 1.2, 99999.0 — constrain or rename (med).
- [pending] Dead guards: ByteMatcher forced!=SIMULATE clause, BytePattern engine!=TREE, dead PikeVm allocation when BACKTRACK pinned; deprecated self-call; Collectors FQN; comb records no defensive copies; PatternCompileException non-final + get-prefixed accessors; bounds error message renders ] as ); EnumSet copy dance ×4 (low).

## Pending — regex internal

- [pending] Lowering: named backreference inside an embedded regex resolves against an unseeded groupNames list — spurious rejection for the first name, silently wrong group for later ones (HIGH; fix in Parser: offset embedded indexOf by firstGroupIndex, seed placeholders; consider outer-name collisions).
- [pending] Analysis.byteLength min computed unguarded int while max is long-guarded — repetition overflow poisons Nfa.minLength → AIOOBE in Backtracker.visited (med).
- [pending] Gate harmonisation: the beyond-region continuation clause (consult data[to] when complete) exists in PikeVm/splitsCharacter but reads data.length (garbage on stream windows) and is absent in Backtracker/FancyBacktracker/NodeTree — thread ByteWindow.contextEnd through the four engines and extract the shared start-gate helper (SKIP/BREAK/ATTEMPT); also FancyBacktracker.matchBehind exempts regionFrom inconsistently (med; batch as one designed change — the A-L auditor's finding 16).
- [pending] NodeTree lookbehind truncates ctx.to — lookahead/\b inside lookbehind diverges from JDK (probed); fix or pin in KnownDivergenceTest (med).
- [pending] Parser: malformed \x{...}/huge bounds escape as raw NumberFormatException/IllegalArgumentException instead of PatternCompileException (med).
- [pending] Nfa fancy-sweep misses CLASS_STAR — doc claims disagreement impossible; holds only by upstream gating accident (med).
- [pending] PlanRunner duplicates PikeVm's assertion switch verbatim against the "one implementation serves them all" comment (med).
- [pending] Words.characterAt re-implements Utf8.decode with different mask idiom — delegate (med).
- [pending] Parser: duplicate parse overloads (delegate); countGroups counts ( inside \Q..\E, digit-greed bound inconsistency (low).
- [pending] NodeTree anchor magic numbers vs Nfa constants; dead nine-arg Nfa constructor; computeFirstBytes default-arm comment reason wrong; PikeVm generation figure 2^31 not 2^32; Words.Unicode.build worse duplicate of UnicodeClasses.build; literal single-byte CharClass inversion undocumented + parseClassExpression byte-length surface; scriptOrBlock resolves scripts only; FQN nits; Plan ellipsis on exactly-six; characterBefore unbounded backward walk on malformed input (all low).

## Decisions surfaced (for Jon)

1. **E22** — charset fallback chains: pin-one-and-fail-loud vs documented approximation; which Shift JIS mapping does ds-rs parity require?
2. **Transforms' plural select** — narrow the model to single RefExpression (format surgery) or compile-time error on >1 (cheap)? Ledger assumes the error.
3. **Silent call-template misses** — compile-time resolution (recommended) or pinned ds-rs faithfulness?
4. **Splitter quote-trim** — ds-rs faithful or divergent? Needs a ds-rs check before gating.
5. **NodeTree lookbehind window** — restore JDK semantics or pin the divergence?
6. **ds3 003 golden regeneration** — fixing the sibling-level defect changes the messages golden and corrects E1's closing text; approve the migration-shape change.
