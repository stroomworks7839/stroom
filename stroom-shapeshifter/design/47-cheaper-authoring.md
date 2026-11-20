# Design 47 — Cheaper authoring: what the walkthroughs say to build

*Proposed 2026-11-17, from the owner's ask: "work on the design to make things easier for the
user to construct configs. Walk through the rungs and figure out what we could do." Nothing here
was ruled when it was written; **A was ruled the same day** (§3) and §5 lists what still needs
ruling. Rungs 4 and 5 were walked on 2026-11-18; §7 (2026-11-19) collects every open finding
and sets out the plan from here.*

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
  own grammar reads it. *Amended by H, 2026-11-19: `${…}` is a reference of any kind, read by
  the Select field's grammar. A bare word is still text.*
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

#### B, reassessed and built 2026-11-18 — in place, not in the dialog

Written before D and E, B put a Pattern field in the New Template dialog. Since then the card
dialogs have gone and the body has settled on one way of working: a list of rows, a New row
at its end, a row opened where it is, a draft taken with Enter. A second field in a dialog
is the one piece of B that goes the other way. The same way of working, applied to templates:

- **Each mode's list in the panel ends with a New row**, as a card list does. It reads
  `new template`, is walked to and selected as a template's row is, and opened it is a Name
  field where the row was. Enter makes the template **in that mode** — the row's place says
  which, so there is no mode to pick, and the picks the stories made without needing to
  (§1.1) cannot be made. Escape, or Enter on an empty name, is no template.
- **The panel ends with a `new mode` row.** Opened, it is a field for the mode's name; Enter
  puts the mode in the panel as a heading, with its own New row open under it for its first
  template, because a mode is only ever the mode of a template. *As built:* this was first
  written as two fields in one row, on the belief that nothing could hold a mode with no
  template in it. Something already did — the Modes dialog declares one, and the editor keeps
  it until a template uses it — but the panel never showed it. It does now, which is also
  where a mode made in that dialog has gone to all along. This replaces the template
  dialog's `New…` as the way a mode is made while authoring (design 46 §6.1a).
- **The Match line opens in place.** At rest it reads as it does now — `regex`, the pattern —
  and clicked it is a field holding the pattern, taken with Enter and put back with Escape,
  as a card is. `workbench` stays beside it, for groups, parts, flags and the plan, and is
  the only way in for a match that is not a regex (a pattern tree, parts, a delimiter): those
  have no one-line spelling. **A template just made arrives with its Match line open and the
  keyboard in it**, so making one is the name, Enter, the pattern, Enter.
- **The toolbar's Add opens the New row** of the selected template's mode. Nothing makes a
  template through a dialog any more. The dialog stays as Edit Template — colour, mode,
  params, encoding, ignore errors — which is where a template is moved to another mode.
- **After the pattern, the body.** Enter in the match of a template whose body is empty goes
  on into the body, with its New row open, so a one-line template is the name, the pattern
  and the line, each with Enter, and one click in all. In a template that has a body the
  keyboard stays on the match.
- **The New rows read `+ template` and `+ mode`** (the owner, 2026-11-18, after seeing them
  as rows): the same add link the declarations and the captures have, on a line of its own
  where the row was. What they do is unchanged. Where this section says a New row of the
  panel, that link is what is meant; the body's New row is still a row.
- **Leaving the match field takes what it holds**, as doing anything else to a body takes
  the card open in it; Escape puts it back. Read only, and for a match that is not a regex,
  the match opens the workbench as it always did.

**What it costs**, counted off the walks, with an Enter as an action:

| templates | rung 1 | rung 2 | rung 3 |
|---|---|---|---|
| before, as driven | 17 | 41 | 25 |
| B as first written (the dialog), estimated | 11 | 25 | 17 |
| **B in place, measured** | **12** | **27** | **17** |
| **whole rung, before** | **66** | **78** | **62** |
| **whole rung, with B** | **60** | **60** | **51** |

A template in a mode that exists is the row, the name, Enter, the pattern, Enter: five, where
the dialog would have been four and the old way was eight as driven. One in a new mode is
seven — the mode and its Enter, then the same five less the click — where it was nine. The
rest of what the rungs lost is a click a template: the body is not clicked into, because the
pattern's Enter goes there. Two things to read those figures with. The old walks picked the
mode in the dialog for every template, and for one in the mode already selected they did not
need to (§1.1; 2, 8 and 4 actions as the walks were driven at the end), so an author's own
saving is smaller than the table's. And **it is not cheaper than the dialog would have
been**, though it was said to be when it was first put to the owner; what it buys over that
is not actions:

- **One place a pattern is typed, used both ways.** The dialog's field would only ever be
  used once per template, at its making; changing a pattern afterwards would still be the
  chip, the workbench and its close. The Match line is the same three steps for a new pattern
  and an old one.
- **No dialog between the author and the sample.** The input stays on screen while the
  pattern is typed, which is design 46 §5.2's point about the record being in front of the
  author.
- **The mode cannot be got wrong, or picked for nothing.**

**What it does not fix.** Between the two Enters the template exists with an empty pattern,
as it does today between the dialog's OK and the workbench. The dialog version made a
template whole in one step, which is the reason D holds a card as a draft. The window is two
keystrokes wide rather than three clicks, and nothing has been seen to go wrong in it across
three rungs, but it is the one thing the dialog did better.

**Not looked at:** what the Match line should do while a regex in it does not compile — today
the messages say why once it has been taken. *(2026-11-19: it says so as it is typed, §7.7.)* The panel's 220px turned out to be enough for a
name being typed.

**The stories.** `ShapeshifterTemplateStories` holds the rows and the line: a template and a
mode made in place, a name not taken, the match opened, taken and put back, the workbench
for a match that is not a regex, and read only. The two stories of the New Template dialog
went with it. The three walks make every template this way, and their keystones passed
unchanged.

#### Named where it is shown, not where it is listed — 2026-11-18, later

The owner, having used the links: lose the Edit button and the name field from the list, and
set names and settings in the main pane. Seven questions were put with a recommendation each
and all seven were taken as recommended. What is built:

- **Adding is immediate.** `+ template`, or the toolbar's Add, makes a template called
  `new template` in that mode, selects it and shows it with **its name open in its own
  title**, all of it selected, so that what is typed replaces it. `+ mode` makes a mode
  called `new mode` the same way. A second is `new template 2`: a call names a template and
  a dispatch names a mode, so two of a name could not be told apart. Nothing is named in the
  list, and the field the links used to turn into has gone.
- **Escape no longer means no template.** It was made when it was asked for; Escape leaves
  it called what it arrived as, and Remove is how one that was not wanted goes.
- **A title is edited where it reads.** An edit button stands beside the name; pressed, or
  the name pressed, the name is a field. A template's other settings — colour, mode, params,
  encoding, ignore errors — are under the title while it is open and not otherwise. Name and
  settings are one draft, as a card is: Enter in the name, OK, or the edit button again takes
  it; Escape or Cancel leaves the template as it was; going to another row takes it if it
  can be written. The Edit Template dialog has gone, and with it the last dialog a template
  could be made or changed in.
- **The chain is kept.** Enter in a new template's name goes on to its match, and from there
  into its body. In a template that was not just made the keyboard stays on the title.
- **A mode has a pane.** A named mode's heading in the list selects it, and it is shown
  where a template would be: its name, edited as a template's is, how many templates it has
  and what dispatches into it, and its templates listed, each the way to that template, with
  a `+ template` of its own. Renaming it there renames it on its templates and on every
  dispatch that points at it. After a new mode is named the keyboard is on that
  `+ template`; nothing is made that was not asked for. The root has no name and its heading
  is only a heading.
- **The Modes dialog and its button have gone.** A mode is added, renamed and removed as a
  row of the list is; Remove takes one that has no templates. The panel's toolbar is Add,
  Remove, Up, Down and the pattern parts' own add.
- **A pattern part is renamed in the workbench's title**, which is where a part is shown. The
  name prompt remains only for adding a part.

**Measured.** The rungs are 61, 60 and 52, as they were. Renaming the root is one action
fewer in each — the button, the name, Enter, where it was the button, clear, the name, OK —
and a template in a new mode is one more: the Enter that presses the mode's `+ template`.

**What this does to C.** The mode's pane is where *nothing dispatches into this* now reads in
full, beside the mode it is about, and is the natural home for C's way out.

**Not done.** A mode's own settings, of which it has none today. Adding a pattern part still
asks for its name in a prompt, where a template and a mode no longer do. *(2026-11-19: no
longer, §7.7.)*

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

#### C, reassessed and built 2026-11-18 — in the mode's pane

