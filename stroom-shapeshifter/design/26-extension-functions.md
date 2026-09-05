# Extension functions: a registry and a contract in the engine, Stroom's functions in the pipeline

**Status: design, ruled 2026-09-04 (D44) — every §7 question answered as recommended.
Resolves E32 when built.**

The engine's functions are a closed set: `Translate`, `StringJoin`, `Replace` and the rest are
records in `OutputNode`, compiled by an exhaustive switch in `CompiledOp`, implemented as static
methods in `Transforms`, `Dates` and `Codecs`. Adding one is four edits inside the engine, and
Stroom cannot add one at all. Stroom's XSLT has the opposite shape: Saxon's own functions are
fixed, and everything Stroom adds — fifty-eight functions today, `format-date` to `http-call` —
goes through one contract (`StroomExtensionFunctionDefinition`, `StroomExtensionFunctionCall`),
one registry (`StroomXsltFunctionLibrary`, a Guice multibinder), and one lifecycle (`init` when
the stylesheet is compiled, `configure` per document with the error receiver, the location
factory and the pipeline references, `reset` after). The user asked for the same in the engine,
and then for the pipeline module to carry a variant of every one of those functions.

---

## 1. Stroom's shape, read for what to copy

| Stroom (Saxon) | What it is | The engine's counterpart |
|---|---|---|
| `StroomExtensionFunctionDefinition` | name, min/max args, argument and result `SequenceType`s, a `Provider` of the call | `FunctionDefinition` (§2) |
| `StroomExtensionFunctionCall` | the per-document instance: `call(name, context, args)`, `getSafeString`, `outputWarning` | `FunctionCall` (§2) |
| `ExtensionFunctionCallProxy` | Saxon holds one object per compiled stylesheet; the proxy swaps the per-document call in and out | not needed — the engine binds a library per run (§3) |
| `StroomXsltFunctionLibrary` | the set of definitions; `init(config)`, `configure(errorReceiver, locationFactory, pipelineReferences)`, `reset()` | `FunctionRegistry` at compile time, `FunctionLibrary` at run time (§3) |
| `AbstractXsltFunctionModule` | Guice: a multibinder of definitions, `bindFunction(Class)` | `AbstractShapeshifterFunctionModule` in the pipeline module (§5) |
| `XPathContext` | where the call reads its location and the context item | `FunctionContext` (§2): the input position, messages, run state |
| `TaskScopeMap` (`get`/`put`) | per-pipeline-scope key/value state | run state on the context, pipeline-scoped by the module |

Two things do not copy. Saxon has argument *types* in a sequence-type algebra; the engine has
design 17's five kinds and its casting table, and that is the type language here. And Saxon
function calls are *expressions*; design 17 §4 refused expressions for instructions, so a
function call is an instruction with a `select` list and a `name`, like every computation the
engine already has.

## 2. The contract — `stroom.shapeshifter.engine.function`

```java
public interface FunctionDefinition {
    String name();                       // "hex-to-dec": the name a configuration calls
    Signature signature();               // arity and kinds, checked at compile time
    Purity purity();                     // PURE, CONTEXT or IMPURE (§4)
    FunctionCall bind(FunctionContext context);   // once per run: the instance that is called
}

public record Signature(int minArgs, int maxArgs, List<Kind> argKinds, Kind result) { }
public enum Kind { STRING, NUMBER, INTEGER, BOOLEAN, DATE, ANY, SEQUENCE }
public enum Purity { PURE, CONTEXT, IMPURE }

public interface FunctionCall {
    /** @return the result, or null for absent; arguments are positional, null where absent */
    TypedValue call(List<TypedValue> arguments);
}

public interface FunctionContext {
    void warn(String message);           // a WARNING in the run's messages, naming the function
    void error(String message);          // an ERROR, likewise
    long inputOffset();                  // where the innermost running match began, or UNLOCATABLE
    Map<String, Object> state();         // the run's scratch space: what get/put write to
    Object service(Class<?> type);       // what the library was configured with (§3), or null
}
```

