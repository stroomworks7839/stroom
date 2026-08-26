# Shapeshifter Matching Language — Specification (draft 1)

Status: **draft for discussion**. Nothing here is implemented yet.

> **Retired design: streaming.** This document specifies a growing-window model —
> `ByteWindow`, `NEED_MORE_INPUT`, resumption across chunks — that was built and then
> retired by [D37](../../design/00-decisions.md) on 2026-08-25. The library takes complete
> views only: byte arrays and slices. Read every streaming passage below as the design of
> the day, not as the contract.

This document defines the *language*: what a user writes. The engine that executes it
is specified in [02-engine-design.md](02-engine-design.md).

The language has two layers that share one execution substrate:

| Layer | What it is | Semantics |
|---|---|---|
| **Regex** | A single pattern string, RE2-style | Full leftmost-first regex semantics *within* the pattern, including give-back on failure |
| **Composition** | Named matchers joined with combinators (nom-style) | PEG semantics — ordered choice, committed sequences, no cross-element give-back |

Both compile to byte matchers for a declared **encoding**. After compilation there are no
characters anywhere in the engine — only bytes.

---

## 1. Principles

1. **Bytes are the substrate.** The encoding is a *compile-time* parameter. A pattern is
   authored in terms of characters; the compiler turns every literal and character class
   into byte sequences for the target encoding. At match time the engine sees `byte[]`
   and nothing else. Encodings that cannot be compiled cheaply are converted by a pipeline
   stage instead of complicating the matcher (§4.0).
2. **Linear time, always.** No construct may cause exponential or superlinear backtracking.
   This is what makes the engine safe on hostile input and what makes streaming tractable
   (see §7).
3. **No silent truncation.** A matcher that runs out of input must be able to say
   "I need more bytes", distinct from "no match". Never a short match at a buffer edge.
4. **Deterministic patterns should not pay for a state machine.** Patterns that admit a
   single-pass, no-backtrack execution are compiled to a straight-line scan plan rather
   than an automaton. This is a compiler decision, not a user-visible one — the observable
   result must be identical either way.

---

## 2. Dialect

The regex layer is an **RE2-style subset**: everything RE2 supports, nothing that requires
backtracking. Where RE2's descendants differ in spelling, this dialect follows **Rust's
`regex`** — see §2.4 for what it takes from Rust and, as importantly, what it declines to.
Full `java.util.regex` compatibility is explicitly future work (§8).

### 2.1 Supported syntax

| Construct | Syntax | Notes |
|---|---|---|
| Literal | `abc`, `\n`, `\t`, `\r`, `\f`, `\a`, `\e`, `\0` | |
| Code point escape | `\xHH`, `\x{HHHH}`, `\uHHHH`, `\u{HHHHH}` | A *character*, encoded via the target encoding |
| Byte escape | `\BHH` | A *raw byte*, never encoded. Only legal where byte-exact matching is meaningful (§4.4) |
| Any | `.` | Any character except `\n`; with `s` flag, any character |
| Class | `[abc]`, `[a-z]`, `[^a-z]`, `[[:alpha:]]` | Code-point set; see §4 |
| Perl class | `\d \D \w \W \s \S` | **Unicode by default**, ASCII under `(?-u)` (§4.2) |
| Unicode class | `\p{L}`, `\p{Letter}`, `\p{Nd}`, `\P{L}`, `\p{IsGreek}` | Short code or long name; resolved via the JDK's Unicode tables |
| POSIX class | `[[:alpha:]]`, `[[:^word:]]` | **ASCII only**, and only in this spelling — see §2.4 |
| Concatenation | `ab` | |
| Alternation | `a|b` | Leftmost-first (preference order), not longest |
| Greedy repetition | `a*`, `a+`, `a?`, `a{n}`, `a{n,}`, `a{n,m}` | |
| Lazy repetition | `a*?`, `a+?`, `a??`, `a{n,m}?` | |
| Capturing group | `(...)`, `(?<name>...)` | Numbered by opening paren, from 1 |
| Non-capturing group | `(?:...)` | |
| Inline flags | `(?i)`, `(?is:...)`, `(?-u:...)` | Scoped to the enclosing group |
| Anchors | `^`, `$`, `\A`, `\z`, `\b`, `\B` | See §5. `$` is the end of the input, not "before a final newline" |
| Comment/extended | `(?x)` free-spacing, `#` comments | Optional, low priority |

### 2.2 Flags

