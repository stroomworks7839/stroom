# Design 47 — Cheaper authoring: what the walkthroughs say to build

*Proposed 2026-11-17, from the owner's ask: "work on the design to make things easier for the
user to construct configs. Walk through the rungs and figure out what we could do." Nothing here
was ruled when it was written; **A was ruled the same day** (§3) and §5 lists what still needs
ruling.*

Design 46 drove three of the engine's fixtures through the editor a step at a time and counted
what it cost. This design reads those counts for where the cost is, and proposes what would
remove it. It deliberately proposes **entry affordances, not model changes**: every proposal
below produces configurations the engine already runs.

## 1. Where the actions go

An *action* is a click, a typed value or a pick from a box, counted off the stories that drive
each rung (design 46 §10 has the method).

| | rung 1 `strict_kv` | rung 2 `lexer_tokens` | rung 3 `classify_alerts` |
|---|---|---|---|
| **adding body cards** | 28 | 32 | 28 |
| **making templates** | 17 | 41 | 25 |
| wiring the dispatch | 6 | 7 | 8 |
| everything else | 26 | 18 | 14 |
| **total, as driven** | **77** | **98** | **75** |

Two things that table says which rung 1 alone had hidden:

- **Cards and templates are 60 to 75 per cent of every rung.** Everything else — settings, the
  sample, roles — is small and roughly constant.
- **Templates cost more than cards once there are several.** Rung 2 has six templates with tiny
  bodies and is the most expensive rung walked, at 98. Design 46 §10 named only the card cost,
  because rung 1 has three templates.

### 1.1 What in those counts is not the author's cost

Three corrections to the counts, and to what was said from them, before proposing anything:

- **The rename is the fixtures', not the author's.** Every rung renames the root to `source`
  (4 actions) because every fixture calls it that. An author has no reason to: the panel labels
  the row `document` whatever it is called. Design 46 recorded "the step table has no rename" as
  a finding three times; it is a finding about the tables and the keystone, not about authoring.
  Setting the encoding to `utf-8` (2) and opening the dispatch card only to read it (2) are the
  same kind.
- **Some mode picks in the stories were unnecessary.** The New Template dialog already defaults
  to the selected template's mode (`TemplatePanelController.onAdd`), so a second template in the
  same mode needs no pick. The stories pick anyway: 2 actions in rung 1, 6 in rung 2, 2 in
  rung 3. *Read from the source, not driven.*
- **`element` is not the short way, and saying so was wrong.** Design 46 §10 W2, and the
  recommendation that followed it, was "lead the add menu to `element`". Counted, the pair line
  as `element` + `attribute` + two `value-of` is 16 actions against 20 — every nested card is
  still a card — and it changes what the output escapes. It is the *structured* way; it is not
  cheaper to build.

Net of those, an author's own cost is roughly **67, 84 and 67**.

*Corrected 2026-11-18.* Rung 2 was first recorded here as 36 actions of cards and 102 in
all. That is nine cards, and its fixture holds eight: `<op>=</op>`, `<arrow/>`, and three each
for the word and the number. Found when the rungs were counted again off a run, a card at a
time, to set the old flow beside the new one. Every rung 2 figure that followed from it is
four lower in this revision; what was measured after A is as it was.

## 2. The short way already exists, and three fixtures do not use it

The finding that reorganises the rest: **one `value-of` card can write a whole line.** Its Select
is a reference expression — "several one after another are joined", in the field's own help — so

```
" <pair key=\"" $1 "\">" $2 "</pair>\n"
```

is one card, where rung 1 builds five. Rungs 4 to 6 already write their output exactly this way
(design 46 §6.3 step 8, §6.4 steps 5, 14 and 16, §6.5 step 8). Rungs 1 to 3 spell the same thing
as `text`, `value-of`, `text` — and that, not the editor, is where "five cards for one line"
comes from. *Read from the tokenizer (`Instructions`), which honours `\"` inside a literal; not
yet driven.*

So W2's real statement is: the one-card line is available today, nothing leads an author to it,
and the spelling that reaches it is hostile to markup — every quote escaped, every fragment
quoted.

**Ruled 2026-11-17: it stays available and is not where authors are led.** The owner expects a
line to be written as a sequence — static text, a value, static text — and holds that it is
safer as several instructions; either is acceptable. There is a reason beyond preference, which
this design had not weighed: **the trace is per instruction.** The output pane colours each
card's output in the card's own hue and each card says what it wrote (design 18 §5.13), so a
line that is one `value-of` is one mark, and the debugger can no longer show which bytes were
literal and which came from a capture. The sequence is the model; what is worth fixing is what
it costs to build one.

