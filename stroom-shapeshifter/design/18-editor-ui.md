# The editor: navigating templated execution through its trace

Status: **draft — the frame/variable model in §5 was agreed 2026-08-25 (Jon), replacing
this draft's original privileged input/output panes; the superseded framing is kept in
§5.10 as a record of why it failed. Phasing (§6) and the §9 questions remain open.**
Written 2026-08-25 from three surveys: the the prototype Leptos editor as it actually shipped,
the the prototype redesign document that described what it should have been, and Stroom's GWT
stepping UI as it exists today. Wireframes are in §8; an interactive HTML mockup of the
central idea is at [mockups/18-trace-editor.html](mockups/18-trace-editor.html). A
second, more complex prototype —
[mockups/18b-event-xml-trace-editor.html](mockups/18b-event-xml-trace-editor.html) —
runs the same UI against real data: 11 lines from the `apache_httpd` shapeshifter test
fixture (`stroom-shapeshifter-engine/src/test/resources/fixtures/projects/apache_httpd/`),
dispatched through ten templates into six distinct `EventDetail` shapes (View,
Authenticate, Update, Delete, Export, Import), with output copied verbatim from the
fixture's own golden `event-logging:3` XML — the shape Stroom pipelines normally
produce via XSLT, reproduced byte-for-byte in every record the prototype includes.
Where the first prototype shows the model, the second is the fidelity check: real
regex captures, a real six-way ordered-choice route dispatch (with real tried-and-failed
templates for the trace navigator's G3 story), and a real security narrative (a
brute-force login lockout) sitting right in the sample data.

**The prototype's config is the fixture's config with the repetition factored out, not a
different program.** The real `apache_httpd/project.json` is one enormous `log_line`
template carrying a fifteen-branch `choose`, with the closing block of `<Data Name=…/>`
rows written out again in every branch — 120 of them in one file. The prototype expresses
the same output as six route templates under a `route` mode plus one shared `event_data`
template applied from each, which is the shapeshifter feature being designed doing the
job the `choose` was doing by hand. Every instruction in it is one the fixture really
performs: a `value-of` per row over a literal with a variable spliced in, an `escape-xml`
step standing for the config's `translate` (which escapes `& " < >` through a temporary,
because escaping untrusted log fields is a correctness property), and a `choose` for the
last row — the fixture emits `Referer` **or** `UserAgent`, never both, with the security
routes adding the agent back. That last rule was read off all 28 golden events rather
than assumed; the obvious guess (`if $useragent != "-"`) is wrong on 13 of them.

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

## 2. Prior art: what the prototype built, and what it only wrote down

The the prototype `node-editor` crate went through two generations. The canvas/node-graph editor
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

