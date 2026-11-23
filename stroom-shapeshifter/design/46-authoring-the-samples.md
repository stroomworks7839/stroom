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

**And a copy the audit never looked at.** `shapeshifter-demos.zip` is a git-tracked built
artefact holding all 72 fixture projects, and taking `Store` and `greater-than` out of the
readers rewrote the fixtures but not the pack: 12 of its projects stopped parsing — 11 with
*Unknown reference part: Store*, one with *Unknown condition: greater-than* — which is every
importer of the demo pack, its whole purpose. Found by a review, not by §3.4's audit, and the
audit's claim to be exhaustive is what made it invisible: `JsonFields.checkFields` does bound
where dead *vocabulary* can hide, and says nothing about where *copies* of a fixture can. A zip
is not compiled, not tested and not read by `checkFields`.

Rebuilt from the corrected fixtures, 72 of 72 parse, and the packed projects pick up the
explicit `"dispatch": "lax"` at the same time. **Open for the owner:** the `contentPack` task
writes the zip beside the checkout and says in its own comment that it is *"not something to
commit"*, yet a copy is tracked at the repository root. A tracked build artefact that nothing
rebuilds is how this happened, and untracking it is the fix that stops it recurring.

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

**Answered 2026-11-12: the panel says so.** A mode heading nothing dispatches into now carries
*"nothing dispatches into this"* beside its name, quieter and italic, on the predicate
`Modes.applySiteCount(project, mode) == 0` — which already existed. The root group is exempt: it
is dispatched by the document template itself. The note goes as soon as an `apply-templates`
names the mode, because one that lingered after the edit was finished would be worse than no
note at all.

It is guarded where it was found rather than in a story of its own: `AuthorRungOne` passes
through this exact window — `pair` is made in mode `kv` two stages before anything dispatches
into it — so the walkthrough asserts the note appears there and is gone after the dispatch.
`ModesTest` pins the predicate.

**The finding that prompted it, kept because the reasoning is the ruling's justification:**

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

### 5.9a Automated a step at a time — done 2026-11-17

`AuthorRungOne` is retired. The walkthrough is now **26 stories, one per step**, in
`ShapeshifterAuthoringStories`, each replaying the steps before it and stopping at its own —
because the test runner takes exactly one screenshot per story, after the play ends, so a
screenshot per step means a story per step. Measured cost: ~5.4s of fixed setup per story and
~17ms per replayed step, so about 175s for the rung. The steps are written once; a story runs
the prefix up to its own.

Story names carry this section's step numbers, so a screenshot's filename names the step it is
evidence for (`…--kv-11-pattern.png` ↔ §5.4 step 11). Four things the automation settled that
the step tables had not:

- **There is no step 9.** The numbering here runs 1–8, 10–27; the gap appeared when §5.3 was
  rewritten to say the root template arrives already made. 26 steps, not 27.
- **Steps 10 and 12 are one action.** A template is given its mode in the dialog that makes it,
  so `pair` and the `kv` mode arrive together and cannot be driven apart. The table's order is
  the author's intent; the UI's is one dialog.
- **Step 23 is already done by step 21.** A template added second *is* second, so the walkthrough
  asserts the dispatch order rather than performing a move that would undo it.
- **Steps 16, 20 and 26 — "Runs" — are asserted, not clicked**, and that is forced rather than
  chosen. See below.

**The sample goes in at step 5, and the panes stay empty until the end.** §5.2 puts the record in
front of the author before any pattern is written, and that ordering is now driven. But
`ShapeshifterDesignController.check()` runs the project over the sample on a debounce after
*every* edit once a sample exists — and a story's preview reply is canned. Withholding the Run
button changes nothing: the editor runs itself. So the walkthrough is served a trace that read
the sample and matched nothing, until the last stage, which is served the real one.

The panes therefore read *"ran, and nothing matched — no template applied to the document"* while
the project is being written. For the stages before the dispatch is wired that is exactly true
(§5.4); for the later ones it understates rather than overstates, which is the right way round.
Showing true intermediate marks needs a live engine, which is §2's other side.

One consequence worth recording: the keystone reads whichever endpoint last carried the project.
An edit goes to `validate` while there is no sample and to `preview` once there is, and
`preview`'s body carries the project as a field beside the sample. `AuthorRungOne` only ever read
`validate` because it never pasted a sample.

**Finding — the body's cards were not on screen at all, and are now — fixed 2026-11-17.** None
of the 26 screenshots showed the card list, at any step, for any template; nor did any of the 58
older shapeshifter stories, which use a fully built fixture. The cards were in the DOM and drove
without trouble — the keystone proves all eleven reach the project — and `+ instruction` measured
at (257, 392), inside the 1280×720 viewport, with `card-list` as its parent. Nothing of it was
painted.

The cause is in `TemplateStrip.ui.xml`. The strip is a vertical split of three:
`sizes="30px, *, 170px"` — Heading, **Template**, Details. The Template pane holds the match,
role and guard rows *and the body's cards*, and it takes the rest, because a body grows with the
cards in it. But taking the rest also makes it **the pane that gives space up first**, and it had
no floor. At 1280×720 the whole strip is about 205px, so 30 and 170 fixed left the body **5px**,
and the cards were clipped out of sight while two grids that are usually empty — Declarations and
Captures — kept their full height.

The fix is the widget's own mechanism, which nothing had used here: `minSizes="30px, 110px, 44px"`.
The floors say which pane matters when there is not enough room for all three — the body first,
then enough of the details to show their headers — so the details give way instead. All 84
shapeshifter stories pass with it, and the cards are in the screenshots from step 13 on.

