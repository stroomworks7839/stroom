# Encoding plan — the parameter the bytes-only ruling owes

D38 ruled the semantics in encoding terms — a character construct matches only well-formed
characters of `E` — while the implementation carries exactly one `E`. That was tolerable
until E29: templates declare encodings (E3, ruled and pinned), delimiters and progressive
steps honour them, and regex steps silently compile UTF-8 against whatever bytes the feed
carries. The spec has said what the parameter looks like since draft 1
([regex 01 §4.0–4.5](../stroom-shapeshifter-regex/design/01-regex-language.md)): three
compiled-in shapes — UTF-8, a 256-entry single-byte table, `RAW` identity — and a
`transcode` stage upstream for everything else. This is the plan for making that true.

What exists already, so the plan does not re-plan it: the engine's `Encoding` enum with
per-name refusal (E22, resolved — *an encoding decodes exactly what it says, or the
configuration naming it does not compile*; every refusal below inherits that doctrine);
per-template resolution (E3, resolved, `EncodedInputTest`); steps classifying characters
under the effective encoding at run time (E5, resolved). The regex module is
dependency-free (`verifyZeroDependencies`), so it takes its **own** three-shape encoding
type and the engine maps its enum onto it — the enum never crosses the module boundary.

The method is the fixed one: one change at a time, measured; any edit touching a search
path gates on the buffer-CSV and per-match-datetime canaries; cross-day comparisons are
paired runs; results are checked in. Statuses move here.

---

## Phase 0 — Make the gap honest *(small; no perf surface)*

The stopgap E29 names. `Compiler.compileMatch` (and the `intern()` path for condition and
replace patterns) refuses by name any template whose effective match encoding is not
UTF-8-compatible and which carries a regex anywhere — match expression, progressive step,
condition, or replace. "No match" stops standing in for "cannot do that".

Also in this phase, because they are the same audit:

- **Fixture census.** Which existing fixtures the refusal breaks is evidence of how much
  the silent gap is leaned on; any that break were mis-matching before and get recorded,
  not worked around.
- **The `RAW → UTF_8` charset shortcut** (`Compiler` line ~73) — decide whether encoding a
  RAW template's delimiters through UTF-8 is right (text delimiters in a binary feed) or a
  second silent approximation; record the answer either way.
- **The UTF-16 story.** No `transcode` stage exists (01 §6.6 is spec only). Establish what
  a UTF-16 source does today, and if the answer is "reaches matching un-transcoded", the
  refusal covers it too.

**Exit:** no configuration can silently get UTF-8 matching it did not ask for. E29 moves to
`deferred` with the stopgap named.

## Phase 1 — The parameter exists *(API; UTF-8 behaviour byte-identical)*

The regex module grows its encoding shape — `UTF8 | TABLE(256-entry inverse) | RAW` — and
`BytePattern.compile(pattern, flags, encoding)`, the old overloads delegating to `UTF8`.
The parameter threads to the lowering seams and to pattern identity; the engine's
`intern()` keys change from pattern text to (text, effective encoding). Only `UTF8` is
accepted at first, so behaviour is provably unchanged: full suites both modules, canary
pair, and a spot-check that compiled artifacts for UTF-8 are structurally identical.

This is the honesty mechanism as much as the capability: after this phase every UTF-8
assumption in the module flows through one named seam, and phase 2's audit falls out of
the compiler's own type checking rather than out of grep.

**Exit:** the signature exists everywhere, nothing behaves differently, and the places
that will need per-encoding work are enumerated by the compiler instead of by memory.

## Phase 2 — One boundary: the tree's classes byte-compile *(the measured risk)*

The tree engine is the unlisted third mechanism of 01 §4.0: the flat engines erase the
encoding at compile time (classes → byte automata via `Utf8.sequences`), the tree
re-derives it at match time (`OneChar.accept` → `Utf8.decode`). It is a port artifact of
the JDK architecture (D30) — `Utf8.decode`'s history says so — and it is where the greedy
divergence lived, because a runtime decode step is a surface a shortcut can disagree with.

