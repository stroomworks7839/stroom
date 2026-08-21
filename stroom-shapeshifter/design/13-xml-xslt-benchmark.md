# The XML head-to-head: pipeline XSLT vs shapeshifter, planned

Status: **phased plan, for sign-off**. Written 2026-08-21; nothing implemented.

## The question, stated fairly

Parse XML, transform, write XML — 1M unique records. The incumbent is Stroom's own shape:
a real XML parser (SAX), a `SplitFilter` (count 1000) to bound memory, XSLT, an XML writer.
The challenger is a shapeshifter configuration doing the same whole job.

The hypothesis is *not* that shapeshifter out-parses a dedicated XML parser — it will not.
It is that the incumbent pays for parse → **event/tree handoff → XSLT walk → re-serialise**,
while shapeshifter goes bytes-in → match+capture → emit bytes in one pass with no
intermediate representation, and that skipping the middle beats the specialist's parsing
edge. The benchmark exists to find out which effect is bigger, and by how much.

## What the reconnaissance found

- `stroom-pipeline` `TestSplitFilter` assembles real pipeline elements with plain
  `setTarget()` chaining and drives them with `ProcessorUtil.processXml(input,
  errorReceiver, chain, locationFactory)` — no injection container. `SplitFilter` is
  configured with `setSplitDepth`/`setSplitCount` directly. This is the harness spine.
- `XsltFilter` appears in tests only under `stroom-app` (`F2XTestUtil`), with heavier
  wiring (stores, pools, pipeline context). Whether it can be stood up without the app
  module's Guice is Phase 0's first question; the honest fallback is Saxon driven directly
  (`TransformerHandler` in the same SAX chain) — the same engine Stroom runs, minus
  Stroom's element plumbing, and the write-up would say exactly that.
- `TestXMLWriter` shows the writer usable standalone.

## Phases

**Phase 0 — wiring spike.** Stand up the full chain once, tiny input: SAX parse →
`SplitFilter(depth from schema, count 1000)` → XSLT → `XMLWriter` → counting byte sink.
Decide: Stroom `XsltFilter` if it wires without the app module, Saxon `TransformerHandler`
otherwise (recorded as a disclosed substitution). Decide module placement — proposal: a new
test-only module `stroom-shapeshifter-xmlbench` depending on `stroom-pipeline` and the
engine, so the engine module's dependencies stay clean. Exit criterion: bytes out equal a
hand-checked golden for ten records.

**Phase 1 — the corpus.** A committed, seeded *generator* (never the file): 1M records,
each unique — sequential id, hash-derived user/host values, varying optional fields, text
containing XML escapes (`&amp;`, `&lt;`) so both sides must handle escaping honestly, and
varied field order across record kinds so the transform cannot cheat positionally. Sizes as
a benchmark parameter: 10k / 100k / 1M (~2.5 MB / 25 MB / 250 MB). The transform job:
realistic normalisation — records → events, an attribute promoted to an element, two fields
concatenated, one dropped, one renamed — expressible in XSLT and in shapeshifter without
favouring either. Golden output committed at 10k; larger sizes verified by digest.

**Phase 2 — the baseline, decomposed.** JMH in single-shot mode (whole-file ops), few
forks, sizes as params, results to `design/benchmarks/` as always. Three rows, so the
comparison teaches where time goes, not just who wins:
1. parse → write (no split, no XSLT): the floor — what the XML machinery alone costs;
2. parse → split(1000) → identity XSLT → write: the plumbing tax;
3. parse → split(1000) → real XSLT → write: **the incumbent**.
Also a memory note: the run that justifies `SplitFilter` (real XSLT, no split, 1M) is
attempted once and its failure or heap use recorded — the reason the split exists deserves
evidence, not folklore.

**Phase 3 — the challenger.** A shapeshifter configuration for the same job: strict
dispatch (D36 idiom — anchored record template, field templates, eaters for structural
lines), captures → the same target XML emitted directly. The escape question is answered
honestly: values carrying `&amp;` must survive to the output identically, however the
config achieves it. Parity gate before any measurement: byte-identical to the incumbent's
output at 10k (golden), digest-equal at 1M. If byte parity founders on XSLT serializer
whitespace, the parity criterion is renegotiated *before* measuring, not after.

