# Baseline Measurements — before any engine code

Run: 2026-08-17. JMH 1.37, JDK 25, single fork, 3×1s warmup, 5×1s measurement, one machine,
otherwise idle. Source: `stroom-shapeshifter-regex/src/test/java/stroom/shapeshifter/regex/bench/`.

Reproduce with `./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:jmh`.

These were taken deliberately *before* writing engine code, to find out what there is to beat
and — more importantly — to test the design's assumptions while they are still cheap to
change. Two of them did not survive.

---

## 1. Method

Both workloads extract every field of every record and combine the field lengths into a
checksum. `ScannersTest` asserts all five implementations produce the same checksum on the
same input, so they are known to be doing the same work.

| Workload | Records | Bytes | Shape |
|---|---|---|---|
| `csv` | 5,000 | 307,190 | `^([^,\n]*),…` — five comma-separated fields; scan-until-byte |
| `syslog` | 5,000 | 383,481 | `^(\S+) (\S+) (\S+) (\S+) (.*)$` — four tokens plus free text; scan-while-class |

Data is pure ASCII, so byte- and char-oriented implementations do identical logical work.

The comparands are arranged to separate the two candidate causes of any difference:

|  | regex (backtracking node walk) | hand-written scan |
|---|---|---|
| `String` | `regexString` | `scanChars` |
| `CharSequence` | `regexCharSequence` | — |
| `byte[]` | not expressible | `scanBytes` |

plus `regexStringFromBytes`, which adds the `new String(bytes, UTF_8)` decode a byte pipeline
must perform before `java.util.regex` can be used at all.

---

## 2. Results

| Benchmark | Workload | ops/s | ±99.9% | MiB/s | vs `regexString` |
|---|---|---:|---:|---:|---:|
| `regexString` | csv | 2127.2 | 40.1 | 623 | 1.00× |
| `regexCharSequence` | csv | 1805.9 | 40.9 | 529 | 0.85× |
| `regexStringFromBytes` | csv | 2031.5 | 25.9 | 595 | 0.95× |
| `scanChars` | csv | 5676.2 | 257.8 | 1663 | **2.67×** |
| `scanBytes` | csv | 5336.1 | 325.5 | 1563 | **2.51×** |
| `regexString` | syslog | 1796.9 | 76.6 | 657 | 1.00× |
| `regexCharSequence` | syslog | 1634.4 | 144.5 | 598 | 0.91× |
| `regexStringFromBytes` | syslog | 1748.5 | 146.0 | 639 | 0.97× |
| `scanChars` | syslog | 7185.3 | 331.0 | 2628 | **4.00×** |
| `scanBytes` | syslog | 6579.2 | 178.2 | 2406 | **3.66×** |

---

## 3. What this confirms

**The central thesis holds: straight-line scanning beats `java.util.regex` by 2.7×–4.0×.**
That is the entire justification for the tier 0 scan plan, and it survives contact with a
real measurement. Note the hand-written scanners are plain loops — no SWAR, no Vector API —
so this is the *unoptimised floor* of the approach, not its ceiling.

The gap is larger on `syslog` (4.0×) than on `csv` (2.7×), which is consistent with
scan-while-class being cheaper to hand-code relative to what a backtracking engine does per
character than scan-until-byte is.

---

## 4. What this refutes

### 4.1 "Bytes beat chars" — not for ASCII, and not in the scan loop

**Predicted:** a byte representation would be meaningfully faster, since UTF-16 halves data
density.

**Measured:** `scanBytes` is **6% *slower*** than `scanChars` on both workloads (1563 vs 1663
MiB/s; 2406 vs 2628 MiB/s), with error bars close to overlapping. The byte representation has
no scan-speed advantage here at all.

**Why:** Java 9's compact strings mean an ASCII `String` is *already* one byte per character
internally. The UTF-16 penalty that argument assumed does not exist for ASCII input, which is
the dominant case for log data. The remaining difference is JIT loop-shape noise, not
representation.

**Consequence for the design:** the byte-native argument must rest on what it actually buys —
no decode step in a streaming pipeline, binary formats expressible at all, zero-copy spans,
and correct encoding semantics — **not** on raw scan throughput for text. Any claim of the
latter form should be removed rather than defended.

### 4.2 The decode cost is ~3–5%, not a major term

**Predicted:** decoding bytes to a `String` before matching is "an O(n) decode plus an
allocation this engine skips entirely", implying a significant end-to-end advantage.

**Measured:** `regexStringFromBytes` is only 4.5% (csv) and 2.7% (syslog) below `regexString`.

**Why:** again compact strings — `new String(asciiBytes, UTF_8)` is close to an
`Arrays.copyOf`, with no character expansion. It is a memcpy, not a transcode.

