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

Each segment: template name and `i/n`, where n counts *same-template siblings under that
parent frame*. This one widget is the whole navigation model:

- **Sideways** — move to the previous/next match of that template under the same parent.
  Stepping a non-leaf segment re-roots everything below it (the descendant indices reset
  to first-child), like an odometer.
- **Up** — click an ancestor segment: that frame becomes the cursor. `source` is the root
  frame, always present.
- **Down** — a trailing `› …` affordance descends into the cursor's child frames; the
  body's dispatch rows are the richer version of the same move.

Two stepper scopes exist: *within this parent* and *across the whole input* (jump to the
next match of this template anywhere, skipping parents with none). Per Jon's concern
(2026-08-26) that a pair of arrows on every segment competes with the one stepper that
matters, the within-parent controls are **demoted, not deleted**: the `i/n` counts stay
always visible (information without load), a segment's ◀ ▶ arrows appear only on hover,
and the strip's whole-input stepper (§5.6) is the single always-visible stepping
control. Keyboard keeps both scopes: `Alt+←/→` within parent, `Alt+Shift+←/→` whole
input, `Alt+↑` parent, `Alt+↓` first child. If hover-reveal still reads as clutter in
practice, full deletion is the fallback — the counts and keyboard carry the capability.
(Stroom's stepper uses toolbar buttons; the keyboard bindings are additive.)

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
width **beneath** the variable panes and **owns 50% of the vertical split by default**,
with the boundary a draggable `ThinSplitLayoutPanel`-style splitter (Stroom's own idiom
and its `--splitter__*` theme variables; double-click resets). Built as GWT actually
builds it: the visible seam is a **single pixel**, and the drag target is an invisible
**5px band absolutely positioned with a negative margin** so it centres over the line
and overlaps the neighbouring panels — hairline visuals, hittable target. Panel
boundaries throughout are thin splitters or single lines, never dead gaps — the
template-list edge and the input/output vars divide drag the same way. The strip's title bar carries the
frame identity: template name, mode, where it is dispatched from (the mode-graph
strip), and the whole-input stepper (`match 6 of 12 · whole input ◀ ▶`). Two parts:

- **Match summary** — the match expression as a read-only summary chip (type + pattern
  text, flags), followed by guard and limits summaries; the chip and the summaries all
  click through to the **pattern workbench** — not a dialog, and (revised 2026-08-26)
  **not full-screen either**: it occupies the same grid cells as the crumb/vars/strip
  region, but the template-and-pattern nav panel and its toolbar stay in place beside
  it. First-class treatment (the room for regex101-grade detail) and leaving the nav
  reachable turned out not to be in tension — the workbench only ever needed the space
  to the right of the nav column, not the nav column itself. This makes the workbench's
  own opening an instance of the navigation this document keeps coming back to:
  clicking a different template's match chip, or a different library pattern's row,
  retargets the open workbench in place — you are never forced to close it just to look
  at something else.

  There is also **no Apply/Cancel** (revised 2026-08-26, prompted by the same
  question): every field commits when you leave it — pattern text and group names on
  blur, step edits on blur or on the action that made them, guard and limits exactly as
  they already did — the same live-editing convention as the rest of this editor, not
  a special case for pattern authoring. This only became the right call *because* the
  workbench stopped being modal: once browsing to another template while the workbench
  is still open is possible, an "unsaved draft" that Cancel could discard is actively
  dangerous — the honest fix is for there to be nothing to discard. Retargeting the
  workbench to a new subject flushes the outgoing one first, and closing (the title
  bar's ✕, or Escape) is a final flush, not a decision between two outcomes — matching
  Jon's observation that pattern editing is, in this respect, no different from editing
  a template inline. The one thing this defers rather than answers is regret: there is
  no per-field Cancel here the way body-card edits have (§5.6's body editor), so a
  genuine mistake needs a **global undo** to be recoverable — which this design does
  not yet have (ds-rs did; see Q10).

  Its layout is regex101's, adapted: the editable **sample** on the left (seeded from
  the current frame's content, freely editable to experiment) with the live match
  display beneath it; the **matcher** on the right; **match details and explanation**
  below, full width. The sample belongs to the workbench, not to any one tab: the text
  to be consumed is present whichever consumption mechanism is active, and regex,
  delimiter and step sequences each show their matches against the same text, so
  switching mechanism never loses the experiment.

  The regex matcher renders the pattern twice: the editable text, and beneath it a
  **pattern map** — the same pattern with each capture group's span coloured in its
  capture hue. **Clicking a group in the map isolates that group's spans inside the
  sample's matches**, regex101's inner/outer colouring: matches render as outlined
  outer spans with nested group spans inside, and a selected group stays saturated
  while the others fade. The details band shows a per-match table (offsets, group
  values in their hues) and a part-by-part **explanation** of the pattern — group
  entries there click through to the sample the same way. Explanations and pattern
  facts come from the engine (`PatternInfo`, `explain()`/`ambiguities()`) — the owning
  parser publishes them; the UI never derives them itself. Validation is live, the
  error shown as you type. The **Groups panel is the capture-declaration editor**: one
  editable row per group (`$1 → name`), appearing and disappearing as the pattern is
  typed, seeded from `(?<name>…)` syntax where present — the same declarations as the
  capture rows in the output vars pane (§5.5), editable in both. The step builder gets
  the symmetric treatment: its details rows show each step's kind, consumption count
  and semantics, and **clicking a step isolates its consumed spans** in the sample.

  **Guard and limits edit here too**, beneath the mechanism tabs — and like the sample
  they belong to the workbench, not to any tab, because they are mechanism-independent:
  the engine's dispatch order is mode → guard → match → limits whatever the match kind.
  The guard is a condition builder (clause rows: variable · operator · value, joined),
  and because the workbench always has a current frame it shows a **live verdict** —
  "✓ guard passes at the current frame", evaluated against that frame's in-scope vars —
  so guard authoring gets the same data-present feedback loop as pattern authoring.
  Limits are three fields (min / max / only) with their semantics stated inline: fewer
  matches than *min* is reported, matching stops after *max*, *only* processes just the
  Nth match.

  The workbench is per-match-type, as tabs: the regex editor, the delimiter editor, and
  the progressive/combinator **step builder** — the answer to "how do I build a
  combinator?". The builder is an ordered list of steps from the engine's `MatchStep`
  vocabulary (tag, take-until, take-while, take-bytes, read-numeric, …) where **a whole
  regex pattern is just a step kind**, as is a **library reference chosen from a
  picker** — the library stores both plain patterns and whole combinators, and reusing
  either is the same act. Containers (repeat, choice, peek) hold sub-steps; any step can
  capture, and those captures become the template's declarations exactly as regex
  groups do. So composing byte consumers — a take-while, a literal tag, a choice
  between a library pattern and a take-until — is assembling rows in one place, not
  learning a second language, and the shared sample alongside shows each step's
  consumed span as you build. The step builder needs this much space and never had a
  plausible inline home — which is half of why the workbench is its own in-place panel
  and not a popup.
- **Body** — the child structure: the output-node list as a breadcrumb card list
  (ds-rs's shape — text, value-of, apply-templates, call, if/choose, transforms),
  full width. The cards are the body *editor*, not just its display: each card carries
  hover actions (edit in place, reorder, delete), editing swaps the card's summary for
  its per-kind inline editor, and a contextual **"+ instruction"** popup — grouped by
  category (output, invoke, control, transform), ds-rs's Add-Child popup reborn —
  inserts a new card and opens it for editing. Container nodes (if/choose) drill down
  breadcrumb-style rather than nesting cards, exactly as the ds-rs body editor did.
  Any body edit marks the trace stale until the next run.
  This is also where the trace lands on the definition: every `apply-templates`
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

The **template panel** (left) stays: headed by **`source` (the document) as its topmost
item** — selecting it is selecting the root frame, so the panel lists everything the
breadcrumb can reach — then the config's own structure, grouped by mode, in
dispatch order (order within a mode is dispatch priority — D34's ordered choice — so
list order *is* semantics and supports drag-reorder). Each row: colour chip, name, match
count over the whole input, attempt count when it tells a story, and a **heat bar**
(§5.8) — always present, since every run profiles.

The panel is managed through a **toolbar in its header** — add / edit / remove, the
Stroom ButtonPanel-over-list idiom (decided 2026-08-26, replacing an earlier draft's
inline hover affordances and per-group "+ template" rows) — acting on the panel's
**selection**: template rows select as before, and clicking a **mode group's header
selects the mode**. The rules:

- **Add** is always enabled and opens a dialog with a Template/Mode choice. A template
  is created with its **name, swatch colour and mode** set up front (defaulting into
  the selected mode, at the end of its ordered choice, since position is priority);
  the mode field offers the existing modes plus a **"Modes…"** button into the mode
  editor. A mode is created by name.
- **Edit** enables when a template or mode is selected: for a template, the **same
  name / colour / mode fields as Add**, reused rather than duplicated (decided
  2026-08-26 — mode has exactly one home whether you're creating or editing); for a
  mode, the mode editor. The strip title's name and chip click through to the Edit
  dialog too, but the strip itself no longer offers a mode picker — it shows the
  template's mode as plain text, since editing it lives in one place. Name and colour
  changes are presentation and never mark the run stale; a **changed mode is a real
  dispatch edit** (it moves the template between ordered choices) and does.
- **Remove** enables for a selected template, or a selected mode that is *empty*; it
  always confirms first, and for a template the dialog states the consequence — its
  frames and their descendants leave the trace display, and the run goes stale.
- The **mode editor** dialog is the one place modes are added, renamed and removed:
  renaming updates the mode's templates and every `apply-templates` site referencing
  it; removal is empty-modes-only, with a warning when apply sites still reference the
  mode (those sites become choices with no candidates). The root group is structural,
  not a named mode, and is protected throughout.

Colour is editor presentation, not engine config: auto-assigned stably from the
palette, user-overridable, with overrides stored as editor metadata in the
`ShapeshifterDoc` beside the sample data (Q2) — never in the engine `Project` or the
compiled graph. Zero-match templates render dimmed, not hidden — finding them is half
the point. The panel header for the selected template shows the mode-graph strip
("dispatched from: `record` (body pos 2)") — the static complement to breadcrumb
ancestry, and the answer when there are no matches to navigate.

Below the templates, the **pattern library** lists the config's named
`CombinatorPattern`s — plain patterns and whole step sequences alike (ds-rs's
PatternLibrary carried forward; DocRef-based libraries in Stroom per D11) — these are
what `Named` match expressions and the workbench's library-reference steps choose
from. **Selecting a library row opens the pattern workbench directly** (decided
2026-08-26): a bare pattern has no frame, no body, no dispatch — nothing else in the
editor has anything to show for it — so, unlike a template row, there is no
intermediate "selected but not yet editing" state to land in. This is also the
answer to a question the earlier draft left implicit: **the workbench always follows
a navigation** — for a template that navigation is selecting its row (or a frame of
it) and then choosing to look at its match via the strip's chip; for a library entry
the navigation and the "look at its match" step collapse into one click, because
there was never a second thing to look at first.

The workbench itself is subject-agnostic — it edits *a match expression*, and a
template's match and a library entry's definition are the same kind of thing wearing
different context. A plain pattern is edited on the regex tab, a stored combinator on
the steps tab, and **the inactive tab is disabled** rather than offered and ignored
(a library entry's kind is fixed at creation, unlike a template, which can freely
switch mechanism). Guard and limits are hidden entirely for a library subject — they
are dispatch controls on a template's match stage, and a bare pattern has no
dispatch. Applying a combinator edit **recompiles its `impl`** from the edited step
sequence, so `named(this-entry)` resolution elsewhere in the config can never drift
from what the steps actually do; applying any library edit marks the whole run stale,
since any template referencing it via a named step or match may now behave
differently — a wider blast radius than a single template's own edit, and the UI
treats it that way rather than pretending the edit was local.

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

### 5.8 Messages, and profiling that is always on

Messages (`Severity` INFO→FATAL, collected not thrown) get the Stroom treatment: a log
pane, and — where a message carries a position — marks inside the owning frame's content
renderer and a severity tint on the owning template's row, mirroring how stepping colours
its pipeline tree.

**Profiling has no mode: every editor run profiles** (proposed 2026-08-26). The ds-rs UI
had a separate Profile button because its profiling was a second run against a separate
timing endpoint. Our seam has no such shape: the preview already runs with a recording
`Instrument`, and `startTiming()`/`stopTiming()` ride the same run at one clock-read
pair per attempt — one run yields trace *and* timing, so a profiling mode would be a
distinction with no cost behind it. Production is untouched (`Instrument.NONE` returns
zero and the clock is never read), keeping this inside D35's decoration rule: the
editor simply always opts in. Run is the only action; timing is always in the trace and
always on screen.

Display, in three altitudes:

- **Template panel heat bars** — every row carries a thin bar whose **length is that
  template's share of total run time** and whose **colour is its per-attempt cost**
  (green/amber/red). Two metrics, one glyph — and their composition is the diagnostic:
  a zero-match template with a long red bar reads "matched nothing, consumed half the
  run", which is the Instrument javadoc's own reason for timing failed attempts ("a
  template that never matches but is tried at every position is exactly the thing worth
  finding").
- **The opened template's strip** — a profile line with the full numbers: attempts,
  matched (hit rate), µs per attempt, total time, share of run. At the `source` frame
  the same line shows the whole run's totals.
- **Per-frame cost** — attempt timing is per match, so a frame can show what *this*
  match cost; tooltip-level detail, not a pane.

One honest caveat, stated in the UI as in this document: single-run timing under
instrumentation is a **diagnostic, not a benchmark** — JIT warmup and recorder overhead
make absolute numbers jittery, so the display leads with shares, ratios and heat, and
keeps absolutes as detail. Performance claims stay with the JMH gates; the editor
profiler exists to answer "which template, and why", not "how fast".

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
   wrappers over `Shapeshifter.run` with a recorder and `PatternInfo.inspect`. The
   preview payload **always** carries per-template timing (attempts, matched, total
   nanos) — profiling is not a separate request (§5.8).
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
┌ apache-audit (Shapeshifter) ──────────────────────────────────────── [▶ Run] ┐
│ ┌ Templates ──────┐ ┌ source › record ◀2/3▶ › kv-pair ◀2/4▶ › … 1 child ▾  ┐ │
│ │ ● source   doc  │ ├ Input vars ─────────────┬ Output vars ───────────────┤ │
│ │ root            │ │ ▾ content  bytes[19]    │ ▾ captures    (editable)   │ │
│ │ ● record    3   │ │   ¦key¦=╔"alice smith"╗ │  ▪key   ←$1 string "name"  │ │
│ │ mode: fields    │ │   ← child matches lit,  │  ▪value ←$2 string "\"al…" │ │
│ │ ● kv-pair   12  │ │     gaps clickable      │ ▾ wrote → output           │ │
│ │ mode: values    │ │ ▸ params (none)         │   <data name="name"        │ │
│ │ ● iso-time  3   │ │ ▾ in scope              │     value="alice smith"/>  │ │
│ │ ● quoted    1   │ │   $ip string "10.0.0.7" │                            │ │
│ │ ○ mac-addr 0·8t │ ├ kv-pair · mode fields · from record ─ match 6/12 ◀ ▶ ┤ │
│ │                 │ │ match  [ regex (\w[\w ]*?)=("[^"]*"|\S+)  ✎ workbench]│ │
│ │                 │ │ body   1 text "<data name=\""                        │ │
│ │                 │ │        2 value-of $key                               │ │
│ │                 │ │        3 apply mode=values  ● quoted 1 → descend     │ │
│ │                 │ │                             ○ iso-time ✗ tried       │ │
│ │                 │ │        4 text "\"/>"                                 │ │
│ └─────────────────┘ └──────────────────────────────────────────────────────┘ │
│ ▸ Messages (0)   · every run profiles — heat bars in the list, detail in strip│
└──────────────────────────────────────────────────────────────────────────────┘
```

The pattern workbench (opened from the `✎ workbench` chip, or by selecting a pattern in
the library section of the nav panel) occupies the crumb/vars/strip region in place —
the nav panel stays visible and clickable beside it. It has: pattern editor with live
validation and group sync, lint from `explain()`/`ambiguities()`, a sample pane running
the candidate pattern against editable text, and tabs for the delimiter editor and the
progressive/combinator step builder. Every field commits on interaction — there is no
Apply/Cancel.

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
| Q5 | Two stepper scopes (breadcrumb: within-parent; strip: whole-input) — resolved in direction 2026-08-26: within-parent demoted to hover-revealed arrows + keyboard (§5.3). Remaining question: is hover-reveal enough, or delete the arrows outright? | Ship hover-reveal; delete if it still reads as clutter in use. |
| Q6 | Auto-run on edit (debounced) or manual run? | Auto with a size threshold that flips to manual. |
| Q7 | Does Phase C replace the generic stepping panes for ShapeshifterParser, or add a fifth "Trace" pane beside them? | Replace — the frame navigator subsumes Input/Output; keep Log. |
| Q8 | Content renderer depth: direct children only, or all descendants nested? | All descendants, direct children prominent, deeper levels quieter — the survey view needs it at the root frame. |
| Q9 | Windowing/minimap for large content values — in scope for B? | Defer; window around the current position first. |
| Q10 | Global undo/redo (ds-rs had snapshot-based history) — now that the workbench and guard/limits commit live with no per-action Cancel, this is the only remaining answer to "I made a mistake." In scope for B? | Yes for B, or accept the gap explicitly for A — a live-editing surface with no undo anywhere is a real regression from ds-rs, not a simplification. |