What the prototype specified but never built (its data-centric redesign notes, the "killer
feature" sections) is precisely the part this document is for: clickable highlights in the
data that navigate to the owning template, ancestor/descendant navigation, keyboard
navigation, output colourised by producing template. And that document was written
against the *old nested-node model*, where ancestry was static. Our flat-template model
makes its navigation design unusable as-is — the tree it navigates no longer exists in
the config. §5 is that design re-derived for a world where the tree is the trace. The
The prototype input/output pane layout itself is *not* carried forward — §5's frame model
replaces it, for reasons recorded in §5.10.

Known the prototype defects not to repeat: highlight placement by searching for the capture's
*value* in the text (wrong span when values repeat — we have real offsets; use them);
invalid patterns failing silently; the full output never actually colourised despite the
ranges being available. (the prototype also lost its sample data on import/export, which looked
like a fourth defect until Q2 was ruled: here the data is never the document's to keep —
it arrives from the pipeline that feeds the parser, so there is nothing to lose.)

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
| `onOutput(templateId, matchIndex, outputOffset, outputLength, unit)` | output attribution — which template wrote which output span, and in which currency: `BYTES` for the byte sink, `EVENTS` (ordinals) for the event sink (design 20 S5, design 21 phase 4). Spans nest; a child whose first emission forced an enclosing element's deferred start tag has that tag at the start of its span. The preview payload carries `unit` beside each span, and the output pane maps `EVENTS` onto the serialised preview it also has. |

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
- **Output side** — **wrote → output**: the fragment of the emitted output this frame and
  its descendants produced (from `onOutput` spans), attributed to the body instruction
  that wrote it (§5.4). At the root frame this is the whole document — the author's final
  answer keeps a home. This pane holds nothing else.

  **Captures are the input side, and they belong with the rest of it** — the frame's own
  bindings head the variables pane, above what its children stored, what it inherited and
  its params (§5.5). They took two moves to get there and the route is worth recording,
  because each move was right about something:

  - They began as an "output vars" section, which read as though the frame had *produced*
    them the way it produced output. But a capture is what the pattern pulled out of the
    input — the raw material the body then spends.
  - So they moved to the template panel, under the match (2026-08-27, Jon: "the template
    is producing the captures in the matcher"). True — of the **declaration**. What the
    line actually showed was the **value**, which is the frame's, not the template's; and
    it left a row in the bottom-left lighting a span in the top-left, the same diagonal
    the output pane had just been moved to avoid.
  - So they now sit in the variables pane (2026-08-28, Jon: "a single area for the user to
    look at to discover all variables in scope"), with the declaration's home settled
    separately: names are edited in the pattern workbench, with the groups they name.

  One product of a frame does still live elsewhere, for a reason that survives all of
  this: **dispatches**, the child templates that matched within this frame's content,
  render inside the template strip's body, anchored to the `apply-templates` instruction
  that caused them (§5.6). They are a fact about a *site in the template*, not an entry in
  the frame's scope. That anchored list is the "switch into" set: selecting an entry
  descends to that child frame.

All navigation state remains one value — the **cursor**, now read as "the selected
frame" — and every pane renders relative to it. Data→template, template→data,
output→frame, frame→output: all the directions survive from the earlier draft; only the
panes reorganise around the frame.

### 5.3 The dispatch breadcrumb — the crux widget

**Back and forward sit at its head** (2026-08-28, Jon: "when navigating I feel myself
wanting to navigate back and forward with some form of history support"). The crumb
answers *where am I*, which is ancestry; it cannot answer *where was I*. Select a template
in the nav panel and the crumb rewrites around a different frame with nothing pointing
back at the one you were reading; descend three levels chasing a value and the way back is
a series of guesses. So navigation states are recorded and walkable — **(frame, selected
template)**, the two things that make this editor show something else, so that a nav-panel
click is undoable — with **Alt+←/→**, the convention every browser has already taught. A
new move truncates the forward branch, as a browser's does. In Stroom this is the state a
GWT implementation hands to the platform's own place history rather than inventing a
second one. (A card with focus handles its own Alt+arrows for moving instructions and
stops them there, so the two never compete.)

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

**Colour + underline is the permanent extent marker; filled background is
hover-revealed, not always on** (revised 2026-08-27, after real multi-record data
exposed the problem: several levels of nested matches, each with its own translucent
fill, stack into an overlapping wash that makes the underlying text hard to read — the
richer the dispatch, the worse it got). A match keeps a thin colour-coded underline (or,
for a root-level match, a coloured inset band) all the time, so the shape of the parse —
which template matched where — is still visible at a glance without ever occupying the
interior of the text with colour. The filled tint that used to be permanent now appears
only on hover, and disappears the moment the pointer leaves — which is exactly the thing
a background can do that colour+underline alone cannot: mark "this is the one I am
looking at right now" without every match, at every depth, competing for the same visual
weight all the time. Hover states carry a short CSS transition rather than snapping, since
nested matches share edges and the pointer crossing between them was re-triggering
`:hover` on several ancestors at once — flicker, not a data problem, and a fade reads as
smooth where an instant swap read as jitter.

The same colour+underline-always, fill-on-hover-only rule applies to the pattern
workbench's group/step highlighting in both the pattern map and the sample, with a
permanent fill once a group or step is **pinned** by a click — click-to-pin replaces the
old default-everything-tinted state, so looking at one group's structure no longer means
fighting the other nine for attention.

**The content pane: one permanent mark, one transient** (2026-08-28, Jon's scheme,
replacing four permanent channels that were answering two questions between them). What
was there — a solid underline per match, a dotted underline per capture, a tint per
capture, an inset band for root-level matches, and a translucent fill on hover — had grown
by accretion, each addition defensible alone. Four levels of template nest in the second
prototype, and at the document frame all four underlined at equal weight: §5.4's own
ruling that direct children should be prominent and deeper levels quieter had never been
implemented, and the one depth that *was* special-cased (root level) was the quiet one, so
the prominence ran backwards.

The scheme now:

| channel | says | when |
|---|---|---|
| **text tint**, plus a faint dotted rule in the same colour | which variable these bytes became | always, at every depth |
| **an outline**, in the colour of the thing | what you are pointing at, or what something else is pointing at | on demand |

The dotted rule is not a second channel; it is the tint repeated where the tint cannot be
seen, since a capture made of whitespace has no glyphs to colour. Matches carry no
permanent mark at all: clicking text already descends into the deepest match under it, so
drawing every extent all the time was four lines competing to say what one hover says
exactly. **A match's extent is revealed by pointing at it — or by pointing at the template
that made it**, which is the same question asked from the other end: hovering a dispatch
row inside an apply card, or a template row in the navigation panel, outlines the bytes it
consumed *and* the output it wrote. That third link is what makes the removal a
simplification rather than a loss; the information did not go away, it stopped being drawn
all the time.

**A point and a click are about the match at *this* level** (2026-08-28, Jon). The box you
see is the **immediate child template's**, in that template's own colour, however deep the
pointer happens to be inside it; clicking steps into that child, one level at a time. The
old behaviour — mark and descend into the deepest match under the pointer — let you skip
three levels in one click and land somewhere whose relationship to where you were had to
be reconstructed from the crumb. Digging is the point: the editor is *for* understanding
how a document is taken apart, and taking it apart a level at a time is the understanding.
**Ctrl-click (or ⌘-click) still jumps to the deepest match**, for anyone who already knows
where they are going, and nothing is reachable only that way.

That makes the content pane's link to the templates bidirectional and specific: pointing
at a match lights **the template row that made it, the dispatch row that reached it, and
the output that very match wrote** — the last keyed by frame, not by template, so hovering
the third record in the input outlines that record's `<Event>` block and no other.

**Any of the four lights the other three** (completed 2026-08-28, after Jon found the one
edge that was missing: the output lit the template that produced it, and stopped there,
never reaching back to the bytes that template consumed). A dispatched region of output
knows the child frame that wrote it, and that frame's match is on screen, so the loop
closes whichever surface you enter it from — input match, template row, dispatch row,
output region. The one case with no counterpart is output this frame wrote *itself*: a
`text` card consumed nothing, and a `value-of` names an expression rather than a capture,
so there is nothing honest to point at in the input. (Wiring `value-of` back to the
capture it reads would need the expression parsed, which is an engine fact, not a UI
guess — worth doing when expressions are real.)

Nothing in either byte pane fills any more. A translucent fill under text was the last
thing in these panes competing with the text for legibility, and with extents revealed on
demand it had nothing left to do. (The pattern workbench keeps its fills: a short sample,
a deliberate click-to-pin selection, and no nesting — a different surface with a different
problem.) Only the innermost thing under the pointer is marked, while every capture around
it still lights its row in the variables pane: the bytes belong to all of them, and one
mark says which one you are pointing at.

**The output pane's rule is different again, and simpler than either of the above**
(superseded 2026-08-27 — the per-template colour + underline + own/dim scheme two
paragraphs above lasted about one round before a sharper question replaced it: *the body
is what's producing this output, so why colour it by template at all rather than by
which body card wrote it?*). Every span in "wrote → output" is now coloured by the
**thing that produced it** — a `text`/`value-of` card's own tagged span takes its card's
colour, exactly the way a capture already gets a swatch; a span a **dispatch** produced
takes the colour of the child template that wrote it (revised 2026-08-28, below). This
one rule does the work three earlier mechanisms were doing separately: colour now shows
which instruction wrote what (strictly more information than "which template" ever
gave), which makes the old own/descendant split redundant (dropped, along with its bold
weight and its opacity dimming) — a span's colour already tells you whether it's this
frame's own card or a dispatched child's, so a second channel saying the same thing was
noise. Every body card gets a small swatch chip, visible without hovering, the same
"here is my colour" affordance a capture row already has; hovering the card, or the
output span itself, outlines the match (never fills — a fill sitting behind text you're
reading fights the thing it's meant to point at, the lesson the earlier own-highlight
design had already learned the hard way).

**One region per card, and one key behind both the colour and the highlight** (refined
2026-08-27 after Jon exercised the rule at every level of the second prototype and found
three ways it broke). The rule above is right; the first implementation of it computed
*colour* by walking body cards and *hover* by walking frames, and the two drifted apart
wherever the tree was deeper or flatter than the case they were written against:

- A frame whose body is a single `apply` and nothing else — `access_record`, and the
  document frame itself — showed its output as dozens of separately-hoverable pieces, all
  identically coloured because they all had the same one owner. Visible seams promising a
  distinction that wasn't there. Adjacent output now **coalesces into one region per
  owning card**, so the output pane is a straight mirror of the body strip: N cards, N
  regions, same colours, same order. An `apply` card keeps one region *per dispatch*,
  because each of those genuinely is a different child frame to click into — so the
  document frame reads as header · record · record · … · footer, which is what it is.
- An `apply` card highlighted only what its children wrote **directly**, so the document
  frame's apply — whose `access_record` children write nothing themselves, they only
  dispatch further — lit nothing at all. Hover and colour now derive from the same
  `data-owner` key, computed once at render; they cannot disagree, and depth stops
  mattering.
- A card with no span of its own fell back to highlighting the frame's **entire** output,
  so a card that had written nothing appeared to claim everything around it. Gone: a card
  that wrote nothing in this frame — a conditional that didn't fire — now highlights
  nothing at all, which is the honest answer rather than a consolation region.

Muting keeps one job after this: a card that never ran *anywhere in the run*, which is
every card of a template with no matches — what you are looking at the moment you add
one. Deliberately a whole-run fact and not a per-frame one, so a card's swatch holds one
stable colour as you step between frames; a swatch that changed colour underneath you
while stepping would be reporting the frame, not the card.

**A dispatch is coloured by the template it dispatched into** (2026-08-28, Jon: the
dispatch rows carry a swatch each, "but only the outer apply-templates is coloured and
highlighted in the output window"). The rows were promising a link the output never made
good on. The fix is the rule the conditionals had already established one level down: **a
container writes nothing, so it holds no colour of its own** — an `if` delegates to the
instruction inside the branch that ran, and an `apply` delegates to the child template it
dispatched into, which already wears an identity colour in the nav list, the crumb and the
row itself. So the apply card drops its single hue and wears those same swatches, one per
template that matched at this frame; hovering the card head still lights the whole
dispatch, hovering one row lights only what that template wrote, and both come off the
same run identity (owning card, child frame) the colour came from.

Two palettes now share the output pane, and they say different things: **capture hues**
mean "an instruction of this frame wrote it", **template colours** mean "a child frame
did, and here is which one". That is the own/descendant distinction dropped on 2026-08-27
for saying nothing that colour wasn't already saying — back, with identity in it. Its best
moment is a multi-candidate site: at `combined_log`'s route `apply` the card's swatch and
its output region say which of the six routes ran for the record you are on, before you
read a word of it.

**An instruction inside a branch owns its output the same way** (2026-08-28, when the
conditionals gained real bodies — §5.6). What owns a span is a *path* rather than an
index: `7` is the eighth instruction of the body, `7.w1.0` the first instruction of that
`choose`'s second `when` branch. A conditional therefore never writes anything itself;
the instruction inside the branch that ran does, and takes the colour. The conditional
is coloured as the container it is — it holds the colour of what its branches wrote, and
hovering it lights every span inside it, which the path prefix gives for free. Two
consequences worth stating: a conditional whose branches *never* fire anywhere in the run
is muted, distinguishing "did not fire in this record" (highlights nothing) from "cannot
fire in this data" (muted swatch); and the trace has to say which branch ran, since the
output alone only says what was written.

Because the renderer is scoped to a frame's content, offsets are always frame-relative
and always honest — the earlier draft's "content lens" problem (§5.10) does not exist.
At the root frame the content variable *is* the whole buffer, so the whole-input survey
("where in the file did `record` match, and what fell between?") is simply the root
frame's content view. The same renderer serves any in-scope variable the author expands —
seeing the matched sections of `$header` is not a special feature, just rendering a
variable that happens to have child matches.

Implementation: not Ace. Nested multi-colour spans with per-span click targets and hover
linking is plain DOM but not plain Ace markers; this is a bespoke widget rendering
escaped text with span underlays (the the prototype input pane's approach, minus its
find-by-value bug — we have real offsets). Ace stays where it is good: the JSON source
tab. Large content windows around the current position (Stroom's `SourcePresenter`
already does context-windowed fetching around a highlight; same idea, client-side).

### 5.5 The variable panes

**Four quadrants** (revised 2026-08-28), all centred on the frame:

```
        INPUT             │      VARIABLES
  ────────────────────────┼────────────────────────
       TEMPLATE           │       OUTPUT
```

**Each pane carries its name** (2026-08-28), in the quiet uppercase the variables pane
already used. Titles had been removed from the two byte panes a few days earlier and that
was right at the time — one pane labelled and its opposite number bare was the fault, not
the labels — but with the panes unbordered and sharing one background, a label is the only
thing naming them, and until now three of the four were named only in an `aria-label`,
which is a name for a screen reader and for nobody else. TEMPLATE's title doubles as the
template's identity line (§5.6).

**Read them top-left, top-right, bottom-left, bottom-right** (Jon, 2026-08-28): the input,
the variables drawn out of it, the template that consumes them, the output it wrote. Each
*row* is a hover-linked pair, and that is the whole highlighting topology:

| row | left | right | the link |
|---|---|---|---|
| top — input and context | the bytes | the names bound out of them | hovering a variable lights its span; hovering a span lights its variable |
| bottom — definition and result | the instructions | the bytes they wrote | hovering an instruction lights its output region; hovering a region lights the instruction, and for a dispatch its child-template row too |

**Highlighting is a set of requests, not a pile of toggles** (2026-08-28, found from
Jon: "the parent apply-templates row does not highlight the output"). It did — and then
stopped, because several surfaces can point at overlapping things at once and each one was
switching the class on and off for itself. Hovering an apply card lights everything it
dispatched; moving the pointer onto a dispatch row *inside* that card and off again then
switched off that template's share, since the row's release knew nothing about the card
still asking for it. The card looked broken while the thing that broke it was a child of
the card. Each source now registers a named request for a set of selectors and one applier
recomputes the union, so a release only darkens what nothing else wants lit. It is the
same shape of fix as the owner key (§5.4): one place computes the answer, rather than
several places each keeping their own and drifting.

Both rows link **in both directions** — which the design had been claiming since the body
cards were first coloured and only half doing: the card→output and variable→span
directions were wired, and neither return path was (fixed 2026-08-28). A claim of this
kind is cheap to write and easy not to notice missing, since each pane looks alive from
the side that works; the mockups now assert all four directions rather than describing
them.

**Hover in the byte panes is delegated to the pane, and a line gap holds** (2026-08-28,
Jon: "multi-line text flickers on hover because you often move the mouse across lines and
there are gaps between lines"). An inline span that wraps is several line fragments, and
the leading between them belongs to no element: pointing at a three-line run and moving
down it fires leave and enter at every crossing, so the highlight blinks once per line —
worst on exactly the long runs worth following. A transition softens that and cannot fix
it, because the state really is being lost. So neither pane asks its spans to report
enter and leave; each asks one question of the pointer's position — *which spans is it
inside now?* — and reads the gap's answer of "none" as **hold what you had** rather than
"clear". The highlight changes only when the pointer reaches other spans, and clears when
it leaves the pane. No timers, no thresholds, and nesting survives because the whole
ancestor chain lights, which is what `:hover` was doing for free. Consequently nothing in
these two panes styles on `:hover` at all: `.hot` is the only state, set from the pointer
or from the pane opposite, which is the same unification the outline weights got. (The
block surfaces — cards, rows, crumb segments — keep `:hover`, since a block box has no
gaps to fall through.)

**And the highlight looks the same from either end.** Pointing at a span yourself and
having something else point at it are the same state, so they are one CSS rule rather
than two that drifted — the outline had been appearing only when the variables pane
pointed at a capture, and the output pane wore a thinner outline for its own hover than
for the body card's (2026-08-28). Everything that lights up now **fades**, over the same
150ms: an instant highlight flickers as the pointer crosses nested spans, which was
already known for the content renderer's fills and had never been applied to the outlines
or to the surfaces on the receiving end — cards, dispatch rows, variable rows, crumb
segments. The implementation detail that makes it cheap: an outline never affects layout,
so each of these carries a permanently declared *transparent* outline or background and
only changes its colour. Nothing moves, and there is something for the transition to
animate.

Two things fall out of stating it that way. Nothing highlights diagonally any more — the
last diagonal was the capture row, which is why it moved (§5.2). And the two palettes have
one meaning each: the rotating **`--cap-N` hues are scoped to a row** — "which capture"
above, "which instruction" below, never read across the divide — while a **template
colour is global identity** and means the same template wherever it appears, which is why
a dispatched output region can safely wear one (§5.4). The rule is the fix, not a third
palette.

**The ring is twelve hues, not four** (2026-08-28). Four was enough for the first
prototype's two-capture templates and failed on the first real config: `combined_log`
declares ten, and rows five onward were asking for a var that does not exist, which paints
white — a swatch that says nothing, next to nine that do. The twelve are material 300s
from the GWT UI's own `material_design_colors.css` (200s where a 300 would collide with a
template or category colour), ordered so neighbours in the ring are far apart in hue, and
one function issues every one of them — capture rows and their spans, body cards, the
workbench's groups and steps — so the ring's size is one number in one place and nothing
can index past its end. Wrapping is honest when a config finally needs it: a repeat twelve
apart reads as "another capture", not "the same one".

The data still sits on top and the definition below it (§5.6) for the the prototype redesign's
own founding reason: the data is the primary object; the author watches the frame's
values constantly and edits the definition occasionally. What changed is the *column*
each thing sits in, and the rule now is adjacency to whatever it is a statement about:

- **Output moved from top-right to bottom-right, beside the body that writes it.** Once
  output is coloured and highlighted per body card (§5.4), the body and its output are
  one thought, and they were sitting in opposite corners — hovering a card meant tracking
  a highlight diagonally across the window. Now the highlight appears a few centimetres
  from the card that caused it. (Jon, 2026-08-27: "it is formed from the instructions and
  highlights when instructions are highlighted".)
- **The variables take the vacated top-right**, beside the content they are drawn from.
  They had been stacked under the content renderer, squeezing the one view here that
  genuinely wants vertical room, in exchange for no relationship at all. The pane reads
  innermost-out, like a debugger's Variables pane: **this match** (the content value, then
  the frame's own captures) → **child stores** (what its children bound, seen as sequences
  after their apply) → **outer scope** (bound by an ancestor, readable here) → **params**.
  One list, one answer to "what can an instruction here read?" — and demonstrably the
  right list, because it is the same union `scopeNames()` has always fed the `value-of`
  picker. Before this the pane showed two thirds of it while the picker beside it knew all
  of it.
- **Both byte panes lost their headings and their insets.** "Output vars → wrote →
  output" was two levels of label distinguishing the output from the other things in that
  pane, and since captures left (§5.2) there are no other things. The content pane reached
  the same state from the other direction when the content row moved into the variables
  pane, and on 2026-08-28 it was built the same way — no title, no inset box, no border
  but the splitters that bound it. Each holds exactly one value, so the pane *is* the box,
  and the two read as one kind of thing on opposite corners: the value coming in,
  top-left; the value going out, bottom-right. All four workspace panes share the page
  background — giving the two byte panes a different one made the workspace a
  checkerboard, alternating shades saying something the splitters already say, and
  competing with the one thing here that *is* coloured by meaning: the text. Only the
  navigation panel differs, as it does everywhere in Stroom.

The two vertical splitters share a single ratio, so the quadrant divide is one straight
line and either handle moves all of it.

Rows are debugger-style: colour swatch (capture hue), name, **type badge** (`string`,
`number`, `instant`, `bytes[n]`, `seq[n]`), value preview; expandable where the value
warrants it (content and text values into the content renderer, sequences into their
entries).

A capture row carries hue, name, binding source (`← $1`), type and live value in one
line, so nothing about the variable is split across places — except its **name**, which is
edited in the pattern workbench, on the group it names (2026-08-28). That is the one place
where a name and its group cannot disagree, and it keeps the variables pane a statement
about *this frame* rather than a second editor for the template.

The cost is real and worth naming: a template with **no matches** now shows its declared
captures nowhere but the workbench, since the variables pane is frame-scoped and there is
no frame. That is one click from the match chip, on the surface that names them — and the
alternative, keeping declaration rows in the template panel as well, is the two-locations
problem that started this.

Hovering a row in **this match** lights its span in the content pane beside it and dims
the rest — the the prototype hover-link, now frame-scoped and one pane away. An **outer scope**
row has usually nothing to light, since an ancestor's span generally falls outside this
frame's content (`combined_log`'s `$time` is nowhere inside a route frame's URL slice), so
it lights the **breadcrumb crumb of the frame that bound it** instead, and clicking steps
there — where the span is visible. A row that cannot show you where a value came from can
at least say where it lives.

### 5.6 The template strip and the pattern workbench

**The strip's header is its pane title** (2026-08-28, Jon). The row that used to sit above
it — swatch, name, mode, dispatched-from, and the whole-input stepper at its right — was
mostly restating what the breadcrumb and the highlighted nav row already say. Mostly: a
template with **no matches** appears nowhere in the crumb, which shows the *cursor's*
ancestry, so this line is the only thing that names it, says where it could be dispatched
from, and how many positions it was tried at. That survives, folded into the title:
`TEMPLATE — ● kv-pair · mode fields · dispatched from record (body pos 3)`, with the
swatch and name still opening the edit dialog. One row saved, and the strip now opens on
`match`, where the eye goes anyway.

**The whole-input stepper moved to the breadcrumb row** (§5.3). It answers "which match am
I on", which is the crumb's question, not the definition's — every other thing in the strip
answers "what is this template". It sits at the far end of that row, past the ancestry, and
is **labelled with the template it steps through**: `matches of kv-pair ◀ 6/12 ▶`. The
label is load-bearing rather than decorative, because the crumb's own per-segment arrows
step *within a parent* while these step *across the whole input* — Q5's two scopes, now
adjacent, and two identical-looking arrow pairs would have made the ambiguity worse rather
than better.

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
  not yet have (the prototype did; see Q10).

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
  typed, seeded from `(?<name>…)` syntax where present. Since 2026-08-28 this is the
  **only** place a capture is renamed: the variables pane shows the name beside the value
  it took at this frame, and the group panel edits it beside the group it comes out of —
  one editor, on the surface where a name and its group cannot disagree. The step builder
  gets
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
  (the prototype's shape — text, value-of, apply-templates, call, if/choose, transforms),
  full width. The cards are the body *editor*, not just its display: each card carries
  hover actions (reorder, delete), editing swaps the card's summary for its per-kind
  inline editor, and a contextual **"+ instruction"** popup — grouped by category
  (output, invoke, control, transform), the prototype's Add-Child popup reborn — inserts a new
  card and opens it for editing. Container nodes (`if`/`choose`) hold **card lists of
  their own**, rendered nested one level in, each branch with its own add line (revised
  2026-08-28; the prototype drilled down into containers breadcrumb-style instead, which we
  looked at and did not take — a conditional here is usually two or three instructions,
  and nesting shows the shape of the whole body at once, where drilling down hides the
  branch you are not in and costs a navigation step to see it. The choice is a rendering
  one: the model underneath is a card list either way, so it can be revisited without
  touching anything else.) Any body edit marks the trace stale until the next run.

  **The card itself is the target: click to edit, or to follow** (2026-08-27, Jon: "the
  instructions should be editable when you click them or followed if they are sub
  templates"). Reaching for a hover-revealed pen to open the thing you are already
  pointing at is a step that earns nothing. The one split is by kind, and it falls out of
  what the instruction *is*: an `apply-templates` card is a dispatch site, so clicking it
  **follows** — descending into the frame it dispatched, the same move as clicking its
  matched-child row — and only opens for editing when there is nothing to follow at this
  frame. Everything else opens its editor. (The pen survives for discoverability; the
  reorder and delete buttons stop the click from reaching the card.)

  **Each kind gets an editor shaped like the instruction it edits.** One free-text box
  per card was only ever honest for `text`, and not even there — it made you type JSON
  escapes to emit a newline, and for `choose` it offered a prose sentence where the
  instruction is a *list*. What each kind actually needs:

  | kind | editor |
  |---|---|
  | `text` | the text itself, decoded, over as many lines as it really has |
  | `value-of` | an expression, the in-scope names offered from a picker rather than remembered, and the value it produced *at this frame* shown underneath |
  | `if` | its condition — the consequent is the card list nested under it |
  | `choose` | its branches as a list — add, edit, prune, with an optional `otherwise`; each branch's instructions are the card list nested under it |
  | `apply-templates` | the mode, and the `select` expression that narrows what is dispatched |

  This makes `if` and `choose` hold their parts as **real fields** rather than a display
  string, with the card's one-line summary composed from them — which is the point, since
  a summary that is the source of truth cannot be edited as structure.

  **Editing commits live, and Escape reverts** (2026-08-28, Jon's ruling, replacing the
  ✓/✕ pair each editor used to carry). Everything else in this editor already committed
  live — the workbench, the guard and limits, the pattern — so the card editors were the
  exception, and their two glyphs were small enough to be a WCAG target-size failure into
  the bargain. What Cancel was really protecting is `choose`'s branch *list*: half-finished
  structure must not reach the model the way a half-finished string harmlessly could. So
  the card is **snapshotted when its editor opens** and Escape puts the snapshot back —
  one revert covering every kind, including the structural ones, instead of a draft for
  the kind that happened to need it. A half-added branch is as revertible as a half-typed
  condition. The footer says so in words rather than glyphs: *applies as you type · Esc
  reverts · Enter closes*.

  **Instructions move.** Each card carries a **grip** at its head — the only part of it
  that starts a drag, so dragging never fights click-to-edit or click-to-follow — and can
  be dropped onto another card (landing before it) or onto a list's "+ instruction" line
  (landing at the end, which is also how an empty branch receives its first instruction).
  Dropping into a conditional's branch is deliberately allowed: *"this should only happen
  when the status is 403"* is a move, not a retype, and paths (§5.4) already express it.
  Drag is never the only way — a pointer gesture with no keyboard equivalent locks out
  anyone who cannot make it — so the same moves are on the ↑/↓ buttons and on Alt+arrows
  while a card has focus: **Alt+↑/↓** reorders within the list, **Alt+→** moves into the
  branch of the conditional above, **Alt+←** moves out of a branch to just after the
  conditional holding it. All four run the same primitive, and each announces where the
  instruction landed.

  **The branch bodies are card lists, not strings** (2026-08-28, closing the one part of
  the editor that was still illustrative). A conditional's consequent used to be a
  free-text field holding a sentence — `"<Permitted>false</Permitted> + a Description"` —
  which is a description of instructions rather than instructions, and it hid the output
  it produced: the whole conditional was one opaque span, so hovering said "somewhere in
  here" and the swatch was a single colour for what were really two or three separate
  writes. Now each branch owns a list of ordinary cards, added from its own
  **"+ instruction"** line, edited, reordered and deleted exactly as top-level ones are —
  the same functions, addressed by path (§5.4) rather than by index. What the conditional
  card itself edits is only what it decides: `if`'s condition, `choose`'s branch
  conditions and whether there is an `otherwise`. Pruning a branch takes its instructions
  with it rather than orphaning them.

  Filling those bodies in against the fixture is what made the case for them: what had
  been one prose card in `generic_view` is a `<Permitted>` and a `<Description>` written
  separately, and `combined_log`'s `<User>` conditional is three instructions with the
  `$user` value-of in the middle — so the trace attributes each to the instruction that
  wrote it, and the `$user` span highlights on its own rather than as part of a block.
  This is also where the trace lands on the definition: every `apply-templates`
  card expands with the **matching child templates at that site for the current
  frame** — colour chip, name, match count (→ descend), or `✗ tried` / `— not tried`
  under the ordered choice; a tried-and-failed row renders in warning colour because
  that row *is* the authoring signal. **A matched row hovers like every other list in the
  editor**: it lights exactly the output its template wrote at this site, in the colour
  its chip is showing (2026-08-28) — while the card head lights the whole dispatch, the
  same two levels a conditional's head and its branch cards give. The "set of matching
  child templates to switch into" is thereby anchored to the instruction that dispatched
  them — which is also
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
`ShapeshifterDoc` — which holds the project model and this metadata and nothing else
(Q2) — never in the engine `Project` or the compiled graph. Zero-match templates render
dimmed, not hidden — finding them is half the point. The panel header for the selected
template shows the mode-graph strip
("dispatched from: `record` (body pos 2)") — the static complement to breadcrumb
ancestry, and the answer when there are no matches to navigate.

Below the templates, the **pattern library** lists the config's named
`CombinatorPattern`s — plain patterns and whole step sequences alike (the prototype's
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

- **No data yet**: the root frame's content variable is empty, every count reads `—`, and
  what the screen offers is the way to get data — pick a stream, or step a record through
  the pipeline. **Data is never the document's** (Q2, ruled 2026-08-28): it arrives from
  the stepping pipeline or from ordinary processing, so the doc persists the project model
  and nothing else. That deletes a whole class of problem the earlier draft carried — a
  size cap, a "grab from stream" action, samples going stale against a feed that moved on,
  and an exported config quietly carrying production data with it — at the cost of the
  editor never being usable entirely on its own, which is the honest shape of a tool whose
  subject is data-driven execution.
- **Data, not yet run**: it runs on its own. Auto-run on edit, debounced (Q6, ruled
  2026-08-28: always, with no size threshold flipping to manual — one rule the user can
  hold in their head, and the frames-are-cheap assumption is testable later against real
  configs rather than guessed at now). `Ctrl+Enter` still forces a run, matching
  stepping's refresh.
- **Ran, template matched nothing**: selecting it shows the mode-graph strip ("nothing
  applies into mode `values`" vs "tried 212 times — nearest failed attempts here, here,
  here"), which is G3 earning its keep.

### 5.8 Messages, and profiling that is always on

Messages (`Severity` INFO→FATAL, collected not thrown) get the Stroom treatment: a log
pane, and — where a message carries a position — marks inside the owning frame's content
renderer and a severity tint on the owning template's row, mirroring how stepping colours
its pipeline tree.

**Profiling has no mode: every editor run profiles** (proposed 2026-08-26). The the prototype UI
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

### 5.9 Accessibility, and the two things it changed

Reviewed 2026-08-28, at Jon's prompt. The design is dense, hover-driven and carries
meaning in colour, so this is not a coat of paint at the end — two findings changed the
design itself, and the rest is scaffolding the mockups now carry so the GWT build inherits
it rather than retrofits it.

**Colour used as text must clear AA, and that is now checked.** Measuring rather than
asserting turned up a regression I had introduced myself: colouring dispatched output by
the child template (§5.4) promoted template identity colours from 10px swatches and 1px
underlines to 13px body text, and four of the nine failed 4.5:1 against the page
background — `delete_draft` at 3.87, `auth_login` 4.06, `update_profile` 4.08,
`generic_view` 4.47 — along with `--cap-8` at 4.45 and three of the ten swatches the
add-template dialog *offers*, which a user could apply to any template. All are lifted to
clear 4.5 with headroom, keeping their hue so identity is unchanged, and the mockup suite
now fails if any colour the editor puts on text drops below AA. The palette being
user-editable is exactly why the rule belongs in a check rather than in a memory.

**Focus mirrors hover, and that is the whole keyboard story.** Every link here is anchored
on a *list* — body cards, dispatch rows, variable rows — and lights something in the pane
opposite. So focusing one fires precisely what hovering it fires: tab to a body card and
its output region lights; arrow to a capture row and its span lights; focus an outer-scope
variable and the crumb segment that bound it lights, with Enter to step there. The
alternative — making three hundred output regions tabbable — would have been a worse
experience than the one it was trying to provide. A keyboard user never focuses a text
span, because the lists are the interface and the panes are their reflection.

The rest, briefly:

- **Hover-only affordances answer to focus too**: card actions and the crumb's step arrows
  reveal on `:focus-within`, not just `:hover`. An affordance only a mouse can reach is not
  an affordance for everyone.
- **Hit targets** are at least 24px (WCAG 2.5.8). The ✓/✕ pair that prompted this were
  about 14×16, and are gone entirely now that editing commits live.
- **The two unheaded panes are named.** Dropping the Content and Output titles was a real
  cost, and `aria-label` is how it is paid: the pane still has a name, it is just not
  drawn.
- **State changes are spoken.** A trace going stale is a button turning amber, which is
  silent; a polite live region says it once, deduplicated so a keystroke-by-keystroke edit
  does not chatter.
- **Motion is optional.** The 150ms fades exist to stop flicker, and to anyone who has
  asked for reduced motion they are motion — `prefers-reduced-motion` turns them off. The
  flicker they were softening is separately fixed by delegated hover (§5.5), so nothing is
  lost by removing them.
- **Structure**: the crumb is a `navigation` landmark with `aria-current` on the frame you
  are in; cards, dispatch rows and variable rows carry labels that say what they are and
  what value they hold, not just what they look like.

Still open, and worth naming rather than leaving implied: a screen-reader user gets the
*lists* but not the shape of the content pane — a nested match is a labelled span, not a
structure they can walk. If that matters in practice, the answer is probably a textual
outline of the frame tree rather than making the renderer itself navigable.

### 5.10 Superseded: the privileged source pane

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

A **minimal forms editor ships in A** as well (Q4, ruled 2026-08-28, against the draft
recommendation of JSON-only): templates and modes — add, rename, delete, swatch, mode
membership — and a template's match expression, which is the workbench's regex tab
without the step builder. That is the part of the config a user edits constantly and the
part where hand-edited JSON is most likely to be silently wrong; the body cards, which
need the trace beside them to be worth using, wait for B. The Ace JSON tab stays as the
escape hatch for everything the forms do not cover yet.

**Phase B — the frame navigator as the document's main tab.** A "Design" tab on the
Shapeshifter document presenter: template panel, breadcrumb, frame header, the two
variable panes with the content renderer — the §5 design. Server side, one new endpoint
pair on the shapeshifter resource: `preview` (project JSON + sample → trace: frames with
parent ids, content values or parent-slice refs, captures in execution order, output
spans, messages, attempts) and `patternInfo` (backed by `PatternInfo.inspect`). The Ace
JSON tab remains as the "Source" view of the same doc; Design and Source edit the same
`Project` and stay in sync. Data is supplied to `preview` by the caller, not stored
(Q2).

**Phase C — the frame navigator inside stepping.** `SteppingPresenter` chooses
per-element presenters; a shapeshifter-aware element presenter can replace the generic
four-pane view with the frame navigator, fed by the stepping record as sample. Same
widget, second mount. This is where "navigate the active templates for a given step
through the data" meets Stroom's step-through-records: stepping moves between records,
the frame navigator moves within one.

Phase B before C is deliberate: the document editor drives its own run, free of
stepping-session mechanics, so the novel UI iterates without dragging the stepping
protocol along. It still needs data from somewhere (Q2), and in B that somewhere is a
stream picked in the UI and passed to `preview` — the same bytes stepping would give it,
without the stepping session.

The structured GWT editor is a real cost (the the prototype body editor's card list, the
per-match-type forms). Phase A's forms-plus-JSON is the hedge: the editing users do most
is native from the first release, everything else is steppable and honest in Ace — which
makes the Design tab's scope a quality decision rather than a blocking one.

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
│ ┌ Templates ──────┐ ┌ ◀▶ source › record ◀2/3▶ › kv-pair ◀2/4▶  matches ◀2/12▶┐│
│ │ ● source   doc  │ ├ INPUT ──────────────────┬ VARIABLES ─────────────────┤ │
│ │ root            │ │   ¦key¦=╔"alice smith"╗ │ ▾ this match               │ │
│ │ ● record    3   │ │   ← child matches lit,  │   content    bytes[19]     │ │
│ │ mode: fields    │ │     gaps clickable      │   ▪$key   ←$1 "name"       │ │
│ │ ● kv-pair   12  │ │   ← hovering a variable │   ▪$value ←$2 "\"ali…"      │ │
│ │ mode: values    │ │     lights its span here│ ▾ child stores             │ │
│ │ ● iso-time  3   │ │                         │   $text  seq[4] ▸          │ │
│ │ ● quoted    1   │ │                         │ ▾ outer scope              │ │
│ │ ○ mac-addr 0·8t │ │                         │   $ip ← record "10.0.0.7"  │ │
│ │                 │ │                         │ ▾ params (none declared)   │ │
│ │                 │ ├ TEMPLATE ● kv-pair · mode fields · from record ─────┤ │
│ │                 │ │ match [regex (\w[\w ]*?)=…]│ OUTPUT                  │ │
│ │                 │ │ body  1 text "<data name=\""  ← click a card to edit │ │
│ │                 │ │       2 value-of $key         ← its region lights up │ │
│ │                 │ │       3 apply mode=values       here, alongside it   │ │
│ │                 │ │         ● quoted 1 → descend  ← click follows        │ │
│ │                 │ │         ○ iso-time ✗ tried                           │ │
│ │                 │ │       4 text "\"/>"                                  │ │
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
`record` matches lit; the body's single `apply` card shows `record 3 → descend`, and
clicking it follows; the output quadrant beside it = the whole emitted document, one
region per record dispatch. Descending never changes the layout, only the frame.

Stepping mount (Phase C): the same centre replaces the generic Code/Input/Output panes
for the ShapeshifterParser element; Stroom's step toolbar moves between records, the
breadcrumb moves within the record.

## 9. Rulings

Put to Jon and ruled 2026-08-28. Three went against the draft recommendation (Q2, Q4,
Q6); those are the ones that changed the design, and each is worked through where it
lands rather than only recorded here.

| # | Question | Ruling |
|---|---|---|
| Q1 | Is GWT the target, per Stroom convention, for the whole of §5? | **GWT, Stroom conventions, no second UI stack.** The content renderer, breadcrumb and variable panes are bespoke DOM widgets either way, so GWT costs nothing this design needs. |
| Q2 | Where does sample data live? | **Nowhere — the document never holds data.** It is supplied by the stepping pipeline or by ordinary processing; only the project model (and editor metadata like colour overrides) is persisted. This replaces the draft's embedded, size-capped sample: no cap, no "grab from stream" field, no stale samples, no exported config carrying production data. Worked through in §5.6, §5.7, §6. |
| Q3 | G1: ordering contract or explicit parent in `onMatch`? | **Explicit parent identity.** One parameter now beats a javadoc contract forever — parentage is the whole navigator, so it is not something to infer. |
| Q4 | Phase A's editing surface: raw JSON as the *only* editor until Phase B? | **No — a minimal forms editor ships in A**: templates and modes (add, rename, delete, swatch, mode membership) and a template's match expression. That is what users edit constantly and where hand-edited JSON is most likely to be silently wrong. Body cards still wait for B, where the trace sits beside them. |
| Q5 | Within-parent stepping: hover-revealed per-crumb arrows, or delete them? | **Ship hover-reveal**, delete if it still reads as clutter in use. |
| Q6 | Auto-run on edit (debounced) or manual? | **Always auto, debounced** — no size threshold flipping to manual. One rule the user can hold in their head; whether big samples need an escape hatch is testable against real configs later rather than guessed at now. |
| Q7 | Does Phase C replace the generic stepping panes, or add a fifth "Trace" pane? | **Replace** — the frame navigator subsumes Input/Output; keep Log. |
| Q8 | Content renderer depth: direct children only, or all descendants nested? | **All descendants**, direct children prominent, deeper levels quieter — the survey view at the root frame needs it. |
| Q9 | Windowing/minimap for large content values — in scope for B? | **Defer**; window around the current position first. |
| Q10 | Global undo/redo — the only answer to "I made a mistake" now that the workbench, guard/limits and body cards commit live. In scope for B? | **Yes, in B.** A live-editing surface with no undo anywhere is a regression from the prototype's snapshot history, not a simplification. |

**Second round, ruled 2026-08-28** — from a pass over accessibility and the editing
mechanics:

| # | Question | Ruling |
|---|---|---|
| Q11 | Four template colours (and one capture hue, and three offered swatches) fail 4.5:1 now that they colour output text. Retune, or stop using them as text? | **Retune, and check it.** Hue kept, luminance lifted, and the suite fails if any colour used as text drops below AA — the palette is user-editable, so the rule belongs in a check. |
| Q12 | How should a card editor commit or cancel? | **Live commit everywhere, Escape reverts** (§5.6) — the ✓/✕ pair goes, and the snapshot covers structural edits as well as text. |
| Q13 | How far should dragging an instruction go? | **Within a list, and into or out of a branch** — the move you want when something should become conditional. Keyboard equivalents are mandatory, not optional (§5.6). |
| Q14 | What should Back/Forward restore? | **Frame + selected template** (§5.3), so a nav-panel click is undoable. |
| Q16 | Should a point and a click in the content pane mean the deepest match, or the one at this level? | **This level**, in the immediate child template's colour, so the structure is discovered by descending it (§5.4). Ctrl-click keeps the deep jump. |
| Q15 | What should the content pane draw permanently — it had four channels answering two questions? | **The capture tint, and nothing else** (§5.4): a faint dotted rule in the same colour covers whitespace captures, match extents are revealed by pointing at them or at their template, and nothing fills. Jon's scheme; my own proposal had kept depth-graded match lines, which was still two channels for one question. |

One question the mockup answered by building rather than asking, and worth a ruling if you
disagree with it: §5.6's conditionals now render their branches as **nested card lists**
where the prototype drilled down into containers breadcrumb-style. The model is a card list under
either rendering, so it can be switched without touching the trace, the paths, or the
editors.
