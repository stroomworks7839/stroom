# Shapeshifter Matching Engine — Design (draft 1)

Status: **draft for discussion**. Nothing here is implemented yet.

> **Retired design: streaming.** This document specifies a growing-window model —
> `ByteWindow`, `NEED_MORE_INPUT`, resumption across chunks — that was built and then
> retired by [D37](../../design/00-decisions.md) on 2026-08-25. The library takes complete
> views only: byte arrays and slices. Read every streaming passage below as the design of
> the day, not as the contract.

Companion to [01-regex-language.md](01-regex-language.md), which defines the language this
engine executes. This document covers architecture, the encoding compiler, the execution
tiers, streaming, and the test strategy.

Target module: `stroom-shapeshifter:stroom-shapeshifter-regex`, package
`stroom.shapeshifter.regex`. No Stroom dependencies — this is a standalone library so it
can be tested, benchmarked and fuzzed in isolation.

---

## 1. Goals and non-goals

**Goals**

1. Match `byte[]` directly, with the encoding resolved at compile time.
2. Linear-time execution with capture groups (RE2 semantics).
3. Correct streaming: distinguish "no match" from "need more bytes", for both finite and
   unbounded sources — including across a transcode stage.
4. Compile deterministic patterns to straight-line scans instead of an automaton, with a
   provable equivalence to the automaton path.
5. Compose matchers as combinators, and decompose regexes into the same combinator/plan
   representation.
6. Zero-copy captures — a group is an offset and a length, not a copy.

**Non-goals (for v1)**

- Backreferences, lookaround, atomic/possessive groups (language doc §8).
- A lazy DFA. The plan tier plus literal prefilters should cover the throughput cases that
  motivate one; revisit with benchmark evidence.
- Encoding *resolution* (inheritance, BOM sniffing, `AUTO`). That belongs to the config
  layer that owns the node tree; this library takes an encoding as an argument.
- Native matching for multi-byte non-UTF-8 encodings. Those are reached through the
  `transcode` stage (§4), which is a deliberate scope decision, not an omission.

---

## 2. Architecture

```
pattern string ──► Parser ──► Ast ──► Hir ──► [validate: RE2 subset]
                                       │
                             encoding  ▼
                          ┌── ByteCompiler ──┐
                          │                  │
                          ▼                  ▼
                    Bir (byte IR)      class → byte-sequence
                          │             alternations (cached)
              ┌───────────┴───────────┐
     one-pass │                       │ general
    analysis  ▼                       ▼
        ScanPlan (tier 0)        NfaProgram (tier 1)
     straight-line ops           Thompson program
              └───────────┬───────────┘
                          ▼
                    CompiledMatcher
                          │
   combinators ───────────┤   (a combinator element may be a CompiledMatcher;
                          │    a ScanPlan step may be an NfaProgram)
                          ▼
                    ByteMatcher (runtime, reusable, single-threaded)
```

Composition (`sequence`, `choice`, …) and regex compilation both terminate in the same
`ByteMatcher` SPI. That is what lets a regex be *decomposed* into combinators and a
combinator *contain* a regex, with one set of streaming rules for both.

Encodings outside UTF-8 / single-byte / `RAW` never enter this pipeline at all. They are
converted by a `transcode` combinator wrapping the matchers that consume them (§4.4), so the
compiler only ever sees the two families it can compile cheaply.

---

## 3. Package layout

```
stroom.shapeshifter.regex
├── syntax/        Parser, Ast, HIR, validation, error positions
├── encoding/      ByteEncoding (UTF-8, single-byte, RAW), class→byte compilation,
│                  caches, and the transcode stage + offset map
├── ir/            Bir, first/follow analysis, one-pass predicate
├── plan/          ScanPlan ops, plan compiler, plan executor
├── nfa/           NfaProgram, Thompson compiler, Pike VM, thread lists
├── comb/          Atoms and combinators, named-matcher registry, inlining
├── stream/        ByteWindow, ByteSource, window management, match outcomes
└── (root)         BytePattern, ByteMatcher, MatchResult, ByteSpan, Flags
```

> **The shipped layout is not this one.** The draft planned seven packages; the module has
> two — `internal/` (parser, HIR, analysis, the plan compiler and all five engines) and
> `comb/` (the combinator layer) — beneath a root of published types: `BytePattern`,
> `ByteMatcher`, `ByteSpan`, `Anchoring`, `Engine`, `Flag`, `LeadingAnchor`,
> `TrailingAnchor`, `MatchLimitException`, `PatternCompileException`. Of the names above,
> `Parser` and `Hir` exist in `internal/`; `Ast`, `Bir`, `ByteCompiler`, `ByteEncoding`,
> `ScanPlan`, `NfaProgram`, `CompiledMatcher`, `MatchResult` and `Flags` never shipped under
> those names — they are the draft's vocabulary, and the prose below uses them throughout — and `stream/`'s `ByteWindow`/`ByteSource` were built and then retired by
> [D37](../../design/00-decisions.md). The engine set the draft plans here was redrawn by
> D25, D26, D30, D31 and D32. For what is actually there, read `package-info.java` and the
> module's [design index](README.md).

---

## 4. Encodings

Handled in two places, split on **offset preservation**:

| Encoding | Mechanism | Cost |
|---|---|---|
| UTF-8, `RAW` | Compiled into the pattern | Zero / algorithmic |
| Every single-byte charset (ISO-8859-x, Windows-125x, KOI8-R, Windows-874, US-ASCII, …) | Compiled into the pattern | One 256-entry inverse table per charset, built once |
| UTF-16LE/BE, Shift-JIS, EUC-JP, EUC-KR, ISO-2022-JP, GBK, GB18030, Big5 | `transcode` stage upstream (§4.4) | One conversion pass over the region |

Where one character is one byte — or the encoding *is* the byte encoding the engine wants —
compiling it into the pattern is trivial and every matched span is directly a span of source
bytes. Where it is not, native support would need repertoire enumeration, character-boundary
tracking during unanchored search, and for ISO-2022-JP a stateful automaton that cannot
exist. Transcoding is cheaper to build, cheaper to reason about, and is a pipeline capability
worth having regardless.

