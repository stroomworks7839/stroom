# Design 40 — XML, JSON and the binary formats: the engine against the parsers and libraries

*Proposed and built 2026-09-17, on the owner's question: "do we have tests for basic
comparison of XML parsing to SAX events with no transform, against a specific XML parser
library, with both byte-based inputs and strings?" We did not. Design 13's baseline has the
JDK's SAX parse of the records corpus as its floor, from bytes only, and the engine's rows
there run the events transform — never a bare parse.*

## 1. The comparison, stated fairly

One job: the records corpus (`RecordsGenerator`, the same document design 13 uses) parsed
into a handler that counts elements, attributes and characters, with nothing transformed.
Three parsers, each from bytes and from a `String`:

- **Xerces** — the JDK's SAX parser, namespace-aware.
- **Woodstox 7.1.0** — StAX, walked to the same counts. A second incumbent, independent of the
  first, so the engine is read against two implementations rather than one.
- **Shapeshifter** — a configuration that tokenises *this corpus's shape* into `element`,
  `attribute` and text ops through `SaxEventSink`: a `records` root with its default
  namespace and `version`, a `record` per line, `data` elements with `name` and `value`, the
  three entities decoded. No CDATA, comments, processing instructions or DTD. **It is a
  comparison for this document shape, not a claim that the engine is an XML parser.**

`XmlParseParityTest` holds all six rows to identical events — every element with its
namespace URI, every attribute with its decoded value, every run of text — with
whitespace-only text between elements set aside (the configuration writes structure, not the
indentation between it) and adjacent text coalesced (the parsers chunk it as they please).

**Strings.** The engine is byte-native. Its string row is a UTF-8 encode and then the byte
parse, with the encode inside the measured region — that is what handing it a string costs.
The incumbents read a `Reader` directly, and their string rows show what they save by not
decoding bytes.

## 2. The first reading — 2026-09-17, `2026-09-17-0809-5aba21c683-xmlparse.json`

Single-shot, whole file, two forks, three warm-ups and five measurements, on a quiet box by
day. Sizes: 2.9, 29 and 293 MB.

| records | Woodstox bytes | Woodstox string | Xerces bytes | Xerces string | Shapeshifter bytes | Shapeshifter string |
|---|---|---|---|---|---|---|
| 10,000 | 6.0 ms | 5.5 | 12.4 | 12.9 | 22.6 | 21.6 |
| 100,000 | 56.5 | 49.5 | 117.6 | 109.8 | 179.7 | 179.2 |
| 1,000,000 | 535 | 479 | 1,164 | 1,072 | 1,753 | 1,853 |
| MiB/s at 1M | **522** | 583 | 240 | 260 | **159** | 151 |

**Where the engine stands:** 1.5× slower than Xerces and 3.3× slower than Woodstox on this
job, at every size; the string row costs it nothing measurable (the encode of 293 MB is
inside the noise of a 1.8 s parse), where the incumbents gain 8–10% by skipping the decode.

**Where the time goes, and it is not the matching.** The configuration was first written
with `<record>(.*?)</record>` — a lazy run to a literal, which D55 established is the NFA
tier — and then rewritten so every template is a scan plan (a record is a line,
`[^\n]*\n`; the data elements are tier 0 inside it; consume templates eat the tags). The
time did not move: 199 ms and 200 ms at 100k. So the matching is not where the 3.3× lives.
`-prof gc` says where: **820 MB allocated per op for a 29 MB document — 31× the input,
about 1.1 KB per `data` element** — against Woodstox's 20 MB and Xerces' 6.5 MB. That is
the structure path: the `element` and `attribute` ops into `SaxEventSink` (an `Attributes`
object, a `String` per name and value, a QName per element), the three entity-decoding
`replace` transforms (a `String` each), the nested dispatch per record and per element.

## 3. What this establishes

- **A standing row.** `XmlParseBenchmark` runs with the module's `jmh` task beside the
  catalogue and the baseline; the parity test runs on every build. Any change to the sinks
  or the structure ops now has a number to move.
- **A census target.** Design 37 ran its allocation census on the value path and the match
  loop and never on the sinks — `element_storm` is the sinks row, and it read flat through
  every point because nothing touched them. This row says the sinks allocate a kilobyte per
  element on a job whose incumbents allocate tens of bytes. The next design on this number is
  design 37's method applied to `SaxEventSink`, `XmlByteSink` and the entity decode: a
  census, the cuts with expectations written first, this row as the reading.
- **The bound on the claim.** Design 36 says what the engine is for, and "a faster XML
  parser than Woodstox" was never it; a configuration that tokenises one shape will not beat
  a parser that reads all of them, and does not need to. What it needs is to not be 3× behind
  on the structure it emits, since every structured output — XML, SAX, JSON — goes through
  the same path.

