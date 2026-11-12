# Design 46 — Authoring the samples: the walkthroughs

*Proposed 2026-11-12, from the owner's ask: "a walk through of a user creating each of our
sample Shapeshifter configurations for parsing and translating data via the UI from scratch
taking it step by step … written as assumed steps in this design and then later written as
playwright steps in the workbench. The objective would be to ensure that the UI makes it both
possible to create the configuration and to ensure that the steps are logical from a user
perspective, e.g. starting by matching a record, then extracting fields etc."*

Every other test the project has asks whether the engine is right. None asks whether the
configuration the engine runs could have been **built** by a person sitting in front of the
editor. The fixtures were all written as JSON by hand or migrated from DS3; not one of them has
ever been assembled through the UI. This design is the walkthrough that asks that question, and
the shape of the test it later becomes.

## 1. What a walkthrough answers that nothing else does

Three different questions, and it is worth keeping them apart:

1. **Is it possible?** Can each feature the sample uses be reached from the editor at all, or
   does some part of it exist only in the wire format?
2. **Is the order logical?** The author's order is *match a record, then extract its fields*.
   The document's order is a list of templates, each with a match, a role, a guard and a body.
   Those are not the same order, and nothing has ever checked that the UI lets the author work
   in theirs.
3. **What does it cost?** Not whether a thing is possible, but how many clicks, dialogs and
   returns to a different pane it takes. This is the question that only a step-by-step
   walkthrough surfaces, because it is invisible in any single screen.

Question 1 can be answered by reading code. Questions 2 and 3 cannot — they need the steps
written out in order, which is why the design half of this is worth doing even if the
Playwright half never happens.

## 2. The division of labour: authorability here, correctness elsewhere

A story cannot judge the engine's output, and must not pretend to.

Every REST reply in a workbench story is a canned fixture — `RestReply.json(TRACE)`,
`RestReply.json(VALIDATION)` in `ShapeshifterEditorStories`. `RestReply` is a pure response: a
status, a body, a content type, and no handler that could see the request. So the preview pane
shows the same trace **whatever the author has built**. A walkthrough story that asserted
"and now the output is right" would be asserting a constant against itself.

So:

- the **walkthrough** owns *authorability* — that every step is reachable, that the controls
  exist and are enabled when the author needs them, and that what the UI assembles is the
  intended configuration;
- the **engine fixtures** own *correctness* — they already do, and they run the real engine;
- nothing owns both, and no story should grow an assertion about output.

## 3. The keystone: compare what the UI built, not what it displays

The weak form of this test is a long click-script ending in a pile of `toBeVisible()`. It is
brittle, it is slow, and it proves almost nothing: every assertion is about the editor's own
rendering of state the test itself just typed in.

The strong form is available because the harness has a **request spy that records each request
with its body** (`RecordedRequest`, already parsed and asserted on in
`ExecutionSchedulesScreenStories.java:415`). So each walkthrough can end:

> drive the UI from an empty project → save → capture the save request's body → compare it
> with the sample's own `project.json`.

One assertion, and the expected value is **owned by the engine fixture** rather than copied
into the story. The walkthrough then cannot drift from the engine: change the fixture and the
walkthrough fails for the right reason.

### 3.1 The comparison must be semantic, not textual

Three traps, each of which would break a byte-for-byte comparison, all found while reading the
fixtures:

- **Legacy spellings.** `"Store"` is the corpus's older spelling of `"capture"`, read for ever
  and never written (`ReferenceJson.java:163`). It appears in fifteen project fixtures,
  `win_sec`, `win_app`, `ausearch`, `json_to_xml`, `identity_transform` and `xml_to_json` among
  them. The UI will write `capture`. A textual comparison fails on every one of those samples
  for no reason at all. **Removed in §3.3** — but it is what first forced the comparison to be
  semantic.
- **Explicit defaults.** `xml_to_json` writes `"advance": 0`, `"var_id": null`,
  `"match_index": null`, and `flags` with both members spelt out. Whether the UI writes a
  default it was never asked to change is an open choice, not a defect.
- **Version drift.** The fixtures carry `version` 1, 3, 4 and 5 — whatever was current when
  each was written. A new project built today gets today's version, and the version is not
  cosmetic: it picks the default dispatch (§3.3).
A fourth was claimed here on 2026-11-12 and **withdrawn the same day**: that a blank `Select` on
an `apply-templates` card builds a null ref where the fixtures spell `{"capture": {"group": 0}}`.
It does build a null — and `ApplyDirective`'s own constructor turns it into exactly that ref,
*"defaulted here rather than at any of the readers so there is one answer to what leaving it out
means"*. Blank and `$0` are the same model, provably: writing the model the editor builds for a
new document produces the fixtures' spelling verbatim. The lesson is the one §3 is built on —
**check what the model does with a value before claiming the writers disagree.**

So the comparison parses both sides with `ProjectJson` and compares the **models**. `OutputNode`
and friends are records, so equality is structural and free.

### 3.2 Strict only — ruled 2026-11-12

**The walkthroughs author `strict`, at the current version, and nothing else.** Lax is DS3's
search-and-skip, and it exists for migrated DS3 configurations; Shapeshifter has never been
released, so there is no old Shapeshifter-only configuration to migrate or support. A
walkthrough teaching an author to build in lax would be teaching the compatibility path as
though it were the language.

`Dispatch.effective` defaults to `STRICT` from version 4 and `LAX` before — "which is how
'migration marks migrated configurations lax' is spelt without editing them"
(`Dispatch`). That rule stays, because migrated DS3 configurations still need it. What
changes is which fixtures are entitled to rely on it.

**Half the ladder is not strict today**, and across the whole corpus it is most of it:

| Rung | Sample | Version | Default dispatch |
|---|---|---|---|
| 1 | `strict_kv` | 4 | **strict** |
| 2 | `lexer_tokens` | 4 | **strict** |
| 3 | `classify_alerts` | 4 | **strict** |
| 4 | `library_parts` | 1 | lax |
| 5 | `text_019_single_line_split` | 3 | lax |
| 6 | `progressive_len_records` | 1 | lax |

Of the 34 fixtures in `projects/`, **23 sit on pre-version-4 versions** and so take lax —
7 at version 1 and 16 at version 3. That is not a decision anyone took; it is when each file
was written.

Only the DS3-derived families have a reason to be lax: `legacy/` (21 vendored DS3
configurations), `native/` (the 18 that reproduce them in the modern format, which the corpus
README calls "the migration's output"), and the `text_0*` forms of those. Everything else in
`projects/` is our own configuration that merely happens to be old.