This is the single largest simplification in the design relative to draft 1: two families
instead of four, no self-synchronisation machinery, no variable-width class enumeration, and
no encoding the engine has to refuse.

### 4.1 The class compiler

Input: a set of code-point ranges. Output: an alternation of **byte-range sequences**
recognising exactly the encodings of those code points.

```java
public interface ByteEncoding {
    String name();
    boolean isSingleByte();
    byte[] encode(int codePoint);           // empty if unrepresentable
    ByteSeqAlt encodeClass(CodePointSet s); // the important one
    CharsetDecoder decoder();               // for \b and diagnostics
}
```

`ByteSeqAlt` is a list of alternatives; each alternative is a list of byte ranges
(`[lo,hi]`) of equal length. `[a-z]` under UTF-8 is `{ [61-7A] }`; `[Ѐ-ԯ]` is
`{ [D0-D3][80-BF], [D4][80-AF] }`.

| Family | `encodeClass` strategy |
|---|---|
| **Single byte** (incl. `RAW`, ASCII) | Build the 256-entry byte→code-point table once from the `Charset`, invert it, map each code-point range onto a set of byte values, coalesce into ranges. Always one byte per alternative, so a class is just a 256-bit set — the fastest possible representation |
| **UTF-8** | Recursive range splitting at encoding-length and continuation-byte boundaries (equivalent to `regex_syntax::utf8::Utf8Sequences`). Excludes surrogates, so the automaton never accepts ill-formed UTF-8 |

Java's `Charset` is the source of truth, so there is no `encoding_rs` equivalent to carry.
Inverse tables are built lazily per charset; `encodeClass` results are cached keyed by
`(encoding, classId)`.

Compiling a pattern for any encoding outside these two families is a **compile error** that
names the encoding and points at `transcode` — never a silently approximate match.

### 4.2 Start positions

Both native families are self-synchronising, so unanchored search may try every offset:
single-byte trivially, and UTF-8 because a continuation byte `80–BF` can never begin a valid
sequence, so the compiled automaton rejects a mis-aligned start on its own. No alignment
tracking, no character-walking scan, no per-encoding search strategy.

The encodings that would have needed that machinery are exactly the transcoded ones, and
after transcoding they are UTF-8.

### 4.3 What this costs

Honest accounting of what moving the hard families upstream gives up:

- **A conversion pass and a buffer** for those encodings. Irrelevant for UTF-8 and
  single-byte input, which is the overwhelming majority, and cheap relative to the
  correctness it buys where it does apply.
- **Byte-exact matching inside a transcoded scope.** The source bytes are no longer what the
  matcher sees. Match them outside the scope in `RAW`.
- **Offset indirection** — captures inside a scope are offsets into transcoded bytes, so
  source offsets require a mapping (§4.5).

### 4.4 The transcode stage

A `transcode` scope (language doc §6.6) converts a region and runs its inner matcher over the
result. Structurally it is the same as the existing `decode` codec steps — a charset is
another codec — and it chains with them: `decode(base64) → decode(gzip) → transcode(Shift_JIS)`.

```java
public final class TranscodeScope implements Matcher {
    Charset from;                      // any JVM charset, including stateful ones
    MalformedAction onMalformed;       // REPORT (default) | REPLACE | IGNORE
    Extent extent;                     // WHOLE_SOURCE | FIXED(n) | STEP_REF(i) | UNTIL_INNER_COMPLETE
    Matcher inner;                     // sees UTF-8
}
```

The scope is **decode-only**. Emitting a value in another charset is a value transform, not
a scope — it has no window and no offsets — so it lives with the other codecs as
`encode(value, charset)`. That asymmetry is what keeps the offset map (§4.5) one-way.

Implementation notes:

- **Target is UTF-8 bytes, not `char`s.** `CharsetDecoder` produces chars, so the stage is
  decode-then-encode; the intermediate `CharBuffer` never escapes the stage and surrogate
  handling never reaches the engine.
- **Incremental by construction.** `CharsetDecoder.decode(in, out, endOfInput)` consumes as
  much as it can and reports `UNDERFLOW` when it stops on a partial character. That maps
  exactly onto `NEED_MORE_INPUT` (§7.2), so the stage feeds from the same window manager as
  everything else and needs no special streaming path.
- **Stateful charsets work.** ISO-2022-JP's escape-driven mode switching lives inside the
  decoder, which is precisely why it is unimplementable as a stateless byte automaton and
  trivial here.
- **Malformed input defaults to `REPORT`**, raising an error record and failing the scope.
  `REPLACE` and `IGNORE` are opt-in. ds-rs decodes lossily everywhere
  (`String::from_utf8_lossy` throughout `engine/src/encoding.rs:344-410`), which turns
  corrupt input into U+FFFD with no signal — the failure mode most likely to go unnoticed
  in production.

### 4.5 Offset mapping

Everything downstream — capture spans, `tell`/`seekAbs`, error locators — is defined in
source byte offsets, so a transcoded scope needs a map back.

- **Exact at scope boundaries.** The stage knows the source byte range it consumed.
- **Checkpointed within.** After each `decode()` call the decoder reports how many input
  bytes and output chars it consumed, so the stage records `(sourceOffset, targetOffset)`
  pairs every N bytes (N tunable, default ~4 KiB).
- **Refined on demand.** An exact source offset for an interior position re-decodes only the
  span between the bracketing checkpoints. Bounded by N, and only paid when a source offset
  is actually requested.

Error locators and `tell` therefore report true source offsets. Per-capture source offsets
are computed lazily, so a pipeline that never asks never pays.

---

## 5. Intermediate representations

**`Ast`** — faithful syntax tree with source positions, for error messages and for a future
"explain this pattern" UI. Preserves things the HIR discards (literal spelling, flag scopes).

**`Hir`** — normalised, encoding-independent:

