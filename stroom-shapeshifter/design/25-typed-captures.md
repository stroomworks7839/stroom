# Typed captures: a value knows its encoding, and nothing is transcoded until someone asks

**Status: design, ruled 2026-09-04 (D43) and deferred the same day by the user: the design
stands, the build waits. Amends E3's "stores hold UTF-8" and design 17 §3.1's string row when
built. Engine only. Design 24 builds first; its character sink decodes the internal UTF-8 form
under E3 and gains the sink declaration of §4 when this lands (§7's ordering argument was
about pinning once, and is outweighed by not doing this now).**

Today a captured slice of the input is converted to UTF-8 the moment it is bound to a
variable (`Executor.normalise`, E3), and a slice of the *current* match is converted on the
way out by the template's encoding (`Refs.bytes`), while a stored value is assumed to be UTF-8
already. That gives the engine one internal text form, which every function, condition and
comparison relies on. It also means the engine cannot write a byte it read: under `raw`, the
byte 0x93 leaves as the two bytes C2 93, and a binary payload captured for pass-through is
inflated on capture and re-encoded on write, twice, for nothing. `EncodedInputTest.
matchesWithARegexUnderRaw` pins exactly that.

The reason to change it is not capability and not efficiency; it is consistency with the type
model the engine already has. Design 17 §3.1 made the casting table the single source of
every conversion, and every other kind obeys it: an `Int` is not rendered to decimal when it
is captured, an `Instant` is not formatted until someone asks for a string. `Bytes` alone is
converted eagerly, outside the table, at capture — a hidden cast applied to everything whether
or not anyone asks, which is why "the internal form is UTF-8" has had to be a rule every
consumer knows implicitly rather than a fact the value carries. The user's ruling: captures
are typed values like every other variable and parameter, cast when asked, by what they are.
Bytes that represent something other than text — a decoded payload, a binary field — stay
what they are until a cast says otherwise. The byte identity and the untouched pass-through
are what falls out of that, not the goal.

---

## 1. The rule

**A `Bytes` value carries the encoding its bytes are in.** Nothing is transcoded when a value
is captured, stored, bound or passed. A consumer that needs *text* asks the value for its
UTF-8 form, which is computed on first use and remembered. A *write* transcodes from the
value's encoding to the encoding the sink declares it accepts. For a UTF-8 feed and a UTF-8
sink, which is every fixture in the corpus, both steps are the identity and cost a reference
comparison, as `isUtf8Compatible` costs today.

That is the whole design. What follows is where the tag comes from, who reads it, and what
"the sink declares" means.

## 2. Where the tag comes from

| value | tag |
|---|---|
| a group of the current match — regex, delimiter, `All`, a progressive step | the template's *effective* encoding (`Executor.effective`: its E3 override, else the run's) |
| the output of a decode step — base64, hex, gzip, deflate — inside a progressive match | the same: the steps that follow already read those bytes under the template's encoding, so that is what they are |
| a literal — `text`, a `RefPart.Text`, a default value, a function argument | UTF-8 (they are Java strings) |
| a composite of parts (`Refs.resolve`), a key-value capture, a `Select` capture | UTF-8: parts are joined in their UTF-8 forms, as design 17 §3.1 says a multi-part expression is a string by construction |
| a function result | UTF-8, or a non-`Bytes` kind |
| under a transcode-family source (UTF-16, design 19 phase 6) | UTF-8: the stream was decoded whole before matching, so every slice already is |

**The one place the principle bends, named.** The transcode family — UTF-16, Shift_JIS,
ISO-2022-JP — is converted to UTF-8 *before* matching, so a capture under it is a slice of the
transcoded stream, not of the input. That is the byte matcher's limitation, not the value
model's: it lowers character classes for UTF-8 and the single-byte encodings and cannot match
those multi-byte encodings in their own bytes, so design 19 phase 6 decodes the stream whole
and accepts that spans are offsets into the decoded bytes (its §4.0). The UTF-8 tag on such a
capture is truthful — those are the bytes it has — and it does not pretend to be the input.
Nothing in the byte-identity story depends on it: `raw`, UTF-8, ASCII and every single-byte
encoding reach the matcher as the bytes they were. Binary declared as UTF-16 is an authoring
contradiction, not a gap. The exit, should anyone need it, is the matcher learning UTF-16
natively (regex-module work) or original byte spans kept beside the transcoded ones, which
design 19 gave up as not worth it for encodings that never preserved offsets. The same is true
one layer up: on Stroom's `Reader` path the bytes were decoded before the engine was involved
and `ReaderBytes` makes UTF-8 from characters; the raw `InputStream` path (design 23 phase 1)
shows the engine the true bytes and is the one the parser element prefers.

