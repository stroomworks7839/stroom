# SAX bridge plan — the phases design 20's rulings owe

Design 20 ruled the shape: bytes stay the engine's native output, a pipeline element bridges
to SAX by parsing them, and structured emitters arrive second — `element` and `attribute` as
containers, `namespace` as a leaf, `text`/`value-of` untouched with `OutputSink` interpreting
`write` by the container it is in. What it did not do is say what gets built in what order,
what each step is measured against, and where the plan is most likely to be wrong. This is
that. Statuses move here; design 20 does not change again unless a phase overturns a ruling,
in which case the ruling is re-put, not quietly bent.

**What exists, so the plan does not re-plan it.** `OutputSink` (`write`, `position()`, one
stream implementation whose javadoc has been waiting for this). `Instrument.onOutput` with byte
spans, consumed by the editor's output pane (design 18 §5.4). `Ds3Migration` assembling
`<records>`, `<record>` and `<data>` as `Text` and fused `RefExpression`s, with `escapeAttribute`
for literals only. Nineteen legacy fixtures whose goldens are **Java Stroom's own DS3 output**,
eighteen native ones over the same goldens, and a fixture ledger that ratchets. In
`stroom-pipeline`: `xml.converter.AbstractParser implements XMLReader`, `DS3ParserFactory`,
`DSChooser`, and `TestDS3` with a `LoggingContentHandler` that records events — the prior art
for every test in phases 1 and 3. Not existing: `stroom-shapeshifter-pipeline` (named by D10 on
2026-08-17, never created); any dependency from `stroom-pipeline` on the engine.

The method is the repository's: one phase at a time; every phase ends with the full fixture
suite green and the ledger moved on purpose; an audit is appended to the phase when it lands,
findings included; deviations from the phase as written are recorded, not silent.

---

## Phase 0 — Make the gap honest *(small; no engine change)* — **Done 2026-09-03**

**Audited as it landed, three findings, one of them not the one the phase was written for.**
(1) *The draft's defect was not one.* Checked first, before any file was written: every
captured value goes through `escapeCaptures`; goldens 002 and 007 had pinned it all along.
Design 20 §1 corrected in place, D40 records the correction. (2) *The `&#xD;` probe found
E33 instead.* The escaping fixture put a `\r` before a line ending to reach the one entity no
golden pins, and Stroom's DS3 returned `value="cr"`: `DS3Parser.normaliseBuffer` trims every
name and value to U+0020 at both ends and drops an element left empty, and the migration does
neither. Not a SAX matter — it belongs to the migration or the capture path — so the fixture
split: `legacy/020_escaped_values` pins the five reachable escapes in a referenced name *and*
value and is `PASS`; `legacy/021_trimmed_values` (trailing CR, padding both sides, a tab, a
whitespace-only value that DS3 renders as `<data name="blank"/>`) holds DS3's golden as
`PENDING` under E33, so the build breaks the day the engine agrees with it. Both goldens
produced through `TestDS3`'s harness and the staging removed from `stroom-pipeline`; 63 fixtures
green. (3) *The module builds.* `stroom-shapeshifter-pipeline` with
`implementation project(':stroom-pipeline')` and the engine compiles against `xml.converter.AbstractParser`
and `ParserFactory` with no module-system or Guice obstacle; `ShapeshifterParserFactory`
compiles a project once and `ShapeshifterParser.parse` refuses by name, two tests pinning both.
How the factory is *found* — document type, chooser, binding — is phase 1's and was not
pretended here. **Audit of the audit, same day, two more findings.** (4) The census claimed
the empty root is "still paired, never self-closed" with no golden behind it; a fixture with
an empty input (`022_empty_input`) went through Stroom's DS3 and came back self-closed — E34,
`PENDING`, and free under phase 3's containers. Rule for the census from here: every line
names the golden that pins it or says *unpinned*. (5) `escapeCaptures` was checked for
coverage, not just existence: `LegacyRefs.parse` yields only `Text` and `Capture`, so every
reference kind the migration can produce is escaped. Also caught: design 20 §7 and §9 named
`win_sec` and `ausearch` as the byte-pinned fixtures, but those are hand-written `projects/`
text configurations phase 3 never touches — the pinned families are `legacy/` and `native/`,
and the wording is corrected. 64 fixtures in the ledger, 2 pending (021, 022), 0 failures. The serialisation census is Appendix A; its one surprise is that
`escapeAttribute`'s `&quot;` for literal quotes is pinned by nothing, so the serialiser has one
form, `&#34;`, Stroom's. Original wording follows.


Three things are known to be true and none is pinned.

**The hazard that was not one, and the fixture that is still worth having.** Design 20 §1 as
first drafted said `Ds3Migration.dataReference` splices reference values into attributes
unescaped. Phase 0's first act was to check, and it is false: `emitDataTag` routes every
captured value through `escapeCaptures`, which generates a `translate` per reference over
six substitutions, and goldens 002 and 007 pin the result against Stroom's own output
(`&#34;`, `&lt;`, `&amp;`, `&#xA;`). Design 20 is corrected in place. What the goldens do
*not* reach: `\r` → `&#xD;`, `>` in a value, and any special character in a data *name*
taken from a reference (`name="$heading$1"`, which 001, 003, 009 and 019 use with clean
headings). Phase 0 writes `legacy/020_escaped_values` to reach them — a DS3 XML config and an
input carrying all six characters in both a referenced name and a referenced value — with a
golden produced the way every legacy golden was, by Java Stroom's DS3 through `TestDS3`'s
harness. It enters the ledger as `PASS`: the migration already does this, and the fixture's
job is to be the pin phase 3's byte-identity gate is measured against where 002 and 007 stop.