Written before a mode was anything but a heading. Since B a mode is added first and has a
pane of its own, which is where the author is at the moment the dispatch is missing; so the
way out is there, and so — now that there is somewhere for it — is the strategy. Six
questions were put with a recommendation each, and all six taken as recommended.

- **The mode's pane has a dispatch line.** While nothing dispatches into the mode it says so,
  and beside that is **`dispatch from the document`**: one press points the document's own
  `apply-templates` at the mode. The author stays in the pane, with the keyboard on the
  strategy. The note under the mode's heading in the list is still only a note.
- **It is offered only when it is a completion.** The document has one `apply-templates` that
  names no mode, and the root has no templates of its own — a dispatch that names no mode is
  what runs those, and pointing it elsewhere would stop them. Otherwise nothing is offered
  and **the pane says why**, and what to do instead: *the document dispatches into kv; add an
  apply-templates where this mode is to be dispatched from*. This is ruling 4, as recommended.
- **The strategy is set in the pane.** A picker holds the strategy the mode's dispatch sites
  share — `project's` where they name none — and set there it is set on every one of them,
  which is where a strategy lives; renaming a mode already writes to those sites from the
  pane. It is disabled until something dispatches into the mode, and where the sites differ,
  which the line then says: there is no one value to show, and nothing is overwritten. The
  card keeps its own Dispatch field. This also answers design 46 §10 W4 from the other side:
  the strategy was only ever chosen on a card of another template, in another pane.
- **A card's Mode offers every mode the list shows**, one with no template yet included, so
  a mode can be dispatched into before anything is in it. This is what C's `New…` on the
  card was for; with `+ mode` in the list there is nothing to add to the card.

**Measured.** The walks dispatch a mode where they make it — the link, and for rungs 2 and 3
the picker and the strategy — and no longer go to the document for it:

| | rung 1 | rung 2 | rung 3 |
|---|---|---|---|
| before C | 61 | 60 | 52 |
| **with C** | **58** | **56** | **49** |

Wiring the dispatch was 4, 7 and 6 actions and is 1, 3 and 3. The window design 46 §5.4 found —
a mode with templates that nothing dispatches into — is no longer something the walks pass
through: the stages that asserted its note now assert that it is not there.

**Not done.** A second or nested mode is still dispatched by adding a card, which is right;
the pane could list the sites that dispatch into a mode, each the way to its card, and does
not.

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

**Add, remove, up, down — and `+ instruction`** (the owner, 2026-11-18, later the same day).
The body's toolbar is the template panel's: add, remove, move up, move down, in that order.
There is one way to add from it, and it is **below** — below the selected row, in the list
that row is in, or at the end of the body when no row is selected, so Add is always there
to press. Insert above has gone. And the New row has become what the panel's became: a
`+ instruction` link under the rows of every list, the add link the declarations, the
captures, the templates and the modes have. It is not a row — the arrows stop at the last
card and Tab reaches it — and pressed it is the same card, kind `auto`, where it stood.

What that changes in what was said above:

- **The walks add and then move again.** With no way to add above the first row, an opening
  tag is added at the end and moved up: the line, Escape, the press. Rungs 1 and 3 are one
  action dearer for it, 61 and 52 where insert above had them at 60 and 51; rung 2 is 60.
- **A card of another kind from the link is three presses, not two**: the link, the kind
  box, the kind. The New row's own kind could be clicked to open it with the kinds offered;
  a link has no kind to click.
- **Dropped, a card goes back where it was asked for**: to the link, or to the row that was
  selected when Add was pressed.

**Not done:** design 18 §5.6 has a card dropped on a list's add line land at its end, which
is also how an empty branch gets its first card by dragging. The add line never took a drop
and the New row does not yet either; a row that is like the others should. *(2026-11-19:
`+ instruction` takes a drop, §7.7.)*

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
| with A, B and C, estimated | about 49 | about 56 | about 47 |
| with A, D and B, measured 2026-11-18 | 60 | 60 | 51 |
| **with A, D, B and C, and the changes between, measured 2026-11-18** | **58** | **56** | **49** |

Roughly **a third to nearly a half** off each rung, and more off an author's own cost once §1.1's
artefacts are out of both rows. Measured, rung 2 is where it was estimated to be and rung 3
is two off; rung 1 is furthest off because it was driven a card to a line (§3 A), which is
the walk's choice and not the editor's — typed as three lines it would be 46.

Rungs 4 and 5 were walked after all of this, at 122 and 158; §7.5 has what F to J would make of
them.

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
3. **B: the dialog or in place.** A Pattern field in New Template, or a New row in the panel
   and the Match line opened in place (§3 B, reassessed). **Built in place 2026-11-18, to be
   looked at.** *It costs the same as the dialog would have, it is how cards now work, and
   its one field serves a pattern being changed as well as one being made. The first
   recommendation was the dialog, on the ground that it made no second editing surface for
   patterns; it made one too, used once.* Within it, and built as recommended: the keyboard
   goes on into the body's New row after the pattern of a template with an empty body.
4. **C's action**, when the document's dispatch already names another mode: add a second
   `apply-templates`, or offer nothing. **Ruled 2026-11-18: offer nothing, and say why** — a
   second dispatch site is a decision, not a completion (§3 C, reassessed).

5. **The no-mode level's name** (§7.3, finding 7): shown as *no mode*, never as a name, or
   `root` forbidden as a mode name. *Recommended: the first.*
6. **The row carries its limit** (§7.3, finding 8; design 44 Q12). *Recommended: yes, as a
   fact on the match summary, not an explanation.*
7. **H**: re-baseline rungs 4 to 6's literals to `text`, and let A's line read `${…}` as a
   reference of any kind — an amendment to A's "a bare word is text". *Recommended: both, or
   neither.*
8. **F's outcome**, once measured: re-baseline `library_parts` to the explode's spelling, or
   fix up by hand. **Ruled 2026-11-19: re-baseline**, as recommended (§7.13).
9. **J**: a toolbar action that wraps rows in *only if it writes something*. *Recommended:
   decided after H and I are walked, against what the idiom then costs.*

**Order:** A, D and C are built; E and B are built to be looked at. From here, §7.6.

## 6. What this does not cover

- **Rung 6.** Rungs 4 and 5 have been walked (§7); rung 6 cannot be until the story can serve a
  stream fixture (design 46 §6.5), and parts over a binary sample may add proposals of their
  own.
- **Helping with the reference expression itself** — completion of declared names, captures and
  labels, colouring a reference in its capture's hue. Rungs 4 and 5 wrote their lines through
  that field and design 44 Q14 flags it; H's `${…}` makes a reference cheaper to type, not
  easier to know of, so this stands.
- **`element`.** Still the right answer when the output should be structured and escaped. It is
  no longer proposed as the cheap one.

## 7. Rungs 4 and 5, every open finding, and the plan — 2026-11-19

Design 46 §6.3a and §6.4a drove rungs 4 and 5 a stage at a time on 2026-11-18, the first rungs
walked after A to E were built. This section collects what they found with everything earlier
sections left open, sorts it into fixes and proposals, and sets the order of work. The counts
come from the stories, as in §1.

### 7.1 Where the actions go

| | rung 4 `library_parts` | rung 5 `text_019_single_line_split` |
|---|---|---|
| settings and the sample | 8 | 6 |
| renaming the root (§1.1: the fixture's, not the author's) | — | 4 |
| templates made and named | 4 | 12 |
| **the match** | **102** — the tree's 14 nodes 83, into the tree 3, lifting four parts 16 | **42** — four delimiters 39, the limit 3 |
| modes, and the dispatch from the document | — | 10 |
| declarations and the capture | — | 15 |
| **body cards** | 7 | **68** — the envelope 14, two dispatch cards 17, `variable` 7, `if` and what it holds 24, the data line 6 |
| run | 1 | 1 |
| **total, as driven** | **122** | **158** |

Read beside §1's table, three things:

- **The match is where rung 4's cost is, and it is the node dialog.** Each node is the add
  button, the kind box, the kind, its one field, often a label, and OK: five or six. Twelve
  of the 83 are not the author's at all. The stories clear the node's text field before
  typing, and a new node's field is already empty. As an author would drive it, rung 4 is
  110.
- **Rung 5 is the first rung whose cards cost more than its templates.** A's line makes text
  and `value-of $…` cards. Rung 5's cards are something else: literals that the fixture
  spells as quoted `value-of` Selects, two dispatches, a `variable`, an `if` and a reference
  to a declared name. None of those can be typed on the line, so each is the kind box and its
  fields again. At four or five actions a card, that is A's "before" cost.
- **A delimiter goes through the workbench.** B opened the Match line in place for a regex
  only. Every delimiter template costs Escape out of the line, the workbench, the kind, a
  field that arrives holding a newline and has to be cleared, the delimiter, optionally its
  containers, and Close. That is eight to twelve actions for what is one character and
  sometimes a quote.