`MatchResult` gets the encoding at construction — the three places that build one
(`regexMatch`, the delimiter match, `Steps.match`) all have `effective(template)` in reach —
and tags its groups. `normalise` is deleted; `bindCaptures` stores what the match gives it.

**`ASCII`, `AUTO` and `UTF_8` are one class for transcoding.** Today an ASCII-declared feed
passes bytes above 0x7F through unchanged, the documented garbage-in-garbage-out fast path.
Kept: a value tagged with any of the three *is* its UTF-8 form, and nothing decodes it by the
ASCII table on the way to text. The tag still records what was declared.

## 3. Who reads it

**Text.** `asString()`, `toString()`, `asNumber()`, `asInteger()`, `asBoolean()` all go
through the UTF-8 form: `new String(utf8(), UTF_8)`, where `utf8()` is the memoised
transcode. Every function in `Transforms`, every cast in `Comparisons`, the `matches`
condition's subject, `distinct`'s key, the `preview` in a strict-values message — none of
them change, because each already asks the value for a string. The casting table's first
row reads "decode by the value's encoding" and means the same thing it did.

**Equality and ordering.** `Bytes.equals`/`hashCode` compare the UTF-8 forms; two values with
the same tag and the same bytes short-circuit. `Comparisons.compare` is unchanged since it
compares through the casts.

**Writes.** One seam, `Output.write(sink, value)`: `sink.write(value.bytes(sink.encoding()))`.
`Refs.resolve`, `CompiledRefs.write` and `emit` all go through it. The "only a local group
converts; a stored value passes through" split — E3's implementation, and correct, since every
route into a store normalised — is deleted because the value knows and the template need not:
the rule moves from the caller's provenance to the value's tag. Literal
`text` ops hold a UTF-8-tagged value and take the same seam; for a UTF-8 sink that is today's
byte copy.

**Instrumentation.** `Instrument.onCapture` receives the `TypedValue`, not a `byte[]` "already
normalised": `Instrument.NONE` then pays nothing, and a real instrument decodes as it likes.

**Stores.** Untouched. A store holds values; values hold their tags. `bindDense`, `Fold`,
`DistinctValues`, sequences (design 16) — none of them look inside a `Bytes`.

## 4. What the sink declares

`OutputSink.encoding()`, default `UTF_8`. `XmlByteSink`, `SaxEventSink` and design 24's
`CharacterSink` all declare UTF-8: their names, escaping and characters are UTF-8 by
construction, and an XML document in another encoding is not this design's.
`OutputSink.of(stream)` stays UTF-8. New: `OutputSink.of(stream, encoding)` — for UTF-8 the
`XmlByteSink` as now; for anything else a plain byte sink that writes what it is given and
refuses structure with `StructureException`, since an element name has no bytes in `raw`.

**Transcoding into a sink** is `Encoding.encode(decoded)` where the tags differ, with the
UTF-8-compatible class collapsed to identity. Into `raw` or Latin-1, a character above 0xFF
becomes `?`, which is `Encoding.encode`'s existing rule and the authored case: only a literal
can put one there, since a `raw` capture never has one. A UTF-8 sequence split across two
writes cannot occur, because a value is written whole.

**The byte identity** is then: a `raw` template's capture, written to a `raw` sink, is the
bytes it matched. Same for Latin-1 into Latin-1, Windows-1252 into Windows-1252. The
reversible byte-to-code-point mapping that made `raw` "lossless" is still what a text
function sees; it is simply not applied when nobody needs text.

