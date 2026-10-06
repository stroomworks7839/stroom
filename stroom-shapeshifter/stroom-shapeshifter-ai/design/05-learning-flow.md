# The learning flow: the plan drawn and edited as the graph it is

*Drafted and ruled 2026-10-06. The ten questions of §6 were put with recommendations and every
recommendation was taken; the rulings are A81–A90 in §9, and the owner ruled that phase H begins at
once rather than after phase 6's exit walkthrough.*

A mock-up accompanies this draft: the three example plans drawn vertically as a flowchart, with a
step's form and its question text opening beside the chart, validation painted on the step it names,
the text form kept beneath, and a recorded attempt's path drawn over the flow.

## 1. Why

A37 made the plan a graph: a step names its checks and where it goes on each outcome, `goto` may name
a step before or after, and `end` is a destination. The three examples use all of that. The escalating
example has two parsers, two transforms, a back edge to a named step and an early exit to `end`.

The screen shows a table. Design 01 §12 item 24 asked for "the steps as a list or tree with the
transitions drawn between them", problems "inline against the step it names" and "the last attempts'
paths drawn over the graph". What was built on 2026-09-24 is the list: a grid with one row per step, a
"Goes to" column that renders transitions as text, a modal dialog per step with a second grid and a
second modal for its transitions, one shared text area for the eleven question texts beneath, and one
label under the grid that joins every problem with semicolons. The graph is in the data and in the
validator and nowhere on the screen. The path over the graph was never built.

Three defects were found in surveying it, which stand whatever is decided here:

- The transition dialog cannot select `on spent`. The outcome picker is `StepOutcome.values()` and
  spent is stored as a null outcome, so the one transition every example depends on can only arrive by
  loading an example or by import.
- The Learning tab's help and the step kind's error message still say four kinds and "Configure last".
  `ADMIT` has been a kind since phase 5 and may be last.
- Design 01 §10.2's constraints predate `ADMIT` too, and name the guard as `always|text|xml`.

## 2. The vocabulary of a flowchart, and what each is in the model

The owner asked for a traditional flowchart, vertical, with a start, steps, decisions, connectors and
exits. Every one of those is already in the model; the drawing only has to name them.

| On the chart | In the model | Notes |
|---|---|---|
| **Start** | The session opening for a shape no rule binds, the sample in hand | Not a step. Its panel is the natural home of the system text, which every question is asked under, and of the three rules texts the questions include. |
| **Step** (rectangle) | A `PlanStep`: kind, role, guard, candidates, record kinds, checks | Titled by kind and role, badged with its candidates, subtitled with its checks. Its id, for `goto`, shown when given. |
| **Decision** (diamond) | The judging of a candidate: a `StepOutcome` | One diamond per step. Its exits are the outcomes. |
| **Connector** | A `Transition`: `on <outcome> goto <id>`, `on spent goto <id>`, `on … abandon` | Drawn solid where the flow declares it, dashed where it is a default the flow need not write. |
| **End** | `goto end`, or a pass of the last step | The fragment is bound as the rule, the corpus is written, the stream and every stream that waited are served. |
| **Exit P** | `abandon`, or spent candidates with no transition, on any step but a parser | The session parks for a person (A65): grant more, answer a turn, send to the exhaustion step. |
| **Exit G** | `abandon`, or spent candidates with no transition, on a `CONFIGURE parser` step | The shape is given up and its streams errored (A52, A54): a parse that cannot be learned is the one failure nothing can serve. |

The defaults matter to the drawing because a flowchart shows every edge and the grammar writes only
the ones that differ from the default. The defaults are: a pass goes down to the next step, or to End
from the last; a shortfall, refusal or failure re-asks the same step with its next candidate; spent
candidates abandon, which is P or G as the table says. The drawing shows these dashed, so that a reader
can see where a session goes without knowing the grammar, and so that a declared transition is visibly
a departure from them. Two global rules are stated once rather than drawn from every step: the budget
spent at any step goes to P (A5, A65), and a transition is taken at most once per attempt (A37).

## 3. The screen

**Its own tab.** The flow is the heart of the document and the Learning tab is already a long form:
model, key, threshold, allowed elements, instructions, the plan, the question text, five budgets. The
flow moves to a tab of its own. The Learning tab keeps the model, the key, the allowed elements, the
instructions and the budgets. See Q2 for where the tab sits.