### 7.2 Every open finding

Gathered from design 46 §5.9 to §6.4a and from §3 above, with what each is now. *Fixed* means
fixed and covered by a story. **Fix** means it is a defect and goes in the plan without a
ruling. **Ruling** means there is a choice to make first.

| # | Finding | Where found | Now |
|---|---|---|---|
| 1 | A tree node could not hold a newline (`take_through "\n"`) | 46 §6.3a | *Fixed* — tags and terminators read `ControlEscapes` |
| 2 | The pattern tree's scroll area could not be reached from the keyboard | 46 §6.3a | *Fixed* — a focusable region |
| 3 | The delimiter form's two fields had no names | 46 §6.4a | *Fixed* |
| 4 | A tall tree draws over the *As regex* and *Library* sections below it | 46 §6.3a, stage 14 | *Fixed* 2026-11-19 (§7.7) |
| 5 | The capture dialog lists only the current template's declarations, not the document's run-scoped `heading` that it binds | 46 §6.4a | *Fixed* 2026-11-19 (§7.7) |
| 6 | The `if` row: `+ clause` is a label the keyboard cannot reach, the clause's name box does not open its list when clicked, and the operator box has no accessible name | 46 §6.4a | *Fixed* 2026-11-19, but for the name box, which was a misreading (§7.7) |
| 7 | A mode named `root` collides with the word for no mode in the panel, the strip, the Mode picker and the mode pane | 46 §6.4a | *Fixed* 2026-11-19 (§7.9) |
| 8 | Two templates with the same match are identical in the panel. The limit that tells `header_record` from `data_record` is out of sight | 46 §6.4, 44 Q12 (open) | *Fixed* 2026-11-19 (§7.9) |
| 9 | Every literal in rungs 4 to 6 is a quoted `value-of`, where an author would make `text`. The strict keystone makes the walks build the fixture's spelling | 46 §6.4a | **Ruling** — proposal H |
| 10 | `heading[matchCount()]` is typed into a plain field, and A's line cannot say it | 46 §6.4 (a), 44 Q14 (open) | **Ruling** — proposal H |
| 11 | *Don't write an empty record* is three nested instructions and a declaration, 39 actions | 46 §6.4 (b) | **Ruling** — proposal J |
| 12 | The walkthroughs' canned traces carry the fixtures' template ids, so a walked project's rows read *not tried* | 46 §6.3a | *Fixed* 2026-11-19, and half of it was the traces' (§7.7) |
| 13 | The stories clear fields that are already empty: 12 actions in rung 4 that are not the author's | §7.1 | *Fixed* 2026-11-19 |
| 14 | The Match line does nothing helpful while the regex in it does not compile. The messages only say why after it is taken | §3 B | *Fixed* 2026-11-19 (§7.7) |
| 15 | A pattern part is still named in a prompt, both when it is added and when it is extracted, where a template and a mode are named in their titles | §3 B | *Fixed* 2026-11-19 for adding; extracting keeps its prompt, on purpose (§7.7) |
| 16 | The mode pane does not list the sites that dispatch into the mode | §3 C | Proposal, small |
| 17 | A card cannot be dropped on a list's `+ instruction` | §3 E | *Fixed* 2026-11-19 (§7.7) |
| 18 | Nobody has debugged a long sample in E's layout | §3 E | To try, before E is ruled |
| 19 | D's draft departs from design 18 Q12's live commit | §3 D | **Ruling**, §5 item 2 |
| 20 | Rung 6 cannot be walked until the story can serve a stream fixture | 46 §6.5 | Ruled 2026-11-12, not built |
| 21 | B′: a template from selected sample text | §3 B | Proposal, the largest and least certain |

### 7.3 Two fixes that need a word first

**7 — the mode called `root`.** The editor writes `root` for the level that has no mode, and
`root` is a legal mode name. So `text_019_single_line_split`, a fixture we wrote ourselves,
puts the same word on two different things on four surfaces. *Recommended: never show the
no-mode level as a name.* It reads *no mode* in italics wherever a name would go: the panel's
heading, the strip, the first entry of the Mode picker, and the mode pane's sentence. `root`
is then an ordinary name. Forbidding `root` as a name would also work, but it would refuse a
fixture we already ship.

**Ruled (the owner, 2026-11-19): the level reads `default`, and `default` is reserved.** Plain,
not italic: the reservation is what makes it unambiguous. The editor and the engine's
validation both refuse a mode called `default`. No fixture uses one; five use `root`
(`win_sec` and two variants of it, `win_app`, `text_019_single_line_split`), which is why
`root` could not be the reserved word. Authors coming from XSLT already know the default mode.

**8 — the identical templates.** Design 44 Q12 asked whether the row should carry the limit or
the panel should explain. *Recommended: the row carries it.* A template row's match summary
gets a limit when the template has one, `delimiter | · first only` for `max 1`, and
`· at most N` otherwise. That is a fact about the template rather than an explanation, which is
the line W4 drew (design 46 §10). It costs nothing where there is no limit, so five of the six
samples show no change.

**Ruled (the owner, 2026-11-19): the row carries it, as recommended.**

### 7.4 Proposals F to J

As in §3, these are entry affordances, not model changes. Each estimate is a count, to be
confirmed by walking the rung again.

#### F — A tree written as a regex, then factored

**This one exists today and has never been measured.** A new template's Match line takes a
regex in place (B). Switching the kind to *pattern tree* explodes the regex into the tree the
engine says it is: `PatternExplode`, which keeps meaning but not text, and keeps named groups
as labels. Rung 4 was walked node by node only because the story's explode stub returns an
empty tree. Walked the other way, rung 4 is:

- the template's name, Enter, and the whole line as one regex with a named group per field,
  Enter;
- the workbench, and the kind changed to *pattern tree*;
- the four lifts, as now.

That is **about 42 actions against 110**, before fix-ups. A fix-up is any node the explode
spells differently from the fixture, and each one costs a node edit of about six actions. Two
are likely:

- `FIELD` is a nested sequence. A regex has no way to say "these three are one thing" except
  a group, and an explode may flatten a non-capturing group.
- A character class may come back spelt differently, for example `\S` as `[^\s]`.

Whether the explode lands on the fixture's tree is the first thing to find out, as §6.3's
question was for lifting. If it does not, the choice is between re-baselining
`library_parts`'s configuration to the explode's spelling and fixing up by hand. Re-baselining
is allowed, because the fixture's output is what is fixed and its configuration is not. Fixing
up by hand is what the measurement would count.

A count understates one thing here: a 120-character regex is one action but not an easy one.
The tree is still the way in for an author who does not write regexes. For one who does,
which is everyone coming from DS3, the regex is the natural spelling, and the explode turns it
into the tree.

**Done 2026-11-19, §7.13.** Rung 4 walked regex-first at 41.

*Later, if F is not enough:* the tree gets A's treatment. An add line under the selected node
takes a regex fragment and explodes it into the nodes it is, about three actions a node
against six. That only pays for building a tree a piece at a time, and nothing walked so far
does that.

#### G — A delimiter on the Match line

B's Match line opens in place for a regex only. This proposal gives it a kind, the way an open
card has a kind (D). Opened, the line is the kind box and then that kind's fields:

- a regex is the pattern;
- a delimiter is its character, spelt with `ControlEscapes`, and the container's start and
  end;
- every other kind is the `workbench` link, as now.

A new delimiter's field arrives with its newline selected, so typing replaces it. A template
just made arrives on this line, as now. The chain then serves a delimiter template too, and
the Escape out of it is no longer needed.

A delimiter template's match drops from 8 or 12 actions to 4 or 7. **Rung 5: about 17 fewer,
158 → 141.** Rungs 1 to 4 do not change.

**Done 2026-11-19, §7.11.** Rung 5 walked again at 146, 12 fewer.

#### H — Literals are text, and the line can name any reference

H has two parts that only pay together.

- **Re-baseline rungs 4 to 6 so their literals are `text`, and their joined lines are the
  sequences A builds.** That is the shape §2 ruled to be the model, and the one the trace
  can show bytes for. The configuration changes and the output does not; `GoldenFixtureTest`
  is the proof, as it was for the document templates (design 46 §6.3).

- **A's line reads `${…}` as a reference of any kind.** `${record_body}` and
  `${heading[matchCount()]}` become `value-of` cards, as `$1` does. This amends a ruling: A
  said "a bare word is text, never a declared name: the field must not guess". `${…}` is not
  a guess, because the author has said "reference". What goes inside the braces is read by
  the Select field's own grammar, through `Instructions`, the reader that already owns the
  spelling. `$$` still writes a dollar.

