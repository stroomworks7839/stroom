# Design 48 — The concept budget: what rung 1 shows, and what it needs

*Inventoried 2026-11-23, from the owner's concern about the editor's apparent complexity and the
load of using it. §5 lists the cuts proposed, §5a what each would cost, and §6 checks them
against rung 5. **Ruled the same day (§7).***

The font inventory (design 44 §5m) asked of every text style on screen whether it earned its
place, and found fourteen where six would do. This asks the same of **ideas**. Design 46 counts
the *actions* a rung costs, and design 47 cut them; neither says whether the author understands
what they are looking at. A concept the author does not yet need still costs them something: it
has to be read, guessed at and set aside, every time the screen is looked at.

## 1. Method

The rung 1 walkthrough (`ShapeshifterAuthoringStories`, design 46 §5) was run with screenshots on
2026-11-23 and every one of its 26 stages read. A **concept** is anything on screen that names an
idea the author would have to understand to know whether it concerns them: a heading, a field, a
word in a summary, a control whose purpose is not its label, a mark. Stroom's own chrome (the
document tabs, Save) is not counted.

For each concept the table records:

- **Shown**: the first stage it is on screen.
- **Used**: the first stage rung 1 needs it, or *never*.
- **Words**: whose vocabulary it is in — the *author's* (plain, or what a DS3 or XSLT author
  brings), *DS3/XSLT* (familiar only from those), *engine* (Shapeshifter's internals) or *editor*
  (invented by the UI).

Rung 1 builds `strict_kv`: name the project, set the encoding, paste three lines, add a mode `kv`
dispatched from the document, add `pair` with a regex and five output lines, add `junk_line` with
a catch-all regex, *advance only* and a warning, and run.

## 2. What rung 1 needs

Fourteen concepts carry the whole rung:

> project name · encoding · sample · template · the document template · mode · dispatching into a
> mode · regex · `$0`/`$1`/`$2` · body line (text and value-of) · template order within a mode ·
> *advance only* · emit-error with a severity · run, output and messages

Everything else on screen is for some other rung, or for a question the author has not yet asked.

## 3. The inventory

### The panel

| Concept | Shown | Used | Words | Note |
|---|---|---|---|---|
| Settings row | 1 | 2 | author | |
| Data row, and its "none" / "pasted · 3 lines" | 1 | 4 | author | |
| Templates heading, its count, and that it folds | 1 | 6 | author | the fold is never used |
| `mode: default` heading | 1 | 10 | engine | names a mode before there is a second |
| the `document` row, and its kind word `source` | 1 | 6 | editor | stays `document` after the rename to `source` (Q11) |
| a template's colour square | 1 | never | editor | |
| `+ template` | 1 | 21 | author | duplicates the toolbar's Add |
| `+ mode` | 1 | 10 | engine | |
| Patterns heading, "none" | 1 | never | editor | the library, rung 4 |
| toolbar: add, remove, move up, move down | 1 | 21 (add) | author | up/down say "dispatch order" only in a tooltip, the one place order is named |
| toolbar: chain link, "add a pattern part" | 1 | never | engine | rung 4 |
| a row's count, `not tried`, `1 skipped ▲` | 5 | 27 | editor | "skipped" is the eater's, before the author has met eaters |
| the heat bar under a row | 5 | never | editor | performance, design 18 §5.8 |

### The settings (stages 1–3)

| Concept | Shown | Used | Words | Note |
|---|---|---|---|---|
| "the root frame: every template descends from here" | 1 | never | engine | *frame* before any frame exists |
| Project name | 1 | 2 | author | |
| Version | 1 | never | engine | the format's version, not the author's |
| Encoding | 1 | 3 | author | |
| Dispatch: *by version* | 1 | never | engine | the strategy of the root dispatch; not this rung's question |
| Buffer size | 1 | never | engine | |
| Max sequence entries | 1 | never | engine | |
| Ignore errors | 1 | never | DS3 | |
| Strict values | 1 | never | engine | below the fold |

### Above the template: the crumb and the stepper

| Concept | Shown | Used | Words | Note |
|---|---|---|---|---|
| "No run yet — choose the sample data in the panel" | 1 | — | author | |
| the crumb, a frame per segment, `#0`, `1/1` | 6 | 27 | engine | *frame*, again, and zero-based |
| "ran, and nothing matched — no template applied" | 6 | — | editor | said of a half-built project; true, and alarming |
| the stepper, `matches –/0` and four buttons | 6 | never | editor | nothing to step until the last stage |

### The template (the strip)

| Concept | Shown | Used | Words | Note |
|---|---|---|---|---|
| Template name, its pencil | 6 | 8 | author | |
| `mode default` / `mode kv` | 6 | 10 | engine | |
| "dispatched from source (body pos 2) · not tried" | 6 | never | engine | truncated at every stage; *body pos* is the engine's |
| the match line: its kind (`source`, `regex`) | 6 | 11 | author | |
| `workbench` | 6 | never | editor | rung 1 never needs it |
| Match is: *a record* / *advance only*, and its four lines of help | 6 | 24 | engine | help says "frame", "cursor", "captured", "counted" |
| Guard: *no guard · no limits* | 6 | never | DS3 | |
| Declarations, `+ declaration` | 6 | never | engine | rung 5 |
| Captures, `+ capture` | 6 | never | engine | `$1` works without it, so it reads as a step skipped |
| Body, and its toolbar: add, remove, up, down | 6 | 13 | XSLT | the toolbar's Add duplicates `+ instruction` |
| a card's colour swatch | 6 | never | editor | the card's colour in the output pane |
| a card's kind word: `text`, `value-of`, `apply-templates`, `emit-error` | 6 | 13 | XSLT | |
| `select $0 in mode kv` | 6 | 14 | XSLT + engine | `$0` before the author has met groups |
| a card's note: `no matches`, `wrote 0 chars` | 6 | never | editor | |
| the line entry: Instruction *auto*, Line, "$1 for a capture", OK, Cancel | 11 | 13 | editor | stays open after the last line, into the finished screenshot |
| emit-error's Severity and Message, `"text" $0` | 25 | 25 | engine | the quoting is the reference language's |
| mode note "nothing dispatches into this", `dispatch from the document` | 10 | 10 | engine | needed, and the best-placed thing on screen |

### The right-hand panes and the messages

| Concept | Shown | Used | Words | Note |
|---|---|---|---|---|
| Input | 1 | 5 | author | |
| its empty text: "choose a sample on the crumb above" | 1 | — | — | **wrong**: the sample is chosen in the panel's Data row now (design 44 §5a); the crumb says so |
| Variables, "every name in scope at the selected frame, innermost first" | 1 | never | engine | rung 1 has captures but never looks at them here |
| Output, "what the selected frame wrote" | 1 | 27 | engine/author | |
| Messages, with an empty Severity/Message table | 1 | 27 | author | |
| marks (tint, rule, pointing) | 27 | never | editor | only on the last stage |

## 4. What it adds up to

**The table has 49 entries (some bundle a control with its note); 21 of them rung 1 never uses.**
Counted off the table's columns:

| Stage | Entries on screen | Of them, used by this stage |
|---|---|---|
| 1, the blank project | 26 | 0 |
| 6, the first template shown | 45 | 7 |
| 11, the first pattern typed | 47 | 13 |
| 20, `pair` finished | 47 | 17 |
| 27, built and run | 49 | 25 |

So the author meets almost everything by stage 6, and is still meeting things for the first
time through stage 27. Half of the template screen is for other rungs.

Four patterns account for most of the gap:

1. **Everything a template *can* have is shown on every template.** Guard, declarations,
   captures, the role and its help, the workbench link and the stepper are drawn whether or not
   anything is set or anything can be stepped. Folding the guard (design 44 §5p) was the first
   step the other way; the rest of the strip still lists capabilities rather than contents.
2. **The settings lead with the engine's knobs.** Seven of the eight fields are for tuning or
   compatibility, and rung 1 touches one of them.
3. **The engine's words reach the author before its ideas do.** *Frame* appears on stage 1, in
   three places, and nothing on screen ever says what one is; *body pos*, *by version*, *in scope*,
   *innermost first* and *the cursor* follow. The rung needs none of them.
4. **Two ways to do one thing.** `+ template` and the toolbar's Add; `+ instruction` and the body
   toolbar's Add. Each pair is one more thing to notice and to wonder whether it differs.

And three things found on the way that are simply wrong:

- the Input pane's empty text sends the author to the crumb for the sample, which is in the panel
  (Messages and the crumb already say so);
- the line entry stays open after the last line, so the finished project ends with an empty card
  being edited;
- the strip's role note is truncated at every stage, so the one line that says how a template is
  reached never reads in full.

## 5. Proposed cuts, for ruling

Each moves a concept to the point the author needs it; none removes a capability.

| | Proposal | Concepts it takes off a first screen |
|---|---|---|
| C1 | **The strip shows what a template has, not what it could have.** Guard, Declarations and Captures appear as one quiet `+ guard · + declaration · + capture` line until one is set, as the guard already folds. | 3 headings |
| C2 | **The role picker appears once there are two templates in a mode**, which is the first time *advance only* can matter; until then *a record* is said by nothing. | 1 field and 4 lines of help |
| C3 | **Settings: name, encoding and ignore errors, then "Advanced"** folded, holding version, dispatch, buffer size, max sequence entries and strict values. *Revised (§5a): ignore errors stays out.* | 5 fields |
| C4 | **No `mode: default` heading while there is only one mode.** | 1 |
| C5 | **The stepper, the heat bars, the row counts and the card notes appear after the first run**, as the marks already do. | 4 |
| C6 | **Variables appears when the cursor has something in scope**, folded otherwise. | 1 pane |
| C7 | **No *frame* in anything a new author reads.** The settings header, the Input, Variables and Output empty texts, and the role help say *match* or *this template's match* instead. *Frame* stays where the author is plainly stepping through matches: the crumb. | 1 word, 5 places |
| C8 | **One way to add a template.** The panel toolbar's Add does what the selected mode's `+ template` does; drop one. *Revised (§5a): the body's two adds are not duplicates and both stay.* | 1 control |
| C9 | **Patterns appears once the library has something in it, or the author asks for it** (the chain-link button, kept). | 1 heading |
| C10 | **Fix the three found wrong**: the Input's empty text, the line entry left open, and the role note's truncation (show it whole on a second line, or drop *body pos* and keep the name). | — |

Done together, C1–C9 take the blank project from 26 entries to 15, and the first template stage
from 45 to 26, counted off §3, without removing anything the later rungs use.

### 5a. What a cut may cost — checked 2026-11-23

A cut must not buy a quieter screen with clicks. A concept hidden until the author needs it costs
nothing if it **appears by itself** when the project reaches the state that needs it (C2, C4, C5,
C6, C9); it costs a click and a search if the author has to **go and open** it (C3); and it costs
the most if what is removed was not a duplicate after all (C8). So each cut was checked against
the 61 engine fixture projects, which stand for what authors build:

| Cut | Reveal | What the fixtures say | Cost |
|---|---|---|---|
| C1 | a click on `+ guard` etc., as the `+ declaration` link is today | 36 declare, 21 bind captures, 2 guard | none: the same one click |
| C2 | by itself, at a mode's second template | no fixture has an *advance only* template alone in its mode | none |
| C3 | a click on Advanced | 29 of 61 set *ignore errors*; 15 set dispatch `lax`; 10 change the buffer size | as first proposed, half of all projects pay a click and a search, so ignore errors stays visible; the rest is paid by a quarter |
| C4 | by itself, at the second mode | 53 of 61 have more than one mode | none: it hides only at the very start |
| C8 | — | the body toolbar's Add inserts **below the selected card**; `+ instruction` appends | dropping either adds moves; only the panel's two template adds are true duplicates |

The guard for the rest is the walkthroughs themselves, which count actions (design 46 §10): every
cut that is built is gated on no rung's count rising.

## 6. Rung 5, against the cuts — inventoried 2026-11-23

Rung 5 (`text_019_single_line_split`, `ShapeshifterSplitStories`, 18 stages) was run with
screenshots to test the cuts on the rung least like rung 1. Its new ideas are names with a
lifetime and nested bodies: a list declared on the document and filled by a capture in the header
row, a `variable` card writing into a scalar, an `if … exists` around the record, delimiter
matches, a limit of one match, three modes besides the default, and `${heading[matchCount()]}`.

### What rung 5 adds to the budget

| Concept | Stage | Words | How it is reached |
|---|---|---|---|
| a declaration, and its type (list, scalar) | 4 | engine | `+ declaration`, a dialog: Name, Type |
| delimiter match: *split on*, quoted values | 7 | author | the match line's kind, then its fields |
| a limit, *Match at most*, shown as `· first only` | 8 | author | the workbench, its fold opened, a number typed, the workbench closed |
| dispatching a group (`select $1`) into a mode | 9 | XSLT + engine | an `apply-templates` card, its Select and Mode |
| a capture binding a group into a list, *Binds into*, *Group* | 11 | engine | `+ capture`, a dialog |
| the `variable` card, which writes its body into a name | 14 | XSLT | the card's kind |
| declaring a name where it is first used, *declare … here* | 14 | editor | an offer on the card |
| `if`, its clause *Name* · *Operator* · `exists`, its `then` list | 15 | XSLT | the card's kind, then a clause row |
| nested bodies, each ending in its own `+ instruction` | 14 | editor | three links on screen at once by stage 15 |
| `${…}` in a line, and `heading[matchCount()]` | 17 | engine | typed; offered under a Select naming a list (Q14) |

Ten concepts on top of rung 1's fourteen, and every one is needed at the stage it appears. Rung 5
is not where the load comes from; it comes from what rung 1 shows and rung 5 does not use either
(the stepper, the heat bars, the card colours, the Patterns section, the role help).

### The cuts, checked against it

| Cut | What rung 5 does | Verdict |
|---|---|---|
| C1, the strip's empty sections as one `+` line | declares on the document (stage 4) and binds a capture (stage 11), each through the `+` link it has today; the second declaration is made from the card that names it | holds, no cost |
| C1, the guard | the limit is set in the **workbench**: open it, unfold *Guard and limits*, type, close — four actions for one number. The strip's `no guard · no limits` already opens the workbench on a click, but with the section still folded | **revise**: the strip's guard line opens the workbench *with its guard and limits unfolded*. One action fewer, and the line becomes the way in, which it should have been |
| C2, the role picker at a mode's second template | every template is *a record*; `root` gets its second template at stage 12 and the picker appears unused | holds, no cost |
| C3, Settings' Advanced fold | only the name is set | holds |
| C4, no `mode: default` while there is one mode | `root` arrives at stage 5, so the heading arrives with it. Once there are modes, `mode: default` heads the document row alone for the rest of the rung | **extend**: the document row never sits under a mode heading. It is dispatched from nowhere, so its mode answers no question; `mode: default` should head templates in the default mode only |
| C5, run results after a run | never steps, never reads a heat bar; the row's `· first only` is used, and is the only thing that tells the two `|` templates apart (design 47 §7.3) | **narrow**: C5 hides run *results* (counts, `not tried`, heat bars, card notes, the stepper); a row's limit note is the template's *definition* and stays |
| C6, Variables when something is in scope | declares two names, but the canned trace binds none, so the pane says *No name is in scope* even after the run | holds; untested against a live run |
| C7, no *frame* | the role help says *opens a frame to step* on every template | holds |
| C8, one way to add | the toolbar's Add is never used; `+ instruction` is used eight times, three of them in nested lists | holds as revised: only the panel's template Adds are duplicates |
| C9, Patterns when the library has something | unused | holds |

### What rung 5 finds that rung 1 could not

- **Nested `+ instruction` links are told apart only by indentation.** At stage 15 there are
  three: the variable's list, the `if`'s, and the body's. The story finds them by position
  ("the first of the two that end with `+ instruction`"), and an author has to do the same by
  eye. Each could say whose it is: `+ in variable`, `+ in then`, `+ instruction`.
- **The strip scrolls the match line off the top** once a body nests (stage 15): the template's
  name stays, its match does not. That's a consequence of the strip being one scroll, and worth
  a look when C1 makes the strip shorter.
- **Order is still named only in a tooltip.** `header_record` before `data_record` in `root` is
  what makes the header the header, as `pair` before `junk_line` is in rung 1. Twice now the one
  fact that silently changes the output is the one the screen does not say.

## 7. Rulings — 2026-11-23

| | Ruling |
|---|---|
| C1 | **Yes. Built 2026-11-23.** Empty sections are one line, `+ guard or limit · + declaration · + capture`: the guard's link is named for the limit too, which is what rung 5 sets, and it and the guard's own line open the workbench with them unfolded. Read only, an empty section is not shown. The clause list's `+ clause` gained the space it lacked, found once the shorter template brought it on screen. |
| C2 | **Yes. Built 2026-11-23.** The role picker shows once another template shares the mode, or the template is already advance only. |
| C3 | **Yes, amended by the gate. Built 2026-11-23.** Settings shows Project name, Encoding, Ignore errors and Buffer size; Version, Dispatch, Max sequence entries and Strict values fold under Advanced, which opens by itself when the dispatch or strict values are set. Buffer size was to fold too, and the gate refused it: rung 6 sets it, and opening Advanced cost that rung a click. |
| C4 | **Yes. Built 2026-11-23.** The document row sits under no heading; `mode: default` heads templates in the default mode only beside a named mode, or where the heading has a note to say (§7, "Found in review"; ruled to stay, 2026-11-23). |
| C5 | **Yes, narrowed, with another trigger. Built 2026-11-23.** A template's results — its row's count and heat bar, and the heading's run note — show once the run has tried it. `not tried` shows only where it was passed over: the run tried another template of its mode. Otherwise it reads as before any run, by its kind. A row's limit note stays. See "C5's trigger" below. |
| C6 | **Yes. Built 2026-11-23.** The Variables pane folds away while no name is in scope at the cursor and opens when one is, unless the user has folded or opened it, which is kept. |
| C7 | **Yes. Built 2026-11-23.** The role help, the settings header, the Output and Variables empty texts, the sample's tip and the variables' section links say *match*. *Frame* stays in the crumb, and in what only a stepping author reads (the guard's verdict counts). |
| C8 | **No.** Both template Adds stay. |
| C9 | **Yes. Built 2026-11-23.** No Patterns section until the library has a part. |
| C10 | **Yes, first. Built 2026-11-23.** The Input's empty text points at the panel's Data row, and no longer says a pasted sample is not kept. A card of a kind chosen from the list is taken without opening an empty line after it, which a typed line still does (design 47 A). A dispatch site is named by its template alone, its body position only where one template dispatches into the mode twice. |
| Nested adds | **Yes. Built 2026-11-23.** A nested list's link says which list: `+ in then`, `+ in when`, `+ in otherwise`, `+ in case x`, `+ in default`, and for a holder of one list its kind, `+ in variable`, `+ in for-each`. The body's own stays `+ instruction`. |
| Order | **Yes, for strict modes. Built 2026-11-23.** A strict mode's heading says "tried top to bottom: the first to match takes it" once it has two templates. Only strict dispatch takes the first to match; a lexer or lax mode's heading says how it is dispatched instead, as it did. |

**How they are built**: C10 first, then each cut as its own change. Every rung's walkthrough is
re-run after each, and a cut lands only if no rung's action count rises (§5a). Screenshots after
each group.

### The gate

Actions are counted mechanically off the step log of each rung's last stage (`results.json`): every
`userEvent` click, double click, typed value, key press and option picked; hovers, clears, waits
and expectations are not actions. The method is design 46 §10's, so the numbers are comparable
with each other but not with its hand counts.

| Walkthrough | Baseline, 2026-11-23 | After the cuts |
|---|---|---|
| `ShapeshifterAuthoring` (rung 1) | 44 | 44 |
| `ShapeshifterLexer` (rung 2) | 39 | 39 |
| `ShapeshifterClassify` (rung 3) | 35 | 35 |
| `ShapeshifterLibrary` (rung 4) | 34 | 34 |
| `ShapeshifterSplit` (rung 5) | 102 | 98 after C10, four Escapes that closed the line it no longer opens; 97 after C1, the limit opened from its link |
| `ShapeshifterLenRecords` (rung 6) | 39 | 39 |

### Found while building: C5's trigger

C5 says run results appear *after the first run*. But the editor runs the project on every edit
once there is a sample, so from rung 1's stage 5 there is always a run, and "after the first run"
hides nothing. C5 needs another trigger before it is built: for instance, a template's results
appear once *it* has been tried, so that `not tried` is shown only for a template the run could
have reached and did not.

**Ruled 2026-11-23: tried, or passed over**, over *once tried, nothing before*, which would make a
template shadowed by the one above it look the same as one the run never got near.
`Profile.tried` and `Profile.passedOver` say which, and the panel's row and the heading both ask
them (`ProfileTest`, `OnlyATemplatePassedOverIsNotTried`).

- **The heading's run note is short.** `3 attempts · 2 matched (67%) · …` was cut short after its
  first number; it is `matched 2/3`, `skipped 1/1` for an advance-only template, or `not
  tried`, and its tooltip has every number. It is whole at rung 1's size; a long name, mode or
  count can still cut its end, which is the note giving way as it was made to.
- **The document is never `not tried`.** It is run and not tried, so its heading and its row's
  tooltip said so wrongly; the heading says nothing, and the tooltip gives the run's total.
- **Card notes needed nothing.** A card has one only where the run reached it.
- **The stepper stays as it was**, shown after any run with its buttons dead where nothing
  matched. That is "after the first run" to the letter, and hiding it was decided against when it
  was built: a control that comes and goes reads as one that is not there (`BreadcrumbController`).

### Measured: the match line scrolling off (§6) — 2026-11-23

At 1280×720, with C1 built. Rung 5's stage 14 fits whole. Stage 15 does not, by 22px, and the top
half of the match line goes under the name. The empty Messages pane below takes about 110px of the
same column. **Ruled and built 2026-11-23: Messages folds to its title bar while there are none**,
as Variables does (C6), and opens when one arrives, unless the author has folded or opened it
(`MessagesAreFoldedWhileThereAreNone` and the three after it). That is about 80px, where pinning
the match line would have taken about 40px from every body. A body taller still scrolls.

### Asked for while building — 2026-11-23

- **A card's OK and Cancel are a dialog's.** They were two links at the end of the card's row of
  fields; they are Stroom's own buttons now, OK in the primary colour with its tick and Cancel in
  the secondary with its cross, on a line of their own under the fields at the right, as every
  dialog has them (`DialogFrame`). Enter and Escape are unchanged.
- **The keyboard stays on an add link across a redraw.** Escape from a new card puts it on the
  list's `+ instruction`; the run the previous edit started could land a moment later, redraw the
  cards and take it away. A redraw already kept it in an open card or on a row, and now keeps it
  on a link. Found because the card toolbar's story failed only when the box was busy enough for
  the run to land late.

### Found in review — 2026-11-23

A review of the cuts found that four of them hid more, or less, than was ruled:

- **C2 never applied to the default mode.** The document's mode is also null, so it counted as
  another template in the default mode. It is entered and not dispatched, and is now not counted.
- **C4 took a note with its heading.** The heading draws the mode's note, so a project of one mode
  said neither how it was dispatched nor the order. The default mode keeps its heading where there
  is a note.
- **The order was claimed of dispatch sites that disagree.** The absence of a dispatch note was
  read as strict, and it also means sites that differ. The order is now said only of strict.
- **C3's Advanced would not stay folded.** A set dispatch or strict values opened it on every
  refresh, which is every edit. It is opened only until the author has folded or opened it.

And two smaller: `+ guard or limit` opened the guard pane for every template shown after it, not
only for the one it was pressed on (C1); and the add link, hidden as its section appeared, took the
keyboard with it. The keyboard now goes to the section's own add link, and back when the last row
is removed. `ShapeshifterConceptStories` holds a story for each, seen to fail without its fix.

## 8. What this cannot tell us

This is a count by someone who knows what every word means. It finds what is on screen
before it is needed; it does not find what a newcomer misreads, which is the larger part of
cognitive load. The next step after ruling on §5 is design 46's rung 1 with a DS3 author
thinking aloud — half an hour, recorded, with this table as the script: for each concept, could
they say what it was for when they first saw it?
