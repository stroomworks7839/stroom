# The editor: navigating templated execution through its trace

Status: **draft — the frame/variable model in §5 was agreed 2026-08-25 (Jon), replacing
this draft's original privileged input/output panes; the superseded framing is kept in
§5.9 as a record of why it failed. Phasing (§6) and the §9 questions remain open.**
Written 2026-08-25 from three surveys: the ds-rs Leptos editor as it actually shipped,
the ds-rs redesign document that described what it should have been, and Stroom's GWT
stepping UI as it exists today. Wireframes are in §8; an interactive HTML mockup of the
central idea is at [mockups/18-trace-editor.html](mockups/18-trace-editor.html).

## 1. Why this UI is hard, said precisely

A shapeshifter configuration is a flat list of templates dispatched by mode
(`config/Template.java`). Unlike DS3's nested split/group/data tree, the configuration has
almost no static structure to draw: which template runs where is decided by the data. Three
consequences fall out of that, and they are the whole problem:

1. **Without data the editor is dead.** A template list with patterns in it says nothing
   about whether those patterns fire, in what order, over which bytes, or what the captures
   hold. Every useful question a config author asks is a question about a *run*.

2. **"Ancestors and descendants" are properties of matches, not templates.** Template
   `kv-pair` has no parent in the config — it has a *mode*, and anything that
   apply-templates into that mode can invoke it. Its parent exists only per match: match 3
   of `kv-pair` was dispatched from match 7 of `record`. The navigation the editor must
   offer — up to the invoking match, down into dispatched matches — is navigation over a
   tree that only exists after execution.

3. **"Sideways" is navigation through data, not config.** For a given template the author
   steps through its matches — match 3 of 42 — watching captures and output change while
   the template stays still. That is the loop in which patterns actually get written.

So the design centre is not a config editor with a preview bolted on. It is a **trace
navigator with editing attached**: run the compiled project over sample data with a
recording `Instrument`, then let every pane render one shared position in the recorded
trace.

## 2. Prior art: what ds-rs built, and what it only wrote down

The ds-rs `node-editor` crate went through two generations. The canvas/node-graph editor
was retired (real configs hit 173 nodes, 89 wide — the canvas drowned). Its successor, the
template editor that actually shipped, is the right skeleton: template list grouped by
mode on the left, structured detail forms in the middle, sample data and output on the
right, a ◀ n/N ▶ stepper over matches. Its execution model is the one we should keep:
**one server round trip runs the whole project and returns a complete trace** (per
template: matched ranges, capture values, output ranges); stepping is then pure
client-side navigation, instant in both directions.

Worth keeping from the shipped editor:

- Capture colour system: per-capture hues, swatch in the capture row, tinted span plus
  coloured underline in the data view, hover a capture row → everything else dims and the
  matching span lights up.
- Per-template colour attribution over the output, with match counts.
- Body editing as a breadcrumb card list (one nesting level visible, drill buttons with
  child counts) rather than a tree widget.
- Pattern facts fetched from the engine on each edit (its `/api/regex_info`, our
  `PatternInfo.inspect`) — captures auto-synced from groups, names seeded from
  `(?P<name>...)`. The editor never parses pattern text itself; the owning parser
  publishes pattern facts. That rule holds here too.
- Per-template profiling with attempt counts — surfaced as sidebar badges.