| Flag | Name | Default | Effect |
|---|---|---|---|
| `i` | case-insensitive | off | Case folding applied at compile time to literals and classes (§4.3) |
| `s` | dot-all | off | `.` also matches `\n` |
| `m` | multiline | off | `^`/`$` match at line boundaries as well as input boundaries |
| `u` | unicode classes | **on** | `\w \d \s \b` use their Unicode definitions, and `i` folds across Unicode; `(?-u)` gives all of them their ASCII meanings |
| `x` | free spacing | off | Ignore unescaped whitespace and `#` comments in the pattern |

### 2.3 Beyond the RE2 subset: the fancy tier

The constructs that are not regular compile and run natively, on the unbounded backtracker
([D27](../../design/00-decisions.md)) — a fourth engine that only patterns containing one of these
constructs ever reach. Writing the construct is the opt-in; `explain()` names the engine; and
the price is stated in §8: the linear-time guarantee becomes a step budget, so a pathological
pattern-input pair raises `MatchLimitException` instead of hanging.

| Construct | Note |
|---|---|
| Backreferences `\1`, `\k<name>` | NP-complete in general, which is *why* this is its own tier: no visited set can bound it. Numeric references may run ahead of their group; named ones must follow it |
| Lookahead `(?=)`, `(?!)` | Also available at the composition layer as `peek`/`not` (§6.2), which stays on the linear tiers |
| Lookbehind `(?<=)`, `(?<!)` | Bounded length only — the body's byte-length range is what bounds the candidate starts, the JDK's rule for the JDK's reason |
| Atomic groups `(?>...)`, possessive `a*+` | Meaningful now that there is backtracking to control, and present in the harvested corpus |
| `\G` | The end of the previous match — holds where the search started |
| `\Q...\E` | Pure syntax, not fancy: quoting compiles to the same literals the escaped spelling would, on whatever tier the pattern earns |

Still excluded: conditionals and recursion (PCRE constructs `java.util.regex` does not have
either), and the warts declined in §2.4.

### 2.4 What is taken from Rust, and what is not

Rust's `regex` is the closest relative of this engine — RE2-style, leftmost-first, linear
time, byte-oriented — so where a spelling had to be chosen, its spelling was taken. Its test
corpus is run as part of this module's suite ([05-engine-benchmarks.md](05-engine-benchmarks.md)),
which is only meaningful if the dialects agree.

**Taken from Rust:**

| | Rust and here | Perl / `java.util.regex` |
|---|---|---|
| `$` | End of the input | Also matches *before* a final newline, so `^(.*)$` on `"x\n"` captures `x` |
| `\w \d \s \b` | Unicode | ASCII, unless `UNICODE_CHARACTER_CLASS` is set |
| `\p{...}` | Unicode properties only | Also spells the ASCII POSIX classes, so `\p{Alpha}` looks Unicode and is not |
| POSIX classes | `[[:alpha:]]`, ASCII, one spelling | Two spellings for two different meanings |
| Named groups | `(?<name>...)` | `(?<name>...)`, plus Python's `(?P<name>...)` |

The `$` change is the one with teeth. Perl's rule exists so that `chomp`-less line
processing behaves; it means a pattern anchored with `$` silently accepts a trailing
newline it never mentioned. Log records are frequently multi-line and are just as
frequently split on `\n` upstream, so the "helpful" behaviour is as likely to mask a
mis-split record as to save one. `\z` and `(?m)$` say the two things separately, and both
are available.

**Declined, though Rust has them:**

| Declined | Why |
|---|---|
| `(?U)` swap-greed | Silently reverses the meaning of every `*` and `+` after it. A flag that inverts existing syntax rather than adding any is a trap in a config file someone else will read |
| `\b{start}`, `\b{end}`, `\b{start-half}` | Rust's own extension; no other engine has it, and `\b` with a class either side covers the cases seen here |
| `(?R)` CRLF mode | Would be worth having, but line-terminator configuration belongs to the pipeline element, not to every pattern in it |
| `(?P<name>...)` | A second spelling of `(?<name>...)`, inherited from Python. One is enough |
| Segmentation properties — `\p{wb=...}`, `\p{sb=...}`, `\p{Emoji}` | The JDK does not expose them, and this engine deliberately builds its property sets from the JDK so the two agree code point for code point |

**Deliberate deviations from Rust:**

- **`(?-u)` narrows the classes; it does not switch to byte semantics.** In Rust, turning
  Unicode off also makes `.` and `[^a]` match single *bytes*, because Rust ties the flag to
  its "matches are valid UTF-8" guarantee. Here the encoding is a property of the compile
  (§4), so byte-exact matching is `Encoding.RAW` and `\BHH`, and `(?-u)` only changes what
  `\w \d \s \b` and `i` mean. Keeping two unrelated ideas on one flag is what makes
  Rust's version hard to explain.