**The dependency is checked, not assumed.** D10 said `stroom-shapeshifter-pipeline`; nobody has
tried to build it. Phase 0 creates the module skeleton with `implementation project(':stroom-pipeline')`
and `project(':stroom-shapeshifter:stroom-shapeshifter-engine')`, one class that extends
`xml.converter.AbstractParser`, and a test that instantiates it. What this is looking for:
whether the engine's Java level and module conventions meet `stroom-pipeline`'s, whether
`ParserFactory`/`DSChooser` registration needs Guice wiring the module cannot provide from
outside `stroom-pipeline`, and whether the answer is a module or a package. Either answer is
fine; an unchecked one is not.

**The serialisation census.** S2 makes byte-identity phase 2's exit. Before phase 2 designs a
serialiser, phase 0 lists exactly which serialisation choices the goldens pin, from the code
and the nineteen goldens rather than from memory: entity forms (`escapeAttribute` writes
`&quot;`, `&amp;`, `&lt;`, `&gt;`; design 20 said `&#34;` — the census settles which, and the
draft was wrong if it is `&quot;`), self-closing versus paired `<data>`, the `\n` + three-space
indentation carried in `Text` nodes, attribute order (`name` before `value`, always), the
`<records>` header's two namespace declarations and their order. The list goes in this
document as an appendix and phase 2's byte serialiser is written to it.

**Exit:** design 20's escaping claim is corrected and the corner it left unpinned has a
Stroom-produced golden; the pipeline module
either builds or the reason it cannot is written down; the serialisation choices phase 2 must
reproduce are enumerated.

---

## Phase 1 — Parse and forward *(the pipeline element exists)* — **Done 2026-09-03**

**As built.** `ShapeshifterParser.parse` runs `Shapeshifter.runWhole` to a buffer, parses it with
`SAXParserFactoryFactory`'s product, and forwards to the content handler; the engine's messages
go to the error handler with their severity through `ErrorHandlerAdaptor` (or as
`SAXParseException`s to any other handler); the parser's errors are rewritten in the output's
terms — "at line N of the output, not the input", the offending line quoted — and handed on with
no location, since the only one available would be read as an input position; a fatal parse
error is recorded and the parse returns, as DS3 does, rather than throwing into the element's
second `fatal`. Input arriving as characters is re-encoded UTF-8. `Ds3EventIdentityTest` builds
Stroom's DS3 live from each legacy config (`RootFactory` + `ConfigFilter`, no schema filter) and
compares event streams; `ShapeshifterParserErrorsTest` pins the two error kinds and the not-XML
case. 26 tests in the module; the engine's 26 classes unchanged.

**Audited as it landed — six findings, two of them not about SAX at all.**
(1) *The plan's premise on locations was wrong:* engine `Message` is `(severity, text)`; it
carries no location. The messages go on unlocated, which is honest, and the promise of source
positions is D10's still-open locator work, not something this phase inherited. (2) *The
migration could not run inside Stroom.* `Ds3Parser` (engine) set the JAXP 1.5 external-access
limits on whatever `DocumentBuilderFactory` JAXP found; on the pipeline's classpath that is
Xerces, which rejects them by name. Every legacy fixture failed in the first run. Fixed in the
engine: the limits are tried and an unrecognised one ignored, which is safe because DOCTYPEs are
already refused and validation is off. (3) *Three normalisations, each a fact about DS3 rather
than a convenience:* DS3 emits no whitespace where the migration writes the serialiser's
indentation; DS3 puts its two `xmlns` declarations in the root's `Attributes` as well as in
`startPrefixMapping`, where a namespace-aware parser reports them only as mappings; DS3 never
calls `endPrefixMapping`. `EventRecorder` names all three. (4) *The vendored goldens are not
Stroom's bytes.* Stroom's serialiser wraps long attributes onto their own line (003, 007, 009,
019 all show it); every vendored golden is unwrapped. Event-identical, so the port is unharmed,
but Appendix A's "attribute on the same line" is the prototype's rule, and S2's byte-identity is to
The prototype's re-serialisation, not to Stroom's serialiser — stated in the appendix now.
(5) *E33 reaches into the vendored goldens.* Live DS3 trims 007's message values and 009's
`User `/`Query ` names; stroom-pipeline's own goldens have the trimmed forms; the vendored ones
do not. The port matches the prototype, not Stroom, on those two, and the event test holds them as
must-differ until E33 is ruled — E33 is updated with the provenance. 20 of 22 legacy fixtures
are event-identical to live DS3 (008 rejected by both, as the ledger says). (6) A small one:
`LoggingErrorReceiver.getTotal` calls `checkRecord(-1)`, which clears the summary
`getMessage()` reads, so a test that asks for the count first sees no message; the tests read
the indicators instead. Not built, deliberately: the pipeline element class, document type,
chooser and Guice binding — the exit criterion was the oracle, and the element's registration is
Stroom integration that deserves its own phase-1b with a stepping test. Original wording follows.

`stroom-shapeshifter-pipeline` gains `ShapeshifterParser`: an `XMLReader` over
`Shapeshifter.runWhole`. The engine writes to a byte buffer; the buffer is parsed by the same
`SAXParserFactoryFactory` product `DS3ParserFactory` uses; events go to the pipeline's
`ContentHandler`. Whole-buffer rather than piped is not laziness: D37 ruled complete inputs
only, the output of a complete input is a complete document, and a pipe would add a thread to
carry a stream the engine never produces. If a feed is large enough for that to matter, the
answer is the pipeline's existing splitting, not a streaming bridge.

**Errors, two kinds, kept apart.** Engine `Message`s carry input locations already; they map
onto `ErrorReceiver` with the source location, severity preserved, exactly as the engine's
`.messages` goldens record them. Parser errors — the output was not well-formed — are a
different thing and are reported as such: the location is a line and column in *generated
text the user never sees*, so the message must say so and carry the offending output line,
because design 20 §4A promised that A's errors land far from their cause and the least the
element can do is say where they landed. A configuration factory (`ShapeshifterParserFactory`,
the document type from D10) compiles once and caches, as `DS3ParserFactory` does.

