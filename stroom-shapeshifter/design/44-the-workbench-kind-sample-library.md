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

## 2. The sample and the live matches

The half of design 18 §5.6 the workbench never got: regex101's left column. Design 43 §4.1
listed it — "workbench: sample and live matches — renderer, needs a `match` endpoint (B)" —
and phase B built the trace and the navigator instead, so today the only match feedback is
indirect, through the content pane after a rerun of the whole project.

**Engine and endpoint.** `POST /shapeshifter/v1/match` — `ShapeshifterMatchRequest(match
text, sample, encoding)` → `ShapeshifterMatches`: every match's offset and length in the
sample, each group's or label's offset, length and value, the compile error if there is one.
Compiled exactly as a template's match is (`PatternCompiler` to a `BytePattern`), run over
the sample's bytes, offsets converted to characters as `TraceChars` already does for the
trace. Regex, tree and parts kinds; a part's take or read is a span too. Per-node spans for
unlabelled tree nodes — click a node, see what it consumed — by compiling with a synthetic
label per node path when the request asks for it; that is 2b, wanted only if 2a proves not
enough.

**Client.** `SamplePresenter` on the workbench's left: an editable sample seeded from the
cursor frame's content, falling back to the document's sample, and **its own text** — an
experiment here never reruns the project. Beneath it the matches: outer spans outlined, group
spans in their capture hues; clicking a group in the pattern map, or a node in the tree,
isolates its spans; a table of matches (offset, groups) in the details band. Refreshed on the
same debounce as `patternInfo`. Guard and limits stay under the form on the right.

## 3. A library of parts, in the project

Composition is already the model: design 38 §2 — a match is a tree, a regex is one kind of
leaf, `sequence(ref("IP_ADDRESS"), tag(" "), ref("NAME"))`. What is missing is *define once*:
`ref` resolves against the standard library alone, and design 38 §4's audit recorded the
project's own library as not built because no fixture or real file had needed one. The
owner's question is that need.

**Model** (config module): `Project.patterns`, an ordered map of name to `PatternNode`, on
the wire as `"patterns": {"HOSTNAME": {…}}`; reader, printer and round trip beside the rest.
`ref` resolves a project name first. **A project name may not be a standard-library name**:
refused at read, not shadowed, so a `ref` never means two things and the standard entries
stay what the documentation says they are. Unknown names and cycles are `ConfigException`s
at compile time, as an unknown name is now.

**Engine**: `PatternCompiler` and `PatternPrint` take a `MatcherLibrary` layered on the
standard one — the shape design 38 §4 names — in place of the static one; the `library`
endpoint returns project entries marked as such beside the standard ones; one fixture project
exercises it and `CombinatorTest`'s identical-plan pin extends to a `ref` into the project.

**Client**:

- A **Patterns** section in the nav panel beneath the templates — design 18 §5.9's library
  pane, now editable: rows of name · kind · *used by n*; add, rename and delete with the
  item-manager icons; delete refused while referenced; rename follows every `ref`.
- Selecting a row opens the workbench on it. The workbench becomes subject-agnostic, as
  design 18 §5.9 already said it was: a template's match or a library pattern; guard and
  limits, which belong to a template, hidden for a pattern.
- The `ref` picker lists the project's patterns above the standard ones.
- **Extract to library** on any tree node: names it, moves it to `patterns`, leaves a `ref`
  in its place — composing in one gesture. Its inverse, **inline**, on a `ref`.

## 4. Order

§1, then §2, then §3; nothing in §3 depends on §2, so they swap if the library is wanted
sooner. Each phase gated as design 43's were — core-client compile and checkstyle, the
presenter tests, the engine and pipeline suites where touched, the GWT draft compile — and
left in the working tree for review.

## 5. Questions for ruling

| # | Question | Recommendation |
|---|---|---|
| Q1 | Tabs or a kind picker for the match editor? | **Ruled and built 2026-09-21: the picker** (§1). |
| Q2 | Does the workbench's sample rerun the project? | **No** (§2): it is the author's experiment, seeded from the cursor's frame; the document's sample and the trace are the run. |
| Q3 | May a project pattern shadow a standard-library name? | **No** (§3): refused at read. |
| Q4 | Per-node spans in the sample (2b)? | **Only if 2a is not enough**: labelled nodes are groups and get spans for free; the rest cost a second compile. |
