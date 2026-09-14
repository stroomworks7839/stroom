# Design 36 — Why Shapeshifter exists

*Written 2026-09-14, after thirty-five designs, from questions that kept recurring: what is this
for, and why is it not one of the things that already exist.*

## 1. The problem it is pointed at

Not "parsing is hard". `ds-rs/design/full/full-design.md` states it plainly: turning raw logs into
schema-compliant events currently needs a parser *and* XSLT, and **XSLT is a niche language in
2026**. Few people can write it, complex translations take weeks, and when their author leaves the
stylesheet becomes a liability. The cost is specialist skills, development time and ongoing
maintenance — not throughput.

So the thing being replaced is not a parser. It is a *pair* — a parser plus a transformation
language — and the pairing is itself part of the cost, because a format change means touching both.

## 2. Why the obvious answers do not fit

**Parser generators — ANTLR, JavaCC, nom, pest.** Two problems, and the first is fatal.

A grammar is a **total function over a closed language**: it accepts or rejects the whole input,
and it assumes the language was defined in advance. A log corpus is the opposite — open-ended and
drifting, emitted by several versions of several systems that never agreed on a format, and
changing without notice. *This was tried with JavaCC and failed*, and the failure was not a tooling
problem. It was a category mismatch: a closed-world tool asked to describe an open-world input.

The second problem is deployment. These generate **code you compile**. A new log format becomes a
build and a release, which is the wrong unit of change for an operations team, and — see §6 — the
wrong unit for a machine to generate and have checked.

**Grok.** Config-driven, which is why it is everywhere, and the right *shape* of answer. But it is
regex over lines: flat fields, no nesting, no byte-level control and no encoding awareness. It
cannot produce structure, and it cannot reach a format that is not line-oriented text.

**XSLT, jq, Jolt, VRL.** All transform input that is **already structured**. They are the second
half of the pair, and they cannot do the first half at all. XSLT is also the thing whose cost
started this.

**Strict parsers — XML, JSON.** These fail at *document* scope. One bad byte at 40MB into a 100MB
file costs the other 99.99%. §5 is why that is disqualifying rather than inconvenient.

## 3. What this is, stated as the combination

**A parser you configure rather than compile, that works on bytes, produces nested structure, and
transforms in the same pass.**

Each of those four is available somewhere. The combination is not, and each competitor above misses
at least one. That is the claim — not that any single property is novel.

## 4. Pattern dispatch instead of a grammar

Templates match where they match; a mode is a set of candidates; dispatch picks one. There is no
start symbol, no derivation, no requirement that the whole input be describable.

That is the right model for an open corpus, and "no grammar" is the design rather than a
compromise. It buys three things a grammar cannot give:

- **Partial description.** A configuration may describe the records it knows and say nothing about
  the rest, which is the normal state of a log feed.
- **Incremental extension.** A new record type is a new template, not a grammar change that must be
  proved not to have broken the old ones.
- **Local failure.** An unrecognised record is one unrecognised record.

*What it gives up is ambiguity detection*, and that is the honest cost. Two templates that could
both match the same bytes are resolved by order and nothing warns that they overlap. That is a
tooling gap rather than a model flaw — a "which templates could match this input" analysis is
buildable, and §6 wants it anyway.

## 5. Partial success is structural, and the granularity is the point

The usual framing is that strict parsers are intolerant of corruption. The sharper statement is
that **they fail at the wrong granularity**. XML and JSON fail at document scope; a log feed needs
failure at record scope, because the operational question is never "is this file valid" but "how
many events did we get".

Here the **record boundary is the failure boundary**, and it is built in rather than recovered:

- unmatched content produces a message — *"Expressions failed to match all of the content.
  Unmatched: […]"* — rather than an abort;
- there are three severities, `WARNING`, `ERROR` and `FATAL`, not one;
- `ignoreErrors` is a **per-level gate**, so an author chooses where tolerance applies rather than
  taking it globally or not at all;
- and the byte rules are strict *at the match* while tolerant *around* it — an undecodable byte
  makes a pattern not match (D38), it does not poison the stream.

