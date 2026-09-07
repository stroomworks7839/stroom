# Typed captures: a value knows its encoding, and nothing is transcoded until someone asks

**Status: design, ruled 2026-09-04 (D43) and deferred the same day; deferral lifted 2026-09-07,
design 24 having been built. Amended 2026-09-07 with §9 — a capture declares what it is, and the
capture binding is compiled — on the user's direction, and ruled the same day (D50), every
question as recommended. Building; phase 1 built and audited 2026-09-07. Amends E3 (the
normalisation at capture) and design 17 §3.1's string row, and takes the capture half of E39.
Engine only. Design 24's character sink decodes the internal UTF-8 form under E3 today and gains
the sink declaration of §4 when this lands.**

Before phase 1 (built 2026-09-07) a captured slice of the input was converted to UTF-8 the
moment it was bound to a variable (`Level.normalise`, E3), and a slice of the *current* match
was converted on the way out by the template's encoding (`Refs.bytes`), while a stored value
was assumed to be UTF-8 already. That gave the engine one internal text form, which every
function, condition and comparison relied on. It also meant the engine could not write a byte
it read: under `raw`, the byte 0x93 left as the two bytes C2 93, and a binary payload captured
for pass-through was inflated on capture and re-encoded on write, twice, for nothing.
`EncodedInputTest.matchesWithARegexUnderRaw` pinned exactly that, and still passes: the two
bytes now leave the UTF-8 sink the same way, transcoded at the write.

The reason to change it is not capability and not efficiency; it is consistency with the type
model the engine already has. Design 17 §3.1 made the casting table the single source of
every conversion, and every other kind obeys it: an `Integer` is not rendered to decimal when it
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
| a group of the current match — regex, delimiter, `All`, a progressive step | the template's *effective* encoding (`Level.effective`: its E3 override, else the run's) |
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
and tags its groups (*as built: the builders tag each group; `MatchResult` itself is
untouched*). `normalise` is deleted; `bindCaptures` stores what the match gives it.

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
the same tag and the same bytes short-circuit. `Comparisons.compare` is unchanged but for its
`Bytes` fast path, which now compares the UTF-8 forms.

**Writes.** *Phase 2; phase 1 writes `utf8()` to the sinks, which are all UTF-8.* One seam,
`Output.write(sink, value)`: `sink.write(value.bytes(sink.encoding()))`.
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
the bytes as tagged; the places that used it to write use `utf8()` until phase 2's seam, and
the places that used it as "UTF-8 bytes" use `utf8()`. *As built:* the callers of `asBytes()`
that remain are `Level`'s two hand-offs of the content to the body and `Steps.bytes`, a step
output fed to a codec — both rightly raw.

## 6. What does not change, and is pinned

- **The corpus.** Every fixture declares `utf-8` or `auto`; every sink is UTF-8. Identity end to
  end; 68/68 must stay 68/68 with no golden touched.
- **`EncodedInputTest`, all nineteen.** Latin-1 and Windows-1252 read and written as UTF-8;
  the per-template override; UTF-16 transcoded whole; the two refusals (a transcode-family
  override, any override under a transcoded source); `matchesWithARegexUnderRaw` — still the
  UTF-8 of U+0093 and U+00E9 between the literal brackets, into the UTF-8 sink it uses. These
  pin that "transcode at use" produces what "transcode at capture" did.
- **New pins.** *Phase 1, built:* a `raw` capture is stored as the two bytes it matched,
  tagged `raw`, not their UTF-8 image; a value captured under one template's encoding and
  written by another with a different one is right (the case the deleted split answered by
  provenance, now answered by the tag); the UTF-8 form is computed once per value and is the
  value's own array under a UTF-8-compatible tag (an identity pin — `Encoding` is an enum, so
  a counting encoding cannot be written); bytes are equal when their text is. *Phase 2:* a
  `raw` capture into a `raw` sink is the input's bytes (0x93 0xE9 out as 0x93 0xE9, the
  literal brackets around them); Latin-1 into Latin-1 likewise; a literal above 0xFF into
  `raw` is `?`; structure into a non-UTF-8 sink is refused.

