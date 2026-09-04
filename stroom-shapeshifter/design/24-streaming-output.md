# Streaming output: text is characters, structure is events, and nothing is parsed twice

**Status: design, ruled 2026-09-04. Completes design 23's output round (its §3b); supersedes
design 21 phase 1's parse-and-forward and amends design 22's byte path. D42. Builds now;
design 25 (D43) is ruled but deferred, and its sink declaration is added to this design's
character sink when it lands.**

Design 23 settled the input side of the streaming contract: the engine windows its input by
`buffer_size`, and both pipeline elements feed it a stream. This document settles the output
side. The engine already streams its output — an emitter's bytes leave the moment its body
runs, and `SaxEventSink` delivers each event as it is made. What did not stream was the
pipeline layer's handling of a *text* configuration: design 21 phase 1 held the run's whole
output, parsed it as XML, and forwarded the parse as events with a resolver mapping every
output position back to the input. That was the text→SAX bridge, and the user has ruled it
out (design 23 §3b): Shapeshifter does not bridge; what needs events is given a configuration
that emits them.

---

## 1. The fact the design turns on

In Stroom's pipeline model nothing carries bytes between a parser and an appender. An
appender is a `Destination` — an `OutputStream` — and the only elements that hold
destinations are writers, which consume SAX: `AbstractWriter` is an `XMLFilter` that borrows
a destination and writes to it, and `TextWriter` writes every `characters` event it receives,
line by line, in its own configured encoding. So "a text configuration streams to a byte sink
with no pipe" already has a Stroom shape, and it is the one every text output in Stroom uses
today (an XSLT into a `TextWriter`): **the parser emits characters; the writer is the byte
sink's mouth.**

## 2. The contract

- **A text configuration's output leaves the element as `characters` events**, one per
  emitter write, on the pipeline's thread, as the write happens. No buffer, no re-parse, no
  pipe. `TextWriter` turns them into bytes as they arrive; a rolling appender rolls them.
- **A structured configuration's output leaves as element events**, as it does now (design 22
  phase 2). Nothing changes on that path.
- **One location mechanism for both.** Every event carries the input position of the innermost
  running match at the moment it was made — `InputLocations.currentInputOffset()` through the
  live line index, exactly as the event path does today. The post-run resolver that mapped
  *output* positions to input positions goes: there is no output document to have positions in.
- **The refusal is the consumer's.** A text configuration produces a document of characters
  and no elements. `TextWriter` is content; anything that needs XML — an `XSLTFilter`, a
  `SchemaFilter` — fails on its own terms, because text outside a root element is not
  well-formed, and says so in its own words. The element does not inspect its targets: the
  immediate target is usually a chain (`SplitFilter`, `RecordCountFilter`) and the check would
  be unreliable. If a text→XML re-parse is ever wanted it is a separate pipeline element, built
  as one, not Shapeshifter's.
- **Bytes and characters.** The engine's output is UTF-8 by construction under E3: captures
  are decoded by the source encoding and re-encoded as UTF-8 (`Refs`, `Steps`), and literals
  are Java strings. So the decode in the character sink is lossless, and a `TextWriter` set to
  UTF-8 (its default) writes the engine's bytes exactly. When design 25 (D43, deferred) lands,
  the same fact is restated as the sink declaring UTF-8 and every write transcoding to it. Any other writer encoding is the writer's
  transcoding, as it is for every other text output in Stroom. The three `text_*_exact`
  fixtures pin the identity through a real `TextWriter`.

## 3. What is built

**In the engine — `CharacterSink`**, an `OutputSink` of `Unit.BYTES` whose `write` decodes
the bytes as UTF-8 and delivers them to a `ContentHandler` as `characters`. The decoder
carries an incomplete sequence across writes (the mirror of the pipeline's `ReaderBytes`, which
carries a high surrogate across chunks), so a multi-byte character split between two emitter
writes arrives whole. Its structural calls throw `StructureException`: a text configuration has
none by definition, and `CompiledProject.structured()` is what chooses the sink, so the throw is
a guard, not a path. `startDocument`/`endDocument` bracket the run. The sink lives in the
engine beside `SaxEventSink` for the same reason that one does: `org.xml.sax` is the JDK's,
and the engine stays Stroom-free.

**In the parser element — `ShapeshifterReader.parse`** runs a text configuration into a
`CharacterSink` over the same `LiveLocatingHandler` the structured path uses, on the pipeline's
thread, and reports the engine's messages after. Deleted: `Run`, `runStreamed`, both
`forward`s, `OutputErrorHandler`, `LocatingHandler`, `PARSER_FACTORY`, and in
`InputLocations` the `Resolver`, `resolver(...)`, `lineStarts(byte[])` and the byte-path span
recording in `onOutput` (spans existed only to be resolved). `LineIndex.lineStarts()` goes with
them; the index is bounded on both paths.