**A spine, not a canvas.** A plan has four to eight steps and its order carries meaning, since a pass
falls through to the next line. The steps are laid down a vertical spine in their order, each followed
by its decision; declared transitions leave the decision sideways and run in lanes, back edges on the
left and forward jumps on the right, to the step or End they name; the default pass runs straight down;
the default re-ask is a short loop on the left into the step above; the default exit is a stub on the
right to a P or G connector symbol. End sits at the foot of the spine. Positions are derived from the
order and never stored, so there is nothing to save, nothing to drift and nothing to lay out by hand.
Reordering is a move along the spine. A drag-anywhere canvas is not proposed: it invites layouts that
hide the fall-through order, it needs positions on the document, and it is a much larger GWT job for
no gain at this size.

**Editing in the flow.** Selecting a step opens it in a panel beside the chart, not in a modal: kind,
role, guard, id, candidates, record kinds, and the checks as the closed list, exactly the form that
exists today. Its transitions are listed in the panel with the defaults shown greyed among them, and a
new one is "on this outcome, go to", the outcome from the closed list with spent in it, the target from
the plan's own steps plus `end` and the exits. Selecting a declared edge on the chart selects its
transition in the panel, where it can be deleted. Add inserts a step after the selected one; delete
confirms where a `goto` names the step, as the grid does today. The examples load as before.

**Question text in context.** Each kind of step maps to one question template: Chain, Split (whose XML
and JSON variants the guard chooses), Target, Configuration, Admission. The step's panel shows that
template's effective text beneath the form, editable, with the built-in text a reset away and the
built-in version shown, as the Learning tab does now for one template at a time. The system text and the
three rules texts belong to no step; they open from Start. Templates are per kind under A33, so an edit
on one Configure step is asked at every Configure step, parser and transform alike, and the panel says
so where it matters. Whether that stays is Q5.

**Problems where they are.** `LearningPlan.problems()` is a list of strings today. It becomes a list
of problems each naming the step it is about, where it is about one; the server keeps joining them for
its refusal message. The chart paints the named step and the panel lists that step's problems at its
top; problems about the whole flow, such as a missing Chain, show above the text form.

**The text form stays, two-way.** One line per step in the grammar of §10.2 is what import, export,
the harness and the design documents carry, and it is the fastest way to read a plan at a glance or to
paste one in. It stays beneath the chart, editable, parsed on change: a line that does not parse is
reported and the chart keeps its last good state. The grammar is the data's own and the two views are
one JSON.

**The path a session took.** Every recorded turn names its step, its candidate and its outcome (A28).
The Supervisor's turns pane gains the document's flow drawn read-only with the attempt's path over it:
the steps entered, numbered in order of visiting, the edges taken highlighted. The same widget, the
same layout, no editing. This is item 24's third clause, and it is the strongest reason to draw the
graph at all: a person reading why a session parked sees where it went.

## 4. Rendering

Stroom's client has no graph library and should not gain one for this. The pipeline structure editor
is the nearest existing widget: HTML boxes over a layered canvas with bezier connectors, laid out by a
tree layout that assumes one parent per node and no labels on edges. A plan has back edges, forward
jumps and labelled edges, so its layout cannot be reused, though its box and connector pieces could be.

The recommendation is inline SVG in a GWT widget, drawn from the plan with the spine layout above. SVG
gives labels, hit testing and theme colours through CSS for nothing, the layout is a few hundred lines
with no library, and the mock-up is written exactly this way, so it is also the layout's proof. The
pipeline editor's canvas approach was built for dragging a subtree, which this does not do.

## 5. What changes in the model

- **The name.** Ruled, A81. `LearningPlan` is `LearningFlow`, `PlanStep` is `FlowStep`, `PlanExample`
  is `FlowExample`, and the document's field is `flow`. The store's serialiser reads `plan` as it
  already read `dialogue`, the name before A37, and writes `flow`; the shared class cannot carry an
  old key, since its JSON codec is generated for the client too, and a creator parameter without a
  property breaks that codec. An old build reading a new document loses the flow rather than failing,
  since the store's mapper ignores a key it does not know; the deployment note says so. *Built
  2026-10-06, with the exits, the structured check, `on spent` in the transition dialog, and the help
  text and §10.2 brought up to `ADMIT`: slice 1. Its own audit, the same day, read every line of prose
  the rename rewrote and found it sound; restored one golden event fixture the rename had touched; gave
  the live harness the old variable name beside the new; made a step that leaves on its pass by
  `abandon` take its default exit by role, as its spent candidates do; said in A83 that an exit is
  where a session goes when nothing serves the shape; and gave scenario 63 its row in design 02.*