## 7. Phasing

*Rewritten 2026-09-07. As first written this section argued for landing before design 24, so
that 24's exact-fixture pins would be taken once against the final write path. The deferral
went the other way and design 24 is built; its `CharacterSink` declares UTF-8 and decodes
UTF-8 bytes to characters, which is what it will do after phase 2 too, so nothing is redone.
Each phase is audited before the next, and each is gated on the corpus and `EncodedInputTest`.*

**Phase 1 — the tag.** §2, §3, §5: `Bytes` with its encoding and memo, `MatchResult` tagging,
`normalise` and the provenance split deleted, `Instrument.onCapture(TypedValue)`, the
casting-table row and E3's entry amended. *Test:* corpus and `EncodedInputTest` unchanged; the
provenance pins.

*Built 2026-09-07. As designed, with five things to note. `TypedValue.of(byte[])` is replaced
by `of(byte[], Encoding)` and `utf8(byte[])` — the second for bytes that are UTF-8 by
construction: a literal, a composite, a variable's buffer, a function's argument. `utf8()` on
the interface is the UTF-8 form of any kind, the memo for `Bytes` and the ASCII rendering for
the rest; every write and every join goes through it. The encoding left the two resolvers,
the conditions and the body interpreter as a parameter — fifty-two pass-throughs in `Body`,
the run's two prologue and tail calls, the level's two hand-offs to the body and its guard —
because nothing below the match builders needs to know it any more; `Body` keeps the run's
encoding for the nested dispatch. `Refs.bytes` went rather than moved (E39's note). The
delimiter splitter, the regex match and the whole-content match take the template's
effective encoding at the call and tag every group; the step matcher already had it.
The "counting encoding" pin of §6 is an identity pin instead, `Encoding` being an enum:
`utf8()` returns the same array twice, and the value array itself under a UTF-8-compatible
tag. Gate: engine 570 (566 and the four new pins), pipeline 154, app 5, xmlbench compiles,
checkstyle clean; every corpus golden and all nineteen encoding pins unchanged.*

*Audited 2026-09-07, one reviewer over the code and one over the documents. Code: no
defect; every construction site's tag checked against §2's table, every consumer through
the tag, the two old paths and the new one shown byte-identical for every configuration.
Fixed: a `@param` in `Level` and `Refs`' class javadoc still describing the split; a second
blank line after four licence blocks; an ASCII case added to the identity pin; the memo's
comment now says why the two instances shared across runs are safe. And one change the
audit did not ask for: the memo is filled at construction when the tag is UTF-8-compatible,
so `utf8()` on the common path is a field read with no branch — a targeted probe of four run
rows had read two to three per cent down, inside the new side's own intervals, and the
branch was the one cost the phase had added to every write. Documents: §3's write seam and
§5's factories marked as phase 2 where they are; the `asBytes()` checklist rewritten as
built; §6's pins sorted into built and phase 2; the intro moved to the past tense; design
17 §12 rewritten, having said the conversion boundary was where it was; E3's amendment
moved below its resolution in the ledger's form; E39's two notes merged.*

**Phase 2 — the sink declares.** §4: `encoding()`, `of(stream, encoding)`, the plain byte
sink, the write seam. *Test:* the identity pins; the refusal; `Encoding`'s class comment
corrected — it says `raw` "survives a round trip", which was true of the mapping and false
of the bytes, and is now true of both.

**Phase 3 — the compiled capture and its cast.** §9: `CompiledCapture` built once per binding
by the compiler, the `select` and key-value sources through the compiled reference, the `as`
field read, written and applied at bind. `Refs` loses its capture callers; E39 narrows to
conditions. *Test:* corpus unchanged (every fixture's captures are uncast, and a `select`
capture through the compiled reference must give what the authored walk gave); §9's pins.
*Benchmark:* the compile rows and the run rows of the workloads whose configurations carry a
`select` or key-value capture, three forks, against the phase 2 commit; a regression beyond
its interval blocks the phase (design 27 ruling 4's gate).

**Phase 4 — the record.** E3 amended; E39's entry narrowed to conditions with the capture half
recorded here; design 17 §3.1's boolean bullet and §12; design 24 §2's sink sentence; E36
pointed at §9's slot for the binary readings; D43 cross-referenced from D13 and E3; this
design's as-built record.

## 8. What this does not decide

- **A binary vocabulary.** Length-prefixed framing (take N bytes where N was just captured),
  integer fields with endianness, slicing a captured value by offset, and a Stroom element in
  the writer role that holds destinations and writes bytes. Those are what a JPEG needs and
  DS3 cannot do; this design makes the *carrier* right for them and stops. Filed as E36. §9's
  `as` slot is where the binary readings go when E36 adds them — an integer of a declared
  width and endianness beside the decimal parse — and E36's framing step needs the captured
  integer §9 provides.
- **Re-tagging.** A cast that reads a capture's bytes under a named encoding other than the
  template's — the bytes stay, the tag changes. The natural sibling of `as` and small; it
  belongs with E36's vocabulary, since nothing in the corpus needs it and the template's
  `encoding` override covers the text case.
- **A cast on a variable.** A `variable` is a value its body wrote, `Bytes` tagged UTF-8 (D46);
  the same `as` slot would fit it. Not asked for; the transforms already bind typed values by
  name, which is what a variable holding a number is for.
- **Capture elimination.** §9's cast is paid per match whether the capture is read or not,
  as the group copy is today (design 10's row, E10). Elimination would skip both for an unread
  capture and is E10's, not this design's.
- **An `output.encoding` field in the project.** The sink decides; a runner or command line
  may read a project field to choose the sink later. Not modelled here.
- **XML sinks in other encodings.** `XmlByteSink` is UTF-8; a declaration in the prolog and a
  transcoding serialiser would be a separate change nobody has asked for.
- **A separate "binary" kind beside `raw`.** `raw` already means bytes with no text meaning;
  a second kind would exist only to be told apart from it.

## 9. The capture declares what it is

*Added 2026-09-07 on the user's direction: a capture is a variable like any other, so it
should be able to say what kind it holds — and say it once, at bind, rather than have every
consumer convert. Ruled 2026-09-07 (D50), every question as recommended.*

### 9.1 The field

A capture binding gains `as`, the cast vocabulary the engine already has on a condition's
operand, a `sort`, `min` and `max`: `string` | `number` | `integer` | `double` | `boolean` |
`date` (the kinds and the two typed casts named as XSLT 2.0 names them, D49). Absent means no
cast: the value is `Bytes` as phase 1 leaves it, carrying its tag (§2) — not, as today under
E3, converted to UTF-8 at bind.

```json
{"capture": {"name": "size", "select": {"group": 2}, "as": "number"}}
```

**Semantics: §3.1's table, applied at bind.** The captured bytes are decoded by their tag and
read as the kind named; the store holds the result — an `Integer` or `Double` for `number`,
whichever the text is, an `Integer` for `integer` and a `Double` for `double` (absent if the
text is not that), a `Bool` for `boolean`, an `Instant` for `date` (the ISO-8601 reading; a
format is `parse-date`'s business, as it is everywhere else). Every consumer then sees the kind
and never converts: a `greater-than` against a number literal compares natively under §8's
strict rule with no `as` on the operand, `sort` orders on the timeline, `sum` adds without a
parse per record.

**`as: string` is the memo filled eagerly.** The value stays `Bytes`, tagged UTF-8, with its
UTF-8 form computed at bind rather than on first use. For a UTF-8 feed that is the identity
and costs nothing; for a Windows-1252 or `raw` feed it is E3's conversion, now chosen by the
author for the captures that will be read as text rather than applied to all of them. This is
the optimisation the user named: the author decides where the conversion happens, and a
capture nobody reads as text is never converted.

**A cast that fails is absent** — the same answer the table gives an operand: the store slot is
removed as an unmatched capture's is, `exists` is false, a reference to it resolves to
nothing, and a comparison against it is false. Not a run-time error: a field that is
sometimes not a number is ordinary input, and the author who wants to know has `exists` and
`emit-error`. The instrument sees the cast value, so a recording instrument can show the
absence next to the bytes that failed.

**Which sources.** `group` and `step` cast the slice. `select` casts the composite, which is
UTF-8 by construction (§2), so its cast is the table's `Bytes` row on UTF-8 bytes. Key-value
casts the value, not the key: the key is a store name and is text by definition. `field` stays
refused at compile time.

### 9.2 The compiled capture

Today `Level.bindCaptures` switches on the authored `CaptureSource` per match: a `group` or
`step` reads a group directly; a `select` or key-value capture resolves its `RefExpression`
through `Refs`, walking the authored parts on every match, while a body's references were
compiled once by design 10's change 3. E39 names that as the capture half of its row. The
cast needs a place to live that is decided once, and that place is the same object E39 wants.

**`CompiledCapture(name, source, cast)`**, built by the compiler once per binding, in the
`compile` package beside `CompiledOp`: the source is a group index, a step's group index, a
`CompiledRef` for `select`, or a pair of them for key-value; the cast is the `Cast` or null.
`CompiledTemplate` carries the list. Binding a capture becomes: take the value (a group by
index; a reference through `CompiledRefs.resolveValue`), which is already tagged (§2); apply
the cast, or none; store or remove. `normalise` went in phase 1; after this phase `Refs` has no
capture caller, and its remaining callers are `Conditions`' — E39's other half, which this
design does not take because conditions are not captures and their compile is still the
measurement E39 owns.

**What does not move.** The `Store`, `VarRegistry`, the per-template capture-name
registration, the dense binding for sequences (design 16), and the instrument's contract
beyond the value's type (§3). The lint that checks capture names against reads
(`ReferenceCheck`) reads the authored binding as before; the compiled capture is the run's.

### 9.3 Pinned

- A `number` capture compared with `greater-than` against a numeric literal, no `as` on the
  operand, is true where the bytes read `"10"` and the literal is `9` — and false today, since
  the comparison is cross-kind (§8).
- A `number` capture whose bytes are not a number is absent: `exists` false, a `value-of` of
  it writes nothing, the store slot is not the previous record's.
- A `string` capture under a Windows-1252 template has its UTF-8 form filled at bind (the
  memo is already there before any read, as §6's identity pin reads it), and an uncast
  capture under the same template has none until a consumer asks.
- A `date` capture sorts on the timeline, so `"2026-01-02T00:00:00Z"` follows
  `"2025-12-31T23:59:59Z"` where a string sort would agree and `"2026-01-02T00:00:00+01:00"`
  precedes both, where a string sort would not.
- A `boolean` capture takes the lexical reading: `"1"` is true, `"yes"` is absent.
- A `select` capture and a key-value capture through the compiled reference bind what the
  authored walk bound: the corpus, and a pin with a composite of a group, a literal and a
  stored variable.
- The round trip: `as` reads and writes through `ReferenceJson`, `EveryVariantTest` sees it,
  and an unknown cast label is refused by name as `Cast`'s other readers refuse it.

### 9.4 Questions for the ruling — all four ruled 2026-09-07, each as recommended (D50)

1. **A failed cast at bind:** absent, as the table says (*recommended*), or a `FATAL` message
   naming the capture and the bytes. Absent keeps the table the single source and treats a
   non-numeric field as input, not error; `emit-error` under `exists` is the author's way to
   make it one.
2. **`as: string`** fills the memo at bind (*recommended*) or is a no-op that documents
   intent. Filling is the optimisation asked for and is free on UTF-8.
3. **E39's capture half** comes here (*recommended*) or E39 stays whole and phase 3 casts
   inside the interpreted walk. The compiled capture is where the cast lives; doing it twice
   is the only alternative.
4. **Order:** phases 1–4 now, E36 after (*recommended*); or phase 3 waits for E36 so the
   binary readings arrive with the slot. E36 has no design yet and needs phase 3's `Integer` to
   frame a take; the slot should exist before the readings do.
