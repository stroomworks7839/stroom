# Design 42 — Native records: a parser yields records, each record is a match, its fields are its groups

*Proposed 2026-09-17, from design 40's readings and the owner's question: "what options do
we have to bolt a JSON parser to the front of our system? ds-rs originally had bolt-ons for
native Avro, Parquet and protobuf." Ruled the same day, eleven questions in §8 (D57), two of
them changing the design; nothing built.*

## 1. Why, and why now

Design 40 measured the engine parsing four formats against the libraries that own them.
The readings split cleanly in two:

- Where the format is *bytes with framing* — Avro's container, protobuf's wire format — the
  engine's configuration is already a schema-specialised parser: reads at fixed widths,
  strings as slices, one dispatch per record. It runs at 1.5–2× the libraries' generic
  readers (design 41 §6). A native library would buy authoring conveniences, not speed.
- Where the format is *text with grammar* — JSON — the engine pays a template dispatch per
  field against a hand-written state machine, and no configuration shape closes that: 6×
  behind Jackson after every shape lesson design 41 §3a found.
- And where the format is *a file that is not a stream at all* — Parquet, with its Thrift
  footer, column chunks and compressed pages — templates cannot read it. D33 deferred it
  for exactly this reason; `parquet_cities` has sat SKIPPED since.
- And XML, where the reading is not about speed at all. Every XML configuration the engine
  has — `xml_to_json`, `win_sec_xml`, `win_app_xml`, design 40's tokeniser — is a regex over
  XML text: no entity beyond what a `decode` names, no CDATA, no comments or processing
  instructions, no namespace resolution, no well-formedness. That is DS3's heritage, which
  never parsed XML either, so migration fidelity is untouched; but an arbitrary XML feed is
  not honestly read, and design 40 §2 had to bound its comparison to "this shape" for
  exactly that reason.

So the question is not "should the engine have parsers" — design 36 says it should not be a
parser collection, and design 38 retired the last one — but "when a library *is* the right
reader, where does its output enter the engine, so that everything downstream is unchanged?"

ds-rs answered this once: `avro`, `parquet` and `protobuf` match kinds, each record a match,
each field reachable by name through a `field` capture source. Design 27 ruling 10 refused
them by name and design 38 phase 4 deleted them — because they were stubs that had never
been built, not because the shape was wrong. This design is that shape, built properly, as
one seam rather than three kinds.

## 2. The model

**Every native format is a tree, and a native template selects nodes in it.** JSON is one
value with objects and arrays inside; XML is elements; Avro is records within records;
protobuf is messages within messages; Parquet is rows with nested groups. A "record" is not
a property of the format but of the *selection*: the nodes a template chooses to treat as
its matches. So a native template says which format reads the input and which nodes are its
records, and each such node is one match.

```
{"id": …, "name": "user", "mode": "records",
 "match": {"json": {"select": "$.users[*]", "fields": ["name", "age", "score", "active"]}},
 "captures": [{"name": "age", "select": {"field": "age"}}],
 "body": [ … as any template … ]}
```

- **Fitted arms in the model, one arm in the graph.** The formats are a sealed family,
  `MatchExpression.Native`, with one variant per format and each variant's own fields:
  `Json(select, fields)`, `Xml(element, depth, fields)`, `Avro(schema, select, fields)`,
  `Protobuf(descriptor, message, select)`, `Parquet(columns, select)` — and beside the
  family, format-free, `Record(route, fields)` for every level beneath a reader. This is the two-layer
  rule deciding, not taste: the model is the *what* and is strict — sealed, every variant
  enumerated by `EveryVariantTest`, every JSON key checked by the reader, every field typed
  for the UI to render (a path editor is not an element picker is not a descriptor chooser)
  — and a generic `native` with pass-through settings would be the one place in the
  vocabulary where none of that held. The graph is the *how*, and there every format is the
  same thing, a source of records with numbered groups: `CompiledMatch.Native(reader,
  groupCount)`, one kind. The registry (§4) maps a model variant to a reader, not a string
  to one; a variant with no reader registered is refused at compile time, by name, as an
  unknown function is. Adding a format touches the model, its JSON and the variant test,
  which is the codebase's discipline for every addition and the right cost.
