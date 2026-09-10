# Design 32 — The encoding is settled before anything compiles

*Proposed 2026-09-10. Engine and the orchestration above it. Raised as: "we should sniff the
encoding of the input before compilation if the user has not set a specific encoding", with an
encoding sniffer, a decision about the default, and an answer for binary input.*

*Yes, it would remove `CompiledMatch.forEncoding` and the second compiled reading beside it. That
is the smallest of the reasons to do it.*

## 1. What the engine does today

`Encoding.AUTO` is the default, and its own javadoc says what it is: **"not an encoding; it is an
instruction to look at the input's byte-order mark"**. So sniffing is already the intent. What
this design changes is *when* — it happens in the middle of a run, after the configuration has
compiled for a reading the input may turn out not to be in.

The sequence now:

1. `Compiler` takes the source's declared encoding, decides whether the whole stream needs
   transcoding, and compiles every pattern, delimiter and step for that reading.
2. A template that follows the source and is not already UTF-8 gets its steps compiled **a second
   time**, for the reading a UTF-8 byte-order mark would move it to — `CompiledMatch.Progressive`
   holds `source` and `marked`, and `forEncoding` picks between them per match.
3. At run time `InputWindow` detects a mark and `Run.applyMark` moves the run's encoding.

## 2. The reason to do it is not the waste

**Only progressive matching gets the second reading.** `markEncoding` reaches exactly one place in
the compiler — the step compile — so `CompiledMatch.Regex` and `CompiledMatch.Delimiter` are built
for the declared reading and have no `forEncoding` at all. A regex is interned under a
`PatternKey` that carries its encoding, and a delimiter is pre-encoded.

So when a byte-order mark moves a run's encoding, **a progressive template follows and a regex or
delimiter template does not**. The compiled model and the input disagree, and nothing says so.
That is a correctness gap, and the dual reading is a patch over one third of it.

The engine half-knows this already. `Run.applyMark` **refuses** a UTF-16 mark outright — a FATAL
telling the author to declare the encoding on the source so the stream is transcoded whole. The
one case where sniffing matters most is the case the run cannot handle, and it answers by asking
to be told in advance. This design is that answer, generalised and moved to where it works.

## 3. What settling it first removes

- `CompiledMatch.Progressive.marked` and `forEncoding`, and the second step compile behind them.
- `markEncoding` threaded through `Compiler` into `MatchCompiler`.
- `Run.applyMark`, the mid-run encoding move, and `Body.encoding` being reassignable at all.
- The UTF-16 refusal, which becomes a decision made before compiling rather than a message.
- **The asymmetry**, which is the one that matters: with one reading fixed before compilation,
  there is nothing for a match kind to be inconsistent about.

## 4. The ruling: `AUTO` never reaches the compiler

*Ruled 2026-09-10.* The encoding is **forced at compile time**. `AUTO` stops being something the
compiler can be handed and becomes what it always described itself as — an instruction, belonging
to the exterior configuration and resolved against the input before any compilation happens. The
orchestration builds and pools a compiled model per encoding it needs.

That is the exit §4 first listed as (1), and making `AUTO` *unrepresentable* to the compiler is
what turns it from a convention into an invariant. `Compiler.compile` should take a concrete
encoding and refuse `AUTO` outright, the way `VarNames` refuses a late intern: a compiled model
that cannot say which reading it was built for is the bug this design exists to remove, and it
should not be constructible.

**The granularity is already right.** A `Template` carries "an encoding override for this
template, or null to inherit", and nothing finer exists — `MatchExpression` has no encoding of its
own. So regexes, delimiters and steps take the source's reading by default and an author can force
one per template, which is what was asked for and is what the model already says. The only change
is that the thing they inherit is now always a real encoding rather than sometimes an instruction.

**A compiled model becomes reusable in a stronger sense, not a weaker one.** Today one graph
claims to serve any input and quietly serves some of them wrongly (§2). After this, a graph is
compiled *for* a reading and says so, and the pool holds as many as the streams require — one for
a uniform feed, a handful for a mixed one. The pool is keyed by the sniffed encoding, which is
data, so design 30 §1 permits the map.

### 4.1 Where the pool goes, which is not where it first looks

`ShapeshifterParserFactory` compiles in its constructor and `getParser()` hands out a reader
**before any bytes exist**. Bytes arrive at `ShapeshifterReader.parse(InputSource)`. So the pool
cannot be consulted where the parser is made.

The shape that works: the **factory owns the pool** — the project, the registry, and compiled
models built on demand — and the **reader takes the pool rather than a model**, sniffs at
`parse`, and asks for the one it needs. A run still sees exactly one immutable graph; what moved
is when it is chosen.

Two things fall out of that, both good. Compilation becomes **lazy**, so a configuration that is
never parsed with is never compiled, where today the factory's constructor compiles regardless.
And the pool is per factory, so its lifetime is the pipeline element's rather than global, which
is where a cache keyed by data belongs.

## 5. What the sniffer has to decide

**A class of its own**, because three things need the same answer and must not each guess: the
orchestration choosing what to compile, the run reading bytes, and the regex compiler lowering
patterns.

- **A byte-order mark, first.** `Encoding.detectByteOrderMark` already exists and finds UTF-8,
  UTF-16LE and UTF-16BE. A mark is a statement, not a guess, and outranks everything below.
- **Then validity.** Is the leading window valid UTF-8? Invalid UTF-8 is strong evidence, because
  the encodings that matter here are byte-compatible with ASCII and differ only above 0x7F.