- **A match never begins inside a character.** Rust guarantees this only in UTF-8 mode;
  here it always holds for a UTF-8 compile, including for empty matches, because a span
  that splits a character is of no use to whatever reads the text back.
- **Look-around sees the region, not the buffer.** Rust can search a span of a haystack
  while still letting `\b` see the bytes outside it. Here the region start *is* the start
  of the input, which is what makes a streaming window self-contained.

---

## 3. Match semantics

**Leftmost-first (Perl/RE2 `leftmost-first`), not POSIX leftmost-longest.**
Given several matches starting at the same earliest position, the one preferred by the
pattern's alternation and greediness order wins. `a|ab` against `abc` matches `a`.
This is what `java.util.regex` does, so harvested patterns behave as their authors expect.

Additional rules:

1. **Unanchored search** finds the match starting at the smallest byte offset. Every offset
   is a legal starting point: both native encoding families are self-synchronising, so a
   mis-aligned start simply fails to match — see
   [02-engine-design.md §4.2](02-engine-design.md).
2. **Empty repetition**: a repetition body that matches empty is not iterated again at the
   same position (standard guard against infinite loops). `(a*)*` terminates.
3. **Capture groups** record the span of their *last* iteration. A group inside a
   repetition that iterated 3 times reports the 3rd span. A group that did not participate
   reports "unset" — distinct from "empty" (ds-rs conflates these; see
   `Regex.java:129-137` in the existing Java DS3 for the same conflation).
4. **Group 0** is the whole match.
5. **Anchoring is a property of the search call, not the pattern**: `ANCHORED` (must match
   at the given offset) or `UNANCHORED` (search forward). `^` is an additional assertion on
   top, not a substitute.

---

## 4. Characters, classes and encodings

This is the part that makes the language different from `java.util.regex`.

### 4.0 Two mechanisms, one boundary

Encodings are handled in two different places, and which one applies is decided by the
encoding, not by the user:

| Encoding | Mechanism |
|---|---|
| UTF-8, `RAW` | **Compiled into the pattern.** Identity or algorithmic; costs nothing |
| Any single-byte encoding (Latin-1, Windows-125x, ISO-8859-x, KOI8-R, Windows-874, …) | **Compiled into the pattern.** A 256-entry inverse table; byte offsets stay identical to the source |
| UTF-16LE/BE, Shift-JIS, GBK, GB18030, Big5, EUC-JP, EUC-KR, ISO-2022-JP | **Transcoded upstream** by a `transcode` pipeline stage (§6.6), after which the matcher sees UTF-8 |

The dividing line is offset preservation. Where a character occupies exactly one byte (or
where the encoding *is* UTF-8), compiling the encoding into the pattern is trivial and every
matched span is directly a span of the source bytes. Where it does not, the machinery needed
to match natively — repertoire enumeration, character-boundary-aware scanning, and for
ISO-2022-JP a stateful automaton that cannot exist — costs far more than a transcode, and a
transcode is something the pipeline should be able to do anyway.

Everything below in §4.1–4.4 describes the compiled-in mechanism and therefore applies to
UTF-8, `RAW` and the single-byte encodings. Inside a `transcode` scope the effective
encoding is UTF-8.

### 4.1 The model

A pattern is authored as **code points**. A character class denotes a **set of code points**.
The compiler is given an encoding `E` and rewrites every literal and class into the byte
sequences that `E` uses for those code points:

```
[a-z]        + UTF-8      →  [61-7A]
[é]          + UTF-8      →  [C3][A9]
[é]          + Latin-1    →  [E9]
[À-ÿ]        + UTF-8      →  [C3][80-BF]
[\u0400-\u052F] + UTF-8   →  [D0-D3][80-BF] | [D4][80-AF]
```

The last example is the general case: **one class becomes an alternation of byte-range
sequences**. This is the same construction Rust's `regex` crate performs internally
(`regex_syntax::utf8::Utf8Sequences`); the difference here is that it is parameterised by
encoding rather than hard-wired to UTF-8, so single-byte encodings compile natively instead
of losing character semantics. See [02-engine-design.md §4](02-engine-design.md) for the
algorithms, and Appendix A there for what Rust actually does and where it falls short.

Consequences, stated plainly:

- The compiled matcher only ever recognises **well-formed characters of `E`**, and for both
  native families every offset is a legal character boundary — single-byte trivially, UTF-8
  because a continuation byte `80–BF` can never begin a valid sequence, so a mis-aligned
  start is rejected by the automaton itself. The start-position hazard that
  non-self-synchronising encodings would introduce is precisely why those encodings are
  transcoded rather than compiled (§4.0). It is also the hazard ds-rs documents as
  "❌ Unsafe" and proceeds anyway
  (`design/byte_level_performance_and_design.md:70-78`).
- Encoding is part of a compiled pattern's identity, but only three shapes exist (UTF-8, a
  single-byte table, `RAW`), so the same source pattern compiles for any of them unchanged.
  Pattern libraries ship one definition, not one per encoding.

### 4.2 `\w \d \s .` and the `u` flag

| Class | `u` on (default) | `u` off |
|---|---|---|
| `\d` | `\p{Nd}` | `[0-9]` |
| `\w` | `[\p{Alphabetic}\p{Mn}\p{Mc}\p{Me}\p{Nd}\p{Pc}\u200C\u200D]` | `[0-9A-Za-z_]` |
| `\s` | `\p{White_Space}` | `[ \t\n\r\f\x0B]` |
| `.` | any code point except `\n` | any code point except `\n`, restricted to the encoding's repertoire |

`u` off is *not* "byte mode" — it narrows the code-point set, and the result is still
encoded through `E`. To match arbitrary bytes regardless of the encoding, use `\BHH`
byte escapes or `Encoding.RAW` (§4.4).

Rationale: `\w` against Latin-1 input containing `0xE9` (`é`) must match, because `é` is a
letter. Rust's bytes mode cannot express this at all (Appendix A). Defining classes over
code points and encoding them is what makes it work.

The Unicode definitions above are exactly those `java.util.regex` uses under
`UNICODE_CHARACTER_CLASS`, which is deliberate: it keeps the JDK usable as a differential
oracle for this engine's *default* behaviour, rather than only for an ASCII corner of it.
`DifferentialTest` checks each shorthand against the JDK over every code point, which is
how two near misses — `Character.isLetter` is not `Alphabetic`, and `Character.isWhitespace`
is not `White_Space` — were found.

### 4.3 Case insensitivity

Case folding is applied **at compile time, to code points, before encoding**: `[a]` under
`i` becomes `[aA]` then encodes. Simple (1:1) Unicode case folding only; full folding
(`ß` → `ss`) is out of scope — it changes length and is a documented non-goal.

Folding is over **equivalence classes, not upper/lower pairs**: `(?i)[a-z]` matches the
Kelvin sign `K` as well as `K` and `k`, and the long s `ſ` as well as `s`. Folding follows
the `u` flag, so `(?i)(?-u)[a-z]` stays inside ASCII — an "ASCII mode" that went on folding
across the whole of Unicode would not be one. Folding happens *before* any negation, which
is why `(?i)[^x]` does not match `X`.

For an encoding that cannot represent a folded variant (e.g. `İ` in Latin-1), that variant
is dropped from the class with a compile warning rather than an error.

### 4.4 `Encoding.RAW` and byte escapes

`Encoding.RAW` treats each byte as its own code point (0–255, identity mapping). This is
the mode for binary formats. Under `RAW`:

- `.` matches any single byte (except `\n` unless `s`).
- `\BHH` and `\xHH` coincide.
- `u` is forced off; `\p{...}` is a compile error.

`\BHH` byte escapes are permitted under *any* encoding, in classes and literals, and are
never re-encoded. Mixing them with character constructs is legal but the compiler emits a
warning when a byte escape could straddle a character boundary in a multi-byte encoding.

### 4.5 Supported encodings

**Compiled natively:** UTF-8, `RAW`, and every single-byte `java.nio.charset.Charset` the
JVM provides — which covers all of ISO-8859-x, Windows-125x, KOI8-R, Windows-874 and
US-ASCII, i.e. the bulk of ds-rs's named set (`engine/src/encoding.rs:29-105`).

**Reached via `transcode` (§6.6):** everything else — UTF-16LE/BE, Shift-JIS, EUC-JP,
EUC-KR, ISO-2022-JP, GBK, GB18030, Big5. Compiling a pattern directly for one of these is a
compile error naming the encoding and pointing at `transcode`, rather than a silently
approximate match.

Encoding resolution (`AUTO`, BOM sniffing, per-node inheritance) is a *host* concern — the
config layer decides which encoding a pattern compiles for, and where to place `transcode`
scopes. This library takes an encoding as a parameter and does not implement inheritance.

