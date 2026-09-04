# Consuming events: Shapeshifter in the middle of a pipeline

**Status: ruled 2026-09-04 — I1, I3, I4 as recommended, I2 against (the pipe; §2 is
rewritten to it). Drives [E30](../stroom-shapeshifter-engine/ISSUES.md). §6 is the order.**

Design 20 put Shapeshifter at the *front* of a pipeline: bytes in, events out, a parser
element. This document is the other half: events in — from a DS3 parser, an XML parser, an
XSLT — and events out, a filter element, so a configuration can be glued in anywhere a
pipeline carries XML. The engine reads bytes and pulls; SAX pushes characters. Something has to
turn one into the other, and the thing that costs is not the plumbing.

---

## 1. The question that costs: what byte image does an event stream have?

The configuration matches *text*. Once the original document has been parsed upstream, its
bytes are gone: what the filter receives is a sequence of `startElement`, `characters`,
`endElement`, with prefix bindings, attribute sets and whitespace exactly as the upstream
parser reported them — which is exactly as the upstream *writer* chose them, or the upstream
XSLT, and no two of those agree on indentation, attribute order, entity forms or whether the
`xsi` prefix is declared on the root or where it is used. A configuration written against the
image one pipeline produces must still work in the next pipeline. So the image is a
**compatibility contract**, not a formatting preference, and it has to be chosen once.

Three candidates:

**A — the engine's own serialisation.** `XmlByteSink` already serialises structure as Stroom's
serialiser does (D41, design 21 phase 2a): Saxon's indentation at three, Saxon's entity forms,
Saxon's wrapping of a long start tag, whitespace-only text dropped, an XML 1.1 declaration.
Feed the incoming events through it and the bytes the engine sees are the bytes a user sees
when they put an indenting `XMLWriter` at the same point in the pipeline, or when stepping
shows them the filter's input. One serialiser, already proven byte for byte against
twenty-two goldens, and an image the user can *look at*. The cost is the one that serialiser
was designed to have: whitespace-only text nodes are the indenter's, not the document's, so a
document in which whitespace between elements is significant has lost it before matching.

**B — faithful.** Every `characters` event kept as it came, no indentation added, entities
escaped only where XML requires. Nothing lost — and nothing stable either: the image is the
upstream's formatting, whatever that was, and a configuration written against it breaks when
the upstream XSLT is reformatted.

**C — Canonical XML.** The W3C answer to "one document, one byte sequence". Stable, defined,
and unfamiliar: no indentation at all, attributes sorted, namespace declarations rewritten,
`\r` normalised — an image nobody in Stroom has ever looked at, produced by a library the
build does not carry.

The recommendation is A, because the contract should be the thing the user can see in the
tools they already have, and because it is already built. B is worth keeping reachable as a
per-element property (`preserveWhitespace`) for the document that needs it, once one appears;
C is named so that it is rejected on the record rather than forgotten.

---

## 2. The plumbing — ruled: a pipe with back-pressure

The engine pulls from an `InputStream`; SAX pushes. The draft recommended serialising the
whole document first, as design 21 phase 1 holds the whole output; the ruling (I2, 2026-09-04)
is the pipe, and it is the better answer for what it buys: the document is not held whole on
the input side, and a large stream part costs the pipe's capacity rather than its own size.
Two things follow, and both are stated so nobody reads the pipe as free.

**Two threads, one of them the pipeline's.** Stroom's pipeline scope is thread-local, so every
event the filter receives and every event it forwards must happen on the pipeline's thread.
The engine therefore runs on a worker: `startDocument` opens a bounded pipe and starts the
worker pulling the engine's `InputStream` from it; each event the pipeline thread receives is
serialised into the pipe's writing end and blocks when the pipe is full, which is the
back-pressure; `endDocument` closes the writing end, joins the worker, and then — on the
pipeline's thread — forwards what the engine produced. The engine is Stroom-free and needs
none of the scope; the join is where its failure, if any, is re-raised where the pipeline can
report it.

**The output is still held whole in phase 1.** The reader parses the engine's output to
forward it (I3), and the output is complete only when the run is; so phase 1 trades holding
the input for holding the output. Phase 2's native path, which forwards events as the engine
emits them, is what makes both ends stream — and it too must forward on the pipeline's thread,
so it will hand the worker's events across the same join rather than call downstream from the
worker. Named now so phase 2 does not discover it.