```
Hir = Empty
    | Literal(int[] codePoints)
    | Class(CodePointSet)              // already case-folded, already \d/\w expanded
    | Concat(Hir...)
    | Alternate(Hir...)                // ordered
    | Repeat(Hir, min, max, greedy)
    | Group(Hir, capture index?, name?)
    | Assertion(START_INPUT | END_INPUT | START_LINE | END_LINE | WORD | NOT_WORD)
```

Normalisations: flatten nested concats/alternations, fold adjacent literals, expand
`a{2,4}` into concatenation + optionals, remove empty alternatives, hoist common literal
prefixes out of alternations (helps both tiers).

**`Bir`** — the same shape with every `Literal`/`Class` replaced by `ByteSeqAlt`, plus
`ByteClass(bitset256)` as a fast special case for alternations that are all single-byte.
This is the last representation shared by both execution tiers.

**Analysis over `Bir`** — computed once, memoised per node:

- `nullable(n)` — can match empty
- `first(n)` — set of first bytes (256-bit set)
- `minLen(n)`, `maxLen(n)` — byte length bounds
- `follow(n)` — first bytes of whatever can come next, within the whole expression
- `requiredPrefix(n)` — a literal byte sequence every match must start with, if any

---

## 6. Execution tiers

### 6.1 Tier 0 — the scan plan (no state machine)

This is the "simple regexes shouldn't be an FSM" mechanism, and it is also the regex→atom
decomposition. The compiler tests a formal predicate and, when it holds, emits a
straight-line plan.

**The one-pass condition.** A node is one-pass if, recursively:

1. For every `Alternate(A₁…Aₙ)`: the `first(Aᵢ)` are pairwise disjoint, and at most one
   `Aᵢ` is nullable (and if so it is last).
2. For every `Repeat(R, min, max, _)`: `R` is not nullable, and
   `first(R) ∩ first(follow(Repeat)) = ∅`.
3. No assertion requires unbounded left context.
4. **No choice is settled by preference rather than by the next byte.** A branch that can match
   empty means "stop here", which competes with any later branch that would consume. Whether
   that competition is decidable depends on what follows: if the remainder can accept — tracked
   as a `canEnd` flag alongside the follow set — then stopping always succeeds and no amount of
   lookahead can choose. The same applies to a lazy repetition that could stop at the end of a
   match.

Rule 4 was missing from the first implementation and is not a missed optimisation but a **wrong
answer**: `(a|ab)` against `"ab"` must yield `"a"` because the first branch is preferred, and
`a*?` must match empty, whereas a byte-dispatching scan consumes in both cases. Both now fall to
tier 1. The lesson is that the one-pass predicate has to model *preference*, not only
determinism — leftmost-first semantics are part of what a plan must reproduce.

Under this condition a single left-to-right pass decides the match: at every point the next
input byte determines the only possible continuation. No backtracking is needed, so no
automaton is needed.

Two consequences worth stating, because they are non-obvious and both are testable:

- **Greedy and lazy coincide** under condition 2. `[^,]+,` and `[^,]+?,` compile to the same
  plan, because the repeat can never consume a byte the follow could have used.
- `.*,` **fails** condition 2 (`first(.)` covers everything including `,`) and drops to
  tier 1 — correctly, since greedy `.*,` must find the *last* comma. A bounded-window
  special case (`scan to last occurrence`) can be added later for finite windows only.

**Plan operations:**

| Op | Meaning | Implementation |
|---|---|---|
| `EXPECT_BYTES(b[])` | literal | mismatch-scan compare |
| `EXPECT_CLASS(bitset)` | one byte in class | table lookup |
| `EXPECT_SEQ_ALT(trie)` | one character of a multi-byte class | small byte trie |
| `SCAN_WHILE(bitset, min, max)` | `[...]{m,n}` | tight loop, word-at-a-time for large classes |
| `SCAN_UNTIL_BYTE(b)` | `[^b]*b` | SWAR/`Vector API` byte search — the `memchr` equivalent |
| `SCAN_UNTIL_SEQ(b[])` | `takeUntil` / lazy `.*?lit` | two-way or SWAR search |
| `SCAN_TO_FIRST(lit)` | lazy `.*?lit` over a permissive class | forward search — first occurrence |
| `SCAN_TO_LAST(lit)` | greedy `.*lit` over a permissive class | backward search from the window end — last occurrence. **Bounded windows only** |
| `SKIP(n)` | fixed-width `.{n}` | pointer bump |
| `BRANCH_ON_BYTE(table)` | disjoint alternation | 256-entry jump table |
| `ASSERT(kind)` | anchors, `\b` | position test |
| `SAVE(slot)` | capture boundary | slot write |
| `SUBMATCH(program, endCondition)` | escape hatch | runs a tier-1 program for one region |

**Prefix factoring.** Before analysis, adjacent alternation branches sharing a leading atom are
factored: `(GET|POST|PUT|DELETE)` becomes `(GET|P(OST|UT)|DELETE)`. First-byte lookahead cannot
distinguish `POST` from `PUT`, so without this a perfectly deterministic alternation of literals
falls to tier 1 — and alternations of literals sharing a prefix are everywhere in real patterns
(HTTP verbs, log levels, month names, version numbers). Only *adjacent* branches are factored,
because alternation is ordered and regrouping non-adjacent branches could change which match
wins.

**Representation, settled by measurement** ([03-baseline-results.md §7](03-baseline-results.md)):

- **Class membership is a 256-entry byte table**, not a 256-bit set. Measured at +22% on a
  class-scanning workload; 256 bytes per distinct class is nothing.
- **The hot interpreter uses flat parallel arrays** (`int[] code`, `int[] args`, side tables),
  not an array of op objects — worth 5–10% over a sealed-record model with a pattern-matching
  switch. Keep the sealed record model as the compiler-facing IR and as what `explain()`
  renders, and lower it to the flat form for execution.
- **Class tests must be specialised** the way `SCAN_UNTIL_BYTE` already is: one byte → equality,
  complement of one → inequality, contiguous range → two compares, general → table. The
  residual gap to hand-written code in the prototype was entirely this.