**Ruled (the owner, 2026-11-19): both, together, as recommended.**

With both:

- rung 5's `if` holds one typed line, `\n   <record>${record_body}\n   </record>`, where it
  held 15 actions;
- the data line is `\n      <data name="${heading[matchCount()]}" value="$1"/>`;
- rung 4's body is `<event time="$time" …>$msg</event>\n`.

**Rung 5: about 16 fewer. Rung 4: 3 fewer.** The re-baseline alone saves almost nothing. The
data line is cheaper as one quoted `value-of` than as a sequence with one card for
`heading[matchCount()]` picked from the menu, so without `${…}` the two roughly cancel. It is
the line's reach that pays.

It does not answer design 44 Q14. `matchCount()` is still something an author has to know
exists. It only makes it cheaper to type once they do.

**Done 2026-11-19, §7.14.** Rung 4 at 39, rung 5 at 130.

#### I — A default where there is one answer

- **A new `apply-templates` card's Mode is the mode nothing dispatches into, when exactly one
  exists.** In rung 5 the author makes `header_columns` and then goes straight to the card
  that dispatches into it, and the same for `data_columns`. That saves 2 each. C's pane covers
  the document's dispatch; this covers the rest.
- **A `variable` naming something undeclared offers *declare it here*.** One press makes the
  scalar declaration on the template, where the declaration dialog costs five. List
  declarations still go through the dialog, because a list's place is a decision.
- **A new `if` arrives with one clause row open.** An `if` with no clause is not an
  instruction, so the extra `+ clause` press is never wanted.

**Rung 5: about 7 fewer.**

**Done 2026-11-19, §7.15.** Rung 5 at 121, 9 fewer.

#### J — *Only if it writes something*

Rung 5's idiom spends 39 actions on one intention: don't write the record's envelope around
nothing. The fixture's shape is:

- a scalar declaration, `record_body`;
- a `variable` holding the dispatch;
- an `if` whose clause is `exists record_body`;
- the envelope, with `record_body` between its two halves.

The proposal is an action on the body's toolbar that wraps the selected rows in exactly that.
The author writes the envelope and the dispatch as plain rows (`<record>`, the
`apply-templates`, `</record>`), selects them, presses the action and names the variable. The
editor finds the one row that dispatches, binds it to the variable and declares the name. It
builds nothing the model does not already have, and the result is ordinary cards that can be
edited one by one.

**About 13 fewer once H and I are in, 28 → 15.** Its worth is more than the count. Design 46
§6.4 calls this "the idiom every real configuration will need", and today an author has to
assemble it from three instructions whose combination nothing suggests. This is the proposal
most likely to be wrong in its details. It should be built after the walks have shown whether
H and I leave this idiom cheap enough without it.

### 7.5 What these would come to

| | rung 4 | rung 5 |
|---|---|---|
| as driven, 2026-11-18 | 122 | 158 |
| as an author would drive it (no needless clears; no rename) | 110 | 154 |
| with G | 110 | about 137 |
| *with G, as driven 2026-11-19 (§7.11)* | *122* | *146* |
| *with F, as driven 2026-11-19 (§7.13)* | *41* | *146* |
| *with H, as driven 2026-11-19 (§7.14)* | *39* | *130* |
| *with I, as driven 2026-11-19 (§7.15)* | *39* | *121* |
| with F | about 42 | about 137 |
| with H | about 39 | about 121 |
| with I | about 39 | about 114 |
| **with J** | **about 39** | **about 101** |

That is about two thirds off rung 4 and one third off rung 5. Rung 4's figure depends on F's
explode landing close to the fixture's tree. Rung 5's depends most on G and H, which are the
two firmest.

### 7.6 The plan

In order. Every phase ends by walking the rungs it touches again. All the keystones have to
hold, and the counts are taken again.

1. ~~**Fixes, with no ruling needed: 4, 5, 6, 12, 13, 14, 15, 17.**~~ **Done 2026-11-19, §7.7.** The tree is sized to its
   boxes. The capture offers names in scope. The `if` row becomes keyboard-reachable and
   named. Then the story artefacts: canned traces keyed by template name, and no needless
   clears, so that rung 4 counts 110. Then the Match line says when its regex does not
   compile, a pattern part is named in the workbench title as a template is, and a drop on
   `+ instruction` lands at the end of its list.
2. ~~**The two fixes that needed a word: 7 and 8** (§7.3).~~ **Done 2026-11-19, §7.9.** Ruled 2026-11-19: the no-mode level
   reads `default`, which is reserved; the row carries its limit.
3. ~~**G — a delimiter on the Match line**, then F, in this order (confirmed 2026-11-19). The firmest saving left, built the way B was. Rung
   5 is walked again.~~ **Done 2026-11-19, §7.11.**
4. ~~**F — measure first.** The walkthrough's explode stub returns what `PatternExplode` makes
   of rung 4's regex, computed by the engine in a test rather than written by hand. Then the
   walk goes regex-first. The result decides between a re-baseline and fix-ups, and whether
   the tree's own add line is wanted.~~ **Done 2026-11-19, §7.13**: re-baselined, 41, and no
   add line.
5. ~~**H — ruled 2026-11-19: both parts go.** The re-baseline of rungs 4 to 6's literals and the `${…}` amendment
   to A go together, or neither goes.~~ **Done 2026-11-19, §7.14.**
6. ~~**I** — three defaults, no new surface.~~ **Done 2026-11-19, §7.15.**
7. **J — needs ruling, and is decided after 5 and 6 have been walked**, against what the idiom
   then costs. *Walked: with H and I in, the idiom is 28 of rung 5's 121, as the estimate
   assumed, so J would make rung 5 about 108.* **Ruled 2026-11-19: rung 6 first; J stays
   open**, to be decided once rung 6 and real DS3 configurations show how often the idiom is
   needed and in what shape.
8. ~~**Rung 6**: the stream fixture as ruled in design 46 §6.5, then the walk. That is the last
   rung, and the binary-sample questions only it can answer.~~ **Done 2026-11-19, §7.17.**

Items 16, 18, 19 and 21 are not scheduled. 16 is a nicety. 18 is a trial for the owner rather
than work. 19 waits on design 18. 21 waits until everything above has been measured, because
it is the one large piece of new surface left.

### 7.7 Step 1, done — 2026-11-19

The eight fixes that needed no ruling, each with a story that would fail without it. All 175
Shapeshifter stories pass with the leak and accessibility checks, and rungs 1 to 5 count 58,
56, 49, 110 and 158.

- **4 — the tree is the height of its boxes.** The canvas the connectors are drawn on was
  absolutely positioned, and so were the boxes, so nothing in the flow had any height. The
  canvas, which is sized to the layout, is now in the flow and the boxes are laid over it.
- **5 — the capture offers every declared name.** The engine's rule (design 35, in its
  reference check) is that a name is declared once, on the template it lives for, and bound
  or read from anywhere. The dialog listed only its own template's names, and so did the
  body's name hints. Both now list the template's own names first, then the rest of the
  project's (`Templates.declaredNames`, with unit tests).
- **6 — the condition row is reachable and named.** `+ clause` and a row's `✕` are buttons;
  the name, operator, value and *read as* boxes have names. **The third part of the finding
  was a misreading:** the name box is an editable selection box, which types where it is
  clicked and opens its list from its arrow, as every editable box in Stroom does. Rung 5's
  story drives the row by those names now.
- **12 — the canned traces describe the walked project.** The preview stub rewrites the
  fixture's template ids to the walked ones, matched by name. **That was half of it.** The
  traces also had no timings at all, and timings are what a row's count is read from, so the
  rows read *not tried* whatever the ids were. Rungs 1, 4 and 5's traces now carry timings
  consistent with their frames, and each rung's last stage asserts the counts.
- **13 — no needless clears.** Rung 4's node dialogs are typed into as they arrive, and the
  story asserts the field is empty first. Rung 4 is 110 actions as driven.