**Consequence:** the "end-to-end from bytes" comparand stays in the suite because it will
matter for non-ASCII input, but it should not be quoted as a headline advantage.

### 4.3 The `CharSequence` hypothesis is real but small

**Predicted:** the interface-dispatched `charAt` that DS3 pays (its `Buffer` is a
`CharSequence`) was "probably the single largest term" in the previously observed gap.

**Measured:** 9% (syslog) to 15% (csv). Real, consistently in the predicted direction, but
nowhere near the largest term.

**Caveat that cuts the other way:** this benchmark passes exactly one `CharSequence`
implementation, so the call site is monomorphic and the JIT inlines `charAt` perfectly. DS3
has several (`CharBuffer`, `RingBuffer`, `ReverseBuffer`, plus `subSequence` results), so its
call site may be polymorphic or megamorphic, where the cost is materially higher. **This
measurement is the best case for the DS3 path, not the typical one.** A follow-up with a
deliberately polymorphic call site is needed before drawing conclusions about the real DS3.

---

## 5. Threats to validity

- One machine, single-threaded, no CPU pinning, no allocation profiling yet.
- Fixtures are ~300–380 KB and likely resident in L2 throughout; larger inputs would exercise
  memory bandwidth, where the byte representation's smaller footprint should start to pay.
- ASCII only. Non-ASCII input would inflate `String` to two bytes per character and make the
  decode a genuine transcode, moving both §4.1 and §4.2.
- Hand scanners are unoptimised. Vectorised search would favour bytes (twice the lanes per
  register), so §4.1 may reverse once tier 0 is optimised — but that has to be *measured*, not
  assumed.
- `java.util.regex` is a mature, JIT-friendly target. The 2.7–4.0× gap is against a good
  implementation, not a straw man.

---

## 6. Follow-ups

| # | Experiment | Answers |
|---|---|---|
| 1 | `-prof gc` on all comparands | Allocation per operation — where zero-copy spans should show a difference that throughput does not |
| 2 | Non-ASCII (Latin-1 and UTF-8 accented) fixtures | Whether §4.1 and §4.2 reverse once compact strings stop applying |
| 3 | Deliberately polymorphic `CharSequence` call site | Whether the real DS3 path is worse than the 9–15% measured here |
| 4 | 10–100 MB fixtures | Whether the byte footprint advantage appears once out of cache |
| 5 | SWAR/Vector API scan kernels | The ceiling of the tier 0 approach, versus the floor measured here |
| 6 | The real DS3 parser end to end | What fraction of a whole parse is regex at all — the number that decides whether any of this matters in production |
| 7 | `String`/`char[]` → `byte[]` at the pipeline boundary | The cost of insisting on byte input when an upstream element supplies characters. This is the scenario behind the rejected character mode ([D13](00-decisions.md)); expected to be cheap for ASCII for the same compact-string reason as §4.2, but it is the measurement that would reopen the decision |

Follow-up 6 is the one that could most change the plan: if regex is a small share of total DS3
parse time, a 3× faster matcher is worth correspondingly less, and the streaming/composability
arguments carry the project on their own.

---

## 7. Round 2 — can an interpreted plan reach the hand-written floor?

The whole tier 0 argument assumes a plan *interpreter* keeps the advantage that specialised
hand-written code demonstrates. If per-op dispatch ate it, tier 0 would have to generate
bytecode per plan — a foundational difference, and much cheaper to discover now.

Three prototype interpreters were built (`Plans.java`), running hand-built plans that mirror
what the compiler is specified to emit. All are checksum-equivalent to `java.util.regex` in
`ScannersTest`.

| Interpreter | csv MiB/s | syslog MiB/s | vs hand-written floor |
|---|---:|---:|---:|
| `planSealed` — ops as records, pattern-matching switch | 1534 | 1380 | 98% / 60% |
| `planFlat` — flat `int[]` opcodes, class as 256-bit set | 1609 | 1533 | 103% / 67% |
| `planFlatTable` — flat opcodes, class as 256-**byte** table | 1604 | **1872** | 103% / **82%** |
| `scanBytes` — hand-written (the floor) | 1560 | 2283 | 100% |
| `regexString` — `java.util.regex` | 617 | 665 | — |

### 7.1 Tier 0 can be interpreted — no bytecode generation needed

On `csv` the interpreted plan **matches and slightly exceeds** the hand-written scanner
(103%). Dispatch amortises: a plan op is decoded roughly nine times per record but each one
then consumes ~15 bytes in a tight inner loop, so the per-op cost is noise against the
per-byte work. The design's contingency — "generate bytecode per plan if the interpreter
cannot reach the floor" — is not needed on this evidence.

### 7.2 Class representation matters more than dispatch

