# Scenarios: the supervisor's executable specification

*Drafted 2026-09-17. Companion to `01-intelligence-in-the-pipeline.md`, whose section numbers and
rulings (A1–A29) this document cites. Status: proposed; §7 lists what is owed a decision. Brought
into line with design 01's A23–A29 and its 2026-09-17 revisions the same evening.*

## 1. Why scenarios, and what one is

Design 01 describes a stage that routes a stream, learns a fragment when it must, scores what it
learned, promotes it or gives up, and rewrites its routing table. None of that yet runs end to end:
the Shapeshifter AI document, the A21 dialogue, two step runners, the fragment writer, the scorer SPI
with three scorers, `Router`, `Quarantine`, `RegressionSet` and `Stage` exist (§6.1); the remaining
scorers, the supervisor element, the runtime tables, durable attempts and the Supervisor view do not
(design 01 §12). The order those are built in matters less than the thing that decides whether they are right.

A **scenario** is that thing. It is a complete, deterministic run of one stage over given input with
a simulated model, asserting on everything the design says should happen. Scenarios are written
before the machinery, and each piece of machinery is built to make the next scenario pass. When the
supervisor element finally exists, the same scenarios run against it inside a real pipeline. The
scenarios are the specification; the design prose is the rationale.

One scenario has four parts:

| Part | What it is | Where it comes from |
|---|---|---|
| **Document** | A `ShapeshifterAiDoc`, built with the builder, exactly as an operator would configure it — scorers with weights and parameters, promotion floor, allowed elements, routing table as it stands before the run | `ShapeshifterAiDoc.builder()` in the scenario; nothing hand-written in JSON |
| **Input** | One or more streams: the data, plus the metadata a stream carries — feed, type and the receipt headers (`Format`, `System`, …) that A22 can route on and that the learning key (A29) may include | The `TestDS3` corpus for extraction; XML records for transformation; hand-written where the corpus has no case |
| **Script** | The simulated model: an ordered list of *expected question → reply*. Every question the dialogue asks is checked against the next expectation, and the reply is what the script says. A question the script did not expect, or a script with lines left over, fails the scenario | Replies are real Data Splitter and XSLT documents in test resources, including deliberately bad ones |
| **Expectation** | What must be true afterwards: the questions asked and in what order; what each carried; the fragment written; the output produced; the score of every scorer; whether promotion happened; the routing table after; what was sentinelled and what the ledger holds; the transcript that would be audited | Assertions in the scenario |

The script is what makes a scenario deterministic and what makes it a specification: it says not only
what the model answers but *what the supervisor must have asked* to get there. A scenario in which the
transform question fails to carry the parser's real output (A21 step 2) fails at the script, before
any output is compared.

## 2. Two tiers, one set of scenarios

The same scenarios run in two places, because "the learning loop is right" and "the learning loop is
wired into a Stroom pipeline correctly" are different claims with different costs.

**Tier 1 — the stage in the module.** `stroom-shapeshifter-ai`'s own tests: no database, no node,
in-memory document stores, the step runners in place of `PipelineFactory`. Runs in seconds. This is
where the dialogue, scorers, promotion gate, routing decision and quarantine are specified and where
almost every scenario lives. It is honest about one thing: the fragment is run by the step runners,
which drive the Data Splitter and Saxon directly, so an element outside `DSParser`/`XSLTFilter` and
any Stroom-specific behaviour of those elements (XSLT functions, reference data, `xsltNamePattern`)
is out of reach here.

**Tier 2 — the stage in a pipeline.** In `stroom-app`, extending `AbstractProcessIntegrationTest` as
`TestXMLTransformer` does: a real database, real stores with explorer nodes, the real
`PipelineFactory` building a pipeline that holds the supervisor element, real `SchemaFilter` against
the event-logging content pack, output to a real stream. Runs in minutes. This is where the claims
Tier 1 cannot make are made: that the learned fragment merges and runs as a pipeline; that the
supervisor element routes, captures and emits; that bindings land in output-stream metadata (§7.3
rule 3); that the sentinel is a real error stream and the ledger a real row (A26). A handful of scenarios run here —
the happy path, one failure, one promotion — not the catalogue.

The scenario definitions — the script DSL, the simulated model, the document builders, the input and
reply resources — live in the module's `testFixtures` source set (Gradle `java-test-fixtures`, which
`stroom-query-language` already uses), so both tiers consume one definition and cannot drift.

## 3. The simulated model

```java
Script.of()
    .expect(chain().allowing("DSParser", "XSLTFilter").withKey("Format", "CSV"))
        .reply("DSParser -> XSLTFilter")
    .expect(configuration("DSParser").withInput(sample))
        .reply(fenced(corpus("001_csv_with_header").configuration()))
    .expect(configuration("XSLTFilter").withInput(corpus("001_csv_with_header").expectedRecords()))
        .reply(fenced(resource("csv-logon.xsl")))
```

An expectation is a predicate over the `Question` plus a description; a mismatch fails with both the
expectation and the actual question printed. `Script` implements `Advisor`. It records the transcript
it saw, so a scenario can also assert on the whole conversation afterwards — what feedback the third
question carried, that the chain question was not re-asked.

Multi-round scenarios are just longer scripts. The model's second answer is scripted knowing the
first will fail, and the expectation on the second question asserts *why* it was re-asked: the
feedback must name the compile error, or the scorer that lost the marks, or the assertion that
failed. That is the design's promise in §10 — feedback goes to the step that failed, with the
diagnostics — turned into a check.

The script is deliberately dumb. It does not look at the question to decide what to say; it says the
next line. Anything cleverer would be a model, and the point is that the test's outcome is fixed by
its author. One exception, and it keeps the rule's point: since A31 a dialogue may ask what one record
is and what each kind of record becomes before it asks for any configuration, and a script that had to
spell those out would restate its own splitter and stylesheet by hand. `Structure` answers them
*from* the configurations the script will give — it runs the scripted splitter over the sample, runs
the scripted stylesheet, and returns the event at the record's position, or `none` — so the answers are
still fixed by the author, derived rather than typed, and a scenario about the split or the target
scripts those lines itself.

## 4. What a run does

A Tier 1 run is one call, `Stage.run(policy, streams)`, over machinery that mostly does not exist
yet. Spelling it out fixes what has to be built:

1. **Route.** For each stream, compute its learning-key value — feed and type by default, with the
   shape signature of the stage's *input* where the document's key includes it (A29) — and evaluate
   the routing table's selectors in order against the stream's meta fields, headers and signature
   (A22). A match binds the fragment. A match on a reserved rule, or a shape the ledger records as
   given up, is a sentinel. No match is an unknown shape: the variants already bound for the same
   feed and type are tried first (design 01 §6), and one that clears the floor is bound
   *provisionally* and handles the stream with no question asked; only if none fits, and
   `learningMode` is `AUTOMATIC`, does the stage learn — with `DISABLED` it is a sentinel.
2. **Learn.** Split the shape's records into a learning sample and a held-out sample (A14). Run the
   document's plan (A33: its steps — direct, target-first, or its own — in its words) over the
   learning sample. Score each candidate with the document's scorer set. Feedback to the failing step
   carries the scorer's diagnostics. Stop at the step's candidate limit (`maxAttempts` unless the step
   says otherwise) or at a candidate whose gates all pass and whose weighted total meets every
   threshold. A bound shape whose rolling per-record score has fallen below `relearnThreshold`
   learns the same way, its incumbent serving meanwhile (A29). In `DEFERRED` mode the attempt is
   recorded `AWAITING_MODEL` and the stream sentinelled; the worker runs the dialogue later (A28).
3. **Judge.** Score the candidate on the held-out sample. Promote if the weighted total reaches the
   floor (A15), every gate passes, and — where an incumbent is bound — the candidate is not worse
   than it on the held-out sample nor on the regression set (A18). Otherwise keep the incumbent, or
   give the shape up if there is none. Where the shape has too few records for a held-out split, a
   candidate that clears the floor on what there is is bound *provisionally* — it handles the stream
   and waits for held-out records (A5, design 01 §6) — rather than left unbound.
4. **Write.** On promotion: write the fragment and its documents (new documents, §7.3 rule 1), append
   a rule whose selector is the document's learning key (A29) to the routing table unless a pinned
   rule already covers the selector — as a *draft* in review mode (A25) — record the promotion time and score, add the accepted records to the
   regression set, and release the shape: clear its ledger entry and request reprocessing of the
   inputs it named (A12, as §5.2 restates it — nothing was held; a draft releases nothing until
   Approve). Save the document.
5. **Emit.** Run the bound fragment over the stream and produce output, with the bindings that
   produced it — provisional or not — recorded alongside (§7.3 rule 3); or, for a shape the stage
   will not process, write the sentinel: an `ERROR` naming the shape, the candidate scores and the
   reason, and a ledger row (A4 as restated).

In Tier 1 the streams, sentinels, ledger, reprocess requests and regression set are in-memory
records that the scenario inspects. In Tier 2 they are what the supervisor element does inside a
pipeline: error streams, rows in the A26 tables, and real reprocess filters.

## 5. The scenario catalogue

Ordered by what each needs built; each one is unlocked by the machinery the previous one forced.
"Corpus" is `stroom-pipeline`'s `TestDS3`.