- **An interpreter is sufficient** — no per-plan bytecode generation. The prototype reached
  100–103% of hand-written code where the classes were already specialised.

**`SCAN_TO_LAST` / `SCAN_TO_FIRST` — sound only in a narrow case.** The strict one-pass predicate
rejects greedy `.*X` because a forward scan cannot know where to stop. Over a bounded window it
can search backward for the last `X` — but only when **nothing consuming follows `X`**. Where
something does, as in `^(.+):(.+)$`, a failure after the last `X` must retry the previous one,
which is backtracking and forfeits the linear-time guarantee. An initial estimate that this
would move half of tier 1 was wrong; measured against the soundness condition it moves one
pattern in ten ([04-corpus-analysis.md §2.3](04-corpus-analysis.md)). Both ops additionally
require `window.complete()` or an end anchor to bound the search.

The motivating examples from ds-rs's future-work notes compile as:

```
^([^,]+)          →  SAVE(2) SCAN_UNTIL_BYTE(',') SAVE(3)
^(\S+) (\S+)      →  SAVE(2) SCAN_WHILE(nonspace,1,∞) SAVE(3)
                     EXPECT_BYTES(' ')
                     SAVE(4) SCAN_WHILE(nonspace,1,∞) SAVE(5)
[a-z]+=[^,]+      →  SCAN_WHILE(lower,1,∞) EXPECT_BYTES('=') SCAN_WHILE(notcomma,1,∞)
```

**Partial plans.** The predicate is evaluated per node, so a pattern with a deterministic
prefix and one ambiguous middle compiles to a plan whose middle step is `SUBMATCH`. Most
real patterns are deterministic at their edges, which is where the scanning cost is.

**The analysis is also a lint.** The one-pass check reports *why* a pattern is ambiguous, and
that diagnostic is worth surfacing to authors rather than keeping as a compiler internal. In
the corpus it immediately found two production patterns writing `\d+.\d+.\d+.\d+` for an IP
address — an unescaped `.` meaning "any character" where a literal dot was intended
([04-corpus-analysis.md §2.2](04-corpus-analysis.md)). The pattern still matches; it is just
looser than its author believed. For a node-editor audience that kind of feedback is worth as
much as the optimisation it falls out of.

**The equivalence obligation.** Tier 0 is only ever an optimisation. Every plan must
produce byte-identical results to the tier-1 program for the same `Bir`. This is enforced
by differential fuzzing (§9.3), not by argument.

### 6.2 Tier 1 — the Pike VM

A Thompson NFA simulation with capture slots. Instructions over bytes:

```
BYTE_RANGE(lo, hi, next)      SPLIT(x, y)         JUMP(x)
SAVE(slot, next)              ASSERT(kind, next)  MATCH
```

Runtime: two thread lists (current/next), a sparse set for dedup, one capture-slot array per
thread with copy-on-write. Priority order in the list gives leftmost-first semantics
directly. Complexity is `O(input × program)` with no backtracking, which removes the
`StackOverflowError` failure mode Stroom's current DS3 catches and rethrows
(`stroom-pipeline/.../ds3/Regex.java:146-152`).

The property that matters most here is not speed: **the whole VM state is the thread list**.
That makes it suspendable at a chunk boundary, which is why choosing RE2 semantics is what
makes streaming tractable at all — a backtracking matcher has its state on the JVM stack
and cannot be paused.

### 6.3 Prefilters

Before running either tier at every offset for an unanchored search, use:

- `requiredPrefix` → literal search for the prefix (SWAR/Vector API), jump straight to
  candidate offsets;
- otherwise `first()` bitset → byte search for any member, or a fast reject if the byte at
  the offset is not in the set.

Cheap, and it captures most of what a lazy DFA would give for typical log/CSV patterns.

### 6.4 Choosing a tier

Compile-time, deterministic, recorded on the compiled artefact so it can be asserted in
tests and shown in tooling:

```
if (!onePass(bir))            → NFA
else if (plan too large)      → NFA
else                          → PLAN (possibly with SUBMATCH steps)
```

`BytePattern.explain()` returns the chosen tier and the plan/program listing. Tests assert
on it — a regression that silently drops a pattern to tier 1 should fail the build, not just
get slower.

### 6.4a Tier 2 — delegated `java.util.regex`

Patterns using constructs outside RE2 are not compiled by this engine at all; they are handed
to the JDK's `Pattern`/`Matcher` behind a decoding boundary (language doc §8). Engineering
notes:

- **Never selected automatically.** Tier 0 and tier 1 are compiler choices with identical
  observable behaviour, so the compiler picks freely between them. Tier 2 has *different*
  guarantees, so it is a user decision — `explain()` reports it, and an RE2 compile failure
  suggests it rather than silently taking it.
- **Reuses the transcode machinery** (§4.4–4.5) for decoding and offset mapping, with a
  char→byte map instead of byte→byte.
- **Opaque to flattening** (§6.5) and to the one-pass analysis. A composition containing a
  tier-2 element splits into segments either side of it.
- **Bounded** by a `charAt`-counting `CharSequence` wrapper and by catching
  `StackOverflowError`, so a pathological pattern fails diagnosably instead of hanging or
  killing the thread.

### 6.5 Flattening compositions

Composition must not cost anything at runtime. A `sequence(a, b, c)` held as a tree of
`Matcher` objects means an interface call per element per match, at a call site that goes
megamorphic as soon as a few matcher types are in play — which would hand back exactly the
per-node dispatch overhead that makes an interpreted regex engine slow in the first place.

So: **compose at authoring time, flatten at compile time.** A composition is lowered into
`Bir` — `sequence` → concat, `choice` → alternation, `repeat` → repetition, atoms → their
byte-level equivalents — and then compiled by the same path as a regex, ending in one flat
`ScanPlan` (or one NFA program) with no per-element dispatch left.

This closes the loop with §6.1. Both directions land on the same representation:

```
regex        ──decompose──►  ScanPlan  ◄──flatten──  composition
```