---

## 5. Anchors and boundaries

| Anchor | Meaning |
|---|---|
| `\A` | Start of the *match window* |
| `\z` | End of input — only assertable when the input is known to be complete (§7) |
| `^` | `\A`, or after `\n` when `m` |
| `$` | `\z`, or before `\n` when `m`. **Never** "before a final newline" — see §2.4 |
| `\b`, `\B` | Word boundary, using the effective `\w` definition (§4.2), evaluated on decoded characters |
| `\Z` | **Refused.** In Perl it is `\z`-but-allow-a-final-newline; the message points at `\z` or `(?m)$` |

`\b` at the end of the available buffer on an incomplete stream is **undetermined**: the
engine returns `NEED_MORE_INPUT` rather than guessing. Same for `$` and `\z`. This is the
concept `java.util.regex` exposes as `hitEnd`/`requireEnd`, made mandatory instead of
advisory.

---

## 6. The composition layer

The second layer joins matchers the way nom joins parsers. An element of a composition is
one of: an **atom**, a **regex**, another **composition**, or a **reference** to a named
matcher.

### 6.1 Atoms

Ported from ds-rs (`engine/src/project.rs:371-456`), which is the proven set:

| Atom | Parameters | Output | Notes |
|---|---|---|---|
| `tag` | literal text | the literal | Encoded at compile time |
| `byte` | byte sequence (hex) | the bytes | Never encoded |
| `takeWhile` | predicate | consumed bytes | Predicate = named class or `[...]` charset |
| `takeUntil` | literal, `inclusive` | bytes before (or through) the literal | |
| `takeN` | n | n *characters* | One byte each under `RAW`/single-byte; UTF-8 lead-byte lengths under UTF-8 |
| `takeBytes` | n or step reference | n bytes | Reference form reads a length from an earlier step |
| `anyChar` | — | one character | Encoding-aware length |
| `readNumeric` | type, signed, endianness | typed integer/float | i8/i16/i32/i64/f32/f64 |
| `readVarint`, `readVarintZigZag` | — | typed integer | LEB128, protobuf |
| `seek`, `seekAbs`, `seekBack` | n or step reference | nothing | Absolute/backward need a seekable window |
| `tell` | — | typed integer | Current offset |
| `decode`, `encode` | step reference, codec | bytes | base64, hex, url, gzip, deflate, snappy, zstd, lz4, **charset** |
| `regex` | pattern, flags | matched bytes + its own groups | A whole regex as one element |
| `javaRegex` | pattern, flags, encoding | matched bytes + its own groups | Delegates to `java.util.regex` for backreferences and lookaround (§8) |

Atom outputs are **typed** (`bytes`, `int`, `float`) — an integer read by `readNumeric` can
feed `takeBytes` directly without a string round-trip. This is ds-rs's `TypedValue` and it
is worth keeping.

### 6.2 Combinators

| Combinator | Arity | Semantics |
|---|---|---|
| `sequence` | 2+ | All elements in order. **Committed**: no give-back |
| `choice` | 2+ | Ordered — first alternative that matches wins, all tried from the same start offset |
| `optional` | 1 | Match or consume nothing. **Committed** if it matched |
| `repeat(min,max)` | 1 | Greedy, committed, empty-iteration guarded |
| `delimited(open,body,close)` | 3 | Sugar for a sequence |
| `separated(elem,sep)` | 2 | List; sugar for `elem (sep elem)*` |
| `peek` | 1 | Match without consuming (positive lookahead) |
| `not` | 1 | Succeed iff the inner fails, consuming nothing |
| `reverse` | 1 | Match the inner against the reversed character sequence |
| `transcode` | 1 | Convert a region from one encoding to another, then run the inner matcher over the result (§6.6) |

### 6.3 The committed-semantics rule

> **Within a regex, the engine backtracks. Between composition elements, it does not.**

`sequence(a, b)` runs `a`, then runs `b` at wherever `a` stopped. If `b` fails, the sequence
fails — `a` is never re-run with a shorter match. This is PEG/nom semantics.

This is a deliberate restriction, not an oversight:

- It is predictable and explainable, and it is what ds-rs already does
  (`engine/src/engine/matching.rs:513-529`).
- It keeps the composition layer one-pass, which keeps it resumable across stream chunks
  for free.
- Give-back is still available where it is genuinely needed: put the ambiguous part in a
  single regex, which does backtrack internally.

Where a composition's committed choice would give the "wrong" answer, that is a design
smell in the composition and the compiler should be able to warn about it (a `choice` whose
alternatives have overlapping first-sets, a `repeat` whose body can match the following
element).

