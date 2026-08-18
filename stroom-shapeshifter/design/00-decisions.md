# Decision Log

Decisions taken during design, with their consequences. Newest last.

---

## D1 — RE2-style dialect, not full `java.util.regex`

*2026-08-17.* The engine's own regex language is an RE2-style subset: no backreferences,
lookaround, atomic groups or possessive quantifiers, and a linear-time guarantee.

**Consequences:** enables the Pike VM, which is what makes streaming suspendable at a chunk
boundary; enables the one-pass analysis behind tier 0. See [01-regex-language.md §2](01-regex-language.md).

Superseded in part by [D7](#d7--javautilregex-is-a-supported-second-dialect).

---

## D2 — Character classes compile through the encoding

*2026-08-17.* A pattern is authored in code points; the compiler expands every literal and
class into byte-sequence alternations for the target encoding.

**Consequences:** `\w` matches `0xE9` under Latin-1, which neither Rust's byte regex nor a
`(?-u)` workaround can express. See [01-regex-language.md §4](01-regex-language.md) and
[02-engine-design.md Appendix A](02-engine-design.md).

---

## D3 — Streaming is a first-class outcome

*2026-08-17.* Matching returns `MATCH` / `NO_MATCH` / `NEED_MORE_INPUT`, for both finite and
unbounded sources.

**Consequences:** constrains the VM design (state must be suspendable); rules out a
backtracking engine as the primary tier; requires a window manager with a caller-supplied
maximum. See [01-regex-language.md §7](01-regex-language.md).

---

## D4 — Greenfield language, existing patterns as a test corpus

*2026-08-17.* No obligation to run existing DS3 patterns unchanged, but they are harvested as
a differential-test corpus against `java.util.regex`.

**Consequences:** [02-engine-design.md §9.2, §9.6](02-engine-design.md).

---

## D5 — Hard encodings are transcoded upstream, not compiled

*2026-08-17.* UTF-8, `RAW` and single-byte charsets compile into the pattern. UTF-16,
Shift-JIS, EUC-*, ISO-2022-JP, GBK, GB18030 and Big5 are converted by a `transcode` pipeline
stage instead.

**Consequences:** removed the variable-width class enumeration, the self-synchronisation
machinery and the ISO-2022-JP rejection from the engine entirely; added a scoped stage with a
checkpointed offset map and an explicit malformed-input policy. The dividing line is offset
preservation, not difficulty. See [02-engine-design.md §4](02-engine-design.md).

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
compiler warning when a pattern did not need it. See [01-regex-language.md §8](01-regex-language.md).

---

## D8 — Compose at authoring time, flatten at compile time

*2026-08-17.* A composition is a description, lowered into the same byte IR as a regex and
compiled to one flat plan — not a tree of `Matcher` objects walked at runtime.

**Consequences:** composability costs nothing at runtime; a readable composition and an
equivalent dense regex compile to the same plan. This is the Java substitute for the
monomorphisation that makes nom fast in Rust. See [02-engine-design.md §6.5](02-engine-design.md).

---

## D9 — Benchmark baseline before engine code

*2026-08-17.* First code written is the JMH baseline, not the engine.

**Consequences:** confirmed the tier 0 thesis (2.7–4.0×) and refuted two representation
assumptions before they were built on. See [03-baseline-results.md](03-baseline-results.md).
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
  [01-regex-language.md §6.5](01-regex-language.md)). The engine only ever sees the resolved
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
   ([03-baseline-results.md §4.1](03-baseline-results.md)). Vectorised scanning gives bytes
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

**Revisit if:** follow-up 7 in [03-baseline-results.md](03-baseline-results.md) shows the
char-to-byte boundary cost is material, or if vectorised scanning fails to close the 6%.

---

## D14 — Tier 0 is interpreted, with flat opcodes and byte-table classes

*2026-08-17.* Settled by prototype measurement rather than argument
([03-baseline-results.md §7](03-baseline-results.md)):

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

*2026-08-17.* The design ruled out a lazy DFA as a v1 non-goal, "revisit with benchmark
evidence". Measuring the same pattern through both engines produced it: tier 0 at 5379 ops/s
against tier 1 at 55.7, a **91× engine-cost gap**, with roughly half the corpus on the slow side.

Three cheaper explanations were tested and refuted — capture-slot allocation, enum `values()`
cloning, and class expansion in the NFA. Fixing the first two removed essentially all allocation
(21.9 MB/op → 3 KB/op) and gained tier 0 21%, but moved tier 1 barely at all. The remaining cost
is the epsilon-closure walk re-derived at every input byte, which is what a plain Pike VM costs
and why RE2 uses a lazy DFA to find match bounds before running the simulation for captures.

**Consequences:** both cheaper options have since been tried and neither closes the gap
([03-baseline-results.md §8.4](03-baseline-results.md)). Widening tier 0 turned out to be
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
[01-regex-language.md §2.4](01-regex-language.md): `(?U)` swap-greed, `\b{start}`, `(?R)`, and
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
  [05-engine-benchmarks.md](05-engine-benchmarks.md); the section now says which claims survive.
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

## D12 — The whole engine comes to Java eventually

*2026-08-17.* Templates, transforms and the output/structure layer that replaces XSLT follow
in later modules; this module is the matching layer.

**Consequences:** keep typed step outputs, capture bindings and step references general enough
to carry a transform layer later — the ds-rs `TypedValue` and `CaptureBinding` model is worth
mirroring rather than simplifying away. Sibling modules under `stroom-shapeshifter` are
expected, so the module naming and package layout should leave room.