What ds-rs specified but never built (`design/data_centric_ui_redesign.md`, the "killer
feature" sections) is precisely the part this document is for: clickable highlights in the
data that navigate to the owning template, ancestor/descendant navigation, keyboard
navigation, output colourised by producing template. And that document was written
against the *old nested-node model*, where ancestry was static. Our flat-template model
makes its navigation design unusable as-is — the tree it navigates no longer exists in
the config. §5 is that design re-derived for a world where the tree is the trace. The
ds-rs input/output pane layout itself is *not* carried forward — §5's frame model
replaces it, for reasons recorded in §5.9.

Known ds-rs defects not to repeat: highlight placement by searching for the capture's
*value* in the text (wrong span when values repeat — we have real offsets; use them);
invalid patterns failing silently; sample data not persisted with the project; the full
output never actually colourised despite the ranges being available.

## 3. Prior art: what Stroom already gives us

Stroom's stepping UI (`stroom-core-client/.../pipeline/stepping/client/`) is mature and
we should take its chassis rather than compete with it:

- **Stepping is a tab swap inside the pipeline editor**; a pipeline tree selects an
  element; `ElementPresenter` builds Code/Input/Output/Log panes from the element's
  declared roles (`ROLE_HAS_CODE`, `ROLE_MUTATOR`). Any element that implements
  `SupportsCodeInjection` gets edit-and-re-step-without-saving for free — the dirty
  editor text rides the step request in a `Map<elementId, code>`.
- Step controls (first/back/forward/last/refresh), step location `meta:part:record`,
  async stepping sessions with polling and progress, per-element severity colouring of
  the pipeline tree, Ace gutter annotations plus an overview bar for indicators.
- A new document type is boilerplate with a well-worn groove (the Pathways type is the
  freshest template): `Doc` + `Resource` in `stroom-core-shared`, an entry in
  `DocumentTypeRegistry`, store/serialiser/module server-side, plugin/presenter/gin
  client-side, `App.gwt.xml` + ginjector wiring. A steppable pipeline element is a
  `@ConfigurableElement` with the right roles; nothing else is needed to appear in the
  stepping tree with panes, indicators and filters.

What Stroom does *not* have is anything like the trace navigator: its stepping shows one
record's input and one element's output as flat text with a single highlight range. The
rich per-frame attribution view is new in kind, not just in degree.

## 4. The engine seam, and the three things it doesn't say yet

`engine/Instrument.java` is the trace source, already tested by a Recorder implementation
("keeps everything it is told, which is what an editor would do"):

| Callback | Gives the editor |
|---|---|
| `onMatch(templateId, name, inputOffset, inputLength, matchIndex, depth)` | which template matched which byte span, its per-template match number (1-based), dispatch depth |
| `onCapture(templateId, name, value, matchIndex)` | every variable binding, in execution order, per match |
| `onMatchContent(templateId, content)` | the matched bytes when they came from a variable (offset reported as `UNLOCATABLE`) |
| `startTiming()` / `stopTiming(templateId, token, matched)` | every *attempt*, including failures — the profiler and the "tried 4,012 times, matched 0" signal |
| `onOutput(templateId, matchIndex, outputOffset, outputLength)` | output attribution — which template wrote which output span |

`PatternInfo.inspect(pattern)` gives editor-time pattern facts (validity, error text,
groups with names) through the engine's own compile path. `BytePattern.explain()` and
`ambiguities()` are ready-made lint output. There is no pause/resume debugger and the
design needs none: run whole, record, navigate.

Two observations matter for §5. First, `onCapture` events arrive in execution order, so a
recorder can answer "what did `$key` hold *at the moment this match happened*" by replay —
the frame view's variable values need no new engine machinery. Second, `onMatchContent`
already concedes the central point of the frame model: matched content is a *value*, and
often has no position in any privileged source.

Three gaps between what the Instrument says and what the navigator needs. All are trace
*reporting* — none adds a layer, none touches production runs (D35's decoration rule:
editor instrumentation is a run option, not a third artifact):

**G1 — Parentage.** `onMatch` reports depth but not which match dispatched this one. A
recorder *can* reconstruct parentage — events arrive in execution order, so a match's
parent is the most recent open match at `depth - 1` — but that leans on event ordering
being a contract rather than a coincidence. Either we pin that ordering in the Instrument
javadoc, or `onMatch` grows a parent identity. Reconstruction server-side keeps the
interface small; the wire format to the client should carry explicit parent ids either
way, so the client never re-derives it.

**G2 — Frame content as the norm, not the exception.** In the frame model every frame
needs its content value delivered — `onMatchContent` generalised from "only when
unlocatable" to the standard channel. Where the content is byte-for-byte a slice of the
parent frame's content (a plain capture group, undecoded), the wire format should say so
with offsets into the parent instead of shipping the bytes again — both as a payload
optimisation and because "this is a slice of that" is itself information the renderer
uses (it lets highlights in an ancestor's content view locate this frame's descendants).
Genuinely transformed content (decoded, joined, formatted) ships as bytes, honestly
unlocatable in the parent.

**G3 — Non-matches as positions.** Strict/lax dispatch skipping, eater templates and
`emit_error` produce Messages, and matched spans leave gaps in a frame's content. The
navigator should render unmatched gaps as first-class clickable things ("nothing matched
these 14 bytes of this frame's content — these 3 candidate templates were tried here and
failed"), which needs failed attempts reported with their *position*, not just their
count. `stopTiming` today carries no offset. A small addition (or a per-attempt callback
carrying offset when instrumented) makes the single most common authoring question —
"why didn't my template fire *here*?" — answerable by pointing at the place.

## 5. The design: frames and variables

### 5.1 Two trees, one of them real

The **match tree** is the primary structure: the trace's matches, parent-linked (G1),
ordered by position within each parent's content. The **mode graph** — which templates'
bodies apply into which modes, derivable statically from the config — is the fallback
skeleton: it is what the template panel uses for grouping, and what the editor shows when
there is no data or a template matched nothing. The mode graph answers "what could
dispatch this template"; the match tree answers "what did". Both are views; neither is a
new artifact — the model stays `Project`, the executable stays `CompiledProject`.

### 5.2 A match is a frame

The editor's world is **variables, not an input**. A template never sees "the input" — it
is handed a value by the dispatching apply-templates and matches against that. At the
root the value happens to be the whole buffer; below, it is whatever capture or computed
variable was applied. There is no single input to show; there are only named, typed
values being matched, bound, manipulated and concatenated (typed per doc 17 — strings,
numbers, nanosecond instants; sequences per doc 16 — the store is the sequence type).

So the unit the UI navigates is the **frame**: one match instance, exactly as a debugger
presents a stack frame. The document itself is the root frame (template: the document
template; content: the whole buffer). Every frame has:

- **Input side** — what this template could see:
  - the **content variable**: the distinguished value this frame matched against;
  - **params**: the template's declared `ParamDecl`s with the values passed at this
    dispatch;
  - **in-scope variables**: every visible binding *with the value it held at this frame's
    moment in the trace* (replayed from the `onCapture` stream). This is where the
    engine's real scope semantics become visible instead of surprising: captures live in
    a global scope with clear-on-first-store per level (E19), and a child level's
    captures accumulate in stores the parent sees as sequences after its apply — so at a
    `record` frame, `$key` reads `sequence[4]`, while inside child frame 3 the same name
    reads entry 3's value.
- **Output side** — what this template produced:
  - **captures**: the variables this frame's template bound, named and typed, with this
    match's values;
  - **wrote → output**: the fragment of the emitted output this frame and its descendants
    produced (from `onOutput` spans), colourised by producing template. At the root frame
    this is the whole document — the author's final answer keeps a home.

  A frame's third product — its **dispatches**, the child templates that matched within
  its content — is deliberately *not* a variable row: it renders inside the template
  strip's body, anchored to the `apply-templates` instruction that caused it (§5.6),
  because "what matched here" is a fact about that site, not about the frame's scope.
  That anchored list is the "switch into" set: selecting an entry descends to that child
  frame.

All navigation state remains one value — the **cursor**, now read as "the selected
frame" — and every pane renders relative to it. Data→template, template→data,
output→frame, frame→output: all the directions survive from the earlier draft; only the
panes reorganise around the frame.

### 5.3 The dispatch breadcrumb — the crux widget

The cursor's ancestry chain renders as a breadcrumb where **every segment is also a
stepper**:

```
source  ›  record ◀ 2/3 ▶  ›  kv-pair ◀ 2/4 ▶  ›  quoted ◀ 1/1 ▶
```

Each segment: template name, `i/n` where n counts *same-template siblings under that
parent frame*, and ◀ ▶ arrows. This one widget is the whole navigation model:

- **Sideways** — step a segment's arrows: move to the previous/next match of that template
  under the same parent. Stepping a non-leaf segment re-roots everything below it (the
  descendant indices reset to first-child), like an odometer.
- **Up** — click an ancestor segment: that frame becomes the cursor. `source` is the root
  frame, always present.
- **Down** — a trailing `› …` affordance descends into the cursor's child frames; the
  output side's dispatches list is the richer version of the same move.

Two stepper scopes are wanted at different moments: *within this parent* (the breadcrumb
arrows) and *across the whole input* (jump to the next match of this template anywhere,
skipping parents with none). The frame header's stepper (§5.6) does the latter; the
breadcrumb does the former. Both existed in ds-rs users' muscle memory as the single
"n / total" bar — splitting them is deliberate and needs to survive a usability check
(Q5).

Keyboard: `Alt+←/→` sideways within parent, `Alt+↑` to parent frame, `Alt+↓` to first
child frame, `Alt+Shift+←/→` across the whole input. (Stroom's stepper uses toolbar
buttons; these are additive.)

### 5.4 The content renderer — highlights live inside a variable

The old "data pane" survives as something better-founded: **the expanded value renderer
for any variable whose value is text/bytes** — most prominently the frame's content
variable. It renders the value with this frame's child matches highlighted inside it in
per-template colours, descendants nested (direct children prominent, deeper levels
visible but quieter), capture spans dotted-underlined in capture hues, and unmatched
gaps as first-class clickable things (G3). Click a highlighted span → descend to that
frame; click a gap → the gap inspector names the candidates that were tried there and
failed.

Because the renderer is scoped to a frame's content, offsets are always frame-relative
and always honest — the earlier draft's "content lens" problem (§5.9) does not exist.
At the root frame the content variable *is* the whole buffer, so the whole-input survey
("where in the file did `record` match, and what fell between?") is simply the root
frame's content view. The same renderer serves any in-scope variable the author expands —
seeing the matched sections of `$header` is not a special feature, just rendering a
variable that happens to have child matches.

Implementation: not Ace. Nested multi-colour spans with per-span click targets and hover
linking is plain DOM but not plain Ace markers; this is a bespoke widget rendering
escaped text with span underlays (the ds-rs input pane's approach, minus its
find-by-value bug — we have real offsets). Ace stays where it is good: the JSON source
tab. Large content windows around the current position (Stroom's `SourcePresenter`
already does context-windowed fetching around a highlight; same idea, client-side).

### 5.5 The variable panes

Side by side at the **top** of the workspace, centred on the frame — input vars left,
output vars right (§5.2's two sides). The data sits on top and the definition below it
(§5.6) for the ds-rs redesign's own founding reason: the data is the primary object; the
author watches the frame's values constantly and edits the definition occasionally.

Rows are debugger-style: colour swatch (capture hue), name, **type badge** (`string`,
`number`, `instant`, `bytes[n]`, `seq[n]`), value preview; expandable where the value
warrants it (content and text values into the content renderer, sequences into their
entries). Hovering a capture row lights its span inside the content renderer and dims
the rest — the ds-rs hover-link, now frame-scoped.

The capture rows in the output pane are also the **declarations**. A capture is a
structured row, not code text, so there is no reason to split "name ← group $1" from
"value at this frame" across two places: one row carries hue, name, binding source,
type and live value, and the name and binding edit in place — rename the variable where
you are looking at its value. This removes the separate captures section the earlier
draft gave the template area, and with it the last duplication between "config" and
"trace" displays.

### 5.6 The template strip and the pattern workbench

The frame answers "what happened"; the template strip holds "what it is". It sits full
width **beneath** the variable panes, with the frame identity as its title bar: template
name, mode, where it is dispatched from (the mode-graph strip), and the whole-input
stepper (`match 6 of 12 · whole input ◀ ▶`). Two parts:

- **Match summary** — the match expression as a read-only summary chip (type + pattern
  text, flags, guard, limits). Clicking it opens the **pattern workbench**: a popup that
  gives pattern editing the room and the helper context an inline field never had —
  live `PatternInfo` validation with the error shown as you type, group list with
  capture sync, `explain()`/`ambiguities()` lint, and a test panel that runs the
  candidate pattern **against the current frame's content** so the edit→see loop closes
  inside the dialog before a full re-run. The workbench is per-match-type: the regex
  editor, the delimiter editor and the progressive/combinator **step builder** are its
  tabs — the step builder in particular needs dialog-scale space and never had a
  plausible inline home. Popups are Stroom's native idiom for exactly this.
- **Body** — the child structure: the output-node list as a breadcrumb card list
  (ds-rs's shape — text, value-of, apply-templates, call, if/choose, transforms),
  full width. This is where the trace lands on the definition: every `apply-templates`
  card expands with the **matching child templates at that site for the current
  frame** — colour chip, name, match count (→ descend), or `✗ tried` / `— not tried`
  under the ordered choice; a tried-and-failed row renders in warning colour because
  that row *is* the authoring signal. The "set of matching child templates to switch
  into" is thereby anchored to the instruction that dispatched them — which is also
  exactly where an author deciding "what should run here?" is looking. With no trace,
  or a zero-match template selected, the same cards show the static candidate list from
  the mode graph — the strip degrades to a plain config editor instead of going blank.

Editing and navigating are one posture here: change the pattern in the workbench,
re-run, and the same strip's annotations move.

**Considered and not taken: child templates as a sub-pane of the left panel.** A
"children of the current template" list beside the template list would show the same
information in a stable place, but it loses the one thing the apply-card anchoring
buys: a body can hold *several* apply sites in different modes (a header/fields split
is routine), and a single side list cannot say which site each child matched at. It
would also duplicate the breadcrumb's descend affordance and make the left panel
two-purpose. If practice shows the strip's annotations are too far from the data,
revisit — the information is the same trace either way.

The **template panel** (left) stays: the config's own structure, grouped by mode, in
dispatch order (order within a mode is dispatch priority — D34's ordered choice — so
list order *is* semantics and supports drag-reorder). Each row: colour chip, name, match
count over the whole input, attempt count when it tells a story, timing badge after a
profile run. Zero-match templates render dimmed, not hidden — finding them is half the
point. The panel header for the selected template shows the mode-graph strip
("dispatched from: `record` (body pos 2)") — the static complement to breadcrumb
ancestry, and the answer when there are no matches to navigate.

### 5.7 Empty states are the front door

Because nothing works without data, the empty states are designed, not accidental:

- **No sample data**: the root frame's content variable is empty and its renderer is a
  drop target — paste, drop a file, or (in Stroom) pick a stream. Templates are editable
  but every count reads `—` and the run button is the only saturated thing on screen.
- **Data, not yet run**: one keystroke (`Ctrl+Enter`, matching stepping's refresh) runs.
  Auto-run on edit, debounced, is probably right for sample-sized data; it needs a
  manual-only escape hatch for big samples (Q6).
- **Ran, template matched nothing**: selecting it shows the mode-graph strip ("nothing
  applies into mode `values`" vs "tried 212 times — nearest failed attempts here, here,
  here"), which is G3 earning its keep.

### 5.8 Messages and profiling

Messages (`Severity` INFO→FATAL, collected not thrown) get the Stroom treatment: a log
pane, and — where a message carries a position — marks inside the owning frame's content
renderer and a severity tint on the owning template's row, mirroring how stepping colours
its pipeline tree. Profiling reuses the attempt/timing trace: the ds-rs table plus its
sidebar badges, with `invocation_count` and `match_rate` actually displayed this time.

### 5.9 Superseded: the privileged source pane

The first draft of this section had a global "Data" pane over the raw input and a global
"Output" pane, with a "content lens" that swapped the data pane's text whenever the
cursor descended into variable-derived content. That framing failed on its own evidence:
the lens existed *because* there is no single input — only values — and `UNLOCATABLE` in
the Instrument was the engine saying so all along. The frame/variable model (agreed
2026-08-25) deletes the problem instead of patching it: content is a variable, every
frame has one, and the byte-level survey view survives as the renderer for such a
variable (§5.4) rather than as a privileged pane. Kept here because the failure mode —
reintroducing a global source pane "for convenience" — will recur and should be
recognised when it does.

## 6. Where it lives in Stroom

Three phases, each independently shippable:

**Phase A — the plumbing, no new UI ideas.** New document type `ShapeshifterDoc`
(`TYPE = "Shapeshifter"`, extends `AbstractEmbeddableDoc`, implements `HasData` with the
Project JSON as the data payload), following the Pathways/TextConverter groove end to
end: registry entry + SVG, store/serialiser/resource/module, plugin/presenter with an Ace
JSON tab, Settings, Documentation, Permissions. New pipeline element `ShapeshifterParser`
in `stroom-shapeshifter-pipeline` (D10's module) with
`ROLE_PARSER/ROLE_HAS_CODE/ROLE_MUTATOR/VISABILITY_STEPPING`, implementing
`SupportsCodeInjection` — at which point **stepping already works**: code pane (JSON),
record input, XML output, indicators, edit-and-re-step. D10's open item — mapping engine
byte offsets and Messages onto `ErrorReceiver`/`Locator` line:col — lands here.

**Phase B — the frame navigator as the document's main tab.** A "Design" tab on the
Shapeshifter document presenter: template panel, breadcrumb, frame header, the two
variable panes with the content renderer — the §5 design. Server side, one new endpoint
pair on the shapeshifter resource: `preview` (project JSON + sample → trace: frames with
parent ids, content values or parent-slice refs, captures in execution order, output
spans, messages, attempts) and `patternInfo` (backed by `PatternInfo.inspect`). The Ace
JSON tab remains as the "Source" view of the same doc; Design and Source edit the same
`Project` and stay in sync. Sample data needs a home (Q2).

**Phase C — the frame navigator inside stepping.** `SteppingPresenter` chooses
per-element presenters; a shapeshifter-aware element presenter can replace the generic
four-pane view with the frame navigator, fed by the stepping record as sample. Same
widget, second mount. This is where "navigate the active templates for a given step
through the data" meets Stroom's step-through-records: stepping moves between records,
the frame navigator moves within one.

Phase B before C is deliberate: the document editor owns its sample and its run button,
free of stepping-session mechanics, so the novel UI iterates without dragging the
stepping protocol along.

The structured GWT editor is a real cost (the ds-rs body editor's card list, the
per-match-type forms). Phase A's JSON-in-Ace is the hedge: usable, steppable, honest —
and it makes the Design tab's scope a quality decision rather than a blocking one.

## 7. Engine asks, collected

1. **G1**: pin `Instrument` event-ordering as contract, or add parent identity to
   `onMatch`. Either way the preview wire format carries explicit parent ids.
2. **G2**: frame content delivered for every frame — `onMatchContent` generalised to the
   standard channel, with a "slice of parent at offset X" form where that is
   byte-for-byte true, and bytes only for genuinely transformed content.
3. **G3**: failed attempts with positions (extend `stopTiming` or a new instrumented-only
   callback), so unmatched gaps in a frame's content can name their failed candidates.
4. `preview` and `patternInfo` need server endpoints in the Stroom app (Phase B) — thin
   wrappers over `Shapeshifter.run` with a recorder and `PatternInfo.inspect`.
5. Value types on the wire: captures should carry their doc-17 type (string, number,
   instant…) so the variable panes can badge them without guessing.
6. Nice-to-have: `BytePattern.explain()`/`ambiguities()` surfaced as pattern-editor lint.

## 8. Wireframes

Main editor (Phase B "Design" tab). The interactive version of this — cursor, breadcrumb
steppers, both variable panes, content-variable highlighting, capture hover-linking and
output attribution actually working — is
[mockups/18-trace-editor.html](mockups/18-trace-editor.html). The mockup is styled with
the GWT UI's dark theme, its colour values lifted directly from
`stroom-app/src/main/resources/ui/css/theme-dark.css` and
`material_design_colors.css` (variable names kept close to the originals), so it
previews how the editor sits inside the real application.

```
┌ apache-audit (Shapeshifter) ──────────────────────────── [▶ Run] [⏱ Profile] ┐
│ ┌ Templates ──────┐ ┌ source › record ◀2/3▶ › kv-pair ◀2/4▶ › … 1 child ▾  ┐ │
│ │ root            │ ├ Input vars ─────────────┬ Output vars ───────────────┤ │
│ │ ● record    3   │ │ ▾ content  bytes[19]    │ ▾ captures    (editable)   │ │
│ │ mode: fields    │ │   ¦key¦=╔"alice smith"╗ │  ▪key   ←$1 string "name"  │ │
│ │ ● kv-pair   12  │ │   ← child matches lit,  │  ▪value ←$2 string "\"al…" │ │
│ │ mode: values    │ │     gaps clickable      │ ▾ wrote → output           │ │
│ │ ● iso-time  3   │ │ ▸ params (none)         │   <data name="name"        │ │
│ │ ● quoted    1   │ │ ▾ in scope              │     value="alice smith"/>  │ │
│ │ ○ mac-addr 0·8t │ │   $ip string "10.0.0.7" │                            │ │
│ │                 │ ├ kv-pair · mode fields · from record ─ match 6/12 ◀ ▶ ┤ │
│ │                 │ │ match  [ regex (\w[\w ]*?)=("[^"]*"|\S+)  ✎ workbench]│ │
│ │                 │ │ body   1 text "<data name=\""                        │ │
│ │                 │ │        2 value-of $key                               │ │
│ │                 │ │        3 apply mode=values  ● quoted 1 → descend     │ │
│ │                 │ │                             ○ iso-time ✗ tried       │ │
│ │                 │ │        4 text "\"/>"                                 │ │
│ └─────────────────┘ └──────────────────────────────────────────────────────┘ │
│ ▸ Messages (0)  ▸ Profile                                                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

The pattern workbench (opened from the `✎ workbench` chip) is a popup: pattern editor
with live validation and group sync, lint from `explain()`/`ambiguities()`, a test panel
running the candidate pattern against the current frame's content, and tabs for the
delimiter editor and the progressive/combinator step builder.

At the root (`source`) frame the same layout reads: content = the whole buffer with
`record` matches lit; the body's single `apply` card shows `record 3 → descend`;
wrote → output = the whole emitted document. Descending never changes the layout, only
the frame.

Stepping mount (Phase C): the same centre replaces the generic Code/Input/Output panes
for the ShapeshifterParser element; Stroom's step toolbar moves between records, the
breadcrumb moves within the record.

## 9. Questions for ruling

| # | Question | Draft recommendation |
|---|---|---|
| Q1 | Is GWT the target, per Stroom convention, for the whole of §5? (The content renderer, breadcrumb and variable panes are bespoke DOM widgets either way; nothing in §5 needs more than DOM.) | Yes — GWT, Stroom conventions, no second UI stack. |
| Q2 | Where does sample data live? | In the `ShapeshifterDoc` (survives import/export, ds-rs lost this), with a "grab from stream" action to populate it; size-capped. |
| Q3 | G1 answer: ordering contract or explicit parent in `onMatch`? | Explicit parent identity — one parameter now beats a javadoc contract forever. |
| Q4 | Phase A's editing surface: raw JSON acceptable as the *only* editor until Phase B? | Yes — steppable and honest beats a rushed forms UI. |
| Q5 | Two stepper scopes (breadcrumb: within-parent; frame header: whole-input) — keep both, or collapse to one? | Keep both; revisit after mockup use. |
| Q6 | Auto-run on edit (debounced) or manual run? | Auto with a size threshold that flips to manual. |
| Q7 | Does Phase C replace the generic stepping panes for ShapeshifterParser, or add a fifth "Trace" pane beside them? | Replace — the frame navigator subsumes Input/Output; keep Log. |
| Q8 | Content renderer depth: direct children only, or all descendants nested? | All descendants, direct children prominent, deeper levels quieter — the survey view needs it at the root frame. |
| Q9 | Windowing/minimap for large content values — in scope for B? | Defer; window around the current position first. |