| # | Scenario | Given | Script | Then | Needs |
|---|---|---|---|---|---|
| 1 | **Learns a CSV feed** | Document: default; scorers Compile (gate), Input coverage, Yield. Stream: corpus 001, `Format: CSV` | chain, DS3, XSLT — all right first time | 3 questions in order; transform question carries the parser output; fragment `Source → DSParser → XSLTFilter`; output equals golden events; routing table gains a `Feed AND Type` rule (the default learning key, A29); nothing sentinelled | Scorecard, yield scorer, routing decision, held-out split, promotion, table write |
| 2 | **Header names the parser** | Stream `Format: JSON` with a JSON body; learning key `Feed AND Type AND Format`; allowed elements include `JSONParser` | chain reply `JSONParser -> XSLTFilter` | chain question carried the `Format` value, because it is in the key and shown means bound (A29); no configuration question for the parser; fragment has `JSONParser` with no document; the learned rule binds on `Format` too | `JSONParser` step runner |
| 3 | **Compile failure, then success** | as 1 | DS3 = corpus `008_invalid_xml_FAIL`, then corpus 001 | 4 questions; 3rd is DSParser again with the compile diagnostic and the previous configuration; chain not re-asked | already built (`TestDialogue`); re-homed as a scenario |
| 4 | **Discarded input, then coverage fixed** | scorers add Input coverage threshold 0.9 | a DS3 that drops the header line and half the fields, then a full one | re-ask carries coverage score and the uncovered ranges; second passes | coverage as a scorer with feedback, not only a measurement |
| 5 | **Degenerate transform is refused** (§8.3, A16) | scorers add Schema conformance (gate) and Extraction quality (gate, required `EventSource/User/Id`) | XSLT that emits the skeleton + `Unknown` + every field as `Data`, then a real one | first candidate validates (schema gate passes) but fails extraction quality with typed-element ratio and `Unknown` rate in the feedback; second promoted | schema-conformance scorer over event-logging pack; extraction-quality scorer |
| 6 | **Business rule** | scorers add Business rules: "interactive events name the user" | XSLT that omits `User/Id` on logons, then one that includes it | feedback names the assertion; second promoted | business-rules scorer |
| 7 | **Below the floor is not promoted** | `promotionFloor` 0.95; Yield threshold 0.5 | XSLT that drops every other record (yield 0.5) three times | abandoned at the candidate limit; shape given up — sentinel and ledger row; routing table unchanged; transcript has 5 questions; the last feedback shows yield 0.5 against expected 1.0 | ledger; abandonment path |
| 8 | **Not a regression** (A15, A18) | routing table already binds v1 (score 0.90); regression set holds 10 accepted records | a v2 that scores 0.97 on the learning sample but 0.85 on held-out | v1 kept; v2's documents not written; transcript audited | held-out judgement; regression set |
| 9 | **A better candidate replaces the incumbent** | as 8 | a v2 that scores higher on held-out and on the regression set | rule rebound to v2; v1's documents untouched; promotion time and score updated | table rewrite semantics |
| 10 | **Pinned rule is never rebound** | as 9 with the rule pinned | as 9 | v2 written? no — no learning is attempted for a pinned, matched rule | pin honoured at routing |
| 11 | **Given-up shape does not consult the model** | shape given up by scenario 7 | *empty script* | stream sentinelled; zero questions | ledger consulted before learning |
| 12 | **Learning mode disabled** | `learningMode: DISABLED`, no matching rule, no bound variant for the feed and type | *empty script* | sentinel; zero questions | §11 opt-in |
| 13 | **Promotion releases the quarantine** (A12) | a shape in the ledger with two input streams recorded against it; then a run that learns it | as 1 | the ledger entry is cleared and a reprocess request names exactly those two inputs; nothing was held — the earlier runs produced error streams naming the shape | ledger; release as a reprocess request (Tier 1 records the request; Tier 2 creates the filter) |
| 14 | **Too few records to judge** (A14) | `minRecordsPerShape` 10; stream of 3 records | as 1 | learned; the candidate clears the floor on the 3 records so it is bound *provisionally* and handles the stream, with the binding marked provisional in the output; not promoted; attempt `PROVISIONAL`; a later stream of 10 records supplies the held-out split and promotes it | provisional binding (A5, design 01 §6) |
| 15 | **Every budget question** | `maxAttempts` 1 | one bad reply | abandoned after one question, reason names the step | already built |
| 16 | **Model reply is not a document / not a chain** | | prose, then a document | refused with feedback | already built (`TestDialogue`); re-homed |
| 17 | **Fragment with a destination is refused** | routing table hand-edited to a full pipeline | — | the save fails naming the element | already built (`TestFragmentCheckImpl`); Tier 2 makes it real |
| 18 | **(Tier 2) Learns a CSV feed in a pipeline** | as 1, but the document, feed and streams are real content and the supervisor element sits in a real pipeline | as 1 | output stream equals golden; bindings in the output stream's meta; fragment opens in the explorer under the feed's folder | supervisor element, `PipelineFactory` harness (§12 items 1, 2, 4), bindings metadata (item 7) |
| 19 | **(Tier 2) Degenerate transform in a pipeline** | as 5 | as 5 | as 5, with the real `SchemaFilter` doing the validating | as 18 |
| 20 | **(Tier 2) Sentinel is an error stream and a ledger row** | as 7 | as 7 | no output stream; an error stream with one `ERROR` naming the shape, the candidate scores and the reason; a `shapeshifter_ledger` row naming the input's meta id; nothing held anywhere | as 18, the A26 tables |
| 21 | **Two supervised stages: extract, then transform** (design 01 §3's own picture) | Two documents, both with the signature in their learning key. *Extraction*, at the source so its variants replay per stream (A1 revised): allowed `DSParser`/`JSONParser`/`XMLParser`, scorers Compile (gate), Input coverage, Yield per line. *Transformation*, after the parser so per record: allowed `XSLTFilter` only, scorers Compile (gate), Schema conformance (gate), Extraction quality (gate), Yield per record. Stream: corpus 001 with `Format: CSV` | stage 1: chain, DS3. Stage 2: XSLT only — no chain question, one allowed element | Stage 2's input is stage 1's `records:2` output with the headers carried through; stage 2's signature is the XML element skeleton of its input record, stage 1's the text one (computed on each stage's input, design 01 §4); stage 2 learns on a seeded shuffle of the records and is judged on the held-out records, rebuilt as `records:2` documents; each document's routing table gains its own rule; the final output equals the golden events. A second stream binds stage 1 without learning while stage 2 is still learning, and a stage-2 abandonment gives the record shape up without re-asking stage 1 | records split and rebuild; a stages runner; for the full form, the stage-2 scorers of scenarios 5–6; the structural form runs with Compile + Yield alone |
| 22 | **Review mode: a draft waits, Approve promotes** (A25) | document `promotionMode: REVIEW`, otherwise as 1 | as 1 | rule appended as a draft; a second stream of the shape is *not* bound, produces an ERROR naming the draft rule, fragment and shape, and enters the ledger with no question asked; Approve — from the Routing tab or the Supervisor view (A28) — makes the rule active, sets promotion time, and issues a reprocess request for the ledger's inputs; Reject leaves the shape given up with the reason recorded | `promotionMode`, `draft`, router skipping drafts, the ledger, approve/reject operations |
| 23 | **Error mode after a streak** (A24) | document `errorModeAfter: 2`; two streams of different shapes | two abandoned attempts | after the second, the feed is in error mode; a third stream produces a fatal error stream and *no question*; reset from the Supervisor view's status strip closes it and the third stream learns normally | `shapeshifter_feed_state`, fatal error output, reset (A24, A28); half-open retry as a variant |
| 24 | **AI review flags a dishonest transform** (A23) | scorers add AI review (sample rate 1000/1000, threshold 0.7); a promoted fragment that swaps logon and logoff | judge replies scoring 0.2 with a critique | the sampling job writes a finding; the shape is marked for relearning; the next attempt's question carries the critique as feedback; promotion was never blocked by the judge alone | `AI_REVIEW` scorer, the sampling job, `Critique` question, relearn trigger |
| 25 | **An existing binding fits a new shape** (design 01 §6) | key includes the signature; a rule binds v1 for shape X; a stream of shape Y arrives | *empty script* | v1 is run over Y and clears the floor; Y bound to v1 provisionally, zero questions; once Y has `minRecordsPerShape`, promoted with held-out satisfied by construction | bound-variant trial before any call |
| 26 | **A reserved rule gives the shape up** (design 01 §3) | first rule `Feed = f` with no fragment | *empty script* | sentinel with reason *reserved*; ledger row; zero questions | reserved-rule routing |
| 27 | **A falling score triggers relearning** (A29) | default key; a rule bound; `relearnThreshold` 0.8; a stream in which a second record kind fails schema conformance for 30% of records | *empty*, then as 1 | no new shape; the failing records go to the error stream and not the ledger; the shape's rolling score falls below 0.8 and it is marked for relearning; the next stream starts an attempt while the incumbent still serves it; the promoted candidate replaces the rule | rolling score on the shape row; relearn trigger |
| 28 | **A provisional rule is retracted** (design 01 §6) | scenario 14 after its provisional binding; then 10 records that the candidate scores below the floor on | as 1, then *empty* | the rule is retracted, the shape is unknown again, and a reprocess request names the streams that carried the provisional binding, as-current | retraction path |
| 29 | **Disabled still selects** (design 01 §11.1) | `learningMode: DISABLED`; as 25 | *empty script* | Y bound to v1 provisionally and later promoted; the model never asked | selection without a model |
| 30 | **Deferred: the worker learns later** (A5, A28) | `executionMode: DEFERRED`; unknown shape, nothing fits | as 1, but answered by the worker | the stream is sentinelled and an attempt recorded `AWAITING_MODEL` with zero questions asked in the task; the worker advances the attempt against the script; promotion issues a reprocess request for the ledger's inputs | durable attempts; the worker |
| 31 | **A person answers a turn** (A28) | as 3 | the model's bad DS3, then a person's good one | the attempt pauses after the failed candidate; a person's *edit and re-run* replaces the DSParser answer and the dialogue resumes from that turn; the transcript records who answered each turn; promoted | resumable dialogue; per-turn answerer |
| 32 | **(Tier 2) The processor waits** (A27) | a filter depending on the document; scenario 23's feed in error mode | — | no tasks are created for that feed while `shapeshifter_feed_state` says `ERROR`; another feed under the same document is processed; reset resumes task creation from where it stopped | A27 in `ProcessorTaskCreatorImpl` |
| 33 | **(Tier 2) Stepping the supervisor element** (A30, design 01 §11.7) | scenario 18's pipeline, with a second rule that does not match the stream; a stream of an unknown shape as a variant | step to the element with the bound stream, then with the unknown one | the element's step data carries `ShapeshifterAiStepDetails`: the shape, the match path (rule 1 missed on its failing term, rule 2 matched), the decision `Bound`, the fragment and its verdicts, an empty transcript; the stepping tree shows `DSParser` and `XSLTFilter` under the element with real input and output; for the unknown shape the decision reads *would learn* — and the script was never consulted, no document written, no ledger row, no request | the `details` slot on `SharedElementData`; the dry-run rule under a `SteppingController`; the tree expansion |
| 34 | **A target is proposed and refused, then accepted** (A31) | as 5, with the target question in the dialogue | targets: first the degenerate event (skeleton, `Unknown`, fields as `Data`), then a real one; then DS3 and XSLT right first time | the target question carries one representative record per line kind, the schema rules and the instructions; the first target validates and fails extraction quality with the typed-element ratio and `Unknown` rate in the re-ask — before any configuration was written; the second is accepted; the parser and transform questions both carry the targets; promoted with the targets on the regression set as goldens | `Question.Target`; representative records by signature; targets on the attempt and the regression set |
| 35 | **A field lost at extraction is caught there** (A31) | as 34 | a splitter that consumes every line but captures two of four fields; then a full one | coverage is 1.0 and field preservation is not: the re-ask goes to the *parser*, naming the target values the records lack; the transform is not asked until the records carry them | field-preservation scorer; feedback attributed to the step that lost the value |
| 36 | **The transform must reproduce the target** (A31) | as 34 | an XSLT that produces a valid event with `where` in the wrong element; then the right one | target fidelity fails naming the record and the difference; the stream-level scorers alone would have passed it | target-fidelity scorer, canonical tree comparison |
| 37 | **Record boundaries first** (A31, A35) | a multi-line record shape — corpus 003 — with the target question in the dialogue; as a variant, an XML document whose records sit two levels down (`nested-entries.xml`) | *Split* reply that cuts one record per unit, then targets, then the rest | the split question is asked before any target, for the CSV of scenario 34 as much as here; targets are proposed for whole records, not lines; a split that cuts mid-record is re-asked on yield and coverage before a target is ever proposed; for the XML variant the split names the element that is one record | `Question.Split` for every kind of input; boundary judged without a target |
| 38 | **Relearning from the millionth event** (A31, design 01 §10.1) | a bound shape; a stream of many records with a fault in one late record kind; bindings carry each record's input span | as 27, the relearn's target question carrying the faulty record's raw text | the record's raw text is read back from the source by its recorded span, not by re-running the parser; the relearn's target and transform questions carry that record; the incumbent serves meanwhile | input spans in the bindings; read-back by span |
| 39 | **The plan is the document's** (A33, A37, design 01 §10.2) | two documents over one feed: one with steps `CHAIN, TARGET kinds 1, CONFIGURE` (a target from the raw sample, no split), one with the direct steps | as 34 for the first; as 1 for the second | the first asks chain, one target, two configurations and no split, the target's record being a line of the sample; the second asks chain and two configurations; a definition with `CONFIGURE` before `CHAIN`, two `SPLIT`s, a `goto` naming no step, or two steps with one id is refused on save naming the rule, and a document that reaches the stage with one is abandoned before the model is asked | `DialogueDefinition` on the document; the step interpreter; validation on save and at the stage |
| 40 | **A template override reaches the model; the rest follows the built-in** (A33) | a document overriding the `CHAIN` template with text using `${elements}` and `${sample}`, with the target-first steps | as 1 | the chain question put to the model is the override with both blocks rendered; the split, target and configuration questions are the built-in text; a template naming `${nothing}` is refused on save naming the template and the variable; the built-ins' version is on the saved document | override-only templates; block variables; the templates resource |
| 41 | **Escalation: a target only when the feed needs one** (A37) | the *escalating* example's steps, as 5 otherwise; two runs over the CSV feed | first: as 1; second: chain, DS3, then the degenerate transform twice, then a target, then DS3 and XSLT right | the first run is learned by chain and two configurations, no split or target asked, `on passed goto end` ending the plan — direct's cost; in the second the transform step spends its two candidates on `quality-short`, `on spent goto target` fires, one target per kind is proposed, the parser is re-asked against the targets, the transform is asked with them and promoted; the transcript names each step by id — `chain, parser, first, first, target, again, transform` — and each candidate's outcome | outcome kinds; `on passed`, `on spent`, `end`; roles on `CONFIGURE`; the interpreter |
| 42 | **A shortfall at the transform sends the parser back** (A37, design 01 §10.1 rule 6) | the target-first example's steps; as 35, but the parser step's checks set to `coverage,yield` so preservation is not judged there, and the transform's to `fidelity` so the stream-level scorers do not fail it first on the fields it cannot find | a splitter keeping two fields of four; the stylesheet, which cannot find `logon` or `office`; then a full splitter and the stylesheet again | the parser passes; the transform's fidelity check finds the values absent from its *input* and reports `preservation-short`; `on preservation-short goto parser` fires at once, the parser is re-asked naming the values the records lack, then the transform, and the shape is promoted; where the parser keeps two fields again, the second `preservation-short` at the transform would take the jump a second time and the attempt is abandoned naming the transition and the step | transitions at once; once-per-transition; outcome attribution in the interpreter's judge |
| 43 | **Syslog: two forms in one feed** (design 03 §3) | fixture `syslog.log`: RFC 3164 and RFC 5424 lines from two senders; `System` header; golden `syslog.events.xml`; first with the default key, then with the signature in it | as 1, the DS3 handling both forms; then, under the signature key, two attempts | with the default key one shape, one rule, one variant that emits every line as a record — a DS3 that handles only 3164 fails coverage and is re-asked naming the 5424 lines; with the signature in the key two shapes, two attempts, two rules, each learned from its own representative | the fixture and golden; `Format` in the chain question |
| 44 | **auditd: a record is several lines** (A31, A35, design 03 §3) | fixture `auditd.log`: interpreted audit records, no separators, events of 2–4 lines sharing `msg=audit(time:serial)`; the target-first plan; yield per line expected 0.35, threshold 0.6 — the document's way of saying a record is several lines | a line-per-record split, then one that joins by serial; targets; a parser that drops the EXECVE's quoted arguments, then the full one; XSLT | the first split consumes every character and is refused on yield alone — 1.0 per line against 0.35 — before any target is asked; the split by serial passes; targets are proposed for whole events, every line of the record sharing one serial; the parser without the arguments passes coverage and is caught by preservation naming `/etc/hosts`; the full parser and the stylesheet promote and the output equals the golden | yield against the input's structure; a lines basis judging the transform record for record; the fixture and golden |
| 45 | **Windows security events: `Data[@Name]` to typed fields** (A16, A35, design 03 §3) | fixture `windows-security.xml`: fifteen `Event` elements in the Windows namespace under an `Events` root, each a `System` block and `EventData/Data[@Name]`, `EventID` 4624, 4634 and 4688; the target-first plan; scorers Schema conformance (gate), Extraction quality (gate, `EventSource/User/Id` required) | chain `XSLTFilter`; split `EventData`, then `Event`; a stylesheet that copies each named `Data` to a `Data` under `Unknown`; then one mapping the `EventID` to Authenticate or Process and the named fields to typed elements | `EventData` — most of an event but not its System block — is refused on wholeness; `Event` is accepted; the degenerate transform validates and is refused on extraction quality alone, the typed ratio and the `Unknown` rate in the re-ask; the typed one is promoted outright, the `Event`s being the root's children, and the output equals the golden with all three `TypeId`s; one target stood for the three kinds of event, since kinds of XML record are told apart by structure and these share one — a finding for phase B | the fixture and golden; a kind of record for XML that structure cannot tell apart is owed |
| 46 | **JSON: lines and an array** (A31, design 03 §3; scenario 2's promise) | fixtures `records.jsonl` and `records.json`, twelve logins and logouts with a nested `client`; `Format: JSON`; allowed elements include `JSONParser`; the target-first plan; scorers Yield (records), Schema conformance (gate), Extraction quality (gate, `EventSource/User/Id` required), Business rules | chain `JSONParser -> XSLTFilter`; for the lines the split is skipped — every top-level value is a record, `root` — and the transform is asked over two targets; for the document the split names `client`, then `events`; XSLT | no configuration question for the parser; the split guard `json` beside `text` and `xml`; the split of the document refuses a key that is no array's and takes the array; a login and a logout are two kinds, told apart by their keys, and two targets are asked; the transform is asked with the parser's real XML in the XSL/json vocabulary; the lines are promoted outright; the document is learned whole, counts as one record at the stage and binds provisionally as the nested XML of scenario 37 does — yield by records is not asked of it until the count is by the array's items (design 03 §5) | the `JSONParser` step runner; the `json` guard and `SPLIT_JSON` template; the array split; the fixtures, stylesheet and golden |
| 47 | **Fixed-width: nothing to split on** (A11, A36, design 03 §3) | fixture `fixed-width.log`: thirty sign-on lines of six columns by position and no delimiter — time, user, terminal, action, result and a reason that holds spaces; the escalating plan; scorers Yield (records), Schema conformance (gate), Extraction quality (gate), Business rules with "a sign-on decision states its outcome" | chain `DSParser -> XSLTFilter`; a positional regex capturing four columns and matching the rest of the line; a stylesheet over the four, twice; then, against two targets, the four-column regex again and the six-column one; XSLT | coverage is 1.0 with four columns — every character is consumed — and never speaks; the four-column transform cannot state an outcome and the rule refuses it, twice, so the plan escalates to a target; two kinds of line, a reason of two words and of one, and a target for each; asked again against them the four-column parser is caught by preservation — the records carry no value for `PASSWORD OK`, for `REVOKED` — and the six-column one passes; promoted outright, the output the golden | the fixture, two splitters, two stylesheets and the golden; the escalating plan on a real shortfall |
| 49 | **XML fragments, no root** (design 01 §12 item 26; the owner's question) | fixture `events-fragments.xml`: one `<Event>…</Event>` per line in the Windows namespace, no root; allowed elements include `XMLFragmentParser`; the target-first plan | chain `XMLFragmentParser -> XSLTFilter`; the split names `Event`; targets; XSLT | no configuration question for the fragment parser, which wraps the fragments in a root; the split question is the XML one, since the parser's output is records XML — not the JSON one because the parser is run only; the rule carries `element Event`; the stream counts its events; promoted | the `XMLFragmentParser` step runner with a built-in wrapper; the walk's kind from the parser's output; the fixture and golden |
| 48 | **CSV with embedded newlines** (A36, design 03 §3) | fixture `csv-multiline.csv`: twenty headerless records of a document store's audit — time, user, workstation, action, document, note — the note quoted where it holds a comma, a doubled quote or a line break, six spanning two lines; the target-first plan; Yield by lines, expected 0.77 | a line-based DS3, then one regex over the stream honouring the quoting, the record as one field; targets; a six-field DS3 of the same regex; XSLT | the line split cuts a record in two: it consumes every character, so coverage says nothing, and loses none, so wholeness — a character share — says nothing either; yield against the lines a record takes refuses it, before any target is asked; the quoting split passes; targets are whole records, one spanning lines; promoted outright, the golden holding the note with its line break intact and its doubled quotes as one | the fixture, two splitters, stylesheet and golden; the DS3 rules teaching a quoted field |

Scenarios 3, 15, 16 and 17 exist today as unit tests of one component; they become scenarios so
that the catalogue is the one place the behaviour is stated.

## 6. What the catalogue forces, in order

Each item is the smallest thing that lets the next scenario pass. The design's §12 list is the
superset; this is its test-driven ordering.

1. **`Scorecard`** — the document's `ScorerSetting`s applied to one candidate's output: per-scorer score in
   [0, 1] with its diagnostics, gate outcomes, weighted total, and the *first* failing step to feed
   back to. Weights and thresholds come from the settings; the scorers are an SPI keyed by
   `ScorerType`. Compile and input coverage wrap what exists.
2. **Scorers** in catalogue order: yield (record count against `YieldParameters`); schema
   conformance (a `SchemaFilter` over the event-logging pack, per record, as the calibration tests
   stand the Data Splitter schema up today); extraction quality (XPath over the output: typed ratio,
   `Unknown` rate, required fields); business rules (XPath assertions plus captured `xsl:message`s);
   error load; event classification.
3. **`Dialogue` scores.** A step passes when its scorecard passes, not merely when it compiles;
   feedback carries the scorecard's diagnostics. Budgets enforced under a clock the scenario controls.
4. **Learning key** — `Feed AND Type` by default, and a provisional shape-signature normalisation
   for documents that put the signature in the key (A29), so that routing and the ledger have a
   key. A6 is open and needs real feeds; scenarios need only that the same record shape yields the
   same signature and a different one does not. Token classes for text, element skeleton for XML,
   as §5 says.
5. **`Stage`** — route, learn, judge, write, emit (§4 above), over in-memory streams, quarantine and
   regression set. This is the supervisor's logic without the pipeline element around it, and it is
   what the element will delegate to.
6. **Tier 1 scenarios 1–17, 21–29 and 31.** Scenario 21 needs nothing scenario 2 does and can follow
   scenario 1 directly: the records split, a stages runner, and — for its full form — the stage-2
   scorers that scenarios 5 and 6 force. Its structural form, with Compile and Yield alone, proves
   the chaining and the record-unit split before those scorers exist.
7. **§12 items 1, 2, 4, 7** — the `stroom-pipeline` harness, the supervisor element, bindings
   metadata — and **Tier 2 scenarios 18–20**; then §12 item 19, the stage pane in the stepper, and
   **scenario 33**.
7a. **Learning against a target** (A31, §12 items 20 and 21) — scenarios 34–38 — before the tables and
   the durable attempts, since it changes what an attempt's turns are and what the regression set holds.
7b. **The plan as data** (A33, §12 item 22) — scenarios 39–40 — straight after, before the stepper
   pane and the durable attempts, so the document settles before either shows it; then **the plan
   as a graph** (A37, §12 item 23) — scenarios 41–42 — for the same reason, and because the turn rows
   of item 8 record the step id and outcome it defines.
7c. **Format breadth** (design 03 §3, phase B) — scenarios 2 and 43–48 — before the tables, since what
   the model can and cannot learn across formats decides how much of the rest is worth building first.
8. **The A26 tables** (§12 item 8) — scenario 20 — where the record element of A35 also reaches the
   rule; then **durable attempts and the worker** (A28, §12 item 15) — scenario 30 — which extend the
   tables; then A27 under scenario 32 (§12 item 16).

Redaction (A17) is not on this list: a scripted model does not care whether the sample is redacted,
so scenarios do not force it. It stays on §12's list as item 6's neighbour and gets its own test.

## 6.1 Where it stands

Built 2026-09-17, the first slice: the `Scorer` SPI with `Scorecard`, `Verdict` and the compile,
input-coverage and yield scorers; the dialogue judging each step by scorecard rather than by compile
alone; a provisional `ShapeSignature`; `Router` over `ExpressionMatcher`; `Quarantine` and
`RegressionSet` as interfaces with in-memory implementations; `FragmentRunner`, which runs a written
fragment back through the step runners; and `Stage` — route, learn, judge, write, emit. The fixtures
of §3 exist as `Script`, `QuestionMatcher` and `Scenarios` in the module's `testFixtures`.

Scenario 1 passes, twice over: the learning run, and a second stream of the same shape bound by the
learned rule with the script never consulted and the written fragment producing the same translation.
Writing it found two things worth recording. The corpus's CSV case has six records, not the ten a
default document waits for, so the scenario lowers `minRecordsPerShape`; and its golden splitter reads
the header into a variable, which coverage counts as discarded — design 01 §9.1's finding, met again
— so the coverage threshold sits at 0.8, and the script's own check caught it: the transform question
never came because the splitter was being re-asked with the coverage feedback.

Later on 2026-09-17 the document and the UI were brought up to the day's rulings (design 01 §12 items
17 and 18): scenario 1 now asserts a `Feed AND Type` rule with a `uuid`, the chain question carries
the key's values (`Feed`, `Type`) rather than a fixed header list, and the fixture's `shape()` is
`chain()`.

The Stage was then brought up to §4 as rewritten (the second slice, after the first commit): a shape is
one value of the learning key (`Shape`); the ledger and regression set are keyed on document-and-shape
and on rule `uuid` (A26, A18); an unknown shape is tried against the fragments bound for its feed and
type before any question (`Router.compatible` reads the selector's top-level `Feed`/`Type` terms);
a candidate that clears the floor is bound provisionally when the shape has too few records, and a
provisional rule is promoted in place the first time a stream brings enough; a matching reserved rule
gives the shape up; a matching draft is sentinelled naming it; `DISABLED` still selects. Scenarios 14,
25, 26 and 29 pass, and the routing half of 22.

The third slice, 2026-09-18, is what happens to a binding after it is made — scenarios 28, 27 and 13 —
and the runtime state it needs, given to the `Stage` as the seams of A26: `Shapes` (the shape row:
given up, marked for relearning, rolling score), `Ledger` (sentinelled inputs), `Outputs` (the bindings
each output carries, §7.3 rule 3, as a `Bindings` record on every `StageRun`) and `Reprocessing`
(requests, as-current). Every sentinel now writes a ledger row (A4), including a given-up shape's own
stream; binding a shape takes its inputs off the ledger and requests them (A12); a provisional rule
that fails the gate is retracted — out of the table, the shape unknown again, the inputs whose output it
produced requested, the failing stream sentinelled — and the next stream of the shape learns afresh. A
promoted rule's score over each stream it serves feeds the shape's rolling score, and once that is
below `relearnThreshold` the next stream is relearned while the incumbent serves it: the candidate
replaces the incumbent's fragment under the same rule when it clears the floor, is no worse than the
incumbent on that stream (A15) and no worse on any record the rule was accepted on (A18); otherwise the
incumbent is kept and nothing is written. Four things were decided in the writing and are worth the
owner's eye:

- **A chain's score is the product of its steps' totals**, not their mean. The mean let a perfect
  transform lift a split that discarded a seventh of the input to 0.93; a record must survive every
  step, and the product says so. The scenario documents' floors sit at 0.85 in consequence, below the
  6/7 that the corpus's header-discarding splitter scores — §9.1's finding, met a third time.
- **The records a stream brings are counted on the input** — its non-blank lines, or its records where
  it is already XML — not on the output, so that a variant which extracts nothing from ten records is
  judged on ten and retracted, not excused as too few to judge. Scenario 14's six records are seven
  lines.
- **The rolling score is a running mean whose memory is capped at `minRecordsPerShape` records** — the
  two columns a shape row can hold — so a shape that has been good for a year is judged on what it has
  done lately, and is not acted on until a memory's worth has been seen. Relearning waits for a stream
  that brings `minRecordsPerShape` records, so the candidate meets A14 as a fresh one must; it spends
  the mark whatever the outcome, so a shape that cannot be fixed is relearned again only after another
  memory's worth of records has fallen below the threshold. A pinned rule is served and nothing else. The
  scenario drives the score with input coverage, since the schema-conformance scorer the catalogue
  names is scenario 5's; the trigger does not care which scorer moved it.
- **A provisional binding releases the ledger too**, not only a promotion: the ledger's inputs are the
  very records the provisional rule is waiting on to meet A14, and if it is wrong about them its
  outputs are retracted and requested again. A draft still releases nothing until Approve.

Also: a fragment step that produced output with errors is now followed, as a pipeline would follow it,
the errors being the failing records' (design 01 §5); only a step that produced nothing stops the chain.

The fourth slice, also 2026-09-18, closes the routing table's story: scenario 22 and the relearn
question. In review mode (A25) `bind` writes the rule as a draft — fragment written, regression records
kept under the draft's `uuid`, nothing released, the stream itself onto the ledger — and every later
stream of the shape is the sentinel the catalogue names. `Stage.approve` is the promotion: the rule goes
live with time and score and the shape's ledger is released as a reprocess request; `Stage.reject`
drops the rule and its records, leaves its documents, and gives the shape up with the reason so the
model is not asked again until an operator says otherwise. The case the catalogue leaves implicit is
covered too: a *relearned* candidate under review is a draft with the incumbent's selector, appended
behind it so the incumbent keeps matching first and serving; while it waits the shape is neither scored
nor relearned again; Approve rebinds the incumbent to the draft's fragment, keeping the incumbent's
`uuid` and folding the draft's regression records into its history, and is refused while the incumbent
is pinned; Reject leaves the incumbent serving, and gives the shape up so that the same falling score
does not draft the same candidate again. A draft's regression records are kept however few, since a
person approving a draft on too few records is §6's exception to A14. The relearn dialogue now opens with why the incumbent fell short — the mark's reason, the
incumbent's score on the stream, and the failing scorers' diagnostics — as the feedback of every
question's first asking (`Dialogue.run` with an opening). Approve and Reject are operations on the
`Stage`; the Routing tab's buttons and the Supervisor view (A28) will call them.

The fifth slice, 2026-09-18, is Tier 2: scenario 18 passes with the supervisor element in a real
pipeline, processed as a processor task under `AbstractProcessIntegrationTest`
(`TestScenario18LearnsACsvFeedInAPipeline` in `stroom-app`). `ShapeshifterAiParser` sits where a parser
sits — `Source → ShapeshifterAi → SchemaFilter → RecordOutputFilter → RecordCountFilter → XMLWriter →
StreamAppender` — reads the stream, lets the `Stage` decide, writes the document back when the routing
table changed, and either runs the bound fragment or logs the refusal as an `ERROR` naming the shape.
The fragment runs as a **nested pipeline** (A20, design 01 §3): its merged `PipelineData` with a
`ShapeshifterAiOutput` filter linked from its tail, built by the same `PipelineFactory` in the same
pipeline scope, so its events reach the element's targets and its errors this pipeline's error stream.
The bindings go into the output stream's attributes through `MetaData` (§7.3 rule 3), and the real
`SchemaFilter` validates the learned transform's events against the event-logging schema with nothing
to report. The second stream is bound with the script never consulted. Five things to know:

- **The fragment runs twice on a stream** for now: once through the step runners for the score the
  stage decides on, once as a pipeline for the output. A fragment runner over the nested pipeline with
  per-element capture (design 01 §12 item 2) removes the first run; until then Tier 2 pays double.
- **Runtime state in a node is in-memory and node-local**: the `InMemory*` implementations moved from
  test fixtures to `stroom.shapeshifter.ai.state`, synchronised, and bound as singletons until the A26
  module exists. A cluster does not yet share what a stage has learned about a shape, and a restart
  forgets it; the routing table, being on the document, survives both. **A reprocess request is real
  already**: `PipelineReprocessing` creates a reprocess filter over the outputs this pipeline made from
  the named inputs — error streams, or a retracted rule's output — at the lowest priority, which task
  creation turns back into their inputs and runs as-current, exactly as an operator's reprocess does.
  It is not yet exercised by a Tier 2 scenario, since the mock processor filter service's `reprocess`
  is a stub; scenario 13 in Tier 2 waits on that.
- **The advisor is an optional Guice binding** whose default, `NoModelAdvisor`, fails a stream loudly
  when a document in `AUTOMATIC` mode would ask; the test node binds the scenario's `Script` through an
  `AdvisorHolder`. Wiring `stroom-ai` in is design 01 §12 item 6.
- **Two tasks learning the same shape at once race on the document write**; the loser's
  `DataChangedException` fails its stream rather than overwriting. The attempt's claim on its shape
  (A45) is the fix, and the rules leaving the document for rows of their own (A41) removes the write.
- **A stream of several parts** is served part by part, but the output stream's attributes are one set:
  the first part's bindings stand for the stream and a later part bound differently is reported as a
  warning, since the attributes cannot say so and an as-processed reprocess of it would be misled.
- **Two mocks were made truthful for this**: `MockStore` now keeps the attributes a target closed with,
  as the real store hands them to the meta service, and `MockExplorerService.create` creates the
  document through the type's handler where one is registered, as the real service does, returning
  null only for a type this environment has no handler for (which the content store setup relies on).

The sixth slice, 2026-09-18, is the scorers of §6 item 2 and the scenarios they unlock: 4, 5, 6, 7, 8,
9 and 10 in Tier 1 (`TestScenariosScoring`, `TestScenariosIncumbent`) and 19 in Tier 2. Schema
conformance validates per record through Stroom's own `SchemaFilter` behind a `SplitFilter`, as
`SchemaFilterSplit` does, with a counter between them so that each error lands on its record; the
feedback is the first failing records' messages as Stroom post-processes them. Extraction quality is
the mean of the typed-element ratio, the proportion of records not naming `Unknown`, and the presence of
each required field, with a diagnostic for each shortfall. Business rules is the proportion of records
holding every assertion, a transform `xsl:message` at warning or above counting against one record —
`XsltStep` now captures messages as Stroom's `XSLTFilter` reads them. Coverage's feedback names the
first lines nothing consumed. Writing the scenarios found four things:

- **The scorers of meaning apply only to a step that is not a parser.** Left to judge every XML output
  they refused the parser's `records:2` document against the events schema. Design 01 §4 says where
  each scorer sits — stream-level after a parser, per record after a filter — and the scorers now
  honour it: a step runner says whether it parses (`StepRunner.parser`), the fact travels with the
  attempted step, and the meaning scorers leave a parser's records alone whatever its input looked like
  (a parser over line-delimited XML fragments has markup for input and is still a parser).
- **The 3.0.0 schema is stricter than the flawed candidates the catalogue imagined.** `Authenticate`
  requires one of `LogonType`/`User`/`Device`, and `EventSource` one of `Device`/`Client`/`Server`/`Door`,
  so a transform that *drops* the user or the device fails conformance first, and the business rule or
  the required field never gets to speak. The scenarios' flawed candidates emit the element empty
  instead — schema-valid, and wrong in the way the later scorer exists to catch — which is also a truer
  picture of what a model produces.
- **A wholly broken rule must score zero.** The first formula counted "no transform messages" as a
  passed check per record, so a rule every record broke scored 0.5; the score is now the proportion of
  records holding every rule.
- **Feedback numbers are the learning prefix's**, not the stream's: scenario 4's re-ask says the split
  consumed 3 of 5 lines and names lines 2 and 4, because the model learns from the prefix. The
  catalogue's rows read as if over the whole stream; the tests state what is actually said.

Also from the audit of the slice: the output a model's stylesheet produced is parsed by the same
DOCTYPE-refusing reader the step already applies to what a model wrote (`ConfinedXml`), so nothing in
it can make the harness fetch anything; a required-field path or a rule's XPath that does not compile is
refused when the scorecard is built (`Scorer.validate`), before a model is asked, rather than failing
every stream; and a record the validator never reached counts as failing, not conforming.

Scenario 7 runs both ways: a candidate that never clears its yield threshold is abandoned at the
candidate limit after five questions and the shape given up with a ledger row, and one that clears
every threshold but not the floor is given up after three. Scenario 8 runs both halves of the gate: a
candidate worse than the incumbent on the held-out stream (A15) and one worse on a record the rule was
accepted on (A18), each kept without a document written. The Tier 2 scorer set is the same six, the
schema-conformance scorer taking the node's `SchemaFilter` and so the node's whole schema store.

The seventh slice, 2026-09-18, is the harness for the **live smoke** — the scenarios' machinery driven
by a real model, to learn what no script can say: whether the questions as put draw replies the grammar
accepts, whether the feedback steers a second candidate to a passing one within the candidate limit,
whether the prefix and the thresholds are workable, and what an attempt costs in tokens and seconds.
Three pieces:

- **`QuestionText`** (main) renders each typed question as design 01 §10's prompt contract says it
  must be put: the system text carries the stage's objective and the document's `instructions`; the
  chain question the key's values, the allowed elements with a line each, the sample and the
  {@code A -> B} grammar; a configuration question the element and document type, the real input the
  element will receive, the previous configuration on a re-ask, what fell short, and the one-fenced-block
  grammar — with §9.1's `schemaLocation` and `ignoreErrors` rules for extraction and §8.2's failure
  modes and §8.3's trap for transformation. A node's advisor over `stroom-ai` (§12 item 6) will use the
  same text, so the harness measures the prompt the node will send.
- **`LiveAdvisor`** (test fixtures) is the `Advisor` seam over an OpenAI-compatible endpoint, configured
  from the environment as `TestExtractionReconstruction` already is — `SHAPESHIFTER_AI_BASE_URL`,
  `SHAPESHIFTER_LEARNING_MODEL`, `SHAPESHIFTER_AI_API_KEY` — putting each question after the system text
  and the attempt's transcript so far, and counting tokens and time. Samples go unredacted (A17 is not
  built): the endpoint must be one the data may be sent to.
- **`TestLiveScenarios`** runs five scenarios with it and writes a report and every transcript under
  the module's `build/live`: scenario 1 (the corpus's CSV with its header); a headerless CSV against the
  full scorer set (does the first transform mean something, or is it steered out of the degeneracy
  trap?); two record kinds in one stream at coverage 0.9; scenario 27 live (learn, fall, relearn); and
  the corpus's `123 [abc] text` shape. It is opt-in — enabled only when the environment names an
  endpoint — and it is a report, not a verdict: it fails only if the harness itself breaks, and a run's
  own failure is recorded and the others go on. Run it with
  `SHAPESHIFTER_AI_BASE_URL=… SHAPESHIFTER_LEARNING_MODEL=… ./gradlew :stroom-shapeshifter:stroom-shapeshifter-ai:test --tests '*TestLiveScenarios*'`;
  what the first run found belongs here, beside §9.1's account of the extraction harness.