Losing one corrupt record out of a million is a different outcome from losing the million, and no
amount of error-recovery heuristics on a document-scoped parser produces it.

## 6. The property that serves the AI half

Shapeshifter Intelligence generates transforms and scores their output, improving them in a loop.
**A feedback loop needs a gradient**, and this is where §5 stops being a robustness feature and
becomes an architectural requirement.

An all-or-nothing parser gives a binary signal: it worked or it did not. There is nothing to climb.
Graceful degradation gives a scorer something real to measure — *8,700 of 8,900 records extracted,
200 unmatched, here they are* — which is a score that can be improved against, and the unmatched
bytes are themselves the next prompt.

The same reasoning sets what "good" means for the engine's surface. If a machine writes the
configuration, **the engine's value is in being checkable**:

- refusals at compile time rather than silent wrong output;
- declared types and lifetimes, so a mistake has somewhere to be caught (design 35);
- deterministic bytes, so scoring is repeatable;
- and explicit operations rather than implicit accumulation, so there is less unstated behaviour
  for a generator to get wrong.

An expressive engine that fails quietly is a *bad* generation target. A slightly less expressive
one that says *"`append` to `x`, which is declared a scalar at line 40"* is a good one. Much of
design 35 is that trade taken deliberately.

## 7. What it measures

Against Saxon on the same job, same machine, same run (design 13, 2026-08-21, ~100k units):

| case | Saxon ms/op | shapeshifter | ratio |
|---|---|---|---|
| `computed_names` | 140.6 | 33.0 | **4.27×** |
| `analyze_string` | 134.6 | 44.9 | **3.00×** |
| `reference` | 717.9 | 312.4 | **2.30×** |
| `string_functions` | 211.4 | 93.3 | **2.27×** |
| `adjacent_groups` | 48.9 | 22.4 | **2.18×** |
| `modes` | 650.4 | 359.4 | **1.81×** |
| `nasty_xml` | 621.7 | 1005.4 | **0.62×** |

Blended, 3.10×. **And one measured loss**, `nasty_xml` at 0.62× — Saxon ahead by 1.6× on the
CDATA/escape-chain case. It is listed because a justification that only reports wins is not
evidence. (These predate the 2026-09-02 machine change, so the absolute figures are not comparable
with later runs; the ratios are, both sides having run together.)

Since then the run rows have gained again — the 2026-09-11 set reads **+42.8% on `apache_httpd`**
and **+23.2% on `csv_header`** against a floor thirteen points earlier — on a different corpus,
so it does not move the Saxon ratios, but it is the same direction.

## 8. What it gives up, honestly

- **A performance ceiling.** An interpreter over a compiled graph will not beat generated code from
  ANTLR or a hand-written `nom` parser. *Fast for a config-driven engine* is the defensible claim.
- **Ambiguity detection**, per §4.
- **Grammar tooling.** No railroad diagrams, no LL(k) classification, no theory of error recovery
  to borrow from.
- **Referential transparency**, since variables are mutable (design 35 §5) — a name does not mean
  the same thing at two points in a run. Declarations and scopes are the compensation.

## 9. What would show this reasoning to be wrong

- **If configurations are as hard to write and maintain as XSLT.** This is the existential risk and
  it is on the design's own terms: replacing a hard niche language with a hard niche format fails
  at §1. `win_sec` is 60+ templates and 71 variables today. Rewriting it under design 35 is a
  direct measurement of whether that complexity is inherent or accidental, and **it should be the
  first exercise of the new model**.
- **If the scoring loop cannot climb.** §6 assumes partial output is a useful gradient. If scores
  turn out to be dominated by whole-format success, the tolerance argument loses its second half
  and only the robustness half stands.
- **If the byte-level reach goes unused.** The strictness work (D38, design 32) is expensive and is
  justified by formats Grok cannot touch. If every real feed is line-oriented UTF-8 text, that
  investment bought a difference nobody needed.
- **If nobody outside Stroom has this shape of problem.** Then it is a very good component and not
  a library, which is a fine outcome but a different one.