**Tests, all in the pipeline module, all in `TestDS3`'s style** — direct `XMLReader` plus a
recording `ContentHandler`, no pipeline runtime:

- **Event identity against DS3, the phase's oracle.** For each legacy fixture: run
  `DS3Parser` on the DS3 XML config and its input, record events; migrate the config, run
  `ShapeshifterParser`, record events; compare. Expect a finding here rather than a pass: DS3
  emits elements and attributes and no whitespace, while the migrated configuration writes
  the indentation DS3's *serialiser* wrote, so the shapeshifter stream will carry
  whitespace-only `characters()` events the DS3 stream does not. The test normalises exactly
  that — whitespace-only character events dropped — and **records the normalisation as a
  finding**, because it is the first evidence of what phase 3's structured migration should
  and should not emit. Any other difference is a defect.
- **Errors.** An engine-level error (a fixture with `.messages` content) reaches
  `ErrorReceiver` with the same severity and text the golden records. A configuration writing
  unbalanced text reaches `ErrorReceiver` as a parse error that names the output line.
- **Not XML.** `projects/xml_to_json` through the element: the parse fails, the error says the
  output is not XML, and nothing is forwarded. This is S6 and S7 together — the deployment
  chose a sink the configuration cannot serve, and the element says so.
- **The engine suite unchanged**, trivially, since the engine did not change.

**Exit:** a shapeshifter parser element produces, for every legacy fixture, the events DS3
produces, modulo the whitespace finding written down. E31 moves to `in progress`; E15 closes.

---

## Phase 1b — The element and its document *(Stroom integration; added 2026-09-03)* — **Done 2026-09-03**