**Phase 4 — the measurement.** Same-run A/B, quiet box, drift controls, five-fork or
single-shot-many-iterations as Phase 2's variance dictates. Recorded win, lose, or split —
including the decomposition: if shapeshifter beats row 3 but not row 1, the hypothesis is
confirmed exactly as stated (the middle, not the parser, was the incumbent's cost). Losses
feed the performance queue (the first-byte table is the obvious lever) rather than
disappearing.

## Rulings (2026-08-21)

1. **Module**: new test-only module `stroom-shapeshifter-xmlbench`.
2. **Saxon direct, no `SplitFilter`** — with the XSLT **compiled to `Templates` in setup**,
   outside the measured region, for a fair test; memory is expected to hold and the run
   records heap use so the expectation is evidenced. (Phase 2's three rows become: parse →
   write floor; identity XSLT; real XSLT.)
3. **Transform**: something moderately complex, taken from a real example in the codebase
   where one exists. The larger intent is recorded as the framework's shape: this is not
   one benchmark but an extensible **catalogue of (input, XSLT, golden) cases**, gathered
   from across the codebase and beyond, doubling as a correctness-and-capability validation
   suite — can shapeshifter do everything XSLT does, and how fast — with cases added freely
   once the framework executes.
4. **Parity: byte-identical**, and the shapeshifter side must achieve it with ordinary
   template emission — no special writer.

## The first verdict (2026-08-21)

Phases 0–3 landed in a day; the head-to-head ran same-day
([benchmarks/2026-08-21-1555-324a9bbd25-xml.json](benchmarks/2026-08-21-1555-324a9bbd25-xml.json),
baseline decomposition [2026-08-21-1543](benchmarks/2026-08-21-1543-0ea8643364-xml.json)):

| row | 1M records, ms | MiB/s |
|---|--:|--:|
| SAX parse alone (the floor) | 1,834–2,918* | 100–160* |
| Saxon identity (parse + tree + serialise) | 6,555–8,225 | 36–45 |
| **Saxon + EVENTS stylesheet (incumbent)** | **8,383–8,448** | **~34.5** |
| **shapeshifter (challenger)** | **2,725** | **~107.5** |

*the floor and identity rows wobbled in the second run (error bars to ±1.8 s); the
challenger and incumbent rows were tight in both runs, and same-run: **3.10×**.

The hypothesis is confirmed in its strong form. The incumbent's decomposition showed 78% of
its cost in the middle — the tree and the serialisation, not the parse and not the transform
logic — and the challenger, having no middle, lands **at the parse floor itself**: it does
the entire job in roughly the time the specialist parser takes to read the input and call
handlers. Byte-identical output, parity-gated before any timing, ordinary template emission.

The challenger's shape is the D36 idiom working as designed: strict dispatch, structural
eaters, a recursive fields scope that keeps the optional field honest, and a
terminator-triggered emitter that turns capture-then-restructure into plain dispatch —
thirteen templates, none exotic.

Honest scope: one adapted case, a regular corpus, and a stylesheet without reference-data
lookups. The catalogue exists to grow — gnarlier XSLT (keys, grouping, multi-pass),
nastier XML (CDATA, comments, deep nesting, attribute-order variance), and the cases where
the challenger should lose, sought as deliberately as the ones it wins. The first-byte
candidate table remains the engine-side lever if a case ever needs it.

## The catalogue (2026-08-21): survey and framework

**The survey.** 97 stylesheets in the repository. Most carry `stroom:` extension functions
(reference-data lookups, date formatting) and need adaptation-with-disclosure to run under
Saxon direct. The notable findings:

- **Nothing in the codebase uses `xsl:key` or `for-each-group`** — the gnarly end of the
  catalogue must be authored, as anticipated by the "and beyond" ruling.
- The gnarliest extension-free stylesheet is `samples/config/Pathways/TEST_TRACES.xsl`
  (modes, `xsl:function`, `position()`) — but its input is **JSON** (OTEL traces), which
  makes it a marquee *future* case of a different kind: shapeshifter parsing the JSON
  directly against the pipeline's JSON-parser-plus-XSLT front end.
- Strong candidates with clean or adaptable features: `CommonIndexingTest/search_result.xsl`
  (modes), `TestIndexingPipeline/Indexes.xsl` (for-each), `benchmark/REFERENCE.xsl`, the
  appender family (`*_Text.xsl`/`*_XML.xsl` — the same source emitted two ways, a good
  dual-output case), and the `CommonTranslationTest` reference-building set.

**The framework.** `CaseCatalogueTest`: each case is a resource directory of `input.xml`,
`transform.xsl`, `challenger.project.json`; the contract is **byte-identical output with
Saxon run live** — no goldens to go stale — plus a clean-run requirement on the engine's
messages. Cases are listed explicitly so a broken path fails loudly.

**Case 1, `nasty_xml`, passing:** comments (one containing `<entry>` bait) dropped; CDATA
flattened to escaped text through a literal-replace chain (`&`, `<`, `>` — matching Saxon's
serializer exactly); an empty CDATA becoming a self-closed `<sql/>` via choose-on-exists;
a five-element-deep descendant pull; attribute value templates. Thirteen templates, strict
throughout, passed byte-identical on the first run.

**The backlog, in intended order:**
1. `keys_grouping` (authored): `xsl:key` + `for-each-group` over non-adjacent groups — the
   case where the challenger may hit a genuine capability wall (grouping needs whole-input
   state before first output), sought deliberately per the ruling: losses are findings.
2. `modes` (adapted from `search_result.xsl`): one input walked by two template modes.
   **Done 2026-08-21 — and no adaptation was needed**; see below.
3. `dual_output` (appender family): the same records emitted as XML and as text.
   **Done 2026-08-21 — as the second wall, per the ruling**; see below.
4. `reference` (`REFERENCE.xsl`): the clean reference-builder.
   **Done 2026-08-21 — verbatim, passed first time**; see below.
5. `json_front` (TEST_TRACES): JSON input — pipeline's JSON parser + XSLT vs shapeshifter
   reading the JSON itself. A different fight, worth its own baseline rows.
6. Deeper nastiness: processing instructions, attribute-order variance, mixed content,
   namespace prefixes differing between input documents.

**`modes`, passing (2026-08-21):** backlog item 2 delivered, and it is the catalogue's first
**unmodified production stylesheet** — `CommonIndexingTest/search_result.xsl` copied verbatim,
its extension-free XSLT running under Saxon exactly as the pipeline ships it. It proves the
two-mode walk (the default mode building records while `mode="text"` collects every text leaf
under `EventDetail` — replayed challenger-side as an eat-tag/emit-text dispatch over the
captured subtree), the sixteen-branch `xsl:choose` translated to branch-at-dispatch (one
fields-mode template per `EventDetail` shape, list order standing in for `when` order), and
`call-template` with a named record-head template — the matrix's covered-but-unproven row, now
proven. Byte-parity earned its keep twice: the stylesheet's stray literal `Authorise` text
(a real production wart) is reproduced with its exact indentation, and inter-event whitespace
copied by XSLT's built-in text rule is emitted where Saxon emits it. The first draft
exists-tested optional branch captures and hit E19's pinned stale-value case — event 2
reporting event 1's action — recorded as an addendum there; branch-at-dispatch is the idiom.

**`dual_output`, walled (2026-08-21):** backlog item 3, ruled onto the wall rather than into
engine work. The appender family's job — the same source emitted two ways — collapsed into
one stylesheet using XSLT's native routing, `xsl:result-document` (adaptation disclosed in
the stylesheet header: two pipelines become one sheet, `stroom:format-date` dropped). The
wall runner grew the one thing result-document needs, a base output URI in a scratch
directory, so the stylesheet genuinely writes its secondary document every run. One sink
today (D10/E15); the test trips the day someone authors a challenger without promoting it —
and promotion will need the runner to compare both outputs, which is tomorrow's problem by
design.

