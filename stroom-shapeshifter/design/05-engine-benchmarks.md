# Engine vs `java.util.regex` — corpus coverage and throughput

Run: 2026-08-17, re-run after the dialect moved to Rust semantics ([D19](00-decisions.md)).
JMH 1.37, JDK 25, **5 forks**, 3×1s warmup, 5×1s measurement, one machine.

> **Read §2.0 before quoting any number below.** The forks are not a detail: measured with one
> fork, as these were until this run, the per-workload figures move by up to 25% between runs of
> the *same binary*.

Production DS3 configurations were not available, so a corpus was written instead:
`stroom-shapeshifter-regex/src/test/java/stroom/shapeshifter/regex/corpus/`. It was written to be
**unflattering** — deliberately including constructs expected to be refused and shapes expected
to land on the slow tier — because a corpus assembled by the same hand that wrote the engine
otherwise measures only its author's expectations.

---

## 1. Coverage: 114 patterns across 13 categories

| Verdict | Patterns | Share |
|---|---:|---:|
| tier 0 (scan plan) | 78 | 68% |
| tier 1 (NFA simulation) | 28 | 25% |
| `NOT_RE2` — needs the java dialect | 8 | 7% |
| `UNSUPPORTED` — not implemented | **0** | — |

Of the 106 patterns the engine accepts, **74% reach tier 0**, and the only refusals left are the
eight that genuinely need the java dialect: backreferences, lookahead, lookbehind, atomic groups
and possessive quantifiers. That is worth comparing with the
38% measured over this repository's own DS3 test fixtures
([04-corpus-analysis.md](04-corpus-analysis.md)): those fixtures are unusually heavy in `.*` and
optional-prefix ambiguity, and are not representative of realistic patterns. It also weakens the
urgency of [D18](00-decisions.md), since the slow tier turns out to be a smaller share of real
work than the fixtures suggested.

**All 1,377 pattern/input comparisons agree with `java.util.regex` exactly** — same match, same
span, same groups, same non-participation.

The two feature gaps this first exposed — word boundaries and Unicode property classes — have
since been implemented, which is why the `UNSUPPORTED` count is now zero:

- **`\b` and `\B`** use the Unicode word definition, walking back over continuation bytes to
  find the character before the cursor. They are treated as undetermined at the edge of a growing
  window, since the byte *after* the cursor decides them as much as the byte before.
- **`\p{...}`** covers general categories by short code and long name, the `Is` names and
  scripts, built by asking the JDK about every code point once and caching. The ASCII POSIX
  classes are spelt `[[:alpha:]]` and only that way; `\p{Alpha}` is refused rather than given
  one of its two possible meanings ([D19](00-decisions.md)).

The tier 0 share fell from 77% to 74% with the dialect change, for a reason worth stating: a
repetition followed by `\b` is no longer judged one-pass. It never was — a greedy scan runs past
the boundary and cannot give the input back — but the predicate did not model it, and tier 0
answered `(?-u:\b).+(?-u:\b)` on `"$$abc$$"` with *no match at all*. The Rust corpus found it.

---

## 2. Throughput, by workload

### 2.0 How much of a difference this harness can actually see

Testing a small optimisation produced a result that looked like a 7% gain on three workloads and
a 10% loss on a fourth. Re-running the **same binary** produced this instead:

| Workload | Run 1 | Run 2 | Difference |
|---|---:|---:|---:|
| QUOTED | 8858 | 6655 | **−25%** |
| ALTERNATION | 8329 | 10061 | **+21%** |
| CSV | 3576 | 3362 | −6% |
| NETWORK | 3419 | 3434 | 0% |

Nothing changed between them except the JVM's own decisions. JMH's reported error stayed near 2%
throughout, because with `@Fork(1)` that error is the spread *within* a single JVM: it measures
how stable one set of JIT decisions was, not whether the number reproduces. A tight error bar on
a single fork is not evidence, and two builds compared that way cannot be told apart below about
25%.

**Consequences, stated plainly:**

- The benchmark now runs `@Fork(5)`, so the reported error spans JVM invocations and means what a
  reader will assume it means.
- The optimisation that prompted this — hoisting the ASCII test out of the per-character class
  matcher into the scan loop — is **unproven and was reverted**. It may help; this harness could
  not show it.
- **The per-workload ratios were over-precise on the old harness**, though re-measuring properly
  has largely vindicated them: NETWORK is still the weak case and KEYVALUE still sits at parity,
  now with intervals attached rather than a single decimal place. The large findings were never
  in doubt, being far outside the noise band: the tier 1 cliff (25–70×), the leading-anchor fix
  (837 → 2932), and the Unicode class regression and its recovery (5382 → 2198 → 4522).

Each workload runs a realistic pattern over 2,000 records and extracts every capture group.
`javaRegexFromBytes` adds the `new String(bytes, UTF_8)` a byte pipeline must perform first.

