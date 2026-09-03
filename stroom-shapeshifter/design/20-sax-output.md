# Emitting XML as events, not as text

**Status: ruled 2026-09-03, in full — eleven questions in §10; S8 and N3 were re-ruled the
same day as their consequences were followed, and the resting place is §4B: the instructions
do not change, the sink interprets bytes by the container it is in. Drives [E31](../stroom-shapeshifter-engine/ISSUES.md); answers the concrete half of
[E15](../stroom-shapeshifter-engine/ISSUES.md), which had been `blocked` on
[D10](00-decisions.md) since the port began. Nothing here is implemented yet; [design 21](21-sax-bridge-plan.md)
is the plan — phases, tests and gates — and §9 here is superseded by it.
One framing the rulings fixed that the draft only implied: bridging to SAX is optional. The
byte path is not a legacy path awaiting retirement — it is the path for every configuration
whose output is not XML, and the deployment, not the configuration, decides which sink runs.**

The engine writes bytes that happen to be XML. Stroom's pipeline consumes SAX events. This
document is about the seam between those two sentences: what it costs today, what the two
honest ways to close it are, and what each of them does to configurations, to the goldens, and
to the trace the editor draws.

---

## 1. Why this is not just plumbing

Three things are true at once, and only the first is obvious.

**The pipeline element cannot exist without an answer.** `AbstractParser` in
`stroom-pipeline` requires `createReader()` to return an `XMLReader`, whose `parse()` pushes
events into the pipeline's `ContentHandler`. A shapeshifter element must therefore produce
events. It can do that by serialising bytes and re-parsing them, or by emitting them — but it
must do one of the two, and that choice is this document.

**Text output makes every configuration hand-roll XML escaping.** The flagship fixture proves
it: `apache_httpd/project.json` contains **244 `translate` steps**, each one mapping
`& " < >` to their entity forms through a temporary variable, because the configuration is
assembling attribute values as text and nothing else will do it. That is not a quirk of one
config; it is what "output is a byte stream that happens to be XML" means for an author.

**And the migration pays for it again, per value.** `Ds3Migration` escapes twice over: literal
names and values through `escapeAttribute` at conversion time, and every *captured* value
through `escapeCaptures`, which wraps each reference in a generated `translate` bound to an
`__esc_N` variable — six substitutions (`& " < > \n \r` → `&amp; &#34; &lt; &gt; &#xA; &#xD;`)
per captured attribute value, in every migrated configuration. It is correct: goldens 002 and
007 pin `&#34;Big, Bad Fred&#34;`, `&lt;`, `&amp;` and `&#xA;` against Stroom's own output.
(An earlier draft of this document called the reference path an unescaped defect; it was
misread — `emitDataTag` routes every reference through `escapeCaptures` — and the correction
is recorded in design 21 phase 0.) The point survives the correction and sharpens: the
migration had to *generate machinery* to do what **Stroom's own DS3 never thinks about**,
because DS3 emits events and the serialiser escapes by construction. The port inherited the
work along with the text model.

---

## 2. Prior art, and it is close to hand

**Stroom's DS3 parser emits events directly.** `DS3Parser` calls `startDocument`,
`startPrefixMapping("", NamespaceConstants.RECORDS)`, `startPrefixMapping("xsi", …)`, then
`startElement(NAMESPACE, "records", …)`, and per record and per field
`startElement(…, "record", …)` / `startElement(…, "data", dataAttributes)`. Attribute values
go into an `Attributes` object; nothing in the config or the parser ever spells an entity.
Its output vocabulary is **fixed**: `records` / `record` / `data[@name,@value]`, the
`records:2` schema, with the XSLT downstream doing everything else.

**Shapeshifter is deliberately not that.** Its configurations emit arbitrary XML — the
`apache_httpd` fixture writes `event-logging:3` directly, which is a whole XSLT stage that
DS3 needs and shapeshifter does not. Any answer here has to keep that: the vocabulary is the
author's, not the engine's.

**ds-rs, the ancestor, is text end to end.** It had no pipeline to fit into, so the question
never arose there. Inheriting text output was reasonable; keeping it once the destination is
known is the thing to decide.

---

## 3. The seams as they actually are