## 3. Proposals

Estimates are counts, to be confirmed by re-walking (§4).

### A — A card costs one entry — ruled 2026-11-17

Today a card is four actions: `+ instruction`, pick the kind, type one value, OK. Text and
value-of — the two kinds whose whole form is one value — are **22 of the 23 cards** added across
the three rungs.

**The `+ instruction` line becomes a field.** Type, press Enter, the card appears:

- text → a `text` card;
- a reference — `$1`, `$label` — → a `value-of` card;
- **a line that mixes them is split into the sequence it describes.** `<pair key="$1">$2</pair>`
  becomes five cards — text, value-of, text, value-of, text — exactly as if they had been added
  one at a time. The templated line is a way of *typing*; what is stored, shown and traced is
  separate instructions, and nothing else is ever produced.

The kind menu stays for every other kind, and a card still opens its dialog when clicked.

Three details that follow:

- **`$$` writes a dollar.** The only escape the field needs.
- **Only `$`-references are recognised.** A bare word is text, never a declared name: the field
  must not guess, and a name or a function still goes through the menu, where the Select field's
  own grammar reads it.
- **Recognising a reference belongs to the reader that already owns the spelling**, which is
  `Instructions` in the editor's model — it is what reads `$1` and `$label` in a Select field —
  and not, as this design first said, the configuration module. One source for what a spelling
  means; the split asks that reader what each reference is.

| cards | rung 1 | rung 2 | rung 3 |
|---|---|---|---|
| today | 28 | 32 | 28 |
| one card per entry | 16 | 16 | 14 |
| with a templated line split | 10 | 8 | 10 |

Because the split builds exactly the cards rungs 1 to 3's fixtures hold, the walkthroughs keep
their expected projects as they are.

**Built 2026-11-17.** `Instructions.cards` is the split; the list's add line is a field with
the kind menu on a button beside it; a split is one edit. Rulings taken while building: the
field is always shown, a line break is typed `\n` (as `ControlEscapes` spells it elsewhere), a
`$` reference is always a reference whether or not the capture exists, and the three rungs were
moved onto the field — rung 1 a card per entry, rungs 2 and 3 with their tag-value-tag lines
typed as they look.

Two things building it turned up:

- **A label typed in a line is letters, digits and underscores**, and ends at the first
  character that is none of them, so `$key">` is the label and then text. The Select field's
  grammar lets a name hold almost anything; a line of markup cannot, or `$time</t>` would be
  one name.
- **The body is drawn again whenever a run's trace lands**, a moment after every edit, and that
  replaced the field — so a line being typed would have been wiped by the run of the card before
  it. The view now carries what is typed, and the keyboard, into the new field. A story proves
  it by checking the field on screen is a different element holding the same text.

**Measured, by re-walking.** Each entry is counted as three actions in the stories — a click
into the field, the line, Enter — though an author who stays in the field pays the click once
per run of cards, not once per card.

| | rung 1 | rung 2 | rung 3 |
|---|---|---|---|
| text and value-of cards, before | 28 | 32 | 28 |
| the same cards, typed | 21 | 12 | 15 |
| **whole rung, before** | **77** | **98** | **75** |
| **whole rung, with A** | **70** | **78** | **62** |

Rung 1 saves least because it was deliberately driven a card per entry, seven entries for seven
cards; typed as three templated lines it would be 9 and the rung 58. The estimates above did
not count the click into the field, which is the difference between them and these. All three
keystones passed unchanged: the typed lines built exactly the cards the fixtures hold.

### B — A template in one step

Making a template is eight or nine actions because its pattern — the thing a template *is* — is
set in a second surface after the dialog that made it: Add, name, mode, OK, then the match chip,
the pattern, close. A **Pattern field in the New Template dialog** (a regex, as a new template's
match already is) makes it Add, name, pattern, OK, with the workbench still there for groups,
parts and the plan.

| templates | rung 1 | rung 2 | rung 3 |
|---|---|---|---|
| today, as driven | 17 | 41 | 25 |
| with B, and no unnecessary picks | 11 | 25 | 17 |

**B′, later: a template from the sample.** Select text in the input pane, *New template from
selection*, and the pattern arrives as the selection, escaped. Four of the nine templates in
rungs 2 and 3 match a literal that is sitting in the sample (`=`, `=>`, `ERROR`, `timed out`).
Its value is less in actions than in where authoring starts — design 46 §5.2's "the record in
front of the author", made literal. The content pane has no selection handling today, so this is
the largest piece of new work here and the least certain.

