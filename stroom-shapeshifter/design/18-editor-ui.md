# The editor: navigating templated execution through its trace

Status: **draft for discussion — nothing here is ruled.** Written 2026-08-25 from three
surveys: the ds-rs Leptos editor as it actually shipped, the ds-rs redesign document that
described what it should have been, and Stroom's GWT stepping UI as it exists today. The
questions for ruling are collected in §9. Wireframes are in §8; an interactive HTML mockup
of the central idea is at [mockups/18-trace-editor.html](mockups/18-trace-editor.html).

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
recording `Instrument`, then let every pane — data, templates, captures, output — render
one shared position in the recorded trace.

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
  coloured underline in the data pane, hover a capture row → everything else dims and the
  matching span lights up.
- Per-template colour legend over the output, with match counts.
- Body editing as a breadcrumb card list (one nesting level visible, drill buttons with
  child counts) rather than a tree widget.
- Pattern facts fetched from the engine on each edit (its `/api/regex_info`, our
  `PatternInfo.inspect`) — captures auto-synced from groups, names seeded from
  `(?P<name>...)`. The editor never parses pattern text itself; the owning parser
  publishes pattern facts. That rule holds here too.
- Per-template profiling with attempt counts — surfaced as sidebar badges.

What ds-rs specified but never built (`design/data_centric_ui_redesign.md`, the "killer
feature" sections) is precisely the part this document is for: clickable highlights in the
data that navigate to the owning template, ancestor/descendant navigation, ancestor-dimmed
highlighting, keyboard navigation, output colourised by producing template. And that
document was written against the *old nested-node model*, where ancestry was static. Our
flat-template model makes its navigation design unusable as-is — the tree it navigates no
longer exists in the config. §5 is that design re-derived for a world where the tree is
the trace.

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
rich per-template attribution view is new in kind, not just in degree.

## 4. The engine seam, and the three things it doesn't say yet

`engine/Instrument.java` is the trace source, already tested by a Recorder implementation
("keeps everything it is told, which is what an editor would do"):

| Callback | Gives the editor |
|---|---|
| `onMatch(templateId, name, inputOffset, inputLength, matchIndex, depth)` | which template matched which byte span, its per-template match number (1-based), dispatch depth |
| `onCapture(templateId, name, value, matchIndex)` | every variable binding, per match |
| `onMatchContent(templateId, content)` | the matched bytes when they came from a variable (offset reported as `UNLOCATABLE`) |
| `startTiming()` / `stopTiming(templateId, token, matched)` | every *attempt*, including failures — the profiler and the "tried 4,012 times, matched 0" signal |
| `onOutput(templateId, matchIndex, outputOffset, outputLength)` | output attribution — which template wrote which output span |

`PatternInfo.inspect(pattern)` gives editor-time pattern facts (validity, error text,
groups with names) through the engine's own compile path. `BytePattern.explain()` and
`ambiguities()` are ready-made lint output. There is no pause/resume debugger and the
design needs none: run whole, record, navigate.

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

**G2 — Offsets inside derived content.** When apply-templates hands a template content
from a variable, child matches report `UNLOCATABLE` and the bytes arrive via
`onMatchContent`. Where the variable's value was byte-for-byte a slice of the input (a
plain capture group, undecoded), absolute offsets exist in principle. Whether the engine
can cheaply know "this value is an input slice at offset X" is an engine question worth
asking, because the answer decides how often the data pane can highlight grandchildren in
the source versus dropping into a content lens (§5.5). The UI design below works with
either answer; more composable offsets just means the lens appears less often.