One case is sharper than the rest. **`xml_to_json` is version 5 with `"dispatch": "lax"`
written explicitly** — a deliberate lax choice in a current-version configuration of ours.
Under this ruling that needs either a justification recorded or the line removed.

**Consequence for rungs 4 to 6 — measured 2026-11-12, and all three are strict.** The question
was whether each sample is strict-expressible at all, or whether it leans on search-and-skip.
Setting `source.dispatch` to `strict` explicitly isolates it: `version` gates only two things,
the dispatch default and version 5's substring starts (`Dispatch.effective`,
`ReferenceCheck.java:696`, `BodyCompiler.java:225`), so naming the dispatch tests the dispatch
and nothing else.

With `"dispatch": "strict"` added to `library_parts`, `text_019_single_line_split` and
`progressive_len_records`, **`GoldenFixtureTest` stays green — 75 tests, no failures**, each of
the three matching its golden byte for byte. A negative control confirms the field is live and
the result is not vacuous: `progressive_len_records` under `classify` fails with *"output:
expected 3 lines, got 1"*.

So none of the three leans on lax; all three take it only because of when they were written.
All six rungs stay on the ladder, and the three fixtures should be **re-baselined** — the
keystone comparison is then against a strict fixture, which is what the walkthrough will build.

The probe's edits were reverted and the suite re-run green; nothing in the corpus was left
changed.

The same probe was later run over the whole corpus rather than these three, and is recorded
where dispatch belongs — [11-strict-dispatch.md §10](11-strict-dispatch.md): 37 of the 45
fixtures that run lax only by version default are byte-identical under strict, 8 depend on it,
and the version-based default is itself the artefact worth a ruling.

### 3.3 The `Store` alias came out — done 2026-11-12

§3.1's first trap stops being a trap under the §3.2 ruling, because the alias stops being
wanted.