- **14 — the Match line says when its regex does not compile.** While the line is open, what
  is typed is put to the engine (`patternInfo`, as the workbench's regex form does), and its
  reason is shown under the line and marked on the field as invalid. Nothing is said of an
  empty field, nor once the line is closed, when the messages take over.
- **15 — a pattern part is named where it is shown.** The panel's add makes `new part` and
  opens the workbench with its name open, as `+ template` and `+ mode` do. *Extract to
  library* keeps its prompt, on purpose: the part it makes is not on screen at that moment, so
  there is nowhere to name it in place. The name-prompt stories go through Extract now.
- **17 — `+ instruction` takes a drop**, landing the card at the end of that list, which is
  also how an empty branch gets its first card by dragging. A holder is refused a drop into
  its own lists, as a card refuses one. The first story to drive a drag at all; it fires the
  browser's drag events with one `DataTransfer` between them.


### 7.8 A code review's findings, fixed — 2026-11-19

A review of the working tree after step 1 found nine faults. None of them came from step 1:
they were in the work it built on, mostly the in-place title, the mode's pane and the Match
line. All nine are fixed. Each of the first six has a story that fails without its fix, which
was checked by taking the fixes out and running the stories. Finding 5's story was also run
against the old rename (re-select afterwards), to show it fails at the crumb rather than
earlier. All 181 Shapeshifter stories pass with the leak and accessibility checks.

| # | Fault | Fix | Story |
|---|---|---|---|
| 1 | A template's title and a body card left open together, then another template chosen: the card was taken first, and the title then found the template changed and was dropped without a word | The title's late write is refused only if the title's own fields have changed. It is written onto the template as it is by then (`Templates.sameIdentity`, `withIdentityOf`) | `LeavingTakesTheTitleAndTheCardTogether` |
| 2 | A part's name, open, stayed open when another part was shown, and Enter renamed that one, along with every tree naming it | Showing another part lets the name go, as the strip lets a mode's go | `APartsNameGoesWithThePart` |
| 3 | An open Match field stayed open when the template changed without the keyboard moving (Ctrl+Alt+→, a step back), and was then written as the next template's regex | Leaving a template takes the field for the template it was typed for, a moment later, as a title is | `AMatchBeingTypedStaysWithItsTemplate` |
| 4 | A template could be renamed to a name another template had, and two of a name make `call-template` ambiguous | Refused, with a warning, as a mode's or a part's taken name is. Let go quietly when the title is left rather than taken | `ATemplateNameAlreadyTakenIsRefused` |
| 5 | Renaming a mode or a part moved the cursor to the document: the selection was lost while the rows were redrawn, and found again afterwards | The root does the rename, and moves the selection to the new row first (`TemplatePanelController.follow`), so it is never lost | `RenamingAModeLeavesTheCursorWhereItWas` |
| 6 | Locking the document with an empty mode shown dropped its row and left its pane showing, with controls that did nothing | A refresh that loses the selection says so, so the document is shown | `LockingWithAnEmptyModeShownShowsTheDocument` |
| 7 | Every title that opened fetched the encodings again | The strip keeps one settings controller for its life | — |
| 8 | `TemplatePanel` kept a map of its add links that nothing read | Removed | — |
| 9 | `Modes.dispatchState` worked out the same reason twice | Once | — |

`Templates.sameIdentity`, `withIdentityOf` and `nameTaken` have unit tests. The strip asks its
owner to rename a mode (`setOnRenameMode`), and the workbench asks for a part
(`setOnRenamePart`). Both used to rename it themselves and tell the owner afterwards, which
was too late to keep the selection.

### 7.9 Step 2, done — 2026-11-19

Both fixes were built as ruled. All 182 Shapeshifter stories pass with the leak and
accessibility checks, and the golden fixtures all compile.

- **7 — the templates with no mode are called `default`, and `default` is reserved.** The
  name is `Template.DEFAULT_MODE`, in the configuration model, so the editor and the engine
  read one constant. The editor shows it through `Modes.label`, in the six places that said
  `root`:
  - the panel's heading, now `mode: default` like every other;
  - the `+ template` link's title;
  - the strip, now `mode default`;
  - the Mode pickers in a template's settings and on an `apply-templates` card;
  - a dispatch card's summary, now `in mode default` where it said `into the root`;
  - the body's *nothing applies into mode* note.

  Renaming a mode to `default` is refused with a warning (`Modes.whyNotName`). The compiler
  refuses a template in a mode called `default`, and a dispatch into one wherever it sits
  (`TemplateUses.refuseReservedMode`). The configuration package's *nothing in the language is
  reserved* now names the exception. Rung 5's walk makes a mode called `root`, and its
  last stage asserts `mode: root` and `mode: default` side by side.
- **8 — a template's row says its limit**, after its count: `· first only` for at most one
  match, `· at most N` otherwise (`Templates.limitNote`). It has its own cell, so the count
  reads as it did, and the row's tooltip says the limit too. The fewest and the only matches
  are left to the strip: they say what the matches mean, not how many there can be. Rung 5's
  last stage asserts `header_record` says *first only* and `data_record` says nothing.

Story: `TheTemplatesWithNoModeAreCalledDefault`. Unit tests: `ModesTest` for the label and
the refusal, `TestTemplates` for the limit, and the engine's `TestTemplateUses` for the
compile refusal.

### 7.10 A second review's findings, fixed — 2026-11-19

A review of step 2's working tree found ten faults, all in step 2's own work. All ten are
fixed. Each of the first four has a unit test that fails without its fix.

| # | Fault | Fix | Test |
|---|---|---|---|
| 1 | Removing a mode that dispatches still point at asked whether they should "become dispatches into the root", where `root` is now an ordinary mode name | The question is `Modes.removeQuestion`, and says *into mode default* | `ModesTest.removingAModeSaysItsDispatchesGoToTheDefaultMode` |
| 2 | A template that eats its matches showed `· first only` if it kept a limit from before its role was switched, though the engine never applies a maximum to an eater (`Level`) | `Templates.limitNote` takes the template and says nothing for an eater | `TestTemplates.aTemplateThatEatsItsMatchesHasNoLimitToShow` |
| 3 | A project written elsewhere with a mode named `default` showed it as the templates with no mode: the panel merged the two under one heading, and the pickers listed `default` twice | `Modes.label` calls it `default (reserved)`, and both Mode pickers show their items through it. Renaming it away is the remedy, and the compiler still refuses it | `ModesTest.aModeNamedDefaultIsNeverShownAsTheTemplatesWithNoMode` |
| 4 | A limit of no matches read `· at most 0` | It reads `· never matches` | `TestTemplates.aRowWhoseLimitAllowsNoMatchSaysItNeverMatches` |
| 5 | A comment still said the templates with no mode are headed unnamed | Says what the code does | — |
| 6 | H's ruling sat between its two bullets, where Markdown reads it as the first one's | Below both | — |
| 7 | `refuseReservedMode` walked the templates and then the uses, though each use carries its template | One walk over the uses | `TestTemplateUses` |
| 8 | `Walkthrough.expectLimit` asserted at once, where the panel may still be redrawing after a run | It waits, as `expectTried` does | — |
| 9, 10 | The engine's new method and test used `/** */` comments, where the guide asks `///` of new code | `///` with its parameter, and `//` on the test | — |

### 7.11 G, done — 2026-11-19

The Match line opens on a delimiter as it does on a regex. Opened, it is the kind box —
*regex* or *delimiter* — and then that kind's fields: a regex's pattern, or a delimiter's
separator and the container either side of it, spelt with `ControlEscapes`. Every other kind
is still the workbench's. A delimiter chosen on the line arrives on a newline, selected, so
typing replaces it. Tab moves between the fields without taking the line; Enter takes it,
and for a template whose body is empty goes on into the body, so the chain from a new
template's name serves a delimiter template too. Escape puts the match back as it was,
kind and all.

- **Choosing another kind asks first where it throws work away.** A template just made has an
  empty regex, and a new delimiter splits on a newline; replacing either loses nothing and is
  not asked about. Replacing a regex or a delimiter someone wrote is (*Make the match a
  delimiter? The regex it holds, …, goes.*). The workbench does not ask, because a kind picked
  there is converted where the engine can say what it means; the line only replaces. A kind
  chosen and then left by moving to another template is taken only where it loses nothing,
  since the template it would be asked about is no longer on screen.
- **A delimiter with nothing to split on is refused where it is**: the line stays open, says
  why under itself, and keeps what was typed on it, as the workbench's form does.
- **What it keeps.** A regex keeps its flags and advance, and a delimiter its escape: those
  are the workbench's to change.

`MatchLine` is the line's reading and writing of a match, in the model, with its unit tests
(`TestMatchLine`). It also builds a delimiter from its spelling for the workbench's delimiter
form, so the two cannot come to disagree. The line takes itself when the keyboard leaves it — not when it moves
between the line's own fields, or into the kind box's list. `BaseSelectionBox.isOpen` says
which.

Stories: `ADelimiterIsWrittenOnTheMatchLine`, `EscapePutsTheMatchsKindBack`,
`ARegexIsNotReplacedWithoutAsking` and `ADelimiterNeedsSomethingToSplitOn`.

**Rung 5, walked again: 146 as driven, where it was 158.** The four delimiters and the limit
cost 31, where they cost 42:

| | before | with G |
|---|---|---|
| `header_record`, `\|` | 7 | 5 |
| the limit | 3 | 4 — the workbench is opened for it now, where before it was already open |
| `header_column`, `,` in quotes | 12 | 9 |
| `data_record`, `\|` | 8 | 5 |
| `data_column`, `,` in quotes | 12 | 8 |

`data_column`'s Enter opens the card the data line is written in, so the data line no longer
clicks `+ instruction`: one more off. The other three delimiters each end with an Escape out
of the card their Enter opened, which the walk uses to leave each stage tidy; whether an
author needs it has not been checked. Without those three, and without the rename, rung 5
is 139, against §7.5's estimate of about 137.

### 7.12 A review of G, fixed — 2026-11-19

A review of G's working tree found nine faults. All nine are fixed. Each of the first three
has a story that fails without its fix, which was checked by taking the fixes out.

| # | Fault | Fix | Story |
|---|---|---|---|
| 1 | A regex's check still on its way when the line was made a delimiter answered onto the delimiter's fields | An answer is shown only while the line is a regex | `ARegexAnswerIsNotShownOnADelimiter` |
| 2 | A delimiter with nothing to split on closed the line, lost what was typed on it, and raised a warning dialog even when the line was only left | The line stays open until it is accepted, and a refusal is said under it | `ADelimiterNeedsSomethingToSplitOn` |
| 3 | Cancel on the question left the keyboard on a hidden field and lost what was typed; OK moved the keyboard to whichever template was shown by then | Cancel leaves the line open with the keyboard back in it. OK writes the template asked about, and moves the keyboard only if that template is still shown. While the question is up, the line is not taken a second time | `ARegexIsNotReplacedWithoutAsking` |
| 4 | A kind box emptied would have sent the controller a line with no kind | The box goes back to the line's kind, and a line with none is the kind it opened as (`MatchLine.withKind`) | unit test |
| 5 | The strip and the body each had their own native call for the element with the keyboard | `ContentFocus.activeElement`, shared | — |
| 6 | `MatchLine` copied the workbench form's delimiter rules | One builder, `MatchLine.delimiter`, for both; the form's unused default is gone | unit test |
| 7 | A whole line was built only to ask whether the line could open on a match | `MatchLine.spells` | unit test |
| 8 | `isOpen` sat between `togglePopup` and its comment | Moved | — |
| 9 | The regex check read the line twice | Once | — |

OK while the strip shows another template, and an emptied kind box, cannot be reached from
the screen as it is — the question is modal, and the box offers no empty choice — so 3's
second half and 4 have no story.

### 7.13 F, measured — 2026-11-19

Rung 4 written as a regex, the way an author coming from DS3 writes it:

```
(?<time>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z) host=(?<host>\S+)
 severity=(?<severity>\p{L}+) msg=(?<msg>.*?)(?: [a-z]+=)(?<pid>\p{Nd}+)[^\n]*\n
```

on one line, with a named group for each field, a group round what will be `FIELD`, and the
rest of the line.
`PatternExplode` makes it into the fixture's tree but for three nodes:

| | the explode | the fixture had |
|---|---|---|
| `TIMESTAMP` | a sequence of twelve: the runs of digits and the tags between them | one `regex` node |
| `msg` | a lazy repeat of any character | `take_until " pid="` |
| the line's end | a run of anything but a newline, then a tag, `\n` | `take_through "\n"` |

The explode never makes a `take_until` or a `take_through`: no regex is one. The other seven
land exactly, `FIELD` included — the non-capturing group stays a nested sequence, and `KEY`
is the run inside it.

**A fault found on the way, and fixed.** `\p{L}` and `\p{Nd}` exploded to classes written
out in full, thousands of ranges each. The regex parser labelled a property class with `\p`
alone, which reads back as no class, so the explode could not keep the author's spelling and
rendered the set. The label is now the whole escape (`Parser`), and `ExplodeTest` holds a
property class to its spelling. A label is only ever shown; what a pattern matches did not
change.

**Ruled 2026-11-19: re-baseline.** `library_parts` is now in the explode's spelling. Its output
is unchanged — `GoldenFixtureTest` — and `PatternExplodeTest.libraryPartsIsTheExplodeOfOneRegex`
holds the explode of the regex above to be the fixture's line with its parts written in place.
The walk's explode route serves that tree. What the re-baseline costs and gains:

- `msg` is a lazy run where it was a scan to ` pid=`, so it ends at the first ` key=digits`
  rather than the first ` pid=`: `msg=a b=1 c pid=5` reads `a`, with `b=1` as the field.
  The fixture's messages hold no `=`. What follows the pid, and a CRLF, are still the rest of
  the line, as the take-through had them, and `msg=old pid=3 died pid=5` still reads `old`
  and `3` (`ProjectLibraryTest.libraryPartsReadsWhatFollowsThePidAsTheRestOfTheLine`).
- *Corrected 2026-11-19.* The re-baseline was first built with the line ending in `\n` alone,
  and ruled on with its cost misstated as *"`msg` could end earlier"*. A review found the
  opposite: no line with anything after the pid, or with a CRLF, matched at all, and `msg`
  ran on to the last ` key=digits`. The regex gained `[^\n]*`, and the ruling was put again
  with the cost as it is.
- A regex part labelled where it is named — the old `TIMESTAMP` — was the only case of it in
  the corpus, so `ProjectLibraryTest.aRegexPartLabelledWhereItIsNamedCapturesWhatItMatched`
  holds it now.
- The demo pack, `shapeshifter-demos.zip`, is rebuilt: one entry, `library_parts`, changes.
- `take_until` and `take_through` are each still in one other fixture, and
  `progressive_text_steps` — the same line, unfactored — keeps the regex node, the take-until
  and the take-through it always had.
- The line matches on tier 2 where it matched on tier 4. The take-until was followed by `FIELD`
  rather than its own tag, so it lowered to a lookahead and the backtracker.

**Rung 4, walked again: 41 actions as driven, where it was 122.** The 83 actions of the tree's
nodes become the regex and its Enter, 2. The walk's explode route serves the tree for that one
regex and refuses any other. That the story's regex and tree are the engine's `RUNG_FOUR` and
its explode is kept by hand: the engine holds `RUNG_FOUR`'s explode to the fixture, and the
keystone holds the lifts from the story's tree to it. Everything else is as it was: the template, the tree,
the four lifts at four each, the body and the run.

The tree's own add line (F's *later*) is not wanted: nothing walked builds a tree a node at a
time any more.

