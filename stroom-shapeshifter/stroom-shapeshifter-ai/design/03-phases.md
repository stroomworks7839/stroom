# Design 03 — Phases: what is built in what order, and what lets the next phase start

*Written 2026-09-21. Design 01 §12 is the work list, in dependency order and marked built or not;
design 02 §6 is the same list ordered by the scenario that forces each item. Neither says what a phase
is, when it is finished, or which input formats the feature has been shown to handle. This document
does, and it is the one the slices are now cut from. A phase ends on its exit criterion — a test or a
run, not a judgement — and the next does not start until it has.*

## 1. Where it stands

Slices 1–11 (design 02 §6.1) built the document type and its editor, the scorers, the `Stage` with its
routing, learning, provisional binding, promotion, retraction, relearning, ledger and review mode, the
supervisor element in a real pipeline, learning against a target, and the plan as data. Runtime state
is in-memory seams; attempts are not durable; the pipeline integration is hand-glued; the model has
been run against seven feeds, all cut from the `TestDS3` corpus and one XML fixture.

Formats exercised so far: CSV (headed, unheaded, quoted), a regex line feed, one multi-line block
(corpus 003), nested XML. Not exercised, anywhere: syslog, auditd, Windows security events, JSON,
fixed-width. Design 01 §2.1 said the corpus lacks them; nothing since has added them.

## 2. The phases