Consequences worth stating:

- A composition of atoms and a regex that mean the same thing compile to the *same plan* and
  therefore perform identically. Users choose between them on readability, not speed.
- The one-pass analysis applies to compositions too, so the compiler can warn on a `choice`
  with overlapping first-sets or a `repeat` that can swallow the following element — the
  authoring mistakes that committed semantics (language doc §6.3) would otherwise turn into
  a silent mis-parse.
- Elements that cannot be lowered — `transcode` scopes, codec steps, `readNumeric`, seeks —
  become plan ops in their own right or split the plan into segments either side of them.
  Flattening is best-effort and per-segment; nothing depends on it succeeding.

---

## 7. Streaming

*(Retired design: D37, 2026-08-25 — the library is complete-inputs-only; kept as the
draft record.)*

### 7.1 Window model

```java
public interface ByteWindow {
    byte[] array();          // backing buffer
    int start();             // valid region
    int end();
    long originOffset();     // absolute offset of start() in the stream — survives compaction
    boolean complete();      // true = no more bytes will ever arrive after end()
}
```

`ByteSource` supplies windows: `FiniteByteSource` (array, file, known length) sets
`complete()` true once fully read; `StreamByteSource` (`InputStream`, socket) sets it true
only at EOF. A `WindowManager` owns fill, compaction and growth, with a caller-supplied
maximum window so an unbounded stream with no matching delimiter cannot exhaust the heap —
exceeding it is a definite failure with a diagnostic, never a silent short match.

### 7.2 Outcomes

`MATCH` / `NO_MATCH` / `NEED_MORE_INPUT`, per language doc §7.

Tier 1 rule: after consuming the last available byte, if any thread is still live and
`!complete()`, return `NEED_MORE_INPUT`. If a `MATCH` was already recorded but a
higher-priority thread is still live, that is also `NEED_MORE_INPUT` — a longer/preferred
match may exist in bytes not yet seen. This is the case naive streaming engines get wrong.

Tier 0 rule: any scan or expect that hits `end()` with the plan unfinished and
`!complete()` → `NEED_MORE_INPUT`.

Assertions (`$`, `\z`, `\b`) evaluated at `end()` with `!complete()` → `NEED_MORE_INPUT`.

Transcode rule: a decoder reporting `UNDERFLOW` on a partial character with `!complete()` →
`NEED_MORE_INPUT`, and the scope propagates whatever its inner matcher returns. A transcode
scope is therefore just another matcher as far as the window manager is concerned.

### 7.3 Resumption

v1: on `NEED_MORE_INPUT` the caller grows the window and **restarts the match from the same
start offset**. Simple, and it is what ds-rs's reader does implicitly. Cost is bounded by
the match length, not the stream length.

Tier 0 plans and combinators are cursor-based and could resume exactly rather than restart;
the API is shaped to allow it (`ByteMatcher` is stateful and reusable), but v1 does not
implement resumable tier-1 state. Listed as future work with a benchmark trigger.

---

## 8. Captures

A group is a `ByteSpan`: `{ long absoluteStart, int length, State state }` where state is
`SET`, `UNSET` (did not participate) or `EMPTY`. No copying — `asBytes()` and
`asString(encoding)` materialise on demand.

ds-rs allocates a `Vec<u8>` per group per match and lists that as its dominant remaining
allocation cost (`design/future_optimisations.md:93-104`). Spans avoid the problem rather
than optimising it later. Callers that need a value beyond the window's lifetime copy
explicitly.

Slot allocation: `2 × (groupCount + 1)` ints per thread. Groups the caller never reads can
be pruned at compile time when the caller declares the groups of interest — ds-rs's "dead
output elimination" (`design/combinator_design_plan.md:752-781`), which is worth having
because it removes slot copying from the VM inner loop.

---

## 9. Testing strategy

The correctness argument for this engine is entirely in the tests, so they are designed
here rather than left to implementation. Six layers, roughly in order of how much they will
actually catch.

### 9.1 Spec corpus (table-driven)

A plain-text corpus, one file per feature area, checked in and readable:

```
# file: corpus/classes/utf8.cases
pattern:  ^([\p{L}]+)=(\d+)$
encoding: UTF-8
input:    "héllo=42"
expect:   match 0="héllo=42" 1="héllo" 2="42"

pattern:  ^(\w+)$
encoding: windows-1252
input:    hex:63 61 66 E9
expect:   match 0=hex:63616669 1=hex:63616669
```

Every case runs under **both tiers** (forcing tier 1 even where tier 0 applies) and under
**every chunking** (§9.4). Adding a case is one paragraph of text, which is what makes
"lots of tests" achievable.

Areas: literals, classes, repetition, alternation, anchors, groups, flags, each encoding
family, byte escapes, empty-match edge cases, pathological patterns.

### 9.2 Differential testing against `java.util.regex`

For the subset both engines share (UTF-8/ASCII, no lookaround/backrefs), run the same
pattern and input through both and compare match position and every group. Sources of
patterns: the spec corpus, harvested DS3 patterns (§9.6), and generated patterns (§9.3).
This is the cheapest high-yield oracle available and it directly validates the
leftmost-first semantics claim.

Since `java.util.regex` is now also a *supported* dialect (language doc §8), this suite has a
second job: its output is the **conformance map** documenting exactly where the two dialects
agree, so a user switching a pattern between them can see what changes. Any divergence found
is either a bug in this engine or a documented entry in that map — never an unexplained
difference.

### 9.3 Property and differential fuzzing

Three properties, all run over a generated pattern grammar × generated inputs:

1. **Tier equivalence** — `plan(p, in) == nfa(p, in)` for every pattern where tier 0
   applies, including group spans. This is the invariant the whole decomposition rests on.
2. **Encoding equivalence** — for encoding `E` and pattern `p`: matching `encode(text, E)`
   with `compile(p, E)` gives the same *character* spans as matching `text` with
   `compile(p, UTF-8)`, for every text in `E`'s repertoire. Catches class-compilation bugs
   that a UTF-8-only corpus never would.