- `select` names the nodes that are this template's records, in the format's own idiom — a
  path for JSON (`$.users[*]`; the root object alone is the default), an element name or
  depth for XML, a field path for Avro and protobuf (`items[*]`), the row for Parquet — and
  its type is the variant's, so an XML depth is an integer and a JSON path a string. **At
  the root it is relative to the document; in a nested dispatch it is relative to the record
  dispatched from** (below).
- **A record's fields are its scalar children**, by name — an object's members, an
  element's attributes and its text, a message's fields — and they are the match's groups,
  numbered from the schema where the reader has one (Avro, protobuf, Parquet) and otherwise
  derived from what the template's body and captures read, an explicit `fields` list being
  an assertion of shape and order rather than a necessity (ruling 1b); reachable by name
  through `{"field": "age"}` in a capture and in a body. The
  `field` source returns, compiled to a group number exactly as a label is. Absent fields
  are absent groups. Values are **typed as the reader read them** — an integer, a double, a
  boolean, UTF-8 bytes — the same kinds a `read` cast produces, so a body treats a native
  field and a cast label identically.
- **A record is a structured slice: values materialised on access, not filled in advance.**
  The engine already does this for text — a `ByteSlice` is the input array, an offset and a
  length, decoded when something asks — and the structured equivalent is a map or list value
  that holds the record's bytes, its format and, where needed, its schema, and decodes a
  member only when a `get`, a capture or a dispatch asks for it, memoising what it decoded.
  To a body it is a map; no node type appears and the interface does not change; but nothing
  is pre-filled, and memory is offsets plus what was touched. Every reader has this as its own
  primitive: a JSON member is found by scanning the object's bytes for its key and parsing
  the value from that offset; a protobuf field by scanning tags, a sub-message a
  length-delimited sub-range, a slice of a slice; an Avro field by decoding the ones before
  it against the writer schema, forward-only and cheap; a Parquet row by its column readers,
  which is how Parquet is meant to be read. It needs the input resident — which native
  templates already require, whole-buffer — and the collections of design 35 to be
  interfaces the readers implement beside the engine's own (`TypedValue.List` and `Map` are
  final classes today; that is phase 0's first change). It makes the two-pass document
  pattern (§2a) cheap: a list of records held for a later pass is a list of slices.
  Concretely, one implementation per format per kind, living with its reader: `JsonMap` and
  `JsonList` over Jackson, `AvroMap` and `AvroList` over a record's bytes and writer schema,
  `ProtobufMap` and `ProtobufList` over a message range and its descriptor, Parquet's over
  its column readers — with three rules that keep them values and not nodes. **The interface
  is the engine's and it is small**: `get`, `size`, iteration, `contains`, what the accessors
  and `Comparisons` already call, and a format's map adds nothing a body could see — if a
  body could tell a `JsonMap` from an engine map, the body vocabulary has grown. **Slices of
  slices**: a member that is itself structured is another slice over a sub-range, never a
  copy, until a scalar is read — `ByteSlice.range`'s rule. **Read-only**: the engine's own
  collections are mutable (design 35's `append`, `put`, `remove`, `clear` on declared
  names); a format-backed slice is not, and the compiler, which already knows a declared
  collection from a captured value, refuses a mutating op on a captured slice at compile
  time — the same check `listTarget` makes today, with one more case.
- **The projection is the eager option, derived by the compiler**, for readers that want
  the demand up front — Parquet's columns, Avro's reader schema — or where the analysis
  shows most of a record is read anyway. The reader is involved at exactly one place: where
  a native template's `select` names the records. The projection is the configuration's
  demand, which the compiler derives: the `field` captures and references in the
  template's body; each `apply-templates` over a complex field, which pulls in the demand of
  that mode's `record` templates beneath that field, recursively; a `get` with a literal key
  on a captured map. That is a tree of names, computed at compile time by the walk
  `ReferenceCheck` already makes over the sealed model, and handed to the reader, which
  decodes those paths into map, list and scalar values and **skips the rest** — Jackson
  skips a subtree without building it, protobuf skips a field by its tag, Avro projects with
  a reader schema, Parquet reads only the columns named; projection is what these libraries
  are built to do. A subtree the analysis cannot bound — a `get` with a computed key, a
  `for-each` over a map's entries — stays lazy, so the fallback is correct and the common
  case is cheap either way. Nothing below a record is a foreign *node*: what a lazy map
  holds is bytes and a format, as a byte slice holds bytes and an encoding, and the library
  is called only to decode a member. What the reader streams is *between* records, so the
  root `select` sets the memory grain: `orders[*]` holds one order's slice at a time, and
  selecting the root object holds the document's. `fields` on a template is therefore
  derived, and an explicit list is an assertion of shape and group order, not a necessity.