One thing to know: the document's `instructions` reach the model through the advisor's construction,
not through the `Question`, since the seam carries no document; the node's advisor will be built per
stage with the document in hand, and A28's persisted turns should record the system text with the rest.

### 6.2 What the first live run found

Run 2026-09-18 against `claude-sonnet-5` through Anthropic's OpenAI-compatible endpoint, three times,
75 questions and about 435k tokens in all. The transcripts are kept beside the module's build output
(`build/live-runs/`); the numbers below are from the reports.

| Run | Questions as put | 01 header CSV | 02 headerless, full scorers | 03 two record kinds | 04 scenario 27 | 05 regex corpus |
|---|---|---|---|---|---|---|
| 1 | as first written, 3 candidates | given up at the splitter | given up at the splitter | given up at the splitter | given up at the splitter | given up at the transform |
| 2 | + a worked Data Splitter example, 3 candidates | **promoted 0.857** | **promoted 1.0** | given up at the transform | promoted 1.0; marked; relearn kept | **promoted 0.938** |
| 3 | the same, 5 candidates | promoted 0.857 | promoted 1.0 | given up at the transform | promoted 1.0; marked; **rebound 0.981** | promoted 1.0 |

- **The grammar holds.** Seventy-five replies, every one a single fenced block or an `A -> B` chain
  exactly as asked; the reply parsers were never exercised in refusal. The endpoint refused
  `temperature` for this model, so the advisor sends it only when asked to.
