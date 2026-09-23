# Design 44 — The workbench: one kind, a sample, and a library of parts

*Proposed 2026-09-21, from the owner's first sitting with the workbench in a browser: "the
pattern workbench has no sample input and there is nowhere to click to ok/cancel pattern
changes"; the tab bar not following a click after a conversion; "I think the type should be a
drop down selection as tabs make it seem more like these are different areas to configure
rather than the choice of match type"; and, on composition, "how would we compose with
combinators, e.g. combine regexes into a composite pattern?" §1 built the same day; §2 and §3
planned.*

Design 18 §5.6 is the design of the workbench; design 43 §4 placed it in Stroom as
presenters and built it through phases A2 and B. This design takes three things the first
real use found — one wrong shape, one unbuilt half, one missing piece of the model — and
settles each. It does not reopen design 18's rulings on live commit or on the workbench being
in place rather than modal; §1 restates why those hold.

## 1. The match kind is one choice, not four places — built 2026-09-21

**What was wrong.** The match editor was `LinkTabPanelView` with four tabs: Regex, Pattern
tree, Parts and Other, the last a kind picker over a JSON wire-form editor. Read as a Stroom
user reads tabs — separate areas of a thing, each with its own settings — that is four
things to configure, and the actual choice, *what kind of match is this*, was hidden in the
fourth. There was also a fault the shape invited: a kind conversion is asynchronous (`explode`
and `print` go to the engine), and `setTemplate` had a rule "if Other is open, stay on Other"
that ran on the reply while the outgoing tab was still the selected one, so Regex → tree →
parts → Other → Regex left the body on Other until a second click found the match already a
regex and simply showed it.

