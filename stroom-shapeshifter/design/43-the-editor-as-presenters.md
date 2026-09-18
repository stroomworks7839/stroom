# Design 43 — The editor as GWT presenters: one document, two mounts

*Proposed 2026-09-17, from the owner's request: "now we need to start designing the UI properly
as a GWT presenter. We need to know how to present it as a document type on its own and how
it will fit into stepping mode. I presume we already have an AbstractDoc to hold the config?"
Q1 and Q7 of §8 ruled and built the same day (D58); the rest open.*

Design 18 is the design of the editor — what it shows, how a frame is navigated, what the
workbench edits — and §6 of it placed the work in Stroom as three phases. This design is
the next level down: the presenter tree that realises design 18 in Stroom's GWT MVP stack,
the client-side model those presenters edit, the endpoints they call, and the two seams in
Stroom's stepping that let the same widget mount a second time inside a pipeline step. It
does not reopen design 18's rulings; where one bears on a choice here it is cited.

## 1. What exists, and the answer to the question

Yes — `ShapeshifterDoc` exists, and it is the right document:

- `stroom-core-shared/…/shapeshifter/shared/ShapeshifterDoc.java`: `extends
  AbstractEmbeddableDoc implements HasData`, `TYPE = "Shapeshifter"`, fields `description`,
  `data` and `embeddedIn`. `data` is the project JSON exactly as `ProjectReader` reads it —
  the engine's own format, the one fixtures and the DS3 migration write.
- `ShapeshifterResource` (`/shapeshifter/v1`): `fetch`, `update`, `create`. Server side in
  `stroom-shapeshifter-pipeline`: `ShapeshifterStore`/`Impl`, `ShapeshifterSerialiser`,
  `ShapeshifterResourceImpl`, `ShapeshifterModule`; the registry entry
  `DocumentTypeRegistry.SHAPESHIFTER_DOCUMENT_TYPE` (group *Transformation*, borrowing the
  TextConverter icon for now).
- Two pipeline elements, `ShapeshifterParser` and `ShapeshifterFilter`, both declaring
  `ROLE_HAS_CODE` and `ROLE_MUTATOR`, both `SupportsCodeInjection`, both with a
  `@PipelinePropertyDocRef(types = ShapeshifterDoc.TYPE, canEmbed = true)` property. Engine
  messages already reach the `ErrorReceiver` with line:col through `InputLocations` — the
  item design 18 §6 listed as D10's open work has landed.
- App tests `TestShapeshifterParser`/`Filter`/`Lookup`.

`HasData` is not incidental: it is what stepping's code injection rides (the dirty editor
text travels as `Map<elementId, code>` and arrives at `setInjectedCode(String)`), and
`AbstractEmbeddableDoc` is what `canEmbed = true` needs. `TextConverterDoc` has exactly this
shape, and its plugin and presenter are the template for ours.

What does not exist is any client: no `DocumentPlugin`, no presenter, no gin module, and
`stroom.shapeshifter.shared` is not inherited by `App.gwt.xml`, so the doc and resource are
not GWT-compiled at all today. The consequences are concrete. The document cannot be opened
from the explorer. And in stepping, `ElementPresenter.load` resolves the element's document
through `findElementDoc` and then `DocumentPluginRegistry.get("Shapeshifter")` — with no
plugin registered, a Shapeshifter element in a stepping tree cannot load its code pane.
Everything in stepping is otherwise generic: `ROLE_HAS_CODE` gets an Ace pane, `HasData`
gives it text, `SupportsCodeInjection` gives edit-and-re-step. **The plugin alone makes
stepping work** — JSON in Ace, record input, XML output, indicators — which is why phase A1
in §7 is a day, not a design.

## 2. The one real decision: what the client edits

Every presenter in §3 edits *something*; the choice of that something shapes all of them,
and it was the question the survey had to settle first. Three candidates, and the first
draft of this section recommended the wrong one — recorded here because the reasoning
that corrected it is the reasoning a reader needs.

**Share the engine's model with the client.** The engine's `config` package —
`Project`, `Template`, `MatchExpression`, `PatternNode`, `OutputNode`, `Condition`,
`Declaration`, `CaptureBinding` — is the one model, and `config/json` is its one reader and
writer. The draft rejected compiling it into the client on evidence: GWT 2.13's
`SourceLevel` ends at `JAVA17` (read from the compiler jar), and `config/json` was written in
Java 21 — `switch` type patterns on every sealed family — against Jackson 3's `JsonNode`,
with `java.util.UUID` ids. The owner asked whether the config *had* to be switch patterns,
and measuring answered: the **model** itself had none — its only non-17 items were `UUID`
and two `getFirst()` calls — and all 78 pattern arms were in the JSON **writers**, where
they were a convenience in 18 methods. The engine's compiler switches over the model with
patterns in four files, and is unaffected: a sealed record family compiled at release 17 is
still sealed for a Java 21 caller. So the constraint is not "the engine at Java 17" but "the
data model and its mapper at Java 17, JDK only" — 4,500 lines where nothing needed more.