### 6.4 Labels

Any element may carry a label. Labels are the composition layer's equivalent of capture
group names, and they are what downstream config wires to:

```
sequence(
  tag("\"")               ,
  takeUntil("\"") as content,
  tag("\"")               ,
) as quotedField
```

Unlabelled elements are still addressable positionally (`$0`, `$1`, …) for compatibility
with existing DS3-style references. Regex groups inside a `regex` element are addressed
as `label.groupNumber` or `label.groupName`.

### 6.5 Named matchers and references

A matcher may be named and referenced by name from any composition, including from another
named matcher. References are inlined at compile time with cycle detection (as ds-rs does
via `PatternRef` UUIDs, `engine/src/compiled.rs:296-334`), so there is no recursion — a
recursive reference is a compile error, not an infinite loop.

This gives the pattern library: `ipv4`, `isoDate`, `quotedField`, `csvField` shipped as
named matchers and composed by users.

### 6.6 Transcoding scopes

`transcode` converts a region of input from one encoding to another and runs its inner
matcher over the converted bytes. It is how every encoding outside the two native families
is supported (§4.0), and it is the same shape as the existing `decode` codec steps — a
charset is just another codec.

```
transcode(from: "Shift_JIS", onMalformed: report) {
    sequence(
        takeUntil(",") as name,
        tag(","),
        regex("\\d+") as count,
    )
}
```

**Target.** Always UTF-8 bytes, never Java `char`s — the engine stays byte-level throughout
and surrogate handling never leaks into it.

**One direction only.** `transcode` is a scope that decodes *into* the matcher. Going the
other way — emitting a value in some other charset — is not a scope at all, because there is
no window and no offsets involved: it is a value transform, and it is already expressible as
`encode(value, charset)` alongside the other codecs (§6.1). Keeping the scope
single-direction is what makes the offset map (below) one-way and therefore simple.

**Scope extent.** One of: the whole source; a fixed byte length; the output of a preceding
matcher (via a step reference, as `decode` already does); or until the inner matcher
completes, for a streaming source. The extent must be knowable *before* the inner matcher
runs, because transcoding is what produces the bytes the inner matcher sees.

**Offsets.** Inside the scope, spans are offsets into the transcoded bytes. Mapping back to
source bytes is **exact at the scope boundaries** and **checkpointed within** — the decoder
reports input and output positions on each call, so the engine records a checkpoint every
N bytes and refines by re-decoding the short span between checkpoints when an exact source
offset is asked for. Error locators and `tell` therefore report true source offsets;
per-capture source offsets cost a bounded re-decode and are computed only when requested.

**Byte-exact matching is unavailable inside a transcoded scope** — the source bytes are no
longer what the matcher sees. Match those outside the scope, in `RAW`.

**Malformed input** is governed per scope: `report` (raise an error record and fail the
scope), `replace` (U+FFFD) or `ignore`. There is no default that silently corrupts data —
`report` is the default, in deliberate contrast to ds-rs, which decodes lossily everywhere.

**Streaming.** A decoder that stops on a partial character at the end of the available bytes
reports underflow, which the engine surfaces as `NEED_MORE_INPUT` (§7). The transcode stage
and the matcher share one streaming protocol, so a `transcode` scope is no harder to feed
incrementally than a bare matcher.

**Composition.** `transcode` nests with the codec steps in either order:
`decode(base64) → decode(gzip) → transcode(Shift_JIS)` is a legal chain, each stage
producing the bytes the next one consumes.

### 6.7 Relationship to nom

The composition layer is deliberately modelled on Rust's `nom` — ds-rs's own design notes
carried a "nom equivalent" column against every atom
(`design/combinator_design_plan.md:104-159`). Being explicit about which parts transfer, and
which cannot:

**Taken:**

| From nom | Here |
|---|---|
| The atom/combinator vocabulary — `tag`, `take_while`, `take_until`, `take`, `alt`, `tuple`, `many_m_n`, `opt`, `delimited`, `separated_list`, `peek`, `not` | §6.1–6.2, near one-to-one |
| Committed/PEG semantics — ordered choice, no give-back across a successful sub-parser | §6.3 |
| Zero-copy over the input — parsers return borrowed slices | `ByteSpan` (engine doc §8) |
| `Err::Incomplete(Needed)` — "this parser needs more input", distinct from failure | `NEED_MORE_INPUT` (§7) |