`"Store"` is **Shapeshifter's own earlier name for a capture**, not DS3's: the runtime class
went in design 35 phase 3 (commit `815caf7961`, "the run time changes once; Store is
deleted"), and the wire spelling survived only so that fixtures still saying `Store` kept
parsing. `Ds3Migration` emits `RefPart.Capture`; the migrator never produced the old word. No
production code writes it — only `ReferenceJson`'s reader and one reader test name it at all.

So it is an old Shapeshifter-only spelling, which is the one kind of compatibility this
project has ruled it does not owe. The scope of taking it out: **1,713 occurrences across 15
fixture files**, plus `e17/win_sec-original.project.json`, all in the oldest fixtures —
`win_app`, `win_sec`, `ausearch`, `json_to_xml`, `identity_transform`, `xml_to_json`.

The comment that kept it — *"read for ever, never written, because a thousand of the fixtures'
parts still use it and they are the measure"* — is the part that inverted. The fixtures are
ours to rewrite, so they were the thing to fix rather than the reason not to.

**How it was done, and why not as first written.** This section originally proposed parsing
each project and writing it back through `ProjectJson`. That was the wrong tool: a round-trip
reformats all sixteen files and may write defaults that were never in them, turning a change
anyone could review into one nobody can. Every one of the 1,993 occurrences is a key in the
position `"Store": {`, with none as a value, so the change is a key rename:
`"Store":` → `"capture":`. The result is **1,993 lines added and 1,993 deleted, every changed
line one of those keys** — a diff that reads as what it is.

Then `ReferenceJson`'s `case "capture", "Store" ->` became `case "capture" ->` with its
comment, and `ProjectReaderTest`'s one use of the old word was converted (its assertion still
earns its place: it covers a capture reference reading another template's variable rather than
this match's group, which is what that case was really testing).

**The gate.** All sixteen files parse; the engine module is green at **1,124 tests, 0
failures**, with every class that reads a changed fixture re-run from clean —
`GoldenFixtureTest` 75, `ProjectReaderTest` 55, `EngineBehaviourTest` 32 (it reads
`e17/win_sec-original.project.json`), `ConfigurationShapesTest` 11 — and the xmlbench module
green too, since the `challenger-*` projects are its corpus: `ChallengerParityTest` 2,
`CaseCatalogueTest` 17, `CaseAmplifierTest` 16, `CaseShapesTest` 3. `checkstyleMain` passes on
the config module.

Nothing in the repo now contains the word in a Shapeshifter configuration or its reader.

This also removes the only reason the keystone comparison had to tolerate two spellings for one
concept, though it stays a model comparison for the other two traps in §3.1. §3.4 is the
audit for the rest of its kind.

### 3.4 The rest of the audit — done 2026-11-12

Taking `Store` out raised the obvious question: what else survived the same way? The answer is
reachable mechanically, because of one property of the reader.

**The audit, and why it is exhaustive.** `JsonFields.checkFields` throws on any key it was not
told to expect, so a fixture *cannot* silently carry dead vocabulary — a stale key fails the
build. That leaves exactly one way for an artefact to hide: **a tag the reader accepts that no
writer ever produces**, which is what `Store` was. So the audit is a diff — every `case "…"` in
the five reader families against every tag any writer emits, counting enum labels
(`JsonFields.label` is `name().toLowerCase()`) and bare `JsonString` tags as written.

**What it found.** Two tags, both in `ConditionJson`: **`greater-than`** and **`less-than`**.
Like `Store` they are pre-release Shapeshifter spellings — `git log -S` puts them and `Store`'s
comment in the same commit, `bedba59bac` ("Design 27 phase 7"), so the "legacy" they preserve is
pre-design-27 *us*, not DS3.

They were not pure synonyms: `numericOrdering` desugared each into a `Compare` with the cast its
semantics implied — `as: number` on the left, a fractional literal on the right. So the six uses
were rewritten to that canonical form rather than renamed:

```json
{"gt": {"left": {"ref": …, "as": "number"}, "right": {"value": 80.0}}}
```

The fractional spelling is deliberate: `"value": 80` reads as `Literal.Whole` and compiles to
`TypedValue.Integer`, where the alias always produced `Fractional`. Keeping the literal kind
means nothing but the spelling changed. `as: number` also matters beyond fidelity — without it a
typed literal against an uncast reference draws a compiler lint (`CompareSpineTest`'s
`theCastSilencesTheLint`).

Removed with them: `numericOrdering`, whose only callers they were; `JsonFields.number`, whose
only caller *it* was; and `CompareSpineTest`'s
`legacyGreaterThanConstructsGtWithANumberCastOnTheLeft`, which pinned the desugaring. That test
earned its keep on the way out — it states the alias's exact model, which is how the rewrite
above was checked rather than guessed.

**Two stale comments, artefacts in their own right.** `ConditionJson` described "the six
comparisons and their **five** legacy spellings" and explained the cast for "the equality trio …
and the ordered pair". But three of the five — `equals`, `not-equals`, `ref-equals` — had
already been deleted, so the comment described an equality trio that no longer existed.
`CompareSpineTest` had the matching problem: a section headed "the legacy absent rule, preserved
through the aliases", where the aliases carrying that rule were the deleted trio, not the two
that remained. Both now say what the code does.

**What was checked and cleared**, recorded so the same ground is not re-dug:

| Suspect | Verdict |
|---|---|
| `take_until` / `take_through` | **written**, via a ternary on `inclusive()` — the audit's regex missed it, not the writer |
| `Dispatch.ANY` (DS3's `matchOrder="any"`) | **live and deliberate.** `Ds3Parser` refuses `matchOrder` *by name*, documented in `package-info` and asserted in `Ds3ImportTest`; no vendored DS3 config uses it. An engine capability reachable by hand, not a migration gap |
| the config model's 146 declared types | none unreferenced |
| client vocabulary vs the wire format | `Instructions`' 49 kinds and `OutputJson`'s 49 tags match **exactly**, both ways — no drift, so Q20's conformance test would pass today, which makes it a guard rather than a fix |
| `TODO`/`FIXME`/`HACK` in main source | none |
| `value-map` | gone as a wire tag, but `ValueMap` survives as a model type, so the comments pointing at "what value-map used to be" are history rather than staleness. Left alone |

**What is left, and wants a ruling rather than a sweep: the version-5 substring base.**
`BodyCompiler.java:225` reads a `substring` start as 1-based from version 5 and 0-based before,
and `ReferenceCheck.java:696` warns pre-version-5 configurations to add 1 when bumping. That is
the same class of artefact as `Store` — reading our own old files — but unlike a spelling it is
*semantics*, it is design-ruled (design/17 §7), and `substring` appears 61 times across the
corpus. Removing it means one fixed base, arithmetic on every explicit start, and the warning
deleted. The goldens would gate it, but the decision is the owner's, because it changes the
language rather than how it is spelt.

### 3.5 Where the comparison runs — settled 2026-11-12

Three ways were open; the first was taken, and building it turned up a reason the others would
not have worked either.

**The fixture is embedded as a story constant.** It already was: `ShapeshifterEditorStories`
holds `strict_kv` as `PROJECT`, because a story runs in a browser and cannot read another
module's test resource. So the choice was really *where the drift guard lives*, and the answer
was not where it looked.

**The guard cannot live beside the stories.** The workbench module has a JUnit source set, and
`stroom-shapeshifter-config` is on its classpath, so a test there could compare the constant
with the fixture directly and type-safely. It would also never run: the module disables its own
test task —

```groovy
// The workbench's JUnit tests are for developing the workbench, not Stroom, so the build doesn't run
// them. Run them on purpose with -PworkbenchUnitTests
tasks.named('test') { enabled = project.hasProperty('workbenchUnitTests') }
```

I wrote that test, ran it, and got `test SKIPPED`. A guard that does not run is not a guard, so
it was deleted and rewritten on the other side of the boundary.

**The guard is `TestStoryFixtureCopy`, in the engine module**, which owns the fixture and whose
tests run on every build. It is a tripwire rather than a second comparison: it hashes the
fixture's **parsed and re-printed model**, so reformatting or reordering keys does not trip it
and a change of substance does, and on tripping it prints the configuration to carry across to
the constant. Verified by perturbing one field — `buffer_size` 20000 to 30000 — which failed it,
and restoring it, which passed.

What it cannot catch is an edit to the stories' constant alone; the modules cannot see each
other, so each side guards what it owns, and the stories' own assertions are what hold their
copy honest. That asymmetry is written into the test's javadoc rather than left for someone to
discover.

**The comparison itself needed nothing new.** `JsonValues` already has `parse`, `toJson` and
`jsonEquals`, so the story compares the saved project with the expected one after both have been
through the same parse and print — semantic, as §3.1 requires, and with a readable diff on
failure. Template ids are generated, so they are replaced by a dash on both sides: an id is the
one part of a configuration no author chooses.

## 4. The ladder

My first cut at this ordered the samples by file size. That was wrong: size measures the input
corpus, not the difficulty of the configuration. `text_019_single_line_split` is 5KB and
introduces six new ideas at once; `apache_httpd` is 428KB and is mostly the same three ideas
repeated two hundred times.

Ordered instead by **how much is new at each rung** — because the question is where the UI stops
teaching the author, and that only shows when one unfamiliar thing arrives at a time:

| Rung | Sample | What is new |
|---|---|---|
| 1 | `strict_kv` | the root template, making a mode, dispatching into it, a regex with groups, `value-of`, and a catch-all row that consumes and warns |
| 2 | `lexer_tokens` | dispatch *strategy* at the dispatch site — `lexer`, longest-match-wins — and order within a mode as priority |
| 3 | `classify_alerts` | `classify`, where every candidate runs rather than one |
| 4 | `library_parts` | the top-level pattern library: `ref`, `tag`, `take_while`, `take_until`, `take_through`, and labels in place of group numbers |
| 5 | `text_019_single_line_split` | delimiters with containers, declarations, capturing into a list, `variable` with a nested body, `if`/`exists`, and `[matchCount()]` to read one list against another |
| 6 | `progressive_len_records` | a parts match that reads binary — `read as varint`, `take length=len` — and `encoding: raw` |

Rungs 2 and 3 are small deltas from rung 1 and might have collapsed into one walkthrough about
dispatch strategies. Stepping them (§6.1, §6.2) says keep them apart: `lexer` demotes the
panel's order to a tie-breaker, `classify` makes it meaningless, and each has a different wrong
belief to catch — that a longest-match mode needs reordering, and that `classify` means every
match rather than every template once. Twelve steps and nine steps respectively, so the cost of
keeping both is small.

`avro_users` and `xml_to_json` are deliberately not on the ladder. `avro_users` is rung 6 plus a
schema; `xml_to_json`'s interest is `dot_all` and `advance: 0`, which belong to the pattern
workbench rather than to authoring order. Both are candidates once the ladder is walked.

## 5. Rung 1 — `strict_kv`, step by step

The sample: `<records>` wrapping one `<pair key="…">…</pair>` per `key=value` line, with any
line that is not a pair consumed and reported as a warning. Three templates, one mode.

The walkthrough is written in **the author's order**, not the document's. Where the UI demands a
different order, that is recorded as a finding rather than smoothed over.

### 5.1 Start the project

| # | The author does | What should happen | Status |
|---|---|---|---|
| 1 | Creates a Shapeshifter document in the explorer and opens it | The Design tab, with the template panel showing `Settings`, `Data` and `Templates` | to verify |
| 2 | Selects `Settings` | `Source — the document`: project name, version, encoding, dispatch, buffer size, max sequence entries, ignore errors | built (`SourceSettings.ui.xml`) |
| 3 | Sets encoding to `utf-8` | — | built |

**Open question at step 1.** What a brand-new document contains is not settled by anything I
could read: whether it arrives with no templates at all, or with a root template already made.
It matters, because *the root template is the one template an author cannot discover the need
for*. Every sample has one, it is the only template with `"match": "source"`, and nothing in the
panel explains that the first thing to make is a template matching the whole input. If a new
document arrives empty, the author's first action is a step the UI has not taught. **This is the
first thing to check against the running app, and probably the first thing to fix.**

*Amended 2026-11-12, while stepping rungs 4 and 6:* "every sample has one" is wrong.
`library_parts` and `progressive_len_records` have a single template in the root mode and no
`source` template at all — the root level dispatches it directly. So a root template is a
choice: needed to wrap an envelope or to reach a named mode, not otherwise. A new document that
pre-builds one would be wrong for two of the six samples, which points the answer at something
the editor *says* rather than something it makes (§6.3).

### 5.2 Get a sample in front of the author

| # | The author does | What should happen | Status |
|---|---|---|---|
| 4 | Selects `Data` | The sample page: `Sample data from`, and a paste box | built (`SampleSourcePicker.ui.xml`) |
| 5 | Pastes a few `key=value` lines and a junk line | The input pane shows them | built |

Deliberately before any template. An author matching a record wants the record in front of them
while they write the pattern, and the input pane is where the marks appear. A walkthrough that
built templates first would be testing a flow nobody uses.

### 5.3 Match the whole input — the root template

**Rewritten 2026-11-12.** This stage used to have the author make the root template. They do not:
it is there already, named `document`, with `match: source` and a body of one empty
`apply-templates` (`Templates.document()`, design 44 §5m). What the author does is find it.

| # | The author does | What should happen | Status |
|---|---|---|---|
| 6 | Reads the panel's `Document` row | The template is already there, selected or selectable, its strip shown | built (`Templates.ensureDocument`, design 44 §5m) |
| 7 | Opens it and sees `match: source` and one `apply-templates` card with nothing in it | The card says *"blank is this match's whole content"* | built (`InstructionEditController.java:294`) |
| 8 | Renames it if they want to — the samples call it `source`, the editor calls it `document` | — | built (`TemplateEdit.ui.xml`) |

**Finding — the undiscoverable step was already solved, and the walkthrough nearly missed it.**
§5.1 called the root template *the one template an author cannot discover the need for*; design
44 §5m had reached the same conclusion and pre-built it. Two consequences for the ladder: the
author's first act is to *extend* a template rather than create one, and rungs 2, 3 and 5 lose
their "add and name a `source` template" steps the same way.

### 5.4 Match one pair — the first real pattern

| # | The author does | What should happen | Status |
|---|---|---|---|
| 10 | `Add template`, names it `pair` | — | built |
| 11 | Opens the match workbench from the chip, picks `regex`, types `([a-z]+)=([^\n]*)\n` | The pattern's groups listed; the input pane marks each match, the two groups tinted by capture | built (`Regex.ui.xml`, `ContentPaneController`) |
| 12 | Puts it in a new mode `kv` — `Mode` → `New…` | The mode is made and picked in one step | built (`TemplateEdit.ui.xml`, design 44 §5k) |

**Finding — the order is inverted here, and it is the central one.** The author's instinct is
*match a pair, then say where pairs are dispatched from*. But a template with a mode is
unreachable until something dispatches into that mode, so between steps 12 and 13 the project is
in a state where `pair` matches nothing and the preview shows an empty `<records/>`. The author
has done nothing wrong and the editor has nothing to say about it.

The engine knows this: a mode with no dispatch site is a dead mode. **Whether the UI should say
so — a note on the mode, a flag on the template, a prompt offering to add the `apply-templates`
— is the first design question this walkthrough raises.** It is the difference between an author
who thinks they have made a mistake and one who knows they are halfway through a two-part edit.

### 5.5 Dispatch into the mode

| # | The author does | What should happen | Status |
|---|---|---|---|
| 13 | Selects `source`, adds a `text` card: `<records>\n` | A card in the body | built |
| 14 | Adds an `apply-templates` card, select `$0`, mode `kv` | Mode is a picker over the project's modes, not free text | built (`InstructionEdit.ui.xml:33`, design 44 §5k) |
| 15 | Adds a `text` card: `</records>\n` | — | built |
| 16 | Runs | Output shows the pairs | built |

Three cards for what the author thinks of as one thought — *wrap the pairs in `<records>`* — and
the two `text` cards are an opening and closing tag that must be kept in step by hand. The
`element` instruction exists and expresses exactly this. Whether the author would find it, and
whether the add menu leads them to it before they reach for two `text` cards, is a
quality-of-life question worth watching at this step rather than a defect.

### 5.6 Extract the fields

| # | The author does | What should happen | Status |
|---|---|---|---|
| 17 | Selects `pair`, adds `text` ` <pair key="` | — | built |
| 18 | Adds `value-of`, select `$1` | The capture's tint in the card matches its tint in the input pane | built (design 18 §10) |
| 19 | Adds `text` `">`, `value-of` `$2`, `text` `</pair>\n` | — | built |
| 20 | Runs | Output matches `example_output.xml` for the pair lines | built |

Five cards for one line of output. Again `element` plus `attribute` is the shorter spelling, and
again the question is whether the author is led there.

### 5.7 Deal with the junk line

| # | The author does | What should happen | Status |
|---|---|---|---|
| 21 | `Add template`, names it `junk_line`, mode `kv` | — | built |
| 22 | Match: regex `[^\n]*\n?` | — | built |
| 23 | Moves it below `pair` with `Down` | Order within a mode is dispatch priority | built (`TemplatePanelController.java:146`) |
| 24 | Sets `match is` to the consuming role | The role picker, with its note on what each role means | built (`TemplateStrip.ui.xml:40`, design 44 §5d) |
| 25 | Adds an `emit-error` card, severity `warning`, message `unrecognised line: ` + `$0` | Severity is a picker; the message is a ref expression of text and capture | built (`InstructionEdit.ui.xml`) |
| 26 | Runs | The junk line warns and does not appear in the output | built |

**Finding — priority is positional and invisible.** Steps 22–23 are the only place in the
walkthrough where getting the *order* wrong silently changes the output: `junk_line` above
`pair` matches everything and the project emits nothing but warnings. The panel shows templates
in order within a mode, and `Up`/`Down` change it, but nothing on the screen says *this order is
the priority order*. For two templates an author will work it out. For `lexer_tokens` at rung 2,
which has five templates in one mode and depends on `=` versus `=>` resolving correctly, they
will not.

*Amended 2026-11-12:* not quite nothing — the move buttons' own tooltips read *"Move up: earlier
in dispatch order"* and *"Move down: later in dispatch order"* (`TemplatePanelController.java:116`).
So the affordance names it, on hover, in the one place an author who already suspects order
matters would look. The list itself still says nothing, and the tooltip is silent on the fact
that *which* order rule applies is chosen elsewhere — see §6.1.

### 5.8 Close

| # | The author does | What should happen | Status |
|---|---|---|---|
| 27 | Saves | `Save` is enabled, and the project the editor is holding is the same model as `strict_kv/project.json` | the keystone assertion, §3 — **but not read from the save**, see §5.9 |

### 5.9 What the browser said — walked 2026-11-12

**Rung 1 is driven end to end by a kept story, `AuthorRungOne`, and the keystone holds.** The
story creates the project from a document with nothing in it, walks all 27 steps, and compares
the project the editor built with `strict_kv`. Sixteen runs at about two and a half minutes
each; what the fifteen failures were is the useful part.

**Three were findings about the editor**, and two of those are the same one twice:

1. **The panel's document row is labelled with the fixed word `document`**, not the template's
   name (`TemplatePanelController` passes the literal when it builds the row). The samples call
   that template `source`, so the walkthrough renames it — and nothing in the panel changes. The
   new name appears only in the strip's head. It bit twice: once asserting the rename, once
   trying to select the row again afterwards. A rename that is both necessary and invisible where
   the author is looking is worse than either alone.
2. **The role is spelt differently from the model.** The configuration's flag is `consume`; the
   picker says *advance only*, because `MatchRole` tried "skip" and "eater" and found both
   misleading — *"nothing is discarded and nothing stops"*. Not a defect: an author never sees
   the word `consume`, and the step table's word is the model's. Recorded because the two
   vocabularies meet here and a walkthrough written from the fixtures will reach for the wrong
   one.
3. **Severity is a real choice with a wrong-ish default.** The picker lists `info`, `warning`,
   `error`, `fatal` — lowercase, as the wire spells them — and a new `emit-error` starts at
   `error`. The idiomatic eater warns rather than errors, so the author must notice and change it.

**Four were my ignorance of the harness**, and they are the ones that make the remaining rungs
cheaper:

| What failed | Why |
|---|---|
| `play.selectOptions` on the encoding picker | A Stroom `SelectionBox` is not a native `<select>`. Click the box, then the item. |
| Finding the item | The list is a GWT `PopupPanel` on the document body, outside the story's root: reach it with `play.screen()`, which the framework documents for exactly this. |
| Finding `kv` | Once a mode has templates, its name is also a heading in the panel. Scope item picks to `.selection-item-cell__text`. |
| Matching a dialog by title | `TextMatch.containing` is case-sensitive and the dialogs are titled *Edit Template*, *New emit-error*. |

All four now live in two helpers, `pickFromBox` and `dialogMatching`, so rungs 2 to 6 pay for
them once.

**One was a trap worth the whole exercise.** `play.type` is `userEvent.type`, where `[` opens a
key descriptor — so `([a-z]+)=([^\n]*)\n` arrived as `(+)=(*)\n`, with the character classes
silently eaten. The result was a **valid regex that means something else**, and nothing on
screen looked wrong: every on-screen assertion in the story passed. Only the model comparison
caught it. If §3 needed a justification beyond authorability, this is it — a story that asserts
what it can see would have shipped a walkthrough that builds the wrong configuration. Literal
brackets are typed `[[`.

**And one was the fixture, not the walkthrough.** The editor writes the current format version,
5; `strict_kv` said 4. That is §3.1's third trap arriving in person. The fixture moved rather
than the comparison bending: `strict_kv` uses no `substring`, so the version gates nothing for
it, and its goldens pass unchanged. `TestStoryFixtureCopy` tripped on cue and its digest moved
with it.

**What the comparison tolerates, and why that is right.** The editor writes defaults the fixture
omits — `match_limits`, `max_depth`, the regex `flags` and `advance: 0`. Containment
(`JsonValues.jsonContains`, Jest's `toMatchObject`) accepts those while still requiring every
array to be the same length, so a missing template, a missing body card or a card in the wrong
place still fails. Generated ids are replaced by a dash on both sides.

**Still owed: the sample.** §5.2 puts *get a sample in front of the author* at steps 4 and 5,
before any template, on the argument that an author wants the record visible while writing the
pattern. The story does not do it — the panel's `Data` row reads `none` throughout — so the
walkthrough builds the configuration without ever seeing it run. It does not affect the
keystone, the sample being part of the document rather than the project, but it is the one step
of the 27 that is asserted on paper and not in the browser.

## 6. Rungs 2 to 6, step by step

Stepped 2026-11-12 the same way §5 was: from the configuration each rung has to produce and
the component that would produce it, with the implementing file named in the status column.
Each rung's steps are numbered from 1, because each is a walkthrough of its own.

What a step table cannot settle is what the author *understands* at each step, so every rung
keeps the question it exists to ask, and the browser pass stays owed (§9). Where reading the
source turned up something the table could settle, it is recorded as a finding here rather
than deferred.

### 6.1 Rung 2 — `lexer_tokens`

The sample: `total => count=42 plus 7` becoming
`<w>total</w><arrow/><w>count</w><op>=</op><n>42</n><w>plus</w><n>7</n>`. Six templates, one
mode, `dispatch: "lexer"` at the one apply site.

| # | The author does | What should happen | Status |
|---|---|---|---|
| 1 | Settings: name `lexer_tokens`, encoding `utf-8`, buffer 20000, ignore errors off | — | built (`SourceSettings.ui.xml`) |
| 2 | Data: pastes the token line | The input pane shows it | built (`SampleSourcePicker.ui.xml`) |
| 3 | Opens the `Document` row, already holding `match: source` and an empty `apply-templates` | Nothing to create (§5.3) | built (`Templates.ensureDocument`) |
| 4 | Leaves that card's `Select` blank | The help reads *"blank is this match's whole content"*, and the model reads it as `$0` (§3.1) | built (`InstructionEditController.java:294`) |
| 5 | `Mode` → `New…` `tokens` | Mode made and picked in one step | built (design 44 §5k) |
| 6 | Sets `Dispatch` to `lexer` on the same card | — | built (`InstructionEdit.ui.xml:40`) |
| 7 | Adds `assign`, mode `tokens`, regex `=`, body `text` `<op>=</op>` | — | built |
| 8 | Adds `arrow`, regex `=>`, body `text` `<arrow/>` | — | built |
| 9 | Adds `word`, regex `[a-z]+`, body `text` `<w>`, `value-of` `$0`, `text` `</w>` | — | built |
| 10 | Adds `number`, regex `[0-9]+`, body the same shape with `<n>` | — | built |
| 11 | Adds `space`, regex ` +`, sets `match is` to the consuming role, leaves the body empty | The role note says what a consuming match is for | built (`TemplateStrip.ui.xml:40`) |
| 12 | Runs | `=>` comes out as `<arrow/>`, not `<op>=</op>` followed by a stray `>` | built |

**The question, sharpened.** `arrow` is listed *below* `assign` and still wins, because `lexer`
takes the longest match and uses list order only to break ties. The same project under `strict`
would give `assign` the `=` of `=>` and then fail on the `>`. So the panel's order is
load-bearing in one mode and nearly inert in the other, and the thing that decides which is a
picker on a card in a different pane. Step 11 is where to watch: an author who thinks order is
the rule will try to fix a problem they do not have by moving `arrow` up, and nothing will
change — which is either reassuring or baffling depending on whether the screen has told them
`lexer` is in force.

### 6.2 Rung 3 — `classify_alerts`

The sample: three log lines in, one `<flags><error/><code>57</code></flags>` out. Four
templates, one mode, `dispatch: "classify"`.

| # | The author does | What should happen | Status |
|---|---|---|---|
| 1 | Settings as rung 2, name `classify_alerts` | — | built |
| 2 | Data: pastes the three lines (`INFO started`, `ERROR connection lost code=57`, `WARN retrying code=98`) | — | built |
| 3 | Opens the `Document` row (§5.3) | Nothing to create | built (`Templates.ensureDocument`) |
| 4 | Body: a `text` `<flags>` before the card that is already there, mode `New…` `flags`, then `text` `</flags>\n` after it | — | built |
| 5 | Sets `Dispatch` to `classify` | — | built |
| 6 | Adds `has_error`, mode `flags`, regex `ERROR`, body `text` `<error/>` | — | built |
| 7 | Adds `has_timeout`, regex `timed out`, body `text` `<timeout/>` | — | built |
| 8 | Adds `first_code`, regex `code=([0-9]+)`, body `text` `<code>`, `value-of` `$1`, `text` `</code>` | — | built |
| 9 | Runs | `<error/>` and `<code>57</code>`; no `<timeout/>` | built |

**Finding — the output is the lesson, and the input was built to teach it.** `code=98` is on
the third line and is deliberately absent from the output: under `classify` each template runs
**once, at its leftmost match**, so `first_code` is the *first* code, not each one. And the
whole file is one region, so `<flags>` wraps the lot rather than appearing per line. An author
who expects `classify` to mean *every match* rather than *every template* will read this output
as a bug. Whether anything on the screen distinguishes those two readings is the question, and
this fixture is the right place to ask it because the difference is visible in four lines.

The standing question from the mode picker holds: under `classify` the panel's ordered list
means nothing — no priority, no tie to break. With rungs 2 and 3 together, the same list in the
same panel carries three meanings (priority, tie-break, nothing) chosen elsewhere.

### 6.3 Rung 4 — `library_parts`

The sample: one syslog-ish line per record, built from four named parts. **One template, and no
`source` template at all.**

| # | The author does | What should happen | Status |
|---|---|---|---|
| 1 | Settings: name `library_parts`, encoding `auto`, ignore errors on | — | built |
| 2 | Data: pastes two log lines | — | built |
| 3 | `Add a pattern part` → `TIMESTAMP`, a regex `[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z` | A part, named, editable from the panel | built (`TemplatePanelController.java:122`) |
| 4 | Adds `WORD` = `take_while` `\S`, and `KEY` = `take_while` `[a-z]` | — | built (`PatternNodeEdit.ui.xml`) |
| 5 | Adds `FIELD` = a sequence of `tag` ` `, `ref` `KEY`, `tag` `=` | A part whose body references another part | built (`PatternNodeEdit.ui.xml`, `ref` field) |
| 6 | `Add template`, names it `line`, leaves it in the root mode | — | built |
| 7 | Match kind `pattern`; builds the sequence: `ref TIMESTAMP` label `time`, `tag " host="`, `ref WORD` label `host`, `tag " severity="`, `take_while \p{L}` label `severity`, `tag " msg="`, `take_until " pid="` label `msg`, `ref FIELD`, `take_while \p{Nd}` label `pid`, `take_through "\n"` | The tree, each node labelled; labelled nodes tint in the input pane | built (`PatternTree.ui.xml`, `PatternNodeEdit.ui.xml`) |
| 8 | Body: one `value-of` whose parts name the labels — `time`, `host`, `severity`, `pid`, `msg` — rather than group numbers | — | built |
| 9 | Runs | One `<event …>` per line | built |

**Finding — two of the six samples had no root `source` template; now they do. Ruled and done
2026-11-12.** §5.3 said every sample has one. That was wrong: `library_parts` and
`progressive_len_records` had a single template in the root mode, which the root level dispatches
directly. The editor cannot build either, because `Templates.ensureDocument` inserts a document
template into *any* project that lacks one, on load as well as on creation — so a UI round-trip
of either came back with a template the fixture did not have, and the §3 keystone could not hold.

**Both fixtures were re-baselined** with the document template the editor would insert, rather
than the comparison being taught to ignore it: the keystone is worth more exact than convenient.
The change is behaviour-neutral, and provably so rather than by assertion — `RootPlanner.plan`
excludes the Source template from the root level and derives the mode, the dispatch and
`ignoreErrors` identically whether the document template is present or absent, while its body
contributes an empty prologue and an empty tail. `GoldenFixtureTest`: 75 tests, 0 failures, both
fixtures exercised.

What survives as the design point: **a root template is not something an author must know to
want.** It is pre-built for design 44 §5m's reasons — a visible place for the input loop, and
somewhere to declare a run-scoped name — which answers §5.1 in the affirmative.

**Finding — a `ref` need not capture.** `ref FIELD` at step 7 carries no label, so it matches
` pid=` and contributes nothing to the output; `FIELD` exists to consume structure, not to
extract. It also references `KEY`, so parts form a small dependency graph of their own. Two
ideas an author meets in the same step: a part can be structural, and a part can be built from
parts.

**The question stands:** the walkthrough's natural order is steps 6–7 before 3–5 — write the
line, then lift `TIMESTAMP` out of it once it repeats. Lifting after the fact is how a person
factors. Whether the editor allows it, or whether a part must exist before a `ref` can name it,
is the thing to try first.

### 6.4 Rung 5 — `text_019_single_line_split`

The sample: a `|`-separated file whose first record is a header row of column names and whose
remaining records are data rows, each value paired with the heading in the same position. Five
templates, four modes, and the only use of `match_index` in the corpus.

| # | The author does | What should happen | Status |
|---|---|---|---|
| 1 | Settings: name, encoding `auto`, ignore errors off | — | built |
| 2 | Data: pastes the header row and two data rows | — | built |
| 3 | Opens the `Document` row (§5.3) | Nothing to create | built (`Templates.ensureDocument`) |
| 4 | On it, declares `heading`, type `list` | The declarations grid beneath the strip — and design 44 §5m's second reason for pre-building the row: *"somewhere to declare a run-scoped name"* | built (`DeclarationEdit.ui.xml`, `TemplateStrip.ui.xml` `declarations`) |
| 5 | Body: a `value-of` of the XML prologue before the existing `apply-templates`, its mode `New…` `root`, and a `value-of` `\n</records>\n` after | — | built |
| 6 | Adds `header_record`, mode `root`, match `delimiter` `\|` | — | built (`Delimiter.ui.xml`) |
| 7 | Sets its limits `max` to 1 | *"stop matching this template after N matches"* | built (`GuardAndLimits.ui.xml`) |
| 8 | Body: `apply-templates` `$1`, mode `New…` `header_columns` | — | built |
| 9 | Adds `header_column`, mode `header_columns`, match `delimiter` `,` with container `"` to `"` | The container fields sit beside the delimiter | built (`Delimiter.ui.xml`) |
| 10 | Captures `heading` ← `$1` on that template, body empty | The captures grid, offering the declared name | built (`CaptureEdit.ui.xml`, `declared` field) |
| 11 | Adds `data_record`, mode `root`, match `delimiter` `\|`, below `header_record` | — | built |
| 12 | Declares `record_body`, type `scalar`, on `data_record` | — | built |
| 13 | Body: a `variable` named `record_body` whose card list is one `apply-templates` `$1` mode `New…` `data_columns` | *"what the body beneath is bound to"* | built (`InstructionEditController.java:322`) |
| 14 | Adds an `if` whose condition is `exists` `record_body`, holding `value-of` `\n   <record>`, `value-of` `record_body`, `value-of` `\n   </record>` | The condition row offers `exists` | built (`GuardClause.Op.EXISTS`, `ConditionEdit.ui.xml`) |
| 15 | Adds `data_column`, mode `data_columns`, match `delimiter` `,` with the same containers | — | built |
| 16 | Body: `value-of` `\n      <data name="`, then `heading[matchCount()]`, then `" value="`, then `$1`, then `"/>` | The help names the form: *"[i] or [matchCount()] after a name says which match"* | built (`InstructionEditController.java:271`) |
| 17 | Runs | Each value carries the heading in its own position | built |

**Finding — two templates with the same match, distinguished only by a limit and their
position.** Steps 6–7 and 11 make `header_record` and `data_record` both match `delimiter |` in
mode `root`. The *only* things that make the first one the header are that it is listed first
and that its `max` is 1. Get either wrong and the file parses with the header as data, or every
row as a header, with no error anywhere — the engine is doing exactly what it was told. This is
the same class as §5.7's positional-priority finding and a good deal worse, because here the
two templates are indistinguishable at a glance in the panel: same mode, same match summary,
and the limit that separates them lives on a row of the strip the author has to select each
template to see. Whether the panel can show *why* two identical matches are not the same
template is the sharpest quality-of-life question the ladder raises.

**Finding — the declaration must come before the capture.** Step 4 before step 10, and step 12
before 13–14, because `CaptureEdit` offers the *declared* names. That ordering is almost
certainly right — it is how the author learns that a list is a thing with a lifetime (design 35)
— but it means the walkthrough cannot be written in the order *extract the value, then decide
where it goes*, which is the order the §6.3 question asks about for parts. Worth noting that
the two rungs pull in opposite directions on the same issue.

**The questions stand**, both now with a step number attached: (a) step 16 is the least
discoverable thing in any of the six samples — a function call typed into a text field to say
*the nth of these goes with the nth of those*, with no form field anywhere for `match_index`;
(b) steps 13–14 are three nested instructions (`variable`, `if`, `exists`) for the single
intention *don't write an empty record*, and it is the idiom every real configuration will need.

### 6.5 Rung 6 — `progressive_len_records`

The sample: length-prefixed binary records — a varint length, then that many bytes of name. One
template, no `source` template, `encoding: "raw"` on the template.

| # | The author does | What should happen | Status |
|---|---|---|---|
| 1 | Settings: name, buffer 1000000, encoding `UTF-8`, ignore errors on | — | built |
| 2 | Gets `input.bin` in front of themselves | **cannot be pasted** — see the finding | blocked |
| 3 | `Add template`, names it `root`, leaves it in the root mode | — | built |
| 4 | Sets the template's own encoding to `raw` | The per-template encoding field | built (`TemplateEdit.ui.xml` `encoding`) |
| 5 | Match kind `parts` | The framed sequence's part list | built (`Parts.ui.xml`) |
| 6 | Part 1: kind `read`, `As` `varint`, `Label` `len` | — | built (`PartEdit.ui.xml`) |
| 7 | Part 2: kind `take`, `Length` from the label `len`, `Label` `name` | The length field takes a label, not just a number (`lengthKind`) | built (`PartEdit.ui.xml`) |
| 8 | Body: `value-of` `<rec name="`, `name`, `"/>\n` | — | built |
| 9 | Runs | One `<rec …>` per record | built |

**Finding — rung 6 cannot be walked as written, and the blocker is step 2.** The sample page
offers two kinds, `PASTED` and `STREAM` (`SampleSourcePicker.Kind`), and a paste box is a text
editor. `input.bin` is not text, so the only route to a binary sample is a stream — which means
rung 6's walkthrough needs a stream fixture in the story's REST stub, not a paste. This is a
harder dependency than any other rung has, and it is worth settling before the step table is
verified rather than after: if a binary sample cannot reach the editor at all, the rest of the
rung is untestable, and *that* is the finding.

**Ruled 2026-11-12: the story gets a stream fixture.** The sample is served through the story's
REST stub as a stream, the way the page's `STREAM` kind expects, rather than rung 6 being dropped
or swapped for a text sample carrying a parts match. Only that answers the question the rung
exists to ask, and it exercises the stream path, which today has one story row and a single meta
row. Not built — rung 6 is not in the first automation slice (§9).

The standing question — whether the marks, capture tints and preview mean anything over a
binary sample — is downstream of it. Step 7 adds a second question now that the form is known:
`Length` reads a label defined by an *earlier part of the same match*, so the parts list has
internal dependencies, and whether `PartEdit` offers `len` in that field or expects it typed is
exactly the kind of thing only the browser will say.

## 7. The large samples: a probe, not a test

Separately from the ladder, `apache_httpd` (428KB, 209 `translate` calls), `win_sec_xml` (164KB)
and `win_app` (149KB) are worth building through the UI **once**, to find out whether it can be
done at all.

That is a different kind of exercise and it must not be kept as a story:

- it answers a one-off question — *can the UI express this* — rather than guarding an invariant;
- a 428KB configuration as a story would be slow, and would break on any UI change for reasons
  that teach nobody anything;
- what is worth keeping is the **finding**, written into this design, not the script.

So: a scratch run, or a story deleted after it has reported, and a section added here. Recorded
now so the distinction does not have to be remembered later.

## 8. What this already says about design 18 Q20

Q20 ruled thirty hand-written forms, one per transform kind, against the draft recommendation of
descriptors. Counting what the fixtures actually use changes the **order** that work should be
done in, though not the ruling:

| Transform | Occurrences across all project fixtures |
|---|---|
| `translate` | 504 |
| `substring` | 61 |
| `contains` | 61 |
| `replace` | 24 |
| `trim` | 13 |
| `tokenize`, `substring-after`, `string-length`, `round`, `parse-date`, `format-date`, `divide`, `add` | 3 each |
| `upper-case`, `format-number`, `decode` | 1 each |
| the remaining 14 kinds | 0 |

Five kinds are 96% of all transform use, and `translate` alone is 73%. Fourteen of the thirty do
not appear in any sample at all.

Two consequences:

1. **Build the five first**, and `translate` first of the five. The gain from a hand-written
   form is concentrated almost entirely in it.
2. **The ladder will not exercise the transforms.** Every body instruction in all six chosen
   samples — `text`, `value-of`, `apply-templates`, `emit-error`, `variable`, `if` — is already
   in `FORM_KINDS`. The transforms live in the migrated DS3 configs: `apache_httpd`, the
   `win_*` family, the `text_00*` family. So the §7 probe is not a curiosity — **it is where the
   Q20 evidence is**, and it should come before the thirty forms are built rather than after.

The conformance test over `OutputNode`'s `permits` list, which Q20 records as owed whichever way
the forms are written, is unaffected by any of this.

## 9. What to do next

1. ~~Write rungs 2 to 6 as step tables~~ — **done 2026-11-12**, §6. Written from the source, so
   every row is *buildable as far as reading can tell*; none is verified in the browser.
2. ~~Re-baseline the three lax-by-version fixtures to strict~~ — **done 2026-11-12**, and not by
   editing them: design 11 §10.3 took the version out of the dispatch default, so `library_parts`,
   `text_019_single_line_split` and `progressive_len_records` say nothing and are therefore strict.
   The two without a root template were re-baselined for a different reason (§6.3).
3. **Automate rung 1 only** — ruled 2026-11-12, and **begun the same day**: `AuthorRungOne`
   drives the first six steps and asserts the keystone, the title now runs 30 stories green, and
   §5.9 records what the browser said and what it cost. What remains is steps 6 to 26 — the
   templates, the mode, the regex workbench and the body cards — and the decision the ruling
   reserved: whether that is worth driving, now that the method is proven and the three harness
   lessons are paid for.
4. Walk the remaining five against the running workbench as their stories are written, settling
   what only the browser can say: what the UI says about a mode with no dispatch site (§5.4),
   whether a pattern part can be lifted out of a pattern already written (§6.3), and whether the
   marks mean anything over a binary sample (§6.5, which also needs the stream fixture ruled
   there). §5.1 and §6.3's first question are already answered from the source.
5. Run the §7 probe on `apache_httpd` before building Q20's forms.

**What the walkthroughs turned up, as design questions rather than steps** — ~~three~~ four,
now that rung 1 has been driven. None is about a missing form; all are about what the editor
*tells* the author. **Moved to [44-the-workbench-kind-sample-library.md §8](44-the-workbench-kind-sample-library.md)
as Q11 to Q14, ruled 2026-11-12**, because design 44 owns the panel and the strip and is where
the next change to either will be designed from. Summarised here only:

- **The document row is labelled with a fixed word** (§5.9), so renaming that template — which
  every sample requires — changes nothing the author can see. Found by driving it.
- **Two templates with the same match are indistinguishable in the panel** (§6.4): rung 5's
  `header_record` and `data_record` differ only in position and in a match limit.
- **The panel's order means three different things** (§6.1, §6.2), chosen elsewhere.
- **`heading[matchCount()]` is typed into a plain text field** (§6.4 step 16).

Two pieces of work this design turned up that are not the walkthrough and should not wait for
it:

- ~~Take the `Store` alias out~~ — **done 2026-11-12**, §3.3; §3.4 is the wider audit for the
  same class of artefact, and leaves one open decision: the version-5 substring base.
- ~~Decide `xml_to_json`'s explicit `"dispatch": "lax"`~~ — **done 2026-11-12, the line went.**
  It was measured rather than argued: removing it from the three `xml_to_json` fixtures and from
  the `apache_httpd` pair showed that four of the five genuinely need lax and `xml_to_json` does
  not — its goldens match byte for byte under strict. So the one version-5 configuration of ours
  that asked for the migration path no longer does, and the four that need it keep it on
  evidence. See design 11 §10.3, which this probe also corrected.