Swapping the 256-bit set (load, two shifts, mask per byte) for a 256-**byte** lookup table
(one load, one compare) is worth **+22%** on `syslog` — 1533 → 1872 MiB/s. The `csv` control,
which has no class ops, is unchanged at 1604 vs 1609, confirming the effect is the class test
and not something else.

**Design consequence:** class membership in a plan is a 256-entry byte table, not a bit set.
256 bytes per distinct class is a trivial cost.

### 7.3 Flat opcodes beat sealed records by 5–10%

`planSealed` is 95% of `planFlat` on csv and 90% on syslog. Real but modest.

**Design consequence:** use the sealed record model as the compiler-facing IR — it is far more
readable and is what `explain()` should render — and lower it to flat parallel arrays for the
hot interpreter. Two representations of the same plan, one for reasoning about and one for
running.

### 7.4 The residual syslog gap is algorithmic, not interpretive

At 82% of the floor, `syslog` still trails. The two implementations are not doing the same
thing: the hand-written scanner pre-scans for the line end and then tests one byte
(`!= ' '`), while the plan performs a true `\S` test per byte with no pre-scan. The plan is
doing the more general work.

This points straight at the specialisation the design already anticipates for
`SCAN_UNTIL_BYTE`, generalised to classes: a class of one byte becomes an equality test, a
complement-of-one becomes an inequality test, a contiguous range becomes two compares, and
only the general case falls back to the table. Worth measuring once the compiler exists,
because the honest comparison then is specialised-plan versus hand-written.

---

## 8. Round 3 — does the order of alternation branches matter?

Run: 2026-08-17. Received wisdom says the common branch should come first, because a
backtracking engine tries branches in order and a failed attempt is wasted work. Neither tier
here backtracks, so the question is whether that still holds. Each pattern is measured with the
frequently-matching branch first and last, over input where it matches 95% of records.

| Benchmark | common first | common last | penalty for bad ordering |
|---|---:|---:|---:|
| `java.util.regex`, disjoint branches | 4734 | 3009 | **36%** |
| `java.util.regex`, overlapping branches | 4643 | 3189 | **31%** |
| Tier 0 (scan plan) | 3973 | 4136 | none — reversed, within error |
| Tier 1 (Pike VM) | 57.2 | 54.3 | none — within error |

### 8.1 Ordering matters for the JDK, and not for either tier here

The 31–36% penalty on `java.util.regex` confirms the received wisdom, and confirms it is worth
worrying about for the `java` dialect (D7). For this engine it does not apply, by construction:

- **Tier 0** compiles an alternation to a 256-entry table indexed by the next byte. One lookup
  selects the branch, so every branch is equally cheap to reach.
- **Tier 1** advances all branches simultaneously as parallel threads. There is no "try and
  fail" to avoid; order decides only *which* match wins, not the work done to find it.

So profile-guided branch reordering would buy nothing on either tier. That is a real dividend
of the architecture, and worth knowing before building machinery to chase it.

### 8.2 An unwelcome finding: tier 1 is ~80× slower than the JDK

On the *same* overlapping pattern, tier 1 manages 57 ops/s against `java.util.regex`'s 4643.
The design predicted tier 1 would be "at parity or behind" a backtracker on patterns that do not
actually backtrack. Eighty times behind is far worse than that, and it matters because tier 1
takes **48% of the corpus**.

This is an implementation problem, not an algorithmic one — a Pike VM should be within a small
factor of a backtracker on easy patterns. The prime suspect is capture handling: every `SAVE`
clones the thread's slot array, so a pattern with several live threads allocates continuously.
Standard remedies, in rough order of expected value:

1. **Stop cloning slots per save** — pool and reference-count the arrays, which is the usual
   Pike VM approach, or write into a shared arena indexed by thread.
2. **Only track groups the caller asks for** — the dead-group elimination already in the design
   (§8); most matches read two or three groups, not all of them.
3. **Prefilter before seeding threads** — the tier 0 first-byte table exists already and is not
   used on the tier 1 path, so a new thread is currently started at every offset.
4. **A one-pass NFA for patterns that are nearly deterministic**, avoiding the general
   simulation for the common shapes that just miss the tier 0 predicate.

### 8.3 Chased — three hypotheses refuted, one real win, and an architectural answer

Same pattern, same data, forced through both engines (`BytePattern.compileForcingNfa`) so the
comparison is engine cost alone rather than pattern difficulty:

| | ops/s | allocation |
|---|---:|---:|
| Tier 0 scan plan | 5379 | 41 B/op |
| Tier 1 NFA simulation | 55.7 | 3.0 KB/op |
| Tier 1, no capture groups | 65.3 | — |