**`reference`, passing (2026-08-21):** backlog item 4, the second verbatim production
stylesheet (`stroom/benchmark/REFERENCE.xsl`, extension-free, straight from main resources).
The case's teeth are in its input, not its stylesheet: the `data` fields arrive in a
different order per record — the stylesheet selects by `@name`, and the challenger's
per-field templates dispatch in any order, which is the honest translation — and one empty
`Desk` value forces Saxon's self-closing serialization, met with choose-on-exists. Passed
first time. This closes the survey-sourced XML tranche; what remains in the backlog is
genuinely new fronts.

## The profiling surface (2026-08-21)

The catalogue is fat enough — the ruling's gate is open. Three pieces make it profilable:

- **`CaseCorpus`**: amplifies any challenger-backed case to benchmark scale by repeating its
  body units (records, events, batches) cyclically, in **whole cycles only**. The disclosure:
  repetition measures throughput, not branch surprise — content variety stays whatever the
  case authored. The whole-cycle rule was learned, not guessed: cutting `adjacent_groups`
  mid-cycle leaves a trailing empty group that Saxon self-closes where the challenger has
  already emitted its open tag — a real idiom limit (recorded in design/14), found by the
  amplifier before it could pollute a measurement.
- **`CaseAmplifierTest`**: the licence for every benchmark row — each case, amplified, must
  still pass Saxon/challenger byte-parity and run clean. The benchmark and the correctness
  catalogue cannot drift apart.
- **`CaseCatalogueBenchmark`**: seven cases × two sizes (~10k and ~100k units) × Saxon and
  shapeshifter, single-shot whole-file, both engines compiled in setup per the fair-test
  ruling. Per-capability-family numbers instead of one workload's blend; results land in
  design/benchmarks by date and commit like every other measurement.

Profiling resumes once the catalogue is fat enough to profile against — per the ruling.
**That gate is now open.**