## 5. The value itself

`TypedValue.Bytes` becomes a final class rather than a record: `value`, `encoding`, and a
lazily filled `utf8` (the run is single-threaded; no volatile). `TypedValue.of(byte[])` is
gone — a caller must say what its bytes are — and `of(String)` tags UTF-8. `asBytes()` returns
the bytes as tagged; the places that used it to write now use the seam in §3, and the places
that used it as "UTF-8 bytes" use `utf8()`. The compiler's grep for `asBytes()` is the
checklist: `CompiledRefs`, `Refs`, `Executor` (`emit`, `Tokenize`'s joined write, the
instrument call), `Transforms`, and the two sinks' attribute buffers.

## 6. What does not change, and is pinned

- **The corpus.** Every fixture declares `utf-8` or `auto`; every sink is UTF-8. Identity end to
  end; 68/68 must stay 68/68 with no golden touched.
- **`EncodedInputTest`, all nineteen.** Latin-1 and Windows-1252 read and written as UTF-8;
  the per-template override; UTF-16 transcoded whole; the two refusals (a transcode-family
  override, any override under a transcoded source); `matchesWithARegexUnderRaw` — still the
  UTF-8 of U+0093 and U+00E9 between the literal brackets, into the UTF-8 sink it uses. These
  pin that "transcode at use" produces what "transcode at capture" did.
- **New pins.** A `raw` capture into a `raw` sink is the input's bytes (0x93 0xE9 out as
  0x93 0xE9, the literal brackets around them); Latin-1 into Latin-1 likewise; a literal
  above 0xFF into `raw` is `?`; structure into a non-UTF-8 sink is refused; a value captured
  under one template's encoding and written by another with a different one is right (the
  case the deleted split answered by provenance, now answered by the tag); the UTF-8 form is computed once per value (a counting
  encoding in the test).

## 7. Phasing, and why this lands before design 24

**Phase 1 — the tag.** §2, §3, §5: `Bytes` with its encoding and memo, `MatchResult` tagging,
`normalise` and the provenance split deleted, `Instrument.onCapture(TypedValue)`, the
casting-table row and E3's entry amended. *Test:* corpus and `EncodedInputTest` unchanged; the
provenance pins.

**Phase 2 — the sink declares.** §4: `encoding()`, `of(stream, encoding)`, the plain byte
sink, the write seam. *Test:* the identity pins; the refusal; `Encoding`'s class comment
corrected — it says `raw` "survives a round trip", which was true of the mapping and false
of the bytes, and is now true of both.

**Phase 3 — the record.** E36 filed for the binary vocabulary (§8); D43 cross-referenced from
D13 and E3.

**Before 24, not after.** Either order works without rework: design 24's `CharacterSink`
declares UTF-8 and decodes UTF-8 bytes to characters whichever lands first. But 24's contract
sentence — "the engine's output is UTF-8 by construction" — is the sentence this design
replaces with "the sink declares what it accepts and every write is transcoded to it", and
24's exact-fixture pins through a real `TextWriter` should be pinned once, against the final
write path, not pinned and then re-verified when the path changes underneath them. This design
is engine-only and its safety net — the corpus and the nineteen encoding pins — is already in
place; nothing about it waits on the pipeline. So: 25, then 24.

## 8. What this does not decide

- **A binary vocabulary.** Length-prefixed framing (take N bytes where N was just captured),
  integer fields with endianness, slicing a captured value by offset, and a Stroom element in
  the writer role that holds destinations and writes bytes. Those are what a JPEG needs and
  DS3 cannot do; this design makes the *carrier* right for them and stops. Filed as E36.
- **An `output.encoding` field in the project.** The sink decides; a runner or command line
  may read a project field to choose the sink later. Not modelled here.
- **XML sinks in other encodings.** `XmlByteSink` is UTF-8; a declaration in the prolog and a
  transcoding serialiser would be a separate change nobody has asked for.
- **A separate "binary" kind beside `raw`.** `raw` already means bytes with no text meaning;
  a second kind would exist only to be told apart from it.