**Arguments are positional and absent is null.** The engine's existing `Transform` drops an
absent input from the list, which `ParseDate` already refuses to share because a dropped
input shifts positions. Registered functions have Stroom's shape — optional trailing arguments
that mean something by position (`format-date`'s output pattern is argument four) — so a call
passes every position, null where the reference resolved to nothing, and the arity check is on
positions written, not values present. A function that wants "absent means skip" says so.

**`SEQUENCE` is a select that names a store.** Two of Stroom's functions take a sequence
where the engine's other instructions take a value — `cosine-similarity` takes two vectors,
`pointIsInsideXYPolygon` the polygon's x and y lists. A `SEQUENCE` argument is a select
resolved the way `count`, `distinct-values` and the folds already resolve theirs
(`Executor.entries`): every entry of the named store, in order, as a `List<TypedValue>`
carried in a `TypedValue.Seq`-shaped argument the call unpacks. No new value kind enters the
model (design 17 §3 refused one); the list exists only for the length of the call.

**Kinds are cast, not checked, at run time.** The compiler checks arity against the signature.
At run time each argument is cast to its declared kind through design 17 §3.1's table; a value
with no reading of that kind arrives as null, and the context carries a note the function may
turn into a warning — Stroom's `getSafeString` warns "illegal non string argument", and a
variant that wants that behaviour has what it needs. `ANY` passes the value as it is.

**The result is a `TypedValue` or absent.** Absent writes nothing and binds absence, which is
`emit`'s rule already. A function that produces an XML fragment in Stroom — `parse-uri`,
`json-to-xml`, `http-call`, `lookup` over a fragment — returns it as text here (§7 asks).

**A throwing function is an error, not a crash.** The engine catches, adds an ERROR naming the
function and the message, and the result is absent; the run continues, as Stroom's
`outputError` and empty sequence do. A function that must stop the run says so through a
`FunctionFailure` it throws deliberately, which the engine reports as FATAL and aborts on.

## 3. Compile time and run time

**`FunctionRegistry`** — immutable, `name → FunctionDefinition`, built once by whoever owns
the engine (the pipeline module's Guice; a command line's list; a test's `FunctionRegistry.of(…)`).
`Shapeshifter.compile(project)` keeps its signature and means the empty registry;
`Shapeshifter.compile(project, registry)` is the new one. Duplicate names are refused when the
registry is built. The registry is a compile-time input, so the pipeline's parser factory pool,
which compiles a document once and caches it, takes the registry in and is keyed as it is now —
the registry does not change while the process runs.

**The `call` instruction** — one new output node, in the shape design 17 §4 requires:

```json
{ "call": { "function": "hex-to-dec",
            "select": [ { "parts": [ { "capture": { "group": 1 } } ] } ],
            "name": "decimal" } }
```

The compiler looks the name up in the registry — unknown is a `ConfigException` by name, as an
unknown match kind is — checks `select.size()` against the signature, and yields a
`CompiledOp.Call(definition, selects, name)`. The function's own name space is the string in
`function`, so a registered name can never collide with an output-node key, and the built-ins
keep their own keys (§7).

**`FunctionLibrary`** — the run-time side. `Executor` is given a library bound for this run:
each definition the compiled project uses is bound once (`definition.bind(context)`) at the
start of the run, and every `Call` op invokes the bound `FunctionCall`. Binding is what Stroom's
`configure` is: the pipeline module gives the context the services this document needs (meta,
feed and pipeline names, the location factory, the pipeline references, the error receiver's
element id), and `reset` is the run ending. The engine's own context implementation carries
the run's messages, the innermost match's input offset (the same live offset `InputLocations`
reads), and a state map.

**Messages carry the function's name and the input location.** A warning from `hex-to-dec` reads
`hex-to-dec: …` and, through the pipeline's `LiveLocatingHandler`, points at the record.

## 4. Purity — E32's teeth

Every definition declares one of:

- **`PURE`** — the result depends on the arguments only. Memoisable, replayable, runs anywhere.
- **`CONTEXT`** — depends on the run's context (the feed, the meta, the record number, the
  clock) but has no effect. Runs anywhere; not memoised across runs.
- **`IMPURE`** — has an effect or reaches outside: `http-call`, `fetch-json`, `log`,
  `add-meta`, `put`, and `host-name` and `host-address`, which resolve through DNS — an
  editor re-running on every keystroke should not be a DNS client either.

The engine's run has a **mode**: `NORMAL` or `PREVIEW`. Design 18's editor re-runs the whole
configuration on every edit, debounced, and the preview endpoint runs it again to draw the
trace (Q6); a configuration carrying `http-call` would fire it on every keystroke. So in
`PREVIEW`, an `IMPURE` function is not called: its result is absent and the run says so once
per function, as a WARNING — "`http-call` not run in preview". `CONTEXT` functions run, since
`feed-name` in a preview is exactly what the author wants to see. `NORMAL` runs everything.
The mode is a parameter of `Shapeshifter.run`; the pipeline elements pass `NORMAL`, and design
18's endpoint will pass `PREVIEW` — this design reaches design 18 by adding that one line to it.

Memoisation of `PURE` functions within a run is not done: the engine's built-ins are not
memoised either, and no measurement asks for it.

## 5. The pipeline module — Stroom's functions as Shapeshifter functions

**`AbstractShapeshifterFunctionModule`** — Guice, a multibinder of `FunctionDefinition`, with
`bindFunction(Class)`, exactly `AbstractXsltFunctionModule`'s shape. **`ShapeshifterFunctionModule`**
binds the inventory below. **`StroomFunctionLibrary`** collects the set into a `FunctionRegistry`
for the pool to compile with, and builds the per-document `FunctionContext` for the elements:
the same holders Stroom's calls are injected with, reached through the context's `service(Class)`
— `MetaHolder`, `MetaDataHolder`, `FeedHolder`, `PipelineHolder`, `CurrentUserHolder`,
`WordListProvider`, `ReferenceData`, `CommonHttpClient`, `TaskScopeMap`, the location factory,
the pipeline references. Both elements gain a `pipelineReference` property, as `XsltFilter` has,
for the lookups.

**Each variant is a thin adapter, not a port of the Saxon class.** Stroom's classes take Saxon
`Sequence`s and an `XPathContext`; their *logic* mostly sits in helpers the variants call
directly — `DateUtil` and `DateFormatterCache`, `EncodingUtil`, the CIDR arithmetic, the digest
in `Hash`, `ReferenceData`, `WordListProvider`, `CommonHttpClient`. Where the logic is inline
in the Saxon class, the variant carries it, with the Saxon class named in a comment as the
thing to keep in step with. Names are Stroom's names, unchanged, so a stylesheet author reads a
Shapeshifter configuration without a dictionary.

**The inventory**, grouped by what a variant needs, which is also the build order (§6):

*A — pure, no services (24).* `cidr-to-numeric-ip-range` (→ two numbers; here a string pair
joined, §7), `cosine-similarity`, `decode-url`, `encode-url`, `format-date` (Stroom's 1/2–3/4–5
argument forms, Stroom's semantics; beside the engine's own `format-date` instruction, which
stays), `parse-dateTime`, `format-dateTime`, `from-unixTime`, `to-unixTime`, `hash`,
`hex-to-dec`, `hex-to-oct`, `hex-to-string`, `host-address`, `host-name`, `ip-in-cidr`,
`numeric-ip`, `parse-uri` (→ XML fragment; text here), `json-to-xml` (likewise),
`pointIsInsideXYPolygon`, `random` (CONTEXT: the clock's cousin), `current-time`,
`current-unixTime` (CONTEXT), `split-document` (**not carried**: it splits the XSLT's document,
which has no counterpart).

*B — the pipeline's context (30).* `feed-name`, `pipeline-name`, `meta`, `meta-attribute`,
`meta-keys`, `feed-attribute`, `classification`, `current-user`, `record-no`, `search-id`,
`part-no`, `source-id`, `stream-id`, `parent-id`, `parent-for-id`, `manifest`,
`manifest-for-id`, `meta-stream`, `meta-stream-for-id`, `source` (the current location as text
here), `line-from`, `line-to`, `col-from`, `col-to` (from the context's input offset through the
run's line index — the engine has this live, where XSLT needs a `LocationHolder`), `get`,
`put` (IMPURE), `log` (IMPURE), `add-meta` (IMPURE), `link`, `dictionary`.

*C — stores and the network (4).* `lookup`, `bitmap-lookup` (`ReferenceData`, the pipeline
references, Stroom's 2–5 argument forms), `http-call`, `fetch-json` (`CommonHttpClient`, and
Stroom's existing controls over what may be reached — a Shapeshifter configuration gets no
wider reach than a stylesheet has).

Fifty-seven carried, one not.

## 6. Phasing

**Phase 1 — the engine — Done 2026-09-04.** *As built:* `stroom.shapeshifter.engine.function`
holds `Kind`, `Purity`, `RunMode`, `Signature` (with `of` factories), `FunctionDefinition`
(with an `of` factory over a binder lambda), `FunctionCall`, `FunctionContext`, `Arguments`,
`FunctionFailure`, `FunctionRegistry` (immutable, `EMPTY`, `of`, duplicates refused) and
`Services` (`NONE`; what `FunctionContext.service` reads). Two things differ from §2 as
sketched: a call receives an `Arguments` object rather than a bare list — `value(i)` cast,
`raw(i)` before casting, `miscast(i)`, `sequence(i)`, and `string`/`number`/`integer`/`bool`/
`date` helpers, Stroom's `getSafeString` family in one place — and the run's services come in
through `Services`, a one-method lookup the engine never looks inside. There is no separate
`FunctionLibrary` class: `Executor` binds each definition the compiled project uses at
construction, one `Context` per function (so a warning is prefixed with its name), and holds
the calls in a map. The `call` node is read and written by the codec (`function`, `select`,
`name`), walked by the compiler's one-walk checks, classified as content when unnamed, and
compiled by `CompiledOp.call` — unknown name refused by name (and "no functions are
registered" when the registry is empty), arity checked against the signature, a `SEQUENCE`
position required to name a variable — into `CompiledOp.CallFunction` (`Call` was taken by
`call-template`). `CompiledProject.functions()` lists the definitions used. The executor
casts each written position through the casting table, expands a sequence position through
the existing `entries`, sets the context's input offset to the running match's, skips an
impure function in preview with one warning, turns a runtime exception into an ERROR and an
absent result and a `FunctionFailure` into a FATAL abort. `Shapeshifter.compile(project,
registry)` and `run(compiled, input, sink, instrument, mode, services)` are the new facade
methods; every existing one is unchanged and means the empty registry and `NORMAL`.
*Pins:* `FunctionsTest`, thirteen cases over a registry of sixteen throwaway functions,
covering everything the phase promised plus positional absence with an optional trailing
argument, miscast warning being the function's choice, binding once per run, services
reaching a call, and the codec's round trip; `EveryVariantTest` covers `Call`. Corpus and
every prior test unchanged: 549 green. *Named:* `Executor` is past checkstyle's 2,000-line
warning (2,126) — a split is due, not in this phase.

*Audited 2026-09-04.* **One defect, fixed:** the run bound its functions in the executor's
constructor, outside the catch that turns a run's failures into messages, so a definition whose
`bind` threw — a service it needs missing, the ordinary case for a pipeline function outside
a pipeline — escaped `Shapeshifter.run` as a raw exception rather than as the run's message.
Binding now happens first inside the run, and a bind that throws is the run's one FATAL,
"<name>: could not be bound to this run: <why>", and the run stops; pinned. **Tidied:** a
throwing function's ERROR carried the exception's `toString`, class name and all; it now
carries the message, with the class as fallback for an exception that has none, which is what
Stroom's `outputError` appends. **Confirmed and pinned:** a call to a function of no arguments
may leave `select` out — the codec reads a missing list as empty — so `{"call": {"function":
"current-time"}}` is the whole instruction. **Named and left:** at root level, in a prologue or
tail, a function's `inputOffset()` is 0 rather than unlocatable, because the root body runs
against an empty match at offset 0 and the two are indistinguishable there; a location
function at root level is a configuration oddity and a `0` is not a wrong answer for one. And
the instrument has no hook for a call — the editor's trace (design 18) will not show a
function ran; that is design 18's to ask for. The `function` package gained its package-info.
551 green.

*As written:* `function` package: the contract (§2), `FunctionRegistry`,
`FunctionLibrary`, the run mode; the `call` output node in model, codec and compiler;
`CompiledOp.Call` and its execution with positional-null arguments, casting, catching,
messages; `Shapeshifter.compile(project, registry)` and `run(…, mode)`. *Tests:* a registry of
test functions pinning arity refusal by name, unknown-name refusal, positional absence, casting
to each kind, absent result, a throwing function as ERROR, a `FunctionFailure` as FATAL,
`IMPURE` skipped in preview with one warning, `CONTEXT` run in preview, `state()` shared
across calls in a run, the corpus untouched (no fixture calls a function).

**Where the pins come from — and where they cannot.** Twenty-five of the fifty-eight have a
test of their own in stroom-pipeline (`TestFormatDate`, `TestHexToDec`, `TestLookup`,
`TestHttpCall` and the rest); their cases are the variants' cases, verbatim. The other
thirty-three do not: `parse-uri`, `encode-url`, `link`, `dictionary`, `meta`, the location
four, `cidr-to-numeric-ip-range` and more are pinned by Stroom's stylesheets and integration
tests only. For those, each variant is pinned two ways: against Stroom's own class where it is
constructible without a pipeline (most of group A: hand a Saxon `Sequence` in and compare), and
against cases read from the class's source where it is not. This is the design's real fidelity
risk, and it is named here rather than in an audit: a variant that agrees with its own reading
of the source is not proven, and the second pin is the one that counts.

**Phase 2 — the pipeline module's library and group A — Done 2026-09-04.** *As built:*
`AbstractShapeshifterFunctionModule` (a multibinder of `FunctionDefinition`, `bindFunction`)
and `ShapeshifterFunctionModule` (group A bound; `groupA()` as instances for a registry built
without Guice), installed from `ShapeshifterModule`; `StroomFunctionLibrary` collects the set
into one registry and the parser factory pool compiles every document against it
(`ShapeshifterParserFactory(project, registry)`; the one-argument form means the empty
registry). `ShapeshifterReader` carries a run's `Services` and `RunMode`; both elements give
each document's reader `ElementServices` — the error receiver proxy, the location factory, the
path creator, the feed and pipeline holders, and the element's `pipelineReference` properties,
by type — and both gained that property. **One thing outside the module changed:** the
reference-date parser that `FormatDate` and `ParseDateTime` each carried inline — the
week-based and missing-year completion from the stream's creation time — is now
`stroom.util.date.ReferenceDateParser`, and the two Saxon classes delegate to it; their 173
tests pass unchanged, and the Shapeshifter `format-date` and `parse-dateTime` run the same
code rather than a copy, which is the fidelity the design asked for. The twenty-three group-A
variants are in `stroom.shapeshifter.pipeline.function`, each a `StroomFunction` naming the
Saxon class it follows; `host-name` and `host-address` are impure (DNS); `format-date` and
`parse-dateTime` are context functions reading `MetaHolder` through the services, now
otherwise; `cidr-to-numeric-ip-range` returns "network,broadcast"; `json-to-xml` and
`parse-uri` return indented text through Stroom's own serialiser with no declaration, and
`parse-uri` wraps its nine part elements in a `uri` element because text needs one root where
the Saxon class returns siblings. *Pins:* `StroomFunctionsTest`, the variants on the cases
Stroom's own tests use (`TestHexToDec` … `TestFormatDate`'s reference-date cases included,
with a mocked `MetaHolder`); `StroomFunctionsVersusSaxonTest`, in `stroom.pipeline.
xsltfunctions` because the Saxon classes are package-private, running thirty-five inputs
through the Saxon class and the variant and requiring the same string — the design's second
pin, for fourteen of the functions; and a full-pipeline test calling `format-date`,
`numeric-ip` and `hash` from a configuration through a real pipeline into a `TextWriter`.
133 pipeline tests green.

*Audited 2026-09-04.* No defect. Confirmed: the parser element creates its reader once, at
`startProcessing`, and hands it the *holders* rather than their values, so a function reads
the stream's feed and meta at call time, and the run — and with it each function's binding
and `format-date`'s parser cache and reference time — is per stream, as `FormatDate`'s
`configure` per document is; the filter binds services before it starts its worker. **Added,
a drift guard:** the Guice module binds classes and `groupA()` lists instances, and nothing
kept the two the same; a pin now builds an injector from the module and requires the bound
set's names to equal `groupA()`'s. `ElementServices` and its `PipelineReferences` record are
public, since phase 4's lookups in the `function` package will read them. **Named and left:**
`hash` digests `getBytes()` in the platform's default charset, because the Saxon class does —
parity over correctness, and a difference between machines that Stroom already has. The
`versus-Saxon` pin covers fourteen functions; the other nine of group A (`cosine-similarity`,
`pointIsInsideXYPolygon`, `json-to-xml`, `parse-uri`, the clock, `random`, `host-name`,
`hex-to-oct` beyond one input, `cidr-to-numeric-ip-range`) are pinned on Stroom's cases or on
cases from the source only, as §6 said they would be: the Saxon classes for the first four
need a Saxon `Configuration` or sequence arguments the harness does not build, and the rest
are non-deterministic or return arrays. 134 green.

*As written:* The Guice module, the library, the
pool compiling with the registry, both elements binding a context per document, the
`pipelineReference` property; the twenty-three group-A variants. *Tests:* every variant pinned
on the same inputs Stroom's own tests use (`TestFormatDate`, `TestHexToDec` and the rest in
stroom-pipeline's tests are the source of the cases), and a full-pipeline test in
`FullPipelineTest`'s shape calling several from a configuration.

**Phase 3 — group B — Done 2026-09-04.** *As built:* the engine's `FunctionContext` gained
`inputLength()`, `recordNumber()` and `message(severity, text)` as defaults, with the executor
keeping the running match's extent and counting top-level records; pinned in `FunctionsTest`.
In the pipeline module, `ShapeshifterServices` is injected once with everything Stroom's
context functions are injected with — the six pipeline-scoped holders through their providers,
`FeedProperties`, `DataService`, `AttributeMapFactory`, the data `Store`, `WordListProvider` —
and both elements take it and hand each document's run `forElement(...)`: those, plus the
element's error receiver, location factory, path creator, `pipelineReference` properties and a
`PipelineState`, one map per element instance, which is what `put` writes and `get` reads
across streams as Stroom's pipeline-scoped `TaskScopeMap` does. The reader overlays each run
with `RunLocations`, lines and columns over the same line index that locates events, so
`line-from` and its siblings answer from the engine's own offsets with no `LocationHolder`:
`from` is the match's first byte, `to` its last, `record-no` the top-level match's number.
The thirty variants are under Stroom's names; `feed-attribute` and `meta` share one lookup, as
Stroom binds one class to both, and so do `source-id` and `stream-id`; `manifest`,
`meta-stream` and `source` return the `stroom-meta` documents the Saxon classes build, as
indented text, with entries sorted by key; `meta-keys` joins with a comma; `log` maps Stroom's
severity names onto the run's messages, unknown names an error. *Pins:* `ContextFunctionsTest`
against real holders and mocked stores, set up as Stroom's own tests set them up, including
the per-run caches of `parent-for-id` and `dictionary`; a full pipeline reading feed, stream
id, record number, line and column from the engine, and a value put by one record and read
by the next. 143 pipeline tests green; the drift pin now covers all fifty-three.

*Audited 2026-09-04.* No defect in what the functions answer. **Tidied:** the location
functions detected "no match running" by comparing the offset with half of `Long.MAX_VALUE`
rather than with the engine's `UNLOCATABLE`; they use the constant. `classification` looked
the feed up on every record where the Saxon class looks it up once per feed; it caches per
feed now. **Pinned, because it is a divergence worth knowing:** `log` at FATAL puts a FATAL
in the run's messages and the run goes on; the pipeline's error receiver hears it after the
run, where Stroom's receiver hears it mid-stream — the stream ends marked fatal either way,
but a Shapeshifter run finishes its output first, and a configuration that wants to stop
has nothing but the engine's own failure for it. **Named and left:** columns are byte
columns, from the line index; Stroom's are characters — the same for ASCII, and a difference
for a multi-byte character earlier on the line. `record-no` counts the engine's top-level
matches; Stroom's counts the split filter's records, which for a DS3 parser are the
`record` elements — the same number for the usual configuration, not by construction.
`meta-attribute` fetches the stream's attributes once per run where the Saxon class keeps them
for the life of the call object, across documents, which is the Saxon class's defect rather
than a difference to match. 144 green.

*As written:* The context-dependent variants, with the holders wired through the
elements; `line-from` and friends from the engine's own offsets. *Tests:* each against the
holder it reads, and a full pipeline with meta.

**Phase 4 — group C — Done 2026-09-04.** *As built:* `ShapeshifterServices` gained
`ReferenceData` and `HttpClientProviderCache`; `lookup` and `bitmap-lookup` share
`AbstractLookupFunction`, which is `AbstractLookup`'s shape over the engine's context — the
map, the key, an optional lookup time (the stream's creation time otherwise), `ignoreWarnings`
and `trace`; the element's `pipelineReference` properties; `ReferenceData.
ensureReferenceDataAvailability`; and the messages Stroom's lookups write, "no reference
loaders", "no effective streams", "map not found in effective streams", "key not found", "key
found … found in stream: N", at the severities Stroom gives them and under the same
trace/ignore-warnings gate. A value is read through `RefDataValueProxy.consumeBytes`, as
Stroom's off-heap consumer reads it, and copied out inside the consumer (the audit below says
why): a string value as it is, a FastInfoset value serialised through Stroom's own
`FastInfosetUtil` with the declaration dropped, a null value absent.
`bitmap-lookup` joins the values it finds with a comma (ruling 4). `http-call` and `fetch-json`
run Stroom's own `CommonHttpClient` — whose constructor is now public, the one change outside
the module — over the injected client cache, so a configuration gets exactly the client
configuration a stylesheet gets; `http-call` builds its content type with `ContentType.parse`
where the Saxon class uses `create`, which refuses the charset parameter the default media
type carries. The response is the `stroom-http` document the Saxon class builds, as text;
`fetch-json` is absent for a 404 and an ERROR for any other failure, where the Saxon class
swallows them. *What Stroom's tests do not give:* `TestHttpCall` is a manual test against a
developer's own TLS server, so the HTTP pair is pinned against a JDK `HttpServer` on this
machine through a client the cache would hand out; the lookups are pinned the way
`TestLookup` pins them, reference data stubbed to fill in each outcome, plus an XML value and
the bitmap's bit-by-bit keys. Not built: a lookup against a reference stream loaded through
`ReferenceData` itself — that needs Stroom's stores, and belongs in stroom-app when a
Shapeshifter reference-data test is written there. 154 pipeline tests green; the drift pin
covers all fifty-seven.

*Audited 2026-09-05.* **One defect, fixed:** a value was read through `supplyValue()`. Over
the off-heap store that is a view into a closed transaction: `RefDataOffHeapStore.getValue`
reads inside `getWithReadTxn`, and the FastInfoset serde wraps the LMDB buffer rather than
copying it ("let the caller clone if required"), so the buffer handed back is dead by the time
the function decodes it. Stroom's own off-heap path, `OffHeapRefDataValueProxyConsumer`, reads
through `consumeBytes`, inside the transaction; only the on-heap consumer uses `supplyValue`.
The tests did not see it because the proxy is mocked, which is also why the audit reads the
store rather than trusting them. `render` now reads through `consumeBytes`, copies the bytes
inside the consumer and decodes the copy afterwards by type id — a string as UTF-8, which is
all Stroom's `StringSerde` is, a FastInfoset value as before — and the tests stub the consumer
with the typed buffer the store would hand over. **One fidelity fix, pinned:** a lookup time
written but absent was taken as "use the default"; Stroom takes it as an empty date, a warning
unless warnings are ignored, and no value. Two tests had been passing a null time to reach the
later flags and pass a real one now. **Tidied:** a dead `lazy` helper. **Named and left:**
`AbstractLookup` checks the task context for termination before each lookup and answers
nothing if the task is done; the function has no task context to ask, and a terminated task
ends the run at the element instead — the check belongs with whatever gives an engine run a
cancellation signal, when it has one. 154 green.

*As written:* Lookups over `ReferenceData` with the pipeline references, and the HTTP
pair under Stroom's controls. *Tests:* lookups against a reference stream loaded the way
`TestReferenceData` does it; `http-call` against a local server, and refused where Stroom would
refuse it.

Each phase audited before the next.

## 7. Rulings — all ruled 2026-09-04, each as recommended (D44)

1. **One `call` instruction** (`{"call": {"function": …, "select": […], "name": …}}`) rather
   than a JSON key per registered function. *Recommendation:* `call`. It keeps the codec closed
   over the engine's own vocabulary, the registry open, and the two name spaces apart; a key per
   function would put the registry inside the codec.
2. **The built-ins stay as they are.** The registry is for extension; `translate`,
   `string-join`, `parse-date` and the rest keep their keys and their compile-time specifics
   (`replace`'s pattern, `parse-date`'s reference). *Recommendation:* yes, now; exposing the
   built-ins *through* the registry for discovery is a later, separate change if the editor
   wants one list.
3. **Preview skips `IMPURE` functions with one warning each.** *Recommendation:* yes; the
   alternative — memoising an HTTP call across edits — hides what the author is editing towards.
4. **XML-returning functions return text.** `parse-uri`, `json-to-xml`, `http-call`,
   `fetch-json`, `lookup` over a fragment, `source`, `manifest`, `meta-stream` return the
   fragment serialised as text. *Recommendation:* yes for now; injecting a fragment as structure
   (element events) is a real feature — "apply-templates over a fragment" — and is its own design.
   `cidr-to-numeric-ip-range` and `meta-keys` return arrays in Saxon; here a string joined with
   a separator the caller can `tokenize`.
5. **Stroom's names, unchanged**, including `format-date` beside the engine's own `format-date`
   instruction. *Recommendation:* yes; the two are told apart by shape (`call` versus the key),
   and an author moving a stylesheet expects the names they know.
6. **`split-document` is not carried.** *Recommendation:* agreed above; it has no counterpart.
7. **A `pipelineReference` property on both elements**, for the lookups. *Recommendation:* yes.

## 8. What this does not decide

- Fragments as structure (ruling 4's larger half).
- Functions in conditions. A condition tests a value; a `call` binds one with `name`, and the
  condition reads it. If that proves too indirect, `test` could take a `call`, later.
- A command-line or test-time way to load definitions by class name. The registry is a Java
  object; whoever owns the engine builds it.