| Workload | Tier | Ours | `java.util.regex` | Ratio |
|---|---:|---:|---:|---:|
| CSV | 0 | 3556 ± 14 | 1391 ± 33 | **2.56×** (2.49–2.63) |
| DATETIME | 0 | 5200 ± 16 | 2423 ± 31 | **2.15×** (2.11–2.18) |
| QUOTED | 0 | 7332 ± 718 | 3863 ± 57 | **1.90×** (1.69–2.12) |
| WEBLOG | 0 | 3269 ± 21 | 2052 ± 132 | **1.59×** (1.49–1.71) |
| ALTERNATION | 0 | 8546 ± 117 | 5886 ± 237 | **1.45×** (1.38–1.53) |
| SYSLOG | 0 | 4661 ± 52 | 3469 ± 120 | **1.34×** (1.28–1.41) |
| KEYVALUE | 0 | 4540 ± 15 | 4243 ± 222 | **1.07×** (1.01–1.13) |
| NETWORK | 0 | 3333 ± 180 | 4321 ± 83 | **0.77×** (0.72–0.83) |
| TIER1_GREEDY | 1 | 410 ± 2 | 2160 ± 42 | **0.19×** (was 0.04×) |
| TIER1_ALTERNATION | 1 | 217 ± 1 | 4105 ± 101 | **0.05×** (was 0.02×) |

Errors are JMH's, spanning five forks; the ratio band is the worst and best pairing of the two
intervals. Geometric mean over the eight tier 0 workloads: **1.53×**. Rendered from
`benchmarks/2026-08-18-1255-de1341be20.json`.
<p>
Tier 0 is unchanged from before [D23](00-decisions.md) and [D24](00-decisions.md) — every one of
those eight is *indistinguishable* run to run, which is the control working, since a scan plan
never touches the NFA. Tier 1 moved by 3.4× and 2.4×.

### 2.0b Bytes against characters: what each side is charged

The two engines do not take the same input, so every benchmark runs three variants and the ratio
uses the middle one:

| Variant | What it does |
|---|---|
| `shapeshifter` | Matches `byte[]` directly |
| `javaRegexFromBytes` | `new String(bytes, UTF_8)` **and then** matches — the honest comparison for a byte pipeline, and what every ratio in this document is against |
| `javaRegex` | Matches a `String` already in hand — the JDK's upper bound |

What decoding actually costs the JDK depends on how far it is amortised, and the two benchmarks
bracket the realistic range:

- **`CorpusBenchmark`: −3% to +10%, mostly noise.** It decodes a 2,000-record buffer once and then
  matches every record in it, which is what a streaming pipeline does. Some workloads measure
  *faster* with the decode included, which is the clearest possible sign that at this amortisation
  the cost is below the noise floor.
- **`PatternCorpusBenchmark`: +3% to +23%, around 13% typical.** It decodes per input, because it
  matches many patterns against short strings.

So decoding is not what makes the corpus numbers what they are: charging the JDK nothing at all
for it would move `keyvalue` from 0.08× to 0.09×.

**The asymmetry that remains runs the other way, and is not charged to us.** Both sides extract
group *lengths*, so neither materialises a field value. In production this engine hands back byte
spans, and a consumer that wants a `String` must decode each one — a cost the JDK has already paid
by holding characters throughout. That is the right comparison for a pipeline which keeps
everything as bytes, which is what Shapeshifter is for, but it flatters this engine against a
consumer that wants strings out. Worth measuring separately before any claim is made about
end-to-end field extraction.

### 2.1 Tier 0 is faster, but by less than the microbenchmark implied

Between 0.77× and 2.39×, averaging 1.53× — against 2.7–4.0× from the earlier hand-written
scanner comparison ([03-baseline-results.md](03-baseline-results.md)). The difference is that
this measures the real task: realistic patterns with many capture groups, extracting every one.
The scanner comparison measured a simpler kernel. **1.5× on real patterns is the number to
quote**, not the earlier one.

`NETWORK` and `KEYVALUE` sit at parity or below. Both have many capture groups over very
short fields, so they are dominated by per-group slot writes rather than by scanning — the shape
where the JDK's specialised node walk is competitive and where a scan plan has least to offer.
That is a fair description of the engine's weak case and worth keeping visible.

### 2.1a What Unicode-by-default cost, and what got it back

Making `\w \d \s` Unicode ([D19](00-decisions.md)) is not free in a byte engine. An ASCII
`\d` is ten entries in a 256-byte table and a scan over it is one lookup per byte; a Unicode
`\d` is `\p{Nd}`, which compiles to dozens of UTF-8 byte-range sequences and has to be scanned
per *character*. The first measurement after the change:

| Workload | Before the change | After | With the ASCII fast path |
|---|---:|---:|---:|
| SYSLOG | 5382 | 2198 | 4522 |
| KEYVALUE | 4592 | 1835 | 4404 |
| WEBLOG | 4491 | 1889 | 3757 |
| NETWORK | 4010 | 2464 | 3206 |

Losing 60% of the throughput on three workloads is not a price worth paying for a default. The
fix is one branch in the class matcher: **a byte below `0x80` can only ever begin a one-byte
sequence**, so the lead-byte table has already decided it and the sequence list need never be
consulted. Input that is overwhelmingly ASCII — which log data is, even when it contains some
accented text — then costs one table lookup per character again.