- **Design 01 §4.1 was right about extraction.** Without a worked example the model does not know the
  Data Splitter's structure — it wraps the `data` elements in a `group` inside the `regex`, or drops the
  group's `value` — and the compile gate's `cvc-complex-type` messages teach it one constraint per
  candidate, so it thrashes and is abandoned: five out of five. With one worked example in the question
  (a four-field CSV, the shape the design calls the strict one), five out of five compiled first time,
  including a two-`regex` splitter for the stream with alarm lines that the example only hinted at.
  Diagnostics are the wrong teacher for extraction; the example is the right one.
- **Transformation converges by the schema's feedback, one constraint per candidate**, and the number
  of candidates is what decides it. A door-access CSV against the full scorer set — schema gate,
  extraction quality with two required fields, a business rule — was promoted at 1.0 on the first
  stylesheet (run 02). Where the model needed rarer branches it climbed: the corpus's `123 [abc] text`
  shape wanted `Other` (not a 3.0.0 branch), then had `Description` before `Action`, and passed on the
  third or fourth stylesheet. **Scenario 27 ran live end to end** at five candidates: learned at 1.0,
  served the alarm stream and fell to a rolling 0.7, was marked, relearned with the incumbent serving,
  and rebound at 0.981 — the whole loop, with a real model, once.
- **The one failure left is a ladder.** For the alarm lines the model chose `EventSource/Door` — the
  right element for a badge reader — and `EventDetail/Alert`, also right. `Door` in 3.0.0 has a long
  mandatory sequence (`Name, Description, Location, SingleEntry, RemoveAll, …`), and the validator
  names one missing child per candidate; the model added one per turn and could not finish in five.
  `Device`, with `Name` alone, would have passed at once. The fix is to the feedback, not the model:
  when conformance reports *the content of element X is not complete*, carry X's whole content model
  from the XSD — its children in order with what is required — so that a candidate can finish in one
  step. §8.2 already has the schema's post-processed messages as feedback; this is the next enrichment,
  and the operator's part is that `instructions` and the required-field list agree with each other and
  with what the schema makes cheap (the run's instructions said *device*, its required field said
  `Device/Name`, and the model still, reasonably, chose `Door`).
- **Cost and time.** A promoted first-time attempt was 6k tokens and 14 seconds; a relearn with five
  candidates 94k tokens and just over three minutes; the failed ladder 77k. On this endpoint a question
  took 5–30 seconds. The candidate limit's default of 3 is right for a simple feed and one short for a
  feed with a rarer branch; five let scenario 27 through. Deferred mode (A5) is the mode for anything
  that is not a backfill, as the design said.
- **Two things the harness itself learnt.** A given-up decision now carries the last candidate's
  diagnostics — the transcript could not say *why* the third attempt failed, and neither could the
  sentinel (§4 step 5). And Gradle does not count an environment variable as a test input, so a second
  live run must be `cleanTest test --tests '*TestLiveScenarios*'` or it reports the last one.

**What the run settles about the questions, round by round.** The *chain* question is right as
written — key values, a line per allowed element, the sample, the `A -> B` grammar — and needs nothing.
The *splitter* question is rules, a worked example, the real input, and only on a re-ask the diagnostics:
the example is what teaches, the diagnostics only correct. The *transform* question must name the target
schema's branches and their order up front, must carry the real `records:2` output (every stylesheet's
XPaths were right), and on a re-ask should carry the content model of the element that failed, which it
does not yet. Feedback steers well when it is specific to the thing that fell short — coverage's
uncovered lines, the rule's name, *required field X present in 0 of 16* — and one rung at a time when it
is the validator's alone. The candidate limit's default should be five, with the attempt budget (A5) as
the real bound.

**And about the scorers.** Coverage did its teaching in the first round of every extraction. Schema
conformance as a gate is the transform's workhorse and the scorer whose feedback most wants enriching.
Extraction quality earned its place as the A16 gate: in run 2's two-kind stream it was what failed
(0.67 against 0.7) when the schema passed — §8.3's trap, met live — and its required-field line is what
steered the model toward the fields the operator wanted. It also showed that the operator's
`instructions` and required-field list must agree with each other and with what the schema makes cheap;
the Learning and Scoring tabs should say so. The business rule fired once and was fixed in one turn.
Yield never mattered here — no candidate dropped records — which is what it is for elsewhere.

What this does not yet say: how the extraction stage fares on the corpus's other shapes (the
reconstruction test of §9.1 measures that, and has not been re-run with the worked example), what a
cheaper or a stronger model does (the same three runs, one line of environment away — a small model is
the cost curve at volume), and whether a person's `instructions` can carry the schema hint the feedback
should. Follow-ups, in order of value: the content-model hint in conformance feedback; the default
candidate limit to five; `QuestionText` as the node advisor's prompt (design 01 §12 item 6 — it is in
main for that reason); the reconstruction test re-run with the new extraction question; a small-model
run.

The eighth slice, 2026-09-18, closes what the live run found. **The schema's feedback now carries the
content model** of what fell short: `ContentModels` reads the group's XSD text and writes an element as a
model can use it — children in order, `?`/`*`/`+`, a choice as `(A | B)` with a run inside a choice kept
as a run, a required complex child opened one level — and `SchemaConformanceScorer` appends up to three
such hints after the record messages: the whole model of an element reported incomplete, and, for
*invalid content starting with X, one of {Y} expected*, the parents that could hold both, ranked by
whether they require what was expected (which picks `Alert` out of seventeen elements with an optional
`Type`). Against the real 3.0.0 schema: `Door (in EventSource) contains, in order: Name, Description?,
Location { Country?, Site?, … }, SingleEntry, RemoveAll, AddAccess { AccessZone+ }`. The hint is best
effort — read as the processing user, a schema that does not parse left out, nothing in it able to fail a
score — and the Tier 1 scenario reproduces run 3's ladder exactly and shows both hints reaching the
re-ask. **The default candidate limit is five.** The §9.1 reconstruction test now builds its prompt from
`QuestionText`'s extraction rules, so the two measure the same words.

**The fourth live run**, later the same day with the hint in place (five candidates): scenario 03 — the
ladder — **promoted at 0.981** in five questions where run 3 had given up after seven; 01, 02 and 05
promoted as before (01 at 0.964 this time). Run 04's relearn failed, though: the relearned splitter
was right first time (two regexes), and the stylesheet climbed `Door` again — `Description, Location`,
`Location`, `SingleEntry`, `RemoveAll` — with the hint present on every re-ask. It was the last of six
lines, at INFO, in a notation the model was not told, and the validator's own "one of {Location}" was
followed instead. So the hint now comes **first**, at WARNING, with a legend for the notation, the
instruction *add every required child at once, not one per attempt*, and — where the element is one
option of a choice in its parent — the alternatives, since `Device` needs a `Name` where `Door` needs
six things: `It is one alternative of ((Device, Client?, Server?) | Door); another may be simpler`. That
form has not yet been run live.

The reconstruction test across the corpus's shapes was attempted twice and measured nothing: the first
run was spoiled by a concurrent Gradle build in the same module overwriting its results after 22 minutes
of model time, and the second found the key's credit spent. It gained a robustness fix — a case that fails
is recorded as `FAILED` and the others go on, so a run always yields its table — and the measurement is
still owed.

Not yet: the reconstruction run; scenario 20 (needs the A26 tables for its ledger row); everything
from scenario 30 on.

### 6.3 Direct against target-first

Run 2026-09-18 against `claude-sonnet-5`, the five runs of §6.2 in each shape of the dialogue, twice:
run 5a as slice 10 left it, run 5b after the fixes 5a called for. Archived as `build/live-runs/
sonnet-5-run5{a,b}-{direct,target_first}`. Scores are the stream score; questions and tokens are the
whole run (04 is three streams).

| Run | 5a direct | 5a target-first | 5b direct | 5b target-first |
|---|---|---|---|---|
| 01 header CSV | 0.902 · 5 q · 21.6k | **given up 0.80** · 8 q · 41.7k | 0.964 · 3 q · 7.1k | 0.857 · 7 q · 30.7k |
| 02 headerless | 1.000 · 3 q · 6.2k | 1.000 · 5 q · 15.7k | 1.000 · 3 q · 6.4k | 1.000 · 6 q · 26.1k |
| 03 two kinds | 0.981 · 6 q · 53.0k | 0.981 · 11 q · 82.1k | 1.000 · 5 q · 39.5k | 0.925 · 6 q · 26.0k |
| 04 scenario 27 | 0.981 · 10 q · 85.8k | 0.981 · 18 q · 127.9k | 0.981 · 7 q · 30.6k | **1.000** · 13 q · 69.7k |
| 05 regex corpus | 1.000 · 7 q · 54.9k | 0.938 · 7 q · 25.4k | 1.000 · 5 q · 23.4k | 1.000 · 8 q · 49.4k |

**Run 5a did not show target-first winning**: the same or lower scores at one and a half to two times
the questions and tokens, and one stream given up. The transcripts laid the cost at four doors, three of
them this side of the dialogue:

- *Fidelity compared serialisations.* 01's first transform wrote `evt:Event` — the same tree in the same
  namespace — and was refused, then re-asked at 16k tokens and 49 seconds. `TargetChecks.canonical` now
  walks the tree by namespace and local name, attributes sorted, whitespace-only text dropped; and the
  refusal shows the nearest event produced beside the target, so the model sees the difference rather
  than the demand.
- *The schema ladder moved, as A31 meant it to, but was still a ladder.* The alarm kind's target in 03
  climbed Door-incomplete, Alert-needs-Type, `Type="Alarm"`-not-in-the-enumeration, accepted: four
  target questions at 4k to 8k tokens where the direct run had spent five transform questions at 10k to
  19k on the same rungs. The content-model hint now carries the values of a required child whose type is
  an enumeration — `Type [Vulnerability | IDS | Malware | Network | Change | Error | Other]` — so that
  rung is gone.
- *A namespace slip read as nonsense.* 03's transform declared `xmlns="event-logging:3"` on the `Events`
  literal alone, so every `Event` beneath was in no namespace; the validator said *Event was found where
  Event is expected* and the hint added *Events contains Event\**. The scorer now sees the found name
  among the expected and says so: wrong namespace or none, declare the default namespace on
  `xsl:stylesheet`. In run 5b that hint fired three times across both shapes and was taken first time
  each.
- *The required fields never reached the model.* Both shapes put the reader's location under
  `Device/Location` and scored extraction quality 0.75 on 01 — `EventSource/Device/Name` is required by
  the document, but the list reached the model only as feedback, and 0.75 clears the 0.7 gate, so it
  never did. `QuestionText.system(doc)` now states the document's required fields and business-rule
  XPaths in the system text; `ModelAdvisors` and `LiveAdvisor` both use it.

**Run 5b, with those in, is the measurement.** Direct improved most: 01 right first time at 3
questions and 7k tokens (from 5 and 21.6k), 03 and 05 at 1.000. Target-first promoted every stream and
03 went from 11 questions to 6 — both targets accepted first time, both configurations right first time
— and 04's relearn rebound at 1.000 where direct rebound at 0.981. It still costs more: 5 to 8 questions
against 3 to 5 for one stream, and about twice the tokens, because every question carries the whole
transcript and the split and target exchanges compound into every later one.

Two things it lost on are findings, not verdicts:

- *01 at 0.857 under target-first, 0.964 direct*: the same header line, lost either way. Target-first's
  target for the header kind is `none`, and the parser the model then wrote dropped the header in DS3 —
  coverage 0.857 in the parser step, which has no other weighted scorer, so the step product carries the
  whole 0.857. Direct's parser kept the header as a record and the transform dropped it — yield 0.857 in
  the transform step, averaged against three 1.0s. Same information lost, a different score by where
  it was lost. That is §9.1's finding meeting the step product, and it wants a ruling: should a line
  whose kind's target is `none` count against coverage at all?
- *03 at 0.925 under target-first*: the alarm kind's target was `none` — the model, now told every
  event is scored on carrying `EventSource/User/Id`, judged a record with no user to be no event. The
  yield scorer caught it at the end (14 events from 20 records, 0.7), but only after promotion was
  decided. The system text now says the fields are scored as a share and a record without one is still
  an event; and the `none` reply is an unguarded exit from the target question, which the scripted
  scenarios do not yet test. A guard — a kind that is more than a small share of the sample is asked
  once more before `none` is taken — is the obvious shape, not yet built.

What the comparison said for A31 at that point: target-first does what it was designed to do — the
schema is learned on the cheap question, the transform is held to a concrete event, and the relearn
came back at a higher score — at about twice the token cost, and the two streams it scored lower on
were scored lower by the scoring, not by the events. On that evidence the node's default was set to
**direct** (`Dialogue.Shape.DIRECT`; the scripted fixture stays target-first so every scenario
exercises the fuller dialogue), the `none` reply was guarded — a kind seen more than once in the sample
has its first `none` questioned and its second taken — and the parser question was told to emit a
`none` kind as a record still, so the loss lands where direct's does.

**Run 6** added the feeds A31 was designed for: 06, corpus 003's Linux audit blocks (records of one and
five lines between `----` separators), and 07, a nested XML audit log (`nested-audit.xml`: eight
entries of two kinds, actor, session and document as subtrees). Both shapes, all seven feeds:

| Run | 6 direct | 6 target-first |
|---|---|---|
| 01 header CSV | 0.857 · 4 q · 14.8k | 0.964 · 7 q · 31.8k |
| 02 headerless | 1.000 · 3 q · 6.4k | 1.000 · 5 q · 16.1k |
| 03 two kinds | 0.963 · 4 q · 19.2k | 0.981 · 8 q · 53.1k |
| 04 scenario 27 | 0.981 · 7 q · 29.2k | 0.981 · 14 q · 70.5k |
| 05 regex corpus | 1.000 · 5 q · 21.4k | 1.000 · 8 q · 45.1k |
| 06 multi-line audit | 0.881 · 4 q · 45.3k · 183 s | **given up 0.85** · 8 q · 95.2k · 324 s |
| 07 nested XML | **given up** · 6 q · 102.9k · 578 s | — quota reached at question 3 |

- *01 swapped places*: this time direct's parser dropped the header in DS3 (coverage 0.857) and
  target-first's kept it (0.964) — the same asymmetry as 5b, the other way round. It is model variance
  meeting the scoring, not a property of either shape. The extraction rules now say a header line is a
  record too, emitted with its fields as data for the transform to drop; and the A11 question stands:
  coverage is `min(chars, lines)`, and on a sample of seven lines one header line is a seventh, where
  by characters it is a sixteenth. Whether coverage should be the character ratio alone is a ruling.
- *06 is the first feed where target-first's events were plainly better and it still lost*: yield 1.0
  against 0.69, extraction quality 0.917 against 0.833, conformance right first time against a re-ask
  — and given up at 0.85 on parser coverage 0.846, because its block split relied on a trailing
  separator and dropped the last record (line 13) with the leading `----` (line 1). Direct's parser
  took every line as a record and let the transform sort them, at the cost of yield. The loss of the
  last block is real and rightly penalised; a split judged on the sample alone cannot see that the
  stream's last record has no terminator, which is an argument for the split question to say so. The
  cost is also plain: the split question took 68 seconds and 10k tokens, the parser configuration 154
  seconds and 30k, on a twelve-line sample.