| Phase | Delivers | Closes (design 01 §12 / design 02 §5) | Exit criterion |
|---|---|---|---|
| **A. The plan mechanism** | A37: outcomes and checks as closed lists; `PlanStep` with id, role, checks, transitions; `Dialogue` as an interpreter; the *escalating* example; the renames of §12 item 23; the Learning tab's grammar; `SHAPESHIFTER_LIVE_PLAN` | item 23; scenarios 39–42 | every scenario green in both tiers; the 2026-10-01 live run over feeds 06 and 07 with all three plans, transcripts under `build/live-runs`, findings in design 02 §6.3 |
| **B. Format breadth** | one fixture, golden event set, scripted scenario and live row per format of §3; the `JSONParser` runner (scenario 2); the coverage floor and yield bases set per format from what the goldens score | scenarios 2, 43–48; thresholds' first values (design 01 §2.1) | each of the six learned scripted; at least four of the six learned live to the floor with the default plan or the escalating one |
| **C. Durable state** | the A26 module and tables; the A35 record element on the rule and in the stage's count and yield; A28's attempt and turn tables, the deferred worker, the Supervisor view and its resource; A18's check reading targets as goldens | items 8, 9, 15, 20's remainder; scenarios 13, 20, 30, 31 | scenarios 20, 30 and 31 green in Tier 2 against MySQL; an attempt paused, answered by a person and resumed |
| **D. Pipeline integration** | §12 items 1 and 2 in `stroom-pipeline`, proposed on their own merits; the fragment run once per stream with per-element capture in a child task; the reprocessing mode that reads bindings (item 7); input spans (item 21); the restricted XSLT function library (item 10); content-pack checks (item 11); replay unit derived and checked (item 18) | items 1, 2, 7, 10, 11, 18, 21; scenarios 18, 19, 38 | scenarios 18 and 19 without the interim glue design 02 §6.1 records; scenario 38's read-back by span; one real feed learned end to end on a running node |
| **E. Safety at volume** | error mode (A24) and its status strip; the A27 processor gate; rate limiting across documents and the spend breaker; the A23 review job, its audit stream and the `Critique` kind; the regression stream as a real stream with retention | items 6's remainder, 12, 14, 16; scenarios 23, 24, 32 | scenarios 23, 24 and 32 green; a soak: a replayed real feed at volume under a deliberately bad model endpoint, with error mode entered, the gate holding tasks, and spend capped |
| **F. Operator surfaces** | the plan editor (item 24); the stage pane in the stepper (A30, item 19); Approve and Reject on the Routing tab (item 13's remainder); the ledger view grouped by shape | items 13, 19, 24; scenario 33 | GWT compiled; scenario 33; a person learns, reviews and approves a feed from the UI alone, no REST calls by hand |
| **G. Real-scale trial** | redaction (A38) built and measured as a harness dimension first, since a real feed is now going to a model; then one real feed with an existing, thousand-line stylesheet as the incumbent, replayed through the feature; thresholds and A6 set from its evidence; the rulings of §4 given from what it shows | A38; A6, A16, A19; the thresholds design 01 §2.1 left open | the promotion gate holds on real data — no promotion a person reviewing the transcript would have refused; the review-mode target page judged readable by an operator who did not build it |

Phases A and B are the proof that the mechanism works and works across formats; C to F make it a
product; G is what decides whether it is switched on. C and D are independent of each other and may
interleave; E depends on C; F depends on C and D; G depends on all of them but its fixture — the real
feed and its stylesheet — should be sought during B, since finding one takes longer than building
anything here.

## 3. Formats, and what each one tests

Each format is a fixture drawn from public documentation samples, never from real data; a golden
event set hand-written against the event-logging schema; a scripted scenario that states what the
plan must ask and the stage must do; and a row in the live harness. Design 02's catalogue carries
them as scenarios 43–48. What each is *for*:

| Format | Fixture | What it tests that nothing before it did |
|---|---|---|
| **Syslog** (RFC 3164 and RFC 5424 in one feed) | ~40 lines from two senders, both forms, with a `System` header | Two record kinds under one shape with the default key — one plan and one variant must cope with both (design 01 §5) — and the same feed with the signature in the key, where two rules result. The chain question with a `Format: syslog` header |
| **auditd** (`key=value`, multi-line events) | ~30 events of 2–4 records each, joined by `msg=audit(ts:serial)` | The split question in earnest: a record is several lines that share a serial, so a line-per-record split scores full coverage and fails wholeness and yield against the input's own structure. Field preservation on `a0`–`a3` hex arguments the target needs decoded |
| **Windows security events** (XML export) | ~20 `Event` elements: `System`, `EventData/Data[@Name]`, several `EventID`s (4624, 4634, 4688) | The XML split on a real nested document; `Data[@Name]` to typed fields is the anti-degeneracy case in the wild — a transform that dumps `Data` as `Data` validates and extracts nothing; the `EventID` decides the `EventDetail` branch, so event classification is exercised |
| **JSON** (lines, and one array document) | ~30 records, two kinds, nested objects | The `JSONParser` runner that scenario 2 has promised since the catalogue was written; the split question for JSON — the array whose items are records (A31) — as a third guard beside `text` and `xml`; no configuration question for the parser |
| **Fixed-width** | ~30 lines, six columns by position, no delimiter | A split with nothing to split on: the regex must be positional, coverage by characters counts every column, and a column dropped at the parser is caught by preservation, not coverage |
| **CSV with embedded newlines and quotes** | ~20 records, a quoted field spanning lines, escaped quotes | The boundary case A36 was tuned on: a line-based split cuts a record in two, scores well on line coverage and fails wholeness; the golden proves the DS3 quoting rules the worked example must teach |

Each fixture is also the seed of the regression stream a promotion would write (A18), so the goldens
are worth writing carefully: they are what "correct" means for that format until a real feed says
otherwise.

## 4. Rulings owed, and where they fall due

| Ruling | Status | Falls due |
|---|---|---|
| A20, A21, A22 | Proposed, but built and relied on by A31–A37 | Now — one line each in design 01 §13 and §14 |
| A16 anti-degeneracy | Proposed; built as a gate | Phase G, from real evidence; the Windows fixture in phase B is the rehearsal |
| A19 no `ignoreErrors` | Proposed; enforced at the compile gate | Phase G, with A16 |
| A6 signature normalisation | Open | Phase G; the syslog fixture in phase B is the rehearsal |
| A23–A28, A30 | Proposed, the owner's | Each on the phase that builds it: C for A26 and A28, E for A23, A24 and A27, F for A30 |
| Thresholds (floor, coverage, yield, relearn) | Open, design 01 §2.1 | First values in phase B from the goldens; settled in phase G |

## 5. Where phase B stands

| Format | Slice | State |
|---|---|---|
| Syslog | 13, 2026-09-21 | Fixture, golden, two splitters, stylesheet; scenario 43 in both keyings green; live row `08-syslog` awaiting the 2026-10-01 run. Finding: a `<PRI>` prefix must not read as markup (`YieldScorer`) |
| auditd | 14, 2026-09-21 | Fixture, golden, three splitters, stylesheet; scenario 44 green; live row `09-auditd`. Findings: a multi-line record is stated through the expected yield per line; a lines basis must judge the transform record for record; DS3 group expressions anchor with `^` |
| Windows security events | 15, 2026-09-21 | Fixture, golden, two stylesheets; scenario 45 green; live row `10-windows-security`. Finding: kinds of XML record are told apart by structure, and events of different `EventID`s share one — a kind by a discriminating value (`EventID`, `Data/@Name`) is owed before the target step can see them |
| JSON | 16 | — |
| Fixed-width | 17 | — |
| CSV with embedded newlines | 18 | — |
| Redaction (A17, A38) | deferred to G | Ruled 2026-09-21 how it works; the owner deferred the build — formats first, redaction before a real feed meets an external model |

## 6. How a slice is cut from this

A slice is the smallest piece of a phase that leaves every scenario green and design 02 §6.1 able to
say what it did. Slice 12 is the whole of phase A. Phase B is six slices, one per format, each
carrying its fixture, golden, scenario and live row; redaction (A38) waits for phase G. The phases
after B are cut when B's exit criterion is met, because what B finds — which formats the model
handles, at what cost, with which plan — is what decides how much of C to F is worth building first.
