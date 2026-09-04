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

**Phase 2 — the pipeline module's library and group A.** The Guice module, the library, the
pool compiling with the registry, both elements binding a context per document, the
`pipelineReference` property; the twenty-three group-A variants. *Tests:* every variant pinned
on the same inputs Stroom's own tests use (`TestFormatDate`, `TestHexToDec` and the rest in
stroom-pipeline's tests are the source of the cases), and a full-pipeline test in
`FullPipelineTest`'s shape calling several from a configuration.

**Phase 3 — group B.** The context-dependent variants, with the holders wired through the
elements; `line-from` and friends from the engine's own offsets. *Tests:* each against the
holder it reads, and a full pipeline with meta.

**Phase 4 — group C.** Lookups over `ReferenceData` with the pipeline references, and the HTTP
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