### 7.14 H, done — 2026-11-19

Both parts, together, as ruled.

**The re-baseline.** Every `value-of` in rungs 4 and 5's fixtures that mixed literals with
references is now the sequence a typed line makes of it: each literal a `text` card, each
reference its own `value-of`. A `value-of` that held only a literal is a `text` card. That is
`library_parts`'s event, and `text_019_single_line_split`'s envelope, record and data line.
The output is unchanged: `GoldenFixtureTest`, 75 with one skipped as before. The demo pack is
rebuilt, and exactly those two entries change.

**Rung 6 is not re-baselined yet.** `progressive_len_records` is also a row of
`EngineBenchmark`, and splitting its one `value-of` into three cards changes the work that
row measures, so runs either side of the change would be compared as like for like. **Ruled
2026-11-19:** it stays as it is until rung 6 is walked, and is re-baselined then, with the
benchmark break decided at the same time.

**`${…}` on the line.** `Instructions.cards` reads what is inside the braces with the Select
field's own reader, so `${record_body}` and `${heading[matchCount()]}` are `value-of` cards.
A bare word is still text. A `}` inside a quoted literal does not close the braces, and `$$`
still writes a dollar, so `$${HOME}` is the text `${HOME}`, and the line's help says so. These
are refused, saying why, and the line stays open to be put right (`TestInstructionCards`):

- a `${` with no `}`, an empty `${}`, and a quote inside the braces left open;
- a modifier that did not bind, as the Select field refuses it (`Instructions.readChecked`,
  which the Select field now uses too);
- a spelling the reader could not finish. What it cannot read it reads as one whole name, so
  `${heading[matchCount()}` was a name with brackets in it, writing nothing at run time. A
  braced name holding the grammar's punctuation, or only digits, is refused. The Select field
  still takes such a name; that is the reader's, and is not changed here.

A line left — another template chosen — with a `${…}` that is refused cannot stay open, since
what is open is going, and is said not to have been added (`ALineThatCannotBeAddedIsSaidToBeLost`).

*Behaviour that changed with H:* a body that writes `${` as text, shell or a template
language, now reads it as a reference. It is written `$${`.

**The walks.** Rung 4's body is one typed line, `<event time="$time" …>$msg</event>\n`.
Rung 5's envelope is two lines, its record is `\n   <record>${record_body}\n   </record>`,
and its data line is `\n      <data name="${heading[matchCount()]}" value="$1"/>`. Both
keystones hold the cards those lines make.

