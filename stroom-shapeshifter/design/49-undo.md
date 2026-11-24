# Design 49 — Undo and redo in the editor

*Proposed, ruled and built 2026-11-24: all five of §4 as recommended, and §6 is what building it
changed. Design 18 Q10 ruled it in scope on 2026-08-28 — "a live-editing
surface with no undo anywhere is a regression" — and nothing had been built. Card drafts (design
47 D, ruled 2026-11-23) took one case away, the half-written instruction; nothing recovers a change
once it is taken.*

## 1. What there is to build on

- **One way in.** Every edit in the Design tab is a new `Project` handed to
  `ProjectHost.replace`, which `ShapeshifterDesignController` alone implements. Twelve controllers
  call it and none changes the project any other way.
- **Immutable records.** A step of history is a reference to a `Project`, and two neighbouring
  steps share everything that did not change. No edit needs an inverse, and a command list would
  be a second description of every edit.
- **Clean is worked out, not counted.** `DocumentEditor` compares the document with what was
  saved, so undoing back to what was saved makes the document clean with nothing said to it.

What is changed and is not in the project: a template's colour (`setColour`), and a mode declared
and not used yet, which is the editor's own. The sample is neither, and is not undone: it is what
the project is run over, and choosing one does not make the document dirty (design 44 §5j).

## 2. What is proposed

**A history beside `replace`.** `ProjectHistory` in `client.model`: the states, and where in them
the editor is. `replace` records the state it leaves; undo and redo show a recorded state by the
same path, without recording. An edit after an undo drops what was ahead of it. Pure Java, tested
without GWT.

A state is the project, the colours, and what was selected when it was left. A `replace` that
changes nothing records nothing.

**Where an undo leaves the author.** It selects what the step changed — the template, the mode or
the part — so that the change is on screen when it is undone. The cursor stays where it is if the
run still has it. Undo is not navigation: Back and Forward (Q14) keep their own history.

**Typing is one step.** The workbench's regex commits 300ms after each pause, and the Source tab's
text when it next parses, so one pattern typed could be a dozen steps. Consecutive edits of one
part of one subject — a template's match, the guard, the settings — are one step while less than
two seconds apart.

**A draft is not in the history.** With a card, a match line or a title open, Ctrl+Z in its field
is the browser's own undo, of the text. The toolbar's Undo takes the draft first, as leaving a card
does, and then undoes that: nothing typed is lost, since Redo brings it back.

**Keys.** Ctrl+Z, and Ctrl+Shift+Z or Ctrl+Y, in the Design tab, anywhere but in a text field.

**Read only:** the buttons are disabled, and a document locked while open keeps its history.

**A document read again** — saved by someone else, or opened afresh — starts an empty history. A
save does not: undoing past a save makes the document dirty, as any edit does.

## 3. What it does not do

- It is not kept between sessions, and is one history for each open document.
- It does not undo the sample, the panes' folds, or where the cursor is.
- It holds the last hundred steps.

## 4. Questions

| # | Question | Recommended |
|---|---|---|
| Q1 | Where are the buttons? | **The document's toolbar, beside Save and Run.** An edit is the document's, and the workbench and the sample page hide the Design tab's other bars. Stroom has both icons. |
| Q2 | What of the Source tab? | **Its text editor keeps its own undo, and a spell of editing there is one step of this one.** Undo on the toolbar is enabled on the Design tab only. The other way, clearing the history when the Source tab is used, loses the author's way back. |
| Q3 | What does the toolbar's Undo do with an open draft? | **Takes it, then undoes it**, so that Redo has it. Dropping it would lose what was typed. Doing nothing until it is closed is a dead button. |
| Q4 | Does Undo select what it changed? | **Yes.** Otherwise a change to another template is undone out of sight. |
| Q5 | Are colours undone? | **Yes**: they are the document's and make it dirty. |

## 5. How it would be built

1. `ProjectHistory`, with its tests: recording, dropping what is ahead, joining typed edits, the
   limit, and a state equal to the last recording nothing.
2. `ShapeshifterDesignController` records through it, and the toolbar and keys.
3. Stories: undo and redo of a card, a template removed, a match typed, a mode renamed, a colour;
   with a draft open; from the workbench; read only; and the document clean again at what was saved.

Gated as design 48's cuts were: the six rungs' action counts do not change, since it adds no step.

## 6. What building it changed

- **Only a regex's pattern is joined as typing** (`ProjectChange.typedInto`). It is the one edit
  that arrives a pause at a time; the guard and the settings are committed on leaving a field, and
  two clauses added in two seconds are two steps.
- **One action is one step**, which §2 did not say: a title taken is a name and a colour, and two
  edits. Edits made in one browser event are one step.
- **The cursor goes where selecting takes it.** Selecting a template is going to its first match
  (design 18 §5.3), so an undo that selects another template moves the cursor there.
- **§1 was wrong that clean is worked out for nothing.** The editor writes the project as printed,
  and the store's text is not in that form, so a document edited and put back was still dirty. What
  is written is now what was read, to the letter, while the project is the one read
  (`ShapeshifterEditor.data`). A project read without its document template is not.
- **A document read again keeps its history only if it is what is shown**, which is how a save is
  told from another's.
- **The keyboard is kept in the tab.** What it was on is often what an undo takes away, and a
  second Ctrl+Z would have gone to the page.

### 6.1 What the review of it changed (2026-11-24)

- **Redo does not take what is open.** Q3 is Undo's: taken for a redo, a draft is an edit, and an
  edit leaves nothing to redo. With something typed in it, a card, a title, a match line or a name
  stays open, and the selection stays with it, so a change elsewhere is redone out of sight. Where
  the redo would change what it is open on, that is said and nothing is redone. With nothing to
  redo, Redo does nothing at all.
- **A mode named and not used yet is a step of its own**, made, renamed or removed. A state carried
  those modes and nothing recorded them, so undoing the edit before one took it away.
- **A mode's name and a part's are taken before an undo**, where leaving them lets them go (design
  47 §7.8, findings 2 and 3). One that cannot be taken is said, and nothing is undone.
- **Only what is waiting to be committed is flushed** (`DelayedUpdate.flush`). The regex form keeps
  the last regex it showed, and taking that without anything typed wrote it over a template that
  had changed since.
- **The Source tab's text is parsed on leaving the tab, and before the document is read**, rather
  than when its pause is over: an undo within the pause lost what was typed, and a save within it
  was read back as another document.
- **A mode that is back is what is shown**, rather than the one template in it, where nothing
  dispatches into it and the template is all that is different.