**Hypothesis 1 — capture-slot cloning. Refuted as the cause, but fixed anyway.** Allocation
profiling confirmed 21.9 MB/op on a 165 KB input: 133× the input size, at 1.2 GB/s, which is
roughly the allocator's ceiling. Rewriting the epsilon closure to mutate one slot array and
restore it on the way back, with thread rows preallocated and reused, cut that to 9.1 MB/op —
**and throughput did not move at all**. Worth doing, but not the bottleneck.

**Hypothesis 2 — `Hir.Kind.values()`.** The residual 9.1 MB had a distinctive shape: tier 0 was
allocating exactly 320,041 B/op, which is 32 bytes × 10,000 assertion evaluations. `values()`
clones its array on every call, and assertions are evaluated once per input position in both
engines. Caching it took tier 1 to 3.0 KB/op and tier 0 to **41 B/op — with a 21% throughput
gain on tier 0**. A real win, in the wrong tier.

**Hypothesis 3 — class expansion in the NFA.** A class such as `.` expands to around ten UTF-8
byte-sequence alternatives, each becoming a live thread at every position although ASCII input
can only use the first. Collapsing the single-byte members into one table-driven `BYTE_CLASS`
instruction was worth about 4%. Also refuted.

**What it actually is.** Sampling puts ~40% of runnable time in `PikeVm.addThread` recursion,
and the no-captures variant is only 17% faster, so the cost is the epsilon-closure walk itself:
roughly 100 ns per input position, spent re-deriving the same closure at every byte. That is
not a defect in this implementation so much as what a plain Pike VM costs — and it is precisely
why RE2 does not use one for searching. RE2 runs a **lazy DFA**, which caches state transitions
so each byte is one table lookup, and only runs the capture-tracking simulation over the region
the DFA has already identified as matching.

**Consequence for the design.** A lazy DFA is currently listed as a non-goal, to be revisited
"with benchmark evidence" (§1). This is that evidence: without one, half the corpus runs ~90×
slower than the other half. The options, in order of expected value:

1. **Lazy DFA for match bounds**, Pike VM only for captures within them. The standard
   architecture, and the only one that closes a 90× gap.
2. **Precomputed epsilon closures** — resolve each byte-consuming instruction's successors at
   compile time so the closure is a table lookup rather than a recursive walk. Cheaper to build
   than a DFA, and should recover a useful multiple, though not all of it.
3. **Widen tier 0 instead** — every pattern moved out of tier 1 sidesteps the problem entirely.
   `SCAN_TO_LAST`/`SCAN_TO_FIRST` ([04-corpus-analysis.md §2.3](04-corpus-analysis.md)) would
   move roughly half the tier 1 corpus, and prefix factoring has already moved some.

### 8.4 Options 2 and 3 attempted — one worked, and the conclusion firmed up

**Option 3 (widen tier 0) is largely unavailable.** Rechecking the corpus against the soundness
condition rather than the shape of the patterns showed `SCAN_TO_LAST` would move one tier 1
pattern in ten, not half ([04-corpus-analysis.md §2.3](04-corpus-analysis.md)). Tier 1's
population is mostly genuine ambiguity.

**Option 2 (precomputed epsilon closures) did not help.** Resolving every epsilon chain at
compile time, so adding a thread walks a flat array rather than recursing, moved throughput
from 55.7 to 56.1 ops/s — nothing. Kept regardless: it is a prerequisite for a DFA's state
construction, and it removed the recursion the profiler had pointed at.

**What did help was cheaper and duller: a prefilter.** For an anchored pattern, an unanchored
search was seeding a fresh start thread at *every* position until a match completed, each
expanding to one target per alternation branch and evaluating `^` only to fail — roughly five
of every seven thread operations. The plan tier already had a first-byte table; the NFA path
ignored it. Wiring it in: **55.7 → 80.6 ops/s, +45%**, with tight error bars.

| Change | Tier 1 ops/s | Note |
|---|---:|---|
| Starting point | 55.7 | after the allocation work |
| Precomputed closures | 56.1 | no effect |
| First-byte prefilter | **80.6** | +45% |

The gap to tier 0 narrows from 91× to about 54×. The residual is what a per-byte thread-set
simulation costs: roughly two thread additions per input byte at tens of cycles each, against
one or two cycles for a scan plan. No further micro-optimisation will close that — collapsing
the thread *set* into a single cached state is the only thing that will, which is a DFA.

**Conclusion.** Options 2 and 3 have now been tried and neither closes the gap. The evidence
points at option 1.

---

## 9. Reference points

`java.util.regex` measures 620–660 MiB/s here, which sits inside the 300–800 MB/s range ds-rs
records for the whole Rust engine (`design/byte_level_performance_and_design.md:180-186`), and
the hand-scan floor of 1.5–2.6 GiB/s is well above it. Different workloads and machines, so
this is orientation only, not a comparison — but it does suggest a byte-native scan plan in
Java is not obviously giving anything away to the Rust prototype.