- **Exits named by their effect.** See Q3. If ruled, `abandon` is joined by `park` and `give-up` as
  transition targets, `abandon` reads as whichever the step defaults to, and the stage obeys the flow
  rather than inferring from the step's role. The mock-up's P and G are these.
- **Structured problems.** `LearningPlan.problems()` returns problems with an optional step index. The
  string form is derived.
- **Question text per step.** See Q5. If ruled, a step gains an optional template override of its own,
  the kind's text as the fallback, and `Conversation` resolves the step's text before the kind's.

## 6. Questions for ruling, with recommendations

**Q1. Rename plan to flow?** A plan says sequence; a flow says graph, which is what A37 made it. Two
cautions: design 04 is "the processing flow" and a Stroom pipeline is already a flow of data, so prose
should say *learning flow* where the two could be confused; and the JSON key changes on the document.
*Recommended: yes, now, before there is an installed base; "Learning flow" on the screen and in prose,
`LearningFlow` in code, `flow` on the document with `plan` still read.*

**Q2. Which tab, and where?** A tab named Flow. It could sit second, after Settings, as the thing a
person most edits; or third, after Learning, since the flow is asked under the Learning tab's model
and instructions. *Recommended: Settings, Learning, Flow, Scoring, Promotion, Routing, Documentation,
Permissions: a person reads the model and the instructions before the questions asked of it. If the
owner wants the flow first, the only cost is the order.*

**Q3. Name the exits by their effect?** Today the grammar has one exit word, `abandon`, and what it
does depends on the step: a parser's spent candidates give the shape up and error its streams; any
other step parks for a person. The chart draws P and G, so the chart would be saying something the
text does not. *Recommended: three exits, `end`, `park` and `give-up`, with `abandon` accepted on read
as the step's default, so that a flow says what it does and a person may choose: a transform whose
candidates are spent may `give-up` if the owner of a feed would rather error than wait, and a parser's
may `park` if a person is expected to answer.* This moves one rule from code to the flow, which A37
allows: a transition may only name a destination the code already has.

**Q4. Draw the defaults?** A flowchart shows every edge; the grammar writes only departures from the
default. *Recommended: draw the defaults, dashed and labelled, and let a declared transition replace
the default it overrides. A reader who does not know the grammar can follow the chart, and a declared
edge is visibly a decision.*

**Q5. Question text per kind, or per step?** A33 made overrides per template, and a template is per
kind of question. Shown in the step's panel, an edit at one Configure step applies to the other. The
escalating example has two parsers and two transforms, and no plan yet has wanted different words for
them. *Recommended: keep per kind, show the text in the step's panel with a plain note at Configure
and Admit, and rule per-step overrides only when a flow needs two Configure steps asked differently.
The model change is small whenever that comes.*

**Q6. Where do the shared texts live?** The system text, the split rules, the extraction rules and the
transformation rules belong to no step. *Recommended: the Start node's panel. Start is the session
opening, the system text is what every question is asked under, and the rules are what the questions
include; a person looking for "the words the model is told first" clicks the first thing on the chart.*

**Q7. Keep the text form, and make it editable?** It is read-only today. *Recommended: keep it beneath
the chart and make it two-way. It is the import and export form and the design documents' form, and a
plan pasted from a scenario should draw itself.*

**Q8. Where is the attempt's path drawn?** On the Flow tab, choosing among the document's attempts, or
in the Supervisor beside the turns of the attempt being read. *Recommended: the Supervisor, read-only,
with the same widget; the Flow tab draws the flow as it is and does not need the attempts table. A
"last attempts" overlay on the Flow tab can follow if it is wanted.*

**Q9. Decision diamonds, or outcomes from the step box?** The outcomes could leave the step box
directly, which is shorter. The owner asked for decisions. *Recommended: a diamond per step. It is
what a reader of flowcharts expects, it gives the re-ask loop and the exits a place to leave from that
is not the step, and it makes the judging visible as an act of its own, which it is.*

**Q10. Rendering: SVG, or the pipeline editor's canvas and boxes?** *Recommended: SVG, as §4 says.*

## 7. Slices, if ruled

1. **The model and the defects.** The rename with `plan` still read; exits by effect; structured
   problems; `on spent` selectable; the help text and §10.2 brought up to `ADMIT`. The grid keeps
   working over the renamed model, so nothing is lost while the chart is built.