### C — Finish the two-part edit where it is noticed

Three rungs in a row, the author makes a template in a new mode and then has to leave it, open
the document, open its dispatch card and point it at that mode — and it can only be done in that
order, because the card's Mode picks among modes that exist (design 46 §6.1a). The panel already
notices the half-finished state: *nothing dispatches into this*.

- **The note becomes the way out.** Beside it, *dispatch from the document*: one action that
  points the document's `apply-templates` at the mode when that card names none — which is its
  state in every rung at that moment.
- **`New…` on the card's Mode**, as the template dialog has. It does not shorten anything; it
  makes the other order possible, so that there is no longer one sequence the editor allows and
  does not mention.

Small in actions (6, 7, 8 → about 2, 5, 6; a non-`strict` strategy still has to be chosen on the
card). Its worth is that it removes the one place every rung detours.

### D — A card is edited where it is — ruled 2026-11-18

A left an inconsistency behind: a text card is *added* by typing in the list and *edited* in a
dialog. And the field can only make two kinds; every other kind is still the menu, a dialog and
OK, however little it has to say. Most of the vocabulary's kinds have three settings or
fewer — the transforms, `element`, `variable`, `emit-error` — and each pays the full four
actions. Rungs 1 to 3 hardly use them, so the counts so far do not show it; rungs 4 to 6 will.

The owner's proposal, 2026-11-18: the left of a card shows its kind; clicking it offers the
kinds, and the card changes the fields it shows in place. No dialog. Cards carry hover actions
to insert before and after, and to delete.

**What is built from it:**

- **A card opens in place.** Click a card and its line becomes its editor: the kind first, as
  a box that offers every kind, then that kind's fields beside it, wrapping as they need.
  Choosing another kind changes the fields there and then. It is the form the dialog held —
  one form for every kind, with the one reading of each field — in the list instead of over it.
- **One card is open at a time, and it is a draft.** Nothing reaches the project until it is
  taken: Enter in a field, or OK. Escape, or Cancel, puts the card back as it was. The dialog's
  OK was what kept a half-filled instruction out of the project — some kinds cannot exist
  empty, and the editor runs itself on every edit — and the draft keeps that.
- **Opening another card, or adding one, takes the open draft first** if it can be written,
  and refuses to move on if it cannot, saying why. Selecting another template takes it if it
  can and lets it go if it cannot.
- **A new card of any kind starts on the add line.** The `+` after the field becomes the
  line's own kind, before it, reading `new`: the field is still A's — a line, split into text
  and value-of cards on Enter — and the kind's menu turns the line into any other kind's
  editor, in place.
- **A card can be added anywhere.** A card's actions insert before it and after it: an add
  line opens at that place, takes lines until Escape, and offers every kind as the one at the
  end of the list does.

**The owner's worry, and its answer.** *New cards would default to text and might switch to
value-of if a reference is typed.* Nothing switches. The add line has no kind until Enter,
which is when A's reading decides it; a card that exists is the kind it says, and in a `text`
card `$1` is two characters.