- **Below the record, every level is format-free.** A record's complex children are values
  — an object or nested record a map, an array or repeated field a list — which a body walks
  with `for-each` and `get`, or dispatches: a body's `apply-templates` over a complex field
  dispatches a mode's templates over the list's elements (or the one map), and those
  templates match **values, not a format**. So the native arms — `json`, `xml`, `avro`,
  `protobuf`, `parquet` — appear only where a reader opens bytes: the root, or a byte
  region a body dispatches into. Everything beneath uses one format-free kind, **`record`**:
  a template over a map value, `{"record": {"fields": ["sku", "qty"]}}`, with an optional
  route to descend within it. It is the same template whether the map came from JSON,
  protobuf, Avro, or a list the configuration built itself in an earlier pass (§2a) — which
  is what makes the two-pass document pattern a pattern and not a feature.
- **Group 0 is the record's canonical image where the format has one.** For a byte-framed
  stream (JSON lines, delimited protobuf) it is the record's slice, as now. For a tree
  format it is the node's canonical serialisation — Jackson's compact form for JSON; for
  XML the serialisation design 22 already chose for events-in (`XmlByteSink`'s, Saxon's
  rules) — so a body can still match *inside* a record with patterns, and a configuration
  written against the image works whatever the upstream's formatting was. For a container
  or a columnar file with no bytes that are "this record" (Avro's blocks, Parquet) it is
  absent, and a configuration that writes it gets absence, not an error.

- **Embedded formats, in either direction and at any depth.** A format arm appears wherever
  bytes are opened, and a field's bytes are bytes. JSON inside XML: an `xml` template
  selects the element, its text is a bytes field, and `apply-templates` over that field into
  a mode whose templates are `json` opens Jackson on those bytes — each object there a match
  as if it were the document. XML inside JSON: a `json` template's string field holds XML,
  and `apply-templates` over it into an `xml` mode opens Woodstox on the string. JSON inside
  a log line: the byte path finds the region with a regex and dispatches it to a `json`
  mode. The compile-time rule already decides it: a mode's templates are all byte, all
  `record`, or all native of one format, and that says what the dispatched value must be —
  bytes for byte and native modes, a collection for `record` modes — so JSON in XML in JSON
  is three modes deep with no new vocabulary, each level's slices slices of the level
  above. A reader hands out string fields and the canonical image as UTF-8, so an embedded
  reader always opens UTF-8 and no template-level encoding is declared there.

**What does not change.** Everything after the match: captures, declarations, scope,
dispatch, bodies, sinks, functions, instrumentation. A native template's body is any body,
and the byte path and the native path nest in either order.

**What stays out.** A native match kind is *not* a way to run a library's transform,
schema evolution logic or query engine; it yields nodes and their fields, and the
configuration does the rest. It does not replace the byte path for formats templates
already parse well — Avro and protobuf configurations keep working exactly as they do, and
the native readers for those formats are for what templates cannot express, not for speed.

## 2a. Hierarchy, and the line against XPath

**Nesting is the same three ops used three times.** An order with its items:

```
{"name": "order", "mode": "orders",
 "match": {"json": {"select": "orders[*]", "fields": ["id", "status"]}},
 "body": [{"element": {"name": "order", "body": [
            {"attribute": {"name": "id", "body": [{"value-of": {"parts": [{"capture": {"field": "id"}}]}}]}},
            {"apply-templates": {"select": {"parts": [{"capture": {"field": "items"}}]}, "mode": "items"}}]}}]}

{"name": "item", "mode": "items",
 "match": {"record": {"fields": ["sku", "qty"]}},
 "body": [{"element": {"name": "item", "body": [ … ]}}]}
```

The root template selects its records; its body writes what it writes and applies a mode
over a complex field; the `record` templates of that mode match the values dispatched to
them — with a route to descend within one where needed — and so on down. A structured child not worth a level — an address —
is a map, walked with `get` as design 35 already allows. No op is new; the byte path's
nested `apply-templates` over a region is the same op over a node set.

**The line.** XSLT's shape comes from one decision: selection by *predicate* —
`order[@status='open']/item[position()>1]`. Once a match can ask a question about a value,
the pattern language grows predicates, axes and functions until it is a query language and
the body is an afterthought. This engine's discipline is the opposite, and it is the rule
here, stated so it can be held to:

> **Selection is by position in the tree — a name or an index — and nothing else. Every
> decision about a value is made where decisions are made today: a `guard` on the template,
> `if` and `choose` in the body.**

Concretely:

- `select` is a **route, never a query**: a chain of member names and `[*]` (`orders[*]`,
  `payload.events[*]`), an element name or depth for XML, a field path for a schema. No
  predicates, no `//`, no axes, no functions, no wildcards on names. It is what a byte
  configuration's nested `apply-templates` already is — a walk to the region — spelled for
  trees; and it is a short grammar the UI renders as a breadcrumb, not a text box.
- **Choosing among a mode's templates is by the node's name** — the element's, the member's,
  the field's — tried in order, first wins: strict dispatch as it stands, with a name where
  the byte path has a match at the cursor. Heterogeneous children (an XML element with
  several kinds of child) are handled as a byte level with several templates is.
- **A body's references name this record's scalar children only.** Anything deeper is a
  map value reached with `get`, or the next level's template. There is no `a/b/c` in a
  `value-of`, and no way to write one.
- **A guard is a condition on captured values**, as now: "only orders whose status is open"
  is a capture of `status` and a guard, not a predicate in the match. The one place this
  costs is a filter that XPath would express in the path and the engine expresses as a
  template that captures and a guard that refuses — which is one template, not a language.

**Why nothing is lost within a record, and what is given up beyond it.** A predicate is a
question about a value and a choice on the answer; here the question is a capture (or a
`get` into a map or list child) and the choice is a guard or an `if` — two lines for one,
nothing missing. Even a question about a descendant — "orders with an item over ten" — is a
`for-each` over the child list setting a flag, since the record's subtree is reachable as
the engine's own values — slices decoded as they are touched (§2). And scope flows downward for free: a parent template
captures, a child template dispatched from its body reads the capture, so "filter by the
parent's value" is a guard on the child, and "the parent's id on every item" is a reference.
That is the mechanism: *capture above, dispatch below, decide in the body.*

**Across the document, the same holds — by choosing what to keep.** `following-sibling`,
`preceding-sibling`, `key()` over everything, position among all matches: none is a
primitive here, and all are reachable with what designs 16, 17 and 35 already provide. A
first dispatch over the records appends each one — or the three fields the question needs,
or its canonical image — to a declared list, or puts it in a map by id; a second dispatch
over the same records processes each with `get` at index ± 1 for its neighbours, the map for
any record by key, captured scope for its ancestors, the subtree for its descendants. Two
modes and a list are XSLT's axes. The limiting factor is the one XSLT has: memory for what
is held. The difference is not capability but that the holding is **explicit and
proportional** — the author keeps what the question needs rather than the document, sees
the cost in the configuration, and the streaming default stands for everything that does
not need it. Design 23's contract is that the *engine* builds no document behind the
author's back, not that the author cannot build what they need in front of it.

What this gives up, then, is deliberate and small: the fluency of a path that both finds
and filters, and the implicitness of a DOM that is always there. What it keeps is the model:
a template matches a thing, captures what it needs, decides in its body, and dispatches the
next thing — the same sentence for a log line, a JSON object and an XML element, with the
memory it uses a choice the configuration shows.

## 2b. Worked: what each template sees, and what a variable is

No node type reaches a body. What the reader offers a template as its match is a structured
slice — bytes, a format, a schema where there is one — and what captures bind and bodies
read are the engine's existing typed values, decoded from it as they are asked for. One
document:

```json
{"orders": [
  {"id": 1, "status": "open",
   "customer": {"name": "Ann", "tier": "gold"},
   "items": [{"sku": "x", "qty": 2}, {"sku": "y", "qty": 5}]}
]}
```

**Template 1**, `{"json": {"select": "orders[*]", "fields": ["id", "status", "customer",
"items"]}}`: the reader walks to `orders` and each element is one match. Its groups:

| field | in the JSON | the group's value |
|---|---|---|
| `id` | number | `Integer(1)` — the kind a `zigzag` read gives |
| `status` | string | `Bytes("open")` — the kind a regex group gives |
| `customer` | object | a `Map` — design 35's kind, a `JsonMap` slice over the object's bytes, `name` decoded when read |
| `items` | array | a `List` — a `JsonList` slice over the array's bytes, each element a `JsonMap` slice |

Captures bind them as they bind anything: `{"name": "orderId", "select": {"field": "id"}}`
puts `Integer(1)` in a declared scalar; `{"name": "customer", "select": {"field":
"customer"}}` puts the map in a declared map, and the body reads `get(customer, "name")`
with the accessor that exists. The body writes `<order id="1" customer="Ann">` and then
`apply-templates` over field `items`, mode `item`.

**The dispatch.** Today `apply-templates` selects a value and dispatches a mode's templates
over its *bytes*; over a list or map value it dispatches them over the elements, each a map
a `record` template matches. Which happens is decided at compile time by the mode: a mode's
templates are all byte templates, or all `record` templates, and a mixed mode is refused.
The reader was involved once, at template 1's boundary; template 2 never sees it, and the
`JsonMap` it matches decodes `sku` and `qty` when the captures ask.

**Template 2**, mode `item`, `{"record": {"fields": ["sku", "qty"]}}` — no format: it
matches a map value, whichever reader or configuration made it. Each element of `items` is
a match with groups `Bytes("x")` and `Integer(2)`; the body reads `orderId` from the
parent's scope — design 35's resolution, the parent captured, the child reads — and writes
`<item order="1" sku="x" qty="2"/>`. A `record` template may carry a route to descend
within its map (`payload.events[*]`), and for XML's heterogeneous children a mode holds one
`record` template per child element name, chosen by name as §2a says.

So the chain is: *the reader offers a record as a structured slice; captures and reads
decode the members they name, and only those; the body decides and dispatches the next
values — which are slices of the slice.* A
variable is an integer, bytes, a double, a boolean, a map or a list — the kinds that exist
— and that is the guarantee behind §6's first test: by the time a body runs there is nothing
left that is not a value it already knows.

## 3. Compilation and execution

`CompiledMatch.Native(RecordReader reader, int groupCount)` beside the regex, the pattern
tree, the sequence and the delimiter; and `CompiledMatch.Record(route, int groupCount)`
for the format-free kind. `MatchCompiler` resolves a native variant against the registry the
compile was given (§4), hands the reader the variant, and takes back the field table: name
→ group, which goes into the interner as labels do, so `field` captures and references
compile to group numbers with no run-time lookup. A `record` template's table is the
`fields` it names or the compiler derives.

At run time a native template is a **source of records**, not a pattern to try at a cursor.
`Level.match` for a native kind asks the reader for the record at the cursor:

```
RecordReader.open(data, from, to) → Records          once per dispatch region
Records.next(groups) → advance, or -1 at the end     once per record
```

The reader fills the `groups[]` array it is given (as `partsMatch` fills its own — clear,
don't allocate): the named scalar fields decoded, since naming them is the demand; a complex
field as a slice; group 0 the record's image or absent (ruling 2). It returns the bytes it
consumed, or the position after the record when it knows it, or 0 when the format has no
per-record position, in which case the level advances by the reader's word alone and
locates records by count; instrumentation gets a record index where it cannot get a byte
offset. A `record` template's match is the check that the dispatched value is a map (or
that its route resolves to one) and the same fill from the map's members.

**Native templates run whole-buffer first** (`runWhole`), as the binary configurations do
today, to establish the contract and the mechanism; streaming a framed format through the
window is phase 5 and is owed (ruling 5), since large inputs cannot be held whole.

**Errors.** A reader that cannot read — a malformed document, a schema mismatch — reports
through the run's messages as a match failure would (D36's per-record rule): the record is
skipped with a message under `ignore_errors`, or the run aborts without it. A reader never
throws through the level.