**As built.** `ShapeshifterDoc` in `stroom-core-shared` (`stroom.shapeshifter.shared`, beside
`ShapeshifterResource`), an embeddable document whose `data` is the project JSON; registered as
`DocumentTypeRegistry.SHAPESHIFTER_DOCUMENT_TYPE` in the transformation group, and
`PipelineElementType.TYPE_SHAPESHIFTER_PARSER`. In `stroom-shapeshifter-pipeline`: the store
(`ShapeshifterStore`/`Impl` on `AbstractDocumentStore`), the serialiser (the JSON travels as its
own `json` asset, as a TextConverter's XML does), the REST resource, a
`ShapeshifterParserFactoryPool` on `AbstractDocPool` keyed on the document and evicted by its
entity events, and the element — `ShapeshifterParser`, `DSParser`'s shape exactly: document
loaded fresh per stream, factory borrowed from the pool, stepping's injected code bypassing it,
name-pattern lookup through `PipelineDocFinder`. Two Guice modules, `ShapeshifterModule` (store,
resource, pool) and `ShapeshifterPipelineElementModule` (the element), installed in `CoreModule`,
`CliModule` and the test `MockServiceModule`. The phase-1 `XMLReader` is renamed
`ShapeshifterReader` so the element can carry the name the pipeline shows.

**Tests.** `TestShapeshifterParser` in `stroom-app`'s integration harness: a migrated
`001_csv_with_header` written into a document, a pipeline of `ShapeshifterParser →
RecordCountFilter → XMLWriter → FileAppender`, six records read, every `<data>` element
Stroom's golden holds present in the output in order and nothing else; and the document
exported and imported, the asset's bytes equal to the JSON and the document equal to itself.
Both passed first time; the pipeline module's 26 tests and `stroom-core-shared`'s suite
unchanged.

**Audited as it landed — three findings, none a defect.** (1) The module needed nine
dependencies the phase-0 skeleton did not: being a Stroom *service* module (injection, Guice,
logging, the REST annotations, Saxon because `ProcessException` is an `UncheckedXPathException`,
and the explorer, docstore, import/export, cache and security APIs). Phase 0's "the module
builds" was true of a parser, not of a service; the README says which it is now. (2) The pool
reuses `ParserConfig`'s cache configuration rather than adding a Shapeshifter one — one knob
for all parser pools, and a second would be a decision nobody has asked for. (3) The document
borrows the TextConverter icon. **Not built, and named:** the client — the GWT plugin,
descriptor and editor are design 18's, and until then the document exists in the explorer
without a UI to open it; and input locations. The stepping test the phase as written asked for
— "the element reports its input location for a matched record" — is not written, because the
reader's locator is the output parser's and the messages are unlocated (phase 1's finding 1):
mapping output positions back to input positions needs the trace's spans, and that is phase 4's
attribution work wearing a different hat. Recorded as the phase's one open item, to be closed
with phase 4 or before it if stepping is wanted sooner.

**Audit of the audit, 2026-09-04 — five checks against the tree, two findings.** (4) *Checkstyle
had never been run over any of this.* `test` does not run it; `check` does. Two violations were
mine — `TreeSet` declared as a type where the rules want `SortedSet`, and a test method name
whose second character was a capital — both fixed; six are pre-existing in the engine's
`EncodedInputTest` (escaped Unicode literals, the encoding plan's) and are left for that
work's owner, named here so they are not mistaken for this phase's. (5) *The serialiser's
empty-document path is safe*: a never-edited document exports with no `json` asset,
`getExtAssetData` returns null for it, `EncodingUtil.asString(null)` is null, and the import
is a document with no data — the same behaviour as a TextConverter's. Checked, not assumed.
Verified clean: `TestRestResources` scans every bound resource, the new one included, 220
checks green; the commit's twenty-seven files are exactly the phase's, no build output or
staging swept in; GWT is untouched by design, since a feature's `shared` package enters the
client build only through that feature's own descriptor. **Not verifiable here:** the
production injector. `TestAppModule` and `TestBootStrapModule` build it against a database
and fail on the connection before reaching any binding; the mock injector installs the same
two modules and builds. The first run of a real node is where a wrong binding would show, and
it is named so nobody reads the green suite as having covered it. Original wording follows.

What phase 1 built is an `XMLReader`; what a pipeline needs is an element that can be placed
in it and a document that holds the configuration. Prior art is `DSParser` and
`TextConverterDoc`: a `@ConfigurableElement` in the `PARSER` category with `ROLE_PARSER`,
`ROLE_HAS_TARGETS` and the stepping visibility, whose `createReader()` loads the document,
compiles it through `ShapeshifterParserFactory` (cached in a pool keyed on the document
version, as `ParserFactoryPool` does for DS3) and returns the parser; a `ShapeshifterDoc`
document type with the project JSON as its body, a store, import/export and the explorer
registration D10 promised; and the Guice module that binds all of it, in `stroom-shapeshifter-pipeline`
if phase 0's build check holds for the runtime too, and in `stroom-pipeline` if the element
registry cannot see across modules.

**Tests.** A pipeline test in `stroom-pipeline`'s style running a migrated legacy configuration
end to end through the element into a recording filter, asserting the same events phase 1's
oracle asserts; a stepping test that the element reports its input location for a matched
record, which is D10's locator work and is where the unlocated messages of phase 1 stop being
acceptable; import/export of the document round-trips the JSON byte for byte.

**Exit:** a feed processed by a Shapeshifter parser element in a real pipeline produces the
events the DS3 element produces for the same feed, and the configuration lives in a document
a user can open.

## Phase 2 — The sink is a state machine *(the ruled shape; the measured risk is S2)*

### 2a — The spike that decides whether 2b is as planned — **Done 2026-09-04: 2b is as planned**

**As built.** `XmlByteSink` in the engine, an `OutputSink` with the structural calls
(`startElement`, `namespace`, `startAttribute`/`endAttribute`, `endElement`) and `write`
meaning what design 20 §4B says by container. Saxon's forms throughout, and Saxon's wrapping
rule **read from `XMLIndenter.startContent`'s bytecode** rather than inferred: sum `9 + uri`
per default namespace declaration, `prefix + 10 + uri` per prefixed one, `name + value + 8`
per attribute (`+ 9` with a prefix, raw lengths); over 80, every attribute after the first goes
on its own line aligned under the first. Appendix A is corrected to it. The ordering rule the
compiler cannot see — a namespace or attribute after content — is refused here by name.

**The spike was made as strong as it could be.** The plan said one fixture by hand. Phase 1's
`Ds3Oracle` drives Stroom's DS3 live, and Stroom's goldens *are* Saxon's serialisation of DS3's
events, so `XmlByteSinkDs3GoldenTest` feeds those events through the sink for every legacy
fixture and compares bytes with the golden: **22 of 22 identical on the first run**, including
003, 007, 009, 019, 021 and 022 — the ones the *engine* cannot yet reproduce, which this test
does not involve. That is S2 answered for the serialiser: the bytes are reachable. What
remains pending is the migration's side (E33's trim, phase 3) and the migration moving onto
the sink at all (phase 3), not the serialiser. `XmlByteSinkTest` pins the contexts, the
entities, the wrap threshold at exactly 80 and 81 (the goldens leave 81–92 unseen), the
namespace alignment, an attribute value split mid-character across writes, and the refusals.

**Two choices made here and named for 2b.** Whitespace-only content inside an element is
dropped — indentation is the indenter's, and phase 3's migration stops writing it as text.
And the XML declaration is the caller's to write as document-level text, which is what the
DS3-event adapter does and what the migration's header will be.

**Audited 2026-09-04, against uses the goldens do not reach — one defect, three notes.**
(1) *Content split across writes corrupted a multi-byte character.* Attribute values were
buffered as bytes and decoded once; content was decoded per write, so an `é` arriving as two
writes became two replacement characters. The executor writes whole values today, but
`OutputSink.write(byte[], off, len)` promises bytes, not characters. Fixed: a trailing
incomplete UTF-8 sequence is carried to the next write or flushed at the structural call
that ends the content; pinned for a two- and a three-byte character. (2) *A whitespace-only
element self-closes* (`<a/>`) where Saxon writes `<a>   </a>` — a consequence of "whitespace
is dropped" the choice above did not name. Unpinned, and left as it is: no configuration in
the corpus writes one, and the rule stays simple. (3) *Two top-level elements* each get the
end-of-document newline. Unpinned, and left. (4) *For phase 4:* under a deferred start tag, a
child's byte span absorbs its parent's start tag — the bytes are attributed to whoever
triggered the flush. Not a defect, a fact the attribution design must know. Original wording
follows.

Before the model changes, a byte serialiser is written to the phase-0 census — and, since D41,
to Saxon's indenter (E35): one attribute line until the 80th column, then each attribute after
the first on its own line aligned under the first — and driven **by
hand** — no new instructions, a test that calls `startElement`/`startAttribute`/`write`/
`endAttribute`/`endElement` in the sequence phase 3 will generate — for one legacy fixture,
`legacy/001_csv_with_header`, and compared byte for byte with its golden. If it matches, 2b
proceeds. If it cannot match without contortion — an entity form that depends on which
instruction wrote it, whitespace that is not content — the spike reports what it could not
reproduce and S2 is re-put to the user with evidence, before anything downstream is built on
the assumption.

### 2b — `OutputSink` widens; `write` gains its context — **Done 2026-09-04**

**As built.** `OutputSink` carries the structural calls with defaults that refuse by name, so
a byte counter or a benchmark sink still compiles; `OutputSink.of(stream)` is `XmlByteSink`,
which is byte-transparent for a configuration that opens nothing — the whole existing corpus
proves it. `SaxEventSink` is the second implementation: declarations become
`startPrefixMapping`, attribute bytes accumulate into `Attributes`, the deferred start fires
at first content, content is `characters()`, the root brackets the document, an unbound
prefix is refused when its element is emitted, and `position()` counts events. Both sinks
resolve prefixes through a per-element scope, so an element whose namespace is given and
whose prefix is not already bound to it declares the binding itself (S3's element-declared
form) and `namespace` is the explicit one. In the model: `Element(name, namespace, body)`,
`Attribute(name, body)`, `Namespace(prefix, uri)`; in the codec `element`, `attribute`,
`namespace`; in the compiler a `Structure` pass — an attribute or namespace after content in
the same element body, or any structure inside an attribute's value, is a `ConfigException`
naming the template, the instruction and the element, while an attribute at a template's top
level is allowed because it may be running inside a caller's element; in the executor three
arms that bracket their bodies with the sink's calls and turn a `StructureException` into the
run's fatal, named for the instruction.

**One thing the phase found that the design had not:** the document template's body is split
at its `apply-templates` — prologue once, the loop over the input, epilogue once — and the
split looked only at the top level, so `element records { apply-templates }`, the exact
shape phase 3 needs, dispatched nothing (and so did an apply inside an `if` or a `variable`,
which is pre-existing and stays: a conditional root loop is not a thing). `RootSplit` now
descends through enclosing elements, opening each after its prologue and closing each after
its tail, which the sink's deferred start tag makes indistinguishable from the body having run
in one piece.

**Tests.** `XmlByteSinkTest` (10), `SaxEventSinkTest` (6), `StructureTest` (5: the three
compile refusals, the run-time refusal across two child templates the compiler cannot see, and
a structured body serialised as Stroom would with a variable as its own document), the
`EveryVariantTest` round-trip through the codec, and the hand-written fixture
`projects/event_logging_structured` — `event-logging:3` with a default and a prefixed
namespace, a conditional attribute, an attribute value carrying `&` and `<`, an attribute list
long enough to wrap, text content, an eater — with its byte golden; and in the pipeline module
`StructuredEventsTest`, the invariant that the native event sink and a parser over the byte
sink's output agree event for event and message for message. Whole corpus unchanged: 65 in
the ledger, 10 pending, 0 failures.

**Two choices named.** The event sink drops an XML declaration written as document-level text,
since a configuration that writes one for the file target is not wrong to run here and the
declaration has no event. And a variable's body is its own document: structure inside a
variable serialises into the variable as bytes, which is P1's problem and phase 3's.

**Audited 2026-09-04 — one defect, three checks.** (1) *A misplaced write escaped the run.*
`structure()` turned a refused structural call into the run's fatal, but the event sink also
refuses a *write* — text at document level — and that path runs through the `text` and
`value-of` arms, so a text-only configuration on `SaxEventSink` came out of `Shapeshifter.run`
as an exception rather than a message. The run's top now treats a `StructureException` as it
treats a failed read: the last message, fatal. Pinned. (2) `RootSplit`'s three shapes read
through by hand: no apply (all prologue, empty tail), apply at the top (before/after, nothing
opened), apply under one or more elements (each level's prologue then its element, each tail
then its close, innermost first) — and the fixture exercises the third. (3) The stroom-app
integration test, which runs the parse-and-forward reader over `OutputSink.of` — now
`XmlByteSink` — re-run and green: the byte sink's transparency holds through a real pipeline,
not only the corpus. Carried to phase 4 unchanged: under the deferred start tag a child's byte
span absorbs its parent's tag. Original wording follows.

The interface gains `startElement(name, uri)`, `endElement()`, `startAttribute(name, uri)`,
`endAttribute()`, `namespace(prefix, uri)`. `write` keeps its three overloads and every
caller; `position()` keeps its contract (see phase 4 for what it counts on the event sink).

**The byte sink** tracks the innermost open container. At document level `write` is raw, as
today — a configuration that never opens a container is byte-transparent, which the whole
existing suite proves. Inside an element it is content: `&`, `<`, `>` escaped per the census.
Inside an attribute it is the value: `&`, `<`, `"` escaped per the census. `startElement`
defers writing `<name` until the first `namespace`, `attribute`, content or child element,
so that attributes can be accumulated; `endElement` with nothing written since the start
emits the self-closing form the census pins, else the paired close.

**The event sink** does the same bookkeeping against a `ContentHandler`: `namespace` →
`startPrefixMapping` (and `endPrefixMapping` at the element's close); attribute bytes
accumulate into an `AttributesImpl` decoded as UTF-8; the deferred `startElement` fires on
first content or child; content → `characters()`; document-level bytes are dropped if
whitespace and otherwise an error, per design 20 §4B, reported through the executor against
the instruction that wrote them.

**The model.** `OutputNode.Element(String name, String namespace, List<OutputNode> body)`,
`OutputNode.Attribute(String name, String namespace, List<OutputNode> body)`,
`OutputNode.Namespace(String prefix, String uri)`; JSON spellings `element`, `attribute`,
`namespace`, read and written by `ProjectJson` beside `if` and `variable`. `Compiler.bodyChecks`
gains the ordering rule: within an element body, `attribute` and `namespace` may not follow a
`text`, `value-of`, `element` or an instruction that can produce content (`apply-templates`,
`call-template`, `value-of` of a variable) — a `ConfigException` naming the instruction. The
executor gains three `CompiledOp`s that bracket their body with the sink calls; nothing else in
`Executor` changes, which is the point of putting the fact in the sink.

**Tests.**

- *Byte sink, by context*: `OutputSinkTest` — raw at document level (bytes through untouched,
  including `<` and `&`); content escaping; attribute escaping with the census's entity forms
  pinned by name; deferred start and self-closing on empty; nested elements; a `namespace`
  producing `xmlns=` and `xmlns:p=` in declaration order.
- *Event sink, by context*: the same sequences against a recording `ContentHandler` —
  `startPrefixMapping` scope, `Attributes` content and order, `characters()` for content, the
  document-level rule (whitespace dropped, other bytes an error).
- *Both sinks, one sequence*: parse the byte sink's output and assert it yields the event
  sink's events. This is the invariant that makes S7's parse-and-forward and the native path
  interchangeable for a structured configuration, and it stays as a property test.
- *Compiler*: attribute after content refused, with the message naming the instruction;
  attribute after `apply-templates` refused; namespace after attribute allowed (the census
  says where `xmlns` sits relative to attributes and the test pins it).
- *Executor, runtime ordering*: a template whose element body calls `apply-templates` before an
  `attribute` and whose child writes content — the compiler cannot see it, the sink refuses it,
  and the error names the attribute instruction.
- *A hand-written structured configuration*: `projects/event_logging_structured`, emitting
  `event-logging:3` with a default namespace, a prefixed one, attributes built from captures
  (one carrying `&`), nested elements and conditionals inside a body. Two goldens: the byte
  sink's output, and an event-stream golden in the pipeline module. The same input through
  `projects/apache_httpd`'s text configuration is *not* expected to match it — that fixture
  keeps its 244 `translate` steps and its own golden, and stays as the proof that text
  configurations are untouched.
- *The whole existing suite, unchanged*: byte-transparency of the byte sink for every
  configuration that opens no container.

**Exit:** the hand-written configuration is byte-identical on one sink and event-identical on
the other; the compiler and the sink each refuse what design 20 §4B says they refuse; the
existing corpus does not move by a byte. The instructions exist for authors; the migration
has not touched them.

---

## Phase 3 — The migration moves onto them *(S2's gate, in full)* — **Done 2026-09-04**

**Ruled at the start (P1, P2):** `omit-if-empty` on both `element` and `attribute` — one word,
two places, general rather than DS3's. P2's draft had it backwards: DS3 always writes the
`<data>` element and drops each *attribute* whose trimmed value is empty (`DataAttributes`,
`normaliseBuffer`); "the whole tag or nothing" was the prototype's. Trim needs no property —
the existing `trim` transform writes the trimmed value into the attribute.

**As built.** `Element(name, namespace, omitIfEmpty, body)`, `Attribute(name, omitIfEmpty, body)`;
in both sinks a child no longer starts its parent when it opens but when it first *emits*, so
an omitted child leaves its parent as empty as it found it — which is what lets the root
self-close on an empty run (E34) while the record it never started leaves no trace (P1).
`Ds3Migration` writes the declaration as text and everything else as structure: the root is
`element records` with DS3's two declarations and two attributes; a record is
`element record { omit-if-empty }` around the group's body; a `<data>` is `element data` with
`attribute name { omit-if-empty; trim(…) }` and the same for `value`, children nested inside.
`escapeAttribute`, `escapeCaptures`, the buffered record body, the buffered children and the
indentation arithmetic are gone; the migration no longer knows what XML looks like.

**The gate.** The engine suite went green on the rewritten migration's first run: every
legacy fixture byte-identical to Stroom's, 003/007/009/019/021/022 promoted — E33, E34 and E35
closed on this path — and nothing else moved. The four natives that were Stroom-pending are
regenerated from the migration, which is the provenance they always had (their bodies
carried its `__esc_` and `__record_body__` names), and their text-output forms are kept as
`projects/text_003…019` under their own goldens — the text path's serialisation, pinned as
what a text configuration produces, per the ruling that both output styles keep fixtures.
`Ds3EventIdentityTest`'s two side-ledgers empty: every legacy fixture is event-identical to
live DS3 as well as byte-identical to its serialiser.

**Audited 2026-09-04 — against live DS3, not only the goldens; no defect.** The goldens do
not pin what an attribute does with a literal mixed into a reference, a padded literal name,
a reference name that did not participate, or a padded literal value, so a probe put all four
through both engines as events: identical, including `value="pre-$2-post"`, which both treat
as a literal because a reference begins with its sigil. Two stale descriptions corrected — the
migration's class javadoc still said the conversion writes the indentation and the escaping,
and design 20 §1 described `escapeCaptures` in the present tense. The commit's thirty-two files
are the phase's: the code, the four regenerated natives, the four text variants with their
goldens, the ledgers and the record.

**Corrected 2026-09-04, on the user's challenge — and a miss the audit had not caught.** The
phase's record said a text configuration "cannot" trim or wrap. It can: every instruction
exists. `projects/text_007_regex_dotall_exact`, `text_021_trimmed_values_exact` and
`text_022_empty_input_exact` reach Stroom's goldens byte for byte from text alone — `trim`
and a `not-equals` test for the dropped attribute, `translate` for the entities,
`string-length` + `add` + `greater-than 80` for Saxon's wrap (the sum counts the attribute
names: `name` + value + 25 for a `<data>`, which the first draft of the fixture got wrong by
nine), and a `sequence` counted from the record template so the root chooses `/>` or `>`
after the loop. The accurate statement, now in E35 and the corpus README: not by default, at
the cost of writing the serialiser as instructions — which is what D40 moved into the sink,
and these three fixtures beside the structured natives are that cost, countable. The miss:
the four `text_003…019` variants the phase claimed to keep were on disk but never in the
ledger — the insertion's guard matched the natives' own notes and skipped — so they had never
run, and the corpus-count test passed at the old number because nothing had been added. Listed
now, run now (they pass), and the count is 49. Original wording follows.

`Ds3Migration` stops assembling markup. `RECORDS_HEADER`/`RECORDS_FOOTER` become
`element records { namespace "" "records:2"; namespace xsi …; … }` with the census's
whitespace as `text`; `<data>` becomes `element data { attribute name {…}; attribute value {…} }`;
`escapeAttribute` and `escapeCaptures` are deleted; escaping is no longer written, generated
or forgotten. E33 is ruled (D41): DS3's trim-and-drop-if-empty is matched, as properties of the
generated `attribute`, and it lands here — `007`, `009` and `021` are promoted with the rest,
and `003`, `007`, `019` with them once the phase-2a serialiser wraps as Saxon does (E35).

**Two semantics the fused expression carried that a container does not, and the plan names
them because phase 3 will meet them on its first fixture:**

- **P1 — the empty record.** `wrapAsRecord` runs the body into a variable and writes
  `<record>` only if something came out, because Java's DS3 writes no empty record. Under the
  container that variable holds rendered `<data>` markup, and `value-of` of it inside
  `element record` is *content* — escaped, wrong. The variable trick cannot survive the
  sink-level ruling; the semantics must. *Recommendation:* `element` gains an
  `omit-if-empty` flag, free to implement because the sink already defers the start — an
  `endElement` arriving with nothing written since the start and the flag set emits nothing.
  Put to the user at phase 3's start, not decided here.
- **P2 — the absent value.** The fused expression exists so that an absent reference yields
  no `<data>` at all rather than half a tag (`AbsentAndMalformedValuesTest` pins it). Under
  containers an absent `$1` yields an element with one attribute missing, and the golden says
  DS3 drops the element. *Recommendation:* `attribute` gains `required`; an absent required
  attribute suppresses its element. This is the fused expression's guarantee made structural,
  and it is the second thing put at phase 3's start.

Both are the kind of question design 20 §4B could not have seen without phase 2 existing,
which is why they sit here and not there.

**Tests.** The nineteen legacy and eighteen native fixtures byte-identical — this is the phase,
and there is no partial credit: a fixture that moves is a bug in the serialiser, a bug in the
migration, or a ruling to re-put, and the commit says which. `legacy/020_escaped_values` still `PASS`, its golden untouched since phase 0, and `Ds3ImportTest`
asserting the migration no longer emits a `Translate` for escaping. The phase-1 event
identity test re-run against the structured migration, with the whitespace finding revisited:
if the structured migration can emit exactly DS3's events *and* exactly DS3's serialised
bytes, the normalisation goes; if it cannot do both, the plan records which one the goldens
chose and why. `Ds3ImportTest` extended to assert the migration emits no `Text` node
containing `<`.

**Exit:** no configuration in the corpus escapes XML by hand; `escapeAttribute` is gone; the
`020_escaped_values` still passes without a `translate` in sight; every golden is where it was. E31 moves to `done` for output; the
issue that remains is E30's input half.

---

## Phase 4 — Attribution speaks its currency *(reaches the editor)* — **Done 2026-09-04, engine side**

**As built.** `OutputSink.unit()` — `BYTES` unless a sink says otherwise; `SaxEventSink` says
`EVENTS` and its `position()` counts events. `Instrument.onOutput` gains the unit as its fifth
argument; the executor reads it from the sink and its bracketing is otherwise unchanged. The
rule the deferred start tag imposes is now stated on the contract and pinned in both
currencies: an enclosing element opened by a parent is written when the first child emits, so
the first child's span begins with the parent's start tag (bytes) or start event (events), and
the parent's span covers it. **And 1b's open item closes:** `InputLocations`, an `Instrument`
the reader runs with, pairs each match's input offset with its output byte span — by
bracketing, since `onMatch` and `onOutput` enclose a body in order and a match index restarts
with each parent match, so a key would not do — and, while the output is parsed, resolves the
parser's line and column in the generated text to an output byte offset, the innermost span
holding it, and that match's input line and column. The `Locator` the pipeline's filters
receive is that one, not the parser's. `InputLocationsTest`: fixture 001's six records report
lines 2–7 and each of their twenty-four `<data>` elements reports its record's line.

**Not built, and where it goes.** The preview endpoint does not exist yet — design 18 §11 is
still a design — so "the preview payload carries the unit" is a sentence in design 18, added
with this phase, not code. When the endpoint is built it carries `unit` beside each span and
the output pane maps `EVENTS` ordinals onto the serialised preview it also has. One limit
named: the parser's column counts characters and the input's column is bytes; the same for
ASCII, and named so the difference is not mistaken for a bug when it is not.

**Audited 2026-09-04 — one defect, and the right kind: it passed the test and would have
failed the feed.** The first `Resolver` decoded the whole output to a `String` and an `int[]`
of one entry per character — about six bytes for every output byte, on top of the output — and
`innermost()` scanned every span for every event, which is fine for fixture 001's six records
and a matter of minutes for a hundred thousand. Rewritten as a single forward sweep: the spans
sorted by offset with parents before the children they enclose, opened as the parser's
position reaches them and closed as it passes their ends, the innermost the top of a stack;
and the parser's character column walked to on the bytes of its own line, a four-byte
sequence counting two. Amortised linear, nothing decoded. Pinned by five thousand records
resolving correctly in under half a second, a run the old code would have measured in minutes.
Read through and left: the root's `<?xml …?>` and `</records>` report no position, since no
match wrote them; an eater's `onMatch` with no `onOutput` is discarded when its enclosing
match closes; a parser that never sets a locator leaves the downstream one unset, as before.
Original wording follows.

S5: event ordinals when writing events, byte offsets when writing bytes, one contract that
says which. The cheapest honest shape, and the one recommended here: the event sink's
`position()` counts *events*, so the executor's existing `before`/`after` bracketing in
`Executor` (lines ~485 and ~904) works unchanged, and `Instrument.onOutput` gains one
argument — the sink's `OutputUnit` (`BYTES` | `EVENTS`) — that the executor reads once from
the sink. A recording instrument sees the same call shape it sees today plus a unit.

The preview endpoint (design 18 §11) carries the unit in its payload; the output pane's span
model is already span-shaped rather than byte-shaped and needs to learn only that a span in
`EVENTS` is rendered against the serialised preview by mapping ordinal → serialised range,
which the preview can compute because it has both. Design 18 §5.4 is updated in the same
commit — the same rule design 16 and 19 held to.

**Tests.** `InstrumentTest` extended: the recorder on the byte sink reports `BYTES` and the
offsets it reports today; on the event sink it reports `EVENTS` with ordinals that, when the
same run is serialised, bracket the same elements. A preview test in the editor's API module
that the four-way hover link (input ↔ template ↔ dispatch ↔ output) resolves to the same
template for a span in either unit.

**Exit:** the editor colours output identically whether the preview ran to bytes or to events,
and the trace says which it did.

---

## What could move this plan, in the order it would find out

1. **Phase 0's dependency check** could turn the pipeline module into a package inside
   `stroom-pipeline`, which changes where phases 1 and 3's tests live and nothing else.
2. **Phase 2a's spike** is the S2 risk made cheap: if the serialiser cannot reproduce the
   goldens, the user re-rules S2 with a diff in hand rather than the plan absorbing it.
3. **P1 and P2** may show that containers need one or two flags the rulings did not name;
   they are put as questions, and the fallback if both are refused is that the migration keeps
   its fused expressions for `<data>` and uses containers only for `<records>` and
   `<record>` — a smaller phase 3, recorded as such.
4. **Phase 1's whitespace finding** may show that "the same events DS3 emits" and "the same
   bytes DS3's serialiser wrote" are not both reachable from one configuration. If so, S2's
   bytes win, because that is what the goldens are, and the event comparison keeps its
   normalisation permanently with the reason written beside it.

Out of scope, and said so it is not read as forgotten: SAX as *input* (E30, its own design);
the function registry (E32); a third sink.

**The prototype purge D41 added — done 2026-09-03** under the reading the user chose:
rewrite the record, keep the fixtures. The prototype's vendored design documents, the port
plan (design 07) and the fixture audit (design 08) are deleted, D33 is rewritten as
superseded, and every other mention now says "the prototype"; D41 is where the name survives.
The regex module's design documents still cite it as a comparison point and are left to that
module's own session.

---

## Appendix A — The serialisation census *(phase 0, 2026-09-03)*

What the legacy goldens pin, read from the nineteen `.out.xml` files and from `Ds3Migration`,
so that phase 2a's serialiser is written to a list rather than to memory. Every line here is
something a byte-identity failure in phase 3 will be traced back to.

**Framing.**
- `<?xml version="1.1" encoding="UTF-8"?>` then a newline. Version **1.1**, not 1.0.
- `<records xmlns="records:2"`, newline, nine spaces, `xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"`,
  newline, nine spaces, `xsi:schemaLocation="records:2 file://records-v2.0.xsd"`, newline,
  nine spaces, `version="2.0">`. Namespace declarations first, default before `xsi`, then the
  attributes — the order Stroom's serialiser chose and the migration's `RECORDS_HEADER` copies.
- Footer `\n</records>\n`: a trailing newline after the root's close, and nothing after it —
  Saxon's end-of-document newline, which the sink writes after the root closes.
  **When no record was written the root is self-closed** — `version="2.0"/>` then the
  newline — pinned by `022_empty_input` (the audit's fixture; the census first said "still
  paired", from memory, and was wrong: E34). The engine pairs it today.

**Records.**
- `\n   <record>` (three spaces) … `\n   </record>`; written only when the body produced
  something (P1 — Java's DS3 writes no empty record). Always paired, never `<record/>`.

**Data elements.**
- `\n` + (6 + 3 × depth) spaces + `<data`; nested data indents three more per level.
- Attribute order `name` then `value`; either may be absent (`<data name="x"/>`,
  `<data value="…"/>`); an element with neither is not written at all (P2 — the fused
  expression yields nothing).
- Leaf: self-closing `/>`. With children that wrote something: `>` … children … `\n` +
  indent + `</data>`. With children that wrote nothing: self-closing, decided after the
  children ran (the migration's `__data_children_N__` variable). Fixture 003 pins the paired
  form; 015 and 009 pin it at depth.
- No character content anywhere except the indentation whitespace. No comments, no PIs.

**Entities, in attribute values.** One table for captured values (`escapeCaptures`, six
substitutions) and the serialiser must use the same six:
`&` → `&amp;`, `"` → `&#34;`, `<` → `&lt;`, `>` → `&gt;`, `\n` → `&#xA;`, `\r` → `&#xD;`.
Pinned by 002 (`&#34;`) and 007 (`&lt;`, `&gt;`, `&amp;`, `&#34;`, `&#xA;`), and by 020 for all
five in a referenced *name* as well as a value. `&#xD;` is pinned by nothing and cannot be:
DS3 trims a trailing `\r` before it reaches the serialiser (E33, `021_trimmed_values`), so a
`\r` can only appear mid-value, which no fixture has — the serialiser keeps the substitution
because `escapeCaptures` has it, unpinned. `'` is never escaped (007: `value="success'"`). **No golden
contains `&quot;`**: the migration's `escapeAttribute` writes it for *literal* config values,
which no legacy fixture exercises with a quote — so the serialiser writes `&#34;` for every
quote, and that is Stroom's form, not a choice. Design 20 was right about `&#34;` for the
wrong reason (it named the migration; it is the serialiser's form that the migration copies).

**The wrapping rule (phase 2a, read from Saxon's bytecode, superseding the phase-1 guess).**
`XMLIndenter.startContent` sums, on raw lengths, `9 + uri` for a default namespace
declaration, `prefix + 10 + uri` for a prefixed one, `name + value + 8` for an attribute
(`+ 9` with a prefix); the indent, the element name and the first attribute do not count. If
the sum exceeds 80, each attribute after the first goes on its own line, indented
`(level − 1) × 3 + name + 2` — under the first attribute. Every legacy golden reproduces
under it (`XmlByteSinkDs3GoldenTest`). Under D41 the goldens are Stroom's, so this is the
rule the engine must meet, and the four fixtures that wrap are `PENDING` until phase 3
moves the migration onto `XmlByteSink`.

**Not pinned by any golden, and therefore free for phase 2 to choose — but chosen once:**
content escaping (text between tags — `&`, `<`, `>` as `&amp;`, `&lt;`, `&gt;`, `"` and `'`
left alone); a prefixed element or attribute; a namespace declared below the root. The
hand-written phase-2 fixture pins whatever is chosen.
