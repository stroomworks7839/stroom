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
set, so the body is a sentence saying what the kind means. **The body always shows the model.**
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

## 4. Order

§1, §2, §3a and §3b — all built 2026-09-21. Each phase gated as design 43's were — core-client compile and checkstyle, the
presenter tests, the engine and pipeline suites where touched, the GWT draft compile — and
left in the working tree for review.

## 5. Questions for ruling

| # | Question | Recommendation |
|---|---|---|
| Q1 | Tabs or a kind picker for the match editor? | **Ruled and built 2026-09-21: the picker** (§1). |
| Q2 | Does the workbench's sample rerun the project? | **No, ruled and built 2026-09-21** (§2): it is the author's experiment, seeded from the cursor's frame; the document's sample and the trace are the run. |
| Q3 | May a project pattern shadow a standard-library name? | **No, built 2026-09-21** (§3a): refused by the compiler, once per project — the config module does not know the library, so the reader cannot. |
| Q4 | Per-node spans in the sample (2b)? | **Only if 2a is not enough**: labelled nodes are groups and get spans for free; the rest cost a second compile. |
| Q5 | A `match` endpoint, or the trace? | **The trace, built 2026-09-21** (§2): the run reports its groups; the sample is a one-template `preview`. |