- *07 exposed the chain question, not the dialogue*: direct chose `DSParser -> XSLTFilter` for XML
  input — the question's one example was that chain — and spent five parser candidates and 578 seconds
  failing to cut XML with the Data Splitter. Target-first made the same choice and then met the key's
  monthly usage limit at its third question. The chain question now says raw text needs a parser and a
  stream that is already XML begins with the transform, with an example of each. The XML feed is
  unmeasured in both shapes.

The key's usage limit closed the run: no more live questions this month. Where it stands: on clean
single-line feeds direct is as good and half the price; on the multi-line feed target-first's events
were better but its split was not, and the scoring made the split decisive; the nested feed has not
been measured. The owner's ruling on this (A32): the shape is not a choice to make in code but a
setting on the document beside the model — different models, trained differently, may want different
dialogues, and the harness exists to find out which. That was first built as a `DialogueShape` field on
the document, then (A33, slice 11) as a preset a step list inherited from, and then — the coherence
audit finding a preset is a mode in all but name — taken out (A34): the document owns its steps, and
the two dialogues are `DialogueExample`s the Learning tab loads from. `Dialogue` reads the steps from
the document it is learning for, and the constructors and fixtures that carried a shape are gone. The
scripted scenarios give their documents the target-first steps, so the fuller dialogue stays exercised.
The next live run, when the
key allows, should be 06 and 07 in both shapes with the chain steer and header rule in, and — if 06
repeats — the split question told that a record may end the stream without its terminator.

**Run 7**, 2026-09-21 to 22, is phase B's live run (design 03 §2): the six formats of slices 13–18 and
the JSON pair, rows 08–14, on sonnet-5, under target-first and then escalating, five candidates a step,
the harness restricted to those rows (`SHAPESHIFTER_LIVE_ROWS`). Archived as
`build/live-runs/sonnet-5-run7-{target_first,escalating}`. Score · questions · tokens · seconds:

| Run | 7 target-first | 7 escalating |
|---|---|---|
| 08 syslog | 1.000 · 7 q · 56k · 131 s | 1.000 · 4 q · 38k · 98 s |
| 09 auditd | 0.997 · 11 q · 181k · 274 s | **given up** · 6 q · 114k · 543 s |
| 10 Windows security | 0.997 · 13 q · 415k · 849 s | 1.000 · 2 q · 28k · 165 s |
| 11 JSON lines | 0.999 · 9 q · 80k · 180 s | 0.938 · 6 q · 83k · 311 s |
| 12 JSON document | **given up** · 10 q · 215k · 883 s | **1.000, wrongly** · 6 q · 144k · 1727 s |
| 13 fixed-width | 1.000 · 8 q · 68k · 71 s | 1.000 · 4 q · 30k · 52 s |
| 14 CSV, embedded newlines | 1.000 · 15 q · 258k · 166 s | 1.000 · 4 q · 30k · 64 s |

Phase B's exit criterion — at least four of the six formats learned live to the floor with the default
plan or the escalating one — is met by either plan alone: five of six under target-first, five of six
under escalating, and every format under one or the other. The positional regex, the quoted-field
regex, the syslog and auditd splits and the Windows `Data[@Name]` mapping were all written by the model
from the rules and the instructions. What the run found:

- *The JSON document is the run's one real failure, and it is ours.* Under target-first the transform
  was refused five times on yield alone — "12 records from 1 input record is 12.0 per unit against an
  expected 1.0" — because the parser's XML has one root child, the map holding the array, and the stage
  counts a JSON document as one record (scenario 46 dropped the yield scorer for this case; the live
  row carried the default). Under escalating the same wall stood, and on its last candidate the model
  did what the scorer asked: a stylesheet that emits *one* event from the array's first item, with a
  comment saying the document is one record so one event is produced. Yield 1.0, every gate passed,
  **promoted at 1.000** with eleven of twelve events dropped — the promotion a person reviewing the
  transcript would have refused, which is phase G's exit criterion failing in rehearsal. The one target
  had been proposed for the whole document, since with no split step the root's one child was the
  record, so fidelity was satisfied by the one event. Everything follows from one miscount: a JSON
  document's records are its array's items, at the split, the target, the stage's count and yield.
  Owed in design 03 §5 since slice 16; now urgent, and the strongest evidence yet that a scorer wrong
  about the unit steers the model to a degenerate output the gate then admits (A16's concern, from the
  other side).
- *auditd under escalating*: with no split step the parser must cut and extract at once, and the model's
  line-per-record parser scored yield 0.35 against the 0.6 the document asks (0.35 per line stated),
  then its two attempts to join lines by serial left content unmatched. Target-first learned the same
  feed in eleven questions. A multi-line record wants the split question asked on its own.
- *Windows security under escalating*: promoted in two questions and 28k tokens against thirteen and
  415k under target-first — the target-first dialogue re-asked the split and the transform several
  times on the way to the same result. On a feed that is already XML with one obvious record element,
  the split and target steps cost more than they found.
- *Cost*: target-first spent 1.27M tokens over the seven rows, escalating 0.47M, for the same five
  promotions each; the escalating plan's savings are on the clean feeds (13, 14, 08), where the direct
  route passed first time and no target was ever asked.

Where it stands: the formats are learned; the plan that suits a feed is the one whose first question is
the feed's hard part — the split for multi-line text, the transform for XML — which is the case for the
plan as a per-document setting (A32) rather than one default. The JSON document count is the next
thing to build, before anything else is measured against it.

**Run 8**, 2026-09-22, is row 12 alone under both plans after slice 19 built the count (§6.1):

| Run | 7 target-first | 8 target-first | 7 escalating | 8 escalating |
|---|---|---|---|---|
| 12 JSON document | given up · 10 q · 215k · 883 s | 0.999 · 7 q · 60k · 106 s | 1.000 with one event · 6 q · 144k · 1727 s | 1.000 · 4 q · 53k · 322 s |

Under target-first the split named `events` and the transform's twelve events were a yield of one per
record; under escalating the new `SPLIT when json` step took one turn of 3k tokens, and the promoted
stylesheet applies templates over `//array[@key='events']/map` — every item, not the first. The
harness's own assertion that it ran seven rows, true of run 7 by coincidence, now says at least the
selected ones. Archived as `build/live-runs/sonnet-5-run8-json-*`.

The ninth slice, 2026-09-18, is the node's advisor (design 01 §12 item 6). `Advisors` gives a stage its
advisor per document — the document names the model and the instructions — and `ModelAdvisors`, the
node's default, answers with `ModelAdvisor` over `stroom-ai`'s chat model for the document's model: the
key resolved through the credentials service, the base URL guarded against SSRF, the HTTP client as
configured, all as `stroom-ai` already does them. It puts `QuestionText`'s words after the system text
and the attempt's transcript, counts the tokens the model charges, and audits every call against the
Shapeshifter AI document through `DocumentEventLog` — model, kind of question, turn, tokens, outcome; not
the sample, since A17's redaction is not built. No response cache stands between it and the model, which
is §10's cache bypass; transport retries are the client's own, so a dropped connection is not a failed
candidate. A document naming no model gets an advisor that fails the stream naming the Learning tab. The
`Dialogue` now holds an attempt to its budgets (A5): wall-clock from `attemptBudgetMs` and tokens from
`tokenBudget` through `Advisor.tokensUsed()`, checked around every question, exhaustion an abandonment
with the reason. A node can now learn for real; what stops it is only a model document and credit.

The tenth slice, 2026-09-18, is **learning against a target** (A31; design 01 §10.1) — scenarios 34 to 37
scripted, 38 not yet. The dialogue has a shape, `DIRECT` (A21, kept for the comparison) or
`TARGET_FIRST`, at that point the node's default — the comparison of §6.3 reversed that (A32) and
slice 11 made the shape data on the document, and A34 an example a document loads its steps from,
no more. Target-first goes: the chain question as before; then, for a parser,
the **split** question — a DS3 configuration that cuts the sample into whole records and nothing more,
judged by compile, coverage, yield and a *wholeness* check that the record text emitted is at least nine
tenths of the input, since coverage counts a consumed group as covered whatever it kept (scenario 37's
bad split, `maxMatch="1"` with `<all/>`, passes coverage and fails wholeness); then one **target**
question per record kind — kinds by `ShapeSignature`'s text skeleton of the record's first line, first
seen, at most three — asking for the event that record becomes, or `none`, each reply validated and
scored against the document's scorers as a one-event document before any configuration is written
(scenario 34's degenerate target is refused with the extraction-quality feedback); then the
**configuration** questions, each carrying the settled split and the targets. Two checks follow the
scorecard in judging a configuration: **preservation** for a parser — every leaf and attribute value the
target takes from its record must appear in some emitted record's data, the re-ask naming the values the
records lack (scenario 35: a splitter keeping two fields of four passes coverage at 1.0 and is sent back
for `logon` and `office`); **fidelity** for a transform — some event produced must equal the target,
canonically (inter-tag whitespace and namespace declarations set aside), the re-ask quoting the target
(scenario 36: `where` in `User/Id` is valid and wrong). The targets ride the `Learned` outcome onto the
regression set's `Accepted` rows, where the A18 check will read them as goldens.

The fixtures grew a **`Structure`**: a script built with `scenarios.script(splitter, stylesheet)` answers
the split and target questions from the configurations it will give — running the splitter over the
whole sample, finding the record the question's representative came from, running the stylesheet and
returning the event at that index — so a scenario states only what it is about, and `scripted()` is the
questions the scenario wrote. The Tier 2 tests build theirs with the node's parser factory, reached
inside the processing pipeline's scope. Every earlier scenario passes with the new questions in the
dialogue; 126 in the module, two in `stroom-app`.

The live comparison — the five runs of §6.2 with `SHAPESHIFTER_LIVE_DIALOGUE=DIRECT` and then
`TARGET_FIRST`, same model, same feeds, transcripts under `build/live/<shape>` — ran twice the same day;
§6.3 has what it found, and what it changed between the two.

The eleventh slice, 2026-09-18, is **the dialogue as data** (A33; design 01 §10.2; scenarios 39–40).
On the document, `DialogueDefinition`: the steps — `DialogueStep`s of a `QuestionKind`, a `StepGuard`
and per-step limits, written and
read as one line each, `TARGET when text candidates 3 kinds 2` — and override-only templates by
`Template`, with the built-ins' version stamped on save. Structural problems (`CHAIN` first and once,
`CONFIGURE` last and once, `SPLIT` and `TARGET` at most once) are the shared class's, so the client can
say them too; a template's variables are checked by the server's `Templates`, which holds the built-in
text of every template with its `${variable}` slots and renders in one pass, so a sample that happens
to contain `${amount}` is not read as a slot. `QuestionText` is now an instance over a document's
templates: it computes the blocks the variables stand for — headers, elements, sample, feedback, split,
targets, previous, rules — and `Templates` fills them; both advisors take a `QuestionText` and put the
same words. `Dialogue` walks the effective steps: chain, then whatever the list asks between, each under
its guard (raw text is a parser with a configuration to write; otherwise the input is already records)
and its candidate limit, then the configurations; a target asked before any split is proposed over the
sample's lines. A definition it cannot hold is abandoned before the model is asked, naming the rule
and the Learning tab; the store refuses the same on save. The Learning tab shows the steps as text, with
the two measured dialogues as examples to load from (`DialogueExample`, A34 — the preset the slice
first carried was taken out the same day), and the templates one at a time — the effective text, editable, "Use built-in" to drop a
change, the built-in version and the version the document was last saved against — the built-ins
served by a `templates` resource. `TestQuestionText` covers the override, the refused variable and the
slot-lookalike; the store test the refusals and the stamp; scenario 39 the reordered steps and the
abandonment. 134 tests in the module, 12 in `stroom-core-shared`, two in `stroom-app`.

The twelfth slice, 2026-09-21, is **the plan as a graph** (A37; design 01 §10.2; scenarios 41–42), and
the rename: the document's `DialogueDefinition` is its `LearningPlan`, `DialogueStep` a `PlanStep`,
`DialogueExample` a `PlanExample`, the harness variable `SHAPESHIFTER_LIVE_PLAN`; `Dialogue`, the run,
keeps its name, and the document's JSON property is `plan`. Two closed lists join the question kinds in
`stroom-core-shared`: `Check` — the ten checks a step may name, seven wrapping a scorer of §8.4 — and
`StepOutcome` — `passed`, `refused`, `compile-failed`, `run-failed` and one `<check>-short` each. A
`PlanStep` carries an id (the kind's name, or the role's, when unset), a `ConfigureRole` for `CONFIGURE`,
its checks and its `Transition`s, in the one-line grammar of §10.2; `LearningPlan.problems()` adds unique
ids, every `goto` naming a step or `end`, and CONFIGURE free to repeat. `Dialogue` is now an interpreter
walking the steps: each kind is a unit of work asked candidate by candidate through one driver, which
records the step id, candidate and outcome on every turn (`Exchange`), takes a transition the step
declares on an outcome at once, takes `on spent` when the candidates are gone, and abandons a transition
taken twice; a pass goes to the next line unless `on passed goto` says where, and `goto end` ends the plan
— which the escalating example needs, since its direct transform's pass must not fall through to the
target. A `CONFIGURE` step's role picks the chain positions it configures; an element learned again forgets
what was learned after it, and an element still unconfigured when the plan ends abandons the attempt. The
routing of §10.1 rule 6 left the code: a transform whose fidelity check fails looks at its own input, and
where the parser's records lack the target's values reports `preservation-short` for the plan to route;
the target-first example routes it `goto parser`. Scenario 41 runs the escalating plan twice — a clean
feed learned direct in three questions, and the degenerate transform twice spending the step and
escalating to a target; scenario 42 puts preservation at the transform by its checks, sees the jump
taken, and sees it refused the second time. Every earlier scenario passes unchanged, the two examples
reading into the graph with the same meaning. 140 tests in the module, 14 in `stroom-core-shared`, two
in `stroom-app`; the Learning tab's help text carries the grammar and the third example.

Audited the same day (code review, high): nine findings, all fixed. `on passed goto` fired after a step's
first unit — a `CONFIGURE` over the whole chain would leave the rest unconfigured — and now fires once the
whole step has passed, with `on passed abandon` abandoning as it says; an explicit id that shadowed another
step's default name passed validation and now collides; a `goto chain` kept the old chain's split, records
and targets and now forgets them; markup over the sample size limit was cut mid-document and is now cut at
a record boundary — the root's tag, whole children, the root closed — and text is bounded by whole lines;
a record element nested inside one of its own name counted twice and now counts its outermost occurrences,
which is what wholeness measured; `Templates.VERSION` is 2, since `SPLIT_XML` joined the built-ins; a
document saved with the plan under its old name `dialogue` reads as it was written; `recordElement`
evaluates its XPath once; the new methods' javadoc is markdown. Regression tests for each.

The thirteenth slice, 2026-09-21, opens phase B (design 03 §3) with **syslog** (scenario 43). The fixture,
`syslog.log`, is twenty sshd authentication lines from two gateways on one feed — one sending RFC 3164,
the BSD form with a month and day and no year, the other RFC 5424 with a version and an ISO timestamp —
with a golden `syslog.events.xml` of Authenticate logons naming the user, the client address and the
gateway. Two splitters: `syslog.ds3.xml` takes both forms, two regexes in one group, each sub-parsing the
message; `syslog-3164-only.ds3.xml` takes the BSD form and drops the rest with `<all/>`, scenario 4's
idiom. The stylesheet composes the BSD form's timestamp with the year the instructions supply. Under the
default key the feed is one shape: the one-form splitter scores coverage short over the learning prefix
and is re-asked naming lines 2, 4, 6 …; the two-form splitter promotes, and the output equals the golden.
With the signature in the key each form is a stream of its own shape: the first learned with the BSD
splitter, the second finds that variant dropping every line under the bound-variant trial and learns
from its own representative, and two rules result. The live harness gains row `08-syslog`. One finding,
the first of the format phase: syslog's priority prefix, `<38>`, begins with the character a naive
markup sniff looks for, and `YieldScorer` read a syslog stream as XML — zero input records, yield zero,
no split could pass. It now asks `ShapeSignature.isMarkup`, which wants an element, a declaration or a
comment after the bracket, as `OutputRecords` already did. 147 tests in the module.