## 4. Where the readers live

The engine module defines the seam and holds no reader: `RecordReader`, `Records`, and a
`FormatRegistry` — a compile-time input exactly as `FunctionRegistry` is (design 26): built
once by whoever owns the run, immutable, `model variant → reader factory`. `Shapeshifter.compile`
takes it beside the function registry; the engine's own default is empty, so a `json` match
compiles nowhere the pipeline has not registered Jackson.

The readers live in a module of their own, `stroom-shapeshifter-formats`, which the
pipeline registers and does not host, **one sub-package per format with a hidden
implementation** (ruled 2026-09-17): `formats.json`, `formats.xml`, `formats.avro`,
`formats.protobuf`, `formats.parquet`. Each package exposes one public class — the format's
registration, `JsonFormat.register(FormatRegistry)` or a provider the registry discovers —
and everything else is package-private: the reader over its library (Jackson's streaming
parser, Woodstox, Avro's `DataFileReader` with a `GenericDatumReader`, `DynamicMessage` over
a descriptor, Parquet's reader), the format's `Map` and `List` slices, its schema handling.
The engine's seam interfaces are the only thing a package implements and the only thing
outside it can name, so the boundary is the compiler's to enforce, not a reviewer's. The
libraries are the module's, never the engine's: the engine's zero-dependency promise for
the regex module and its one-dependency reality (Jackson for the configuration format) stay
as they are. Parquet's Hadoop weight is the one thing that may argue for a module of its
own when phase 2 arrives; §8 leaves that to then.

## 5. What each format gets, honestly

| format | what templates do today | what a native reader adds | speed expectation |
|---|---|---|---|
| JSON | one dispatch per field, 111 MiB/s | one dispatch per *record*; every escape, number form and unicode case Jackson handles | design 40's census: Jackson's parse (~170 ms per 1M records) plus the body (~450 ms) ≈ 2× today; still 3–4× behind bare Jackson, because the body emits structure |
| Parquet | nothing — SKIPPED since D33 | the format at all: rows from column chunks, projected columns, the footer's schema | no comparison; correctness is the reading |
| Avro | 1.7× the generic reader | schema resolution (a reader schema against the writer's), unions, nested records, logical types | *slower* than the template parse, by the library's own generic-reader cost; a feature, not a speed-up |
| protobuf | 2.1× `DynamicMessage` | field names and types from the descriptor, nested and repeated messages, maps, oneofs — the parts of the wire format a template can express only by hand | slower than the template parse on flat messages; a generated-class reader would change that, and is a registration, not a design |
| XML | level with Xerces, half of Woodstox — on one shape | **correctness**: entities, CDATA, comments, PIs, namespaces, well-formedness, whatever the upstream's formatting; elements selected by name or depth as records over Woodstox, attributes and text as fields, children the next level, group 0 the canonical image (design 22) | slower than the tokeniser on the flat corpus — the parse plus a serialisation per element — and the right reader for any feed that is *XML* rather than a known shape |

The first phase is JSON — the one format where a library is both faster and more correct
than templates can be — and Parquet the second, because nothing else reads it. XML is third
and is a correctness phase, not a speed one: new fixtures beside the regex-over-XML ones
(ruling 7), a native twin of an existing job against the same golden as the proof that a
body written against the canonical image is the body written against the text. Avro and protobuf readers follow when a configuration needs what they add.

## 6. What would make this a mistake

- **A second engine.** If a native template's records cannot be handled by the *same*
  captures, scope, bodies and sinks — if readers need their own body vocabulary, or nesting
  needs anything but `apply-templates` — the seam is wrong. The test is a native twin of
  `json_to_xml` — the same golden, the match a `json` arm, the body unchanged.
- **The reader owning the cursor for everything.** If a native template can only be the
  root, and nothing byte-shaped can follow it in the same configuration, the design has made
  the input the library's rather than the engine's. A native template must be dispatchable
  from a body over a byte region — a JSON document inside a log line — with the reader
  opened on that region.
- **Speed sold that is not there.** The Avro and protobuf rows are the control: a native
  reader for a format templates already parse must read slower or level, and the design must
  say so rather than let "native" imply "fast".
- **Libraries in the engine.** One `import org.apache.avro` in the engine module is the
  boundary crossed.

## 7. Phases

0. **The seam**: the collections as interfaces (`TypedValue.List`, `Map` and `Set` today
   final classes) with the engine's implementations kept; `RecordReader`, `Records`,
   `FormatRegistry`, `CompiledMatch.Native` and `CompiledMatch.Record`, the
   `MatchExpression.Native` family with its first variant and the format-free `Record` kind
   in the model and JSON, the projection analysis in the compiler, `apply-templates` over a
   collection value, the `field` capture source and reference, `MatchCompiler` resolving a
   format or refusing it by name, `Level` running a native template whole-buffer. An engine-side test reader (records from a fixed list) so the
   engine's own tests cover the seam with no library. `EveryVariantTest` extended.
1. **JSON** over Jackson, the first sub-package of the formats module: `select` paths,
   objects as records or maps, arrays as the next level or lists, the `fields` projection,
   the compact image as group 0. Fixtures for embedding both ways, since the feeds carry it constantly: JSON
   inside a log line's byte region, and XML inside a JSON string field opened as text —
   the second reading the XML through the byte path until phase 3 gives it a reader.
   Parity against the design 40 configuration on the JSON
   corpus (`JsonParseParityTest` gains a native row), then the JSON parse row with a native
   configuration beside the template one. A native twin of `json_to_xml` against the same
   golden as the fixture proof, the regex one kept (ruling 7's spirit: both mechanisms
   tested, the corpus grown).
2. **Parquet** — the library chosen for dependency weight (parquet-java carries Hadoop;
   the alternatives are a ruling); `parquet_cities` un-skipped, its golden the reading.
3. **XML** over Woodstox: elements by name or depth, attributes and text as fields, children
   the next level, the canonical image as group 0. New fixtures beside the existing ones
   (ruling 7): native twins of `xml_to_json` and `win_sec_xml` against the same goldens, so
   both mechanisms are tested against one output, plus a fixture with CDATA, comments and a
   namespace prefix that no regex configuration reads right — the phase's reason.
4. **Avro** with a reader schema; **protobuf** from a descriptor. Each with a fixture whose
   configuration needs what the reader adds, so the phase has a reason.
5. **Streaming** a framed native format through the window — owed, not on demand (ruling
   5): large inputs cannot be held whole. The contract is design 23's for text applied to a
   record: the window holds a record's bytes until its templates are done with it, and a
   structured slice never outlives its record unless the configuration copies it into a
   declared collection — which is where the two-pass pattern's memory becomes the
   author's, as §2a says.

## 8. Rulings — 2026-09-17

Put to the owner and ruled the same day (D57). Two changed the design rather than confirming
it, and are marked.

| # | Question | Ruling |
|---|---|---|
| 1 | The model surface | **Fitted arms**: one sealed variant per format with its own typed fields and checked JSON keys, one `CompiledMatch.Native` in the graph. |
| 1b | How a record's groups are named | **Both**: a reader with a schema numbers groups from it; a schemaless one takes the configuration's list; with lazy slices `fields` is derived from what the body reads and an explicit list is an assertion of shape and order. |
| 2 | Group 0 for containers and columnar files | **Absent is allowed**; byte-framed formats keep the record's slice. |
| 3 | Where readers live | **A `stroom-shapeshifter-formats` module**, one sub-package per format, one public registration class each, the rest hidden. Parquet's own module if Hadoop's weight says so, at phase 2. |
| 4 | First formats | **JSON, then Parquet**; XML third as the correctness phase; Avro and protobuf readers when a configuration needs what they add. |
| 5 | Whole-buffer or streaming | **Whole-buffer first, to establish the contract and mechanism — and streaming as a committed phase, not on demand**: large inputs cannot be held whole. *Changed the design*: §7's phase 5 is owed, and the slice contract must say a record's bytes stay resident for the record's life, which is design 23's window contract for text applied to a record. |
| 6 | The `field` source | **Returns**, compiled to a group number as a label is. |
| 7 | XML as a native format | **Yes — and with new fixtures beside the existing ones**, not a move: both mechanisms stay tested and the corpus grows. *Changed the design*: §7's phase 3 adds fixtures rather than moving `xml_to_json` and `win_sec_xml`. |
| 8 | Selection by position only | **A route, never a query**; every decision about a value in a guard or a body; the `select` grammar fixed here and grown only by a ruling. |
| 9 | One format-free `record` kind beneath the reader | **Yes**; format arms only where a reader opens bytes. |
| 10 | Records as structured slices | **Yes**: a map or list over the record's bytes, decoding on access, memoised, read-only, the collections made interfaces; the per-format implementations hidden in their packages. |
| 11 | The projection | **Kept as the eager option**, derived by the compiler, for readers that want demand up front and where most of a record is read. |