*The add line of these two bullets became the New row later the same day (§3 E, "The New row
is a row"): the same things are true of it, said by a kind that reads `auto`.*

**Changing the kind of a card that holds others.** A holder keeps its branches across a change
to a kind that has a place for them, as the dialog already did. A change that has none — an
`if` to a `text` — used to drop them without a word. It now asks first, as removing a holder
does.

**Built 2026-11-18.** The form the dialog held is the editor, unchanged in what each kind's
fields are and how they are read: `InstructionEdit` lost its scroll pane and its dialog and
gained a wrapping row, OK, Cancel and its keys; `BodyController` owns the one open draft. The
dialog is gone, not kept beside it. Rulings taken while building:

- **The line among the cards takes lines until Escape**, each going in after the last, so
  three cards inserted in the middle are three lines and not three reaches for the action.
- **The editor of a new card stands in for the line it was started from**, and the line comes
  back with the keyboard when the draft is taken or dropped.
- **A changed kind that would drop held instructions asks**, where the proposal said refuse:
  asking is what removing a holder already does, and a refusal would leave no way to do on
  purpose what the dialog did by accident.
- **Enter takes the draft from a one-line field only.** In a selection box Enter opens the
  list; in an area it is a line break, and Ctrl+Enter takes it.
- **A card is opened from the keyboard**: it takes the focus, Enter opens it, and the focus
  goes back to it when the draft is taken or dropped. Before this a card could only be
  clicked.

Three things building it turned up:

- **Taking a new card moves what the author clicks next.** The draft is not in the body, so
  the card under the pointer is one place higher than it will be once the draft is taken —
  and the click that takes the draft is the click on that card. Paths held from before are
  therefore read through the insertion (`Bodies.shiftForInsert`); a story opens a card below
  a draft and checks it is that card which opens, not the one that slid into its place.
- **Leaving the template cannot make its edit there and then.** The strip is told to show
  another template from inside the selection, which is often inside an edit being announced;
  an edit made from there is announced from inside the other, and its listeners hear about
  the older project last. What the body is to become is worked out on the way out and put
  to the host a moment later, if nothing else has touched the template meanwhile.
- **The open card has to be carried across a run**, as the add line's text was for A, and
  for the same reason: the cards are drawn again when the trace lands. It is carried as the
  widget it is, so nothing typed is lost and the keyboard goes back into it.

**It departs from design 18 Q12, and that is not yet ruled.** Design 18 §5.6 (2026-08-28)
has a card's editor *commit live, with Escape reverting to a snapshot* — no draft, and no
tick-and-cross pair, which were too small to be targets. What is built holds a draft and
has OK and Cancel, as words and at target size. The reason is the model, which the mock-up
that ruling was made on did not have: a `value-of` with no select, a `format-number` with
no picture, a reference half typed — none of these is an instruction, so "applies as you
type" would mean "applies whenever what is typed happens to be one", and the project would
be valid at some keystrokes and unchanged at others with nothing on screen saying which.
The draft was described to the owner and accepted on 2026-11-18, but not set against Q12
when it was. §5 lists it.

**Measured.** Rungs 1 to 3 are unchanged, as expected: 70, 78 and 62. Their cards are text
and value-of, which A already made a line each, and the two things they do through the
editor — pointing the dispatch at a mode, and rung 1's `emit-error` — cost what they did:
the click that opened a dialog opens the card, and OK is OK. What D changes there is where
it happens, which the counts do not see. Its saving in actions is on the kinds A's line
cannot say, and rungs 4 to 6 are where those are.

### E — The template at full height — tried 2026-11-18, not yet ruled

Not a proposal about actions: about room. The walkthroughs kept running into the same thing
from different sides. The body is where an author spends most of a rung, and it was the
smallest pane on the screen: about 110px at the stories' 1280×720, two or three cards. That
is why A's old add link went below the fold after the third card, why the template pane
needed a floor before its cards could be seen at all (design 46 §5.9a), and why D's open card
had to be scrolled to before its way out was on screen. Above it the input pane was the
largest on the screen, holding three lines.

The quadrant is design 18 §5.5's (2026-08-28):

```
        INPUT             │      VARIABLES
  ────────────────────────┼────────────────────────
       TEMPLATE           │       OUTPUT
```

The owner's question, 2026-11-18, was whether the template should be top left, with the input
top right and the variables under the template. Top left is right, for three reasons: it is
beside the panel row that selects it, where today the detail of a click appears diagonally
away; it is the pane being worked in; and a pattern is written beside the sample it is
matching. But variables under the template would undo what §5.5 was arranged for. That
ruling made each *row* a hover-linked pair — input with variables, template with output —
and moved panes twice to stop a hover in one corner lighting something in the opposite one.
Template over variables, input over output, puts both pairs on the diagonal.

What is built instead:

```
                │  INPUT
    TEMPLATE    │  VARIABLES
                │  OUTPUT
```

- **The template has the height.** At 1280×720 the pair's five cards and its add line are
  all on screen, with the match, the role and the guard above them.
- **The right-hand column reads downwards in the order §5.5 gives**: the bytes, the names
  bound out of them, the bytes written.
- **Neither hover pair is on a diagonal.** Input and variables are neighbours, and the
  output is beside the template.
- **The crumb runs across the top of both**, since the frame it names is what all four show.

The halves are equal to start with; the owner's note is that the data can have more if it
needs it. Two things followed from the strip becoming a column: its declarations and captures
now share its width evenly, where a fixed 360px for the first left the second too little to
show both its columns; and when its heading is too long it is the note that gives way, not
the mode, with the note in full on its tooltip.

**The three data panes fold away** (the owner, 2026-11-18, on seeing it). Each is under a
title bar — *Input*, *Variables*, *Output* — and pressing the bar leaves only the bar, with
the room going to the panes still open: to the input while it is one of them, then to the
output. This is the answer to the cost below as far as a pane can be done without: an author
reading a long sample folds the other two and the input has the column. A folded pane dragged
open by its splitter is open. With it, two things the owner asked for at the same time:

- **All three have one surface, and it is the page's.** The input and the output had a code
  editor's since 2026-09-22, to read as data, and the variables kept the page's as a reading
  of the data. One above another, a pane of another shade between two alike read as a gap,
  so first the variables were given the editor's; then, with the template beside them on
  the page's, the lighter column read as something apart, and all three went back to it —
  which is where design 18 §5.5 had all four panes to begin with. None of the three is an
  Ace editor, so there was no theme to follow: they are marked-up text, and the surface is
  one rule.
- **The input and the output lost the margin above their text.** The title bar is the room
  they need there, and the 8px they had under it was a second one.

Design 18 §5.5 already had every pane carry its name; the input and the output had not, and
now do.

**The declarations and the captures are sections of the template** (the owner, 2026-11-18:
"another header like BODY … part of the scrolled area"). They were two grids with toolbars
in a strip of their own under the template, 170px whether or not either held anything — and
of the six samples only rung 5 has any. Each is now a heading with its own add link, a line
when empty, and a row per name that is clicked to edit and carries its own remove. They come
before the body, in the order things happen: what the template declares, what its match
binds, then the instructions that use both. Their dialogs are as they were.

**The messages are under the work, not under the panel** (the owner, 2026-11-18), and fold
away under a title bar as the data panes do. They are about the project being worked on, not
about the list of its templates, and the panel now has the whole height for that list. The
bar counts them — *Messages · 1* — so it still says there is something to read when they are
folded. Moving them found a fault in the split pane itself: it set its panes' sizes only
after their contents were attached, and a data grid works out its columns' widths once, from
the width it first has — so a grid in the second of two panes side by side was measured too
wide, and the messages ran off the edge under a scrollbar. `SplitPane` now sets its tracks
before its panes attach.

**The body is one list of rows, not a stack of cards** (the owner, 2026-11-18). No gap between
rows, no box round each, nothing at the sides: a row's hover and its selection run the width
of the pane, and what is in a row steps in by how deep its list is, where a nested list used
to be a narrower box inside a dashed rule. The keyboard is on one row at a time — the rows are
one tab stop between them — and the arrows, Home and End move it; Enter opens the row and
Delete removes it, leaving the keyboard on the row that comes up into its place. A run landing
no longer takes the keyboard off the list. The open row is the width of the list too, ruled
above and below; the declarations' and captures' rows match.

**One toolbar over the rows, not five glyphs on each** (the owner, 2026-11-18). Move up, move
down, insert above, insert below and remove are on the body's heading line and act on the
selected row, as the template panel's toolbar acts on the selected template and a grid's on
its selected row. The rows have their whole width for what they say, and the actions are
buttons the keyboard reaches, which the glyphs were not. That makes the selection a thing of
its own rather than wherever the keyboard is: the row last clicked, reached with the arrows
or opened, and it stays selected while the keyboard is on the toolbar. **A row just added is
the selected one**, as a grid selects the row it has been given.
Clicking a row still opens it; the toolbar acts on the open row too, taking its draft first.

Two things that were found on the way. **Alt and an arrow is not a row's to take**: design 18
§5.6 gave it to moving a card, and the tab already uses it to walk the run's frames from
wherever the keyboard is, so a row leaves it alone and is moved by its buttons or its grip.
And **a row's focus was put off to a timer**, which is how GWT focuses a panel in this
browser, while a field's is immediate — so an add line opened from a row's own action was
given the keyboard and then lost it to the row a moment later. A row is now focused directly.

**The New row is a row** (the owner, 2026-11-18). The add line was a field at the end of
every list: always in an edit state, though it was never the selected row, and drawn as
nothing else in the list was. A list now ends with a row like the others — its kind reads
`new` — which is walked to, selected and opened as they are, and is not a field until it is
opened. Opened, it is a card's editor, the same one, whose kind reads **`auto`** until one is
chosen: A's line, with what is typed deciding between text, value-of and the sequence of
them. Choosing a kind changes the fields there and then, as it does for a card that exists,
so the menu the add line's `new` opened has gone; clicking the row's kind opens it with the
kinds offered. `auto` is offered only to a card that does not exist yet.

What follows from it being the one editor:

- **Insert above and below open the same card**, where it will go. There is no second kind of
  add line among the cards, and the list still ends with its New row while one is open
  elsewhere.
- **Taking a new card opens another after it**, with the keyboard, so a body is still typed a
  line and Enter at a time. **Enter on a line left empty, or Escape, is the author having
  finished**: the keyboard goes to the row they started from, or to the row they added last.
- **What is open is what is selected.** While a new card is open no row is the selected one
  and the toolbar offers nothing; it used to act on the row just added while the keyboard
  stayed in the line. So "add it, then move it up" is the line, Escape, and the press — one
  key more than it was. **The walkthroughs no longer do that.** A card that belongs above
  another is put there: the other row selected, insert above, and the line. Adding at the
  end and moving up was only ever the way because there was nowhere else to add.
- **The New row is not a card yet.** The toolbar on it offers only insert above, which opens
  it; Delete on it removes nothing.

**Measured.** The rungs cost what they did: 78 for rung 2 and 62 for rung 3, as before.
Rungs 1 and 3 each put an opening tag above the dispatch it wraps, and insert above costs
what add-then-move did — the row, the button, the line, where it was the field, the line, the
button. Rung 1 counts 66 where it counted 70, and none of that is a saving: the four are
clicks the walkthrough used to make on a field that already had the keyboard, which an
author would not have made. The first line into a body costs what it did, a click and then
the line: the click is on the New row where it was on the field.

**Not done:** design 18 §5.6 has a card dropped on a list's add line land at its end, which
is also how an empty branch gets its first card by dragging. The add line never took a drop
and the New row does not yet either; a row that is like the others should.

**What it costs, and what has not been looked at.** Three panes share the height one pane
had: the input has about 210px at this size where it had 270, and it is narrower, which is
where long records live. All of the evidence is authoring, at 720p, on samples of one to
three lines. Nobody has yet stepped through the matches of a long sample in this layout,
which is the use the input pane is for. The workbench and the sample page replace the whole
workspace as before and are unchanged.

### What these come to

| | rung 1 | rung 2 | rung 3 |
|---|---|---|---|
| as driven | 77 | 98 | 75 |
| with A, B and C | about 49 | about 56 | about 47 |

Roughly **a third to nearly a half** off each rung, and more off an author's own cost once §1.1's
artefacts are out of both rows.

## 4. How we would know

The walkthroughs are the instrument. Each proposal changes what a stage does, not what the rung
builds toward, so the measure is the same count taken again.

A as ruled changes nothing about what a rung builds: a templated line is split into the cards
the fixture already has, so each keystone stands as written and only the count moves.

## 5. What needs ruling

**Ruled and built:** A — the sequence is the model, a card is entered in the list, and a
templated line is split into its sequence (2026-11-17, §3).

**Still open:**

1. **E: the layout.** Built to be looked at. *Recommended: keep it if a debugging session on a
   long sample reads well in it; that has not been tried.*
2. **D: a draft, or live commit as design 18 Q12 has it.** Built as a draft, for the reason
   in §3 D. *Recommended: the draft, and Q12 amended to say so — live commit asks the author
   to know which keystrokes made an instruction.*
3. **B: the dialog or the strip.** A Pattern field in New Template, or the match chip made
   editable in place. *Recommended: the dialog — it is where the template is being described,
   and it does not make a second editing surface for patterns.*
4. **C's action**, when the document's dispatch already names another mode: add a second
   `apply-templates`, or offer nothing. *Recommended: offer nothing — a second dispatch site is
   a decision, not a completion.*

**Order:** A and D are built, and E is built to be looked at. B and C are reassessed after them.

## 6. What this does not cover

- **The remaining forms.** Rungs 4 to 6 bring the pattern library, declarations, captures into
  lists, `variable`, `if` and parts. Their step tables were written from the source and none has
  been driven; rung 6 cannot be, because a binary sample cannot be pasted (design 46 §6.5).
  Expect them to move the numbers and to add proposals of their own — design 46 §6.3 already
  asks whether a pattern part can be lifted out of a pattern that has been written.
- **Helping with the reference expression itself** — completion of declared names, captures and
  labels, colouring a reference in its capture's hue. Rungs 4 to 6 write lines through that
  field and design 44 Q14 already flags it, so it will matter when they are walked; A does not
  touch it.
- **`element`.** Still the right answer when the output should be structured and escaped. It is
  no longer proposed as the cheap one.