| seam | what it is today |
|---|---|
| `OutputSink` | `write(byte[], off, len)`, `write(String)` (UTF-8), `position()`. One implementation, `OutputSink.of(OutputStream)`. Its javadoc already names SAX as the expected second implementation and D10 as the reason it is not one yet. Under the rulings it widens (§4B): the structural calls join `write`, and `write` becomes context-dependent inside them. |
| Output instructions | `OutputNode.Text` (a literal), `ValueOf` (an expression), plus control flow (`If`, `Choose`, `Switch`, `ApplyTemplates`, `CallTemplate`, `Variable`, `ValueMap`) and the transforms. **Nothing in the model names an element, an attribute or a namespace.** |
| Attribution | `Instrument.onOutput(templateId, matchIndex, outputOffset, outputLength)` — byte offsets into the sink, taken from `position()`. |
| The editor | Colours output *spans* by the instruction that wrote them, and links them to the body cards, the template rows and the input (design 18 §5.4). It is built on those byte offsets. |

The sink is the easy part and it is already in place. The instructions and the attribution are
where the work is.

---

## 4. The fork

### Option A — serialise as now, parse at the boundary

The pipeline element wraps the engine's byte output in a parser and forwards the events.

- **Configurations are untouched**; every fixture keeps its golden byte for byte.
- **Ships almost immediately** — it is an `XMLReader` around `Shapeshifter.run` plus a parse.
- Costs a full parse of everything the engine just wrote, on every record.
- **Errors land far from their cause.** A configuration that writes an unbalanced tag or an
  unescaped `&` fails in the parser, with a position in generated text rather than in the
  instruction that wrote it — a hand-written configuration's mistake, since the migration's
  generated escaping (§1) does not make them.
- The engine keeps two output truths: bytes, and events derived from bytes.

### Option B — structured emitters

New instructions — `element`, `attribute`, `namespace` — that tell the sink where it is. The
byte sink serialises when the target is bytes; the event sink forwards. `text` and `value-of`
are not touched: they call `write` exactly as today, and **the sink interprets those bytes by
the container it is in**.

- **Escaping stops being the author's problem.** `apache_httpd`'s 244 `translate` steps become
  unnecessary, and so does the migration's generated `__esc_N` `translate` per captured
  value — escaping cannot be forgotten because it is not written.
- **Ill-formedness is caught where the mistake is made** — an unclosed element is a defect in
  the instruction, reported against that instruction, which is exactly what the editor's body
  cards are addressed by.
- **Namespaces become sayable.** Today a configuration writes `xmlns="…"` as text and the
  engine has no idea; under events, prefix bindings are declared and scoped.
- But it is a **second output vocabulary**, and the first does not go away: text output is how
  every existing configuration works, and configurations that emit non-XML (the `xml_to_json`
  fixture writes JSON) must keep working.
- **The sink is a state machine; the instructions are not** (S8, N2). `OutputSink` gains
  `startElement`/`endElement`, `startAttribute`/`endAttribute` and `namespace`; `write` keeps
  its signature and its callers. What a `write` *means* is decided by the innermost open
  container: at document level it is raw bytes, inside an element it is content, inside an
  attribute it is the attribute's value. The byte sink escapes content and attribute values
  as it serialises (and is byte-transparent for a configuration that never opens a container,
  which is every configuration today). The event sink accumulates attribute bytes into the
  `Attributes` object, fires `startElement` at the first content or child element, and
  forwards content as `characters()`. No logic lands in `Text` or `ValueOf`; the executor
  already knows the nesting, and the sink is told. The alternatives tried and withdrawn on the
  day: a `characters` wrapper instruction (a new instruction whose only job is to say what
  the container already says), and reinterpreting `text` in the model (the same fact, encoded
  in the wrong layer).
- **`element` and `attribute` are containers** (N1, N3). Their bodies are their content; the
  end event is emitted when the body finishes. There is no close instruction, so there is no
  unclosed element — S4's balance rule becomes a property rather than a check, and the
  migration's `<record>` around its children is written as `element record { apply-templates }`,
  which is what it always meant. `attribute name { value-of … }` is the same shape one level
  down. An element cannot be opened in one template and closed by a sibling; nothing in the
  corpus does that, and the configuration that did would be unreadable.
- **`namespace` is a leaf**, and both it and `attribute` must precede content in their
  element's body. The compiler checks the direct ordering; the sink enforces the rest at
  runtime, because content can arrive through `apply-templates` from a template the compiler
  is not looking at, and the error is reported against the instruction that wrote it.
- **Document-level bytes in a structured configuration** are raw on the byte sink, as now. On
  the event sink there is no root to put them in: whitespace is dropped, anything else is an
  error against the instruction. A configuration with no structured instruction at all never
  reaches this — S7 parses and forwards it whole.