2. **The chart, read-only.** The Flow tab with the spine drawing of the document's flow, problems
   painted, the text form beneath, and the Supervisor's path overlay. The grid stays on the tab beside
   it until the next slice. *Built 2026-10-06.* `FlowChart` draws the flow as inline SVG from the steps
   alone, with the spine layout of §3 and the mock-up; the Flow tab holds it above the table of steps,
   a step clicked on either selected on both, the text form beneath two-way with a line that does not
   parse reported and nothing changed (A87), and the question text moved from the Learning tab, whose
   help says an edit to the configuration text is asked at every Configure step (A85). The flow's
   check paints the step it names and the step's title says which. The Supervisor's turns pane has the
   document's flow drawn beside it with the attempt's path over it (A88): `FlowPath`, in the shared
   model with a test, says which edge each turn's judging took — `passed`, the outcome's own name where
   the step declares a transition on it, `reask` where the next turn is the same step, `spent` otherwise
   — and the chart highlights those edges and numbers the visits. The Learning tab keeps the model, the
   key, the allowed elements, the instructions and the budgets. *Its own audit, the same day:* the
   chart was drawn at a fixed width and the Supervisor's pane is narrower, so it now scales to its
   pane; two exits leaving one decision were drawn on the same spot, so each takes the next place
   down; and design 01's item 24 records what of it is now built. Noted and left: a step of several
   units — a Configure over the whole chain, a Target over several kinds — records a turn per unit, and
   a unit's pass highlights the step's `passed` edge before the step as a whole has passed; and the
   text form is parsed when it loses focus, not on every keystroke.
3. **Editing in the flow.** The side panel with the step's form, transitions and question text; edge
   selection and deletion; add, delete, move; the examples. The grid and the Learning tab's plan and
   template sections go.
4. **Later, if wanted.** Per-step question text (Q5); a last-attempts overlay on the Flow tab; the
   path drawn live as a session runs.

Not in scope at any slice: a free canvas with stored positions; expressions, counters or values on
transitions, which A37 excludes; a plan that is not a graph over the typed kinds and outcomes.

## 8. Phasing

Phase H in design 03, begun 2026-10-06 at the owner's direction, before phase 6's exit walkthrough.
Its first slice changes a stored field, so it goes first.

## 9. Rulings this document records

**A81.** *The plan is the learning flow.* "Learning flow" on the screen and in prose, `LearningFlow`,
`FlowStep` and `FlowExample` in code, `flow` on the document; a document written as `plan` is read
and never written so again. Where "flow" could be confused with the processing flow of design 04 or a
pipeline's flow of data, prose says *learning flow*.

**A82.** *The flow has a tab of its own, named Flow, after Learning.* The Learning tab keeps the model,
the key, the allowed elements, the instructions and the budgets.

**A83.** *The exits are named by their effect: `end`, `park` and `give-up`.* `abandon` is accepted on
read as the step's own default, give-up for a parser (A52, A54) and park for any other step (A65), and
the stage obeys the flow rather than inferring from the step's role: a transform may `give-up` where a
feed's owner would rather error than wait, and a parser may `park` where a person is expected to answer.
No step may be named `end`, `park` or `give-up`. An exit is where a session goes when nothing serves
the shape: a relearning under an incumbent that leaves the flow parks or keeps the incumbent as it
did, whatever exit is named, since giving up a shape a rule is serving would error streams that are
being served.

**A84.** *The defaults are drawn, dashed and labelled:* a pass to the next step, a shortfall re-asking
the step, spent candidates to the step's exit. A declared transition replaces the default it overrides.

**A85.** *Question text stays per kind of question (A33)*, shown in the step's panel with a plain note
at Configure and Admit that an edit is asked at every step of that kind. Per-step text is ruled only
when a flow needs two Configure steps asked differently.

**A86.** *The shared texts open from the Start node:* the system text and the split, extraction and
transformation rules.

**A87.** *The text form stays beneath the chart and is two-way:* one line per step in the grammar of
design 01 §10.2, parsed on change; a line that does not parse is reported and the chart keeps its last
good state.

**A88.** *A recorded attempt's path is drawn in the Supervisor beside its turns*, with the same widget
read-only: the steps entered numbered in order, the edges taken highlighted. The flow's check names the
step each problem is about, so an editor can paint it.

**A89.** *A decision diamond follows each step.* The outcomes leave the diamond: the re-ask loop and
the exits have a place to leave from that is not the step, and the judging is visible as an act.

**A90.** *The chart is inline SVG drawn by a GWT widget with the spine layout of §3, no library.* The
mock-up is written the same way and is the layout's proof.