## 4. Not done, on purpose

No engine change. The configuration is the shape a user would write, twice (the NFA form and
the scan form, the second kept); the benchmark measures what exists. The optimisation is a
design of its own, when it is wanted.

## 5. The same for JSON — 2026-09-17, `2026-09-17-0833-5aba21c683-jsonparse.json`

*On the owner's follow-up: "it might be worth doing the same for JSON parsing."* The same
records as JSON lines (`JsonRecordsGenerator`: one object per line, the same fields and
values, numbers as numbers, the message carrying `\"` and `\n`), parsed to a token stream
with nothing transformed. The incumbent is **Jackson 3.1.2's streaming parser**, from bytes
and from a string, every value materialised with `getString()` — the length alone would let
it skip making the string, and the XML incumbents hand their handler real strings, as the
engine does. The engine runs a configuration that tokenises *this shape* — a record per line,
a string field and a bare field as two scan-plan templates rather than one NFA alternation,
the two escapes decoded by a replace chain — into `record` and `field` events.
`JsonParseParityTest` holds all four rows to the same records, names and values.

| records | Jackson bytes | Jackson string | Shapeshifter bytes | Shapeshifter string |
|---|---|---|---|---|
| 10,000 (1.4 MB) | 2.1 ms | 3.2 | 27.6 | 27.7 |
| 100,000 (14.6 MB) | 17.6 | 24.2 | 242.8 | 234.3 |
| 1,000,000 (148 MB) | 169 | 229 | 2,388 | 2,381 |
| MiB/s at 1M | **836** | 615 | **59** | 59 |

**Where the engine stands: 14× slower than Jackson from bytes**, 10× from a string. Not a
different story from XML — the same per-element cost, about 330 ns per field event with
~1 KB allocated (696 MB per op at 100k, against Jackson's 34 MB with every value
materialised) — but Jackson is four times faster than Xerces at the same job, so the same
engine cost reads as 14× where XML read 1.5×. The string rows say the same thing as before:
the engine's encode is invisible, Jackson pays about a third for reading chars.

**What it settles.** Two incumbents in two formats, and the same number: the engine spends
roughly 300 ns and a kilobyte per structural event it emits, and that is the whole of the
gap on both. §3's census target stands, with a second row to read it on; nothing about the
matching is in question. The bound on the claim stands too — one shape, not a JSON parser —
and so does the point of design 36: a parser that reads all JSON was never the job; not
being an order of magnitude behind one on the structure we emit is.

## 6. The binary formats, against the Java libraries that own them — 2026-09-17

*On the owner's challenge to the Avro and protobuf statements: the Avro number was a fresh
measurement but against the Rust crate, which is not the library a Java system would use;
the protobuf statement had no measurement behind it at all. Both are corrected here.*
`BinaryParseBenchmark` (engine bench package, run by name; `2026-09-17-1052-6345f8f723-binaryparse.json`): the same
container and the same message stream, about 263 KB each, through the Java libraries reading
generically — `GenericDatumReader`, `DynamicMessage` from the fixture's own descriptor — and
through the engine with its output taken out (every match, read, capture and dispatch kept)
and as written. `ProtobufMessages` writes the corpus from the descriptor and is the oracle,
as `AvroContainers` is; `ProtobufAmplifiedTest` holds the engine to it, the proto3
omitted-false case included.

| ~263 KB | Java library, generic, nothing written | engine, parse only | engine, with output |
|---|---|---|---|
| Avro, 12,762 users | 991 ops/s (1.01 ms) | **1,668 ops/s (0.60 ms)** | 424 |
| Protobuf, ~20,000 events | 175 ops/s (5.7 ms) | **154 ops/s (6.5 ms)** | 133 |

**Avro holds, against the right library:** the engine's parse is 1.7× the Java library's
generic reader (and 2.4× the Rust crate's, the earlier reading). Both libraries read
generically; a schema-specific or generated-class reader would be faster and is unmeasured.

**Protobuf does not:** the engine is 12% *slower* than `DynamicMessage`, which is the
library's slowest, reflective route, and a generated message class would be several times
faster than that. The configuration says why: each field is a template whose first part is a
one-byte tag as a *pattern* — a regex call to match a single byte — before its read. So the
honest standing is "correct, and on a par with the slowest library route", not "no library
needed". Two remedies, both design 42's: a one-byte literal in a match sequence should not
cost a regex call (a `tag` verb, or `read` of a byte with a test — an engine change, small);
or the native seam, for the formats where a library is the only route (Parquet) or clearly
the better one.