3. **No pathology** — every (pattern, input) completes within a step budget proportional to
   `input × program`, and never throws.

### 9.4 Streaming equivalence

For every corpus case, replay the input through: 1-byte chunks, 2-byte chunks, random
chunk boundaries (seeded, so failures reproduce), and one whole chunk. Assert identical
final results, and assert that no chunking ever produces a match the whole-input run does
not. This is where `NEED_MORE_INPUT` gets its coverage, and it is the layer most likely to
find real bugs.

### 9.5 Encoding conformance

Generated, not hand-written. Two parts:

**Class compilation.** For each single-byte charset the JVM offers, and for UTF-8, and for a
set of code-point ranges: assert that `encodeClass(range)` accepts exactly the byte sequences
`Charset.encode` produces for the code points in the range, and rejects the encodings of code
points outside it. Run across the whole BMP for UTF-8 and across all 256 byte values for each
single-byte charset — both are small enough to test exhaustively rather than by sampling.

**Transcode.** For each non-native charset: encode a text sample, run it through the
transcode stage, and assert (a) the transcoded bytes equal `new String(bytes, cs).getBytes(UTF_8)`,
(b) the offset map round-trips every checkpoint and every interior position to the correct
source offset, (c) matching inside the scope gives the same character-level result as
matching the same text natively in UTF-8, and (d) malformed bytes produce the configured
outcome — in particular that `REPORT` reports rather than substituting U+FFFD. Chunked
replay (§9.4) applies here too and is where partial-character underflow gets exercised.

**`encode(charset)`.** The value-transform direction gets its own cases: representable text
encodes to exactly `String.getBytes(charset)`, unmappable characters produce the configured
outcome rather than a silent `?`, and the round trip is asserted to be non-identity where it
genuinely is — a test that pins the documented caveat rather than pretending it away.

### 9.6 Harvested corpus

Extract regex patterns from Stroom's DS3 test configs
(`stroom-app/src/test/java/stroom/pipeline/xml/converter/datasplitter/`), the content packs,
and any DS3 XML in the content store. For each: does it compile under RE2? which tier?
does it agree with `java.util.regex`? The output is both a test suite and the evidence for
whether `java.util.regex` compatibility (language doc §8) is actually needed.

### 9.7 Benchmarks

A first-class deliverable, not an afterthought — the design makes performance claims and each
one needs a number or it is just an assertion. Baseline measurements, taken before any engine
code existed, are in [03-baseline-results.md](03-baseline-results.md); they confirmed one
claim and refuted two, which is the argument for benchmarking early rather than at P5:

| Claim | Status |
|---|---|
| Straight-line scanning beats `java.util.regex` | **Confirmed** — 2.7–4.0×, with unoptimised scanners |
| Bytes beat chars | **Refuted** for ASCII — compact strings mean an ASCII `String` is already one byte per character; the byte scanner measured 6% *slower* |
| Decoding bytes to `String` is a significant cost | **Refuted** for ASCII — 3–5%; it is a memcpy, not a transcode |
| DS3's `CharSequence` indirection is the dominant term | **Partly refuted** — 9–15%, and measured at a monomorphic call site, so this is its best case |
| An interpreted plan can reach hand-written speed | **Confirmed** — 100–103% of hand-written code where class tests are specialised, so no bytecode generation is needed |
| Composition is free after flattening | Not yet measurable — needs P3 |

The surviving performance argument is therefore **tier 0 versus an automaton**, not
representation. Byte-native execution is justified by streaming without a decode step, binary
format support, zero-copy captures and encoding correctness — and the design should not claim
a throughput advantage it does not have.

JMH, run in CI on a fixed machine profile, with results checked in so regressions are
visible.

**Comparands** — the point is to separate the *sources* of any difference, because a single
"ours vs Java" number cannot tell you which mechanism paid off:

| # | Comparand | Isolates |
|---|---|---|
| 1 | Tier 0 scan plan | The one-pass/no-automaton claim |
| 2 | Tier 1 Pike VM | The linear-time engine on its own |
| 3 | `java.util.regex` over a `String` | The best case for Java — contiguous UTF-16 array |
| 4 | `java.util.regex` over a `CharSequence` view of a byte buffer | What Stroom's DS3 actually does today (`ds3/Buffer.java` is a `CharSequence`), i.e. `charAt` through an interface call |
| 5 | `String.split` / `indexOf` hand-code | The floor — what a human would write for the simple cases |
| 6 | ds-rs, out of process | Reference point only; not a like-for-like comparison |

**The decode axis.** Comparands 3 and 5 need `char`s, so an honest end-to-end number for a
byte pipeline includes `new String(bytes, charset)`. Measure both: *end-to-end from bytes*
(the real pipeline question) and *given an existing `String`* (the fair engine-vs-engine
question). Note that the baseline measured this at only 3–5% for ASCII, because compact
strings make it a memcpy rather than a transcode — it is expected to matter for non-ASCII
input, and should not be quoted as a headline advantage.

**Workloads:** CSV field extraction; syslog line parse; fixed-width records; `key=value`;
a pattern with a long literal prefix (where `java.util.regex` uses Boyer-Moore and should do
well); a Unicode-class-heavy pattern; a pathological backtracker (where `java.util.regex`
degrades or throws `StackOverflowError` and the linear engine does not — an availability
result, not a throughput one); a length-prefixed binary format (which `java.util.regex`
cannot express at all — report it as coverage, not as a win).

**Metrics:** throughput (MB/s), latency (ns/match), **allocation per match** via the JMH GC
profiler — the `ByteSpan`-vs-`String` difference should show up here more starkly than in
raw throughput — and time-to-first-match for the streaming cases.

**Expected result, recorded up front so the benchmark can falsify it:** tier 0 in the region
of 2.5–4× `java.util.regex` on scan-shaped patterns, matching the hand-written floor already
measured; tier 1 roughly at parity or *behind* `java.util.regex` on patterns that do not
backtrack, because a Thompson simulation does strictly more work per byte than a backtracking
walk that never has to backtrack. If tier 1 turns out to be competitive on its own, that is a
pleasant surprise and not the basis of the design. The linear-time engine earns its place on
worst-case behaviour and streaming, not on average-case speed.

