# The streaming contract: multi-terabyte inputs, DS3's way

**Status: design, opened 2026-09-04 on the user's correction. §5 collects what needs ruling.
Amends design 21 phase 1 and design 22; supersedes nothing in the engine.**

Stroom parses files of terabytes. DS3 does it by filling a window, matching in it, consuming,
refilling, and carrying the unconsumed tail forward — never holding the stream, never holding
its output. Shapeshifter is expected to behave the same way, as its *parsing contract*, on both
sides: the input is streamed through a window the engine manages, and the output is streamed
as it is produced, whether that output is bytes from the emitters or SAX events. The regex
library does not stream and need not (D37): a matcher is handed a window and must never be
allowed to want more than the window; the engine is what manages that.

The engine already does. The pipeline elements did not. That was design 21 phase 1's choice —
`runWhole`, "one input is one document", made for the whole-document reasoning of D37, which
is about the *library* — and the choice was wrong for the contract. This document says what
the contract is, where it is met, where it is broken, and how it is fixed.

---

## 1. The contract, in DS3's terms

`DS3Reader` keeps a character buffer of `capacity`. `fillBuffer` reads while the buffer is
below half full, compacting the consumed prefix away; `eof` is the reader's end. A top-level
match consumes and the reader moves; the next fill happens when the buffer is half empty. A
match that consumes the *whole* buffer is treated as having matched too much: DS3 enters
recovery, advancing one character and trying again, and only when that runs to the end of the
stream does it fail — `FATAL`, "matched too much content and reached the end of the stream
without recovering". Output is events, emitted as each `<data>` is found; nothing is held.

Three properties follow, and they are the contract:

1. **Memory is the window**, not the stream. A record is never bigger than the window's
   capacity; the stream can be any size.
2. **A record is never cut by where a read happened to end.** The unconsumed tail is carried
   and the window refilled behind it; only a record larger than the window fails.
3. **Output leaves as it is produced.** No part of the output waits for the run to finish.

---

## 2. Where the engine stands

`Shapeshifter.run(compiled, InputStream, sink, instrument)` → `Executor.stream`: a byte window
of the configuration's `buffer_size`; one byte of pushback so "full" and "exhausted" are told
apart; a match that runs into the edge with input unread is discarded, the window compacted
and refilled, and the match retried against more — property 2. Match counts live for the
stream. Property 1 holds by construction; property 3 holds because the sink is written as
each body runs.

One difference from DS3 at the full-window edge, now ruled (§5.1). Where DS3 recovers by
advancing a character, the engine treats a root match that fills the window with input still
unread as fatal — the record is larger than `buffer_size`. Today the engine does this only for
an end-anchored pattern and merely *warns* otherwise; §5.1 promotes the warning to a fatal for
every root match. DS3's grow-and-recover is not adopted; the fixed window with a hard limit is
the contract.

`runWhole` exists beside it — one window the size of the input — and is right for tests and
for a caller that holds bytes anyway. It is wrong for a pipeline element.

---

## 3. Where the elements stand, and what changes

### The parser element (design 21 phase 1)

**Broken twice.** `ShapeshifterReader.parse` reads the whole `InputSource` into a byte array
and runs `runWhole`; on the byte path it then holds the whole output and parses it. A terabyte
in, a terabyte held, then held again.

**Input.** The pipeline hands the element the raw byte stream (`setInputStream`, the default
with no reader element in front) or a decoded `Reader` (`setReader`, when a reader element
sits between source and parser) — the framework's own `TakesInput`/`TakesReader` shape, as
`XMLParser` has it (§5.3). Either way `Shapeshifter.run` streams it through the window rather
than reading it whole; a raw byte stream keeps `source.encoding` real, a reader is UTF-8. The
only element-level work is to stop `AbstractParser.getInputSource` decoding a byte stream that
should stay bytes.

**Output, structured configuration.** Already streams: the native path (design 22 phase 2)
runs `SaxEventSink` straight into the downstream on the pipeline's own thread, each event
located live. Nothing on the output side changes for it.

**Output, text configuration.** Reshaped by the user's ruling and deferred to the output round
(§3, below the line): a text configuration writing to a byte sink streams; feeding a SAX
consumer it is refused rather than bridged inside the element. Only the input side of the
parser element changes in phase 1.

**Messages.** The engine's messages arrive as the run collects them and are reported as they
come, not after; a `FATAL` ends the run.

### The filter (design 22)

Its input already streams through the pipe under the same window rules — phase 2 re-audits that
against this contract. Its structured path already streams both ends. Its text path holds the
output whole, which is the same defect the output round addresses, not this one.

---

## 3b. Output — the direction, deferred