- **Then the default**, which is a decision rather than a detection, and §6 is about it.
- **And "this is not text at all"**, which is the JPEG question.

**Binary input.** `RAW` exists and maps every byte to a code point, so the vocabulary is there;
what is missing is the decision to use it. A sniffer that sees a JPEG's `FF D8 FF`, a NUL byte in
the first window, or a byte sequence valid in no candidate encoding should answer `RAW` rather
than pick a text encoding that will mojibake. Worth stating plainly: **`RAW` is the right answer
for binary, not a failure mode** — a configuration matching a JPEG's structure wants bytes, and
every byte being a character is exactly what it needs.

## 6. The default, which is the part with a trap in it

Today `AUTO` reads as UTF-8. Three candidates, and the trade is not obvious:

- **UTF-8.** Right for modern input, and it is what `AUTO` already does. But undecodable bytes are
  a real case — E45 was resolved on that ground the same day — so a UTF-8 default has to have an
  answer for input that is *mostly* UTF-8 and occasionally not.
- **Windows-1252 or Latin-1.** Never fails: every byte maps to a code point. That is the trap.
  A wrong answer that cannot fail is worse than one that can, because nothing downstream can tell
  it went wrong, and the mojibake reaches the index.
- **RAW.** Never fails and never pretends: no byte claims to be a character it is not. The cost is
  that character classes and case-insensitivity stop meaning anything above 0x7F.

*No recommendation yet, deliberately.* This one wants the corpus looked at before it is answered —
what the real DS3 configurations declare, and what their inputs actually are. The
regex-corpus entry has been waiting on real configurations since 2026-08-27, and this is a second
question that would be answered by the same material.

## 7. Phasing

**Phase 1 — the sniffer, with no wiring. Done 2026-09-10.** `text/EncodingSniffer`: a decision
table, and 21 tests. It answers; nothing uses it yet. **This phase was worth doing even if the
rest is refused**, because the run's own sniffing is spread across `InputWindow`, `Run` and
`Encoding` today.

*What it decides, in order — and the order is the design.* A byte-order mark. Then ISO-2022-JP,
which announces itself with escape sequences no other candidate uses. Then **UTF-16 with no mark,
which must come before the binary rule** — such text is half NUL bytes, and the signal is not how
many but that they all fall on one alignment, which is the byte order and which random binary does
not manage. Then a NUL, which means not text: `RAW`, and that is the right answer for a JPEG
rather than a failure. Then UTF-8 validity. Then the caller's ordered candidates. Then the
fallback.

*It returns how sure it is*, not just an encoding: `MARKED` for a statement, `DEDUCED` when only
one reading fits, `NARROWED` when several fit and the caller's order chose, `ASSUMED` when nothing
did. A component that guesses has to be able to say so, and §6's trap — a single-byte encoding
never fails, so a wrong answer cannot be detected downstream — is why this asks rather than picks.

*The multi-byte grammars discriminate, and the measurement is worth keeping.* Checked against real
JDK encoder output rather than against a reading of the specifications:

```
             shift_jis  euc-jp  gb18030  gbk  big5  euc-kr
 shift_jis      fits       -      fits   fits   -    fits
 euc-jp          -       fits     fits   fits  fits  fits
 gb18030         -         -      fits   fits  fits   -
 gbk             -         -      fits   fits  fits   -
 big5           fits       -      fits   fits  fits   -
 euc-kr         fits     fits     fits   fits  fits  fits
```

Every grammar accepts its own output and most accept several others'. **Big5 text fits the
Shift_JIS grammar, so no ordering of all six answers both correctly** — order cannot repair an
overlap, only decide who wins one. That is why the candidate list is the caller's and why the
constant listing all six is documented as a thing to understand rather than a default to use: a
deployment should pass the one or two encodings its feeds carry, and get `DEDUCED`.

*Cost, since it was asked:* **one window of 8 KiB, read once, and the bound is enforced rather
than advisory** — a caller handing over a whole file gets the same work as one handing over the
first page, and a test pins it.

*Known limits, named rather than left to be found:* UTF-16 text made entirely of characters above
U+00FF carries no NULs and cannot be told from other two-byte data by structure; and single-byte
encodings are not told apart at all, which is §6's open question rather than an omission.

**Phase 2 — the ruling on §6**, the default, which is the owner's and is not a code change. §4 is
ruled already.

**Phase 3 — settle before compiling.** `Compiler.compile` takes a concrete encoding and refuses
`AUTO`; the factory owns a pool and compiles per encoding on demand; the reader sniffs at `parse`
and asks for the graph it needs. `Encoding.AUTO` stops reaching the compiler at all, and the
refusal is what proves it.

**Phase 4 — remove the dual reading.** `marked`, `forEncoding`, `markEncoding`, `applyMark`, and
`Body.encoding`'s setter. This is the phase that pays for itself in deletions, and it must come
last: while a run can still move its encoding, the dual reading is load-bearing for the one match
kind that has it.

## 8. What would make this a mistake

- **If real configurations are overwhelmingly UTF-8 or declared**, the whole design is machinery
  for a case that does not arise, and the honest answer is to fix the asymmetry (§2) by giving
  `Regex` and `Delimiter` the second reading, and stop.
- **If the pool turns out to hold one entry in every real deployment**, then §4.1's machinery is
  paying for a case that does not arise, and declaring the encoding on the source — which is
  already supported — was the answer. The pool should be instrumented for its size before it is
  defended.
- **If sniffing is wrong often enough to need overriding per stream**, it has become configuration
  rather than detection, and belongs in the source declaration where it already is.