| | before | with H |
|---|---|---|
| rung 4, the body | 7 | 5 |
| rung 5, the envelope | 14 | 10 |
| rung 5, what the `if` holds | 14 | 4 |
| rung 5, the data line | 5 | 3 |
| **rung 4** | **41** | **39** |
| **rung 5** | **146** | **130** |

Rung 5 saves the 16 the proposal estimated. Rung 4 saves 2 against an estimate of 3: the
estimate did not count the Escape out of the card the line leaves open.

### 7.15 I, done — 2026-11-19

All three defaults, as proposed.

- **A new `apply-templates` card starts on the one mode nothing dispatches into**, where there
  is exactly one (`Modes.onlyUndispatched`), never the mode of the template it is in. With two
  waiting, or none, it starts on the default as before. A card that already exists keeps its
  own mode, and a mode chosen on a new card stays chosen when its kind is changed and back.
- **A `variable` naming what nothing declares offers *declare … here*.** The offer shows as
  the name is typed: a name an author could declare, which no template declares and which is
  not a param or a function (`Templates.undeclared`). One press declares it on the card's
  template as a scalar (`Templates.withScalar`), and the card stays open with the keyboard
  back in the name. A list is still declared with `+ declaration`, because where a list lives
  is a decision.
- **An `if` with no clause has one clause row**: a new one, and a card of another kind made
  an `if`.

Stories: `ANewIfHasItsFirstClause` and `AVariableOffersToDeclareItsName`, and
`ACardCanDispatchIntoAModeWithNoTemplatesYet` now finds its mode chosen where it used to pick
it. All three fail with the defaults taken out. Unit tests: `ModesTest` and `TestTemplates`.

**Rung 5, walked again: 121, where it was 130.**

| | before | with I |
|---|---|---|
| the two dispatch cards' modes | 4 | 0 |
| `record_body`'s declaration | 5, the dialog | 1, the offer |
| the `if`'s first clause | 1 | 0 |

That is 9, against the estimate of about 7.

### 7.16 A review of I, fixed — 2026-11-19

A review of I's working tree found ten faults. All ten are fixed. Each of the first six that
can be reached from the screen has a story or a unit test that fails without its fix.

| # | Fault | Fix | Test |
|---|---|---|---|
| 1 | A new dispatch could start on the mode of the template it was in — a template in `data_columns` opened before anything dispatched into it — and a template dispatching into its own mode recurses until the engine calls it a runaway | `onlyUndispatched` leaves out the card's own template's mode | `ModesTest.aNewDispatchNeverGoesToItsOwnTemplatesMode` |
| 2 | *Declare* marked the name declared before the body had declared it, so a body that could not — read only by then, or the template gone — left an offer that had vanished as if it had worked | The body answers whether it declared, and the offer is said again from what the project then holds | — |
| 3 | The names the offer read were those the card opened with, so a name declared elsewhere meanwhile was still offered, and a second, scalar, declaration of it could be made | The card asks the body for the names as they are now, before offering and again before declaring | — |
| 4 | The offer was said again only on a key, so a paste with the pointer, a drop or an autofill left it stale | It follows the fields' `input` event | `ANamePastedIsOfferedToo` |
| 5 | A card of another kind made an `if` had no clause row; only a new card had one | Any `if` with no clause has one | `ACardMadeAnIfHasItsFirstClause` |
| 6 | The mode a new dispatch starts on was set again whenever the kind was chosen, throwing away a mode the author had picked | Set once, when the card is new | `ANewDispatchKeepsTheModeChosen` |
| 7 | The regex form had its own copy of *declare a scalar unless declared*, and the offer its own copy of what a name may be | `Templates.withScalar` and `Instructions.isPlainName`, each once | — |
| 8 | Opening a card walked the project for its modes twice | Once | — |
| 9 | `onlyUndispatched` allowed for a duplicate the list of modes never holds | Gone | — |
| 10 | The view's new callbacks sat apart from the others | With them | — |

2 and 3 cannot be reached from the screen as it is — the offer is pressed in the card, and
nothing else can change the names or the lock between the offer being shown and pressed — so
they have no story.

### 7.17 Rung 6, walked — 2026-11-19

The last rung, `progressive_len_records`: a binary sample of length-prefixed records, chosen as
a stream, matched by a framed sequence — a `read` of a varint labelled `len`, and a `take` as
long as `len` — in a template that reads raw. Nine stories, **45 actions**, and the keystone
holds. Design 46 §6.5a has the walk.

**A binary sample did not reach the engine as itself. Ruled 2026-11-19: fixed here.** The
preview read a stream sample with the stream viewer's text fetch — decoded in the feed's
encoding, re-encoded as UTF-8 — so bytes from 0x80 up became other bytes before the engine saw
them. Now:

- `StreamSamples` reads the record's bytes as stored, as the viewer's fetch reads them: only by
  a user with the View Data app permission, then through the store under their permissions on
  the feed, inside `SecurityContext.useAsReadResult`. A record of a segmented stream keeps the
  stream's header and footer, its index held within the stream; no more is read than the viewer
  would fetch (`maxCharactersPerFetch`), cut where a UTF-8 character ends; a record cut short
  says so, as a warning in the run's messages.
- `ShownText` says how bytes are shown: the input in the encoding its project declares where
  its characters can be counted (UTF-8, a single-byte encoding, UTF-16) and it is well formed
  there, else as UTF-8 where it is UTF-8, else a byte to a character — and output as UTF-8 or a
  byte to a character the same way. `TraceChars` shows every slice of the input as the input.
- The trace says which charset its input is shown in, and the workbench sends text it took from
  the input back in that charset, so the engine meets the bytes the text was shown from.
  *(Corrected 2026-11-19, §7.18: as first built, the read skipped the View Data check, and the
  workbench sent a binary sample back as UTF-8.)*

Tests: `TestStreamSamples` — the bytes as stored, the segmented record, the limit — and
`ShapeshifterResourceEndpointsTest.previewOfAStreamRunsItsBytesAndShowsThemAByteToACharacter`,
over a record holding 0x80 and 0xFF, which fails without the byte-to-character reading. The
pipeline module's 183 tests pass.

**The re-baseline H left for this rung, and the benchmark. Ruled 2026-11-19: a frozen copy.**
The fixture's line is a `text`, a `value-of` and a `text` now, and its encoding is spelt
`utf-8`, as the editor writes it; the output is unchanged (`GoldenFixtureTest`). The
`progressive` row of `EngineBenchmark` — retired from the default rows since D54, run by name —
reads its own copy of the configuration as it was, `bench.project.json`, so it measures the same
work across commits. The demo pack is rebuilt; one entry changes.

**What the walk found besides.**

- The parts editor's `Length` was two controls under one label. Named for themselves now,
  `Length from` and `Length value`.
- `len` is typed: nothing offers the labels an earlier part defined. *Fixed 2026-11-19, §7.19
  item 1.*
- The story harness wrote JSON strings without escaping control characters, which a binary
  sample's length bytes are. Fixed.

| | actions |
|---|---|
| settings | 9 |
| the stream | 4 |
| the template, named | 4 |
| its encoding, raw | 4 |
| the framed sequence | 3 |
| the `read` | 8 |
| the `take` | 7 |
| the body, one line | 5 |
| run | 1 |
| **total** | **45** |

### 7.18 A review of rung 6, fixed — 2026-11-19

A review of rung 6's working tree found nine faults, all in the byte path. All nine are fixed,
as ruled 2026-11-19. Each of the first five has a test that fails without its fix, which was
checked by taking the fixes out.

| # | Fault | Fix | Test |
|---|---|---|---|
| 1 | **The read skipped the View Data app permission** the viewer's fetch requires (`DataServiceImpl.fetch`), so a user with only Use on a feed could read its raw bytes through the preview | `secureResult(VIEW_DATA_PERMISSION)` round the read, as the fetch has it | `TestStreamSamples.aUserWhoMayNotViewDataReadsNothing` |
| 2 | The workbench sent text it took from a binary sample back as a pasted sample, which was encoded as UTF-8: every byte from 0x80 up became two | The trace names its input's charset, and text taken from the input goes back in it (`TraceModel.encodingOf`, `sampleEncoding`) | `ShapeshifterResourceEndpointsTest.aBinarySampleSentBackAsItWasShownIsItsBytesAgain`, `TraceModelTest` |
| 3 | The limit, a count of characters, cut bytes inside a UTF-8 character, and the whole text was then shown as no text | The cut ends where a character does | `TestStreamSamples.aRecordCutShortEndsWhereACharacterDoes` |
| 4 | A record index past the last, or none yet, threw, where the viewer holds it within the stream | Held as the viewer holds it | `TestStreamSamples.aRecordPastTheLastIsTheLastAndNoneYetIsNothing` |
| 5 | Output that is not UTF-8 was shown as UTF-8, while its offsets counted it otherwise | Output is shown by the same rule as input | `TestTraceChars` |
| 6 | The display knew only UTF-8 or a byte to a character, so a UTF-16 feed showed a NUL between characters | The input is shown in the project's declared encoding where its characters can be counted (`ShownText`) | `TestShownText` |
| 7 | The display rule was written twice | Once, `ShownText` | — |
| 8 | The sample was decoded once to check it was UTF-8 and again to show it | A byte scan checks it | `TestShownText` |
| 9 | Two new methods' comments were `/** */` | `///` | — |