**The shape now.** `MatchEditorPresenter` has a view of its own: a **Match kind** picker —
regex · pattern tree · parts · delimiter · source · all · named (`MatchKind`, which is also
what the chip's word comes from) — over a body that is the kind's form. Regex, tree and
parts keep the forms they had (`RegexPresenter`, no longer `RegexTabPresenter`, since there is
no tab); delimiter is its four fields (`DelimiterPresenter`, the Other
tab with the picker and the editor taken out of it); source, all and named have nothing to
set, so the body is a sentence saying what the kind means. **Choosing a kind means "make it this kind"** (2026-09-22, on the owner's report that source
could not be switched back to regex). Switching away from a regex to source already discarded
the regex; refusing the way back because a source "cannot be re-expressed as a regex" made the
picker a one-way door. So a kind with no pattern to carry — source, all, named, delimiter —
arrives blank in either direction, and the one refusal left is a match that *does* hold a
pattern the new kind cannot carry: a sequence of several parts, whose takes, seeks and reads
are not a single pattern. `Templates.blank(kind)`, `singlePattern` and `holdsPattern` are the
arithmetic, pinned in `MatchKindTest` — the presenter itself no JVM test can reach.

**And what a kind held is kept for the session** (2026-09-22, the owner's follow-up): moving
through a kind to reach another — a regex to a source and back — should not cost what was
typed. The match editor keeps the last match of each kind for its subject, refreshed on every
reading of it, and a kind with nothing to carry into it arrives at that draft rather than
blank. Kept only while the workbench is open on that subject: closing it, or retargeting to
another template, clears the drafts, because a kept regex is this template's and not the next
one's. Only the current kind is ever in the document — the rest are drafts, which is why they
do not survive the session that made them.

**The body always shows the model.**
Choosing a kind converts the match where the engine can say what it means — regex ⇄ tree
through `explode` and `print`, either into a one-part sequence — and the plain kinds replace
outright (a delimiter arrives as one field per line, `"\n"`, to be edited); a conversion the
engine cannot make warns and the picker springs back to the kind the match is. No sticky
rule: the reply to a conversion shows the kind the model now has, whatever was showing when
the request went. The tree editor's node kinds lead with `regex` and a new node is a regex,
since a tree is mostly built of them (§3).

**The wire-form editor is gone.** The Source tab is the wire form of the whole document; a
second JSON editor of one match, in the workbench, was the Other tab's way of reaching kinds
that had no form, and every kind has one now.

**Why there is still no OK/Cancel** (design 18 §5.6, revised 2026-08-26, restated because the
question came back): the workbench is not modal — clicking another template retargets it in
place — so a draft that Cancel could discard would be a trap the moment the author browsed
away. Every field commits live, as everywhere else in the editor; regret is answered by
global undo (design 18 Q10, ruled *yes, in B*, still unbuilt), not by a per-surface Cancel.

## 2. The sample and the live matches — built 2026-09-21

The half of design 18 §5.6 the workbench never got: regex101's left column. Design 43 §4.1
listed it — "workbench: sample and live matches — renderer, needs a `match` endpoint (B)" —
and phase B built the trace and the navigator instead, so until today the only match feedback
was indirect, through the content pane after a rerun of the whole project.

**No `match` endpoint: the trace already knows.** The draft of this section planned an
endpoint that compiled a match and ran it over a sample, returning spans. Built instead: the
run reports what it already has. `Instrument.onGroups` follows a frame's `onMatch` with every
group of the match — bound to a capture or not — by number, name where the match names it (a
regex's named group, a tree's label, a part's label; `CompiledMatch.groupNames()` says which),
and its place in the frame's content, placed exactly as a capture is (a group the content does
not hold, a lookahead's say, is `NOT_A_SLICE`). `TraceRecorder` keeps them, `TraceChars`
converts them to characters, `ShapeshifterTrace.groups` carries them and `TraceModel.groups`
reads them. One match loop, one conversion, one wire, and the navigator can show unbound
groups too whenever a pane wants them. Only a watched run pays.

**The sample is an experiment on the document's own run path.** `SamplePresenter` sends
`preview` a project of one template — the subject's match, encoding and consumption under the
document's source settings, at the root, with no guard, limits, declarations, captures or body
(`Templates.experiment`) — so every match is found and nothing else runs; the trace that comes
back is read for that template's frames, their groups and the places it was tried. Regex, tree,
parts and delimiter kinds; source, all and named have no pattern to try and the pane says so.
The text is seeded from the cursor's frame, else the document's sample, when the workbench
opens or retargets to another template; what the author has typed stays theirs across a
retarget. Every keystroke reruns on a 400 ms debounce, one request in flight, the same text
and match never sent twice.

**What it shows.** The sample painted with `Marks`, as the content pane paints: each match an
outlined span in the template's colour, each group inside it a span in its capture hue
(`RegexPresenter.hue`, by group number, so the pattern map and the sample agree), a gap mark
where the template was tried and did not match; a summary line — *3 matches · tried at 5
places*; and a table, one row a match with its offset and length and every group's name and
value, a dash for a group that took no part. **Isolation**: a group clicked in the regex map,
or a labelled node selected in the tree, leaves only that group's spans painted.

**Layout.** The workbench's centre is a west/centre split: the sample at 420 px on the left,
the match editor on the right; guard and limits beneath both as before.

**Not built: per-node spans for unlabelled tree nodes** (Q4). A labelled node is a group and
gets a span for free; an unlabelled one would need the compiler to label it synthetically.
Wanted only if labels prove not enough.

## 3. A library of parts, in the project

Composition is already the model: design 38 §2 — a match is a tree, a regex is one kind of
leaf, `sequence(ref("IP_ADDRESS"), tag(" "), ref("NAME"))`. What is missing is *define once*:
`ref` resolves against the standard library alone, and design 38 §4's audit recorded the
project's own library as not built because no fixture or real file had needed one. The
owner's question is that need.

### 3a. The model, the engine and the wire — built 2026-09-21

**Model** (config module): `Project.patterns`, an ordered map of name to `PatternNode`, on
the wire as `"patterns": {"HOSTNAME": {…}}`, written only when there is one; `readPatterns`
and `writePatterns` exchanged on their own like a pattern node is. A blank name is refused
at read. `Project` gained `withSource`, `withTemplates` and `withPatterns`, and every place
the client rebuilt a project by hand uses them, so nothing can drop the library on the way.

**Engine.** `MatchCompiler` carries the project's parts to `PatternCompiler`, which compiles
a tree against the standard library alone when the tree names none of them, and otherwise
against a library of the standard entries plus **the parts the tree reaches** — directly or
through one another — lowered by the same compiler instance, so a labelled node inside a part
carries its cast into the naming template's plan, and a label in a part the tree never names
cannot collide with one of its own. **A project name may not be a standard-library name**:
`refuseShadowing` runs once per project, used or not, so a `ref` never means two things and
the standard entries stay what the documentation says. The config module cannot check this —
it does not know the library — so it is the compiler's refusal, not the reader's (Q3 amended).
An unknown name and a cycle are the regex library's own refusals, wrapped as the template's
`ConfigException` as before. `PatternPrint` takes the parts too and prints a project `ref` as
its definition, the project's before the standard library's, refusing a cycle by name.

**Wire.** `ShapeshifterPatternRequest.patterns` — the library in its wire form, null for
none — so `print` can render a tree that names a part; both client callers send it. The
`library` endpoint is unchanged: the client holds the project, so it holds the parts.

**Pinned.** `ProjectLibraryTest`: a `ref` matches as the part inlined would, parts reach one
another with the standard library beneath, shadowing, an unknown name and a cycle are refused
with their names, the library round-trips in order and is absent when empty, and the printer
resolves and refuses. `library_parts` in the fixture corpus: the text-steps line composed of
four parts, byte-identical output to `progressive_text_steps`. The print endpoint pinned with
and without the library.

### 3b. The client — built 2026-09-21

- **Patterns in the nav panel**, a section beneath the templates' modes (design 18 §5.9's
  library pane, editable): a row per part — name, *used by n* or *unused* — with a row id of
  its own (`Patterns.rowId`, never a template's uuid). The toolbar's link icon adds a part (a
  name, then an empty regex leaf to edit); with a part selected, the edit and delete icons
  rename and remove it — a rename follows every `ref`, in templates and in other parts, and a
  removal is refused while anything names it. `NamePresenter` (the mode-name prompt,
  generalised) asks for the name, refusing one the project or the standard library has.
- **Selecting a part opens the workbench on it.** The workbench is subject-agnostic now: a
  template's match, or a part. For a part the kind row is hidden (a part is a tree), guard and
  limits are hidden (they are a template's), and the sample tries the part as a one-template
  experiment whose match is `{"pattern": {"ref": NAME}}` with the library beside it — so the
  part's labels are the groups the sample paints. Closing the workbench on a part leaves it:
  the document row is selected, so the next edit does not reopen it.
- **The tree form edits a `Subject`** — a template's match or a part — read live from the
  host and written as one rewrite of the project, so an edit that also touches the library is a
  single replacement. Two toolbar actions (design 44 §3): **extract** (link icon) names the
  selected node, makes it a part less its label — the label is the tree's use of it and stays —
  and leaves a `ref` in its place; **inline** (unlink icon) puts the part a `ref` names back,
  keeping the tree's label. A part's own root cannot be extracted.
- **The `ref` picker** in the node editor lists the project's parts before the standard
  library's.
- `Patterns` is the model of it, JVM-tested: uses, define, remove, rename, extract, inline, and
  a node walker that treats a label's body as a node of its own.

## 4. The tree as Stroom draws a tree — built 2026-09-22

*From the owner, on the first sitting with the workbench: "could we make the pattern tree look
more like a stroom expression tree? Could we also lose the JSON source pane as we don't need
it? Is parts the same as pattern tree or is the UI just confusing the mechanism for building
combinators?"*

**The tree is boxes and connectors.** It was a nested `<ul>` of text rows, which read as a
file listing rather than as a composition. It is now built from the widgets Stroom's own
expression editor and pipeline structure editor are built from — `TreePanel`, `Box`,
`TreeRenderer2`, `BracketConnectorRenderer`, `CenteredParentTreeLayout` — with the expression
editor's own box classes, so a composition here reads the way a condition reads there: a
container is a short box carrying the combinator's word, a leaf a wider one carrying what it
matches, and the shape is read across the boxes rather than down an indent. `PatternItem` is
what the view lays out — path, parent path, label, container, labelled — identified by its
path, since two nodes of a tree can be the same value and still be different nodes. Selection
and double-click are the presenter's as before, by path; the toolbar is unchanged. Not yet
taken from the expression editor: dragging, and editing inline in the box rather than in a
dialog.

**The wire-form panes are gone**, from the tree form and the parts form both. Every node kind
and every part kind is reachable from the rows, so unlike the guard — whose rows genuinely
cannot express an `or`, a `not` or a comparison of two references, and which keeps its wire
form for that — they were a second way to say what the rows already say. The Source tab is the
document's wire form and the place to paste one.

**Parts is not the pattern tree, and the name said otherwise.** A tree is *composition*:
sequence, choice, optional, repeat, peek, not, over leaves and `ref`s, lowered to one plan on
the regex engine with backtracking inside — this is the combinator builder, and §3's library
is what makes its parts reusable. A `parts` match is *framing* (§3b, design 39): patterns
interleaved with `take`, `seek` and `read`, run in order with **nothing backtracking across
parts**, and lengths taken from a label matched earlier or from a variable — which is what a
length-prefixed or binary format needs and no regex can say. `[pattern A, pattern B]` is
therefore not `sequence(A, B)`; the one-part case is the overlap, which is why the kinds
convert there. The word "parts" read as "pieces of a pattern", which is exactly what a tree's
`sequence` is, so the picker now says **framed sequence**, and every kind carries a line
saying what it is beneath the picker (`MatchKind.note()`).

## 5. The data has a door of its own — built 2026-09-22

*From the owner: "It's a bit confusing — the project run, the paste a sample to run over
button, the Run, Cancel button. Perhaps the sample input page needs to be separate. Was there
a plan to add an input data chooser?" There was: design 43's `SampleSourcePresenter`, the one
piece of phase B that had waited on a decision — which Stroom picker to reuse. Ruled: the
**stepping** stream list, because phase C mounts this navigator inside stepping and the two
should name a record the same way.*

**One door.** `SampleSourcePresenter` is a page over `SteppingMetaListPresenter` — the same
list stepping picks a stream from, with its filter — plus part and record, and beneath it a box
to paste into for when there is no stream to point at. A stream selected wins; otherwise the
pasted text is the sample. It is reached from the panel (§5a).

**The content pane is a content pane again.** It held the paste box, took the pane over, and
carried a Run and a Cancel of its own — the second Run the owner was looking at. Its empty
state now points at the chooser and nothing else; `ContentPaneUiHandlers` loses `onUseSample`
and `onCancel`.

**A record is read on every run, never held.** `ShapeshifterPreviewRequest` carries either the
text or a `SourceLocation`; where it names a record, the server reads it through `DataService`
— the same fetch the data viewer and stepping use, under the caller's own permissions — and
runs over that. So design 18 Q2 holds all the way down: the document keeps the project and
nothing else, a feed that has moved on is seen to have moved on, and an exported configuration
carries no data. A record that cannot be read is a message, not an error page: the project may
still be sound.

**What the editor holds** is `SampleSource` — a location and a label, or text and a label —
which is what `ProjectHost.getSampleSource()` hands out. `setSample(String)` is gone; nothing
in the editor can supply data without saying where it came from.

## 5a. A page of the project, not a dialog over it — built 2026-09-22

*The owner, on seeing the chooser as a dialog behind a button: "Rather than adding that button
next to Run could we instead add source data selection above TEMPLATES in the left pane, and
selecting it shows the Sample data page filling the screen right of the template selection
pane?"*

Right, and it makes the sample what it is — part of the project's shape, not an errand. The
panel's first row is **Sample data**, above the document and the templates, saying what the
sample is (`syslog 1234 · record 7`, `pasted · 42 lines`, or *none*); selecting it shows the
sample page in the whole of the area right of the panel, as selecting a pattern part shows the
workbench. The panel is titled **Project** now, since it holds the sample, the document, the
templates by mode and the library's parts.

**Which door, said at the top.** A picker — *a stream* or *pasted text* — heads the page and
the half beneath it follows (the owner, on the first cut having both at once and a rule about
which wins): the two are alternatives, so the page says so rather than leaving it to be
inferred.

**The stream half is Stroom's own.** `SourcePresenter` with the stepping stream list inside it,
which is the arrangement the stepping tab shows: pick a stream above, read the record below,
with the filter button on the list and the part, record and character navigation the viewer
already has. **The record the viewer is showing is the sample** — choosing a stream, or
stepping its parts and records, is the choice — which is why the first cut's part and record
boxes are gone: they were a second way to say what the viewer says, and a worse one, because it
showed nothing. `SourcePresenter` gained one hook for this, `setOnLocationChange`, told the
record it settled on; paging within a record is reading, not choosing, so only the address
counts.

**The paste half** is the Ace editor the rest of the tab uses, filling its half with no
heading, padding or border of its own — text is text, and the editor is how this tab shows
text everywhere else. It commits on a change, as the other Ace panes here do.

Nothing on the page says what is chosen: the panel's row does, which is where the eye is when
choosing and the only place it needs saying.

There is no OK and no Cancel, and the one **Run** left is in the document's own toolbar beside
Save, where a Stroom document tab keeps the things it does. Not through `HasToolbar`, which
publishes a *tab's* buttons and so takes them away when another tab is selected:
`ShapeshifterPresenter` overrides `createToolbar()` instead, so Run is there whichever tab is
showing — Design and Source edit one project, and running it is the document's verb, not a
tab's. (The base constructor calls `createToolbar()`, so the field it assigns must have no
initialiser of its own.) The design tab says whether a run is possible and the button follows.
The crumb keeps what is its own: the ancestry, the stepper, and what the run is doing.

## 5b. The history is the browser's — built 2026-09-22

*From the owner: "the history buttons are a bit confusing as they are just more arrows the
user has to understand. Could we just use the browser history when we are on this page?"*

Design 18 §5.3 had said so already — the navigation states are "the state a GWT implementation
hands to the platform's own place history rather than inventing a second one" — and phase B
invented the second one. The two `◀ ▶` anchors at the crumb's head are gone; the crumb keeps
one arrow pair, the stepper's, which is what §5.3 wanted of it.

**How two things share one history.** Stroom's content tabs already own it, as a counter over
a list they keep themselves (`ContentTabPanePresenter`), and GWTP's place manager is bound to
`InactivePlaceManager`, so nothing else competes. The navigator pushes `ss<n>`: the tab pane's
`Integer.parseInt` of that throws and its existing catch ignores the press, and this handler
ignores the tab pane's numeric ones. On a press the navigator moves by as many states as the
token moved, and only while its tab is attached and visible — a back press while another tab
is showing belongs to the tabs, not to a cursor nobody can see. `Alt+←/→` call
`History.back()`/`forward()`, so the keys and the browser's own buttons are one history.

**Nothing survives a reload, and nothing should.** The states name frames of a run, and the
data a run is over is never the document's (Q2), so a restored cursor would point at what is
no longer there. The token is a counter, not a bookmark — the same contract Stroom's tabs have.

## 5c. Stepping the matches, from where the run leaves you — built 2026-09-22

*From the owner: "after running with a line splitter there is no control to step line matches",
and then "I don't see any ability to navigate matches across the data".*

Design 18 §5.3 made the whole-input stepper "the single always-visible stepping control", and
phase B built it so that it appeared only once the cursor was already inside a match — which is
never where a run leaves you. Two corrections, and the control is now there whenever a run has
anything to show:

- **At the document** the stepper steps the matches of the template the panel has selected,
  reading `–/12` with the forward arrow as the way in to the first.
- **With nothing selected** it steps the document's own children — the matches as they were
  found, whatever template each is of — under the word *matches*. Every run has those, so
  after any run there is something to step.

**Selecting a template is navigating to it.** Design 18 §5.3 says as much — "select a template
in the nav panel and the crumb rewrites around a different frame" — and phase B moved only the
strip, leaving the crumb on the document, the input and variables on nothing, and the stepper
with no match to be at. The cursor now moves to the template's first match, so the whole top
half follows the panel; a template that matched nothing leaves the cursor where it is, and its
row and the strip say it matched none. Descending into a frame still selects its template's row
without throwing the cursor back to that template's first match.

**And the stepper never disappears while a run has matches.** A selected template with no
matches used to hide it; it falls back to the document's children, as it does when nothing is
selected.

**And the template has to be one whose matches exist.** The navigator showed a run with eleven
attempts, eleven matched, and no frames at all — because the New Template dialog defaulted
`consume` to true, making every template made in the editor an **eater**: D36's "its matches
exist to advance the cursor, not to count", which opens no frame, binds no capture and writes
no output. So there was nothing to navigate, nothing to step, and nothing in the panes; the
count on the row said 11 and promised all three. Three corrections: a new template is an
ordinary one, the workbench sample's experiment is never an eater whatever its subject is, and
an eater's row reads *11 eaten* rather than *11*. The dialog's tick is labelled **Skip data** now and says what it
does — its old help, "whether a match consumes its bytes, so the next template starts after
it", describes what every match does anyway, which is how it came to be ticked. *Eater* is
D36's word and the right one in a decision record; the author is not trying to eat anything,
they are trying to skip a header, so the editor says skip, the row reads *11 skipped*, and the
wire keeps `consume` (renaming it would rewrite every fixture and the DS3 migration's output
for a word the author never sees).

**And the tick has to be on screen to be ticked.** The dialogs' forms were flow panels inside a
scroll panel with `dock-container-vertical` on them — a flex column, whose children shrink by
default — so past a certain number of fields the later ones were squashed to nothing instead of
the panel scrolling: the skip tick, the params, the encoding. Stroom's own forms are
`max form-padding form` and nothing else, which is what these are now; the four that scroll
drop `max` as well, so the form is as tall as its fields and the panel scrolls to them.

**And the run has to be on screen for any of it to be seen.** Choosing the sample is a page
that fills everything right of the panel, so a run started from there left the author looking
at the picker — no crumb, no content, no stepper, which is what made the control look missing
twice over. Run, and `Ctrl+Enter`, now leave the sample page for the navigator; choosing a
sample does not, so several streams can be tried without the page being pulled away. Run also
selects the Design tab, since it can be pressed from Source.

Inside a match it is as before: `matches of kv-pair 6/12`. And it now **reads as a stepper**
(the owner, having missed it twice): Stroom's own step buttons — first, previous, next, last,
the stepping tab's icons and enablement — at the **head** of the crumb row where the eye
starts, carrying the label and the count with them. Not in the document's toolbar beside Run,
which was the other candidate: phase C mounts this navigator inside stepping, where Stroom's
toolbar already steps *records*, and two identical button groups meaning different things is
the confusion §5.3 set out to avoid; the toolbar is also the document's, and stepping a trace
means nothing on the Source or Documentation tabs. The label is what tells the two apart, so
the buttons stay with it. The keyboard is unchanged (`Alt+Shift+←/→` whole input, `Ctrl+Alt+←/→` among siblings, `Alt+↑`/`Alt+↓` up and
down), and a segment's own ◀ ▶ still appear on hover for the within-parent scope.

## 5d. Skipping is a tick in the open — built 2026-09-22

The `Line` template matched 1001 times and framed nothing, because a new template was created
as an eater. The default is fixed (§5c) and the field relabelled from "eater" to **skip data**,
but the owner then asked twice where the tick *was*, having looked at the workbench and at the
template strip. It was in the Edit template dialog, fourth field — which is the wrong place for
it.

Everything else in that dialog is identity: name, colour, mode, params, encoding. Skipping is
not identity. It is one bit that decides whether the template frames anything at all, and it is
the answer to the one failure that looks like a broken editor — a template that reports matches
while the navigator stays empty. A switch whose whole job is to explain an empty screen cannot
live two clicks behind that screen.

So the strip carries a **skip data** tick, on a line of its own **between the match and the
body** — which is where it acts, and reads in the order the engine works in: the match is found,
then skipping decides what becomes of it, then the body. It is shown for a template and not for
the document, disabled when the project is read-only, and it writes the project itself rather
than routing through the dialog — the only identity field the strip writes, because it is the
only one that is a single bit with a visible consequence. The tick is set on every refresh
without firing, so a refresh cannot write the project back. The dialog keeps its copy; the two
are the same bit and either will do.

**The body still runs, and the tick must not say otherwise.** The obvious reading of "skip
data" — no further processing, so grey out the body — is wrong against D36 §3, which is
explicit: a consume-marked match does not advance the match count, but *its body still runs*.
The canonical eater in that ruling is precisely a body: one `emit_error` reading "unrecognised
line: …", the authored replacement for DS3's engine-generated skip warnings. Disabling the body
instructions would disable the single most idiomatic thing a skipping template does. What
skipping actually costs is the **frame, the count and the captures** — and captures are not
merely ignored but *refused at compile time* (`Compiler.refuseCaptures`: an eater's matches have
no index to bind at, and a binding would trip the first-match restart of a list, D36 §8b).

So the line carries a note that says which of the two it is, and turns into an error when the
tick and the captures contradict each other:

```
skip  ☐ skip data   off: each match opens a frame — it counts, binds its captures and runs its body
skip  ☑ skip data   the cursor advances past each match and nothing is framed, counted or
                    captured — the body still runs, which is where an emit-error goes
skip  ☑ skip data   this template binds 3 captures, and a skipped match has no index to bind
                    at: the project will not compile until they go or the tick does
```

That last one is the compiler's refusal said at the moment the author causes it, rather than at
the next run.

Three things were ruled out on the way, and are worth recording so they are not re-tried. The
dialog's form was not at fault: Stroom's own dialogs are a `max form-padding form` flow panel,
`.form` is a flex column with `overflow: auto`, and the shapeshifter dialogs now match it — the
earlier `dock-container-vertical` was wrong and is fixed, but it was never what hid this field.
`CustomCheckBox` renders a fixed 20px box whether or not it carries a label, so a label-less
tick is not an invisible one. And the strip's own tooltip still said "Edit name, mode and
consume…" — stale vocabulary from before the relabel, now naming what the dialog actually
holds.

## 5e. All stepping at the right edge — built 2026-09-22

§5c put the step buttons at the **head** of the crumb row, on the reasoning that the eye starts
there. That was wrong twice over. It pushed the breadcrumb — the thing whose whole job is to
read left to right as the cursor's ancestry, from the document down — off the left edge it
wants; and it put this stepper somewhere no other stepping control in Stroom lives, when the
argument for adopting Stroom's step buttons at all was that stepping should look the same
wherever it appears. Stroom's pagers and steppers sit at the **right** of their bar.

The crumb row is now: breadcrumb at the left, run state after it, then everything pushed right —
what the stepper steps and the cursor's place in it, then the four step buttons hard against the
right edge, with the divider moved to their left. The buttons, titles, icons, enablement and
keyboard are all unchanged; only the order across the row is.

## 5f. The role is a choice, not a tick — built 2026-09-22

Three names were tried for D36's `consume` marker and all three misled, because each described
an action on the data — *eater*, *skip data*, *advance only as a switch* — when the marker
describes **what a match is for**. Both roles find the match, advance the cursor by it and run
the body over the matched content. The marker decides one thing: whether that match becomes a
record.

| | a record | advance only |
|---|---|---|
| cursor advances | ✔ | ✔ |
| body runs, over the matched content | ✔ | ✔ — usually one `emit_error` |
| output written | ✔ | ✔ |
| counts (`__match_count`, the match number) | ✔ | ✗ |
| captures bind | ✔ | refused when the project compiles |
| opens a frame the navigator can step | ✔ | ✗ |

So it is a **picker**, for the reason §1 made the match kind one: the author is choosing which
of two things this is, not turning a feature on. The strip's line before the body reads

```
match is  [ a record  ▾ ]  each match counts, binds its captures and opens a frame to step
match is  [ advance only ▾ ]  the cursor moves past each match and nothing is counted, captured
                              or framed — the body still runs over the content, which is where
                              an emit-error goes
```

`consume` stays the wire key — fixtures and DS3 migration are fixed — and "eater" stays engine
vocabulary in D36, where it is precise. The editor says neither.

The identity dialog's copy of the bit is **gone**. Name, colour, mode, params and encoding are
identity; what a match is for is not, and two spellings of one bit in two places is how three
rounds of "I can't see the checkbox" happened. An edit carries the role through untouched.

## 5g. What the UI owes the runtime — ruled 2026-09-22

Asked whether the editor should run advance-only templates differently from a real run.
**No.** The trace is a record of what happened, and a trace that quietly reframed skipped spans
as records would put an asterisk on every count in the strip.

The asymmetry that prompted the question is not semantic but a **hole in the reporting**, and it
is the whole of the original bug: `stopTiming` counts an advance-only template's wins — which is
why the strip said *1001 attempts · 1001 matched* — while `processEater` never calls
`instrument.onMatch`, so no frame is recorded. The editor was told the count and denied the
evidence.

The fix belongs in the trace, not the run: an `onSkip` event carrying the span, painted and
stepped in the navigator as a **skipped span, visibly not a record**. Runtime semantics
untouched; a one-byte eater over a large file is held by the cap the recorder already applies to
attempts — kept up to a cap, counted beyond it. Not yet built.

One deliberate divergence stands, and is the exception that shows the rule: the workbench sample
forces `consume=false` (`Templates.experiment`), so a candidate can be seen while it is being
authored. It is one template, explicitly an experiment, and labelled as one.

## 5h. The strip's left column — built 2026-09-22

Four rows, four keys, one column. `.ss-strip-k` was `min-width: 44px`, which fits MATCH and
BODY but not MATCH IS, so that row's control started further right than the others; and
`.ss-strip-content` added 4px to the body's own 12px, so BODY sat 4px right of everything above
it. The key column is now a fixed 64px that does not flex, and the content pane adds nothing —
every control starts at the same x and the body's cards line up beneath them.

The match chip was a chip: 5px radius, hand-picked 4px/10px padding, and a pattern clipped at
`max-width: 480px` with guard and limits crowding it from the right. It is a **text input** now
— Stroom's own `--control__border-color`, `.25rem` radius and `--input__padding--*`, so it has
the dimensions of every other input rather than dimensions of our own — and it **fills the
row**, the pattern taking the slack, because a pattern is the longest thing on the strip and was
the thing being clipped.

Guard and limits move to a row of their own, below the role rather than between it and the
match, so that the match and what it is for stay adjacent:

```
match     [ regex   .*[\n]?                                    ✎ workbench ]
match is  [ a record  ▾ ]  each match counts, binds its captures and opens a frame to step
guard     no guard · no limits
body      + instruction
```

## 5i. Three from a sitting with it — built 2026-09-23

**The guard row was short.** The match and role rows are as tall as the control in them; the
guard row holds only text, so it sat shorter and broke the rhythm of a column that §5h had just
straightened. `.ss-strip-line` now carries a `min-height` of one control —
`calc(1.2em + 2 * var(--input__padding--vertical) + 2px)`, from the same tokens the controls use
rather than a measured number — so all four rows are one height whatever is in them.

**A newline delimiter read as a space.** The chip said `split on " "` for a `\n` delimiter,
because `Templates.describe` quoted the delimiter raw and the browser collapsed the line break
to a space. It is the wrong answer to the only question the chip exists to answer. It now escapes
through `ControlEscapes`, the same helper the delimiter form round-trips through, so the chip
reads `split on "\n"` and the editor has one spelling of a control character rather than two.

**`apply-templates` demanded a select it should have defaulted.** The editor refused to write one
without a select. It was right that something was missing but wrong about whose job it was: an
omitted select means *this match's whole content*, which is the thing being dispatched when the
author does not say otherwise, and which `BodyCompiler.isWholeParentContent` already recognises
as its fast path. The model now says so — `ApplyDirective`'s compact constructor defaults a null
select to `RefExpression.group(0)` — so the compiler, the reader, the migration and the editor
all get the same answer, and the compiler is never handed the null it would have dereferenced.
Defaulted in the owning record rather than in any of the readers, for the reason the pattern
facts are: one place publishes what an omission means. The field stays, help text and all, and
now says that blank is the whole content.

**And the dialog stuck.** Dismissing that alert left the OK button spinning, because the
hide-request handler called neither `hide()` nor `reset()` when nothing was written — the
request was simply abandoned, and the dialog's buttons stay busy until one or the other answers
it. Not specific to `apply-templates`: the same shape appears **fourteen times** across the body,
captures, declarations, parts, pattern-tree and template-panel editors, so every validation
failure in the editor left its dialog dead. `NamePresenter` had it right all along, passing
`e::reset` as its alert's callback. All fourteen now reset.

## 5j. The document remembers where the data was — ruled and built 2026-09-23

Design 18 **Q2** (ruled 2026-08-28) says data is never the document's, and lists what that
buys: no size cap, no staleness against a moved feed, and no *"exported config quietly carrying
production data with it"*. It also names the price — *"the editor never being usable entirely on
its own"* — and that price is what an author pays every time they reopen a project and have to
find their stream again.

**Q2 is amended, not overturned.** It stays true of **data**. It becomes false of **a reference
to data**: `ShapeshifterDoc` now carries a `SourceLocation` beside the colours, which are the
precedent — editor metadata that is not the project. The difference between the two is real and
is what the conditions below are for: a colour means the same thing everywhere, and a stream id
does not.

Four conditions, each answering an objection rather than decorating one:

1. **Stripped on export.** `StoreImpl.exportDocument` takes a `Function<D, D>` applied before
   serialising — the seam `omitAuditFields` already uses — so `ShapeshifterStoreImpl` overrides
   the export to null the field. Elsewhere the same id is a different stream, or none, and an
   export that silently pointed at unrelated data would be worse than one that pointed at
   nothing. The git repository exports through the same `ImportExportSerializer`, so this covers
   version control too, and a project's history stops moving every time someone looks at a
   different stream.
2. **Written on an ordinary save, never on choosing.** `getSampleLocation()` is *read* when the
   document is written and never pushed, so picking a stream does not mark the document dirty,
   bump its version, or fire an entity event at anyone else holding it open. What is remembered
   is where the author was when they last saved for a reason of their own — which is how
   `colours` has always behaved.
3. **The reference, not the bytes.** A pasted sample stays out: stripping it from export would
   still leave real data in the database, in backups and inside a configuration artefact, which
   is a data-classification question rather than an editor one. `getSampleLocation()` returns
   null for a pasted sample, so pasting is remembered for the session and no longer.
4. **Three failure modes on load, not one.** Gone is the easy one. *Not permitted* matters
   because a stored id is readable by anyone who can read the document, whether or not they can
   read that stream. *Moved* — the id resolving to a different stream after a restore or
   re-import — cannot be detected at all, and is the reason condition 1 is not optional. A
   restored sample is marked as remembered; the first run that fails on it clears it, says the
   stream may have been deleted or may not be theirs to read, and leaves the author at the empty
   state rather than on a sample that will never work. A sample chosen by hand drops the mark, so
   an ordinary failed run still reports as an ordinary failed run.

What remains, and is a judgement rather than a defect: the document is shared and "the sample I
was working with" is personal, so two authors get last-write-wins. Condition 2 makes that no
worse than any other field they both edit.

**Condition 4 was built in the wrong place first, and the review caught it.** The recovery sat in
the run's `onFailure`, where no transport error ever arrives: `preview` catches a failed record
read and returns a *successful* answer carrying `compiled=false` and a FATAL message, because the
project may be perfectly sound. So the one case the condition exists for — reopening a project
whose remembered stream is gone — took `onSuccess`, kept the dead sample for ever, and told the
author "the project did not compile" about a project that compiles.

The fix is the rule the pattern facts already follow: **the side that knows publishes the fact**.
`ShapeshifterTrace` carries `sampleRead` as its own field rather than folding a second meaning
into `compiled`, so the editor asks instead of reading it out of a message. The crumb now
distinguishes the two, and the recovery lives where the answer lands.

## 5k. The mode a dispatch goes into is a choice — built 2026-09-23

`apply-templates` asked for its mode as free text, with the project's modes offered as the
field's *tooltip* — `setModes` set `field1.setTitle("Modes: …")` and nothing else. A mode is one
of a known set, the template's own mode field has been a picker since §1's reasoning, and a typo
here dispatches into a mode nothing declares. It is a `SelectionBox` now, with `root` as the
non-select entry exactly as the template dialog spells it, and the mode no longer travels through
the generic text fields at all.

## 5l. Where the global variables already are — answered 2026-09-23

Asked how to add a global variable, and whether the document should take instructions. Both
already exist, and the editor simply never says so.

Design 35 §4 is explicit: **there is one scope rule, not two scope kinds.** An author never marks
anything global; a declaration's scope is where it is written, and a declaration written outside
every other template lasts the run because nothing encloses it. That outermost place is the
**document template** — the one whose match kind is `source`, which `ReferenceCheck` identifies
as exactly that (`template.match() instanceof MatchExpression.Source`) and which the DS3
migration creates for every imported configuration.

Instructions on the document exist too, and are the same thing: `Run` splits the document
template's body around its `apply-templates` — the prologue runs once at the start of the stream,
the tail once at the end, and the loop over the input sits between them. A declaration there, set
in the prologue and read in a tail, is the run-long accumulator the question was really about.

So the answer today is: add a template, set its **match kind to `source`**, and put the
declarations and the body on it. Nothing needs building.

**What does need building is the signpost.** Nothing in the editor connects "source" to "this is
the document, and this is where a run-long name lives". The panel's `document` row shows source
*settings* only — buffer size, encoding, dispatch — so the run looks like it has no body at all,
which is what prompted the question. Two cheap changes would close it, unbuilt and recorded here
rather than done on a guess: `MatchKind.SOURCE`'s note should say what the kind is *for*, not
only that it matches once; and the panel's document row should point at the source template when
the project has one, and offer to create it when it does not.

**A correction to the first draft of this section.** It said a declaration on a root-mode
template has chunk lifetime, and left that unqualified. That is wrong, and the owner was right to
challenge it. `Run.dispatchInput` reads
`chunked = rootDispatch == CLASSIFY || rootDispatch == ANY`, and sets `body.chunkedRoot` only
when the run is *also* not whole-buffer. The default root dispatch is `STRICT` for a v4+ project
and `LAX` for a migrated one; both stream through the `InputWindow` without re-entering the
root-mode templates, so **a root template's declarations last the whole run in the ordinary
case**, which is what was agreed. Design 35 §4 does qualify its "sharp edge" with *"under a
`classify` or `any` root"* — the qualification was dropped in the reading, not in the design.

Nor is the narrow case silent. Under a chunked root an append is **refused**:
`Body.guardAccumulation` raises a FATAL naming the cause — *"the input is read in pieces whose
counters restart, so the accumulation would summarise only the last piece"* — and aborts the run.
The one residue is the case that guard's own javadoc names as uncovered: a **list declared on a
root template** under a `classify` or `any` root, where a grouping over it answers for the chunk
and nothing warns. That is worth a compiler warning some day; it is not the trap this section
first described, and it is not something the editor needs to shout about.

## 5m. The panel has three sections — built 2026-09-23

The panel listed a `data` group, a `document` group holding a row named after the project, then
the templates by mode, under a pane title reading PROJECT. Two problems. The row called
*document* held the project's **settings** — buffer size, encoding, dispatch — and none of the
document's own content, so an author looking for run-scoped names or a root element found a form
about reading bytes. And the pane title named the panel for the one thing in it that was not a
section.

The owner's shape, built:

```
SETTINGS    foo                   → the project: name, buffer, encoding, dispatch
DATA        stream 820            → the sample data page
TEMPLATES
  root
    document                      → the document template, fixed at the head
    Header Line
    Line
  mode: Value
    Value
```

A section says what it holds — the project's name, the chosen sample — so neither needs a row of
its own beneath it; the sample's own row is gone. A section is also the only kind of row without
a swatch, and the chip was what pushed SETTINGS and DATA right of TEMPLATES, so it is hidden for
them and the three align.

Making two of the three out of a row and one out of a label cost a little: the row's own children
out-voted the heading, so SETTINGS and DATA came out in the rows' monospace while TEMPLATES was
sans, and the value beside DATA inherited the heading's uppercase at a size larger than the
heading itself — `stream 820` shouted as `STREAM 820`. The section styles the children it borrows:
the name takes the heading's typeface, and the value drops back to normal case and weight. A
heading is a heading whichever widget draws it.

**The document template sits inside the `root` group, first and fixed.** It is the outermost
execution and everything in that group is dispatched from its body, so it heads them rather than
floating above the groups; and it does not move among its siblings, so the panel's up and down
are dead on it.

`SETTINGS` and `DATA` are destinations; `TEMPLATES` heads the list and is a label. They are
therefore **rows that render as headings**, not headings that happen to be clickable — so
selection, hover and the keyboard work on them exactly as on everything else in the list, and
nothing needed a second interaction model. The pane title is gone: the sections name themselves.

**Every project the editor holds has a document template**, not only a new one.
`ShapeshifterPresenter` ensures it where the editor takes a project on — reading a document — so
an older project gains it on open and the author never meets a row offering to create what should
already be there. Not in `ProjectText.parse`, which was the first attempt and was wrong twice
over: a parser that added a template stops round-tripping, so `parse(print(p))` no longer equalled
`p`; and it put a document template back into the workbench's deliberately **one-template** sample
experiment (§2), changing what the sample runs. The presenter tests caught both. A parser parses.

Because it is always there the document template is **fixed**: remove is disabled on it, as up and
down already were, and the strip's create link is gone — there is no state left for it to offer.

**A new project starts with it too**, and the row is always there whether or
not a project has one, because the run always has a document. `ProjectText.empty` — the one path
a blank document takes in the UI — seeds a template whose match is the source and whose body is a
single `apply-templates`. That adds no behaviour: `RootPlanner` reads a null directive as the null
mode and the project's own dispatch, which is exactly what an empty directive says, so a project
with this template and one without run identically. What it adds is the **place** — the author can
see where the input loop sits, wrap a root element around it, and has somewhere to declare a
run-scoped name. One factory, `Templates.document()`, because both routes to it — a new project
and the panel's `+` — must produce the same thing. Where the project has one, the row *is* that template — the strip
shows its declarations and its body, which is where run-scoped names and the root elements live
(§5l). Its row carries the same furniture as every other: a heat bar, and text at full
strength. The engine records no timing for it — it is *run*, not matched, so nothing
dispatches it and nothing times it — which left the row dimmed and bar-less, reading as a
template that had failed to match. That is the opposite of what this one is, so the row says
`1`, the one execution a stream gets, and looks like the rows around it.

**The document template no longer appears among the root templates.** `RootPlanner` filters it out
of `roots` explicitly, so listing it there contradicted the engine; a DS3-migrated project used to
show it twice over, once as `document` and once as an ordinary root row.

Still open, and named rather than guessed at: `RootPlanner` takes `findFirst()`, so a **second**
source template is silently ignored. That should be a compile refusal, and is not one yet.

## 5n. What the panel sections say, and one that forgot itself — built 2026-09-23

**A declaration on the document threw the author back to SETTINGS.** `refresh` keeps the selection
by id across a rebuild, and a row records that it survived — but the document template is added
outside the loop that does the recording, because it is pinned ahead of the rest of the root
group. So editing it rebuilt the panel, found nothing claiming the selection, and fell back to the
project. Fixed where the row is made.

**SETTINGS no longer repeats the project's name.** The section *is* the project; DATA carries the
chosen sample because there is a choice to report, and SETTINGS has no equivalent.

One thing checked and left alone. Nesting is already restricted to the eight `Holder` kinds —
`If`, `Choose`, `Switch`, `Variable`, `Element`, `Attribute`, `ForEach`, `ForEachGroup` — and the
body pane offers a nested list only for those (`node instanceof Holder`). A variable among them
looked wrong and is not: a variable's body *is* how it gets its value, as the fixtures show —
`{"variable": {"name": "record_body", "body": [{"apply-templates": …}]}}` — and design 35 §4 makes
that body an execution with its own scope, which is how `xsl:variable` nests in XSLT. A variable
with no instructions inside it binds nothing.

## 5o. Dragging a card — built 2026-09-23

Instructions already reordered — `BodyCard` has had move actions and `Bodies.move` all along —
but only a step at a time, and the prototype dragged. Now a card carries a **grip**, which appears
with its other actions on hover; the card itself stays clickable to edit, and only the grip starts
a drag. A card being dragged over shows a line on the half the pointer is in, so the drop lands
before or after it, and a card refuses itself and its own descendants — a holder cannot be put
inside what it contains, and the indicator says so by not appearing.

`Bodies.moveTo` does the move, and it is one of those operations that is two lines and three
traps. **Lifting the card renumbers the list it came from**, and the destination is addressed by
number, so both the list to drop into and the place in it must be read in the numbering that will
exist once the card is out — not the one on screen. The first cut adjusted the index but not the
path, which dropped a card into a list that had shifted out from under it: into the wrong holder,
or off the end of the body and into a null list. Two tests, written before the code was believed,
caught it immediately. Containment is judged on the paths before any list is touched, because two
sibling cards can be equal and searching for the moved one afterwards finds the wrong one.

The GWT draft compile caught the last of it: `int[].clone()` is not emulated, `Arrays.copyOf` is.

## 6. Order

§1, §2, §3a and §3b built 2026-09-21; §4, §5 and its parts the day after, from the first sitting with them. Each phase gated as design 43's were — core-client compile and checkstyle, the
presenter tests, the engine and pipeline suites where touched, the GWT draft compile — and
left in the working tree for review.

## 7. Questions for ruling

| # | Question | Recommendation |
|---|---|---|
| Q1 | Tabs or a kind picker for the match editor? | **Ruled and built 2026-09-21: the picker** (§1). |
| Q2 | Does the workbench's sample rerun the project? | **No, ruled and built 2026-09-21** (§2): it is the author's experiment, seeded from the cursor's frame; the document's sample and the trace are the run. |
| Q3 | May a project pattern shadow a standard-library name? | **No, built 2026-09-21** (§3a): refused by the compiler, once per project — the config module does not know the library, so the reader cannot. |
| Q4 | Per-node spans in the sample (2b)? | **Only if 2a is not enough**: labelled nodes are groups and get spans for free; the rest cost a second compile. |
| Q5 | A `match` endpoint, or the trace? | **The trace, built 2026-09-21** (§2): the run reports its groups; the sample is a one-template `preview`. |
| Q6 | The tree's look: restyle the list, or rebuild on Stroom's tree widgets? | **Rebuild, 2026-09-22** (§4): a restyled list still reads as a foreign idiom. |
| Q7 | Keep a wire form beside the rows? | **No, 2026-09-22** (§4): only where the rows cannot say it, which is the guard alone. |
| Q8 | Which picker for the stream door? | **Stepping's** (§5, the owner, 2026-09-22): `SteppingMetaListPresenter`, so the editor and the stepping mount name a record the same way. |
| Q9 | The navigator's back and forward: its own arrows, or the browser's? | **The browser's** (§5b, the owner, 2026-09-22), which is what design 18 §5.3 had asked for. |
| Q10 | The chooser: a dialog from the crumb, or a page of the project? | **A page** (§5a, the owner, 2026-09-22), reached from the panel above the templates. |
