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
its author.

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
   A21 dialogue over the learning sample. Score each candidate with the document's scorer set.
   Feedback to the failing step carries the scorer's diagnostics. Stop at the candidate limit
   (`maxAttempts`) or at a candidate whose gates all pass and whose weighted total meets every
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
8. **Durable attempts and the worker** (A28, §12 item 15) — scenario 30 — then the A26 tables under
   scenario 20 and A27 under scenario 32 (§12 items 8 and 16).

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
  `DataChangedException` fails its stream rather than overwriting. The learning lease of A26 is the fix.
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

Not yet: the reconstruction run and the louder hint live; scenario 20 (needs the A26 tables for its
ledger row); everything from scenario 30 on.

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