What remains is a real 10–20% against the ASCII-only engine, and it is
[D18](00-decisions.md)'s argument restated: a DFA over bytes does not care how many sequences a
class expands to, so the cost disappears entirely on the tier it would replace.

### 2.2 One defect found and fixed by this benchmark

CSV initially measured **0.77×** — slower than the JDK on the pattern that should suit a scan
plan best. The cause was that an unanchored search attempted a match at every byte offset,
resetting capture slots each time, only for the leading `^` to reject it. Patterns are now
checked for a leading start-anchor and only attempted where that anchor can hold, which for a
`^`-anchored pattern is the line starts alone.

CSV went **837 → 2932 ops/s, a 3.5× improvement**, taking it from the worst ratio to the best.
Most real DS3 patterns are `^`-anchored, so this applies broadly.

### 2.3 The tier 1 cliff is confirmed on realistic patterns

27× and 71× slower than the JDK. This is the same gap [D18](00-decisions.md) records, now
measured on realistic patterns rather than a synthetic one. Set against §1, the picture is:
**the cliff is severe but applies to roughly a fifth of accepted patterns**, and an author can
be told which side they are on, because the engine reports the tier and the reason.

---

## 3. Correctness findings

Generated patterns — synthesised from the grammar and compared with the JDK — found **two tier 0
defects that produce wrong answers**, both since fixed. Both are the same underlying mistake in
the one-pass predicate, which is worth recording because it recurred:

> Committing to a branch or an iteration on the strength of one byte is only safe if that choice
> **cannot fail later**. Where an alternative is always available — an empty branch, or simply
> stopping — a construct that consumes and then fails needs to be undone, and a forward-only
> scan cannot do it.

1. **An alternation with a nullable branch.** `-[0-9]|\s*` on `"-: 0"` matched at offset 1
   instead of 0: the dispatch committed to `-[0-9]`, which then failed, and the plan could not
   fall back to the empty branch. Order is irrelevant — it decides only which branch wins when
   both succeed.
2. **A repetition that can simply stop.** `(?:,,{2}){0,3}` on `",cx"` failed entirely instead of
   matching empty: entering an iteration on the first `,` could not be undone when the body
   failed part way. A one-element body cannot fail this way, so `[a-z]*` and friends are
   unaffected.

Both now fall to tier 1, which handles them correctly.

### 3.1 The empty-repetition defect, and how it was fixed