Worth recording for its own sake: **this was not found by three months of stories over a built
fixture, and was found in a day by photographing an author's own sequence.** The older stories
asserted classes and text, which were all present; only the camera showed that the surface an
author spends most of rung 1 in had never been visible. That is the argument for the rest of the
ladder.

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

### 6.1a Driven 2026-11-17 — twelve stories, and what the table had wrong

`ShapeshifterLexerStories`, the same shape as rung 1 (§5.9a) on the shared mechanism in
`Walkthrough`: twelve stories, a screenshot each, the last carrying the keystone. All green. Four
things the browser said that reading the source had not.

**The table has no rename, and every rung needs one.** §6.1 step 3 only *opens* the `Document`
row. But the root arrives named `document` and every sample's root is called `source`, so without
a rename the keystone cannot match. Rung 1 renames at its step 8; rung 2's table simply lost the
step. Rungs 3 and 5 will want it too.

**A mode cannot be made from the dispatch card — so the two-part edit has exactly one order.**
§6.1 step 5 is `Mode` → `New…` `tokens` on the document's `apply-templates`. That button exists
only on the template dialog (`TemplateEdit.ui.xml`); the card's Mode is a picker over modes that
already exist (`InstructionEdit.ui.xml`), because a mode is a property of the templates in it.
So the author cannot start from the dispatch side: the first token template has to be made first,
which is what creates the mode, and only then can the card be pointed at it.

This is §5.4's finding from the other end, and it sharpens it. §5.4 said the author's instinct
(*match a pair, then say where pairs are dispatched from*) leaves the project briefly broken, and
the panel's note answers that. What rung 2 adds is that **the opposite order is not available at
all** — the editor allows one sequence and says nothing about which. The note tells you that you
are halfway through; nothing tells you which half comes first.

**The note does appear here, reached from that forced order** (stage `Lx06DeadMode`), which is
worth having guarded in a second sample: the predicate is `Modes.applySiteCount == 0` and this is
a different project shape reaching it.

**An empty body is a spelling the engine's own writer would not use.** The keystone failed on one
line: `space` has `"body": []` in the fixture and the editor omits it. The editor is right.
`ProjectJson` writes a body only `if (!template.body().isEmpty())` and reads a missing one as
empty — so round-tripping `lexer_tokens` through the config writer would drop that line too. This
is §3.1's "explicit defaults" family found from the other end: not the UI writing a default it was
never asked for, but a fixture spelling one its own writer does not. The expected project in the
story drops it, with the reason recorded there.

**Two notes on driving it, neither a defect.** The workbench commits a pattern on a debounce and
closing it does not flush that, so a story has to wait for the model rather than the field — the
strip's match chip is where it shows. And the keystone reads the project *after* clicking Run,
because Run sends a preview of the project as it stands; reading whichever edit last happened to
put something on the wire is not reliable.

**One thing measured, chased, and explained — it is by design.** While building, the editor's
preview requests alternate between the project and one holding a *single* template: the one being
edited, stripped to its match, with the root and every sibling absent. That is
`Templates.experiment` (design 44 §2): opening the pattern workbench runs the sample against the
subject's match alone, so the sample pane can show what that pattern hits and nothing else.
Nothing the author sees is wrong.

**But it means the wire is not a record of what the editor holds.** The experiment goes to the
same `/preview` endpoint as a real run, so anything reading requests — which is what §3's
keystone does — must tell the two apart or it will read a half-built template on its own. It is
what the keystone read here before this was understood, and the symptom was a comparison failing
on a project with one template in it.

`Walkthrough.projectBuilt` now skips experiments, on the one discriminator that is not a guess:
`Templates.ensureDocument` means a real project always has a template whose match is the literal
`source`, and an experiment never does, because the root is not the subject and is not carried
into it. The last stage also clicks Run before reading, so what the keystone compares is the
project at the moment the author would run it rather than whichever edit last touched the wire.

Worth recording for §3.5's sake: **the keystone's premise — "what the editor sends is what the
editor holds" — was not true, and nothing would have shown that except reading the wire by hand.**
It held for rung 1 only because that walkthrough's last act happened to be an ordinary edit.

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

### 6.2a Driven 2026-11-17 — eleven stories, and the third rung confirms the second

`ShapeshifterClassifyStories`, on the shared mechanism in `Walkthrough`: eleven stories, a
screenshot each, the last carrying the keystone. All green.

