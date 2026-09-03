# Decision Log

Decisions taken during design, with their consequences. Newest last.

---

## D1 — RE2-style dialect, not full `java.util.regex`

*2026-08-17.* The engine's own regex language is an RE2-style subset: no backreferences,
lookaround, atomic groups or possessive quantifiers, and a linear-time guarantee.

**Consequences:** enables the Pike VM, which is what makes streaming suspendable at a chunk
boundary; enables the one-pass analysis behind tier 0. See [01-regex-language.md §2](../stroom-shapeshifter-regex/design/01-regex-language.md).

Superseded in part by [D7](#d7--javautilregex-is-a-supported-second-dialect).

---

## D2 — Character classes compile through the encoding

*2026-08-17.* A pattern is authored in code points; the compiler expands every literal and
class into byte-sequence alternations for the target encoding.

**Consequences:** `\w` matches `0xE9` under Latin-1, which neither Rust's byte regex nor a
`(?-u)` workaround can express. See [01-regex-language.md §4](../stroom-shapeshifter-regex/design/01-regex-language.md) and
[02-engine-design.md Appendix A](../stroom-shapeshifter-regex/design/02-engine-design.md).

---

## D3 — Streaming is a first-class outcome

*2026-08-17.* Matching returns `MATCH` / `NO_MATCH` / `NEED_MORE_INPUT`, for both finite and
unbounded sources.

**Consequences:** constrains the VM design (state must be suspendable); rules out a
backtracking engine as the primary tier; requires a window manager with a caller-supplied
maximum. See [01-regex-language.md §7](../stroom-shapeshifter-regex/design/01-regex-language.md).

---

## D4 — Greenfield language, existing patterns as a test corpus

*2026-08-17.* No obligation to run existing DS3 patterns unchanged, but they are harvested as
a differential-test corpus against `java.util.regex`.

**Consequences:** [02-engine-design.md §9.2, §9.6](../stroom-shapeshifter-regex/design/02-engine-design.md).

---

## D5 — Hard encodings are transcoded upstream, not compiled

*2026-08-17.* UTF-8, `RAW` and single-byte charsets compile into the pattern. UTF-16,
Shift-JIS, EUC-*, ISO-2022-JP, GBK, GB18030 and Big5 are converted by a `transcode` pipeline
stage instead.

**Consequences:** removed the variable-width class enumeration, the self-synchronisation
machinery and the ISO-2022-JP rejection from the engine entirely; added a scoped stage with a
checkpointed offset map and an explicit malformed-input policy. The dividing line is offset
preservation, not difficulty. See [02-engine-design.md §4](../stroom-shapeshifter-regex/design/02-engine-design.md).

---

## D6 — Encoding back out is a codec, not a scope

*2026-08-17.* `transcode` is decode-only. Emitting a value in another charset is
`encode(value, charset)` alongside base64/gzip/hex.

**Consequences:** the offset map stays one-way. Raised a new open question — the
unmappable-character policy on the encode side, since Java's default substitutes `?` silently.

---

## D7 — `java.util.regex` is a supported second dialect

*2026-08-17.* Patterns needing backreferences or lookaround use a `javaRegex` element that
delegates to the JDK engine, rather than the project building a backtracking engine of its own.

**Consequences:** deleted the largest item from future work. Requires a decoding boundary
(reuses the transcode machinery), conservative streaming via `hitEnd()`/`requireEnd()`, and
containment via a `charAt`-counting budget plus catching `StackOverflowError`. **Never
selected automatically** — a distinct element type, gated by a deployment capability, with a
compiler warning when a pattern did not need it. See [01-regex-language.md §8](../stroom-shapeshifter-regex/design/01-regex-language.md).

Superseded by [D27](#d27--the-fancy-tier-backreferences-and-lookaround-run-natively): once D25
had built a bounded backtracker, building the unbounded one stopped being the largest item on
the books, and the delegation lost its reason to exist.

---

## D8 — Compose at authoring time, flatten at compile time

*2026-08-17.* A composition is a description, lowered into the same byte IR as a regex and
compiled to one flat plan — not a tree of `Matcher` objects walked at runtime.

**Consequences:** composability costs nothing at runtime; a readable composition and an
equivalent dense regex compile to the same plan. This is the Java substitute for the
monomorphisation that makes nom fast in Rust. See [02-engine-design.md §6.5](../stroom-shapeshifter-regex/design/02-engine-design.md).

---

## D9 — Benchmark baseline before engine code

*2026-08-17.* First code written is the JMH baseline, not the engine.

**Consequences:** confirmed the tier 0 thesis (2.7–4.0×) and refuted two representation
assumptions before they were built on. See [03-baseline-results.md](../stroom-shapeshifter-regex/design/03-baseline-results.md).
Vindicated the ordering: both refuted claims were in the design as motivating arguments.

---

## D10 — Ships as a new pipeline element and document type

*2026-08-17.* Shapeshifter becomes its own parser element alongside the existing DS3, JSON and
XMLFragment parsers, with its own document type for configs. Existing DS3 is left untouched.

**Consequences, to be designed:**

- The matching library stays Stroom-free, but a thin adapter module will be needed for the
  pipeline element (`stroom-shapeshifter-pipeline`, not yet created).
- Error reporting must map onto Stroom's `ErrorReceiver`/`Locator` model, so the engine's
  errors need source line/column, not just byte offsets — which is what the transcode offset
  map (D5) has to preserve.
- Output shape is an open question: SAX events into the pipeline like the existing parsers, or
  the structured output layer from the Rust design. Deferred until the transform layer (D12).
- No migration of existing DS3 configs is required, which keeps D4 honest.

---

## D11 — Configs are compositions of library patterns, realised at import/export

*2026-08-17.* The authored form is a library-based DSL: a config references named library
patterns (`IP address`, `ISO date`, `CSV field`) rather than inlining them, and is only fully
realised when imported or exported.

**Consequences, to be designed:**

- Library patterns are first-class, referenceable artefacts. In Stroom that means DocRefs, so
  import/export carries pattern dependencies with the config the way it already does for
  pipelines, feeds and XSLTs.
- Reference resolution therefore has two stages: *authoring* (a reference stays a reference)
  and *compilation* (references are inlined with cycle detection, as ds-rs does — see
  [01-regex-language.md §6.5](../stroom-shapeshifter-regex/design/01-regex-language.md)). The engine only ever sees the resolved
  form.
- A pattern's definition can change under a config that references it. Versioning or pinning
  needs a decision; unversioned references mean a library edit can silently alter every
  config using it.
- The serialised form must round-trip references without materialising them, so the flattening
  in D8 happens strictly after resolution, never before.

---

## D13 — One substrate: bytes. No character mode

*2026-08-17.* Considered offering a character-oriented mode alongside the byte one, on the
theory that always insisting on bytes might carry a performance penalty. Rejected.

**Why:**

1. **The penalty is not established.** The baseline measured byte scanning 6% behind char
   scanning on unoptimised loops with ASCII data and near-overlapping error bars
   ([03-baseline-results.md §4.1](../stroom-shapeshifter-regex/design/03-baseline-results.md)). Vectorised scanning gives bytes
   twice the lanes per register, and non-ASCII input inflates a `String` to UTF-16 while UTF-8
   bytes do not. Both push that figure the other way.
2. **The abstraction costs more than the penalty.** In Java, being generic over the input
   representation means either an interface between matcher and input — which is the
   `CharSequence` indirection measured at 9–15%, i.e. worse than what it avoids — or
   duplicating every matcher, plan op, span type and streaming rule. Rust gets this free via
   monomorphisation (nom is generic over its input trait); Java does not. Same constraint that
   produced [D8](#d8--compose-at-authoring-time-flatten-at-compile-time).
3. **A character mode is self-defeating for the motivating cases.** BOM sniffing *determines*
   the decoding, so it must run on bytes — in character mode, something upstream has already
   answered the question the engine was meant to answer. Likewise binary types, `seek`/`tell`
   and length-prefixed formats. The byte path can never be removed, so a mode means
   maintaining both permanently and doubling the test matrix in the highest-risk code.

**The real cost, which is not throughput:** a pipeline-ordering constraint. Stroom's
char-based reader elements (`FindReplaceFilter`, `InvalidCharFilterReader`,
`BadTextXMLFilterReader`) cannot sit directly upstream of a byte-native parser without a
re-encode. Mitigations, in preference order: byte-level equivalents where the engine's own
transform layer already covers the behaviour (find/replace being the obvious one); an explicit
encode adapter element where it does not; documenting the constraint otherwise.

Stroom's pipeline already has the byte side this needs — `AbstractInputElement`,
`InputStreamElement`, `BOMRemovalInputStream`, `InputStreamRecordDetector` — so the element
from [D10](#d10--ships-as-a-new-pipeline-element-and-document-type) attaches to an existing
part of the model. Note that today's `DS3Parser` takes `InputSource.getCharacterStream()`
(`DS3Parser.java:122`), so this is a departure from how the current parser is wired, not from
what the pipeline supports.

**Revisit if:** follow-up 7 in [03-baseline-results.md](../stroom-shapeshifter-regex/design/03-baseline-results.md) shows the
char-to-byte boundary cost is material, or if vectorised scanning fails to close the 6%.

---

## D14 — Tier 0 is interpreted, with flat opcodes and byte-table classes

*2026-08-17.* Settled by prototype measurement rather than argument
([03-baseline-results.md §7](../stroom-shapeshifter-regex/design/03-baseline-results.md)):

- **Interpret, do not generate bytecode.** An interpreted plan reached 100–103% of
  hand-written specialised code, because per-op dispatch amortises against the per-byte work
  inside each op. The bytecode-generation contingency is dropped.
- **Class membership is a 256-entry byte table**, not a 256-bit set — worth +22% on a
  class-scanning workload.
- **Flat parallel arrays for execution, sealed records for the IR.** Flat opcodes are 5–10%
  faster; the record model is what the compiler and `explain()` should work with. Two
  representations, lowered one to the other.
- **Class tests must be specialised** — one byte, complement of one, contiguous range, then
  the table as fallback. The entire residual gap to hand-written code was this.

**Consequence:** tier 0 is cheaper to build than assumed, and the performance work is in the
*compiler's* op selection rather than in the runtime.

---

## D15 — Plans before the Pike VM, with `java.util.regex` as the oracle

*2026-08-17.* The original phasing built the Pike VM first (P1) so it could serve as the oracle
proving scan plans correct (P4). Reversed: for the subset both engines share — ASCII input, no
lookaround or backreferences — **`java.util.regex` is already a mature oracle**, so plans can be
proved correct without the VM existing.

**Consequences:** tier 0 ships first and is useful on its own; the VM becomes the fallback for
patterns plans reject, built when those patterns need supporting rather than up front. The
differential suite (`DifferentialTest`) is the correctness argument in both orderings, so
nothing is lost.

**Validated immediately:** the first differential run found a real bug — `^` and `\A` were
being evaluated against the *attempt* offset rather than the region start, so a start-anchored
pattern would match at any offset during an unanchored search. Curated cases missed it; random
ASCII inputs caught it on the first seed.

---

## D16 — Ambiguity selects a tier, it is not an error

*2026-08-17.* With the Pike VM built, an ambiguous pattern is no longer rejected — it compiles
to tier 1. `PatternCompileException.Reason.NOT_ONE_PASS` was removed, since it can no longer
occur, and the ambiguity is exposed as `BytePattern.ambiguities()` instead.

**Consequences:** the analysis keeps both of its jobs — choosing a tier, and telling authors
their pattern is looser than they think — but the second is now advice rather than a refusal.
18 of the 21 corpus patterns compile and run; the other 3 need the java dialect by design.

---

## D17 — The one-pass predicate must model preference, not just determinism

*2026-08-17.* Prompted by checking whether nested choice works. Implementing prefix factoring
(specified in the design, never built) moved `(GET|POST|PUT|DELETE)` from tier 1 to tier 0, and
in doing so exposed a latent **wrong-answer** bug in the one-pass condition.

A branch that can match empty means "stop here" and is *preferred* over later branches. Where
the rest of the pattern can accept, stopping always succeeds, so no lookahead can decide — but
the plan's byte dispatch always prefers to consume. `(a|ab)` against `"ab"` returned `"ab"`
where every other engine returns `"a"`, and `a*?` consumed greedily where it should match empty.
The second case predates factoring entirely.

**Consequences:** the analysis now tracks a `canEnd` flag alongside the follow set, and both
shapes fall to tier 1. Recorded because the general lesson is easy to lose: a scan plan has to
reproduce **leftmost-first semantics**, not merely be deterministic, and the predicate that
selects it must test for that.

---

## D18 — A lazy DFA is back on the table, with evidence

> **Superseded by [D25](#d25--a-bounded-backtracker-rather-than-a-lazy-dfa), 2026-08-18.** The gap
> this entry argues from was 25–70×; it is now 5–20×, and the mechanism it blames — many threads
> advancing in lockstep — was removed by [D24](#d24--a-character-class-is-one-instruction-not-a-set-of-branches).
> Kept for the reasoning, not the recommendation.

*2026-08-17.* The design ruled out a lazy DFA as a v1 non-goal, "revisit with benchmark
evidence". Measuring the same pattern through both engines produced it: tier 0 at 5379 ops/s
against tier 1 at 55.7, a **91× engine-cost gap**, with roughly half the corpus on the slow side.

Three cheaper explanations were tested and refuted — capture-slot allocation, enum `values()`
cloning, and class expansion in the NFA. Fixing the first two removed essentially all allocation
(21.9 MB/op → 3 KB/op) and gained tier 0 21%, but moved tier 1 barely at all. The remaining cost
is the epsilon-closure walk re-derived at every input byte, which is what a plain Pike VM costs
and why RE2 uses a lazy DFA to find match bounds before running the simulation for captures.

**Consequences:** both cheaper options have since been tried and neither closes the gap
([03-baseline-results.md §8.4](../stroom-shapeshifter-regex/design/03-baseline-results.md)). Widening tier 0 turned out to be
largely unavailable — tier 1's population is mostly genuine ambiguity, and the estimate that
`SCAN_TO_LAST` would move half of it was wrong by an order of magnitude. Precomputed closures
made no measurable difference. A first-byte prefilter gained 45%, leaving the gap at ~54×.

The remaining cost is what a per-byte thread-set simulation inherently costs, so **a DFA is now
the indicated next step** rather than a possibility held in reserve. Its caching design is
already settled in D18.1.

### D18.1 — If a DFA is built, how its state cache should work

*2026-08-17.* Settled in advance, because the caching design is the part most likely to be got
wrong and it interacts with the threading model already in place.

**The property everything else rests on:** a DFA transition table is *pure memoisation of a
deterministic function* — `next(stateSet, byte)` always gives the same answer. Duplicated work
between threads is therefore harmless, and discarding the entire cache mid-match costs
performance only, never correctness. Every policy question below is consequently a performance
choice, which is why they can be answered crudely.

**Prefer a compile-time table to a runtime cache.** Attempt the full subset construction when
the pattern is compiled, capped at a state budget (~256). Most real patterns have small state
sets, so this usually completes — and then the transition table is **immutable**, lives on
`BytePattern`, is shared across every thread with no synchronisation, and has no warm-up, no
eviction, no thrash detection and no cache at all. This turns the common case into a
compile-time artefact like everything else in the engine, and confines the awkward runtime
design to the pathological tail.

**Where a runtime cache is needed, confine it to the matcher.** Per-`ByteMatcher`, matching the
existing contract that `BytePattern` is immutable and shared while `ByteMatcher` is mutable and
single-threaded. No locks, no safe publication, no false sharing, and no new concurrency model
to reason about. Threads rebuild states independently, which is a few dozen closure walks
amortised over millions of records.

A shared cache was considered and rejected: the hot path could be a lock-free read, but
*interning* a set of program counters to a stable state id is a mutable map, and two threads
interning concurrently could assign different ids to the same set. That needs a lock on each
novel state — cheap after warm-up, but it buys back only warm-up cost in exchange for a
concurrency model.

**Do not hold a cache that has stopped paying.** Bound it, and on overflow **flush wholesale
rather than evicting by LRU** — the bookkeeping would sit on the hot path, and a state set that
has blown its budget was never going to be stable, so starting clean is as good as any policy.
Then track the flush rate: if flushing is frequent relative to bytes processed, **abandon the
DFA for that run and fall back to the NFA simulation**. Being this cavalier is only safe
because of the purity property above.

**Which reinforces the ordering.** Every pattern tier 0 absorbs needs none of this — no cache,
no thread policy, no flush heuristic, no fallback. Tier 0 proves determinism once at compile
time; a DFA discovers it per run and may have to throw the discovery away. That is a further
argument for spending the next effort on `SCAN_TO_LAST`/`SCAN_TO_FIRST` and precomputed
closures before building a DFA at all.

---

## D19 — The dialect follows Rust's `regex`, minus its warts

*2026-08-17.* Settled after running Rust's test corpus, which forced the question of whose
spelling this dialect is following. Rust's is the closest relative — RE2-style, leftmost-first,
linear time, byte-oriented — and running its corpus is only evidence of anything if the two
dialects mean the same things.

Four changes, all of which the corpus can now check:

1. **`$` is the end of the input**, not "before a final newline". Perl's rule quietly accepts a
   trailing newline the pattern never mentioned; `\z` and `(?m)$` say the two things separately.
   `\Z` is refused rather than redefined, with a message naming both.
2. **`\w \d \s \b` are Unicode by default**, with `(?-u)` for ASCII. Log data is full of
   accented text, and an ASCII `\w` fails to match it silently — the kind of defect that
   surfaces months later as a report that has been under-counting.
3. **`\p{...}` means Unicode properties only.** `\p{Alpha}` reads as a Unicode property and is
   ASCII in Perl and the JDK; it is now refused, pointing at `[[:alpha:]]` for the ASCII meaning
   or `\p{L}` for the Unicode one. General categories are accepted by short code and long name.
4. **`(?i)` folds across Unicode**, over equivalence classes rather than upper/lower pairs, and
   follows the `u` flag.

Rust's own warts were declined and are listed in
[01-regex-language.md §2.4](../stroom-shapeshifter-regex/design/01-regex-language.md): `(?U)` swap-greed, `\b{start}`, `(?R)`, and
the second spelling `(?P<name>)`.

**Consequences and costs, both real:**

- The JDK stays usable as the differential oracle, because `UNICODE_CHARACTER_CLASS` makes its
  shorthands Unicode too — a test helper compiles the reference with that flag and rewrites `$`
  to `\z`. Every deviation therefore stays *visible* in a test rather than becoming a silent
  difference. The property sets are built from the JDK for the same reason.
- The comparable share of the Rust corpus rose from 306 cases to 645 loaded and 409 executed,
  and finding three engine defects (below) is what that bought.
- **Unicode classes cost throughput.** A Unicode `\w` or `\d` no longer fits a 256-entry byte
  table, so a scan over it runs per character rather than per byte: SYSLOG fell 5382 → 2198
  ops/s before an ASCII fast path in the class matcher brought it back to 4522. The residual
  10–20% is the price of the default, and it is [D18](#d18--a-lazy-dfa-is-back-on-the-table-with-evidence)'s
  case restated — a DFA over bytes does not care how many sequences a class has.

Three defects were found by the widened corpus, all fixed: a match could begin *inside* a
character (so an empty match split one); a repetition followed by `\b` was wrongly judged
one-pass, and a greedy tier 0 scan then ran past the boundary and reported no match at all;
and `(?-u)` was not being applied to case folding.

---

## D20 — The empty-iteration guard is resolved at compile time

*2026-08-17.* The last of the two open divergences, fixed rather than documented: a repetition
whose body can match empty must stop the loop, as both `java.util.regex` and Rust do, where this
engine carried on and consumed.

The rule both references apply is that **an iteration consuming nothing ends the loop** — but the
empty iteration is still taken, and the loop exits *from it*. That distinction is the whole
problem: killing the path instead leaves a lower-preference alternative free to consume, which is
a different wrong answer. `(?:|a)*` against `"aaa"` must match nothing, and does so only because
the path that matched nothing is the preferred one.

**Implementation.** The compiler wraps a nullable loop body in `MARK m` … `PROGRESS m else exit`.
Neither instruction exists at run time: exactly one byte is consumed between one epsilon closure
and the next, so an iteration consumed nothing **precisely when its `MARK` lies on the same
closure path**, and the closure walk resolves `PROGRESS` statically. The alternative — per-thread
iteration-start slots checked in the hot loop — was rejected for that reason.

**Consequences:**

- The closure walk's cycle guard is now keyed on *(instruction, set of marks)* rather than the
  instruction alone. Reaching the same instruction twice — once before entering an iteration and
  once inside it — is not a cycle, and treating it as one silently dropped the second visit. That
  alone was the residual divergence in what a nested group captured.
- The guard is kept in a small per-instruction stack rather than a hash set. A hash set was
  correct and tripled the test suite's running time, because closure construction is quadratic in
  program size; the array form costs what the plain visited flag it replaced cost.
- Eighteen Rust corpus cases held back for this defect now run and agree, and
  `KnownDivergenceTest` keeps them pinned as agreements.
- **A second, worse defect was found while probing the first**: the closure kept only the first
  arrival at each instruction, so an asserted path shadowed an unasserted one and
  `(?:\b|)a` against `"ba"` found nothing at all. Only an unasserted path closes a target off
  now. Worth recording as a pattern: the two defects were the same mistake — a compile-time
  cache keyed on too little state.

---

## D21 — A single JMH fork is not a measurement

*2026-08-17.* Found while testing a scan-loop optimisation: the same binary, benchmarked twice,
differed by 25% on one workload and 21% on another in the opposite direction, while JMH's
reported error stayed near 2% throughout. With `@Fork(1)` that error is the spread within one
JVM — it says one set of JIT decisions was stable, not that the figure reproduces.

**Consequences:**

- `CorpusBenchmark` runs `@Fork(5)`. Anything used to justify a change must be measured that way.
- A change below roughly 25% could not have been detected by the old harness, which retrospectively
  covers several of the finer-grained numbers in
  [05-engine-benchmarks.md](../stroom-shapeshifter-regex/design/05-engine-benchmarks.md); the section now says which claims survive.
  The large ones do — a 50× cliff and a 3.5× fix are not fork noise.
- The general lesson is the one this project keeps relearning: an error bar measures the spread of
  whatever was varied, and if the thing that actually varies between builds was held fixed, the
  bar will look tight and mean nothing.

---

## D22 — Benchmark the whole corpus, not a chosen ten

*2026-08-18.* Prompted by a question — "are you doing JMH against the larger test corpus too?" —
to which the answer was no. `CorpusBenchmark` measured ten patterns picked by the same person who
wrote the engine, while the 106-pattern corpus was used only for correctness.

Adding `PatternCorpusBenchmark` over the whole corpus found that **on a realistic mix of patterns
this engine is slower than `java.util.regex` in every category** — by 20% at best, three hundred
fold at worst — not 1.5× faster. Both statements are true of different populations: tier 0 is
1.35× faster over 78 patterns, and tier 1 is **256×** slower over 28. The published headline
described only the first.

Tracing it found a **regression introduced by [D19](#d19--the-dialect-follows-rusts-regex-minus-its-warts)**:
a Unicode `\w` expands to some seven hundred code point ranges, and the NFA compiler turns each
into UTF-8 byte sequences as a flat alternation with no sharing, so `(\w+):\s*(\S+)` compiles to
**11,007 instructions against 9** under `(?-u)` and runs **1,237× slower**. The Pike VM's cost per
byte is proportional to program size, so class expansion is paid on every input byte.

**Consequences:**

- The next piece of work is **suffix sharing in the NFA compiler**, ahead of
  [D18](#d18--a-lazy-dfa-is-back-on-the-table-with-evidence)'s lazy DFA: it attacks a 264× factor
  rather than a 25–70× one, and shrinks the NFA the DFA would later be built over.
- Two benchmarks are kept, and neither is the headline alone. A change that helps sustained
  scanning and not the pattern mix, or the reverse, is telling you something.
- The general lesson, which is the same one [D21](#d21--a-single-jmh-fork-is-not-a-measurement)
  taught about forks: **a benchmark set chosen by the author measures the author's expectations.**
  Ten patterns had no member that was both tier 1 and Unicode-classed, so a thousand-fold
  regression sat in the engine, through a full benchmark run and a documented performance
  analysis, entirely invisible.

---

## D23 — Character classes compile to a byte-range trie, not an alternation

*2026-08-18.* The fix for the defect [D22](#d22--benchmark-the-whole-corpus-not-a-chosen-ten)
found. A class expanded to UTF-8 byte sequences was emitted as a flat alternation, which made
every sequence a live thread at every input position: `(\w+):\s*(\S+)` compiled to 11,007
instructions and ran 1,237× slower than its ASCII equivalent.

Classes now compile to a trie over byte ranges, sharing at three levels:

1. **Prefixes** — sequences beginning with the same range become one transition into a shared
   subtree, so the dispatch is over distinct continuations rather than over sequences. Suffix
   sharing alone would not have done: the `SPLIT` dispatch is itself one instruction per sequence,
   which is the part that sets the thread count.
2. **Suffixes** — subtrees compiling to the same thing are emitted once, collapsing the
   `[80-BF]` tails that nearly every multi-byte sequence ends in.
3. **Non-adjacent ranges with a common target** — merged into one table-driven branch rather than
   one branch each. This is the common case for a class scattered across Unicode, and it is worth
   more than range coalescing, which measured as no help at all.

Byte instructions therefore need an explicit successor (`Nfa.next[]`, `pc + 1` everywhere else),
since a shared tail is by definition not reachable by falling through.

**Results:** 11,007 → 2,152 instructions and 111,360 → 5,060 ns on the worst pattern; up to **26×**
per category on the corpus benchmark; and the test suite from **6m 24s to 20s**, because closure
construction is quadratic in program size.

**Consequences:**

- **The engine is still slower than `java.util.regex` on the corpus mix**, by 20% to fourteen
  fold. The fix moved the worst case from three hundred fold; it did not make the mix a win.
- **Instruction count is not the cost; closure width is.** Measured: 31 live threads at the start
  state for a Unicode `\w` against 1 for ASCII, and time tracks that rather than program size. A
  further change that halved instructions again moved time by 10%, which is where this stopped.
- The residual is inherent to simulating a 700-range class byte-wise, and collapsing 31 threads
  into one state is the definition of a DFA — so [D18](#d18--a-lazy-dfa-is-back-on-the-table-with-evidence)
  is next, over a program a fifth the size.
- A first attempt emitted the trie children-first so control fell into a subtree; patterns
  silently stopped matching and the apparent speedup was them failing fast. Worth recording as the
  standing hazard of this project: **a large improvement is a defect until proven otherwise.**

---

## D24 — A character class is one instruction, not a set of branches

*2026-08-18.* The follow-on from [D23](#d23--character-classes-compile-to-a-byte-range-trie-not-an-alternation),
and the largest single improvement made to this engine.

D23 established by measurement that the simulation's cost is the **closure width** — the number of
threads added per input position — and not program size: a Unicode `\w` put 31 branches in the
closure where at most one could survive the next byte. So the branches were replaced by a table.
A trie node whose ranges are disjoint, which is nearly always, compiles to a single
`BYTE_DISPATCH` instruction holding 256 successors, and matching a byte is one array lookup.

| | Width | Instructions | `(\w+):\s*(\S+)` |
|---|---:|---:|---:|
| Originally | 36 | 11,007 | 111,360 ns |
| Trie (D23) | 31 | 2,152 | 5,060 ns |
| Dispatch | **1** | 697 | **718 ns** |

**Measured consequences**, five forks, rendered from `benchmarks/2026-08-18-1255`:

- The corpus mix moved from **0.003×–0.83×** of `java.util.regex` to **0.42×–0.83×**. `keyvalue`
  gained a factor of 247 across the two changes, `structured` 147.
- `CorpusBenchmark`'s tier 1 workloads moved 3.4× and 2.4×; its eight tier 0 workloads are
  **indistinguishable**, which is the control working, since a scan plan never touches the NFA.
- The Unicode penalty fell from 56× to 1–7×, and patterns with no Unicode class gained as well:
  `.` is a class like any other, so `^(.+):(.+)$` went 1,823 → 437 ns.
- Byte instructions need explicit successors for this, which D23 had already introduced.

**What it means for [D18](#d18--a-lazy-dfa-is-back-on-the-table-with-evidence), which is now
badly out of date.** D18 argued for a lazy DFA against a 25–70× tier 1 gap. That gap is now
5–20×, and much of what a DFA would have bought — one state transition per byte instead of many
live threads — is exactly what this change delivers, at a fraction of the cost and with none of
the state-cache machinery of D18.1. A DFA is also **no help for captures**, which is what these
patterns exist to extract, so the remaining tier 1 cost is largely capture bookkeeping in the
Pike VM. D18 should be re-argued from current numbers before any of it is built; a bounded
backtracker, which D18 never considered, may suit short records and capture-heavy patterns better.

---

## D25 — A bounded backtracker rather than a lazy DFA

*2026-08-18.* Supersedes [D18](#d18--a-lazy-dfa-is-back-on-the-table-with-evidence), which was
argued from numbers that [D23](#d23--character-classes-compile-to-a-byte-range-trie-not-an-alternation)
and [D24](#d24--a-character-class-is-one-instruction-not-a-set-of-branches) invalidated.

Measurement, not reasoning, decided this — and the first two attempts at measuring it were both
wrong, which is worth recording:

1. **The sampling profiler pointed at capture-slot copying.** Removing the copy entirely, wrong
   captures and all, bought 10%. Half its samples were unattributed and the rest misdirected.
2. **Running the same pattern on both tiers is what worked.** Tier 1 costs a flat **21–27 ns per
   input byte whatever the pattern**, against 1.5–9.5 for tier 0. The constancy is the point: it is
   fixed cost per input *position* — outer loop, thread-list swap, closure indirection, slot
   bookkeeping — not anything that scales with the pattern, and with the thread width now 1 there
   is no multiplicity left to blame.

**Why not the DFA.** Its inner loop would take 24 ns/byte to perhaps 4, a 5× gain on deciding
*where* a match is. But a DFA cannot produce captures, and these patterns exist to extract fields.
DFA-for-the-span then a capture engine over the span saves only in proportion to the input outside
the match, which in per-record matching is almost none of it.

**Why the backtracker.** One pass, captures directly, none of the per-position bookkeeping that was
measured; a visited bitset over (instruction, position) preserves linear time at program × length
bits — about 54 KB for a 2,152-instruction program over a 200-byte record — with the Pike VM as
the fallback beyond a threshold. Short records with captures wanted is exactly the case Rust
maintains one for, and it is a smaller change than a lazy DFA with the state cache of D18.1.

**Consequences:** D18.1's cache design is shelved rather than deleted; if unanchored search over
large buffers ever becomes the dominant shape, the DFA argument returns on its own merits. The
Pike VM stays as the general fallback, so the linear-time guarantee is unaffected either way.

---

## D26 — The backtracker is the default automaton engine, chosen per search

*Superseded in part by [D32](#d32--the-bounded-backtracker-retires-from-the-default-path): the
engine remains, pinnable and under differential test, but no longer runs unpinned.*

*2026-08-19.* Closes out [D25](#d25--a-bounded-backtracker-rather-than-a-lazy-dfa): built,
measured twice, and enabled.

The first measurement was mixed — csv +54.7% but syslog −12.5% and TIER1_GREEDY −11.4% — and
under the rule set at the time it would have reverted the default. The cause was the visited
set being zeroed on every search: sized program × input, it made the engine pay a per-search
cost proportional to the *program* to save per-position costs proportional to the *input*,
and on short inputs the program is the larger. Generation stamping (a byte per cell, stamped
rather than cleared, zeroed once every 127 searches) fixed it: nine of twelve corpus
categories improved, up to +62%, with csv reaching parity with the JDK. TIER1_GREEDY's
regression — diagnosed at the time as *inherent* to depth-first greedy repetition — was also
the clearing, and recovered to +2.6%. One more entry for the list of confident diagnoses that
measurement overturned. See [05-engine-benchmarks.md §9](../stroom-shapeshifter-regex/design/05-engine-benchmarks.md).

**Consequences:** selection is per search, not per pattern — backtracking where its visited
set fits a 128 KB budget, simulation otherwise — so the linear-time guarantee and the
simulation's exact streaming answers are unaffected. `compileForcing` pins an engine through
to the matcher, which is what keeps all three under differential test now that the default
would otherwise never run the simulation on short inputs; the three-way agreement test is the
correctness argument. Programs with the empty-iteration guard always simulate. The residual
syslog −7.5% and fixedwidth −1.7% are accepted and recorded rather than mitigated with a
compile-time heuristic.

---

## D27 — The fancy tier: backreferences and lookaround run natively

*2026-08-19.* Supersedes [D7](#d7--javautilregex-is-a-supported-second-dialect). The constructs
outside the RE2 subset — backreferences (`\1`, `\k<name>`), lookahead, bounded lookbehind,
atomic groups and possessive quantifiers, plus `\Q...\E` and `\G` — compile and run natively
on a fourth engine, `Engine.FANCY`: unbounded backtracking over the same NFA program the other
automaton engines share. The architecture is fancy-regex's — a backtracker layered on an
RE2-style core, taking only the patterns whose syntax asks for it — which is also the answer to
"whose spelling?", since Rust's `regex` deliberately has no backreferences at all.

D7's reasoning was sound when it was made: an unbounded backtracker was the largest item it was
possible to delete. [D25](#d25--a-bounded-backtracker-rather-than-a-lazy-dfa) changed the
arithmetic — with a bounded backtracker built, the unbounded variant is the same interpreter
loop minus the visited set, plus nested sub-matching. The visited set *cannot* survive
backreferences (two arrivals at one (instruction, position) genuinely differ by capture state;
the problem is NP-complete), which is why this is a separate engine rather than a mode of D25's:
the bound is replaced by a step budget, and a pathological pattern-input pair raises
`MatchLimitException` after bounded work. That is D7's §8.3 containment, kept, without the
decode boundary, the `hitEnd()` conservatism or the `StackOverflowError` catching.

What decided the shape, empirically: the harvested corpus's three `java dialect` patterns use
atomic groups and lookahead — **zero backreferences** — so a backref-only tier would have closed
none of the observed gap. All three now compile natively.

**Consequences:**

- One dialect. The `javaRegex` element, its decoding boundary and its capability gate are no
  longer needed; [01-regex-language.md §8](../stroom-shapeshifter-regex/design/01-regex-language.md) is kept as the record of the
  superseded design.
- Entry to the tier is the pattern's own syntax, never a fallback: no cost model, no surprise.
  `explain()` names it, and the linear-time guarantee now reads "every pattern without a fancy
  construct".
- The streaming contract survives unchanged — conservative `NEED_MORE_INPUT` whenever any
  explored path touched a growable window's edge — except that a lookbehind's sub-match, which
  ends at the cursor by definition, does not record edge contact.
- Lookbehind is bounded-length only, backrefs inside lookbehind are refused (no static length),
  and case-insensitive backrefs compare by the JDK's folding rule so the oracle stays usable on
  that corner.
- The four-engine differential (`compileForcing` pins `FANCY` onto any NFA pattern) extends the
  correctness argument; the JDK is a true oracle here since it supports every one of these
  constructs natively.
- `Reason.NOT_RE2` is deleted: nothing is refused for being outside RE2 any more.

---

## D28 — Oniguruma's suite is the fancy tier's corpus

*2026-08-19.* The Rust `regex` corpus ([D19](#d19--the-dialect-follows-rusts-regex-minus-its-warts))
cannot test [D27](#d27--the-fancy-tier-backreferences-and-lookaround-run-natively)'s constructs
— it contains no backreference or lookaround, by that engine's design. Oniguruma's
`test_utf8.c` can: a backtracking engine's own suite, 1,011 cases dense in exactly those
constructs, with expected spans as **byte offsets over UTF-8** — this engine's native
coordinate system, needing no translation. It is the same corpus fancy-regex uses for the same
architecture, taken from the same snapshot.

**Standing: 864 of 1,011 convert, 622 execute, 122 on the fancy tier, zero disagreements.**
The conversion (`tools/convert-oniguruma-corpus.py`) drops only what this parser would
*silently misread* — Oniguruma-only escapes such as `\g<...>` and `\X`, and `\xHH` above
0x7F, which is a raw byte there and a code point here — with a printed tally; refusals stay in
and are counted by reason in the test's report; and 14 cases that compile but ask a question
the dialects answer differently (full case folding of ß, bare-inline-flag regrouping, unset
backreferences matching empty) are listed in an `ignore` file, one reason per line,
fancy-regex's own mechanism for the same corpus.

It found three parser defects before it ever ran green, each a silent approximation of syntax
a reference dialect gives meaning to, and each now a refusal instead:

1. **Duplicate group names were accepted**, making `\k<name>` silently bind to the first
   occurrence; both reference dialects refuse them.
2. **`[` and `&&` inside a character class read as literals**, where Rust reads a nested class
   and an intersection. Refused until class set operations are built.
3. **A quantifier stacked on a quantifier read the brace as a literal** — `a*{2}` matched
   `a*` then `{2}` — where Rust and the JDK refuse, and Oniguruma multiplies.

**Consequences:** Ruby dialect cases compile under MULTILINE (`^`/`$` are always line anchors
there) and `(?m)` is translated to this dialect's `(?s)`, both recorded in the corpus README.
The three `FANCY_*` workloads added to `CorpusBenchmark` at the same time are the fancy tier's
first performance measurement against the JDK — results in
[05-engine-benchmarks.md §10](../stroom-shapeshifter-regex/design/05-engine-benchmarks.md).

---

## D29 — The fancy tier reaches the JDK by structure, not by shedding semantics

*2026-08-19.* Five measured steps took FANCY_LOOKAHEAD from 0.15× of the JDK's backtracker to
statistical parity (0.94×, CI crossing 1.0), with FANCY_BACKREF and FANCY_ATOMIC at ~0.7×.
The ledger is [05-engine-benchmarks.md §10.1](../stroom-shapeshifter-regex/design/05-engine-benchmarks.md); the wins were an ASCII
fast path in the word-boundary test, `CLASS_STAR` (an unbounded byte-safe class repeat as one
instruction with an O(1) backoff frame, fancy programs only), and its mostly-ASCII hybrid for
classes like `\w`.

Two things this settles beyond the numbers:

- **Streaming support costs nothing measurable.** Dropping conservative `NEED_MORE_INPUT` in
  favour of an outer controller was considered as a possible price of performance before the
  work began; every step landed without touching it. The option stays available; the reason to
  take it is gone.
- The first hypothesis was wrong again — the line-anchor start gate bought 5.7% where a
  multiple was predicted, because this workload's matches are dense. Kept for sparse scans;
  logged as the fourth entry in the confident-diagnosis-refuted series (§8's profiler, D26's
  clearing, D28's "one defect").

**Consequences:** `CLASS_STAR` widens the gap between what fancy programs and shared programs
may contain — the Pike VM cannot see through it, which is fine while entry remains per-pattern
and the bounded engines never receive fancy programs, and a constraint to remember if that
ever changes. The remaining ~1.4× on backref/atomic workloads is future work with the method
on record.

---

## D30 — `Engine.TREE`: the JDK's architecture, built to be measured

*2026-08-19.* [05-engine-benchmarks.md §10.2](../stroom-shapeshifter-regex/design/05-engine-benchmarks.md) analysed why
`java.util.regex` can be fast — the JIT specialises a per-pattern node tree, which a
shared-program interpreter structurally cannot have — and the analysis stayed a hypothesis
until an engine existed to test it. So one was built as a fifth, deliberately exploratory
tier: the HIR compiled to node objects with recursion as the undo log, over this dialect and
byte input, **never selected by the compiler**, reached only through `compileForcing`, and
held to identical results by the differential suite.

Both halves of the hypothesis got their answer ([§10.3](../stroom-shapeshifter-regex/design/05-engine-benchmarks.md)):

- **The specialisation is real and priced**: 1.05–1.9× over the flat fancy engine, parity to
  1.2× against the JDK on its home turf — and 5.5×/12.5× over the simulation on the two
  ambiguous workloads that were this project's worst numbers.
- **The predicted profile-pollution decay did not materialise at corpus scale.** Dozens of
  patterns in one JVM leave the tree engine far ahead everywhere an automaton runs today;
  only the scan plan beats it, which is what the scan plan is for. Another confident
  paragraph joins the refuted list.

**Consequences:** the experiment stays an experiment until three things exist — a
recursion-depth guard (call-stack-as-undo meets `StackOverflowError` on long records, the
JDK's own failure mode, which D7 §8.3 planned to contain rather than fix); a decision on the
linear-time guarantee for non-fancy patterns (a budgeted TREE with simulation fallback would
keep the promise while taking the speed); and wider evidence (Oniguruma corpus through TREE,
pollution at hundreds of patterns). If those land, the tier map gets redrawn; until then,
`compileForcing(Engine.TREE, …)` is how anyone asks the question again.

---

## D31 — The tier map, redrawn: the tree engine takes the searches it wins

*2026-08-19.* [D30](#d30--enginetree-the-jdks-architecture-built-to-be-measured)'s three
preconditions landed the same day it set them, so the decision followed. The policy:

- **One-pass patterns**: the scan plan, untouched — it beats every other engine including
  the tree, which is what it is for.
- **Fancy patterns**: the tree engine is the primary, having measured at or ahead of the JDK
  on every fancy workload. The flat unbounded backtracker becomes its structural fallback —
  an explicit stack cannot run out of call-stack depth — and stays pinnable.
- **Ambiguous patterns**: the bounded backtracker keeps every search its bitset can afford,
  unchanged. Beyond the budget — the whole-buffer searches that previously fell to the
  simulation — the tree engine runs first, and the simulation remains both fallback and
  guarantee: whatever the engines give up on, one machine finishes in linear time.

The preconditions, each with its evidence: a **loop-depth guard** (1,024 stacked iterations,
plus a `StackOverflowError` backstop in the D7 §8.3 containment tradition) turns the tree
engine's structural limit into a `Bailout` signal the selection catches — pinned use converts
it to `MatchLimitException` instead of falling anywhere. The **Oniguruma corpus** runs every
executed case through the tree engine, identically, 622 for 622. And the **pollution question**
closed at full corpus scale: per-category tree numbers are stable across a fourteen-category
sweep, and the new `everything` workload (114 patterns, one JVM) showed pinned-tree at 354
ops/s against the mixed policy's 617 — the decay §10.2 predicted never arrived, but the
corpus's own composition settled the larger point: **no single engine is the product; the
selection is.**

Measured on the default path (`2026-08-19-1748`, `-1752`): TIER1_ALTERNATION 7.7× and
TIER1_GREEDY 3.9× over the pre-redraw default; the fancy workloads at their pinned-tree
numbers (FANCY_ATOMIC 1.23× ahead of the JDK); the per-match fancy category 3.7× to 1.56×
ahead of the JDK; short-record searches unchanged, still the bounded backtracker's.

**Consequences:** `engine()` reports the tree for fancy patterns and the simulation for
ambiguous ones — the primary and the promise respectively; `Engine.FANCY` remains the fancy
fallback and a differential witness; every automaton pattern now compiles both a flat program
and a node tree, a compile-time and footprint cost accepted knowingly; and the linear-time
guarantee's wording is unchanged, because the fallback structure preserves it exactly.

---

## D32 — The bounded backtracker retires from the default path

*2026-08-19, evening.* Supersedes [D26](#d26--the-backtracker-is-the-default-automaton-engine-chosen-per-search)'s
default, six commits after [D31](#d31--the-tier-map-redrawn-the-tree-engine-takes-the-searches-it-wins)
gave the tree engine the searches the budget refused. The question D31 left open — which
engine should take the searches the budget *accepts* — was answered by a per-pattern probe of
every automaton pattern in the corpus: the tree engine won 35 of 36, typically 2–4×, and the
single loss (`\S{1,10}`, nine nested optionals costing a frame per level) was a missing node
type, not a verdict. With bounded class repeats compiled to one `CountedClass` node — the
`StarClass` treatment with a ceiling — the score is 36 of 36, the former loss now 3× a win.

So the ambiguous path simplifies: the tree engine first at every region size, the simulation
as fallback and guarantee, and the bounded backtracker pinnable only — still compiled, still
the differential suite's third witness, which is what the correctness argument ever needed
from it. The minimum-length fail-fast (the last quick win from the plan) landed in the same
batch: no engine attempts a start with fewer bytes remaining than the shortest match spans,
on complete windows only, so the streaming contract is untouched.

**Confirmed by JMH** (`2026-08-19-1916`, `-1920`): per-match datetime 2.3× to **1.20× ahead
of the JDK** (was 0.67× behind — a scoreboard deficit erased), fixedwidth 2.3× to **2.0×
ahead** (was 0.86× — erased), identifiers 2.4×, csv 1.9×, syslog 1.6×; and TIER1_GREEDY at
2,288 against pinned-tree's 2,285 — [D31](00-decisions.md)'s recorded selection seam closed
exactly, the backtracker's tail searches having been the missing 19%. The all-plan NETWORK
control did not move.

**A method note, recorded because the method is the asset:** this batch bundled three changes
— the fail-fast, `CountedClass`, and the retirement — into one confirmation run, so the
aggregate numbers do not attribute between them. The retirement's own evidence is the
36-pattern probe table; the fail-fast's share is unattributed and stays that way unless a
future question needs it separated.

**Consequences:** three engines run unpinned — plan, tree, simulation — with the flat fancy
engine and the bounded backtracker as pinnable witnesses and fallbacks. D26's memory-budget
selection logic is gone from the hot path. The scoreboard's remaining deficits are
TIER1_ALTERNATION (0.65×) and NETWORK (0.84× buffer / 0.89× per-match).

---

## D12 — The whole engine comes to Java eventually

*2026-08-17.* Templates, transforms and the output/structure layer that replaces XSLT follow
in later modules; this module is the matching layer.

**Consequences:** keep typed step outputs, capture bindings and step references general enough
to carry a transform layer later — the ds-rs `TypedValue` and `CaptureBinding` model is worth
mirroring rather than simplifying away. Sibling modules under `stroom-shapeshifter` are
expected, so the module naming and package layout should leave room.

---

## D33 — The engine port is a port: ds-rs's behaviour is the specification

*2026-08-20.* `stroom-shapeshifter-engine` is filled by porting the `shapeshifter` crate from
`ds-rs` — 9.7k lines of source, 5.0k of test, and 56 fixture sets — rather than by designing
a Java engine afresh against the same requirements. The fixtures are golden files, several of
them produced by Java Stroom's own DS3 in the first place, so they are the only cheap
oracle available for a layer this large. Redesigning first would have thrown that away.
The plan is [07-engine-port-plan.md](07-engine-port-plan.md).

Three sub-decisions were taken with it:

- **Jackson binds the config.** `project.json` is a serde document with ~40 externally-tagged
  variants; Jackson 3 is in the catalogue and is Stroom's standard. The engine module
  therefore does *not* inherit the regex module's zero-dependency promise, which remains the
  matching layer's alone and stays enforced by `verifyZeroDependencies`. Reading sits behind
  a `ProjectReader` seam so a JDK-only reader is still reachable.
- **The binary formats are deferred, visibly.** Avro, Parquet, Protobuf and the
  snappy/zstd/lz4 codecs each need a large third-party library; the match variants are
  modelled and rejected at compile time with a clear message, and their 3 fixtures stay
  vendored and are reported as skipped. ds-rs's own default-features run already skips the
  same three, for the same reason. Base64, hex, URL-encoding, gzip and deflate are JDK
  built-ins and are in scope.
- **The chunk-boundary limitation is ported too.** ds-rs never lets a match span a buffer
  boundary. Our matching layer's `NEED_MORE_INPUT` outcome could lift that, but reproducing
  the limitation is what keeps golden parity a clean pass/fail across all 51 in-scope
  fixtures. Real streaming is the first follow-up decision after the port is green, not a
  change smuggled into it.

**Evidence that the dialect gamble paid off:** all 208 distinct `pattern` values in the
corpus were compiled through `BytePattern` before the plan was written. 207 compile — 77
`SCAN_PLAN`, 121 `SIMULATE`, 9 `TREE`, atomic groups and `\z` included — and the one failure
is `"+"`, a literal `replace` pattern that Rust rejects as a regex too. D19's choice to
follow Rust's dialect is what makes this a port rather than a rewrite of 247 patterns.

**The baseline is green and measured:** `cargo test --offline` on 2026-08-20 gives 200
passing, 0 failing, 4 ignored — the four being fixture regenerators. 51 fixture sets are in
scope (18 legacy, 18 native, 15 projects) and all 51 pass in Rust today, so any red in the
Java port is the port's, not inherited. One caveat found while establishing this: eleven of
the eighteen `projects` goldens are regenerated from ds-rs's own output by one of those
regenerators, so for those fixtures parity means "same as ds-rs" and cannot catch a bug ds-rs
already has. The `legacy` goldens came from Java Stroom's DS3 and are a real external oracle.

Three further points settled on 2026-08-20, after the baseline was measured:

- **Generated goldens are audited once, then frozen.** Generation was the starting point, not
  a warrant. None of the four regenerators is ported — a fixture that can rewrite its own
  expectation is not a test. *The audit ran on 2026-08-20 and found four of the eleven wrong*
  (`apache_httpd` not well-formed XML, `xml_to_json` not valid JSON, `win_sec` and
  `win_sec_xml` dropping group identity present in their inputs). They are quarantined rather
  than deleted; see [08-fixture-audit.md](08-fixture-audit.md). In-scope expectations are 48.
- **The runners assert on `ParseMessage`s.** Every legacy fixture now has a `.messages`
  golden — 38 messages across 18 fixtures — where the Rust suite compared output only. The
  `.err` files stay as external evidence but are Stroom's format, not the expectation.
  Additive, test-side, and the only deliberate deviation from faithfulness. It immediately
  showed that `maxMatch` templates raise a false "did not consume all content" warning, and
  that both `ignoreErrors` fixtures warn anyway — recorded, not endorsed.
- **The output side goes behind a sink interface from the start**, with a byte sink as the
  only implementation built. D10's open question — what the pipeline element consumes, SAX
  events or something else — stays open, but the seam it needs costs one indirection now and
  a second pass over every `OutputNode` case later.

The Rust project's design documents are vendored unedited under
`stroom-shapeshifter-engine/docs/`, with an index marking which apply to the port and which
describe the node editor that is not being ported.

**Consequences:** behaviour that looks wrong in ds-rs gets ported, recorded, and decided
separately rather than fixed in flight; no performance work happens during the port, because
change-then-measure needs the semantics to hold still; and the engine gets its own benchmark
set and plan only once the suite is green.

**Closed 2026-08-20.** All eight phases done: 48 of 48 in-scope fixtures, 198 tests, both modules
clean. The port found four defects of its own making — a missing pattern-reference cycle guard,
and three places where an `apply-templates` or a message path was subtly wrong — and seven pieces
of ds-rs behaviour worth deciding about, none of them fixed in flight. Both lists, and the three
decisions the port sets up, are in the plan's closing section. The engine's benchmark set is now
unblocked.

---

## D34 — Dispatch follows DS3: iterated ordered choice, with skipping reported

*2026-08-20.* E16's investigation led to reading Java Stroom's own DS3 dispatch loop, which
settled a question the port had unknowingly carried: real DS3 dispatches a level's expressions
as `(A|B|C)*` — first match wins each pass, restarting from the first expression — while ds-rs
designed `A* B* C*`, exhausting each expression before trying the next, and claimed equivalence
in a comment. They are not equivalent, and E1's false-positive warning, E16's "wrong" template
order and the `005`/`014` message divergences are all symptoms of the substitution. The full
write-up, with the evidence quoted, is [09-engine-semantics.md](09-engine-semantics.md).

Decided, with the user:

- **Dispatch becomes `(A|B|C)*`** — DS3's model, which is also XSLT's instinct and what every
  existing configuration was written against. Implementation is E17.
- **Skipping is reported, never silent.** A match starting past the cursor reports the skipped
  content, unconsumed content is reported once per level, both gated on `ignoreErrors` —
  DS3's deliberate design, kept for its reasons: no silent data loss, and pressure toward
  start-anchored expressions, which are also the cheap ones. `matchOrder="any"` (excision) is
  deferred until a configuration needs it (E18).
- **The step layer keeps PEG commitment**, now documented as the design rather than inherited
  as an accident. Atoms are semantics-neutral — the phase 5 claim that they "could not be
  used" with backtracking drivers was wrong, and E14 is reworded to match. Lowering onto the
  combinator layer is an optimisation question that waits for E17.

**Consequences:** the first deliberate behavioural departure from ds-rs since the port —
sequenced exactly as D33 prescribed, decision first, diff second. The message goldens encoding
the false-positive class change under review when E17 lands; output goldens are expected to
survive, and the ratchet names any configuration that depended on `A*B*C*`.

---

## D35 — Two layers, never three

*2026-08-20.* There are exactly two artifacts in this engine, and there will only ever be two:

1. **The user-editable model** — a `Project`, as a pattern is text.
2. **The executable optimised graph** — a `CompiledProject`, as a pattern becomes a
   `BytePattern`. It *performs the execution*; its nodes own their state — matchers, stores —
   as fields.

Everything else people are tempted to put between them is a mistake with a familiar shape, and
this decision exists because the temptation demonstrably recurs. The ds-rs project grew
intermediate layers repeatedly and had to be fought back each time — its own docs record
NodeConfig eras, legacy-node wrappers and multi-path compilation pipelines. And on the very day
this was written, the same session that documented the two-layer target proposed a matcher
*cache*, then a shared-immutable `CompiledProject` with a third "per-run instance graph"
instantiated from it, before the user's question collapsed it back to two.

The recurring rationalisations, pre-refuted:

- *"We need a shared immutable layer for concurrency."* The sharing that matters — pattern
  compilation — lives in immutable values (`BytePattern`) the graph holds; they are shareable
  regardless of what holds them. Concurrency is compile-one-per-instance, measured at
  milliseconds (10-engine-compilation.md §5).
- *"We need a cache for stateful pieces."* Nothing is looked up when state has an owner.
  Matchers and stores are fields of graph nodes.
- *"DS3 has a factory tree."* As an instantiation convenience, not an architecture. Its
  essential shape is config → executable node graph.
- *"Instrumentation needs a wrapper layer."* Decoration is a compile *option* that produces
  different nodes in the same graph, not a layer around it.

**Consequences:** `CompiledProject` carries `ByteMatcher`'s contract one level up — one
execution at a time, reusable sequentially, with a defined reset between streams (E19's
lifecycle). `Executor` is transitional and dissolves into the graph as compilation deepens. Any
design that introduces a third artifact between the model and the graph is wrong until the user
says otherwise.

## D36 — Strict dispatch: the cursor moves only by matching at it

*2026-08-21.* Dispatch control flow is unbundled into four dimensions — binding, iteration,
selection, consumption — and shipped as five named modes chosen per apply site
(`dispatch:` on the directive, source-level default): **`strict`** (at-cursor, the default
for newly authored configurations), **`lax`** (DS3's search-and-skip, kept for migrated
configurations, whose goldens stay frozen), **`any`** (DS3's excision mode), **`classify`**
(one pass, every matching template runs, nothing consumes) and **`lexer`** (maximal munch —
longest match wins, ties to list order). Full design: [11-strict-dispatch.md](11-strict-dispatch.md).

The consequences, each decided with the design rather than discovered later:

- Implicit cursor movement is gone from strict groups. Skipping is authored — any match
  expression carrying a **`consume`** marker, which means *advance, don't count*; a
  consume-marked template may not declare captures (a non-counting match has no index to
  bind them at, and a binding would trip E19's clearing).
- **`emit_error`** joins the body vocabulary — severities include `fatal`, which aborts the
  run — replacing engine-generated skip warnings with authored diagnostics.
- A **zero-advance match in a consuming mode is an error and exits the group**: with
  `classify`, the progressive `Peek` step, and composition available, it has no innocent
  reading left. No compile-time empty-match gate — `*`-quantified patterns legitimately
  smell empty; the run-time rule carries the weight.
- The library's published `leadingAnchor()` becomes a validator in strict groups (the
  anchored question carries the anchoring) and keeps its performance role wherever lax
  dispatch survives. Lints are **warnings** at every strictness until one can *prove*
  author confusion rather than suspect it — amended from errors-in-strict during
  implementation, user-approved: a `(?m)^` pattern asked the anchored question is
  well-defined, and converted configs commonly carry the prefix.

Why: silent content skipping and search-shaped dispatch costs were both symptoms of the
engine doing implicitly what authors should say explicitly. DS3 is precedent, not oracle —
its author's own ruling. Migration cannot silently convert lax to strict: DS3's winner
selection is template-priority-over-position, a strict group with an eater is
position-priority-over-template, so conversion is an authoring act with audited output
changes.

## D37 — Complete inputs only: the streaming surface retires

**Ruled by Jon, 2026-08-24.** The regex library supports byte arrays and byte slices —
complete views, always — and the streaming machinery (`StreamMatcher`, growing
`ByteWindow`s, `MatchOutcome.NEED_MORE_INPUT`, the `complete` flag threaded through every
engine, the `edge`/`hitEnd` latches) retires. The project began as a streams-of-data
design; the verdict, in the author's words, is that this "turned out to be nonsense and
could also never work" for end-anchored matching — and the code agreed before the ruling
did: the executor, the library's only production consumer, buffers and refills at the
window level itself and has never once called the streaming entry. Every one of
2026-08-24's features paid the growing-window toll for that zero-consumer mode (the tail
window, the reverse finder, the harmonised gate, the endgame dispatch all carry
exclusions), and the engines' hottest signatures carry a `complete` argument on frames
where a single extra value measured −8.6%.

What stays, deliberately: `contextEnd` and the beyond-region probe are **slice**
semantics, not streaming — a slice can end mid-character while real bytes continue — so
R1's gate survives intact. The fancy engine's `recordEdge`/`requireEnd` seam is partly
lookaround machinery and is separated with care, not bulk-deleted. If resume-mid-input is
ever genuinely needed, the Pike VM's self-contained thread state remains the natural seed
and this ledger holds the design; the option is kept in escrow, not in live code.

Execution (planned 2026-08-25): delete the stream-only surface and its tests; strip
`complete`/`NEED_MORE`/edge bookkeeping from the five engines as a measured change —
AnchoredSearchBenchmark and EndAnchoredSearchBenchmark either side, with the signature
shrink (8 values back to 7) a plausible win on exactly the frames that have hurt all
week; then the standing eight-angle audit, since the diff crosses the five hottest files.

**Executed 2026-08-25** (`-d37-before-*`/`-d37-after-*`, same boot, idle box, corpus
CSV+DATETIME added to the gate per the new standing rule). Deleted outright:
`StreamMatcher`, `ByteWindow`, `MatchOutcome`, `StreamingTest`, the corpus prefix
differential, and every latch — with one addition the deletion exposed: the window entry
was the only spelling of a fixed region with an advancing search position (the find-next
shape, `\b` at the iteration cursor needing the byte before it — RustCorpusTest pins it),
so `ByteMatcher` gained the five-argument `match(data, regionFrom, from, to, anchoring)`
and the hot four-argument entry is untouched. What stayed stayed: `contextEnd` and the
beyond-region probe everywhere, `requireEnd` and the lookbehind overshoot logic whole,
`ReverseScanner`, and zero engine-module edits.

The predicted win materialised where predicted: `anchored_miss` +8.7% on both the scan
plan and the tree, scan-plan `anchored_hit` +3.7%, WEBLOG hit rows +9.6–10.3%, scan-plan
BOUNDED_MISS +12.2%, forced-tree buffer CSV +13.8%. The costs, recorded not chased: the
simulation's full-scan rows paid −8.2–8.6% (`floating_miss`/`line_miss`), and two probes
cleared both nameable suspects — the inner-loop branch reshape (shape-preserving spelling:
identical numbers) and the arity itself (a dummy eighth argument restored: identical
numbers) — leaving D21's alignment disease with R1's own precedent ("recorded not
chased"); and the default-dispatch corpus rows moved −5–6% while the forced-tree rows on
identical workloads *gained* 5–14%, which is the ByteMatcher-shape coin the open
ISSUES.md item already tracks, flipped again by a class reshape that removed a field.
Suites green both modules, 524 tests.

## D38 — Undecodable bytes are matchable by nothing: strictness is the semantics, leniency is composition

**Ruled by Jon, 2026-08-28.** A character construct — a class, a literal, a dot — matches
only well-formed characters of the pattern's encoding. Bytes that are not part of one are
matchable by no character construct: not by `.`, not by a negated class, not by anything
spelled in code points. Matches are byte spans over the input as given. The engine
introduces no leniency of its own, because the composed pipeline already owns every
deliberate way of choosing some: a codec stage upstream (validate, replace, or reject —
the explicit descendant of 7.x's `ByteStreamDecoder` with `CodingErrorAction.REPLACE` and
its `MalformedBytesReport`), the DS step vocabulary (`MatchByte`, the `Take*` combinators —
which classify bytes under the effective encoding and already carry this exact doctrine in
E5's words: *bytes with no declared meaning earn none*), an upstream shapeshifter in a
composed pipeline, or — in the dialect itself, per the spec that predates this ruling —
`\B{HH}` byte escapes and `Encoding.RAW` (01 §4.4 — specified then; implemented 2026-08-28
by design 19: tables in phase 3, RAW in phase 4, the byte escapes in phase 5, with the
spelling braced to spare `\Bad` its ambiguity).

The ruling was provoked by a wash-up review finding the codebase held three positions at
once. 01 §4.1 already said the strict words ("the compiled matcher only ever recognises
well-formed characters of `E`"); the character-wise engines and the DS steps agreed; but
the tree's greedy `scan()` carried a byte-permissive shortcut beside a strict lazy branch
in the same node (fixed, `c65615bec0` — an inconsistency, now conformed to `accept()`),
and the scan plan's byte-level ops are byte-permissive by construction. Replacement
semantics — decode-with-U+FFFD, the legacy-conformant option — was considered and
rejected: 7.x's REPLACE was an artifact of an engine whose API forced a decode step, not a
product decision that dirty bytes ought to be matchable; and this is an audit pipeline,
where bytes of an event being silently treated as characters they are not is a rewriting
of evidence that must only ever happen as an explicit, logged, configured step.

**The licensed deviation.** The scan plan's byte ops (`SCAN_UNTIL_BYTE` above all — the
memchr shape tier 0 is built on) implement the strict semantics exactly on validly encoded
input and are permissive off it. Validity is therefore the caller's contract, supplied by
composition where a feed cannot promise it; the deviation on contract-violating input is
deliberate, documented (regex 05 §3.2), pinned by `GreedyRunRawBytesTest` so drift
announces itself, and carries a deprioritised closing row in the regex performance plan
should the contract prove unsupplyable in practice. Strictness in the scan loop itself was
rejected on measured grounds: it forfeits the memchr, and buffer CSV lives there.

## D39 — The context bound is the array's end: the `contextEnd` seam is deleted

**Ruled by Jon, 2026-09-03**, on the recommendation in regex design 07 Phase 6. The question
was whether to keep `contextEnd` — the per-engine bound on the one-byte look past the region
end that decides whether a region ends mid-character — as a seam through which a caller could
one day pass a *tighter* bound than the array's length (a reused buffer's stale tail), or to
delete it and read `data.length` where it was consulted.

Read against the record, the ruling had effectively been made already. Every consumer of the
seam, in all four engines and the reverse scanner, was the single start gate
`splitsCharacter(data, at, contextEnd)`; both public entries bound it to `data.length` and
nothing else ever bound it, since D37 retired the growing-window paths. The tighter bound it
kept expressible was one of three options the stale-byte-window entry offered on 2026-08-25,
and that entry's resolution on 2026-08-27 declared all three moot: *the contract is the
answer, not an API* — the array holds the caller's data up to its length, `Executor.stream`
blanks its window's tail, `RegionContextTest` and `WindowTailTest` pin both halves. A seam
whose only purpose is to keep open an option already closed is dead vocabulary, and the code
standard says delete it rather than wire it.

What it buys, beyond the deletion itself: R1 (2026-08-24) bound `contextEnd` as engine state
precisely because threading it as a ninth `search` argument cost −8.6% on the simulation and
an inlining coin-flip on the tree, and the state binding left an accepted ~0.35 ns store on the
tree's instant-rejection rows (−2.3% / −4.3%) and one on `ByteMatcher.match()` setup. All of
that goes: the field, the setter and the assert in each engine, the six binding stores in
`ByteMatcher`, `ReverseScanner`'s fifth argument, and the parameter from `Utf8.splitsCharacter`
and `ByteForm.splitsCharacter`. The contract does not move: it was always the array's length.

Measured under the standing gate, paired against the previous commit minutes apart: tree
`anchored_hit` +0.9%, `anchored_miss` +1.8%, scan-plan `anchored_miss` +3.0%, per-match weblog
+1.6%, per-match datetime flat, buffer CSV tree +3.2% — and buffer CSV scan-plan **−3.2%**, the
one cost. That row first read −8 to −14% with bimodal forks; under forced 32-byte loop
alignment the bimodality vanishes and the −3.2% remains, so the swing was code placement and
the residual is `ByteMatcher`'s object shape — the open class-shape entry in regex ISSUES.md,
regex 07 Phase 4, which now carries this datapoint. The simulation's `line_miss` read −6.2%
and is the D37 coin row: a padding field in `PikeVm` did not move it, and Phase 3's guarded
static gate moved it a further −4% while lifting the simulation's real-work row +3.7%. Landed
with the cost recorded, by direction (2026-09-03), rather than parked behind Phases 3 and 4.

## D40 — Output bridges to SAX by parsing; structured emitters come second, and the sink interprets bytes by container

**Ruled by Jon, 2026-09-03**, eleven rulings in [design 20 §10](20-sax-output.md), on a draft
written 2026-08-28. The question was how an engine whose native output is bytes-that-happen-to-be-XML
meets a pipeline that consumes SAX events, and what that does to the instruction vocabulary,
the goldens and the editor's trace.

**Both, phased.** A pipeline element wraps the byte output in a parser and forwards events,
and that path is permanent — it is the only one that ever serves a configuration whose output
is not XML, and it is what a text-emitting configuration in a SAX pipeline gets. Bridging to
SAX is *optional*: the deployment chooses the sink, a configuration says only what it emits,
and the engine never refuses. Structured emitters — `element` and `attribute` as containers
whose bodies are their content and whose end events are emitted by construction, `namespace`
as a leaf, both leaves preceding content — arrive second, and must serialise byte-identically
to today's text path, the twenty-odd fixtures that pin their bytes against Stroom's own DS3
being the gate rather than something to rebase.

**The ruling that moved three times in one sitting, and where it rested.** How `text` and
`value-of` behave inside a container was first ruled "reinterpreted as character data",
then "unchanged, but wrapped in a new `characters` instruction", and finally: unchanged, and
no wrapper — the executor already knows the container it is in, so `OutputSink` is told and
interprets `write` accordingly (raw at document level, content in an element, value in an
attribute). The distinction that settled it is which layer holds the fact: not the instruction,
the sink. No instruction changes meaning by where it sits, no new instruction exists to say
what the nesting already says, and a configuration that opens no container is byte-transparent
by construction. Attribution follows the same instinct: event ordinals when writing events,
byte offsets when writing bytes, one `Instrument` contract that says which.

The plan with tests and gates is [design 21](21-sax-bridge-plan.md). Its phase 0 corrected
the draft's one factual error before anything was built on it: the migration's reference
values were never unescaped — `escapeCaptures` generates a `translate` per value — which
turned a claimed defect into the sharper point that the port had to generate machinery DS3
never needed. E31 proceeds; E15, `blocked` on D10 since 2026-08-17, unblocks.