- And it lands on a hard constraint: **twenty-odd fixtures pin their output byte for byte.**
  A serialiser for structured emitters must reproduce today's bytes exactly — attribute order,
  self-closing versus paired, whitespace, entity choice (`&#34;` versus `&quot;` — the
  migration emits the former) — or the goldens move, and every move must be argued.

### Option C — B on top of A, phased

Ship A to unblock the pipeline element, then add B and migrate the vocabulary onto it, with A
remaining the path for configurations that emit text (including non-XML ones).

This is the recommendation, and §9 phases it. The reasoning is that A is worth having *even
if B lands the next day* — it is the only path that ever works for a configuration whose
output is not XML at all, and the `xml_to_json` fixture is that configuration.

---

## 5. Namespaces, which text output lets everyone ignore

A text-emitting configuration writes `xmlns="event-logging:3"` as characters. Under events the
same fact is `startPrefixMapping` with a scope, and three questions appear that have no
current answer:

1. **Who declares?** An `element` instruction naming a namespace, with the prefix chosen by
   the engine; or explicit `namespace` instructions the author places.
2. **What scope?** Prefix bindings are naturally per-element; shapeshifter's templates nest by
   dispatch rather than by element, so a template that opens an element in one instruction and
   closes it in another (which the corpus does — see `Ds3Migration`'s `<record>` pairs) has an
   element scope that spans instructions.
3. **What about the fixed pair?** DS3's output always binds the default prefix to `records:2`
   and `xsi`. A migrated configuration should keep doing exactly that.

**Ruled (S3, S4).** An `element` instruction names its namespace URI and the engine chooses or
reuses the prefix; an explicit `namespace` instruction exists for authors who need a particular
prefix — because downstream XSLT may match on it — and the migration uses it to keep DS3's fixed
pair. Elements balance within the **dispatch subtree**: an element opened in a template is
closed by the time that template's dispatch, `apply-templates` children included, returns.
With `element` a container (N1) that is not a check but a property — the body *is* the
subtree — and it is the shape the corpus already has. Prefix scope follows element scope, so
a `namespace` declared in an element body is in force for that body and no longer.

---

## 6. Attribution under events, and the editor

`onOutput` reports byte offsets. Under events there are no bytes. Three shapes:

- **Event ordinals.** A span becomes "events 12–19". Honest, and the editor's model survives
  unchanged — it already thinks in spans and never in bytes as such.
- **Synthesised offsets.** The event sink keeps a notional serialised position so the existing
  contract holds. Cheap for the UI, but it invents a number that corresponds to nothing.
- **Both, by target.** Bytes when writing bytes, ordinals when writing events, with the
  `Instrument` contract widened to say which.

This reaches design 18: the output pane colours by span, and the whole four-way hover link
(input ↔ template ↔ dispatch ↔ output) is keyed on those spans. Whatever is chosen, the
preview endpoint must be able to answer in the same currency for both targets, because the
editor is one editor.

---

## 7. What the DS3 importer should emit

Under option B the migration stops assembling `<data name="…" value="…"/>` as a fused
`RefExpression` and emits an `element` with two `attribute` instructions. That is strictly
better — `escapeCaptures` and its generated variables disappear and the "either the whole tag or nothing" property
that the fused expression exists to guarantee comes free, because an element is atomic.

The constraint from §4 applies with full force here: the nineteen vendored legacy fixtures and
the eighteen native ones pin their bytes against Stroom's own DS3 (the `projects/` family —
`win_sec`, `apache_httpd` — is hand-written text and phase 3 does not touch it), so the
structured path must serialise to the same bytes the fused expression produces today, down to
`&#34;`. If it cannot, the goldens change in one commit
that says exactly which bytes moved and why — and the DS3 comparison, which is what those
goldens are *for*, has to be re-argued rather than quietly rebased.

---

## 8. What this does not decide

- **SAX as input** ([E30](../stroom-shapeshifter-engine/ISSUES.md)) is a separate question with
  a separate hard part (what byte image an event stream has). The two are symmetric only in
  name.
- **Whether the pipeline element is a parser or a filter.** This document assumes parser —
  bytes in, events out — because that is what `AbstractParser` provides for and what a feed
  needs.
- **The output sink's third implementation.** E15 asks what else there might be; answering SAX
  does not close that, it just stops it blocking.

---

## 9. Phasing

