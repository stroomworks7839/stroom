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

Three traps, all found while reading the fixtures, each of which would break a byte-for-byte
comparison:

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

### 3.5 Where the comparison runs — open

The story runs in the browser; the fixture is a test resource on disk. Three ways, and I have
not picked one:

- **Embed the fixture JSON as a story constant** (the stories already hold large JSON
  constants) and add a small JVM test asserting the constant still equals the file. Cheapest,
  and the drift is caught by the test rather than trusted not to happen.
- **Serve the fixture through the story's REST stub** on a path of its own, so the story fetches
  it. No duplication, slightly more machinery.
- **Assert on the captured body in a JVM test** driven from the Gradle task. Keeps parsing out
  of the browser entirely, but crosses a boundary the harness does not currently cross.

Recommendation: the first, because it is the only one that costs nothing to try and its one
weakness is closed by a five-line test.

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

Rungs 2 and 3 are small deltas from rung 1 and may collapse into one walkthrough about dispatch
strategies; I have kept them separate for now because they are separate ideas and the cost of
finding out is one paragraph each.

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

### 5.2 Get a sample in front of the author

| # | The author does | What should happen | Status |
|---|---|---|---|
| 4 | Selects `Data` | The sample page: `Sample data from`, and a paste box | built (`SampleSourcePicker.ui.xml`) |
| 5 | Pastes a few `key=value` lines and a junk line | The input pane shows them | built |

Deliberately before any template. An author matching a record wants the record in front of them
while they write the pattern, and the input pane is where the marks appear. A walkthrough that
built templates first would be testing a flow nobody uses.

### 5.3 Match the whole input — the root template

| # | The author does | What should happen | Status |
|---|---|---|---|
| 6 | `Add template` in the panel toolbar | A template, selected, its strip shown | built (`TemplatePanelController.java:109`) |
| 7 | Names it `source` | — | built (`TemplateEdit.ui.xml`) |
| 8 | Sets its match kind to `source` | The match chip reads `source`; the kind's body is a sentence saying what it means | built (design 44 §1) |
| 9 | Leaves its mode as the root | — | built |

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

### 5.8 Close

| # | The author does | What should happen | Status |
|---|---|---|---|
| 27 | Saves | The save request's body parses to the same model as `strict_kv/project.json` | the keystone assertion, §3 |

## 6. Rungs 2 to 6 — not yet stepped

These are the shape of each walkthrough and the question it exists to ask. The step tables are
the next piece of work, and each needs a pass against the running workbench rather than a pass
over the source, because what they are testing is exactly what reading the source cannot tell
us.

Rungs 4, 5 and 6 take lax today, their fixtures being versions 1, 3 and 1, and the walkthrough
will build them strict. §3.2 has measured that all three still match their goldens under
strict, so each is re-baselined rather than dropped and the ladder is unchanged.

**Rung 2, `lexer_tokens`.** Five templates in one mode, `dispatch: "lexer"` at the
`apply-templates` site, and `=` versus `=>` resolving by longest match rather than by position.
The fixture's own output proves the semantics: `assign` (`=`) is listed above `arrow` (`=>`),
and the output is `<arrow/>`, so under `lexer` the longest match beats the earlier template.
*The question:* the dispatch picker exists (`InstructionEdit.ui.xml:40`) and its help says
"blank takes the project's" — but does the author understand that choosing `lexer` has just
demoted the panel's template order from *the* rule to the **tie-breaker** (`Dispatch.LEXER`:
"the longest match wins, ties go to list order")? Two different rules for which template wins,
chosen in a pane other than the one that displays the consequence.

**Rung 3, `classify_alerts`.** `dispatch: "classify"` — "one pass, every matching template runs,
nothing consumes" (`Dispatch.CLASSIFY`). *The question:* under `classify` the panel's ordered
list means nothing at all — there is no priority and no tie to break, every template that
matches runs. So the same list of templates in the same panel carries three different meanings
depending on a picker in another pane: priority under `strict`, tie-break under `lexer`, and
nothing under `classify`. Does anything on the screen say which is in force?

**Rung 4, `library_parts`.** A top-level `patterns` map of named parts, referenced by `ref` from
a match. The panel has the affordance — *"Add a pattern part: a tree defined once, named with
ref from any template"* (`TemplatePanelController.java:122`) — so design 44 §3 is built. *The
question:* the walkthrough's natural order is to write the line's pattern first and then lift
`TIMESTAMP` out of it once it repeats. Is that extraction possible, or must a part be made
before it can be used? Lifting after the fact is how a person actually factors.

**Rung 5, `text_019_single_line_split`.** The heavy one, and the one that will find most. A
`heading` list declared and captured into on the header row, then read back on each data row by
`[matchCount()]` to pair the nth value with the nth heading; a `record_body` scalar built in a
`variable` and tested with `if`/`exists` so empty records are not written; delimiters with
`container_start`/`container_end`. *The questions:* (a) `match_index` has no form field anywhere
in the editor — it is spelt in the reference mini-language, `[matchCount()]`, documented in
`InstructionEditController.java:271`. Typing a function call into a text field to express *the
nth of these goes with the nth of those* is the single least discoverable thing in any of the
six samples. (b) The `variable`-then-`if`-`exists` idiom exists to avoid writing an empty
`<record>`. It is three nested instructions for one intention, and it is the pattern every
real-world config will need.

**Rung 6, `progressive_len_records`.** A parts match — `read as varint` into `len`, then
`take length=len` into `name` — with `encoding: raw`. `PartEdit.ui.xml` has `Kind`, `As`,
`Label`, `Length`, `Absolute`, so the form is there. *The question:* there is no sample input a
person can read. The input pane is built for text; this input is binary. Whether the marks, the
capture tints and the preview mean anything at all over a binary sample is unknown, and it is
the one rung where the whole left-hand half of the screen may simply not apply.

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

1. Walk rung 1 against the running workbench and settle the two open questions in §5.1 and
   §5.4 — what a new document contains, and what the UI says about a mode with no dispatch site.
2. Re-baseline `library_parts`, `text_019_single_line_split` and `progressive_len_records` to
   strict at the current version — §3.2 has measured that all three still match their goldens,
   so this is recording a fact rather than taking a risk.
3. Write rungs 2 to 6 as step tables, each verified in the browser as it is written.
4. Automate rung 1 only, as a proof of the §3 keystone, and decide the rest from what it costs.
5. Run the §7 probe on `apache_httpd` before building Q20's forms.

Two pieces of work this design turned up that are not the walkthrough and should not wait for
it:

- ~~Take the `Store` alias out~~ — **done 2026-11-12**, §3.3; §3.4 is the wider audit for the
  same class of artefact, and leaves one open decision: the version-5 substring base.
- **Decide `xml_to_json`'s explicit `"dispatch": "lax"`** (§3.2): a version-5 configuration of
  ours asking for the migration path. Either the reason is recorded or the line goes.