Tier 0 failing to reach the hand-written floor would have been the result that mattered most —
it would mean the plan interpreter's own dispatch overhead was eating the advantage, and the
answer would be to generate bytecode per plan. The prototype in
[03-baseline-results.md §7](03-baseline-results.md) settled this: interpretation is enough.

---

## 10. Public API sketch

```java
ByteEncoding utf8 = ByteEncoding.of("UTF-8");

BytePattern p = BytePattern.compile("^(\\S+) (\\S+)", utf8, Flags.CASE_INSENSITIVE);
ByteMatcher m = p.matcher();                       // reusable, NOT thread-safe

MatchOutcome outcome = m.match(window, offset, Anchoring.ANCHORED);
switch (outcome) {
    case MATCH -> { ByteSpan host = m.group(1); ... }
    case NEED_MORE_INPUT -> window = manager.grow(window);
    case NO_MATCH -> ...
}
```

Composition:

```java
Matcher quotedField = Comb.sequence(
        Comb.tag("\""),
        Comb.takeUntil("\"").label("content"),
        Comb.tag("\""))
    .label("quotedField");

Matcher csvField = Comb.choice(quotedField, Comb.takeUntil(","));
Matcher row = Comb.separated(csvField, Comb.tag(",")).label("row");
```

Both `BytePattern` and combinators produce a `Matcher`; `Comb.regex(pattern, encoding)`
lifts a regex into a composition.

---

## 11. Threading and lifecycle

`BytePattern` and compiled combinators are **immutable and shareable**. `ByteMatcher` holds
the mutable match state (thread lists, slots, cursor) and is **single-threaded, reusable**
— allocate once per parsing thread, reset per match. This mirrors `Pattern`/`Matcher` and
avoids per-match allocation in the hot path.

---

## 12. Limits and error handling

| Limit | Default | Behaviour on breach |
|---|---|---|
| Compiled program size | 64k instructions | Compile error with the offending sub-expression |
| Class expansion size | 4k byte-sequence alternatives | Compile error naming the class and encoding |
| Repetition bound `a{n,m}` | m ≤ 1000 | Compile error |
| Match window | caller-supplied | `WindowOverflowException`, never a short match |
| Transcode buffer | caller-supplied, defaults to the match window | As above; a transcode scope cannot outgrow the window that feeds it |
| VM step budget | `input × program × k` | Internal assertion; a breach is an engine bug, not a user error |

All compile errors carry the offset within the pattern and a caret-style rendering. Compile
warnings (unrepresentable case-folded variants, byte escapes straddling characters,
overlapping `choice` first-sets) are collected on the compiled artefact rather than logged.

---

## 13. Phasing

Reordered after [D15](../../design/00-decisions.md): `java.util.regex` is the oracle for the shared subset,
so the scan plan no longer has to wait for the Pike VM to be checked against.

| Phase | Deliverable | Done when | Status |
|---|---|---|---|
| **P1a** | Parser → Hir, RE2 validation, one-pass analysis, scan plans, captures, anchors, unanchored search, `explain()` | Differential tests (§9.2) green against the JDK | **Done** |
| **P0a** | UTF-8 class compiler — code-point sets, byte-sequence alternations, whole-character matching, with the byte-level fast path preserved where provably equivalent | §9.5 conformance green exhaustively over the BMP; differential tests extended to non-ASCII input | **Done** |
| **P1b** | Pike VM for patterns plans reject | Corpus tier 1 patterns green; differential tests cover both tiers | **Done** |
| **P0b** | The remaining `ByteEncoding` families — single-byte charsets and `RAW` | §9.5 conformance green exhaustively per single-byte charset | Not started |
| **P2** | Streaming: windows, sources, `NEED_MORE_INPUT`, window manager | §9.4 chunking equivalence green | Not started |
| **P3** | Atoms + combinators, labels, named matchers, inlining with cycle detection, **the `transcode` stage + offset map**, **tier 2 (`java.util.regex`) as an explicit dialect** | ds-rs's atom test cases ported and green; §9.5 transcode conformance green; tier 2 bounded and streaming-conservative | Not started |
| **P4** | `SCAN_TO_LAST`/`SCAN_TO_FIRST`, partial plans, prefilter tuning | Corpus tier 0 share measurably improved | Not started |
| **P5** | SWAR/Vector API scan kernels, JMH suite for the real engine | Benchmarks published; tier 0 at or above the hand-written floor | Not started |

P1a–P1b is a usable engine for any RE2 pattern on complete UTF-8 input: 18 of the 21 corpus
patterns compile and run, and the 3 that do not need the java dialect by design. The remaining
constraints are the input model (streaming, P2) and the encoding families (P0b), followed by
the composition layer (P3) that motivated the project.

---

## 14. Future work

- Resumable tier-1 state instead of restart-on-grow.
- Lazy DFA, if benchmarks show prefilters are not enough.
- Vector API scan kernels (currently incubator; SWAR fallback first).
- Reverse matching (for `reverse`, and for lookbehind if that is ever added).

- **Profile-guided authoring advice.** Branch ordering is worth 31–36% on `java.util.regex`
  ([03-baseline-results.md §8](03-baseline-results.md)) and nothing at all on either native
  tier, so this is advice for authors rather than an engine optimisation. Shape:

  | Aspect | Position |
  |---|---|
  | What it observes | Which alternation branches actually match, and how often, against real data |
  | What it advises | Reordering branches on the `java` dialect, where they are tested sequentially; and reducing branch *count* elsewhere, since a `SPLIT` chain costs per branch regardless of order |
  | Where it must not act | Automatically. Reordering is only semantics-preserving when branches are mutually exclusive — `(a|ab)` and `(ab|a)` match differently — so a rewrite needs either that guarantee or the author's consent |
  | How safety is decided | Already computed: first-set disjointness is exactly the mutual-exclusivity test, and the one-pass analysis produces it |
  | Hot-path cost | None. Plans are immutable and cheap to compile, so profiling compiles a *second, instrumented* plan rather than testing a flag in the loop — the construction-time decorator approach from ds-rs (`design/combinator_design_plan.md:911-971`) |

  The counter-intuitive part is worth writing down so it is not rediscovered: for tier 0 an
  alternation is a 256-entry dispatch table, so a branch written tenth costs exactly what a
  branch written first costs, and for tier 1 every branch advances simultaneously. The received
  wisdom about putting the common case first is correct, and correctly does not apply here.