The fourteenth slice, 2026-09-21, is **auditd** (scenario 44): `auditd.log`, eight interpreted audit events
of two to four lines each — logins and execve calls — with no separator, the boundary being only that
consecutive lines share a `msg=audit(time:serial)`. Three Data Splitter configurations: `auditd-split.ds3.xml`
answers the split question — one regex whose lookahead carries the serial as a back-reference, the run as
one field; `auditd.ds3.xml` parses each line of the run into a data of its type with its `key=value` pairs
and those inside a quoted `msg='…'`; `auditd-no-args.ds3.xml` the same but matching and dropping the
EXECVE's quoted arguments. `auditd.xsl` makes an Authenticate event of a login and a Process event of an
execve, the time from the epoch seconds. Two things the slice settled. A feed whose record spans several
lines says so through yield: the document's expected yield per line — 0.35 here — is what refuses a split
of one record per line, which coverage and wholeness both pass; so `YieldScorer` now judges a step whose
input is already records record for record whatever the document's basis, since a lines basis was made
for the raw input and would have judged the transform by lines of XML. And Data Splitter tries a group's
expressions in order at the current position — one that matches later than the start is an error, one that
does not match is skipped — so a `key=value` grammar anchors each expression with `^`. The live harness
gains row `09-auditd`. 148 tests in the module.

Audited the same day (code review, high) with slice 12's later fixes: ten findings, all fixed. A document
saved with a pre-A34 *preset* and no steps read as direct — the serialiser now gives it the example's steps;
the yield scorer's new markup test treated text that merely looks like markup, and a document of *no*
records, alike as text — now a document is a document however few its records (`Records.isDocument`), and
fragments or `<word>`-led lines are text, in the sample cut too; a single occurrence with a repeated *leaf*
child — one `<record>` of several `<data>` — was refused as a container, and now only a repeated child with
structure of its own makes one; feedback a transition carried was dropped when the step it named was
skipped by its guard, and now reaches the next question asked; the settled boundary is typed
(`Boundary`: a configuration or an element) rather than sniffed from a leading bracket in two places; step
ids and `goto` names are matched without regard to case; new files carry this year's header, new methods
their javadoc. Regression tests for each. 154 tests in the module.

The fifteenth slice, 2026-09-21, is **Windows security events** (scenario 45): `windows-security.xml`, fifteen
events in the Windows event namespace under an `Events` root — logons, logoffs and process creations by
`EventID` — with `windows-security.xsl` mapping the `EventID` to Authenticate or Process and the named
`Data` to typed fields, `windows-security-degenerate.xsl` copying every `Data` to a `Data` under `Unknown`,
and the golden. The split refuses `EventData` on wholeness and takes `Event`; the degenerate transform
validates and is refused on extraction quality; the typed one promotes outright, since the `Event`s are the
root's children and the stream's count is right. One finding, the second of phase B: the kinds of record a
target is proposed for are told apart by structure — the text skeleton of a line, the element skeleton of
a record — and a logon, a logoff and a process creation are one structure in this log, a System block and
a run of named `Data`, so one target stood for all three. The transform was still judged over the whole
stream and its output carries all three `TypeId`s, but the target step did not see the kinds the feed
has. What tells them apart is a value — the `EventID`, or the `Name` attributes of the `Data` — and how a
kind of XML record is recognised by a discriminating value is owed (design 03 §5). The live harness gains
row `10-windows-security`. 155 tests in the module.

The sixteenth slice, 2026-09-21, is **JSON** (scenarios 2 and 46): `records.jsonl` and `records.json`, twelve
API-gateway logins and logouts, one object per line and one document holding them in an `events` array,
`records.xsl` mapping the XSL/json vocabulary the parser emits to Authenticate events, and the golden.
The `JSONParser` step runner is run only — no configuration, no document — so the plan asks nothing
of it and the transform is asked over the parser's real XML; the `json` guard and the `SPLIT_JSON`
template join `text` and `xml`, and the walk knows its kind from the chain's first element: a parser
with a document to write is text, a parser without is JSON, no parser is XML. The split of a document
names the array whose items are records by its key, or `root` where every top-level value is one, as
JSON lines are; it is judged by whether such an array occurs and whether its items hold the document
whole. For the lines the split is skipped and the transform asked at once over two targets — a login
and a logout, two kinds — and the stream is promoted outright. For the document the split refuses
`client`, no array's key, and takes `events`; the document is learned whole, and at the stage counts as
one record, so it binds provisionally as the nested XML of scenario 37 does: the sample cut at the
array's items, and the stream's count by them, are owed (design 03 §5). Slice 15's finding is met in
part: a markup record's kind is its element skeleton with the values of its naming attributes — `Name`,
`key` — so the three Windows event kinds of scenario 45 are now three targets, and JSON maps with
different keys are different kinds; a kind by a discriminating value such as `EventID` is still owed.
The live harness gains rows `11-json-lines` and `12-json-document`. 161 tests in the module, 14 shared.

Audited the same day (code review, high): five findings, four fixed and one left as the convention it
questioned. A JSON document was told by its first bracket, so a log whose lines open with
`[Mon Sep 21 ...]` was learned whole, unheld-out and unbounded, and counted as one record, never enough
to promote — the value is now parsed, one object or array and nothing after it. A top-level JSON array is
wrapped by the parser in a keyless array under the root map, which no key reply could name, so the
split could not settle the commonest batch shape — `root` now reaches such an array's items. The record
skeleton was every element in document order with every naming value, so an array of three roles and
one of four were two kinds and a lone `<User Name="alice"/>` made every user a kind, starving later real
kinds of targets under the `kinds` cap — a record's kind is now its parsed tree with repeated siblings
of one skeleton counted once, a `key` always a field's name and a `Name` one only where the element
repeats among its siblings. Representatives were chosen by the record skeleton but counted by the text
skeleton of the first line, the same for every markup record, so a kind seen once was questioned as if
seen by all — one kind function serves both. Not changed: a run-only parser that cannot read the sample
abandons the attempt from the split as it does from `CONFIGURE`, outside the candidate loop and the
step's transitions; whether a wrong choice of parser should route back to the chain question is a
ruling for the plan, not this slice. 165 tests in the module.

The seventeenth slice, 2026-09-21, is **fixed-width** (scenario 47): `fixed-width.log`, thirty mainframe
sign-on lines of six columns by position with no delimiter — a fourteen-digit time, the user, the terminal,
the action, the result and a reason that holds spaces — with `fixed-width.ds3.xml` capturing the six by
width, `fixed-width-four.ds3.xml` capturing four and matching the rest of the line away, the stylesheet
over each, and the golden. Under the escalating plan the four-column parser passes: every character is
consumed, so coverage is 1.0 and has nothing to say, which is the point of the format. The transform
written over four columns cannot state an outcome, the business rule refuses it twice and `on spent goto
target` fires; the lines are two kinds — a reason of two words and of one — and a target is proposed for
each; asked again against the targets, the four-column parser is caught by preservation, the records
carrying no value for `PASSWORD OK` or `REVOKED`, and the six-column parser passes; the transform over
it is promoted outright, its output the golden. Nothing in the module needed to change: the escalating
plan, preservation and the positional regex were already there, and the slice is the fixture, the
scenario and live row `13-fixed-width`, whose document carries the outcome rule. 166 tests in the
module.