**The window.** The streamed run reads in windows of the configuration's `buffer_size`,
carrying the unconsumed tail across the edge, so a record image is never cut by where a read
happened to end — only by being larger than the window, which warns as DS3's does.

## 3. The element

`ShapeshifterFilter extends AbstractXMLFilter implements SupportsCodeInjection`, in
`stroom-shapeshifter-pipeline` beside the parser: category `FILTER`, roles `TARGET`,
`HAS_TARGETS`, `VISABILITY_STEPPING`, `MUTATOR`, `HAS_CODE`; the same `shapeshifter` document
property, name pattern and pool as the parser, because it is the same configuration and the
same compiled factory — what differs is only where the bytes come from.

**Input side.** The filter's own `ContentHandler` methods feed an `XmlByteSink` over a buffer:
`startPrefixMapping` is held until its element opens (as the phase-2a test's adapter does),
`startElement` opens the element and its attributes, `characters` is content, `endElement`
closes. At `endDocument` the buffer is the image.

**Output side.** The buffer goes through `ShapeshifterReader.parse` with the filter's
downstream as the content handler — parse-and-forward, the path design 20 S7 keeps for every
configuration, with the reader's error handling (engine messages and output-parse errors kept
apart) and its locator (§4). A structured configuration could go straight to `SaxEventSink`
and skip the re-parse; that is a later phase for both elements at once (§6), not a reason to
have two output paths on day one.

**Documents.** A Stroom filter sees `startDocument … endDocument` once per stream part. The
engine runs once per document; a part that is not a document — a fragment stream — is the
upstream's problem to wrap, as it is for every other filter.

**Encoding.** The image is UTF-8 by construction. A configuration declaring another source
encoding is describing bytes it will never see; the compiler's existing refusal covers it once
the element says the encoding is fixed.

---

## 4. Locations

The reader's locator (design 21 phase 4) points into *its* input — for the filter, the
serialised image. That is the right answer, not a compromise: stepping shows a filter's input
as XML, and that XML is the image, so a location in it is a location the user can see. What is
lost is the upstream parser's position in the original stream — the record's line in the
source file — because the image does not carry it. A design that wanted it back would thread
the upstream `Locator` through the sink into the trace as a second input offset; named, not
built.

---

## 5. What this does not decide

- **Streaming.** A document is the unit (D37). A pipeline that needs a filter to work
  record-by-record on an unbounded stream needs a `SplitFilter` in front of it, as an XSLT
  does.
- **Matching the event stream itself** — element names and paths as a second matching
  vocabulary beside bytes. E30 named it and rejected it; nothing here reopens it.
- **A binary image.** Events are text; a configuration that wants bytes wants the parser
  element.

---

## 6. Phasing

**Phase 1 — the filter, on the paths that exist — Done 2026-09-04.** *As built:* `EventImage`
writes events through `XmlByteSink`; `BoundedPipe` is the back-pressure, a ring buffer either
end can fail so the other stops waiting (not `PipedInputStream`, whose liveness is judged by
the writing thread being alive); `FilterRun` is one document — the image into the pipe on the
caller's thread, `ShapeshifterReader.runStreamed` on a worker, the join and the forward on
the caller's thread — separated from the element so the mechanics are tested without a
pipeline; the reader itself is split into a run half, callable on any thread, and a forward
half, callable on the pipeline's; `InputLocations` takes its line starts from a counting
stream, since a streamed input is not held to be scanned; and `ShapeshifterFilter` wires it
with the parser's document, pool and properties. *Tests:* `FilterRunTest` — DS3's events
through the image are Stroom's golden byte for byte (the contract is a file); a configuration
glued after DS3 produces exactly what it produces from the file DS3 would have written (the
exit); the locator points at the image's lines; five thousand records through a 4 KB pipe;
a worker that dies unblocks the writer and surfaces at the join. `TestShapeshifterFilter` in
`stroom-app`: `DSParser → ShapeshifterFilter → XMLWriter` end to end.

