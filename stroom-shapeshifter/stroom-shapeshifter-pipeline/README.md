# stroom-shapeshifter-pipeline

The adapter [D10](../design/00-decisions.md) named on 2026-08-17 and
[design 21](../design/21-sax-bridge-plan.md) phase 0 created on 2026-09-03: the one module that
knows both `stroom-pipeline`'s parser contract and the Shapeshifter engine. The engine stays
Stroom-free; this is where a configuration becomes a pipeline element.

`ShapeshifterParserFactory` compiles a project once and hands out `ShapeshifterParser`s, each
an `XMLReader` over the engine. `parse` is design 21's phase 1: run the configuration to a byte
buffer, parse the buffer with the same hardened factory every Stroom parser uses, forward the
events, and keep the engine's messages (about the input, no location) apart from the parser's
errors (about the output — reported in the output's terms, quoting the offending line, with no
input position invented for them). `Ds3EventIdentityTest` drives Stroom's own DS3 live beside it
over the legacy corpus and compares event streams; it is the module's oracle. Structured
emitters (design 20) arrive in phase 2 and give the same element a second, native path; the
parse-and-forward path stays, permanently, for every configuration whose output is not XML.

Phase 1b made it a Stroom service module: `ShapeshifterDoc` (in `stroom-core-shared`, so the
explorer and the client can see it) with its store, serialiser and REST resource;
`ShapeshifterParserFactoryPool`, compiled configurations keyed on the document; and the
`ShapeshifterParser` element, `DSParser`'s shape exactly. `ShapeshifterModule` binds the
services and `ShapeshifterPipelineElementModule` the element; `CoreModule`, `CliModule` and the
test `MockServiceModule` install both. `TestShapeshifterParser` in `stroom-app` runs a migrated
configuration through a real pipeline and round-trips the document through import/export.
Every event the reader forwards carries the input position behind it: `InputLocations` pairs
each match's input offset with its output span while the configuration runs, and resolves the
parser's position in the generated text back through them while it is parsed, so the
`Locator` the pipeline's filters see points at the record that produced the event (design 21
phase 4). `ShapeshifterFilter` (design 22) is the same configuration in the middle of a
pipeline: the events it receives are written as the byte image an indenting `XMLWriter` would
produce — the contract a configuration is written against — into a bounded pipe the engine
reads on a worker thread, and the output is forwarded on the pipeline's thread at
`endDocument` — or, for a structured configuration, delivered as the engine emits them, the
pipeline's thread draining the worker's queue between the events it pushes, so both ends
stream. A structured configuration takes that native path in the parser element too, with each
event located live from the running match. `preserveWhitespace` on the filter matches the
input as it came — every character, no indentation — for a document whose whitespace is its
own. What is not here yet: the client plugin and editor (design 18).