This phase compiles the tree's class nodes (`OneChar`, and through it `StarClass` /
`CountedClass` items) to the same byte form the flat engines use: ASCII table plus
byte-sequence trie. `Utf8` retreats at match time to the two genuinely textual uses,
backreference comparison and case folding. A byte-compiled class exposes its lead bytes
directly, which the lazy-run skip's `leadingByte()` currently reconstructs by hand —
the skip gets simpler, not more complex.

Risk is priced honestly: this edits the primary engine's hottest nodes, the non-ASCII path
could move either way, and the phase ships only under the full gate — canaries, the
LazyRun rows, an evening paired full set — with `GreedyRunRawBytesTest` and
`LazyRunSkipTest` as the semantic pins. If the tree measurably loses, the fallback is
per-encoding decode (for `TABLE` a flat lookup, so phases 3–4 do not block on this one),
and the loss is recorded here with numbers.

**Exit:** no engine decodes input at match time to answer class membership, and 01 §4.0's
"two mechanisms" is true again.

## Phase 3 — Single-byte tables *(the one real feeds are waiting on)*

`TABLE` lowering: literals and classes through the 256-entry inverse, so every class is one
byte wide — which the scan plan's byte ops take directly, and dispatchability should
improve, not suffer. Case folding expands within the table's repertoire at compile time.
`\w \d \s`, `\b` (`Words`) and `\p{...}` (`UnicodeClasses`) answer over the 256-code-point
repertoire by intersection; the `u` flag semantics of 01 §4.2 get their per-table statement
recorded in 01.

Two notes that keep later arguments short: under a single-byte encoding **every byte is
well-formed**, so D38's strictness is vacuously satisfied and the UTF-8 deviation pin does
not extend — stated in the tests, not just here. And the differential oracle is unusually
clean: decode with the table, run the JDK on the decoded `String`, and offsets map
one-to-one, so the whole `LazyRunSkipTest`-style apparatus transfers.

Engine integration lands here too: `compileMatch` and `intern()` pass the effective
encoding through, the phase-0 refusal narrows to the still-unimplemented shapes,
`EncodedInputTest` gains a regex step under `windows-1252`, and **E29 closes**. Benchmarks:
a windows-1252 corpus row alongside the canaries — which encodings matter beyond
Latin-1/1252 is a question the arriving real DS3 configs answer, not this plan.

**Exit:** a `windows-1252` template's regex matches the bytes its feed carries, proven by
the extended `EncodedInputTest` and a checked-in benchmark row.

## Phase 4 — `RAW` *(small once phase 3 exists)*

Identity lowering per 01 §4.4: `.` is any byte except `\n` unless `s`; `u` forced off;
`\p{...}` a compile error naming the mode. The phase-0 answer on RAW delimiters is
implemented rather than shortcut. This gives D38's composition story its in-dialect mode
for binary formats.

## Phase 5 — `\BHH` byte escapes *(completes D38's in-pattern leg)*

Permitted under any encoding, never re-encoded, straddle warning per 01 §4.4. One spec
decision to confirm before parsing starts: `\B` is also the conventional non-word-boundary
escape, and 01 must say which spelling wins or how `\B{hex}{hex}` disambiguates — settled
in 01 first, implemented second.

## Phase 6 — `transcode` *(deferred, deliberately)*

The UTF-16/Shift-JIS/GB18030 family stays upstream per 01 §4.0, and no stage exists.
Phase 0's refusal keeps the absence honest; a real feed demanding one is the revisit
condition. Nothing above depends on this.

---

Folded issues, for the ledger cross-refs: **E29** (driver; stopgap phase 0, closes phase
3), **E22** (doctrine inherited by every refusal here), **E3/E5** (the prior art this
completes: the third vocabulary joins the two that already honour declared encodings),
**D38** (per-encoding strictness statements land with each phase), **D30** (phase 2 retires
the port artifact it left). The tier-0 strictness deviation and its pin are UTF-8-specific
and unmoved by any phase.