**Phase 1 — the event sink by serialisation.** An `XMLReader` implementation over
`Shapeshifter.run`, parsing the engine's bytes and forwarding events, with errors mapped onto
`ErrorReceiver` through the same locator work D10 already owes. No engine change beyond the
element. *Exit: a shapeshifter parser element in a pipeline produces the same events the DS3
element does for a migrated configuration, proven against the legacy family.*

**Phase 2 — structured emitters in the model.** `element` and `attribute` (containers) and
`namespace` (a leaf) in `OutputNode`, the codec, the compiler and the executor; the structural
calls on `OutputSink`, with `write` interpreted by container in both implementations, and the
ordering check of §4B; a byte serialiser that reproduces
today's output exactly; an event sink that forwards natively. Text instructions keep working.
*Exit: a hand-written configuration emits `event-logging:3` through both sinks, byte-identical
on one and event-identical on the other.*

**Phase 3 — the migration moves onto them, and the escaping retires.** `Ds3Migration` emits
structured data elements; `escapeAttribute` and `escapeCaptures` both go; the
fixtures prove byte-identity or the goldens move with an argument. *Exit: no configuration in
the corpus escapes XML by hand.*

**Phase 4 — attribution.** Whichever §6 shape is ruled, plumbed through `Instrument` and the
preview payload, with design 18 updated in the same commit.

---

## 10. Decisions — ruled 2026-09-03

All ruled by the user; the sections above are rewritten to the rulings. S1–S7 as the draft
recommended. S8 was not in the draft — it surfaced when the rulings were put — and it moved
twice in one sitting: first "text is reinterpreted as character data under an element", then
"a `characters` wrapper instruction, text untouched", then the resting place — text untouched
*and* no wrapper, because the executor already knows the container and the sink can be told.
The distinction that settled it is which layer holds the fact: not the instruction, the sink.
N1–N3 followed from the middle position and N3 was re-ruled with S8.

1. **S1 — Both, phased** (§4C, §9). Parse-and-forward first, because it is the only path that
   ever works for non-XML output and it unblocks the pipeline element now; structured emitters
   second, because escaping and well-formedness belong at the instruction.
2. **S2 — Structured emitters serialise byte-identically to today's text path**, and that is
   the phase-2 exit criterion. Attribute order, self-closing versus paired, `&#34;` versus
   `&quot;`: a difference is a golden change to be argued, never absorbed. The goldens are the
   DS3 comparison; they do not get rebased for convenience.
3. **S3 — Element-declared namespaces with engine-chosen prefixes, plus an explicit
   `namespace` form** (§5). Migrated configurations keep DS3's `records:2` / `xsi` pair by
   spelling it.
4. **S4 — Elements balance within the dispatch subtree** (§5), not the template body and not
   only the document. Compile-time, and the migration's `<record>` pairing is already this.
5. **S5 — Event ordinals when writing events, byte offsets when writing bytes** (§6). One
   `Instrument` contract that names its currency; the editor's span model survives; the byte
   path keeps the real offset it has.
6. **S6 — The deployment chooses the target.** A configuration says what it emits; the pipeline
   element or the file sink says where it goes, and the engine never refuses. This is what
   makes bridging optional rather than a mode a configuration opts into.
7. **S7 — A text-emitting configuration in a SAX pipeline is parsed and forwarded, permanently.**
   Phase 1's path is not scaffolding; it is the answer for every configuration that never
   adopts structured emitters, with parser errors mapped onto `ErrorReceiver`.
8. **S8 — The sink interprets `write` by the container it is in; `text` and `value-of` are
   untouched** (§4B). Ruled three times on the day; the first put the fact in the model, the
   second added an instruction to carry it, the third put it where the executor already has
   it. No `characters` instruction.
9. **N1 — `element` is a container with a body; the end event is emitted by construction.**
   Not flat `start-element`/`end-element` pairs, and no escape hatch for cross-template
   close. S4 becomes a property.
10. **N2 — Mixing needs no rule of its own.** Bytes inside a container are content or value;
    bytes at document level are raw on the byte sink and, on the event sink, whitespace or
    an error against the instruction. A configuration with no structured instruction is S7's
    case. The whole-configuration switch and the per-subtree mode were both withdrawn as
    stating in the model what the sink already knows.
11. **N3 — `attribute` is a container like `element`; `namespace` is a leaf**; both precede
    content in their element's body, compiler checking the direct order and the sink the
    rest. Re-ruled from expression-valued when S8 settled: one shape for every container.