**In the filter — `FilterRun`** has one path: the worker runs the engine into an `Enqueuer`
through either `SaxEventSink` or `CharacterSink` by `structured()`, and the pipeline's thread
drains the queue as it does now. Deleted: the `result` field, `reader.forward(result)`, and
the `structured ? … : …` branching that chose a path. The input pipe stays as it is — it is
the mid-pipeline plumbing design 23 §3b named, it is already Stroom-side, and design 23 phase 2
audited it.

**In the app — an end-to-end pin**: `TestShapeshifterParser` gains a case with a text
configuration in front of a `TextWriter` and an appender, its bytes compared whole against
the fixture's golden, over an input large enough to cross several windows.

## 4. What changes for the tests

- `ShapeshifterReaderErrorsTest`'s two pins on the parse of the output — ill-formed output
  reported against the output line; output that is not XML is one fatal and no elements — are
  the bridge's and go with it. What replaces them: a text configuration's events are
  `characters` only, located at the input; a structured configuration's misplaced write is
  still the engine's own FATAL (design 21 phase 3).
- `FilterRunTest.textConfigurationTakesTheBytePathAndAgreesWithTheStructuredOne` becomes:
  the text variant's characters, concatenated, are the structured variant's serialisation
  minus the markup — the same records, the same values, in order.
- `StreamedInputTest.textConfigurationStreamsItsInputToo` gains the other half: the
  characters are delivered *while* the input is still arriving, pinned the way the filter's
  back-pressure test pins it (`delivered()` before the end).
- The engine's fixture corpus is untouched: `GoldenRunner` compares bytes through
  `OutputSink.of(stream)`, which is the sink a `FileAppender`-shaped consumer would see, and the
  new sink is pinned separately for the decode.

## 5. Phasing

**Phase 1 — the character sink and the parser element — Done 2026-09-04.** *As built:*
`CharacterSink` in the engine, an `OutputSink` of `Unit.EVENTS`: each write is one `characters`
event, an incomplete UTF-8 sequence at the end of a write is held for the next (the helper is
now `Utf8.incompleteTail`, shared with `SaxEventSink`), the document starts with the first
write and ends at `end()`, which the caller owes it since a text run has no root to say when
it is over; a run with no output is still one document. `ShapeshifterReader.parse` has one
path, `parseLive`, choosing the sink by `structured()`; the text path holds nothing and each
characters event is located live through `LiveLocatingHandler` like an element event. *Kept
for phase 2, deliberately:* `Run`, `runStreamed`, `forward(Run)`, the output parse behind it,
`LocatingHandler`, `OutputErrorHandler` and `InputLocations.Resolver` with the byte-path spans
— the filter's byte path still runs on them, and deleting them here would have moved phase
2's change into phase 1; the reader's javadoc names them as phase 2's. *Pins:* the sink's own
(`CharacterSinkTest`: one event per write, a split character whole, an incomplete tail at the
end as it decodes, no output is still a document, structure and late writes refused); the two
output-parse pins in `ShapeshifterReaderErrorsTest` replaced by characters-only-and-nothing-
parsed pins (the once "ill-formed" text is delivered as text with nothing fatal; `xml_to_json`
is characters and no elements); `StreamedInputTest.textConfigurationStreamsItsInputToo` now
pins the output side too — the first characters arrive with under a quarter of the input read;
and `TextWriterGoldenTest` runs all seven `projects/text_*` fixtures through a real
`TextWriter` into a byte destination and compares the bytes with the golden, with the reader's
messages compared to the engine's own for the same fixture (003's unmatched separator is an
error DS3 reports too, and the earlier draft of the pin wrongly expected silence). Corpus
unchanged, 625 tests across the two modules green. *As written:* `CharacterSink` with its carry-over,
pinned on a split multi-byte character and on the `text_*_exact` fixtures through a real
`TextWriter`; the reader's text path live; parse-and-forward deleted with its resolver and the
byte-path spans; the errors pins replaced. *Test:* every pipeline test green with the deletions;
the corpus unchanged.

**Phase 2 — the filter.** One path in `FilterRun`; `Run` gone; the text-variant test rewritten
as §4. *Test:* both currencies through the pipe under a live DS3 upstream, the too-large record
still FATAL through the pipe (design 23 phase 2's pin) on both.

**Phase 3 — the app pin.** Parser → `TextWriter` → appender, byte-for-byte, windowed.

Each phase is audited before the next, as the others were.

## 6. What this does not decide

- Whether a text configuration should be able to *declare* that it produces XML and be parsed
  as such by a separate element. Ruled out of Shapeshifter; not designed anywhere.
- Header and footer handling for text output. `TextWriter` has both as properties and they are
  the writer's; a configuration's own prologue and tail emitters (root `text` before and after
  `apply-templates`) already stream as characters like everything else.