**Walked for a specific reason, not for completeness.** §10 W4's change had just taught the panel
and the dispatch card to say which strategy is in force, and `CLASSIFY` was the one branch of it
with no screenshot — it existed on a unit test and on reading. That is the exact shape of both
findings this ladder has produced (§5.9a, §10 W4's slot), so the branch was driven before moving
on. `mode: flags · dispatched with classify` is now photographed.

**Three for three on rung 2's two order findings** (§6.1a). The root still has to be renamed and
§6.2's table still does not have the step; the `flags` mode still has to be made by the first
template that lives in it, because the dispatch card's Mode picks only modes that exist. Neither
is a `lexer_tokens` quirk — they are the editor's shape, and rungs 4 to 6 should be written
expecting both.

**§6.2's lesson holds on screen.** The output is `<flags><error/><code>57</code></flags>`.
`<timeout/>` is absent because nothing matched it; `code=98` is absent for a quite different
reason — under `classify` each template runs **once, at its leftmost match**, so `first_code` is
the *first* code and not each one. The story asserts the two absences separately, because they
are exactly what the author §6.2 worries about would confuse: the one who reads `classify` as
"every match" rather than "every template".

**An assertion that passed while saying almost nothing.** `getByText` matches one element's text,
and the output pane marks every instruction as a span of its own — so `<code>57</code>` is three
spans and no element holds it. The naive form *passed* for `<error/>`, which is one instruction
and therefore one span, so a less careful story would have looked right while checking a fraction
of what it claimed. The stage reads the pane's whole text instead. A third entry in the same
family as the body's cards and the ellipsised note: **what a query can see is not what is there.**

**What it did not do.** Rung 3 does not advance §10 W1 to W3. `<code>`, `$1`, `</code>` is the
same three-card shape for the third time, in a third sample sharing nothing else with the first
two — which strengthens the evidence that the cost is the editor's and not a fixture's, without
discriminating between the two candidate causes. Rung 5 remains the only rung that can, because
its output is `variable` with nested bodies and `if`/`exists`, where `element` would not help.

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

### 6.3a Driven 2026-11-18 — sixteen stories, in the order a person factors

`ShapeshifterLibraryStories`, a story per stage as the other rungs have, ending in the keystone:
the project the editor sends is the fixture, version aside. **122 actions.**

**The question §6.3 left is answered: yes.** The walk writes the line in full first — the
timestamp as a labelled regex, the host and the key as `take_while`s, ` pid=` as an inline
sequence — and then lifts the parts out of it with the tree's *Extract to library*. The node
becomes a `ref`, the part gets the node, and **the label stays on the ref and off the part**,
which is exactly the fixture's shape. `KEY` is lifted out of the inline sequence before the
sequence itself becomes `FIELD`, so a part built from a part is arrived at by factoring twice.
The table's order (parts first) still works; this one is the one people use.

**A newline could not be typed into a tree node — fixed.** The last node is `take_through "\n"`,
and the node form took its text raw, so a terminator or a tag could not hold a newline at all
— a text box drops one. The delimiter form and the cards already spell control characters as
`\n`, `\t`, `\\` (`ControlEscapes`); a tag and a terminator now do too. A regex and a
character class keep their own backslashes, which mean something else.

**The tree's scroll area was not reachable from the keyboard — fixed.** Once the tree is wider
than its pane the area scrolls, and the boxes it is drawn with are not tab stops, so nothing
could scroll it without a mouse. The accessibility check failed on stages 6 to 10 until it was
made a focusable region, as the regex form's already is.

**Not fixed: a tall tree draws over what is under it.** Past about a dozen nodes the tree's
boxes overlap the *As regex* and *Library* sections beneath it (stage 14's screen). The tree's
section is not sized to its boxes.

**Where the 122 go.** The tree is about 90 of them: every node is the add button and a dialog —
the kind box, the kind, its one text field, often a label, OK — six or seven actions a node,
fourteen nodes. Lifting the four parts is four actions each. The body is one `value-of`, typed
as one Select with the labels as `$time`, `$host` and so on.

*Walked again 2026-11-19, regex-first (design 47 F): the line is one regex on the template's
match line, the match is made a pattern tree, which explodes it, and the four parts are lifted
from that. Ten stories, **41 actions**. The fixture is now in the explode's spelling, its output
unchanged (design 47 §7.13). And again after design 47 H: the body is one typed line, and the
fixture's literals are `text` cards. **39 actions** (design 47 §7.14).*

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
| 6 | Adds `header_record`, mode `root`, match `delimiter` `\|` | — | built (`Delimiter.ui.xml`); on the match line since design 47 G |
| 7 | Sets its limits `max` to 1 | *"stop matching this template after N matches"* | built (`GuardAndLimits.ui.xml`) |
| 8 | Body: `apply-templates` `$1`, mode `New…` `header_columns` | — | built |
| 9 | Adds `header_column`, mode `header_columns`, match `delimiter` `,` with container `"` to `"` | The container fields sit beside the delimiter | built (`Delimiter.ui.xml`); on the match line since design 47 G |
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

### 6.4a Driven 2026-11-18 — eighteen stories, and a mode called `root`

`ShapeshifterSplitStories`, ending in the keystone: the project the editor sends is the fixture,
its version and one empty body aside. **158 actions.** The sample is the fixture's, emoji
included — the table's "header row and two data rows" is one data row in the fixture.

**Driven mode first** (design 47 B and C). Each mode is added with `+ mode` before anything
dispatches into it; the one the document dispatches into is dispatched from its own pane, and
the other two from cards in the templates that own them, which is a decision and the author's.
The table's `New…` on the card never existed (§6.1a); it is no longer needed.

**The fixture's mode is called `root`, and so is having no mode.** The panel heads the two
`root` and `mode: root`; the strip says `root` and `mode root`; a template's Mode picker offers
`root` for no mode and `root` for the mode, one above the other; and the pane of a second mode
says *the document dispatches into root*. Nothing is wrong and every one of those is
ambiguous. The stories tell the two `+ template` links apart only by their titles.

**Every literal is a `value-of`, because the fixture writes them that way.** A line typed as it
looks makes `text` cards (design 47 A), and the keystone is strict (§3.2), so each of the six
literals is made by choosing the kind and typing a quoted Select — the prologue is five lines
in one quoted string. Whether the fixture should be re-baselined to `text`, which is what an
author would build, is a question for the keystone and not the editor.

**The capture does not offer the name it binds.** `heading` is declared on the document and
bound in `header_column`; the capture dialog's hint lists the declarations of the template it
is in, which has none, so the name is typed from memory. §6.4 expected the dialog to offer it.

**The two identical templates are identical on screen.** `header_record` and `data_record` are
two rows of `mode: root` with the same match, and the limit of 1 that makes one of them the
header is in the workbench, which is not open when the panel is read. §6.4's sharpest finding
stands, now with a screen (stage 15).

**The test row is awkward to drive.** The `if`'s `+ clause` is a label rather than a button, so
the keyboard cannot reach it; the row's variable box takes typed text and does not open its
list when clicked; and its operator box has no name a screen reader could read.

**Where the 158 go.** Four delimiter matches at about ten actions each — the workbench, the
kind, the delimiter, the container — because the match line opens in place only for a regex.
Six `value-of` literals at four or five each. The `variable` and `if` that mean *don't write
an empty record* are 39 between them (§6.4 question b). Declarations, the capture and three
modes make most of the rest.

*Walked again 2026-11-19, after design 47 G put the delimiter on the match line: 146 as
driven. The four delimiters and the limit cost 31 where they cost 42 (design 47 §7.11). And
after H, which made the fixture's literals `text` cards and let a line hold `${…}`: 130
(design 47 §7.14). And after I's three defaults: 121 (design 47 §7.15).*

*What these two rungs found, with every other open finding, is collected in design 47 §7,
with the fixes and proposals that follow and the order to do them in.*

### 6.5 Rung 6 — `progressive_len_records`

The sample: length-prefixed binary records — a varint length, then that many bytes of name. The
`document` template and one other, `root`, with `encoding: "raw"` on the template. *(This said
"no `source` template"; the fixture has always had one. Corrected 2026-11-19.)*

| # | The author does | What should happen | Status |
|---|---|---|---|
| 1 | Settings: name, buffer 1000000, encoding `UTF-8`, ignore errors on | — | built |
| 2 | Gets `input.bin` in front of themselves | **cannot be pasted** — see the finding; chosen as a stream from the sample page's list | built 2026-11-19 |
| 3 | `Add template`, names it `root`, leaves it in the root mode | — | built |
| 4 | Sets the template's own encoding to `raw` | The per-template encoding field | built (`TemplateEdit.ui.xml` `encoding`) |
| 5 | Match kind `parts` | The framed sequence's part list | built (`Parts.ui.xml`) |
| 6 | Part 1: kind `read`, `As` `varint`, `Label` `len` | — | built (`PartEdit.ui.xml`) |
| 7 | Part 2: kind `take`, `Length` from the label `len`, `Label` `name` | The length field takes a label, not just a number (`lengthKind`) | built (`PartEdit.ui.xml`); since design 47 §7.19 the one earlier label fills in, and the field says which there are |
| 8 | Body: `<rec name="$name"/>\n`, typed as one line: a `text`, a `value-of` and a `text` (design 47 H) | — | built |
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

### 6.5a Driven 2026-11-19 — nine stories, and a binary sample

`ShapeshifterLenRecordsStories`, a story per stage, ending in the keystone: the project the
editor sends is the fixture, version aside. **45 actions.** The step-2 blocker is gone: the
story serves the sample page's list one stream and the stream viewer its record, and choosing
the row makes it the sample, as an author would in Stroom.

**The standing question had a worse answer than the marks.** A stream sample reached the engine
as text: the preview fetched the record in the viewer's text mode, decoded in the feed's
encoding, and re-encoded it as UTF-8, so every byte from 0x80 up arrived as something else, and
a binary match counted and read the wrong bytes. `input.bin` happens to be all below 0x80, which
is why nothing had shown it. The preview now reads the record's bytes as stored
(`StreamSamples`), under the View Data permission and the caller's own permissions as the
viewer reads it. The input is shown in the encoding the project declares, or as UTF-8, or a
byte to a character where it is no text (`ShownText`), so the marks and offsets point at the
bytes the engine counted; and text the workbench takes from it goes back as those bytes.
Design 47 §7.17 and §7.18 have the detail.

**The other two questions.** The marks over a binary sample mean what they say now; a length
byte shows as a box before the name it measures. And `Length`'s label `len` is typed: nothing
offers the labels an earlier part of the same match defined. The field's two controls also
shared one label, so neither a screen reader nor the walk could tell them apart; they are
`Length from` and `Length value` now.

## 7. The large samples: a probe, not a test — run 2026-11-12

Separately from the ladder, `apache_httpd` (428KB, 8 templates, 361 body instructions) was
worth putting through the question *can the UI express this at all* **once**, as a finding
rather than a kept story: a 428KB configuration as a story would be slow and would break on any
UI change for reasons that teach nobody anything.

**It was run as a structural probe, not by hand-driving 209 dialogs.** Driving them would have
answered the same question far less reliably and at enormous cost; what the question actually
asks is whether every construct the configuration uses has somewhere to be authored. So every
construct in it was enumerated and held against what the editor offers.

### 7.1 Nothing is missing

| Family | What `apache_httpd` uses | Offered by |
|---|---|---|
| Instructions | `value-of` 142, `choose` 19, `if` 15, `apply-templates` 7, `variable` 2, `put` 1 | `Instructions.FORM_KINDS`, each with a form |
| Transforms | `translate` 173, `replace` 2 | `Instructions.TRANSFORM_KINDS`, edited as their wire form |
| Matches | `regex` 6, `source` 1, `delimiter` 1 | `MatchKind`, all three |
| Reference parts | `capture` 525, `text` 374, `get` 1 | the reference language (`$1`, `"quoted"`, `get(m, "k")`) |
| Conditions | `not` 16, `or` 15, `and` 7, `contains` 7, `eq` 7, `matches` 1 | `GuardClause.Op` for the comparisons; `and` by listing rows |

**So the answer to §7's question is yes** — the configuration is expressible, with no construct
left without a home. That is worth having established, and it was not obvious beforehand.

### 7.2 But about half of it would be typed as JSON

Expressible is not the same as authorable, and the probe's real finding is the proportion:

| Sample | Conditions | as rows | as wire form | Instructions | transforms |
|---|---|---|---|---|---|
| `apache_httpd` | 53 | 22 | **31** | 361 | **175 (48%)** |
| `win_sec` | 39 | 36 | 3 | 90 | 14 (15%) |
| `win_app_xml` | 15 | 14 | 1 | 33 | 4 (12%) |

**206 of `apache_httpd`'s 414 editing acts are raw JSON.** Two separate causes:

- **Transforms are edited as their wire form** by design — `TRANSFORM_KINDS` is documented as
  *"edited as their wire form, listed for the add menu"*. 175 of them here.
- **A condition that is not a clause, or an `and` of clauses, falls back to the wire form.**
  `GuardClause.read` returns null for anything else — which is the panel's signal to show JSON —
  so every `or` and every `not` is typed. Design 44 Q7 ruled the wire form in for exactly this
  reason, *"only where the rows cannot say it"*; the probe measures what that costs on the
  largest real configuration: 31 of 53 conditions, 58%.

The second is concentrated: `win_sec` and `win_app_xml` are 92% row-authorable. It is
`apache_httpd`'s branching that the clause rows cannot say.

### 7.3 What this is evidence for

Two pieces of work, with the probe's numbers attached:

1. **Q20's transform forms** (§8), where 175 wire-form edits in this one sample become forms.
2. **`or` and `not` in the clause rows**, which is not a design 18 question at all and has no
   ticket: 31 wire-form conditions here, 4 across the two `win_*` samples. Worth raising beside
   design 44 §8's Q11–Q14 if the clause editor is opened again.

The probe is not kept. These numbers are the finding, and they are reproducible from the
fixtures by the same enumeration whenever the corpus changes.

### 7.4 The clause rows learnt *or* and *not* — done 2026-11-12

The second of §7.3's two pieces was the cheaper one and was taken first: one editor rather than
thirty forms.

**What the rows say now.** A row can be negated, and the rows are joined by *all* or by *any* —
`holds when [all] of these hold`, one choice above the rows rather than a word between each
pair. The shape was chosen from the corpus rather than from first principles: of 476 conditions,
229 are a bare clause, 115 a negated clause, 79 an *and*, 51 an *or*, and **none nests further**
— no `and(or(…))` anywhere — so per-row joiners would have offered a precedence the model does
not have.

**What it is worth, measured honestly.** Wire-form conditions across the corpus fall **from 234
to 77**. The first number I reported was 168 → 2, and it was wrong: it counted shapes while
assuming every clause's *select* was row-expressible.

**It is not, and that is now the commoner reason to see JSON.** A row's variable must read back
as a name, which `ReferenceJson.writeRefOrName` allows only for a bare declared name or a
counter. A comparison against a bare group (`$0`), a label, a path or an accessor goes to the
wire form whatever its shape. That is what the remaining 77 are, and it is a smaller and
sharper question than the one just answered — worth raising beside design 44 §8 if the clause
editor is opened again.

Two deliberate exclusions: `not(not(x))` stays on the wire form rather than being simplified to
the clause it equals, because rewriting a configuration behind its author is worse than showing
them JSON; and a negation of anything but a clause is wire form too, the rows having one level.

Pinned by `GuardClauseTest` — three new cases for the shapes the rows learnt, and the case that
used to assert *or* and *not* were out of reach rewritten to assert what still is — and by the
`ConditionOrAndNot` story, which opens the composite fixture's condition, finds rows where it
used to find JSON, and changes the join to see the rows follow it.

**A review caught two defects in the first version of this, both worth recording.** The row kept
its own four-field comparison to decide whether a change was worth committing, and the negation
was added to the clause without being added to that — so picking *not* and nothing else
committed nothing, and the next refresh put the box back. The fix was to delete the duplicate
rather than extend it: `GuardClause` is a value whose equality is already every field a row
holds. And the word between the rows is fixed when they are built, so changing the join left
them reading *and* under a picker saying *any* while an `Or` was written; the panel now rebuilds
the rows before telling anyone. Neither showed up in the story until it was asked to change the
join — an editor whose two halves disagree looks right in a screenshot.



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

**Corrected 2026-11-12, by counting instructions rather than names.** The table above counts
occurrences of each word, and two of them are not transforms where they appear: `contains` is a
condition operator, and `substring` turns up outside transforms too. `WireFormCensusTest` counts
the instructions themselves, which is what a form would serve, and the order is different:

| Transform | Instructions still opening as the wire form |
|---|---|
| `trim` | 55 |
| `replace` | 14 |
| `add`, `string-length` | 3 each |
| `decode`, `divide`, `format-date`, `format-number`, `parse-date`, `round`, `substring-after`, `tokenize`, `upper-case` | 1 each |

`translate` is absent because it now has a form (§8.1). `contains` and `substring` are absent
because, as instructions, the fixtures hold none. **So the next form by evidence is `trim`, not
`substring` or `contains`** — which is what the recommendation said before the counting was
done properly.

Two consequences:

1. **Build the five first**, and `translate` first of the five. The gain from a hand-written
   form is concentrated almost entirely in it.
2. **The ladder will not exercise the transforms.** Every body instruction in all six chosen
   samples — `text`, `value-of`, `apply-templates`, `emit-error`, `variable`, `if` — is already
   in `FORM_KINDS`. The transforms live in the migrated DS3 configs: `apache_httpd`, the
   `win_*` family, the `text_00*` family. So the §7 probe is not a curiosity — **it is where the
   Q20 evidence is**, and it should come before the thirty forms are built rather than after.

**The probe has now run (§7), and it sharpens this rather than changing it.** `translate` is
48% of `apache_httpd`'s body instructions on its own, and every one of them is typed as wire
form today. It also found a second gap of the same shape that Q20 does not cover: 31 of that
sample's 53 conditions are typed as JSON because the clause rows cannot say `or` or `not`. If
the question is where a form buys the most, `translate` is first and the clause rows are second
— and the second is cheaper, being one editor rather than thirty.

The conformance test over `OutputNode`'s `permits` list, which Q20 records as owed whichever way
the forms are written, is unaffected by any of this.

### 8.1 `translate` has a form — done 2026-11-12

The first of Q20's thirty, and the one the probe pointed at: 504 translates in the corpus, every
one of them typed as JSON until now.

**The corpus designed the form.** All 504 have exactly one select and a name, none has a short
`to` list, and across every one of them there are only **14 distinct from/to values** — the XML
escaping set. Three of those values are a literal tab, newline and return, which no text field
can carry, so the replacements are spelt with `ControlEscapes`, the helper the delimiter form
already uses for the same reason: one `from = to` a line, with `\t`, `\n` and `\r`.

A translate whose values contain the `=` the lines are split on falls back to the wire form
rather than being cut in half. The corpus has none; a configuration could.

**The ratchet that should have caught this was measuring the wrong thing.** `WireFormCensusTest`
asserted that instructions falling to the wire form stayed at zero, and it did read zero while
504 translates opened as JSON: it asked only whether the form could *spell* an instruction's
references, never whether the form offers that kind at all. Its own method is named
`everyInstructionOpensAsAFormRatherThanAsJson`. It now asks both, and reports 84 — which is the
remaining Q20 work, counted rather than guessed, and a ceiling that may fall and not rise.

Pinned by a round-trip over 276 real translates: if the spelling did not come back exactly,
pressing OK would rewrite every one of them.

**And a review found the hole in exactly that test.** The first version trimmed each side of a
replacement line, and `ControlEscapes` spells a tab, a newline and a return but not a *space* —
so a value that was, or ended in, a space was deleted on the way back. The story's own fixture
showed it: `\n` → `" "` rendered as `\n =  ` and read back as a deletion. The corpus cannot
catch this, all 504 of its translates using the XML escaping set and not one of them edged with
a space, and `replace "," with ", "` is the ordinary case that is. The lines are now parsed by
taking back exactly the one space `replacements()` writes either side of the separator, and the
case the corpus cannot supply is a test of its own.

Two more from the same review, both the same shape — **a form promising what it could not
keep**. `translate` was left in `TRANSFORM_KINDS` as well as `FORM_KINDS`, which the add menu
concatenates, so it was offered twice; and `spellable` opened a form for a nameless translate,
which is legal and means *write the result straight out*, while the form's Name is required — so
the author's only way out of the dialog was to invent a name and silently turn a write into a
binding. A translate the form cannot save now stays on the wire form.

### 8.2 Eight more, in one form — done 2026-11-12

The next form by the census was `trim`, 55 instructions. Counting what `trim` actually *is*
found something better: **eight kinds are one shape** — `trim`, `lower-case`, `upper-case`,
`normalize-space`, `add`, `divide`, `round` and `string-length` are all `(selects, name)`, so
they are one form rather than eight, and together they are 64 of the 84 rather than trim's 55.

Two things the corpus settled that an assumption would have got wrong:

- **The name is optional, and usually absent.** 42 of the 55 trims have none, because a
  transform without a name writes its result straight out instead of binding it. Requiring one —
  which is right for `translate`, all 504 of whose instances are named — would have left those 42
  on the wire form, which is the opposite of the point. Blank is the configuration, not an
  omission, and the round-trip test asserts the nameless case by name.
- **`add` and `divide` fold over several inputs** (design/17 §5), so the selects are a list, one
  a line, and the label says so for those two.

The read-off and put-back live in `SimpleTransforms` rather than eight near-identical arms of the
controller. **The census falls from 84 to 20:** `replace` at 14, then six kinds with one
instruction each.

| | wire-form instructions |
|---|---|
| before any form | 1588 instructions, 84 as JSON |
| after `translate` (§8.1) | 84 |
| after the eight (§8.2) | 20 |
| after the rest (§8.3) | **0** |

**A review found that "one shape" was one shape too few.** The eight share their *fields* and not
their *arity*, and `BodyCompiler` enforces the difference: six read exactly one thing, `divide`
reads exactly two — `a / b` — and only `add` folds. The first version called `divide` a fold,
against the section it cited, and put no limit on the list at all, so a `trim` could be given a
second select, saved, and refuse to compile the next time the project was read. The form is now
shaped **by** the arity — one field for the six, `Left` and `Right` for `divide`, a list only for
`add` — which incidentally fixed a second complaint, that the form asked what the result was
called before asking what it read.

The same review found the round-trip test was not testing the form: it fed the model's own
selects and name straight back into `build()`, never through the text the form holds, which is
the only part that can go wrong. It goes through the text now, `refsOf` was extended so the
file's central invariant stops skipping the eight, and the dialog has an OK story rather than a
Cancel-only one.

### 8.3 All thirty — done 2026-11-12

Ruled by the owner: forms for every transform, including the fourteen the fixtures use once or
not at all. **`TRANSFORM_KINDS` is now empty and the census is zero**: all 1,588 instructions
across the fixture projects open as a form.

Thirty kinds turned out to be five shapes, which is why this was an afternoon rather than a
project:

| Shape | Kinds |
|---|---|
| selects and an optional name, shaped by arity | 15 |
| select, one word, name | 8 — `string-join`, `tokenize`, `substring-before`, `substring-after`, `starts-with`, `ends-with`, `contains`, `format-number` |
| replacement lists | `translate` |
| a form each | `replace`, `substring`, `format-date`, `parse-date`, `decode`, `call` |

Two things were extended rather than worked around. The generic instruction view grew a **fifth
text field**, because `parse-date` genuinely has five (select, pattern, time zone, relative-to,
name) and four was an accident of what had been needed so far; and it grew a **choice box**,
because `decode`'s codec is a closed set and deserves a picker. The codec list is taken from
`Codec.values()` lowercased — the wire spelling — so it cannot drift from the enum.

**What the wire form is now for.** Not ordinary work: it is the escape for a construct the model
grows before the forms catch up, and `WireFormCensusTest`'s ceiling of zero is what says when
that has happened.

**A review found eleven things, and the pattern in them is worth more than the list.** Three
were serious. **Seven kinds were listed as having forms and had none** — worse than leaving them
off, because an unlisted kind at least gets a JSON skeleton while these opened an empty box that
OK could not save; the census read zero only because no fixture uses any of the seven.
**`required()` trimmed every significant value**, so a `tokenize` splitting on a space could
never be saved and a marker of `" ("` was silently changed — the whitespace bug §8.1 records
fixing for `translate`, standing in nine more places. And **emptying `TRANSFORM_KINDS` deleted a
capability**: `string-join` takes several selects, the form offered one, and the wire form that
had been the only route to a multi-value join was gone.

The common thread is not carelessness about any one case but a habit: **a bug found in one place
was fixed in that place and not swept for siblings.** Whitespace trimming, forms that promise
what they cannot save, kinds listed in two places at once — each was found once, fixed once, and
left standing elsewhere until the next review found the next instance.

What changed as a result, beyond the eleven fixes: `EveryFormKindHasAFormTest` reads the
`showKind` and `write()` switches and holds them against `FORM_KINDS`, so "every kind has a
form" is checked rather than claimed; `BodiesTest` holds the two kind lists disjoint; and the
values a form must keep exactly go through one pair of helpers rather than being trimmed by the
one that was right for names.

## 9. What to do next

1. ~~Write rungs 2 to 6 as step tables~~ — **done 2026-11-12**, §6. Written from the source, so
   every row is *buildable as far as reading can tell*; none is verified in the browser.
2. ~~Re-baseline the three lax-by-version fixtures to strict~~ — **done 2026-11-12**, and not by
   editing them: design 11 §10.3 took the version out of the dispatch default, so `library_parts`,
   `text_019_single_line_split` and `progressive_len_records` say nothing and are therefore strict.
   The two without a root template were re-baselined for a different reason (§6.3).
3. ~~**Automate rung 1 only**~~ — ruled 2026-11-12, **finished 2026-11-17**. All 26 steps are
   driven, as 26 stories with a screenshot each (§5.9a), and the keystone holds. The reserved
   decision — whether driving the rest was worth it — was answered by doing it: it turned up the
   body-cards finding in §5.9a, which three months of stories over a built fixture had not.
4. Walk the remaining five against the running workbench as their stories are written — **rungs
   2 and 3 done 2026-11-17 (§6.1a, §6.2a), 4 and 5 done 2026-11-18 (§6.3a, §6.4a), 6 done
   2026-11-19 (§6.5a); every rung is walked** — settling
   what only the browser can say: what the UI says about a mode with no dispatch site (§5.4),
   whether a pattern part can be lifted out of a pattern already written (§6.3 — it can, §6.3a), and whether the
   marks mean anything over a binary sample (§6.5, which also needs the stream fixture ruled
   there). §5.1 and §6.3's first question are already answered from the source.
5. ~~Run the §7 probe on `apache_httpd` before building Q20's forms~~ — **done 2026-11-12**,
   §7. The configuration is expressible; 206 of its 414 editing acts would be typed as JSON.
   Q20's forms and the clause rows' missing `or`/`not` are what that is evidence for.

**What the walkthroughs turned up, as design questions rather than steps** — ~~three~~ four,
now that rung 1 has been driven. None is about a missing form; all are about what the editor
*tells* the author. **Moved to [44-the-workbench-kind-sample-library.md §8](44-the-workbench-kind-sample-library.md)
as Q11 to Q14, 2026-11-12, since ruled**, because design 44 owns the panel and the strip and is where
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

## 10. What the walkthroughs cost the author — measured 2026-11-17

Written after rung 1 was driven a step at a time (§5.9a). These are **costs and questions, not
proposed fixes**: the form that worked last time was a question with evidence attached, which is
what Q11 to Q14 became in design 44, and what kept those rulings honest. Each is marked with the
design that owns the surface and would rule it.

**The count.** Rung 1 takes **76 author actions** — clicks, typed values, picks from a box —
counted off the stories that drive it, to produce three templates, one mode, two regexes and
eleven body cards. That is the *simplest* of the six samples. Where they go:

| | acts | |
|---|---|---|
| adding body cards | 28 | 7 cards × (`+ instruction`, pick the kind, type one value, OK) |
| picking from a selection box | 10 | 5 × (open the box, choose) |
| everything else | 38 | naming, renaming, the two patterns, the reorder, selecting templates |

### W1 — every card is a modal round trip (design 18 §5.6)

`+ instruction` → pick a kind from a menu → type one value in a dialog → OK. Four acts for a
`text` card whose entire content is `">`. Seven cards is 28 of the 76, and the body is where an
author spends most of a rung. The question is whether a card whose form is one short field needs
a dialog at all, or whether the list could take the value in place.

### W2 — five cards for one line, and nothing leads to `element` (design 18 §5.6, Q20)

§5.6 and §5.7 both ask "whether the author is led there". Driven, the answer is no: steps 17 to
19 build ` <pair key="`, `$1`, `">`, `$2`, `</pair>` as five separate cards, and the add menu
lists kinds without hinting that `element` plus `attribute` says the same thing in one or two.
The same shape recurs in rung 2 (`<w>`, `$0`, `</w>`, twice) and rung 3 (`<code>`, `$1`,
`</code>`), so it is not a `strict_kv` accident.

This is the largest single cost in the count, and it is the one place the editor lets an author
build the laborious thing without mentioning the short one.

**Corrected 2026-11-17 by design 47 §1.1 and §2: the short one is not `element`.** Counted, the
pair line as `element` and `attribute` is 16 actions against 20, and it changes what is escaped.
The short way is a single `value-of` whose Select joins literals and references — which rungs 4
to 6 already use for every line they write, and which is available today behind a spelling that
is hostile to markup. Design 47 proposes from there.

### W3 — the envelope is ordered and kept in step by hand (design 18 §5.6)

A card lands at the end of the body, so step 13's `<records>` has to be moved up past the
dispatch it wraps. *(2026-11-18: no longer. The toolbar over the rows opens a new card above
the selected one, so the opening tag is put in where it belongs and the walkthroughs do that
— design 47 §3 E. The rest of this finding stands.)* The opening and closing tags are then two independent `text` cards: rename one
and nothing says the other is now wrong. `element` would close this too, which is why W2 and W3
may be one question rather than two.

### W4 — order is priority, and only a tooltip says so (design 44)

**The one finding where a mistake produces silently wrong output rather than extra work.** Order
within a mode is dispatch priority. Put `junk_line` above `pair` and it matches every line: the
project emits nothing but warnings and no error is raised, because nothing is wrong — the author
asked for exactly that. The panel shows the order and `Up`/`Down` change it, but only the move
buttons' own tooltips name what the order *means*, and only on hover.

With two templates an author works it out. Rung 2 has five in one mode and turns on `=` versus
`=>` resolving correctly; rung 5 has two templates that differ only in position and a match
limit (§6.4). And §6.1 and §6.2 sharpen it further: under `lexer` the order is only a tie-break,
under `classify` it means nothing at all, and which rule is in force is chosen by a picker on a
card in a different pane. **The same ordered list in the same panel carries three meanings.**

**Ruled and built 2026-11-17 — and cut back the same day, because the finding as first written
was half wrong.**

What was claimed: that an author cannot tell the panel's order is priority order. That does not
survive contact. An author writing a grammar knows a list of alternatives is ordered — anyone
who has written a lexer, a firewall ruleset or a DS3 config does — and a panel explaining it is
chrome. The evidence for the claim was thin: a scripted walkthrough never makes the mistake, so
nobody was observed being confused, and the scenario used to justify it (`junk_line` dragged
above `pair`) is contrived, since adding it second already puts it second. §5.7's own amendment
had half-retracted the finding already by noting the move buttons name the order on hover.

What survives is the half §6.1 actually sharpened: **the strategy is invisible.** Under `lexer`
an author who knows order matters will move a template up to fix a problem they do not have, and
nothing will change — reassuring or baffling depending on whether the screen said `lexer` was in
force. It is chosen on an `apply-templates` card that may belong to another template, in another
pane, and appeared nowhere else.

So what was built says **the fact, not what it implies**, in two places:

- **The mode's heading** — *dispatched with lexer* — in the slot that already carried "nothing
  dispatches into this", so a heading still says at most one thing.
- **The `apply-templates` card's note line**, beside the match count: *no matches · lexer*.
  Visible in the strip without opening anything, and here the fact cannot be ambiguous — a site
  has one strategy, where a mode could in principle be dispatched from several.

**Only where it is not `STRICT`**, which is the reading everyone brings, so most projects show
nothing at all; and nothing where sites disagree, because there is then no fact to state. The
five explanatory sentences, the "dispatched more than one way" wording and the
two-templates-or-more rule are all gone with the claim that motivated them. *Upheld 2026-11-23: design
48 had put a strict mode's order back on its heading, without this set against it, and took it
out again (design 48 §7, "Against W4").*

The fact is `Modes.dispatchOf`, resolved with `Dispatch.effective` — the engine's own answer,
because a site naming no strategy takes the project's — and pinned by `ModesTest`. It was right
either way and did not change. Rungs 1 and 2 are the `STRICT` and `LEXER` cases driven end to
end: rung 1 asserts the panel stays *quiet*, rung 2 that it speaks.

**And the slot it went in was never readable.** `.template-panel__mode-note` sat on one line with
the mode's name and ellipsised: at the panel's own width *"nothing dispatches into this"* rendered
as *"nothing dispatc…"*. That note was ruled in §5.4, is asserted by both walkthroughs, and had
never been legible — the stories pass because a query reads the DOM and not the pixels, which is
exactly the blindness that hid the body's cards (§5.9a). The heading now wraps and the note takes
the width. **Twice in two days, something the screenshots show and the assertions cannot.**

### W5 — the rename that nothing confirms

Already raised as design 44 Q11 (§5.9), ruled and built 2026-11-23: the row says
`document · source`. Noted here only because driving it is what
found it, and it recurs in every rung that renames the root.

### Already fixed

The body's cards were clipped to 5px at 1280×720 and had never appeared in a screenshot
(§5.9a) — a one-attribute fix once seen. Recorded here because of how it was found: not by three
months of stories asserting classes and text, all of which were present, but by photographing an
author's own sequence once.

### What is owed before these are designed from

Rungs 2 and 3, for the reason §4 gives for keeping them apart: each carries a different wrong
belief about what the panel's order means, and W1 to W3 are costs whose *cause* is still
ambiguous. If the card treadmill hurts as much in rung 5, where the output is `variable` with
nested bodies and `if`/`exists` and `element` would not help, then the answer is the editing
surface rather than the menu. Designing from rung 1 alone would pick one without knowing which.