**A shared DTO model in `stroom-core-shared`, Stroom style.** Jackson-annotated classes
mirroring the engine's JSON form, decoded by RestyGWT. Two things count against it. The
format is polymorphic by *wrapper object* (`{"regex": {…}}`) with bare-string shorthands
(`"source"`, `"is-first"`) that RestyGWT's codecs do not decode. And a DTO drops what it
does not model, while design 18 Q4 ruled the forms editor ships *partial* — a model that
discards the body of every template on its first save is not an escape hatch.

**A typed façade over the JSON tree.** The draft's recommendation, now withdrawn: a view
over `JSONValue` nodes passes unknown content through, but it is a second reading of the
format, and it exists only because sharing the model looked impossible.

**Ruled: share the model.** It is strictly better once it is possible. One typed model, one
reader and writer, already pinned by every fixture. Q4's concern is answered *better* than
the façade answered it: `ProjectJson` is the whole vocabulary, so nothing is ever unknown —
the phase A editor edits some of a `Project` that holds all of it. Records are immutable, so
edits are `with`-copies up to the root, which makes Q10's undo a stack of `Project`
snapshots. The client's `data` text is `ProjectJson.writeProject(project)` printed.

**Built the same day**, as `stroom-shapeshifter-config`, the engine depending on it (`api`):

