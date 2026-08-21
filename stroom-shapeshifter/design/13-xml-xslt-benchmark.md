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
