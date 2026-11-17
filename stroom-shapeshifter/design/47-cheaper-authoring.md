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
| **adding body cards** | 28 | 36 | 28 |
| **making templates** | 17 | 41 | 25 |
| wiring the dispatch | 6 | 7 | 8 |
| everything else | 26 | 18 | 14 |
| **total, as driven** | **77** | **102** | **75** |

Two things that table says which rung 1 alone had hidden:

- **Cards and templates are 60 to 75 per cent of every rung.** Everything else — settings, the
  sample, roles — is small and roughly constant.
- **Templates cost more than cards once there are several.** Rung 2 has six templates with tiny
  bodies and is the most expensive rung walked, at 102. Design 46 §10 named only the card cost,
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

Net of those, an author's own cost is roughly **67, 88 and 67**.

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
value-of — the two kinds whose whole form is one value — are **23 of the 24 cards** added across
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
| today | 28 | 36 | 28 |
| one card per entry | 16 | 18 | 14 |
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
| text and value-of cards, before | 28 | 36 | 28 |
| the same cards, typed | 21 | 12 | 15 |
| **whole rung, before** | **77** | **102** | **75** |
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

### What these come to

| | rung 1 | rung 2 | rung 3 |
|---|---|---|---|
| as driven | 77 | 102 | 75 |
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

1. **B: the dialog or the strip.** A Pattern field in New Template, or the match chip made
   editable in place. *Recommended: the dialog — it is where the template is being described,
   and it does not make a second editing surface for patterns.*
2. **C's action**, when the document's dispatch already names another mode: add a second
   `apply-templates`, or offer nothing. *Recommended: offer nothing — a second dispatch site is
   a decision, not a completion.*

**Order:** A is done. Then B, then C.

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