That last row is worth dwelling on: nom arrived at the same three-way outcome for the same
reason, which is decent evidence the streaming model here is the right shape rather than an
invention.

**Deliberately not taken — nom's implementation strategy.** A nom parser *is* a function,
and composition *is* function composition; performance comes from rustc monomorphising and
inlining the whole nested call tree into one flat run of machine code. **Java has no
equivalent.** A combinator tree of `Matcher` objects in Java is virtual dispatch per element
at call sites that go megamorphic, with no cross-tree inlining — a literal port of nom's
runtime model would be *slower* than the regex engine it replaces. Java's substitute for
monomorphisation is to treat a composition as a *description* and flatten it into one plan
at compile time (engine doc §6.5). Same authoring experience, different machinery, and the
difference is forced by the language rather than chosen.

**Not applicable.** nom is a library for programmers, where the compiler validates the
composition and the source code *is* the serialisation. Here compositions are authored in a
UI and stored as config, so three things nom never needs are requirements: they must
round-trip to a serialised form, they must be introspectable (labels, `explain()`, live
match highlighting), and the compiler must *warn* about mistakes nom's type checker would
never see — an overlapping `choice`, or a `repeat` that swallows the element after it.

**Where nom is improved on.** nom makes streaming-vs-complete a property of the
*combinator*: `nom::bytes::streaming::take_until` and `nom::bytes::complete::take_until` are
different functions, and choosing wrongly is a common source of bugs. Here it is a property
of the **input** (`ByteWindow.complete()`), so one matcher definition works against both a
file and an open socket, and no authoring decision depends on which it will face.

**Also unlike nom:** regex is a first-class element (§6.1), not something you avoid. Pure
combinators get verbose for character-level work — the point of having both layers is that
each covers the other's weakness.

---

## 7. Streaming semantics

*(Retired design: D37, 2026-08-25 — the library is complete-inputs-only; kept as the
draft record.)*

Every match returns one of three outcomes:

| Outcome | Meaning |
|---|---|
| `MATCH` | Match found; spans are valid |
| `NO_MATCH` | Definitely no match — more input cannot change this |
| `NEED_MORE_INPUT` | A match is still possible if the window is extended |

`NEED_MORE_INPUT` is returned when:

- a repetition or scan reached the end of the available bytes and could continue;
- a partial literal/character was consumed at the end of the window;
- a `transcode` scope's decoder underflowed on a partial character (§6.6);
- an end-anchor (`$`, `\z`) or a `\b` needs to know what follows the window;
- any alternative was still live when the bytes ran out.

If the caller has declared the input **complete** (finite source fully read), the engine
resolves these internally and never returns `NEED_MORE_INPUT`. For an **unbounded** source
the caller extends the window and retries, subject to a caller-supplied maximum window size
so a pathological stream cannot force unbounded buffering.

The invariant that matters, and that the test suite exists to defend:

> For any input and any chunking of that input, the sequence of results is identical to
> matching the whole input at once.

---

## 8. The second dialect: delegating to `java.util.regex`

> **Superseded by [D27](../../design/00-decisions.md).** The constructs this section delegates —
> backreferences, lookaround, atomic groups — now compile natively to the fancy tier (§2.3):
> once [D25](../../design/00-decisions.md) had built a bounded backtracker, the unbounded one stopped being
> "the single largest item it is possible to remove" and became a few hundred lines over the
> same program representation. One dialect, no decode boundary, and the containment this
> section designs (§8.3) survives as the step budget. The section is kept as the record of
> why delegation was once the right call.

Some patterns genuinely need backreferences or lookaround. Rather than *build* a backtracking
engine for them — which was the original plan here, and is the single largest item it is
possible to remove from this project — Shapeshifter supports `java.util.regex` as a second,
explicitly selected dialect:

```
javaRegex("(?<=\\[)(\\w+)\\]\\s+\\1", encoding: "UTF-8") as tag
```

The JDK's engine is battle-tested, already present, and covers exactly the constructs the RE2
engine excludes. Writing a second engine to duplicate it would be hard to justify.

### 8.1 What it costs, stated so it can be chosen knowingly

| Property | RE2 dialect | `java` dialect |
|---|---|---|
| Input | bytes, native | `CharSequence` — the region must be decoded first |
| Worst case | linear | exponential; `StackOverflowError` is reachable |
| Streaming | exact `NEED_MORE_INPUT` | conservative (§8.2) |
| Offsets | source byte offsets directly | UTF-16 char offsets, mapped back |
| Composition | flattens into a plan with everything else | opaque element, never flattened |
| Speed | plan tier, or automaton | JDK-tuned backtracker |