**Output — not resolved here, and reshaped by the user's direction (2026-09-04).** The draft
of this document proposed building a parse worker and an output pipe *into* the element to turn
a text configuration's bytes into SAX events while streaming. The user has ruled that this is
not the engine's or the element's job. The output contract instead is:

- **A SAX-emitting (structured) configuration streams to a SAX consumer with no pipe** — the
  native event path (design 22 phase 2), already built, already streaming.
- **A text configuration streaming to a byte sink** — a `FileAppender`, a stream target —
  **streams with no pipe**: the emitters write bytes as each body runs, and the bytes leave.
- **A text configuration feeding a SAX consumer is refused**, not bridged. A Shapeshifter
  element placed before something that requires events must be given a configuration that
  emits events. If a text→SAX bridge is genuinely wanted, it is a *separate* pipeline
  construction — an external IO element that serialises and re-parses — not machinery inside
  Shapeshifter.
- **The pipes are Stroom pipeline plumbing, not an engine concern.** Where a push/pull bridge
  is unavoidable (the mid-pipeline filter's event input; any external text→SAX element), it
  belongs to the pipeline layer, and the engine stays a thing that reads a stream and writes a
  stream. This is the "pipe IO for mid-pipeline usage" to be designed next, once the input
  contract here is settled and built.

The consequence for what is already built: design 22's `FilterRun`/`BoundedPipe` and design 21
phase 1's parse-and-forward `ShapeshifterReader` are now candidates to move out of the engine's
concern or be reframed as pipeline plumbing. That reframing is the output round; this document
does not do it.

---

## 4. Phasing — input first

**Phase 1 — the parser element streams its input — Done 2026-09-04.** *As built:* the engine's
full-window case is one rule — probe the stream; exhausted means the record simply ended where
the input did, otherwise FATAL, "the record is larger than source buffer_size", for every root
match and regardless of `ignore_errors`; the end-anchored special case and the trailing-anchor
import are gone. `ShapeshifterParser.getInputSource` passes a byte stream through with its
declared encoding rather than letting `AbstractParser` decode it; `ShapeshifterReader.parse`
streams whatever it is given — a byte stream as it is, a reader encoded to UTF-8 a chunk at a
time (`ReaderBytes`) — through `Shapeshifter.run`, the structured path straight into the event
sink and the text path into the held output the output round will address; the reader's
`runWhole` and whole-input read are deleted. *Pinned:* `EngineBehaviourTest` — a record larger
than the window is fatal, `ignore_errors` does not downgrade it, an input of exactly the window
is not truncation, an end-anchored match is no longer special; `StreamedInputTest` — five
thousand records through a 4 KB window produce exactly the whole-buffer events, a reader
produces the byte stream's events, a text configuration streams its input, a record larger
than the window reaches the pipeline as one fatal, and the element passes bytes through
undecoded. 68 of 68 fixtures unchanged, which is the corpus saying the window loop was always
DS3's.

*Audited 2026-09-04 against the contract's first property, memory is the window — three
defects, all in the trace that rides along with a streamed run, none in the engine.*
(1) `InputLocations.onOutput` returned early for the event sink without popping the open
match, so on the native path the open stack grew by one entry per match for the life of the
stream, and its top was the *last-started* match rather than the innermost running one — a
parent's events after a child closed were located at the child. Now every close pops,
whatever the currency; only the byte path keeps a span. (2) `LineIndex` kept a start for
every line of the input for ever: eight bytes a line, tens of gigabytes on a terabyte. It now
forgets everything before the outermost open match — or, with nothing open, before the match
that just closed — and counts what it forgot so line numbers stay right. The first cut of that
forgot up to the *read* position, which runs ahead of the matches, and located every record at
the last line read; caught by three existing pins before it was committed. (3) `ReaderBytes`
encoded each chunk on its own, so a surrogate pair straddling a chunk boundary became two
replacement characters; the high half is now held for the next chunk. Added, because the byte
pass-through made it possible for the first time: when a byte stream arrives with a declared
encoding and the configuration reads bytes by a different one, one warning names both, and the
configuration is used. Named and left: every full-window root match now probes the stream for
one more byte, and on a live source that pauses at exactly a window's edge the probe blocks
until the source speaks — which is the only way to know whether the record ended or the window
did, and is what DS3's read does. The byte path's held output and post-run resolution are the
output round's; on that path the line index is still whole, by the same deferral.
*Original wording follows.* `ShapeshifterReader.parse` stops reading
the `InputSource` whole and stops calling `runWhole`; it runs `Shapeshifter.run` over the
source's stream, windowed by `buffer_size`, DS3's contract, whether the pipeline hands it a
byte stream or a reader. The structured output path is unchanged — it already streams. The
text output path still holds its output whole: what becomes of it (a byte sink, a refusal
before a SAX consumer, an external bridge) is the output round's, not this phase's. The
engine's `runWhole` stays for tests and byte-holding callers; the reader's own copy goes. *Exit: a feed larger than any window
produces the same events as today, holding neither the input nor — for a structured
configuration — the output; a record larger than `buffer_size` is FATAL.*

**Phase 2 — the filter's input, already done (design 22), re-audited against this contract —
Done 2026-09-04.** *Confirmed:* the worker reads the engine's window from the pipe through the
same line index and the same `Executor.stream` as the parser element, so `buffer_size` rules
the window and the pipe's 64 KiB capacity is only back-pressure, not a second buffer; on the
structured path neither end holds the stream — the event queue is 1024 deep and the pipeline's
thread drains it between the input events it pushes and while it waits on a full pipe. The
probe for one more byte after a full-window match blocks on an *empty* pipe until the
pipeline's thread writes or closes, and that thread is never blocked at the same moment, since
what blocks it is a *full* pipe. *One defect:* the ruling did not reach the pipeline. A FATAL
ends the engine's run normally — the worker returned, and never told the pipe — while the
pipeline's thread still had most of the image to write; it filled the pipe and waited on it
in 20 ms turns for ever. The pipe now has a reader-side close: once the reader has gone,
whatever the writer still has is accepted and discarded, the writer reaches endDocument, and
the FATAL is what it hears there. The worker closes the reader on every exit. Pinned through
the pipe with a record larger than the window under a live DS3 upstream. *Parity, named and
not changed:* when nothing matches a full window with input still unread, the run ends —
an ERROR without `ignore_errors`, nothing at all with it — and the rest of the stream goes
unread. That is DS3's exact behaviour (`process` returns on no advance; the parse's tail check
is gated on `ignoreErrors`); a ruling could make it FATAL like the matched case, since on a
terabyte the silence costs the same. The text path still holds its output — the output round's.
*Original wording follows.* Confirm the filter streams the engine's input from the pipe under
the same window rules and the same truncation ruling, and that its structured path holds nothing.

