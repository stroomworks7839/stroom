# XSLT coverage matrix — what shapeshifter covers, can express, lacks, and refuses

Status: living document, first drawn 2026-08-21. Organised by the XSLT 2.0 specification's
own structure plus the XPath 2.0 function library (3.0 additions noted), because that is the
same tree the W3C test suite hangs from. Every row carries one of four verdicts:

- **covered** — a native mechanism exists; the mechanism is named.
- **expressible** — achievable with a known idiom; the idiom is named, and the row is not
  *proven* until a catalogue case exercises it (the `case` column tracks that).
- **gap** — no mechanism today; each is either engine-work-candidate or awaiting a ruling.
- **out of scope** — refused with a reason. This column is a feature: it is the honest
  statement of what shapeshifter is *for*.

## 1. Core processing model

| XSLT | Verdict | Mechanism / idiom / reason | Case |
|---|---|---|---|
| Template rules + `apply-templates` | **covered** | templates + `apply-templates` with modes; dispatch is D36's five modes, richer than XSLT's document-order walk in some ways (lexer, classify), narrower in others (no priority/`next-match`) | events ✓ |
| Modes | **covered** | `mode` on templates and applies | events ✓, modes ✓ (a production stylesheet's two-mode walk, verbatim) |
| Template priority / `next-match` / `apply-imports` | **out of scope** | list order *is* priority (D34); import trees are an authoring-composition feature with no engine analogue planned |
| Named templates + `call-template` + params + defaults | **covered** | `call-template`, `with-param`, declared defaults | modes ✓ (call + named body; params/defaults still unproven) |
| Tunnel parameters (2.0) | **gap** | scoped stores get close but do not pass silently through intermediate levels; awaiting a real case before any ruling | |
| `for-each` | **expressible** | a level dispatched over the selected content — iteration is what levels do | events ✓ |
| `if` / `choose` / `when` / `otherwise` | **covered** | `if`, `choose`, plus `switch`, which XSLT lacks | nasty ✓ |
| Variables / params | **covered** | captures, stores, `variable`, transform `name=` binding; per-match array indexing (`match_index`) exceeds XSLT's scalar variables | events ✓ |
| `value-of` | **covered** | `value-of` with multi-part refs | events ✓ |
| `copy-of` / deep copy | **expressible, proven** | subtree passthrough: the matched bytes re-emitted verbatim *are* the deep copy — mixed content, self-closed elements and all. Namespace fix-up remains **out of scope** (no tree) | computed_names ✓ |
| `copy` (shallow) | **out of scope** | shallow-copy-then-rebuild-children is a tree operation; the byte model re-emits or re-writes, it does not graft |
| Literal result elements + AVTs | **covered** | literal text + refs anywhere, including inside attribute text | nasty ✓ |
| Computed constructors (`xsl:element`/`xsl:attribute` with computed names) | **expressible, proven** | a name is just bytes from a ref | computed_names ✓ |
| `xsl:text` / whitespace control | **covered** | literals are exact bytes; there is no whitespace stripping to control | events ✓ |
| Comments / PIs in output | **covered, proven** | they are bytes | computed_names ✓ |
| `strip-space` / `preserve-space` | **out of scope** | input whitespace is content to match or eat, not tree decoration |
| Serialization (`xsl:output`, indent, cdata-section-elements, character maps) | **out of scope** | the engine emits exactly what the config says; there is no serializer to configure. Byte-parity with a *configured* serializer is the author's job, as the escape-chain idiom shows |
| `result-document` (multiple outputs) | **gap, walled** | one sink today; D10/E15 territory — the sink seam exists, the routing does not. Walled executably 2026-08-21: `dual_output` runs the same records to XML on the primary output and text via `result-document`, and no challenger exists | dual_output (wall) |
| `xsl:import`/`include` | **out of scope** | composition is an authoring-tool concern (combinator patterns already compose matches; body composition unplanned) |

## 2. Sorting, grouping, keys — the hard block

| XSLT | Verdict | Mechanism / idiom / reason | Case |
|---|---|---|---|
| `xsl:sort` | **gap** | emission follows match order; no reorder buffer exists. Any answer (sortable store iteration? sink-side sort?) is a design decision, not an idiom | backlog |
| `for-each-group group-by` (non-adjacent) | **gap** | needs whole-input state before first output byte; stores can accumulate but nothing iterates a store's *distinct keys*. The expected capability wall — the backlog's first case exists to hit it honestly | keys_grouping |
| `for-each-group group-adjacent` | **expressible** | adjacency is dispatch's native gait | — |
| `group-starting-with` / `ending-with` | **expressible, proven — with a found limit** | strict dispatch with a starting template; group position via `__match_count`; the boundary close via choose-on-count. Limit found at amplified scale (2026-08-21): a **trailing empty group** serializes as `<x></x>` where Saxon self-closes `<x/>` — the challenger has emitted the open tag before knowing the group is empty. Expressible in principle via the match language's `Peek`; awaiting a real case before any rework | adjacent_groups ✓ |
| `xsl:key` / `key()` | **gap** | keys are random-access indexes over the whole document; stores are append-arrays. Same wall as group-by | keys_grouping |

## 3. XPath semantics against matched content

| XPath | Verdict | Mechanism / idiom / reason |
|---|---|---|
| Downward paths (`a/b/c`, predicates on structure) | **expressible** | nested levels, or a single pattern spanning the structure (nasty's five-deep pull) — proven ✓ |
| `position()` / `last()` | **covered, proven** | `__match_count` works as position, tested with `equals`; the dead `is-first`/`is-last` conditions the case found were **deleted by E21's ruling** (2026-08-21) — count equality is the documented idiom until a case demands richer positional vocabulary | adjacent_groups ✓ |
| Upward/sideways axes (`ancestor::`, `preceding-sibling::`) | **out of scope** | there is no tree to walk back up; state wanted from "above" is captured on the way down (the `batch` var in nasty is exactly `../@id`) — proven ✓ |
| General/value comparisons, arithmetic | **partial** | conditions compare equality, ordering, existence, regex; arithmetic beyond `number()` is a **gap** (no expression language, by design — D35's model is declarative). Revisit only if cases demand computation |
| Sequences, `distinct-values`, `index-of`, quantifiers | **gap** | store arrays exist; sequence *operations* over them do not |

## 4. The function library

| Function family | Verdict | Mechanism |
|---|---|---|
| `concat` | **covered** | multi-part refs |
| `substring`, `substring-before/after` | **covered / proven** | `substring` (**0-based where XSLT is 1-based** — ruled 2026-08-21: documented as-is, faithful to the ported library; the trap note lives in `OutputNode.Substring`'s javadoc); before/after via capture patterns | string_functions ✓ |
| `translate` | **covered** | `translate` |
| `upper-case`, `lower-case` | **covered** | same names |
| `normalize-space` | **covered** | `normalize-space`, plus `trim` |
| `replace` (regex), `matches`, `tokenize` | **covered** | same names, byte-level regex |
| `string-join` | **covered** | `string-join` |
| `string-length`, `starts-with`, `ends-with`, `contains` | **expressible, contains proven** | `matches` conditions (regex anchors give starts/ends); length-as-value stays a **gap** | string_functions ✓ |
| `format-number` | **gap** | transforms emit what they were given; numeric formatting is a candidate transform if cases demand it |
| `format-dateTime`, date/duration arithmetic | **gap** | the corpus dodged it (ISO passthrough); Stroom's real configs lean on `stroom:format-date` — this is the likeliest **first real gap** a production-shaped case hits. **Ruled 2026-08-21: not a verbatim port.** `stroom:format-date` conflates parsing and formatting in one call where the honest shape is a composed `format-date(parse-date())` pair; the vocabulary, when grown, should be that pair — design before code, shaped by the first case that hits it |
| `sum`, `count`, `avg`, `min`, `max` | **gap** | aggregation over matches = accumulator territory; classify mode + stores get partway, nothing folds |
| `number()` | **covered** | `number` |
| `generate-id`, `id()`, `document()`, `doc()` | **out of scope** | identity and secondary documents are pipeline concerns (reference data), not transform concerns — Stroom itself agrees, via `stroom:lookup` living outside XSLT |
| `current-dateTime()` etc. | **out of scope** | injecting wall-clock into a deterministic transform breaks golden parity by definition; Stroom feeds times through data or context |
| `analyze-string` (2.0) | **expressible, proven** | a level of regex templates over a captured value | analyze_string ✓ |
| `unparsed-text` (2.0), `xsl:evaluate` (3.0), streaming/accumulators (3.0), `xsl:try` (3.0), maps/arrays (3.0) | **out of scope / gap-by-ruling** | 3.0's streaming machinery solves a problem the byte engine does not have; `try` partially met by `emit-error`+`fatal`; maps/arrays await evidence |

## 5. The score, and what it means

Counting rows: **~17 covered, ~9 expressible — 8 now proven by catalogue cases — ~12 gaps
(one of them, non-adjacent grouping/keys, executably documented as a wall the suite trips on
the day it is solved), ~13 out of scope.** Three authoring traps found by the proving cases,
all now ruled on (2026-08-21): `substring` is 0-based against XSLT's 1-based — documented
as-is, javadoc carries the trap; the `is-first`/`is-last` conditions were dead vocabulary —
deleted until a case needs positional semantics (E21); and an optional capture that fails to
re-match reads the previous record's value straight through an `exists` test — E19's pinned
no-match case, met live by `modes` and dodged by deciding branches at dispatch time.

The gaps cluster into exactly three families, which is the matrix's real finding:

1. **Whole-input state before output** — sort, group-by, keys, aggregation, distinct-values.
   One architectural question wearing five names. `keys_grouping` is the case that forces it.
2. **Value computation** — arithmetic, string-length-as-value, format-number,
   format-dateTime. Transform-vocabulary growth, evidence-driven, one function at a time —
   date formatting first, since Stroom's own configs lean on it hardest. Ruled 2026-08-21:
   the date vocabulary is a composed `format-date(parse-date())` pair, not a verbatim port
   of `stroom:format-date`'s conflated signature; design starts when the first case hits it.
3. **Output routing** — result-document / multiple sinks. Already owned by D10/E15, and now
   walled executably: `dual_output` is the second wall, tripped the day routing lands.

Everything else is either covered, an idiom awaiting its proving case, or refused with a
reason that survives being read aloud.