A `javaRegex` element therefore sits inside a decoding boundary, reusing the transcode
machinery (§6.6): the same extent rules, and the same checkpointed offset map (engine doc
§4.5) — mapping char offsets back to source bytes rather than byte-to-byte.

### 8.2 Streaming with the JDK engine

Not exact, but not hopeless either. `Matcher` exposes `hitEnd()` and `requireEnd()`, which
exist for precisely this:

- `find()` fails and `hitEnd()` → `NEED_MORE_INPUT` (a longer input might have matched)
- `find()` succeeds and `requireEnd()` → `NEED_MORE_INPUT` (more input could change the match)

That is conservative and correct, at the cost of re-matching the whole region each time the
window grows, and of the region having to fit in memory as characters. Both are acceptable
for the "complex pattern on a bounded record" case this dialect exists to serve.

### 8.3 Containing the blast radius

- **Step budget.** The `CharSequence` handed to `Matcher` counts `charAt` calls and aborts
  the match once a budget is exceeded. This is the only practical way to bound backtracking
  from outside, and it turns a hang into a diagnosable failure.
- **`StackOverflowError` is caught** and reported as a pattern-complexity error, as Stroom's
  current DS3 already does (`stroom-pipeline/.../ds3/Regex.java:146-152`).
- **Never automatic.** The dialect is chosen explicitly. ds-rs falls back silently — compile
  with the byte engine, and on *any* compile error retry with `fancy-regex`
  (`engine/src/engine/core.rs:31-42`) — which means a typo can quietly move a pattern onto an
  engine with different performance, different guarantees and different streaming behaviour.
  Here, an RE2 compile failure is an error whose message names the offending construct and
  suggests `javaRegex` along with what selecting it gives up.
- **A distinct element type, not a mode flag.** `javaRegex` is its own element rather than an
  attribute on `regex`, so the choice is visible in the serialised config, is its own node
  type in the editor, and cannot be flipped by editing one attribute. An author choosing it
  should have to mean it.
- **Deployment can withhold it.** A pipeline processing untrusted data is a place where an
  unbounded backtracker is an availability risk, so tier 2 is gated by a capability the
  deployment controls. With it off, `javaRegex` is a compile error naming the policy rather
  than a runtime surprise.
- **The compiler tells authors when they did not need it.** A `javaRegex` pattern that
  happens to use only RE2-supported constructs produces a warning suggesting `regex` — the
  parser already validates against the RE2 subset, so this costs nothing and stops the second
  dialect spreading by copy-paste.

### 8.4 Remaining future work

Genuinely deferred, and much shorter than it was: full case folding, `\G`, POSIX
leftmost-longest mode, and a repertoire check that warns when a pattern can never match under
the chosen encoding.

---

## 9. Open questions

1. **`\B` byte-escape syntax** — `\B` currently means "not a word boundary" in Java. Using
   it for byte escapes is a conflict. Alternatives: `\O` (octet), `\$HH`, or requiring
   `(?-u:\xHH)` to mean a raw byte. Needs a decision before the parser is written.
2. **Unmappable-character policy on `encode(charset)`.** The decode direction has
   `onMalformed` (§6.6); the encode direction needs the same choice for characters the
   target charset cannot represent. Java's default silently substitutes `?`
   (`String.getBytes(charset)`), which is the encode-side equivalent of lossy decoding and
   should not be the default here. Proposal: mirror §6.6 — `report` by default,
   `replace`/`ignore` opt-in — but the transform layer's error model has to be able to carry
   it.
3. **Do we want POSIX leftmost-longest as an option?** Cheap to add in a Pike VM, and some
   data formats are naturally longest-match. Default stays leftmost-first.
4. **Composition-layer give-back** — is committed semantics ever too weak in practice?
   Revisit after the harvested-pattern corpus exists.
5. **Per-capture source offsets** — is the bounded re-decode (§6.6) ever on a hot path, or
   are source offsets only ever needed for error reporting? If the former, the checkpoint
   interval becomes a tuning parameter.

Resolved by the transcode split: `takeN` now counts bytes under `RAW` and single-byte
encodings and characters under UTF-8, with no variable-width case to disagree about.

Resolved by making the encode direction a value transform: the offset map stays one-way.
Note the corollary — **decode-then-re-encode is not the identity** for every charset
(duplicate mappings, ISO-2022-JP escape-sequence choices, unmappable characters), so a
pipeline that must preserve the exact source bytes should capture the `RAW` span rather than
round-tripping through a charset.