- The model and `config/json` moved there as `stroom.shapeshifter.config`; `Severity` with
  them (the model's `emit_error` names one). Not `stroom.shapeshifter.shared.config`, the
  GWT idiom — the owner accepted the plainer name on 2026-09-17; the module file
  `ShapeshifterConfig.gwt.xml` names the package, and `App.gwt.xml` inherits it in A2. `ProjectReader` (Jackson) and `PatternExplode`
  (regex library) stayed in the engine at its top level.
- The mapper is written against a JSON tree of the module's own — `JsonValue` with
  `JsonObject`, `JsonArray`, `JsonString`, `JsonNumber`, `JsonBoolean`, `JsonNull` — and each
  edge adapts its parser's tree in forty lines: `ProjectReader` adapts Jackson's; the client
  will parse text itself (§5). `JsonNumber` keeps the **literal** because a literal's spelling
  is its declared type (design 17 §8) — `80` is whole, `80.0` fractional — which the fixture
  round trip caught the first time the tree collapsed them, and which is why the client
  cannot use `JSON.parse`, whose every number is a double.
- The writers' switches became `instanceof` chains with a trailing `throw`; template ids
  are `String` (`Instrument`, `InputLocations` and the DS3 migration followed); `getFirst()`
  became `get(0)`.
- The module's `check` enforces the promise twice: a release-17 compile for the language,
  and a **GWT compile** of the module from a spike entry point that reaches the whole
  mapping (`src/gwtSpike`) for the emulation. The GWT compile found two things javac cannot:
  GWT 2.13's JDT does not infer `permits` for a sealed interface nested in a record
  (`CaptureSource`, `RefPart` — now explicit), and it loses the body of an implicit canonical
  constructor that another constructor delegates to with `this(...)` (`RefPart.Capture` —
  now spelt out). Both are in the source with their reason. The zero-dependency check the
  regex module makes is made here too, with one more reason: a runtime dependency would
  have to be GWT-compilable, and almost nothing is.
- The corpus is the pin: every engine fixture reads, writes and reads again equal through
  the new tree; the pipeline, xmlbench and app suites pass unchanged. (One app test,
  `TestShapeshifterLookup`, had been failing since design 35's declaration rule landed on
  14 Sep without the test's `when` being declared; it is declared now.)

Everything that needs the engine's *understanding* of the model — validity, pattern facts,
explode, the tree-to-regex printer, the run itself — stays on the server behind endpoints
(§5). This is the standing rule that pattern facts have one source, applied to the whole
model: the client renders and edits, it never interprets.

## 3. The document presenter

Stroom's groove, copied from TextConverter and Pathways:

```
stroom.shapeshifter.client
  ShapeshifterPlugin                 DocumentPlugin<ShapeshifterDoc>: load/save/create via the resource
  gin/ShapeshifterModule             bindPlugin + bindPresenterWidget for every presenter below
  gin/ShapeshifterGinjector          listed in AppGinjectorUser beside PathwaysGinjector
  presenter/ShapeshifterPresenter    DocTabPresenter<LinkTabPanelView, ShapeshifterDoc>
stroom/shapeshifter/Shapeshifter.gwt.xml   <source path="client"/> <source path="shared"/>; inherited by App.gwt.xml
```

`ShapeshifterPresenter`'s tabs, in order: **Design**, **Source**, **Documentation**,
**Permissions**. No Settings tab (§8 Q5): the description is the Documentation tab as for
every other doc, and everything else a settings tab might hold — encoding, buffer size,
source configuration — is part of the project and belongs in the Design tab's source panel.

**Design and Source edit one model.** This is the one place the presenter differs from
TextConverter's, where each tab writes a disjoint field. Both of our tabs write `data`, so
the presenter owns a single `Project` (the engine's own record, §2) and the two tabs are views
of it:

- The Source tab is an `EditorPresenter` in `AceEditorMode.JSON`. Its text is the printed
  `Project` on entry; an edit re-parses on the debounce, replacing the tree when the
  text parses and leaving the previous tree with a "source has a syntax error at line n"
  banner on the Design tab when it does not. Ace's format action becomes "canonicalise":
  a round trip through the `validate` endpoint that returns the engine's own pretty form.
- The Design tab is `ShapeshifterDesignPresenter` (§4) over the same model.
- `onWrite` from either tab is `document.copy().data(print(project))`; dirtiness is the
  document presenter's by comparison, as today.

## 4. The Design tab: the presenter tree

One presenter per widget design 18 §5 names, so that each can be built, styled and tested
on its own and so that the stepping mount (§6) can take the whole tree by taking its root.
`PresenterWidget<View>` throughout; layouts are Stroom's `ThinSplitLayoutPanel`s; the three
bespoke DOM renderers are marked.

```
ShapeshifterDesignPresenter                    root; owns the Project, the run, undo (Q10), selection state
├─ TemplatePanelPresenter                      left: templates and modes — add, rename, delete, swatch,
│                                              mode membership; per-template attempt/match counts (§5.8)
├─ TemplateWorkbenchPresenter                  centre-top: the selected template
│  ├─ MatchEditorPresenter                     tabbed by match kind: regex | pattern tree | parts | native (42)
│  │  ├─ RegexTabPresenter                     pattern text, flags, groups from patternInfo, "explode" button
│  │  ├─ PatternTreePresenter                  nested node editor over the design 38 vocabulary (bespoke DOM);
│  │  │                                        live regex rendering from the print endpoint; std library, read-only
│  │  ├─ PartsPresenter                        the match sequence: pattern / take / seek / read (design 39)
│  │  └─ NativeMatchPresenter                  design 42 arms — format, select route, fields (phase 42.x)
│  ├─ DeclarationsPresenter                    scalar/list/map/set with scope (design 35) — new since the mockup
│  ├─ CapturesPresenter                        label or group → declaration, with cast and codec
│  ├─ GuardAndLimitsPresenter                  guard condition, min/max/only match
│  └─ BodyPresenter                            the card list (phase B): text, value-of, apply-templates,
│                                              conditionals as nested lists, collection ops; drag within a
│                                              list and into or out of a branch (Q13), keyboard equivalents
├─ FrameNavigatorPresenter                     centre-bottom (phase B)
│  ├─ BreadcrumbPresenter                      §5.3 — the crux widget, hover-revealed sibling arrows (Q5)
│  ├─ FrameHeaderPresenter                     template, match n of m, span or "no byte span" (design 42)
│  ├─ ContentPanePresenter                     §5.4 — bespoke DOM renderer; capture tint only (Q15),
│  │                                           this-level click, ctrl-click deep (Q16), all descendants (Q8)
│  └─ VariablePanesPresenter                   §5.5 — values at this frame, typed badges (18 §7 item 5)
├─ MessagesPresenter                           §5.8 — messages with paths; profiling always on
└─ SampleSourcePresenter                       document mount only: stream/part/record picker (Q2 — never stored)
```

Phase A builds `TemplatePanelPresenter`, `MatchEditorPresenter` with its first three tabs,
`DeclarationsPresenter` and `CapturesPresenter` — the parts of the config users edit
constantly (Q4) — plus `MessagesPresenter` fed by `validate`. `BodyPresenter` and the
navigator are phase B, where the trace sits beside them.

Two conventions the tree relies on. **Selection is one object** — the selected template,
the selected frame, the selected op path — owned by the root and published by a
`SelectionChangedEvent` on the presenter's own event bus, so a click in the content pane
selects a template in the panel without the two knowing each other. **Edits are replacements** —
the model is immutable records, so every edit is a new `Project` handed to the root, which
keeps the previous one: undo/redo (Q10) is a stack of projects, the debounced auto-run (Q6)
has one place to hook, and live commit with Escape revert (Q12) is a pop.

### 4.1 Stock or new: the widget inventory — 2026-09-18

A2's first cut used Stroom's stock grids and form groups for every surface, and on the
screen that read as stock Stroom — pager bars, column headers, help buttons — not as the
mockup. The owner asked which widgets and layouts can be stock and which are new, and for
the new ones to be built the standard GWT way. Surveyed against `18b-event-xml-trace-editor.html`'s
markup, render functions and 290 selectors; "new view" is a `ViewImpl` with its own `ui.xml`
and row `Composite`s, "new renderer" is `SafeHtmlTemplates` with delegated events for content
too dense for widgets (the tree editor already works this way). Presenters, binders and the
theme variables throughout; the mockup's CSS is written against Stroom's variables and lifts
nearly verbatim under an `ss-` prefix.

| surface | stock | new |
|---|---|---|
| splitters, the frame | `ThinSplitLayoutPanel`, nested | — |
| header, Run, toolbars, dialogs, tabs, menus, Ace editors, messages grid | yes | — |
| template panel list: rows with chip, name, count, heat track; mode headers | — | view + `TemplateRow` |
| template strip header and match line (chip opens the workbench; guard and limits summary) | — | view |
| breadcrumb, input pane, output pane | — | renderer (B) |
| variables pane | — | view, row composites (B) |
| body cards, add line, add menu, per-kind editors, drag | — | view (B) |
| workbench: sample and live matches | — | renderer, needs a `match` endpoint (B) |
| workbench regex tab: pattern map, groups panel, details | — | renderer + view (groups panel is the capture editor) |
| workbench tree tab | built | restyle rows |
| guard clauses, limits | — | view, inline rows |
| add/edit template dialog | `FormGroup`s | `ColourPalette` composite |
| mode editor dialog body | — | view |
| declarations, captures | grids without paging, until the strip has a home for them | — |

**The frame is the larger gap.** The mockup is a quadrant — panel · (crumb / input | variables)
over (strip | output) — and the workbench replaces the crumb, input, variables and strip cells
in place when opened from the match chip. A2 had no trace, so it put the workbench where the
strip goes and had no other cells; phase B would then have had to re-lay the tab out. Ruled:
adopt the frame now, every cell present, the trace cells showing design 18 §5.7's empty
states until B fills them, the workbench opening in place. With nested split panels the two
rows' vertical splitters are independent where the mockup ties them to one line; the
workbench takes the top row's space as well as the strip's, so the output pane grows to full
height while it is open — both accepted.

### 4.2 Model coverage: what the forms could not reach — 2026-09-18

With phase A's workbench built, the owner asked which items of the model the UI still could
not edit except as Source text. Inventoried against `ProjectJson.readTemplate` and the
`OutputNode` vocabulary:

| model item | had | now |
|---|---|---|
| `Template.param`, `encoding`, `ignore_errors` | nothing | the template dialog: params as `name = default` lines, encoding, ignore errors |
| match kinds `delimiter`, `source`, `all`, `named` | the Other tab's wire form | the Other tab is a **kind picker** — source, all, named, delimiter with its four fields — over the wire form, which stays for what it is: a view of any match |
| `Template.body` | nothing | **`BodyPresenter`**, the card list of 18 §5.6 *without* the trace's annotations: cards with kind and summary, containers holding card lists per branch with their own add line, move and delete by button (drag and the keyboard moves are B), *+ instruction* grouped output · invoke · control · transform · collection; each card's editor is per kind for the kinds an author writes constantly — text, value-of, apply-templates, call-template, element, attribute, variable, namespace, emit-error, if, choose, switch, for-each, for-each-group, the five collection ops — and the wire form for the thirty transforms, with the same rule as the guard: a reference is a name or a function in a field, and a card whose reference is a path is edited as wire form. Conditions on `if`, `when` and the guard share one clause editor. Phase B adds what a card shows about the run, not what it edits. |
| capture sources `select`, `key-value` | wire form in the dialog | unchanged, deliberately: a reference has no text syntax in the model, only its wire form, and a builder for `parts` would be a second language |

The body's path addressing follows the tree editor's: a card is addressed by its index at the
top level, then by *(branch, index)* pairs into a holder's `bodies()`, and every edit is a
rewrite returning a new body (`Bodies`), the label-through-rewrite convention of `PatternNodes`
applied to holders keeping their heads.

## 5. Endpoints and wire types

Additions to `ShapeshifterResource`, all `POST`, all taking the project as JSON text (the
model's wire form; never a doc ref, so the unsaved document can be checked):

| endpoint | in | out | backed by |
|---|---|---|---|
| `validate` | project text | messages with paths, canonical text | `ProjectReader` + the compiler, no run |
| `patternInfo` | pattern, flags | validity, error, groups and labels, explain, ambiguities | `PatternInfo.inspect`, `BytePattern.explain()` |
| `explode` | pattern | pattern tree JSON | `PatternExplode` |
| `print` | pattern tree JSON | regex text | `PatternPrint` — the inverse (§8 Q2), built |
| `library` | — | the standard library: name and the regex each entry means | `PatternPrint.library()` over `Matchers.standardLibrary()` |
| `preview` | project text, sample text | `ShapeshifterTrace`: frames, captures, output spans, attempts, timings, messages, input and output | `Shapeshifter.runWhole` with `TraceRecorder`. **Built 2026-09-18** over a supplied sample; the `SourceLocation` form — the sample read from the stream store under the caller's permissions — comes with `SampleSourcePresenter` |

`preview` reads the sample from the stream store by `SourceLocation` (meta, part, record)
under the caller's permissions — Q2's "supplied, never stored" — and runs one record whole.
It carries per-template timing always (18 §5.8).

The client parses and prints the project text with a small parser and printer **in the
config module** (JDK only, a few hundred lines, to be built in A2): GWT's `JSONParser` hands
back doubles, and `JsonNumber` needs the spelling (§2). The same printer gives the Source
tab its text, so the document reads the same on both sides of the wire.

`TraceModel` and its parts are ordinary Stroom shared classes (they are new, fully modelled,
and never partial, so the DTO objection in §2 does not apply): `Frame(id, parentId,
templateId, matchIndex, depth, span-or-content)`, `Capture(frameId, name, value, type)`,
`OutputSpan(frameId, offset, length, unit)`, `Attempt(templateId, offset, matched)`,
`Message`, `TemplateTiming`. Its shape is the same in the document mount and the stepping
mount (§6) — one recorder serialises it for both. Design 18 §7's engine asks are the
prerequisites and stand as written: explicit parent identity on `onMatch` (Q3, not yet
built — the signature still carries only `depth`), content for every frame with a
slice-of-parent form (G2), failed attempts with positions (G3), typed capture values.

## 6. The stepping mount

Design 18 §6 phase C and Q7: the navigator *replaces* the generic Input/Output/Code panes,
Log stays. Two seams make that possible without teaching `stroom-core-client`'s stepping
anything about Shapeshifter.

**Client seam — the element editor is chosen by document type.** Today `ElementPresenter`
is both the chassis (resolve the doc, load it through the plugin, save it, track dirt,
inject code, show the log) and the content (three Ace panes). Split them:

```
SteppingEditor                    interface: read(Document) / write(): Document / getCode()
                                  setStepData(SharedElementData) / setIndicators / setReadOnly / ChangeHandler
SteppingEditorRegistry            docType → Provider<SteppingEditor>, like DocumentPluginRegistry; a default
DefaultSteppingEditor             today's code + input + output panes, extracted unchanged
ElementPresenter                  keeps the chassis; ElementView gains setEditorView(View) beside the log
```

The Shapeshifter gin module registers `ShapeshifterSteppingEditor`, which is
`ShapeshifterDesignPresenter` in its second mount: the same tree as §4 with
`SampleSourcePresenter` absent (the step *is* the sample) and the run trigger being the
step rather than the debounce. `getCode()` is the printed `Project`, so edit-and-re-step
carries the edited project exactly as it carries edited XSLT today. Save goes through the
chassis to the document plugin as now.

**Server seam — a per-record detail payload.** The stepping store captures, per element per
record, `input`, `output` (text or replayable SAX events) and `indicators`, in
`CapturedElementData`, mapped to `SharedElementData` on read. `ElementMonitor` already
exposes the live element so that per-record state which is not IO can be read at capture
time — `SteppingCounter` is the precedent. Add one more marker in the same package:

```java
public interface SteppingDetail {
    /** This element's detail for the record just processed, as JSON text, or null. Cleared per record. */
    String captureDetail();
}
```

`CapturedElementData` and `SharedElementData` gain `String detail`; `ElementMonitor`
captures it when the element implements the marker; the store serialises it with the rest.
Both Shapeshifter elements implement it: when they are running under stepping they run with
the recording `Instrument` and answer with the record's `TraceModel` serialised — the same
JSON `preview` returns. Production runs never see the recorder (D35's decoration rule);
the cost is a recorder per stepped record, which is the cost of stepping.

The navigator in the stepping mount then has everything the document mount has: the
record's bytes are `SharedElementData.input` (the root frame's content), the output text is
`SharedElementData.output`, and the trace with its output attribution in `EVENTS` ordinals
(design 20 §5) is `detail`. Indicators keep flowing through the `ErrorReceiver` as now, so
the pipeline tree's severity colouring and the Log pane are unchanged.

Rejected alternative: have the client call `preview` with the step's record. It re-runs the
record outside the pipeline, without the element's properties, reference data or the
pipeline's own error handling — a different run pretending to be the same one.

**The same widget, two mounts** — what differs and what does not:

| | document | stepping |
|---|---|---|
| sample | `SampleSourcePresenter`, `preview` | the step's record, `detail` |
| run trigger | debounced on edit (Q6) | the step; an edit re-steps through the chassis |
| save | `DocTabPresenter` | `ElementPresenter.save` → the document plugin |
| read-only | doc permission | pipeline permission, as for XSLT today |
| messages | `MessagesPresenter` | `MessagesPresenter` **and** indicators in the tree and Log |
| embedded doc (`canEmbed`) | n/a | the chassis already handles "edited copy of an embedded doc" |

## 7. Phases

Aligned with design 18 §6 and split where the survey found the seams; each independently
shippable.

- **A1 — the plugin.** `Shapeshifter.gwt.xml`, `ShapeshifterPlugin`, `ShapeshifterModule`,
  `ShapeshifterGinjector`, `App.gwt.xml` and `AppGinjectorUser` wiring, an icon of its own,
  `ShapeshifterPresenter` with Source (Ace JSON), Documentation and Permissions. The document
  opens; stepping works with JSON in the code pane. **Built 2026-09-17**, in
  `stroom-core-client/…/shapeshifter/client` on the TextConverter groove; the icon is the
  owner's, 2026-09-18: a square, a circle and a triangle over one another in three colours
  (`document/Shapeshifter.svg`, `SvgImage.DOCUMENT_SHAPESHIFTER`), replacing the square fused
  into a circle (which replaced a square-to-circle outline that read as a letter D at 16px).
- **A2 — the model on the client and the forms.** `ShapeshifterConfig.gwt.xml` inherited by
  `App.gwt.xml`; the config module's JSON text parser and printer; `validate`,
  `patternInfo`, `explode`, `print`; the Design tab with `TemplatePanelPresenter`,
  `MatchEditorPresenter` (regex, pattern tree, parts), `DeclarationsPresenter`,
  `CapturesPresenter`, `MessagesPresenter`; Design/Source sync over one `Project`.
  **Built 2026-09-17/18.** Server: `JsonText` (the config module's own parser and printer —
  `ProjectReader` prints through it now, so both sides of the wire read the same),
  `PatternPrint` pinned by explode∘print over the 298-regex corpus, the four endpoints on
  `ShapeshifterResource` with `ShapeshifterValidation`/`PatternInfo`/`PatternRequest`/
  `Text`/`Message` as the wire types, `explain` on the engine's `PatternInfo`. Client, in
  `stroom-core-client/…/shapeshifter/client`: `ShapeshifterDesignPresenter` as the root and
  `ProjectHost` — the one way an edit lands, a replacement `Project`, published to the
  document presenter as a value change; the panel with its toolbar (add, edit, remove, up and
  down — order is dispatch priority) and a topmost *project* row that opens
  `SourceConfigPresenter`, the source panel Q5 promised; the workbench with the match editor
  tabbed **Regex** (pattern, flags, advance, the engine's groups and plan live) / **Pattern
  tree** / **Parts** / **Other** (the kinds without a form, as their wire form), a tab change
  converting through `explode` and `print` where the engine can and refusing where it cannot;
  `DeclarationsPresenter` and `CapturesPresenter` as grids with dialogs, a capture's
  `select` and `key-value` sources edited as their wire form and read by the one reader;
  `MessagesPresenter` fed by `validate` on a debounce, the Source tab's syntax error as its
  first row. An empty document opens as an empty version-5 project. Verified by the GWT draft
  compile, the config module's `check`, the engine and pipeline suites, and a JVM test of the
  client's text edge (`ProjectTextTest`).
  **The tree tab as a node editor, 2026-09-18:** `PatternTreePresenter` renders the tree as
  nested rows — click selects, double-click edits — with a toolbar acting on the selection
  (add child, add after, wrap, edit, remove, unwrap, up, down), every action a rewrite through
  `PatternNodes` (path-addressed, immutable, labels kept, a single-body container never left
  empty) landing on the host as a replacement match; `PatternNodeEditPresenter` is the one
  dialog for a node's kind and fields, label and cast, keeping what a node holds across a
  change of kind where the new kind can hold it; the regex the tree means is printed live,
  and the standard library a `ref` can name is listed read-only beside it and offered in the
  dialog, from a new `library` endpoint (`PatternPrint.library()`). The wire form stays
  editable in the right-hand pane.
  **The regex tab as the mockup lays it out, 2026-09-18:** pattern text over the pattern map —
  each group's span in its capture hue, from spans the parser now publishes
  (`BytePattern.groupSpans`, `PatternInfo.Group.start/end`), never read client-side — the
  error as you type, explode, the flags; then the **groups panel as the capture-declaration
  editor** (design 18 §5.6): a name beside `$n` declares it (scalar) and captures the group
  into it, blanking unbinds, the pattern's `(?<name>…)` is the placeholder; then the plan the
  engine would run.
  **Guard and limits, 2026-09-18:** `GuardAndLimitsPresenter` beneath the workbench's tabs,
  mechanism-independent as 18 §5.6 places them. The guard is clause rows — variable ·
  operator · value · as — joined by *and*, the names in scope (declarations, params, the
  functions) offered with free text allowed, the value's spelling declaring the literal's type
  (quoted for text that would read as a number); a guard the rows cannot express — *or*, *not*,
  two references, a path — is edited as its wire form. Limits are min, max and only with their
  semantics inline. The strip's summary reads the rows: `status ge 400 and user exists`.
  The live verdict against the current frame waits for the trace.
  **The rest of phase A, 2026-09-18:** colour overrides as editor metadata — `ShapeshifterDoc`
  gains `colours` (template id → colour) beside `data`, chosen from a `ColourPalette` in the
  template dialog, read by the panel and strip before the palette's position, dirtying the
  document without touching the project; the **mode editor** — modes exist through their
  templates and apply sites, so it renames (both follow, into every branch of every holder)
  and removes empty modes (their sites become root dispatches, with a warning), while a mode is
  created by giving it to a template, whose dialog now offers the existing modes with free
  text; the **parts tab** as rows with add, edit, remove, up and down and a part dialog
  (pattern as wire form, take and seek with a length as count, label or variable, read with a
  cast), the wire form still editable beside; and the Source tab's format action is
  **canonicalise**: the engine's own pretty form replacing Ace's when the text reads as a
  project — printed locally, since the printer is the config module's and the engine prints
  with the same one, so §3's `validate` round trip is not needed for it. Phase A of the workbench is complete but for the declarations and
  captures grids, which stay stock until the strip has a home for them; undo is phase B.
- **B — the trace.** Design 18 §7's engine asks; `TraceModel`; `preview`;
  `SampleSourcePresenter`; the navigator's four presenters; `BodyPresenter`; undo; profiling.
  **Engine side built 2026-09-18:** the `Instrument` contract answers G1–G3 (frame and parent
  ids, content as a slice of the parent or as bytes, attempts with their place),
  `TraceRecorder`, `ShapeshifterTrace` as the wire model — `Frame`, `Capture` with its type,
  `OutputSpan` with its unit, `Attempt`, `Timing` — and `preview` over a supplied sample.
  `BodyPresenter` landed with A2 (§4.2) as the editor; what B adds to it is the annotations.
  **Client side, first cut, 2026-09-18:** the server converts every offset to characters
  before the wire (`TraceChars` in the pipeline module — UTF-8 continuation bytes dropped,
  a frame's bytes found as the input, a slice of its parent's or its own), so the client works
  in strings and never in bytes. `TraceModel` is the navigator's reading of a trace: frames
  by id with the document as frame zero, children by parent, matches by template, captures,
  output span and attempts by frame, timings by template, and `content(frameId)` by slicing
  the parent's content all the way up. The root owns the sample, the trace and the **cursor**
  (the selected frame): `ProjectHost` gains `getSample`/`setSample`/`run`/`trace`/`isStale`/
  `cursor`/`setCursor`; selecting a frame selects its template, so the strip shows what the
  cursor is an instance of. The sample arrives through the content pane's empty state — a
  box to paste into, Run, Ctrl+Enter (design 18 Q2's first door; the stream picker is still to
  come) — and every edit runs the project again on a 600 ms debounce (Q6), one request in
  flight at a time, the crumb saying *stale · running…* meanwhile; without a sample the edit
  validates as before. The four cells are real presenters now: `BreadcrumbPresenter` (the
  cursor's ancestry, a sibling stepper on every segment, the whole-input stepper at the end
  labelled with the template, the sample and run buttons), `ContentPanePresenter` (the
  cursor's content as one HTML block, each child match a span in its template's hue that
  descends on click, failed attempts as gap marks, matches whose content is not a slice listed
  beneath), `VariablesPanePresenter` (this match's captures with their types, then each
  ancestor's under a heading that is a click to it), `OutputPanePresenter` (the whole output
  with the cursor's span lit and the rest dimmed, the children's spans in their hues, event-
  counted output plain with a note). The panel's rows read the timings — a count, or *0 ·
  tried n* — and the strip's header says *no matches · tried n places* or *n matches · µs*.
  **The capture tint, 2026-09-18:** design 18 §7's ask 7 answered — `onCapture` carries the
  capture's offset and length in its frame's content where the bytes are a slice of it (a
  watched whole-buffer run slices its root for it; the unwatched path is unchanged), `TraceChars`
  converts them, and the content pane paints every capture of the cursor and of every sliced
  frame beneath it in its hue, nested inside the match spans — `Mark` and the `Marks` emitter
  (outer first, straddlers clipped, pinned by `MarksTest`) serve the content and output panes
  both.
  **The strip annotated, 2026-09-18:** design 18 §7's asks 8 and 9 — `onGuard` at level
  entry and `onInstruction` from a watched frame's body run one instruction at a time (the
  hot loop untouched) — carried by `TraceRecorder`, `TraceChars` and the wire. The strip is
  annotated with one frame of its template: the cursor when it is an instance, else the
  first match. Each top-level card carries a swatch in its hue and the run's word — *wrote n
  chars*, and for a dispatch *n matches* (a click descends to the first) or *no matches* or
  *nothing applies into mode m* — and the output pane paints each instruction's output in
  the card's hue with the child matches inside. The guard line says *held at row #2; held in
  3, refused in 2 frames*.
  **The hover topology, the heat bars, history and the keys, 2026-09-18:** `Hot` is the one
  request (frame · capture · instruction · template) the root broadcasts and every surface
  answers — the content and output blocks by the attributes their marks carry (`data-frame`,
  `data-tpl`, `data-cap`, `data-instr`; `Marks.light` walks a block's spans once per request),
  the variables rows, the crumb's segments, the panel's rows, the body's top-level cards —
  the same outline in the thing's own hue from whichever end it is pointed at (§5.5).
  `Profile` turns the timings into the panel's heat bars (length the share of attempted
  time, colour the per-attempt cost against the run's) and the strip's profile line
  (attempts · matched (rate) · per attempt · total · share), the document row carrying the
  run's total (§5.8). Navigation states `(frame, template)` are recorded on every move and
  walked with back and forward at the crumb's head and `Alt+←/→`; the tab's other keys as
  §5.3 now states them. Not yet: positioned messages, the stream picker, undo, the keyboard
  moves of cards, child stores and params in the variables pane.
- **C — stepping.** `SteppingEditor` + registry + `DefaultSteppingEditor` extraction in
  `stroom-core-client`; `SteppingDetail` + `detail` through the store in `stroom-pipeline`;
  the recorder in both elements; `ShapeshifterSteppingEditor`.
- **42.x** — `NativeMatchPresenter`, when design 42's arms exist to edit.

C's `stroom-core-client`/`stroom-pipeline` changes are the only ones outside the
Shapeshifter modules, and both are pure extractions plus one optional field: the generic
stepping behaves exactly as before for every other element.

## 8. Questions for ruling

| # | Question | Recommendation |
|---|---|---|
| Q1 | The client's model: façade over the JSON tree, shared DTOs, or the engine's model compiled to GWT? | **Ruled and built 2026-09-17: the engine's model**, in its own module (§2). The draft said façade; the owner's question "does the config have to be switch patterns?" showed the obstacle was the writers, not the model. |
| Q2 | Where does the tree-to-regex printer live? Design 18 §10 called it "the UI's to build". | **In the engine**, as `PatternPrint`, the inverse of `PatternExplode`, pinned by explode∘print round trips over the 298-regex corpus; served by `print`. A client printer would be a second reading of the pattern vocabulary. |
| Q3 | The stepping client seam: a `SteppingEditor` chosen by document type inside `ElementPresenter`, or a second element presenter class chosen by `SteppingPresenter`? | **Inside `ElementPresenter`**: the chassis (doc resolution, plugin load/save, dirt, injection, log) is shared and should stay so; only the content varies. |
| Q4 | The stepping trace transport: a `detail` payload captured by the store, or the client re-running the record through `preview`? | **`detail`** (§6): the trace must come from the run the user is stepping. |
| Q5 | A Settings tab? | **No**: Documentation carries the description; everything else is project. |
| Q6 | Tab order and the default tab? | **Design, Source, Documentation, Permissions**, opening on Design once A2 lands, on Source in A1. |
| Q7 | Record the GWT source level as a constraint? | **Yes, inverted by Q1**: the config module is Java 17 and JDK only, enforced by its own `check` (release-17 compile, GWT compile, zero dependencies); the engine keeps Java 21. |
| Q8 | Build A1 first, ahead of the model work, so stepping works immediately? | **Yes**: it is a day, it unblocks every test through the UI, and A2 changes nothing it creates. |