**Phase 3 — the output round.** Separate design: the byte-sink target, the refusal of
text→SAX, and where the pipe plumbing lives. Opened once phase 1 lands.

---

## 5. Decisions — input (5.1, 5.2 ruled 2026-09-04; 5.3 resolved by the framework)

1. **A root match that fills the whole window while the input is not exhausted is FATAL**
   (ruled 2026-09-04). Not only an end-anchored one — *any* top-level match whose consumption
   reaches the window's end with input still unread has matched the buffer's edge, not the
   data's shape, and the record is larger than `buffer_size`. This replaces the engine's
   current split (FATAL for an end-anchored pattern, a WARNING otherwise): the warning is
   promoted to a fatal for every root match. The message keeps its shape — the record is too
   large; increase `buffer_size`. `ignore_errors` does not downgrade it: a skip can be ignored
   because there is something after it to move to, and here there is not — the next window
   would begin in the middle of the record. Nested matches are unaffected: they work within a
   region a parent already bounded, not against the raw window. DS3's grow-and-recover is *not* adopted;
   the fixed window with a hard limit is the contract (D33's kept limitation, now sharpened
   from "processing ends" to "the run fails").

2. **The parser element streams; it does not hold the input.** `runWhole` is a test and
   byte-holding path, never a pipeline element's. Ruled by the correction that opened this
   document.

3. **The parser takes whichever the pipeline hands it — raw bytes or a decoded reader — and
   that is the framework's own design, not a choice this document makes** (corrected 2026-09-04
   on the user's recollection). `AbstractParser` implements `TakesInput` *and* `TakesReader`,
   exactly as `XMLParser` does: with no reader element in front, the pipeline calls
   `setInputStream(stream, encoding)` and the parser has the raw feed; a reader element
   (BOM removal, a charset decode) makes it `setReader(reader)` and the parser has characters.
   So a byte engine is given bytes by default, and `source.encoding` is real — RAW, single-byte
   tables, binary formats and `transcode` all work — and the decoded-reader path exists for the
   configuration that declares UTF-8 with an upstream reader already in place, where the
   compiler's existing UTF-8-compatible refusal covers it.

   The one thing in the way is not the framework but a helper: `AbstractParser.getInputSource`
   wraps *even a raw byte stream* in an `InputStreamReader` and hands the subclass a
   `BufferedReader`, so `ShapeshifterReader` currently receives characters and re-encodes them.
   The fix is element-level and small: when the source carries a byte stream, pass it through to
   the engine with its declared encoding rather than decoding it first; when it carries a
   character stream, the decode already happened upstream and the engine's input is UTF-8. No
   ruling needed — this is `XMLParser`'s own shape, and phase 1 adopts it.