- Dead-group elimination driven by the caller's declared groups of interest.

---

## Appendix A — How Rust's byte regex handles non-UTF-8 encodings

Directly relevant because it is what ds-rs relies on, and it is the reason the Java port has
to build something Rust got for free.

**It has exactly two modes, and neither takes an encoding.**

*Unicode mode (default).* `regex::bytes::Regex` compiles Unicode classes into byte-range
alternations that recognise **UTF-8** specifically, via `regex_syntax::utf8::Utf8Sequences`
(`~/.cargo/registry/.../regex-syntax-0.8.11/src/utf8.rs:1-60`). The module's own example:
`[0400-04FF]` becomes `[D0-D3][80-BF]`, and `[0400-052F]` needs *two* sequences,
`[D0-D3][80-BF]` and `[D4][80-AF]`. Ill-formed UTF-8 and surrogate encodings are excluded by
construction. So the "byte" engine is really a UTF-8 engine over bytes — UTF-8 is
hard-wired, not a parameter.

*ASCII-compatible mode (`(?-u)`).* From the crate's own documentation
(`regex-1.12.4/src/bytes.rs:66-88`): Unicode classes are disallowed; `\w`, `\d`, `\s` revert
to ASCII definitions; `\xFF` means the literal byte `0xFF` rather than U+00FF's UTF-8
encoding `C3 BF`; and `.` matches any *byte* except `\n`.

**What that means per encoding:**

| Input encoding | Unicode mode | `(?-u)` mode |
|---|---|---|
| UTF-8 | Correct | Correct but ASCII-only classes |
| Latin-1 / Windows-125x | `\w` does not match `0xE9` (`é`) — it wants `C3 A9`. Non-ASCII bytes are invalid UTF-8, so no Unicode construct matches them | `\w` is ASCII-only, so `0xE9` still fails. The user must hand-write `[\x80-\xFF]` classes and lose all character semantics |
| UTF-16LE/BE | Effectively unusable — every ASCII character is two bytes with an embedded `0x00`, so no pattern written in characters matches | Same; patterns must be hand-written as byte sequences |
| Shift-JIS / GBK / Big5 | No Unicode construct can match a multi-byte character | Works byte-wise, and this is where the trail-byte hazard lives: `[^,]+` can stop on a `0x2C` that is really the second byte of a two-byte character |

So Rust's answer to "what about variable-byte encodings" is: **nothing** — either the input
is UTF-8, or you write raw byte patterns and lose character semantics. ds-rs inherits
exactly that. Its plan to paper over it (`design/encoding_completion_plan.md:96-121`) had two
halves: prefix `(?-u)` for single-byte encodings, and decode-match-reencode with offset
mapping for the rest. Neither was implemented.

The design here keeps the first half and does it properly — compiling classes through a
single-byte charset's inverse table gives real character semantics, which `(?-u)` alone
never could, at essentially no cost and with byte offsets preserved. And it replaces the
second half with an explicit `transcode` stage (§4.4), which is the same idea moved out of
the matcher and into the pipeline, where the extent is scoped, the offset mapping is
first-class, and the malformed-input policy is the user's to choose. The ds-rs version would
have buried all three of those inside a regex match.

---

## Appendix B — Inherited from ds-rs, and deliberate changes

**Taken as-is (proven, worth keeping):**

- The atom catalogue and typed step outputs (`engine/src/project.rs:371-456`).
- Compile-time encoding of literals so matching never encodes
  (`engine/src/compiled.rs:405-472`).
- `StepRef` — a later step consuming an earlier step's numeric output, which is what makes
  length-prefixed binary formats expressible.
- Named patterns inlined with cycle detection (`engine/src/compiled.rs:296-334`).
- Committed/PEG combinator semantics (`engine/src/engine/matching.rs:513-529`).

**Changed:**

| ds-rs | Here | Why |
|---|---|---|
| Regex compiled without the encoding | Encoding is a compile parameter for UTF-8/single-byte/`RAW` | The whole point (§4.1) |
| Groups copied into `Vec<u8>` per match | Zero-copy `ByteSpan` | §8 |
| Shift-JIS/GBK/Big5 matched byte-wise and documented "unsafe" | Not matched natively at all — `transcode` upstream, then UTF-8 | §4.4. Removes the hazard rather than documenting it |
| Lossy decode everywhere | `REPORT` is the default malformed-input action | Silent U+FFFD substitution hides corrupt input |
| `takeN` counts code units | Counts bytes (single-byte/`RAW`) or characters (UTF-8) | No variable-width case survives the transcode split |
| `CharSet.matches_byte` casts byte→char | Classes compiled per encoding | Latin-1 semantics regardless of declared encoding is a latent bug |
| `[:alpha:]` expands to `a-z` | Full POSIX definitions | `matcher/predicate.rs:137` omits uppercase — a bug, not a design choice |
| Unset vs empty capture groups conflated | Distinguished (`ByteSpan.State`) | Callers cannot currently tell "did not participate" from "matched empty" |
| Fancy-regex fallback selected silently on any compile error | Tier 2 (`java.util.regex`) is chosen explicitly by the user | A typo should not move a pattern onto an engine with different guarantees |
| Fancy-regex path silently skips non-UTF-8 input | Tier 2 decodes through an explicit boundary with a declared malformed-input policy | Silent non-matching is the worst failure mode available |