Number 5 was first tested through a preview whose output held a captured name of binary
bytes. That test passed without the fix: a capture is written as characters, so the output was
UTF-8 after all. The rule is held directly instead, on `TraceChars`.

Other multi-byte encodings — Shift_JIS, GBK and their kind — are still shown as UTF-8 or a byte
to a character: their characters cannot be counted from the bytes without decoding them, and
nothing walked uses one.

### 7.19 What is left — planned 2026-11-19

Every rung is walked. What remains, in the order ruled 2026-11-19: the small fixes first, then
what was ruled to build.

**Closed without work.** H left a question: a `${…}` naming nothing declared — `${HOME}` in a
line of shell — becomes a reference, and would anything say so? The engine does already:
reading a name no declaration names is refused at compile (`ReferenceCheck.declared`, *"reads
'HOME', which no declaration names"*), so the next validation says it.

**Small fixes.**

1. **`Length` offers the labels an earlier part defined** (§7.17, open). A `take` or `seek`
   whose length is a label's value is offered the labels of the parts before it, as the
   capture dialog is offered declared names. Rung 6 walked again with the label picked.
   **Done 2026-11-19.** Taken from a label, the length says under itself which labels the parts
   before bind — a `take`'s, a `read`'s, and any a pattern part labels in its tree, as the
   engine binds them. Where exactly one of them is a `read` cast to an integer, and the field
   holds nothing or the count a new part starts with, that label fills it; taken from a count
   again, the field gets back what the fill replaced (`PartLabels`, `TestPartLabels`,
   `ALengthFilledFromALabelGivesItsCountBack`). Rung 6's `take` no longer clears the field and
   types `len`: **43 actions**, where it was 45. Its stage fails with the fill taken out.
2. **The mode pane lists the sites that dispatch into the mode** (§7.2, item 16). **Done
   2026-11-19.** The line beside the mode's dispatch picker names its sites as a template's
   header does — *dispatched from source (body pos 2)* — where it gave only their number: the
   first three, then how many more, with every one in the line's tooltip (`Modes.dispatchNote`;
   `ModesTest`).
3. **A closing table** for this design: every rung, as first driven and as driven now. **Done
   2026-11-19, §7.20.**

**Then what was ruled.**

4. **Design 44 Q14 — ruled: suggest it in the Select.** Where a Select names a declared list,
   what follows it — `[matchCount()]`, `[index()]` — is offered there, where the author is
   looking when they need it. **Done 2026-11-20.** A Select holding the plain name of a list any
   template declares shows them under itself as links; one press puts the subscript after the
   name and the keyboard back in the field, and the offer goes, since the name now says which
   entry (`Templates.subscriptsAfter`, `Templates.declaredLists`, `TestTemplates`,
   `ASelectNamingAListIsOfferedWhichEntry`, `ASelectInAForEachIsOfferedItsIndex`). A scalar, a
   set or a map is offered nothing, and nor is a Select that is the collection taken whole: a
   `for-each`'s, a `for-each-group`'s, an `apply-templates`'. What the offer writes is read
   back by the Select's own reader as the match index it says, which a test holds it to.

   **`[index()]` only inside an iteration — ruled 2026-11-20**, amending the ruling above: a
   review found it offered at the top of a body, where nothing sets `index()`; the engine
   warns, and the reference reads the first entry every time. It is offered where a
   `for-each` or a `for-each-group` holds the card, as the engine's reference check counts it
   (`Bodies.iterated`). **Only the first field's Select — ruled 2026-11-20:** `append`,
   `insert` and `put` take their Select after a Target, and are not offered, as no fixture
   subscripts one.

   It is not offered in a typed line's `${…}`, which is where rung 5 writes
   `heading[matchCount()]`: the line holds text around the reference, and an offer there would
   have to know where in the line the author is. Rung 5's count is unchanged.
5. **The workbench leak check — ruled: retry before failing.** A story that fails only the leak
   check is run again, alone, before it is reported; a real leak fails both times. It misread
   more than thirty healthy stories this session whenever other work loaded the machine. It is
   the shared test runner's, so it is its own change. **Done 2026-11-20.** A story whose play
   and every other check passed, and whose leak check then failed — a leak, or the screen not
   opening again in time — is held back, and once every other story has run it is run again,
   one at a time. Passing alone it is reported as flaky, with the failed attempt kept; failing
   again, it fails with both (`run.mjs` `runAgainAlone`, `outcome.mjs` `failedOnlyLeakCheck`
   and `withAttempt`, the runner's README). Proved with a fault put in for the purpose: every
   card story's first leak check failed, 24 passed alone, and the one made to fail each time
   failed on both attempts with both screenshots kept.

**Left as they are.**

- **J — ruled: keep waiting** for real DS3 configurations, which have not arrived, to say how
  often the idiom is wanted and in what shape.
- **18** is a trial for the owner; **19** waits on design 18; **21**, B′, is the largest and
  least certain piece of new surface left, and waits until the rest is measured.

### 7.20 Every rung, as first driven and as driven now — 2026-11-19

Counted off the stories, as §1 counts them: an action is a click, a typed value or a pick from
a box.

| rung | fixture | first driven | now | what took it there |
|---|---|---|---|---|
| 1 | `strict_kv` | 77 | 58 | A, B, and what §7.7 counted after them |
| 2 | `lexer_tokens` | 98 | 56 | A, B, and what §7.7 counted after them |
| 3 | `classify_alerts` | 75 | 49 | A, B, and what §7.7 counted after them |
| 4 | `library_parts` | 122 | 39 | F's regex exploded into the tree, H |
| 5 | `text_019_single_line_split` | 158 | 121 | G, H, I |
| 6 | `progressive_len_records` | 45 | 43 | the length offered its label |
| | **all six** | **575** | **366** | **36% fewer** |

Rungs 1 to 3 are as §7.7 counted them: nothing since has changed what their walks do. Rung 6
was first driven after A to I were in, so its first count already had them.

What is left of the cost is mostly typing — names, patterns and lines an author has to write
anyway — and the idiom J would wrap, which is 28 of rung 5's 121 and waits on real DS3
configurations (§7.19).

### 7.21 A review of the small fixes, fixed — 2026-11-19

A review of items 1 and 2 found nine faults. All nine are fixed, three of them as ruled
2026-11-19. Each of the first six has a test that fails without its fix.

| # | Fault | Fix | Test |
|---|---|---|---|
| 1 | The hint read only a `take`'s and a `read`'s labels, where a pattern part binds them too — a netstring's digits, labelled — and said no part before had one | **Ruled: include them.** A pattern part's tree is walked for its labels | `TestPartLabels.aPatternPartsLabelsAreOfferedTooAsTheEngineBindsThem` |
| 2 | Any single label was filled in, though a length is read as an integer and a `take`'s label holds bytes | **Ruled: only an integer read fills.** Every label is listed; only a `read` cast to an integer is filled in | `TestPartLabels.onlyAReadOfAnIntegerIsFilledIn` |
| 3 | A count the author typed was replaced by the label | Only nothing, or the count a new part starts with, is replaced | `TestPartLabels.theOneIntegerFillsALengthThatHoldsNoLabelYet` |
| 4 | Taken from a count again, the field kept the label the editor had put there, and OK then refused it | What the fill replaced comes back | `ALengthFilledFromALabelGivesItsCountBack` |
| 5 | The note counted dispatches but named instructions, so one named site could be said to *differ* among *their cards* | Says the dispatches differ, and to set it on each card | `ModesTest.twoDispatchesInOneInstructionAreOneSiteAndCanStillDiffer` |
| 6 | Every site was named, however many | **Ruled: the first three, then how many more**; every one in the tooltip | `ModesTest.aModesDispatchNamesItsFirstSitesAndCountsTheRest` |
| 7 | The mode pane walked the project twice to count its sites and then name them | One walk, `Modes.dispatchState` | — |
| 8 | Three imports were left behind by the move | Gone | — |
| 9 | Two new methods' comments said only their parameter | Say what they do | — |

A regex leaf's named groups are labels too, and are still not offered: what a regex names is
the regex parser's to say, and is not read out of its text in the editor.