**A repetition whose body can match empty** (`(a*)+`, `(?:|a)*`, `(?:^|a)+`) was originally filed
as a difference of opinion, on the grounds that the JDK looked idiosyncratic. Adopting the Rust
corpus ([§6](#6-the-rust-regex-corpus)) settled it — **Rust agreed with the JDK against this
engine** — and it was broader than first recorded: it changed whether a match was found at all,
not only what a group held. `(?:|a)*` against `"aaa"` matched three bytes where both references
match nothing.

Both references apply the same rule: **an iteration that consumes nothing ends the loop.** The
subtlety is that the empty iteration must still be *taken*, and the loop exited from there,
because the priority of that path is what makes the empty match win. Killing the path instead
leaves a lower-preference alternative to consume, which is the wrong answer by a different route.

The fix costs nothing at run time, because it is resolved during closure construction:

- The compiler wraps a nullable loop body in `MARK m` … `PROGRESS m else exit`.
- Exactly one byte is consumed between one epsilon closure and the next, so an iteration consumed
  nothing **precisely when its `MARK` lies on the same closure path**. The closure walk therefore
  decides `PROGRESS` statically, and the run time never sees either instruction.
- The walk's cycle guard had to become "this instruction, under this set of marks" rather than
  "this instruction". Reaching the same instruction twice — once before entering an iteration and
  once inside it — is not a cycle, and treating it as one lost the second visit and everything
  after it. That was the whole of the remaining divergence in what a nested group captures.

Re-running the throughput table above after the fix gives the same figures within noise, on both
tiers, which is the claim "costs nothing at run time" measured rather than asserted. Closure
construction is a little slower — the suite went from 5m30 to 6m25 — and that is where the cost
landed, once. Keying the guard in a hash set instead of a small per-instruction array cost three
times that, because closure construction is quadratic in program size.

**A second defect surfaced while probing the first**, and it is the more serious of the two: the
closure kept only the *first* arrival at each instruction, so an asserted path shadowed an
unasserted one. When the assertion failed at run time the thread was never added at all, and
`(?:\b|)a` against `"ba"` found **nothing** where both references find `a`. Only an unasserted
path may close a target off now.

### 3.2 What still diverges

- **A capture inside two repetitions** (`((\S){0,3}x){2}`). This engine reports the last matching
  iteration, which is the rule everywhere else; the JDK reports an earlier one, apparently as a
  consequence of how it restores group boundaries while backtracking. Seven neighbouring shapes
  agree exactly, so this one is narrow, and no reference has been found that agrees with the JDK.
- **Look-around sees the region, not the buffer** ([§6](#6-the-rust-regex-corpus)).

## 6. The Rust `regex` corpus

Adopted under Apache-2.0 (`src/test/resources/rust-regex/`, converted from the upstream TOML by
`tools/convert-rust-corpus.py` so the module keeps zero dependencies).

**665 of its 771 cases convert; 645 load and 409 execute.** The conversion drops only tests that
ask a *different question* — leftmost-longest semantics, earliest/overlapping search, match
limits, regex sets, configurable line terminators, haystacks that are not UTF-8, and Rust's
byte-oriented mode over non-ASCII input. It deliberately does **not** drop tests this engine
merely refuses to compile: those are counted in the run's report, so the scope boundary stays
visible instead of being filtered away. The refusals are dominated by `(?R)` (108) and
`\b{...}` (90), both Rust-only, plus ten quantified zero-width assertions this engine declines
on purpose.

Adopting Rust's semantics ([D19](00-decisions.md)) is what raised the comparable count from 306:
the previous conversion excluded 180 non-ASCII cases and 34 Unicode-shorthand cases purely
because the dialect then disagreed about them.

It has now found **ten defects** no locally written test had:

| Defect | Effect |
|---|---|
| Inline `(?m)` never reached the compiler | Anchors silently ignored the flag; now resolved at parse time |
| `(?i)` leaked out of its group | `(?:(?i)foo)|Bar` folded case in `Bar` too |
| POSIX classes inside brackets (`[[:word:]]`) | Silently mis-parsed as literal characters |
| `\b{start}` mis-parsed | Rust-only syntax read as `\b` followed by a literal brace; now refused |
| `StreamMatcher` yielded a duplicate empty match | An empty match abutting a non-empty one was reported twice |
| A match could begin inside a character | An empty match split a code point, giving offsets no caller can use |
| A repetition followed by `\b` was judged one-pass | **Wrong answer**: `(?-u:\b).+(?-u:\b)` on `"$$abc$$"` found nothing |
| `(?-u)` did not switch case folding to ASCII | `(?i)(?-u)[a-z]` still matched the Kelvin sign |
| A repetition whose body matched empty carried on consuming | **Wrong answer**: `(?:\|a)*` on `"aaa"` matched three bytes, not nothing ([§3.1](#31-the-empty-repetition-defect-and-how-it-was-fixed)) |
| An asserted epsilon path shadowed an unasserted one | **Wrong answer**: `(?:\b\|)a` on `"ba"` found nothing at all |

Only the fifth is one the chunking tests could ever have caught, and they did not, because they
compare the engine against itself. That is the argument for an external corpus in one line.

**426 cases now execute**, up from 409: with the empty-repetition defect fixed, the eighteen
cases held back for it run and agree. Three remain excluded at run time, each named individually
so the exclusion cannot quietly widen, all for the same reason — Rust lets look-around see bytes
outside the searched span, while this engine's region start *is* the start of the input.

---

## 4. Test suite

| Suite | Tests | What it covers |
|---|---:|---|
| `CorpusDifferentialTest` | 2 | 114 patterns × inputs against the JDK; coverage report; no early decision on partial windows |
| `GeneratedPatternTest` | 8 | Patterns synthesised from the grammar, ~10k comparisons per seed |
| `DifferentialTest` | 19 | Both tiers, ASCII and non-ASCII, tier-against-tier equivalence, and every shorthand against the JDK code point by code point |
| `NestedChoiceTest` | 9 | Deep and overlapping alternation, group by group |
| `StreamingTest` | 15 | Every chunking of every input agrees with the whole |
| `Utf8ConformanceTest` | 19 | Exhaustive over the BMP for the class compiler |
| `BytePatternTest` | 35 | Matching, rejection reasons, plan specialisation, the dialect's Unicode defaults |
| `CombinatorTest` | 15 | Compositions compile to the same plan as the equivalent regex |
| `RustCorpusTest` | 1 | 409 cases from the Rust `regex` corpus |
| `OnigurumaCorpusTest` | 1 | 622 cases from Oniguruma's UTF-8 suite, 122 of them on the fancy tier |
| `CorpusAnalysisTest` | 1 | Classifies this repository's own DS3 fixtures by tier |
| `KnownDivergenceTest` | 4 | The remaining divergence, and the two fixed ones, pinned |
| `ScannersTest` | 3 | The hand-written scanners the baseline benchmarks measure |
| **Total** | **131** | |

The JDK is still the oracle for the first four, which needed one addition to survive the dialect
change: `JdkOracle` compiles the reference with `UNICODE_CHARACTER_CLASS` and `UNIX_LINES`, and
rewrites an unescaped `$` to `\z`. The rewrite is deliberately narrow — a translation layer that
papered over real disagreement would turn the differential suite into a tautology.

---

## 5. What to do with these numbers

1. **Quote 1.5×, not 4×.** Tier 0 beats the JDK on realistic patterns by a useful but modest
   margin, and the honest case for this engine rests on streaming, byte-native operation,
   composability and binary support — not on being several times faster at regex.
2. ~~**Word boundaries are the top feature gap.**~~ Implemented, along with `\p{...}`. No
   realistic pattern in the corpus is now refused for want of a feature.
3. **The tier 1 cliff is real but bounded.** Roughly a quarter of accepted patterns, with the
   engine able to say which. That is an argument for the DFA, but a weaker one than the DS3
   fixtures implied.
4. **Keep generating patterns.** Two wrong-answer defects in tier 0 came from generated cases,
   neither of which a hand-written corpus had found — and a third came from the Rust corpus.
   Every wrong answer found so far has come from a corpus nobody wrote by hand for this engine.

---

## 7. The whole corpus, and the defect it exposed

`CorpusBenchmark` measures ten patterns the author picked. `PatternCorpusBenchmark` measures all
106 the engine accepts, over the same inputs the correctness suite uses. The second was added
after the question "are you benchmarking the larger corpus too?", and the answer it gave is the
most important measurement in this document.

| Category | Ours | `java.util.regex` | Ratio | Before ([§7.4](#74-what-was-done-about-it)) | Originally |
|---|---:|---:|---:|---:|---:|
| syslog | 62164 ± 482 | 74834 ± 484 | **0.83×** | 0.16× | 0.01× |
| network | 254476 ± 1349 | 308890 ± 2717 | **0.82×** | 0.83× | 0.83× |
| stress | 17943 ± 113 | 22139 ± 228 | **0.81×** | 0.14× | 0.01× |
| keyvalue | 57071 ± 1872 | 77048 ± 685 | **0.74×** | 0.08× | 0.003× |
| numbers | 164054 ± 1042 | 232800 ± 3746 | **0.70×** | 0.25× | 0.04× |
| csv | 78129 ± 950 | 119043 ± 676 | **0.66×** | 0.22× | 0.21× |
| fixedwidth | 267291 ± 1442 | 407834 ± 4428 | **0.66×** | 0.15× | 0.09× |
| weblog | 76740 ± 641 | 125587 ± 2410 | **0.61×** | 0.19× | 0.07× |
| quoted | 135552 ± 510 | 259606 ± 4280 | **0.52×** | 0.13× | 0.08× |
| datetime | 82465 ± 827 | 164474 ± 2458 | **0.50×** | 0.21× | 0.04× |
| structured | 112338 ± 352 | 255637 ± 4454 | **0.44×** | 0.07× | 0.003× |
| identifiers | 85585 ± 466 | 201984 ± 1529 | **0.42×** | 0.34× | 0.33× |

Rendered from `benchmarks/2026-08-18-1255-de1341be20.json` by `tools/render-benchmark.py`,
against `2026-08-18-0920` and `2026-08-18-0752` for the last two columns.
Compilation is in `@Setup` on both sides, matchers are built there too and the JDK's are reused
with `reset`, so this times execution only, warm. An earlier run that built our matcher inside the
timed method — charging us an allocation proportional to program size, amortised over twenty short
inputs — differed by about 3%, and *flattered* us: giving the JDK the same treatment gained it
more than hoisting ours gained us, which is why `network` moved from 1.10× to 0.83×.

**On a realistic mix of patterns this engine is still slower than `java.util.regex` in every
category** — but now by between 1.2× and 2.4×, where the first measurement of this benchmark put
the worst case at *three hundred* fold. `keyvalue` went 0.003× → 0.74×, a factor of 247. Every
headline in §2 remains a statement about tier 0 alone, and no configuration consists only of
tier 0 patterns; the difference is that the rest is now within sight rather than off the scale.

### 7.1 Where it goes: 78 patterns are fine, 28 are catastrophic

Timing each pattern separately separates the two populations completely:

| | Patterns | Ours | `java.util.regex` | Ratio |
|---|---:|---:|---:|---:|
| tier 0 | 78 | 77.4 µs | 104.3 µs | **1.35×** |
| tier 1 | 28 | **12,230 µs** | 47.7 µs | **0.004×** |

Both columns use a reused matcher, as the benchmark now does. Tier 0 agrees with §2, which is the
check that the two benchmarks measure the same engine. Tier 1 is **256× slower**, not the 25–70×
§2.3 reports — and building a matcher per pattern instead changes it by 3% (12,230 → 12,577 µs),
so it is not construction cost.

### 7.2 The cause: Unicode classes blow up the NFA

| Pattern | Unicode | `(?-u)` | Penalty |
|---|---:|---:|---:|
| `(\w+):\s*(\S+)` | **11,007 instructions**, 111 µs | 9 instructions, 90 ns | **1237×** |
| `^(\w+\|\d+\|\s+)+$` | 23,183 instructions, 222 µs | 43 instructions, 655 ns | 340× |
| `^([^\s]*)\s+([^\s]*)$` | 313 instructions, 2.7 µs | 10 instructions, 55 ns | 50× |
| `^(.+):(.+)$` | 186 instructions, 1.8 µs | 186 instructions, 1.8 µs | 1× |

A Unicode `\w` is some seven hundred code point ranges, and the NFA compiler expands each into
its UTF-8 byte sequences as a flat alternation with **no sharing between them**. The Pike VM's
cost per input byte is proportional to the number of live threads, which is proportional to
program size — so an 11,000-instruction program costs a thousand times what a 9-instruction one
does. The last row is the control: `.` compiles to a compact set of ranges and is unaffected by
the flag, so this is class expansion and not something else about tier 1.

Tier 0 escapes it because a scan plan holds a class as a `CharClass` — a lead-byte table plus its
sequences, tested against the input — rather than as branches in a program.

### 7.3 Why the existing benchmarks could not see it

This is a regression introduced by [D19](00-decisions.md) making the shorthands Unicode, and it
went unmeasured for a straightforward reason: `CorpusBenchmark`'s two tier 1 workloads use `.`,
`[^"]` and `[^ ]`, none of which expand, while every workload that uses `\w` or `\d` happens to
be tier 0. The benchmark set had no member that was both tier 1 and Unicode-classed. Ten patterns
chosen by the person who wrote the engine could not have covered that; 106 written to be
unflattering did, immediately.

### 7.4 What was done about it

Classes now compile to a **byte-range trie with shared tails** rather than a flat alternation
([D23](00-decisions.md)). Sharing suffixes alone would not have been enough — the `SPLIT` dispatch
is itself one instruction per sequence — so the trie shares at both ends, and ranges reaching the
same subtree without being adjacent merge into one table-driven branch rather than one branch each.

| | Before | After |
|---|---:|---:|
| `(\w+):\s*(\S+)` instructions | 11,007 | **2,152** |
| ...matching a 14-byte input | 111,360 ns | **5,060 ns** |
| `^(\w+\|\d+\|\s+)+$` | 222,393 ns | 7,569 ns |
| Whole test suite | 6m 24s | **20s** |

The suite time is corroboration rather than a bonus: closure construction is quadratic in program
size, so a five-fold smaller program compiles nineteen times faster.

Per category, the corpus benchmark moved by up to **26×** — `keyvalue` and `structured` from
0.003× to 0.08× and 0.07×, `syslog` and `stress` from 0.01× to 0.16× and 0.14× — while `network`
and `identifiers`, which are almost all tier 0, were **indistinguishable**, exactly as they should
be, since a scan plan never touches the NFA.

### 7.5 Where the rest of it is, and why the DFA is next

Two things were wrong in my reasoning on the way, and measurement caught both. The first attempt
emitted the trie's children before its dispatch, so control fell into a subtree and patterns
silently stopped matching — the resulting "1,237× speedup" was patterns failing fast. The second
was assuming instruction count was the cost. It is not:

| Pattern | Instructions | Closure width at the start state |
|---|---:|---:|
| `(\w+):\s*(\S+)` | 2,152 | **31** |
| `(?-u)(\w+):\s*(\S+)` | 65 | **1** |
| `^(.+):(.+)$` | 110 | 8 |

Time tracks the width, not the size — a later change halved the instruction count again and moved
the width only 36 → 31 and the time by 10%, which is where this stopped. The residual 56× against
the ASCII equivalent is now inherent: a Unicode `\w` genuinely reaches 31 distinct continuations
from its lead bytes, so 31 threads advance in lockstep at every input position.

### 7.6 Collapsing the width: one dispatch per class

The width *was* the cost, so it was attacked directly ([D24](00-decisions.md)): a trie node whose
branches are disjoint — nearly always — becomes a single instruction carrying a 256-entry
byte-to-successor table, instead of one branch per range.

| | Width at the start state | Instructions | `(\w+):\s*(\S+)` |
|---|---:|---:|---:|
| Originally | 36 | 11,007 | 111,360 ns |
| After the trie | 31 | 2,152 | 5,060 ns |
| After the dispatch | **1** | 697 | **718 ns** |

**155× on the worst pattern**, and the width is now at its floor: one live thread per position
where there were 31. Two things beyond the headline. The Unicode penalty has collapsed from 56×
to 1–7×, so `^(\w+|\d+|\s+)+$` now runs at the speed of its `(?-u)` equivalent. And patterns with
no Unicode class gained too — `.` is a class like any other, so `^(.+):(.+)$` went 1,823 → 437 ns.

Instruction count fell fivefold as a side effect, but it was never the thing that mattered; the
measurement in §7.5 is what pointed at the width, and the width is what moved.

---

## 8. Where tier 1's time actually goes

Written to settle [D18](00-decisions.md), whose case for a lazy DFA rested on numbers that
[D23](00-decisions.md) and [D24](00-decisions.md) had made obsolete.

**The sampling profiler was misleading.** It attributed roughly half of the identified samples to
`ThreadList.add`, which copies a thread's capture-slot row on every step. Removing that copy
entirely — wrong captures, but a valid upper bound on what any copy-on-write scheme could buy —
moved TIER1_GREEDY by 10.2% and TIER1_ALTERNATION by 10.5%. Half the samples were unattributed,
and the attribution that remained pointed at the wrong thing. **Capture copying is not the cost.**

**The same pattern on both tiers, same input, is the measurement that works:**

| Pattern | Tier 0 | Tier 1 | Ratio | Tier 0 ns/byte | Tier 1 ns/byte |
|---|---:|---:|---:|---:|---:|
| `^([^,]+),([^,]+),([^,]+)$` | 54 ns | 957 ns | 17.7× | 1.54 | 27.34 |
| `^(\S+) (\S+) (\S+)$` | 52 ns | 358 ns | 6.9× | 3.47 | 23.87 |
| `^([0-9]{4})-([0-9]{2})-([0-9]{2})$` | 95 ns | 207 ns | 2.2× | 9.50 | 20.70 |
| `^"([^"]*)","([^"]*)"$` | 35 ns | 554 ns | 15.8× | 1.52 | 24.09 |
| `^(\w+)=(\w+) (\w+)=(\w+)$` | 68 ns | 673 ns | 9.9× | 2.72 | 26.92 |

**Tier 1 costs a flat 21–27 ns per input byte whatever the pattern**, against 1.5–9.5 for tier 0.
That constancy is the finding: it is fixed overhead per input *position* — the outer loop, the
thread-list swap and generation stamp, the closure array indirections, the slot bookkeeping — and
not anything that scales with the pattern. Since [D24](00-decisions.md) put the thread width at 1,
there is no longer any thread multiplicity to blame. Mean penalty 10.5×.

### 8.1 What this means for the DFA

A lazy DFA's inner loop is one table lookup per byte, so it would plausibly take 24 ns/byte down
to 4 or 5 — a **5× improvement on deciding where a match is**. But a DFA cannot produce capture
groups, and every pattern in this engine's intended use exists to extract fields. The standard
architecture — DFA for the span, then a capture-capable engine over that span — saves work only in
proportion to the input *outside* the match, and in per-record matching the match is most of the
record. So the realisable win is a fraction of that 5×.

**A bounded backtracker addresses the cost that was actually measured.** It walks the program
consuming input in a single pass, produces captures directly, and pays none of the per-position
bookkeeping above; a visited bitset over (instruction, position) keeps the linear-time guarantee,
at program × length bits — some 54 KB for a 2,152-instruction program over a 200-byte record,
falling back to the Pike VM beyond a threshold. Short records with captures wanted is precisely
the case Rust keeps one for.

**Recommendation: a bounded backtracker, not a lazy DFA.** It targets the measured 24 ns/byte
rather than a thread-width problem that no longer exists, it needs none of
[D18.1](00-decisions.md)'s state-cache machinery, and it does the thing the DFA cannot.

---

## 9. The backtracker, measured

Written to close [D25](00-decisions.md): the recommendation at the end of §8 was built, and this
section records what it actually bought. A note on numbering, since the engine count changed
under this document: §§1–8 say "tier 1" for the NFA simulation, because there were two tiers when
they were written. With the backtracker between the scan plan and the simulation, the simulation
is now tier 2, and `explain()` names engines rather than leaving numbers to be decoded. The
sections above are left as written.

What was built, briefly. Depth-first execution of the same NFA program the simulation runs: an
explicit choice-point stack (a program can nest deeper than the Java stack tolerates), captures
written where they happen with an undo log, and a visited set over (instruction, position) that
abandons any second arrival — a pair reached twice has the same program and the same input left,
so the second visit cannot find a match the first missed. That caps the work at program × input,
which is the simulation's bound. Programs containing the empty-iteration guard (`MARK`/`PROGRESS`)
are refused and go to the simulation: the guard makes the future depend on where the iteration
began, so the visited key would prune paths whose outcomes genuinely differ. Selection is per
search rather than per pattern, because the visited set's size depends on the input: backtracking
runs where it fits a 128 KB budget, the simulation everywhere else. The linear-time guarantee is
therefore untouched — whatever the pattern, whichever engine runs, the bound is the same.

On streaming the backtracker is deliberately conservative: it reports `NEED_MORE_INPUT` whenever
any explored path reached the edge of a window that can still grow — including an assertion
*evaluated* at the edge, whichever way it came out, which a test caught when `$` held on a prefix
that the full input then contradicted. The simulation knows precisely whether more input could
change the answer, because live threads carry that information; a depth-first search discards it
as it backtracks, and undetermined-too-often costs a caller another chunk where
determined-wrongly would silently truncate a match.

### 9.1 The first measurement was mixed, and the prediction wrong

§8 predicted "several times faster on the short records this engine is meant for". The first run
(`benchmarks/2026-08-18-1900-a7d06f2236.json` against the `1255` baseline) said otherwise: csv
+54.7%, weblog +37.4%, structured +19.0% — but syslog **−12.5%**, TIER1_GREEDY **−11.4%**, quoted
−4.7%, fixedwidth −4.6%. A wash with large variance by workload is not a win, and it was not
committed as one.

The diagnosis: **the visited set was being zeroed on every search.** It is sized program × input —
hundreds to thousands of instructions against tens of bytes of record — so for exactly the short
inputs the engine targets, clearing the set cost more than the matching it bounded. The engine
was paying a per-search cost proportional to the *program* in order to save per-position costs
proportional to the *input*, and on short inputs the program is the larger of the two.

### 9.2 Generation stamping, and the re-measurement

The fix: one byte per cell instead of one bit, stamped with the search's generation rather than
cleared between searches. Zeroing now happens once every 127 searches, and an eightfold size
increase buys its removal from the per-search path. Re-measured
(`benchmarks/2026-08-18-1948-a7d06f2236.json` against the same baseline), the whole-corpus suite
on short records reads:

| Category | Before | After | Change | Verdict |
|---|---:|---:|---:|---|
| csv | 78129 ± 950 | 126560 ± 918 | +62.0% | better |
| weblog | 76740 ± 641 | 114719 ± 1740 | +49.5% | better |
| structured | 112338 ± 352 | 145921 ± 1486 | +29.9% | better |
| numbers | 164054 ± 1042 | 204948 ± 4757 | +24.9% | better |
| identifiers | 85585 ± 466 | 105989 ± 1440 | +23.8% | better |
| keyvalue | 57071 ± 1872 | 69903 ± 220 | +22.5% | better |
| stress | 17943 ± 113 | 21041 ± 170 | +17.3% | better |
| quoted | 135552 ± 510 | 146291 ± 1143 | +7.9% | better |
| datetime | 82465 ± 827 | 87759 ± 1276 | +6.4% | better |
| network | 254476 ± 1349 | 252894 ± 1828 | −0.6% | indistinguishable |
| fixedwidth | 267291 ± 1442 | 262750 ± 2955 | −1.7% | worse |
| syslog | 62164 ± 482 | 57511 ± 597 | −7.5% | worse |

Nine of twelve categories better, one unchanged, two worse. And the second diagnosis from §9.1
did not survive: TIER1_GREEDY's regression had been read as *inherent* — greedy `.+` consuming to
the end and backtracking byte by byte, depth-first doing quadratically what breadth-first does in
one pass. With the stamping fix it recovered from −11.4% to +2.6%: the regression was the
clearing all along, paid in the tail of searches near a buffer's end that the budget admits. The
inherent-cost story may yet be true at some record length, but it was not what this measurement
was showing, and §8's record of the profiler pointing at the wrong thing now has a sequel in
which the author does the same.

The buffer-scale suite (`CorpusBenchmark`, 2,000-record buffers) is structurally unaffected:
its searches pass the whole remaining buffer as the region, the budget arithmetic sends those to
the simulation, and the backtracker runs only in the last kilobyte or so. Its scores moved most
on the two workloads with the largest recorded fork spread (QUOTED's baseline error bar is ±10%;
see §2.0 and [D21](00-decisions.md)), which is spread, not effect.

### 9.3 Where this leaves the engine against the JDK

The §7 table, re-rendered from the `1948` run — ratios against `javaRegexFromBytes`, the honest
comparison for a byte pipeline:

| Category | Ours | `java.util.regex` (bytes) | Ratio | Was ([§7](#7-the-whole-corpus-and-the-defect-it-exposed)) |
|---|---:|---:|---:|---:|
| csv | 126560 ± 918 | 120151 ± 794 | **1.05×** | 0.66× |
| stress | 21041 ± 170 | 22365 ± 268 | **0.94×** | 0.81× |
| weblog | 114719 ± 1740 | 127341 ± 1450 | **0.90×** | 0.61× |
| keyvalue | 69903 ± 220 | 77909 ± 841 | **0.90×** | 0.74× |
| numbers | 204948 ± 4757 | 231191 ± 4016 | **0.89×** | 0.70× |
| network | 252894 ± 1828 | 309270 ± 2181 | **0.82×** | 0.82× |
| syslog | 57511 ± 597 | 75554 ± 533 | **0.76×** | 0.83× |
| fixedwidth | 262750 ± 2955 | 408848 ± 3880 | **0.64×** | 0.66× |
| structured | 145921 ± 1486 | 257819 ± 2052 | **0.57×** | 0.44× |
| quoted | 146291 ± 1143 | 263040 ± 6052 | **0.56×** | 0.52× |
| datetime | 87759 ± 1276 | 164798 ± 2015 | **0.53×** | 0.50× |
| identifiers | 105989 ± 1440 | 203086 ± 4404 | **0.52×** | 0.42× |

A category at parity did not exist before this change. The whole-corpus mean has moved from
"half the JDK's speed on our worst categories, two-thirds on typical ones" to "parity on the
best, three-quarters typical, half on the worst" — per match, over 106 patterns, with every
capture group extracted.

### 9.4 What remains slower, and why it stays

Syslog (−7.5%) and fixedwidth (−1.7%) are real, reproduced in both runs, and understood in shape:
syslog patterns are chains of greedy permissive classes — `^(\S+) (\S+) (\S+) (.*)$` — where
depth-first consumes to the end of the record and backs off once per class, work the simulation
never does. The visited set bounds it; it does not remove it. Three options were considered:

- **Per-pattern heuristics** (send greedy-tail patterns to the simulation) — rejected for now.
  A compile-time guess about a runtime cost was exactly the kind of reasoning §8 was written to
  replace with measurement, and −7.5% on one category against +62% on another does not justify a
  second selection mechanism.
- **Accept it** — chosen. The backtracker stays the default where its budget admits it.
- **Revert the default** — the position §9.1 would have taken had the re-measurement stayed
  mixed. It did not.

Recorded as [D26](00-decisions.md). The engine's cost model is now: scan plan 1.5–9.5 ns/byte
where the pattern is one-pass, backtracking a few ns/byte typical on short records where the
budget admits it, simulation at 21–27 ns/byte as the floor under everything — and all three
provably agreeing, which is what `compileForcing` and the three-way differential exist to keep
true.