**G3 — Non-matches as positions.** Strict/lax dispatch skipping, eater templates and
`emit_error` produce Messages, and matched spans leave gaps. The navigator should render
unmatched gaps as first-class clickable things ("nothing matched these 14 bytes — these 3
candidate templates were tried here and failed"), which needs failed attempts reported
with their *position*, not just their count. `stopTiming` today carries no offset. A small
addition (or a per-attempt callback carrying offset when instrumented) makes the single
most common authoring question — "why didn't my template fire *here*?" — answerable by
pointing at the place.

## 5. The design: one cursor, four views

### 5.1 Two trees, one of them real

The **match tree** is the primary structure: the trace's matches, parent-linked (G1),
ordered by input position. The **mode graph** — which templates' bodies apply into which
modes, derivable statically from the config — is the fallback skeleton: it is what the
template panel uses for grouping, and what the editor shows when there is no data or a
template matched nothing. The mode graph answers "what could dispatch this template"; the
match tree answers "what did". Both are views; neither is a new artifact — the model
stays `Project`, the executable stays `CompiledProject`.

### 5.2 The active-match cursor

All navigation state is one value: the **cursor**, a single match instance in the trace
(template, per-template match index, input span or derived content, ancestry chain). Every
pane renders relative to it:

- **Data pane** — all matches highlighted in per-template colours; the cursor's span
  emphasised; its ancestors visible but dimmed; unrelated matches faint. Click any
  highlighted span → cursor moves there. Click an unmatched gap → the gap inspector (G3).
- **Template panel** — the cursor's template selected; every ancestor template on the
  active path marked; match counts (and attempt counts) per template.
- **Detail pane** — the cursor's template config, with the cursor's *live* capture values
  beside each binding.
- **Output pane** — full output colourised by producing template; the spans the cursor's
  match wrote emphasised and scrolled into view. Click any output span → cursor jumps to
  the match that wrote it.

Data→template, template→data, output→match, match→output: all four directions, one state.

### 5.3 The dispatch breadcrumb — the crux widget

The cursor's ancestry chain renders as a breadcrumb where **every segment is also a
stepper**:

```
source  ›  record ◀ 2/3 ▶  ›  kv-pair ◀ 3/4 ▶  ›  iso-time ◀ 1/1 ▶
```

Each segment: template name, `i/n` where n counts *siblings under that parent match*, and
◀ ▶ arrows. This one widget is the whole navigation model:

- **Sideways** — step a segment's arrows: move to the previous/next match of that template
  under the same parent. Stepping a non-leaf segment re-roots everything below it (the
  descendant indices reset to first-child), like an odometer.
- **Up** — click an ancestor segment: the cursor becomes that match.
- **Down** — a trailing `› …` affordance lists the cursor's child matches; choosing one
  descends.

Two stepper scopes are wanted at different moments: *within this parent* (the breadcrumb
arrows) and *across the whole input* (jump to the next match of this template anywhere,
skipping parents with none). The detail pane's stepper does the latter; the breadcrumb
does the former. Both exist in ds-rs users' muscle memory as the single "n / total" bar —
splitting them is deliberate and needs to survive a usability check (Q5).

Keyboard: `Alt+←/→` sideways within parent, `Alt+↑` to parent, `Alt+↓` to first child,
`Alt+Shift+←/→` across the whole input. (Stroom's stepper uses toolbar buttons; these are
additive.)

### 5.4 The data pane

Not Ace. Nested multi-colour spans with per-span click targets, dimming and hover linking
is plain DOM but not plain Ace markers; the data pane is a bespoke widget rendering
escaped text with span underlays (exactly how the ds-rs input pane worked, minus its
find-by-value bug — we have real offsets). Bands, not just underlines: depth shows as
nested background tint so a record reads as a box containing its fields. Ace stays where
it is good: the JSON source tab, and output if we want its usual options (hex view,
wrapping) — though output attribution spans may push output to a bespoke pane too.

Large inputs: the pane windows around the cursor (Stroom's `SourcePresenter` already
does context-windowed fetching around a highlight; same idea, client-side over the
sample). The full-input minimap/density strip — where in the file did template X match —
is a later nicety, noted in §9.

### 5.5 The content lens

When the cursor sits on a match over derived content (G2's `UNLOCATABLE` case), the data
pane cannot point into the source. Instead it grows a second breadcrumb level — a
**lens**: the pane shows the derived content as the text being navigated, with its own
highlights, and a header naming where it came from ("content of `$header` bound by
`record` match 7"). Popping the lens returns to the source view at the binding site. The
lens nests (derived content of derived content), which is why it is a breadcrumb and not
a toggle. If G2 resolves toward composable offsets, the lens appears only for genuinely
transformed content (decoded, joined, formatted), which is the honest boundary anyway.

### 5.6 The template panel and detail pane

The shipped ds-rs shape survives mostly intact:

- List grouped by mode, in dispatch order (order within a mode is dispatch priority —
  D34's ordered choice — so the list order *is* semantics and supports drag-reorder).
  Each row: colour chip, name, match count, attempt count when it tells a story
  (`0 of 4,012` in red is the story), timing badge after a profile run. Zero-match
  templates render dimmed, not hidden — finding them is half the point.
- Detail pane tabs: match expression (per-type editors: regex with PatternInfo-driven
  capture sync and inline compile errors; delimiter; progressive step list), guard,
  captures (with live values at the cursor), body (breadcrumb card list), limits.
- The mode graph as a small always-visible strip in the panel header for the selected
  template: "dispatched from: `record` (body pos 2), `header` (body pos 1)" — clickable,
  the static complement to breadcrumb ancestry.

### 5.7 Empty states are the front door

Because nothing works without data, the empty states are designed, not accidental:

- **No sample data**: the data pane is a drop target — paste, drop a file, or (in Stroom)
  pick a stream. Templates are editable but every count reads `—` and the run button is
  the only saturated thing on screen.
- **Data, not yet run**: one keystroke (`Ctrl+Enter`, matching stepping's refresh) runs.
  Auto-run on edit, debounced, is the ds-rs redesign's answer and probably right for
  sample-sized data; it needs a manual-only escape hatch for big samples (Q6).
- **Ran, template matched nothing**: selecting it shows the mode-graph strip ("nothing
  applies into mode `values`" vs "tried 212 times — nearest failed attempts here, here,
  here"), which is G3 earning its keep.

### 5.8 Messages and profiling

Messages (`Severity` INFO→FATAL, collected not thrown) get the Stroom treatment: a log
pane, and — where a message carries a position — gutter/underline marks in the data pane
and a severity tint on the owning template's row, mirroring how stepping colours its
pipeline tree. Profiling reuses the attempt/timing trace: the ds-rs table plus its
sidebar badges, with `invocation_count` and `match_rate` actually displayed this time.

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

**Phase B — the trace editor as the document's main tab.** A "Design" tab on the
Shapeshifter document presenter: template panel, data pane, breadcrumb, detail, output —
the §5 design. Server side, one new endpoint pair on the shapeshifter resource:
`preview` (project JSON + sample → trace: matches with parent ids, captures, contents,
output spans, messages, attempts) and `patternInfo` (backed by `PatternInfo.inspect`).
The Ace JSON tab remains as the "Source" view of the same doc; Design and Source edit the
same `Project` and stay in sync. Sample data needs a home (Q2).

**Phase C — the trace editor inside stepping.** `SteppingPresenter` chooses per-element
presenters; a shapeshifter-aware element presenter can replace the generic four-pane view
with the trace navigator, fed by the stepping record as sample. Same widget, second
mount. This is where "navigate the active templates for a given step through the data"
meets Stroom's step-through-records: stepping moves between records, the trace navigator
moves within one.

Phase B before C is deliberate: the document editor owns its sample and its run button,
free of stepping-session mechanics, so the novel UI iterates without dragging the
stepping protocol along.

The structured GWT editor is a real cost (the ds-rs body editor's card list, the
per-match-type forms). Phase A's JSON-in-Ace is the hedge: usable, steppable, honest —
and it makes the Design tab's scope a quality decision rather than a blocking one.

## 7. Engine asks, collected

1. **G1**: pin `Instrument` event-ordering as contract, or add parent identity to
   `onMatch`. Either way the preview wire format carries explicit parent ids.
2. **G2**: can the engine report "this derived content is an input slice at offset X"
   where that is true? Decides lens frequency, not the design.
3. **G3**: failed attempts with positions (extend `stopTiming` or a new instrumented-only
   callback), so unmatched gaps can name their failed candidates.
4. `preview` and `patternInfo` need server endpoints in the Stroom app (Phase B) — thin
   wrappers over `Shapeshifter.run` with a recorder and `PatternInfo.inspect`.
5. Nice-to-have: `BytePattern.explain()`/`ambiguities()` surfaced as pattern-editor lint.

## 8. Wireframes

Main editor (Phase B "Design" tab). The interactive version of this — with the sample
data, cursor, breadcrumb steppers, capture hover-linking and output attribution actually
working — is [mockups/18-trace-editor.html](mockups/18-trace-editor.html).

```
┌ apache-audit (Shapeshifter) ──────────────────────────── [▶ Run] [⏱ Profile] ┐
│ ┌ Templates ─────────┐ ┌ Data ────────────────────────────────────────────┐ │
│ │ root               │ │ ip address=192.168.2.245 name=bob create time=…  │ │
│ │ ● record     3     │ │ ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒  │ │
│ │ mode: fields       │ │ ip address=10.0.0.7 name="alice smith" create …  │ │
│ │ ● kv-pair    12    │ │ ░░░░░░░░░╔══════════╗░░░░░░░░░░░░░░░░░░░░░░░░░░  │ │
│ │ mode: values       │ │          ║ cursor   ║  ← ancestors dimmed,       │ │
│ │ ● iso-time   3     │ │          ╚══════════╝    others faint            │ │
│ │ ● quoted     1     │ └──────────────────────────────────────────────────┘ │
│ │ ○ mac-addr 0·12t   │ ┌ source › record ◀2/3▶ › kv-pair ◀2/4▶ › … ─────┐ │
│ └────────────────────┘ └──────────────────────────────────────────────────┘ │
│ ┌ Template: kv-pair ──────────────────┐ ┌ Output ──────────────────────────┐ │
│ │ match  regex  (\w[\w ]*)=("[^"]*"|\S+)│ <record>                         │ │
│ │ captures  key=$1 ▪"name"            │ │   <data name="ip address" …/>    │ │
│ │           val=$2 ▪"\"alice smith\"" │ │  ▶<data name="name" value=…/>◀   │ │
│ │ body  [data name={key} value={val}] │ │   … colourised by template …     │ │
│ │ matches ◀ 7/11 ▶ (whole input)      │ │ </record>                        │ │
│ └─────────────────────────────────────┘ └──────────────────────────────────┘ │
│ ▸ Messages (0)  ▸ Profile                                                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

Stepping mount (Phase C): the same centre replaces the generic Code/Input/Output panes
for the ShapeshifterParser element; Stroom's step toolbar moves between records, the
breadcrumb moves within the record.

## 9. Questions for ruling

| # | Question | Draft recommendation |
|---|---|---|
| Q1 | Is GWT the target, per Stroom convention, for the whole of §5? (The data pane and breadcrumb are bespoke DOM widgets either way; nothing in §5 needs more than DOM.) | Yes — GWT, Stroom conventions, no second UI stack. |
| Q2 | Where does sample data live? | In the `ShapeshifterDoc` (survives import/export, ds-rs lost this), with a "grab from stream" action to populate it; size-capped. |
| Q3 | G1 answer: ordering contract or explicit parent in `onMatch`? | Explicit parent identity — one parameter now beats a javadoc contract forever. |
| Q4 | Phase A's editing surface: raw JSON acceptable as the *only* editor until Phase B? | Yes — steppable and honest beats a rushed forms UI. |
| Q5 | Two stepper scopes (within-parent vs whole-input) — keep both, or collapse to one? | Keep both; revisit after mockup use. |
| Q6 | Auto-run on edit (debounced) or manual run? | Auto with a size threshold that flips to manual. |
| Q7 | Does Phase C replace the generic stepping panes for ShapeshifterParser, or add a fifth "Trace" pane beside them? | Replace — the trace view subsumes Input/Output; keep Log. |
| Q8 | Minimap/density strip for large samples — in scope for B? | Defer; window around cursor first. |