Audited the same day, the range since upstream (the owner's code review): six findings, all fixed. A
plan could say `CHAIN on refused goto end` and be held, and a refused chain then left the interpreter
with no chain and a null to dereference rather than an abandoned attempt — `CHAIN` takes no transition,
checked with the other constraints. A run-only element consulted `on <outcome> goto` but not `on spent
goto`, though its one run is its only candidate — both are consulted, from `CONFIGURE` and from the JSON
split alike. A split that consumed every line and emitted no records was judged wholeness-short whatever
checks the step named — it is refused, as a reply that is no split. A step id arriving as JSON was
trimmed and lower-cased but not checked, so `my step` saved by the API could not be read back by the
Learning tab — one pattern serves both forms. Markup that is not one document — a fragment per line, no
root — was learned whole, unheld-out, because its first character said markup — a document is one that
parses, and anything else is lines. A run-only element that could not read its input was reported as
compile-failed, a gate it has no configuration to fail — run-failed, so `on run-failed` routes. 169
tests in the module, 15 shared; GWT draft compiled.

The eighteenth slice, 2026-09-21, is **CSV with embedded newlines** (scenario 48), the last format of
phase B: `csv-multiline.csv`, twenty headerless records of a document store's audit whose note is quoted
where it holds a comma, a doubled quote or a line break, six of them spanning two lines;
`csv-multiline-split.ds3.xml`, one regex over the stream matching a record from its start with
`"(?:[^"]|"")*"` for the quoted field and emitting it whole; `csv-multiline.ds3.xml`, the same regex
into six fields; the stylesheet, which unquotes the note and makes a View or Delete on a Document; and
the golden, the line break intact. The catalogue expected the line split to fail *wholeness*, and it
does not: wholeness is a character share (A36's tuning), and a split of one record per line loses no
characters of a quoted record, only its boundary. What refuses it is yield against the lines a record
takes, which the document states as 0.77 per line and the line split scores 1.0 against — the same road
auditd took in slice 14 — before any target is asked. The DS3 rules now say how a quoted field is
matched, in the split's and the parser's text alike. One thing arranged rather than solved: the learning
prefix is cut by lines, so a quoted record can be cut in two at the sample's end; the fixture's six
two-line records are placed so that the four-fifths cut lands on a boundary, and cutting the prefix at
the settled boundary is owed (design 03 §5). Live row `14-csv-multiline`. 170 tests in the module.
Phase B's scripted half is complete: six formats, six scenarios green.

The nineteenth slice, 2026-09-22, is **the record boundary on the rule and in the stage's count and
yield** (A35), pulled forward from phase C on run 7's evidence (§6.3). `RecordBoundary`, shared: the
element that is one record in XML, or the key of the JSON array whose items are records, `root` for
top-level values; a routing rule carries the one its variant was learned with, null for raw text and for
rules from before. The dialogue's learned outcome carries the split's boundary; `Attempted` carries it to
the scorers, so the yield scorer counts a records input by it — `OutputRecords.recordsBy`, which the
target checks now read through too — and the stage counts the records a stream brought by it, on the
parser's XML for JSON and on the input for XML, on the learning stream and, through the fragment runner,
on every stream the rule serves, at binding, promotion, retraction, relearning and the regression set.
The escalating example gains `SPLIT when json`, one cheap question without which a JSON document is one
record to that plan (design 01 §10.2; whether XML should have the same is the owner's). Scenario 46's
document case is promoted with yield judged and the rule carrying `array events`; scenario 37's nested
XML is promoted rather than provisional, its rule carrying `element entry`; and run 7's trap is a test —
a transform emitting one event from the first item is "1 record from 12 input records", refused. Run 8
(§6.3) confirmed it live under both plans. 175 tests in the module, 15 shared.

The same day the owner ruled A20–A22 as built and, from run 7, A39 and A40 (design 01 §13): the
escalating example asks the split of every input — one question, on which the count, the target and
yield rest — and a parser refused on yield goes back to the split (`CONFIGURE parser on yield-short
goto split`) rather than being re-asked blind. Scenarios 41, 42 and 47 gained the split turn; scenario
44 gained the escalating case run 7 gave up on — the split by serial holds, a parser cut per line is
refused on yield and sent back, the split holds again and the parser asked again is held to it —
promoted in six questions with no target asked. Design 01 §2.2 records phase B's first values of the
thresholds. 176 tests in the module.

The same day, in discussion, the owner asked whether the split carries what a record needs
downstream — the header with every CSV record, as a DS3 parser does; the parser then `SplitFilter`
for XML and JSON — and whether fragments without a root are handled. Three answers (design 01 §12
items 25 and 26). The header: the corpus's `001_csv_with_header` reads the header into a `var` and
names every record's data `$heading$1`, and scenario 1's script replies with it, but the rules taught
"a header line is a record too" and the live model hard-coded the names every time — a reordered
column would misfile silently. The extraction rules now carry a second worked example, the `var`
form, and say the header is then not a record; a test runs both examples and shows the columns
reordered still land in the right fields (built-in templates version 5). The `SplitFilter`: the
learned boundary is exactly its setting, and it belongs in the written fragment between parser and
transform, with the transform asked over one record's document — phase D, item 25. Fragments: XML
without a root is text to the stage today and its attempt is abandoned; the `XMLFragmentParser` as a
run-only step, the walk's kind following the parser's output, is scenario 49, phase D; JSON without
a root is the parser's root map and the split's `root`, handled by scenario 46. 177 tests in the
module.

Audited the same day, the working tree and the range (the owner's code review): seven findings, six
fixed and one kept by decision. The stage counted a stream of markup that is not one document — a
fragment per line — as bringing no records, the failed parse clamped to zero, so such a shape bound
provisionally and stayed so; it brings its lines, as the yield scorer counts them. The DS3 rules' text
changed without the built-in version rising — 4 now. The Learning tab threw on an empty steps box on
every change, since dirtiness is computed on each; an empty box keeps the saved steps, and the store
refuses a plan without CHAIN and CONFIGURE on save. A JSON document over the sample size limit was sent
whole — cut by lines beyond the limit until the cut by the array's items. Two transitions on one outcome,
or two on spent, were held with the first silently winning — refused. A byte order mark made XML or
JSON read as text to every classification — stripped where the stream is read, and ignored by the
classifications. Kept: an XML document with a DOCTYPE is not one document to the module, since the
hardened reader every parse here uses refuses it, and that refusal is deliberate (XXE); such a feed
needs the declaration removed before the stage, and the module treats it as text until then. 173
tests in the module.

Audited the same day (code review, high): nine findings, eight fixed and one found not to hold. The
serialiser threw where a document had no meta asset and parsed every document's JSON twice — it now
returns as the delegate does and looks for the old name in the bytes before parsing; feedback a transition
carried to a `CONFIGURE` step over a run-only element — a `JSONParser`, scenario 46's shape — was consumed
there and never reached the transform, and a run-only element is now run and judged outside the candidate
loop, leaving what was carried for the next question asked; `Scorecard.outcome` named whichever failing
scorer the document listed first, so a non-gate listed before a gate masked the gate's `-short` and the
plan's transition on it — a failed gate now decides; the yield scorer parsed a records input twice; an
at-once `on <outcome> abandon` reported the step's candidates as spent when one had been asked; the
Windows fixture and golden carried doubled backslashes in paths; three test classes' javadoc was
block-style. The claim that a one-record sample with a repeated structured grandchild is refused as a
container did not hold — the check looks at an element's own children — and a test now says so. 158
tests in the module.

The twentieth slice, 2026-09-22, is **the rules as rows** (A41), the first of phase C and the one that
had to come before the tables: `Rules`, a seam beside `Shapes`, `Ledger`, `Outputs`, `Reprocessing` and
`RegressionSet` — the document's rules in router order, appended, replaced, removed by uuid — with
`InMemoryRules` behind it until the A26 module lands. The routing table is gone from
`ShapeshifterAiDoc`: the stage reads the rules for the document it is learning for and writes one row
at a time at binding, promotion, retraction, rebinding, drafting, approval and rejection; `approve` and
`reject` no longer return a document; and the supervisor element's `writeDocument` is gone, so learning
never touches the document at all. What that buys is what A41 asked for: two nodes promoting two shapes
of one document write two rows instead of racing to rewrite one document, and the operator editing the
Learning tab no longer shares an optimistic lock with the machine.

The document's dependencies are now its model alone — an exported document carries its configuration
and none of what it learned, as a processor filter's state is not part of the pipeline it runs — and the
store no longer names or numbers rules. The Routing tab fetches the rules for the document through
`GET /shapeshifterAi/v1/{uuid}/rules` and saves them with the document through `PUT …/rules`, which
checks each fragment is a fragment (A20) as the store used to on save; a row-level Routing tab, each
action its own call, is owed with the operator surfaces of phase F. The scenario tests read the rules
from the seam rather than from the document they got back, which is also how they now seed a reserved,
pinned or draft rule. One test had to be told that rules outlive a stage: two runs against one feed in
one scenario now bind what the first promoted, which is what a cluster would do. 177 tests in the
module, 15 shared, 2 in the app; GWT compiled.

Audited the same day (the owner's code review): seven findings, six fixed and one accepted. Two were
the slice's own and serious. The whole-table `PUT` applied neither the operator's order nor their
position — an existing rule was replaced in place and a new one appended — so *Move up* did nothing and
a rule added at the top, "the operator has just decided it is the most specific", landed *behind* every
learned rule, where the router's first match never reaches it: their precedence was inverted. And the
two new endpoints went straight to the seam, so any authenticated user could read or rewrite any
document's rules by uuid, pointing a rule at a pipeline of their choosing to run on that feed's data.
The answer to both is the row-level Routing tab that was owed to phase F, brought forward: `POST
/rules?at=`, `PUT /rules/{uuid}`, `PUT /rules/{uuid}/position?to=`, `DELETE /rules/{uuid}`, each
checking `VIEW` or `EDIT` on the document and each answering with the table as the server now holds it.
That also closes the third finding — the tab saved the snapshot it fetched on open, on every document
save, so a rule promoted meanwhile was deleted as absent — since the tab no longer saves a table at
all: one action, one call, and the answer re-renders the list, so a rule promoted while it was open
appears rather than being overwritten. A document that learned before A41 lost every rule on upgrade,
the serialiser migrating `dialogue` but not `routingTable`; the first read of such a document now puts
its rules where they belong, once, and only where the rows have none, at the cost of one `contains`
over bytes already in hand on every other read — the A26 module will do it as a migration of its own.
A preset this build does not know now leaves the document openable, with the default plan's steps,
rather than unopenable and so unrepairable. The store's dead fragment check and stale javadoc are gone.
Accepted: `InMemoryRules` is a node-local map until slice 21 gives it tables — which slice 20 made
observable by putting it behind the UI, and is what phase C exists to fix. 185 tests in the module.

The twenty-first slice, 2026-09-22, is **the tables** (A26): `stroom-shapeshifter-ai-impl-db`, in the
pattern of `stroom-ai-impl-db` — a Flyway migration, jOOQ codegen against a temporary database, its own
connection provider and config — holding the five tables A41–A44 settled on. `shapeshifter_rule` is one
row per rule with a `sort_order`, since the router takes the first match and an operator may move a rule
above the learned ones; the selector is the JSON of its expression, read and written whole because
nothing queries into it. `shapeshifter_shape` is one row per `(doc, shape)` with what the stage knows —
given up, marked, awaiting review — its rolling score, and the lease columns A42 will use.
`shapeshifter_ledger` is one row per sentinelled input. `shapeshifter_feed_state` and
`shapeshifter_spend` are written now and used when A24 and A44 are built.

`RulesDao`, `ShapesDao` and `LedgerDao` implement the seams the stage has used since slice 8. A
promotion is one insert and a rebinding one update, so two nodes promoting two shapes of one document do
not contend; an insert at a position moves the rules below it down in one statement; a rolling score is
folded inside a transaction on the row, so two nodes serving the same shape do not each read the old
mean and write over the other's; and the ledger's release reads and deletes under `for update`, so two
nodes promoting the same shape cannot both replay the same backlog. A shape row is made with
`onDuplicateKeyIgnore`: two nodes meeting a new shape at once both insert, the unique key decides, and
the loser reads what the winner wrote.

The node now takes its rules, shapes and ledger from the tables — `DbConnectionsModule` installs the
module, and the element module no longer binds the in-memory ones — while a harness without a database
installs `InMemoryStateModule` instead, which is what the mock-service tests of `stroom-app` do. Eight
tests run against MySQL 8.4 in the module's own source set: a rule survives the round trip whole and in
its order, an operator's rule sits where they put it, a rule moves and the others close behind it, a
replace keeps a rule's place and a promotion whose row was pruned goes back in, one document's rules are
its own, a shape remembers why it was given up, the rolling score is of what it has done lately, and the
ledger releases once. What is owed to finish phase C's exit criterion: the Tier-2 scenarios of design 02
§2 run under the mock-service harness, so they exercise the stage against memory; running scenarios 20,
30 and 31 against the tables needs the `CoreTestModule` harness, and is the next slice's, with the lease
(A42) and the cluster-wide spend (A44) that the shape and spend tables are now ready for.

Audited the same day (the owner's code review), fifteen findings over the branch, all fixed. The one
that mattered most was mine and simple: the module's config was not reachable from `AppConfig`, so
`./gradlew build` failed on the presence test and an operator could never have pointed the tables at
another database — a `ShapeshifterAiConfig` now carries the DB config under `shapeshifterAi`, and the
expected YAML is regenerated. I had run the touched modules, not the build.

Two in the learning path. A JSON stream over the sample size limit was cut by lines, which makes JSON
that will not parse, so the split abandoned the attempt: every real feed over 8 KB failed, and nothing
caught it because the fixtures are 2.9 KB. It is now cut at the items of its widest array and closed
again, as a long XML document is cut at the root's children — the cut design 03 §5 had owed since slice
16, now load-bearing. And the array key the model replies with goes into an XPath predicate: the reply
was validated as anything without spaces or angle brackets, so `a]|//x[` reached Saxon, which threw
where nothing catches — one bad reply failed the stream instead of refusing a candidate. A key is a
word now, at the reply and again in `recordsBy`, which names nothing rather than building a predicate
it cannot quote.

Four in the tables. `remove` left a hole in `sort_order` while `insert` and `move` read it as a place in
a list, so after a delete an appended rule landed above the last one and a move to the end did nothing;
the rules below now close up. `insert` and `move` ran their statements outside a transaction, unlike
`scored` and `release`, so a shove that committed without its insert would leave a hole and no rule.
`replace` bumped a version it never compared, so a supervisor rebinding a rule and an operator editing
its selector both succeeded and one was lost; they are serialised on the row. The ledger had no
uniqueness, so a stream sentinelled twice would be replayed twice — the thing its own javadoc warns
against. And `shape_id varchar(500)` could not hold what a learning key naming a sender-supplied header
produces: rows are keyed on the id's hash now, with the id beside it for a person reading the row.

The rest: yield counted the output by the root's children while counting the input by the boundary, so
a parser's XML of one map holding twelve items scored one record in twelve lines; a `read` wrote to the
database, which made an import's confirmation screen write rules for a pack the operator then cancelled
— a read is a read now, and the store's `readDocument` does the migration; the Routing tab's copy
dropped the record boundary, which is not history but how a bound stream is counted; the Learning tab's
modal fired on every dirty check, and the fault is shown beside the steps; a boxed `Integer` was
compared by reference; and the rule dialog turned a catch-all's null selector into an empty `AND`, which
the grid and the draft-pairing both read differently. 186 tests in the module, 11 against MySQL.

The twenty-second slice, 2026-09-22, is **scenario 20 against the tables**, the first of phase C's exit
criterion. Tier 2 had been running under the mock-service harness, which has no database, so what it
proved about the ledger it proved about a map in one node's heap.
`TestScenario20SentinelInAPipeline` extends `AbstractCoreIntegrationTest` instead — the node's own
wiring, `DbConnectionsModule` and all — and asserts what it is testing with: the injected `Ledger` is a
`LedgerDao`, the injected `Rules` a `RulesDao`. A stream of an unknown shape under a document with
learning off produces no output stream, one `ERROR` naming the shape, no rule, and one row in
`shapeshifter_ledger` naming the input's meta id, which `release` then hands back — the replay A12
promises, from a row that survives the node. Scenarios 18 and 19 stay under the mock harness, where
they are about the pipeline rather than the tables, and are quick.

What phase C still owes: scenarios 30 and 31, which need A28's durable attempts — the attempt and turn
tables, the dialogue as a state machine that stops at a question and resumes on any answerer, and the
worker that advances one awaiting the model — and the lease (A42) and cluster-wide spend (A44), whose
columns the shape and spend tables already carry.

The twenty-third slice, 2026-09-22, is **the lease and the spend counter** (A42, A44), which make those
two rulings real rather than designed. The lease is a conditional update on the shape row: one learner
per shape across the cluster, taken before the dialogue and given back in a `finally`, its expiry the
attempt's own budget and a little more, so a node still working keeps it and one that died lets the
next in soon after it would have finished. A node that does not win it does not wait — that would hold
a processing thread for the length of an attempt, and at hundreds of threads a shape's first minute
would stall the cluster — it sentinels its stream, and the winner's promotion releases the backlog as
A12 releases any other. The stage learns which node it is from `NodeInfo`.

The spend is a `Spend` seam and a row per document: what an attempt cost — the tokens the model charged
and the questions put to it — added to the document's window inside a transaction, so that two nodes
finishing at once do not each add to the same old total. It is counted whether the attempt learned
anything or not, since an attempt that abandons costs what it asked. The window is an hour for now; the
policy that reads the count — the per-document rate limit and the spend breaker that opens error mode —
is A24's, and is built with it in phase E, which is why this slice adds a counter and no refusal.

Five tests against MySQL: one node learns a shape and the other is told to go away, the holder may take
its own lease again, a different shape is a different lease, a node cannot release what it does not
hold, an expired lease is free, resetting a shape leaves the lease alone — that last because a
promotion resets the shape while the attempt that promoted it still holds its lease — and every node's
spend is counted in one place, one document's being its own and a window that has run out starting
afresh. Two scenarios in the module: a second node meeting a shape sentinels rather than waiting, and
its stream is released by the winner's promotion; an expired lease lets the next node learn. 188 tests
in the module, 15 against MySQL.

Audited the same day (the owner's code review), seven findings, all fixed, and the first of them showed
that the paragraph above said something the code did not do. The lease was released as soon as the
dialogue ended, not when the rule was written, so between the two — the judgement over the whole
stream, the fragment's documents, the rule's row — another node found no rule for the shape, won the
lease and learned it again, and both appended a rule for one selector: the router serves the first and
the second's fragment is orphaned. It is held now until `bind` or `givenUp` has run. The test that
pinned the old claim had been written from the claim rather than against the code; the new one puts a
second stage's run at the moment the rule is appended, and fails if the lease is released any earlier.

The expiry was set from the stage's clock and judged against the wall clock, so a scenario whose clock
is fixed — every scenario — took leases that were expired on arrival and excluded nobody; the two
scenarios hid it by planting the competing lease by hand. The seam takes the caller's *now* as well as
its *until* now, and the new scenario lets a second stage take its own. Relearning called the model
without any lease at all, so two nodes could relearn one shape and, in review mode, each write a draft
and overwrite the other's `awaitReview` — leaving a draft that every later stream sentinels on and that
approval cannot resolve; it takes the lease too, and a node that does not win it serves the stream from
the incumbent, which is what an incumbent is for. Nothing extended the lease during an attempt, though
this section called re-taking it the heartbeat: the dialogue now heartbeats before every question, so a
slow model call cannot cost a node its lease.

`InMemoryShapes.reset` dropped the whole row and with it the lease, where the row keeps it, so the two
implementations of one seam disagreed exactly where A42 matters. The spend was recorded after the
dialogue returned rather than in a `finally`, so an attempt whose model call threw — the runaway A24's
breaker exists to see — showed no spend at all. And both DAOs inserted with `ON DUPLICATE KEY IGNORE`
before selecting `FOR UPDATE`, which on the steady path leaves a shared lock the select must upgrade:
two nodes recording spend for one document at once would deadlock on that upgrade, which is the very
thing the table is for. The row is looked for before it is made. 189 tests in the module.

The twenty-fourth slice, 2026-09-22, is **the attempt as a record** (A28), the first of the four that
A28 needs. `shapeshifter_attempt` and `shapeshifter_turn` join the A26 module: one row per attempt —
which document, which shape, which stream raised it, which node took it, both modes, its status, what
it came to and the rule it wrote — and one per turn, with the question, the answer, who answered it and
what the answer scored. An `Attempts` seam, a DAO and an in-memory twin as every other seam has. The
stage opens an attempt when it commits to learning a shape, writes a turn for every exchange of the
transcript, and closes it with the decision's own words; an attempt whose node, model or database threw
is closed `ERROR` rather than vanishing. Nothing about how the dialogue runs has changed: this slice is
the record, and the resuming is the next one's.

Two things it does not do, for reasons worth stating. It does not store the rendered prompt, only a
line saying what the turn asked — the kind, what it was about, and how much the step had been told —
because the prompt carries the stream's own text, which may not be stored until A17's redaction is
built (A38), and which `stroom-ai` audits in any case. And the attempt is not yet the claim on the
shape that A45 makes it: the shape row's lease still holds that, and the attempt row carries the expiry
column that will take it over when the dialogue can be resumed. A node that does not win the lease
opens no attempt, since it learned nothing.

Ten tests against MySQL now, including that an attempt and its turns read back whole and in order, that
a shape id longer than any column is still one attempt, and that the newest attempt of a document comes
first — which is what the Supervisor view lists. In the module: every attempt is recorded with its
turns and what it came to, an attempt that bound nothing says so, and a node that lost the lease
records none. 191 tests in the module.

Audited the same day, at the owner's asking, before the resuming is built on these tables: eight
findings, all fixed. The worst was invisible to every test: the node makes a new advisor for each call,
each counting its own tokens, so an attempt that asked one and read another recorded nothing spent —
every row said zero, and the harness could not tell because its advisor is one shared object. One
advisor is now resolved per attempt and passed to the dialogue, which is what `learn` already did and
what the recording did not. The same re-resolution inside the catch block could throw over the failure
that brought it there, leaving the attempt stuck at `IN_PROGRESS` — the state that block exists to
prevent.

Two were about what a record may cost. The turns and the closing row were written after the rule was
bound and the output emitted, so a record that failed would fail a stream that had succeeded — a
document whose model reference carries no name would have done exactly that, against a column that may
not be null. Bookkeeping is now guarded: it logs what it cannot write, and never throws over the work
it describes. And this section's own claim that "a turn is inserted as it is answered" was not true —
they were written in a loop at the end, so an attempt still running showed none and one that threw lost
every question it had asked. The dialogue now reports each turn as it is answered and again as it is
judged, and the row is written by number, so an attempt that fell over keeps the transcript a person
most needs.

The rest: a decision was recorded as its own `toString`, which for a shortfall carries the scorers'
diagnostics and so the stream's own text — the very thing the turn's description withholds until
redaction exists (A38); it is the decision's own words now. An attempt that wrote a draft stayed
`AWAITING_REVIEW` for ever, since approval and rejection said nothing to the record, and `REJECTED` was
written by nothing at all. `forDocument` asked for each attempt's turns in its own query, which the
Supervisor view would have done a hundred times a page. And a relearn where the incumbent won is
recorded as abandoned, which the decision's words distinguish but the status does not — whether that
deserves a status of its own is for the Supervisor view slice, since A28's list of statuses is the
owner's. 194 tests in the module, 16 against MySQL.

The twenty-fifth slice, 2026-09-22, is **the attempt resumed** (A28), and the shape of it is the point.
The dialogue is not recast as a state machine that saves its workings: it is re-walked from the start
with the answers it was given. `RecordedAdvisor` answers from the attempt's turns in the order they
were put and then hands on — to the model, for a worker carrying an attempt forward; to nobody, for one
that is to stop and wait. Everything those answers produced is re-derived on the way: the chain, the
boundary, the records, the targets, each element's configuration and its output, because all of it
follows from the sample and the answers, both of which are kept. What the model said is a record; what
running produced is a consequence, cheaper to re-derive than to store and the stream's own text besides
(A38). A replayed answer is judged exactly as it was judged the first time; that the walk has reached
the same place is checked rather than assumed — see the audit below.

A45 is built with it: the claim on a shape moves from the shape row to the attempt. One open attempt
per `(doc, shape)` is what one learner means; a parked attempt still holds it, since it is still
learning; one whose expiry passes has lapsed and the next node takes the shape. A stage that reaches a
question nobody present can answer parks the attempt `AWAITING_MODEL`, sentinels its stream and
returns, which is deferred mode's shape without the worker that will drive it (slice 26).

The clock caught it again: the claim's expiry was set from the stage's clock and judged against the
wall clock, exactly as the lease was before the last audit, and every scenario's claim was lapsed on
arrival. This time a test found it rather than a review — the scenario that says a waiting attempt
holds its shape failed, because it did not. The seam takes the caller's *now*, as the lease does.
195 tests in the module, 17 against MySQL.

The audit of slice 25 (the owner's code review) found eleven, all fixed, and the first of them was a
sentence in the paragraph above. "The replay is its own check" was written of code that checked
nothing: `RecordedAdvisor` handed out its answers in order, to whatever was asked. A document edited
while its attempt waited — one more allowed element, a changed instruction — puts different questions,
and the answers kept would have been given to them, judged, configured and possibly bound. Every
question now carries a one-line `summary()` of itself, which is what a turn has always recorded, and a
replayed answer is given only to the question whose summary matches the one it answered. A walk that
asks anything else has diverged: the attempt is refused with `ReplayDiverged` and recorded as an
error, rather than carried on from a mixture of two walks. Everything in that line is re-derived by
walking the same plan over the same sample, so two walks that have reached the same place write the
same line, and the check costs nothing.

`resume` itself took no notice of who held the attempt or what it was about: it would carry on one that
another node was learning, one that had finished, and one whose stream was of another shape entirely.
It now takes the claim before it walks — `claimed`, which succeeds only for the holder or for an
attempt that has lapsed — compares the stream's shape with the attempt's, and sentinels or refuses
rather than learning beside another node.

The deeper finding was two mechanisms for one rule. The shape's lease (A42, slice 23) and the attempt's
claim (A45, slice 25) both said who was learning a shape, and the stage took both: `run` leased the
shape, `recording` opened the attempt, `resume` opened neither and leaked the lease on every path.
Two rows that must agree are a bug waiting for a schedule, which is exactly what A45 was ruled to
prevent, so the lease is gone — the seam, both implementations, its tests, and the columns, dropped in
`V07_13_00_004`. What is left is one claim, and it is the database that holds the rule: `claim_key`
carries the shape's hash while an attempt is open and nothing once it has closed, under a unique key on
`(doc_uuid, claim_key)`, so two nodes meeting a new shape are decided by the key and not by what each
read. The heartbeat, the lapse and the release all move to the attempt row with it.

The rest were of a kind: a parked attempt's tokens overwrote what it had spent before rather than
adding to it, so a resumed attempt's cost was only its last leg, and its A5 token budget started again
each time it woke — `Dialogue.alreadySpent` carries it across. A replayed turn was rewritten with the
model's name over whoever had answered it, which would have made a person's answer the model's in the
record. A draft awaiting review was counted as still holding its shape, though it holds a rule instead
and a person's decision is what moves it. `parked` would raise a finished attempt back into life. And
the in-memory twins had drifted from the tables in three places — the newest open attempt, the claim
cleared on close, a stream sentinelled twice for one shape being one row — each now as the DAO has it,
since a scenario that passes over a twin that lies proves nothing. 197 tests in the module, 16 against
MySQL.

The twenty-sixth slice, 2026-09-22, is **deferred mode and its worker** (A5, A28), which closes scenario
30. Until now every document learned in the task whatever its mode, and deferred is the *default* mode —
an LLM call inside a processing task holds a task slot and a volume handle for as long as the model
takes, which is the whole reason A5 exists. A deferred document now asks nothing in the task: the stage
opens the attempt, parks it at its first question and sentinels the stream, and `DeferredWorker` carries
it on outside the task, at whatever pace the model answers. What it promotes releases the shape's ledger
as any other promotion does, so the streams that waited are reprocessed (A12).

The worker needs two things the stage was always handed: the document, and the stream the attempt was
raised on. Both become seams — `Documents` and `Inputs` — with in-memory implementations for the
harness, because the sample is not copied into the attempt: a stream's own text may not be stored until
redaction is built (A38), and a stream is already kept where streams are kept. An attempt whose stream
has been aged off, or whose document has been deleted, is abandoned with the reason rather than left
waiting for ever, since the shape it holds is a shape nothing else may learn. One attempt's failure is
its own — a pass that stopped at the first bad attempt would never reach the good ones behind it.

Writing the relearn case found a real fault. An attempt is resumed by re-walking the dialogue, and
`resume` walked every attempt as though it were learning an unknown shape: a *relearn* (A29) carried on
by the worker appended a second rule for one selector instead of rebinding the incumbent, leaving the
first rule's fragment orphaned behind it — exactly what the claim exists to prevent, arrived at from the
other side. What an attempt is doing is not stored either, and need not be: it is read from the rules as
they stand, and a shape a rule already binds is being relearned. With that, the shape's mark moves too —
it is spent when the relearning has happened rather than when it was scheduled, or an attempt that parks
would find the shape changed under it and its re-walk would ask a different question. While it waits,
the shape's other streams are served by the incumbent, as they are served throughout a relearning, and
the stream in front of the stage is `Kept` rather than sentinelled: a bound shape's stream does not
belong on the ledger.

A parked attempt is nobody's — no thread is behind it — so any node's worker may take it up; a *running*
attempt is its own node's until its claim lapses (A45). That is one line of the claim rule, and it is
what lets the job be a cluster job rather than a node's own business.

Every scenario document now says `INLINE` for itself, through one fixture: the catalogue's scenarios are
about what learning does, not about when it happens, and scenario 30 is the one that is about when.
202 tests in the module, 16 against MySQL, 3 in Tier 2.

The twenty-seventh slice, 2026-09-22, is **the worker in a node**: what slice 26 owed. `StageFactory`
builds the stage that both the supervisor element and the job run — one list of runners and scorers, not
two — `StreamInputs` reads a stream back out of the store by the id the attempt kept, `StoreDocuments`
reads the document, and *Shapeshifter AI Deferred Learning* is a scheduled job that carries a bounded
batch on as the processing user, once a minute by default and editable in Jobs like any other. Scenario
30 is now green in Tier 2 as well: the processing task asks nothing and writes its error stream, the job
runs with no task in front of it, and the next stream of the shape is bound by what the job learned.

Two things had to give first. A stage cannot be a singleton: the Data Splitter compiler and the schema
scorer report through the pipeline-scoped `ErrorReceiverProxy`, so the job enters a pipeline scope of
its own — no pipeline in it, but a candidate configuration is compiled and run there exactly as it is in
a task.

And the reprocess request had to stop guessing its pipeline. It took the pipeline from the task it was
running in, which is no answer at all for a job outside one, and was already the wrong answer for a
document used by two pipelines — a release names streams that several pipelines may have sentinelled,
and a stream replayed through a pipeline that never saw it produces something nobody asked for. The
ledger now records where each stream it names was being processed; a release hands back rows rather than
ids, and the stage asks once per pipeline. A stream sentinelled by no pipeline — the harness, the worker
itself — is released and nothing is asked for it.

The wiring found two things already broken on this branch, both from slice 21 and both invisible to the
module's own tests: `TestConfigMapper`'s `AppConfig` subclass never gained the Shapeshifter AI config
that was added to `AppConfig`, so `stroom-config-global-impl` did not compile, and
`ConfigProvidersModule` — generated — had not been regenerated, so the config had no provider. Both are
fixed, the second by running its generator. 202 tests in the module, 16 against MySQL, 4 in Tier 2.

**Still owed.** The batch size is a constant in the job rather than a configuration property: the
feature's `ShapeshifterAiConfig` lives in the impl-db module, which the module the job lives in cannot
depend on, so giving an operator that dial means moving the config class first — worth doing when there
is a second thing to put in it.

The audit of slices 26 and 27 (my own, 2026-09-22) found one thing that mattered and several small ones.
**`resume` never asked what the shape had become while its attempt waited.** An attempt may wait hours;
in that time an operator may reserve the selector or turn learning off, a person may have a draft of the
shape in front of them, or it may have been given up — and the worker would carry the attempt on
regardless, appending a rule for a selector an operator had just decided binds nothing. The shape's
state is now read exactly as `run` reads it for a stream, and an attempt the shape no longer wants is
closed with the reason rather than carried on.

The worker counted a refused attempt as one it had carried on: an attempt another node had taken, or one
the shape no longer wanted, came back as a sentinel and was counted as progress. And `awaiting` hid a
parked attempt whose claim had lapsed, which is the wrong reading of the claim: nobody is behind a
parked attempt, so there is nothing to take it from, and a worker that was down while the claim lapsed
must still pick it up or the shape waits for a stream that may never come. The expiry now says who may
carry an attempt, not whether it is still wanted.

The rest were housekeeping: three constructor parameters left behind in the element when the stage moved
to its factory, two accessors nothing calls, a doubled Javadoc block and a doubled catch.

Three things are noted rather than changed. `claimed` lets a node take an attempt it is already running,
which is what a node re-entering its own attempt must do; what keeps two of a node's own threads off one
attempt is that a scheduled job cannot overlap itself, and that is now said where the method is. The
worker's promotion records an output binding for an output nobody wrote, since the worker emits no
stream — an artefact of the in-memory `Outputs`, which is a meta search over output attributes in a node
once its table arrives, where the phantom cannot exist. And a retraction still replays through the
pipeline of the stream in front of the stage rather than each output's own, which the ledger now knows
how to do and `Outputs` does not; it is the same approximation as before, and worth closing when
`Outputs` becomes rows. 204 tests in the module, 16 against MySQL, 4 in Tier 2.

The twenty-eighth slice, 2026-09-22, is **a person answering a turn** (A28), which closes scenario 31
and with it phase C's exit criterion. *Answer instead* and *edit and re-run from here* turn out to be
one operation over the machinery slice 25 built: write an answer against a turn, drop the turns after it
— what they were is a consequence of an answer that has changed, and re-walking derives them again —
and leave the attempt waiting for the worker. Nothing runs when a person clicks: their request must not
wait on a model, and the attempt is picked up as any other waiting attempt is.

For that to be answerable, an attempt that stops must say what it stopped at. It now records the
question as a turn with no answer and no answerer — which is also what a person needs to see in the
Supervisor view, and what the replay compares its re-walk against once they have answered. The dialogue
is what knows where a walk stopped, so it fills in the step, the candidate and the number as the
`AwaitingAnswer` passes through it; `answered_by` becomes nullable, since nobody has.

An attempt that had finished is opened again and takes its shape back under the same unique key that
admits one open attempt per shape (A45), so re-running an attempt whose shape another is now learning is
refused and the person is told. Re-running also resets the shape: a person saying "run this again with
my answer" is saying the shape is not settled, and without that the re-run would be refused by the
give-up its own first run wrote.

The scenario itself: the model spends its one candidate on a splitter that drops the fields the event
needs, the attempt is given up, a person puts the right configuration in place of the model's, and the
attempt runs again — everything before their turn replayed from the record, their answer given where the
model's was, and only the transform asked. The transcript says who answered each turn, model and person
alike. 209 tests in the module, 20 against MySQL, 4 in Tier 2.

The audit of slice 28 (the owner's code review) found six, all in the new pair of operations and all
fixed. `amend` opened the attempt again *before* it checked that the turn a person named exists, and
nothing undid that: a mistyped or stale turn number would turn a promoted attempt into an open one with
its outcome wiped, tell the person their request had failed, and leave the deferred worker to pick it
up. The check comes first now, from the record already read.

An attempt a node is walking *at this moment* was quietly amended underneath it. Reopening treated any
open attempt as nothing to do, so the answer was written, the walk's next turn overwrote it with the
model's, and nothing ever carried the person's answer on — the worker only picks up what is awaiting.
Reopening now refuses an attempt whose claim is live and whose walk is running, and takes one whose
walk has stopped.

The claim was read too strictly in one direction and too loosely in the other. Reopening did not release
a lapsed claim, so an attempt held by a node that had died blocked its shape from ever being run again —
"another attempt is learning this shape", for ever, until some unrelated stream arrived and swept it.
And where the attempt being reopened was itself parked, its claim was not pushed out, so the next stream
of that shape could sweep away the very answer a person had just given. Both are the release that
opening a new attempt has always performed; reopening performs it too.

The last two were the twins disagreeing: the in-memory `amended` deleted the turns after the one named
before finding out that the turn did not exist, where the table's two writes are one transaction and
roll back; and `amend` took the document on trust, so the wrong document's attempt budget was used and
another document's shape row was reset. Both now do what the other did.

**Phase C's exit criterion.** Scenarios 20, 30 and 31 are green, 20 and 30 in Tier 2 against MySQL, and
an attempt paused, answered by a person and resumed is what scenario 31 is. Scenario 31 has no Tier-2
run of its own: it needs no node machinery the other two have not already proved — the same worker, the
same tables — and what it adds is the Supervisor view's to drive, which is where its REST resource
belongs.

## 7. Decisions taken

Ruled 2026-09-17, each as recommended:

- **The held-out split is a seeded shuffle**, the seed supplied by the `Stage`'s caller and fixed in
  scenarios; a node draws one per learning round. An order-based split would let sorted input game
  the gate. Where the input is raw text and records do not yet exist, the learning sample is a
  prefix and the held-out judgement is the whole stream — design 01 §4's probe prefix — because
  lines cannot be shuffled without knowing where records begin.
- **Tier 2 starts now with a hand-glued pipeline**: a fragment the module wrote, spliced into a
  pipeline by hand in an `AbstractProcessIntegrationTest` and run through `PipelineFactory`. The
  cheapest proof that A20's fragments are real pipelines; it does not wait on §12 items 1–2.
- **Quarantine and regression set are interfaces the `Stage` is given** — `Quarantine`,
  `RegressionSet` — with in-memory implementations for scenarios and stream-backed ones in Tier 2.
- **First slice: `Scorecard`, the scorers scenario 1 needs, and scenario 1 end to end in Tier 1.**
- **Shape signature normalisation** is a strategy the document does not yet expose, provisional per
  design 01 §5 and revisited under A6 with real feeds; scenarios depend only on same-shape-same-key.
- **The 2026-09-17 rulings in design 01 §14** — provisional bindings, the learning key, the relearn
  trigger, reserved rules, durable attempts and the Supervisor view, the per-feed processor gate —
  are taken as read here; scenarios 25–32 are their statement as tests.

Ruled 2026-09-18, the owner's:

- **The scripted fixtures derive the split and target answers** (`Structure`, §3) rather than
  script them, so that scenarios written before A31 state only what they are about; a scenario about
  the boundary or the target scripts those lines itself (34–37, 39).
- **The dialogue is measured, not chosen** (A32): both shapes stay, the live harness runs either on
  the same feeds (`SHAPESHIFTER_LIVE_DIALOGUE`), and §6.3 is where the evidence goes.
- **The dialogue is the document's** (A33, A34): scenarios 39 and 40 state it; a document owns its
  steps, the two measured dialogues are examples to load from, and the scripted scenarios give their
  documents the target-first steps so the fuller dialogue stays exercised while a new document starts
  direct.
- **The XML split is built, not waived** (A35): scenario 37's XML variant, `nested-entries.xml` with
  records two levels down — the container refused, the element accepted, the transform told. It binds
  *provisionally*, since the stage still counts the root's children as the stream's records; that
  count follows the record element onto the rule with the A26 tables.
- **Coverage is by characters** (A36): the header case's golden scores its sixteenth, not its seventh,
  and the line is still named; `TestExtractionDegeneracy` states it.
- **Next live run**: feeds 06 and 07 in both dialogues, when the key's limit resets on 2026-10-01,
  with everything since run 6 — the chain steer, the header rule, the none guard, the XML split,
  whole-document samples for markup, coverage by characters. **Slice 12**: the A26 tables.

Ruled 2026-09-21, the owner's, on four questions put with recommendations:

- **The plan is a graph** (A37): the list of A34 was one path through a flow the document could not
  express, and 02 §6.3's finding — direct cheaper on clean feeds, a target better on hard ones — wants a
  plan that escalates. Steps gain ids, checks from a closed list, a role on `CONFIGURE`, and
  transitions over typed outcomes: `on <outcome> goto <id>` at once, `on spent goto <id>` when the
  candidates are gone; self re-ask stays the default; each transition is taken at most once. Checks
  are declared per step, thresholds stay on the Scoring tab. The parser–transform routing of design 01
  §10.1 rule 6 moves from `TargetChecks` into the examples as a transition. Scenarios 41 and 42 state
  it; 39 grows the new refusals.
- **Sequencing**: A37 is specified now and built as slice 12, ahead of the A26 tables, so that the
  attempt and turn tables are cut once around step ids and outcomes; the A35 record element reaches
  the rule with the tables, one slice later than Friday's order said.
- **The word**: what the document holds is a *learning plan* — `LearningPlan`, `PlanStep`,
  `PlanExample`, `SHAPESHIFTER_LIVE_PLAN` — and the run an attempt follows it with is still a
  *dialogue*; the classes are renamed in slice 12, and this file's history keeps the names of the day.
- **A phased plan** exists from today as design 03: phases A–G with exit criteria, the six formats
  of §3 as scenarios 43–48 (phase B, after slice 12), and the rulings owed with the phase each falls
  due in.
- **Redaction deferred** (A38): the owner ruled how it works — vocabulary kept, values classed; every text
  the model sees and every comparison against what it wrote; a harness dimension — and deferred the build
  until the formats are proven. Phase B runs raw; it is owed before phase G's real feed.