*Findings:* (1) An eater that can match the head of a record is unsafe under the streamed run.
At a window's edge a record cannot match yet — its close is beyond the window — and an eater
listed after it that matches any tag takes `<record>`, after which the record dissolves tag by
tag: one record lost per window, invisible in whole-buffer mode where the record always wins
first. The test configuration's eater carries `(?!record>)`; a configuration written for the
filter must keep its eaters off the shapes its records begin with, or set `buffer_size` past
the largest record. DS3 has the same property and the same answer. (2) The locator reports the
line of the match that *emitted* an element, which under the deferred start tag is the first
child's match, not the enclosing template's — design 21 phase 4's rule, met here for the first
time by a user of the locator, and pinned. (3) The `startProcessing` path refuses a
configuration that will not compile with the stored errors replayed and a fatal, before any
document arrives — which is how an invalid test configuration first showed itself.

*Audited 2026-09-04 — two defects, both in what a fixture cannot show.* (1) `XmlByteSink`
dropped any whitespace-only write outright. The rule was written for indentation between
elements, and a parser splits character data anywhere it likes: `"a"`, `" "`, `"b"` arriving
as three events became `ab` in the image — and the engine's own `element d { value-of; text
" "; value-of }` became `ab` on the way out, a latent phase-2b defect the input side found.
Whitespace is now held as pending in both sinks: text that follows keeps it, a child element
or the close discards it, which is the indenter's own rule. Pinned in both sinks and in the
image. (2) A document that never reached `endDocument` — an upstream failure mid-stream —
left its worker waiting on the pipe for ever. `endProcessing` and a fresh `startDocument` now
abandon an open document, failing the pipe so the worker's read ends and the worker with it,
with a warning against the element. Pinned: an abandoned run's worker is gone within the
join. Read through and left: a fatal in `image()` is logged by the filter and then again by
the parser element that catches the `SAXException`, twice in the indicators, once in truth;
the pipe's 64 KB is a constant, not a property, until a stream shows it matters.

**Phase 1 as written:** `ShapeshifterFilter` as §3, feeding
`XmlByteSink` and forwarding through `ShapeshifterReader`. *Tests:* the filter driven directly
with Stroom's DS3 as the upstream — `Ds3Oracle` produces the events, a configuration written
against the records image transforms them, the recorder downstream compares; the image itself
pinned as a golden for one legacy fixture, so the contract is a file; the locator pointing at
the image's lines; and in `stroom-app`, `DSParser → ShapeshifterFilter → XMLWriter` end to end.
*Exit:* a configuration glued after a DS3 parser produces the XML the same configuration
produces from the file the DS3 parser would have written.

**Phase 2 — the native path for structured configurations, in both elements — Done 2026-09-04.**
*As built:* `CompiledProject.structured()` says whether any body carries an element,
attribute or namespace. The parser element's reader runs a structured configuration straight
into `SaxEventSink` — no serialisation, no re-parse — with each event located *live* from the
innermost running match (`InputLocations.currentInputOffset()`; under the deferred start tag
that is the match whose emission forced the tag, phase 4's rule in this currency); its
messages follow the events, since the run collects them and the events cannot wait. The
filter, for a structured configuration, streams at both ends: the worker enqueues each event,
located live, into a bounded queue (1024) and blocks when it is full — back-pressure on the
output — while the pipeline's thread drains the queue between the input events it pushes and
*while it waits on a full input pipe* (`tryPut` + `awaitSpace` in place of a blocking write),
so neither thread can hold the other; the downstream is given to the run at `startDocument`,
which is where it is known. A text configuration keeps the byte path, forwarded whole at the
end. *Tests:* the reader's native events equal a parse of the byte sink's output for the
structured fixture; through the filter, a text configuration and its structured twin produce
the same events; five thousand records through a 4 KB pipe with more than a thousand events
delivered before `endDocument`; and the locator, both elements, every existing pin.

*Audited 2026-09-04 — the threading, and one leak with two doors.* The deadlock analysis
holds: the pipeline's thread never waits more than twenty milliseconds without draining, and
the worker's wait on the output queue is only ever for a drain the next input write or
`finish()` performs. But `abandon()` failed the *pipe*, and a worker blocked on the *output
queue* — full, with nobody left to drain it because the downstream had just thrown on the
pipeline's thread — never looked at the pipe again. Two doors: a downstream refusing an event
during a drain inside the image's write (the element abandons, the worker waited for ever), and
a refusal during `finish()`'s own drain (nothing abandoned at all). Closed both: the enqueue
loop honours an abandoned flag, and `finish()` abandons on any failure before rethrowing.
Pinned, each with the failure where it really happens — the first on the first element, the
second on `endDocument`, the one event the worker can only produce after the pipe is closed.
Read through and left: a structured configuration that emits no root at all (every element
omitted) forwards no document events, where the byte path would have reported a parse error;
a fatal mid-run on the native path leaves the document unclosed downstream, as DS3's own
fatals do; processing instructions have no place in either path.

**Phase 2 as written:** The compiler
already knows whether a configuration carries structure; when it does, the filter and the
parser run `SaxEventSink` straight into the downstream and skip the serialise-and-parse, with
locations resolved through event-unit spans instead of byte spans (phase 4's sweep, in the
other currency). *Exit:* `StructuredEventsTest`'s invariant holds through both elements, and
the re-parse is gone where it is not needed.

**Phase 3 — `preserveWhitespace` — Done 2026-09-04.** *As built:* `XmlByteSink.Layout` —
`INDENTED`, Saxon's, and `FAITHFUL`, which adds nothing and drops nothing: no indentation, no
wrapping, no final newline, every character written as it came. `EventImage` takes the choice,
`FilterRun` carries it, and `ShapeshifterFilter.setPreserveWhitespace` is the property. Two
images of one document are pinned as files (`images/mixed.xml` → `.indented.xml`,
`.faithful.xml`); the faithful one ends at `</doc>`, since nothing after the root is a
parser's to report.

*Found on the way, and fixed for both layouts:* the indenter indented child elements inside
mixed content — `<p>Hello <b>big</b> world</p>` grew a line break before `<b>` — which Saxon
does not do and which is text corrupted, not layout; and whitespace at a boundary (before a
child, before the close) was always the indenter's to drop, when in an element that has text
it is the text's: a parser splits one text node at line ends, so the last line of a
`<pre>` arrived as a whitespace-only chunk and was lost. The rule in both sinks is now: an
element with text keeps every whitespace chunk; element-only content drops the ones between
elements. Both pinned, in both sinks. The corpus did not move — its documents are element-only.

*Audited 2026-09-04 by probing what the two goldens do not reach; no defect.* Text arriving
after a child (`<p><b>x</b> tail</p>`): indented, `<p>\n   <b>x</b> tail</p>` — the child was
indented before anyone knew text would follow, and the close sits in the text, which is what
Saxon's own on-the-fly indenter does; faithful, exact. A whitespace-only element: indented,
`<a/>` (the 2a audit's known departure from Saxon, unchanged and still unpinned by any
document); faithful, `<a>   </a>`. Whitespace around a child in element-only content: dropped
when indented, kept when faithful. Two roots: each gets the document newline when indented,
nothing when faithful. A character split across writes: whole in both. Named and left: the
faithful image is faithful to the *events* — comments never reach a `ContentHandler`, a CDATA
section arrives as characters and is written escaped, an empty element pair arrives as start
and end and is written self-closed, and the declaration is the image's own line. A document
whose bytes must survive exactly wants the parser element and a file, not events.

**Status: design 22 complete — three phases built and audited.**

---

## 7. Decisions — ruled 2026-09-04

1. **I1 — the image is `XmlByteSink`'s serialisation**, as recommended: what an indenting
   `XMLWriter` and stepping show. Whitespace-only text between elements is lost; the faithful
   image stays reachable as a later per-element property.
2. **I2 — a pipe with back-pressure**, against the draft's serialise-first. §2 carries the
   consequences: the engine on a worker thread, every SAX event on the pipeline's, the join at
   `endDocument`, and the output still held whole until phase 2.
3. **I3 — the reader for every configuration in phase 1**, the native path for structured
   ones in phase 2, in both elements together.
4. **I4 — the locator points into the image**, which is the input stepping shows. The
   upstream position is lost and named as threadable later.
5. **I5 — the filter shares the parser's document and pool** (not put as a question; nothing
   in the rulings argued otherwise).
