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
| Modes | **covered** | `mode` on templates and applies | events ✓ |
| Template priority / `next-match` / `apply-imports` | **out of scope** | list order *is* priority (D34); import trees are an authoring-composition feature with no engine analogue planned |
| Named templates + `call-template` + params + defaults | **covered** | `call-template`, `with-param`, declared defaults | — |
| Tunnel parameters (2.0) | **gap** | scoped stores get close but do not pass silently through intermediate levels; awaiting a real case before any ruling | |
| `for-each` | **expressible** | a level dispatched over the selected content — iteration is what levels do | events ✓ |
| `if` / `choose` / `when` / `otherwise` | **covered** | `if`, `choose`, plus `switch`, which XSLT lacks | nasty ✓ |
| Variables / params | **covered** | captures, stores, `variable`, transform `name=` binding; per-match array indexing (`match_index`) exceeds XSLT's scalar variables | events ✓ |
| `value-of` | **covered** | `value-of` with multi-part refs | events ✓ |
| `copy-of` / deep copy | **expressible** | group-0 passthrough: the matched bytes re-emitted verbatim *are* the deep copy, cheaper than any tree walk. Not proven for "copy with namespace fix-up", which is likely **out of scope** (no tree) | |
| `copy` (shallow) | **out of scope** | shallow-copy-then-rebuild-children is a tree operation; the byte model re-emits or re-writes, it does not graft |
| Literal result elements + AVTs | **covered** | literal text + refs anywhere, including inside attribute text | nasty ✓ |
| Computed constructors (`xsl:element`/`xsl:attribute` with computed names) | **expressible** | a name is just bytes from a ref | |
| `xsl:text` / whitespace control | **covered** | literals are exact bytes; there is no whitespace stripping to control | events ✓ |
| Comments / PIs in output | **covered** | they are bytes | |
| `strip-space` / `preserve-space` | **out of scope** | input whitespace is content to match or eat, not tree decoration |
| Serialization (`xsl:output`, indent, cdata-section-elements, character maps) | **out of scope** | the engine emits exactly what the config says; there is no serializer to configure. Byte-parity with a *configured* serializer is the author's job, as the escape-chain idiom shows |
| `result-document` (multiple outputs) | **gap** | one sink today; D10/E15 territory — the sink seam exists, the routing does not | |
| `xsl:import`/`include` | **out of scope** | composition is an authoring-tool concern (combinator patterns already compose matches; body composition unplanned) |

## 2. Sorting, grouping, keys — the hard block

| XSLT | Verdict | Mechanism / idiom / reason | Case |
|---|---|---|---|
| `xsl:sort` | **gap** | emission follows match order; no reorder buffer exists. Any answer (sortable store iteration? sink-side sort?) is a design decision, not an idiom | backlog |
| `for-each-group group-by` (non-adjacent) | **gap** | needs whole-input state before first output byte; stores can accumulate but nothing iterates a store's *distinct keys*. The expected capability wall — the backlog's first case exists to hit it honestly | keys_grouping |
| `for-each-group group-adjacent` | **expressible** | adjacency is dispatch's native gait: a guard on value-change, or nested levels — needs its case | keys_grouping |
| `group-starting-with` / `ending-with` | **expressible** | that is literally what strict dispatch with a starting template does | |
| `xsl:key` / `key()` | **gap** | keys are random-access indexes over the whole document; stores are append-arrays. Same wall as group-by | keys_grouping |

## 3. XPath semantics against matched content

| XPath | Verdict | Mechanism / idiom / reason |
|---|---|---|
| Downward paths (`a/b/c`, predicates on structure) | **expressible** | nested levels, or a single pattern spanning the structure (nasty's five-deep pull) — proven ✓ |
| `position()` / `last()` | **covered** | `__match_count`/`__match_idx`; `is-first`/`is-last` conditions |
| Upward/sideways axes (`ancestor::`, `preceding-sibling::`) | **out of scope** | there is no tree to walk back up; state wanted from "above" is captured on the way down (the `batch` var in nasty is exactly `../@id`) — proven ✓ |
| General/value comparisons, arithmetic | **partial** | conditions compare equality, ordering, existence, regex; arithmetic beyond `number()` is a **gap** (no expression language, by design — D35's model is declarative). Revisit only if cases demand computation |
| Sequences, `distinct-values`, `index-of`, quantifiers | **gap** | store arrays exist; sequence *operations* over them do not |

## 4. The function library

| Function family | Verdict | Mechanism |
|---|---|---|
| `concat` | **covered** | multi-part refs |
| `substring`, `substring-before/after` | **covered / expressible** | `substring`; before/after via regex `replace` or capture patterns |
| `translate` | **covered** | `translate` |
| `upper-case`, `lower-case` | **covered** | same names |
| `normalize-space` | **covered** | `normalize-space`, plus `trim` |
| `replace` (regex), `matches`, `tokenize` | **covered** | same names, byte-level regex |
| `string-join` | **covered** | `string-join` |
| `string-length`, `starts-with`, `ends-with`, `contains` | **expressible** | `matches` conditions; length is a **gap** if a case needs the number rather than a test |
| `format-number` | **gap** | transforms emit what they were given; numeric formatting is a candidate transform if cases demand it |
| `format-dateTime`, date/duration arithmetic | **gap** | the corpus dodged it (ISO passthrough); Stroom's real configs lean on `stroom:format-date` — this is the likeliest **first real gap** a production-shaped case hits |
| `sum`, `count`, `avg`, `min`, `max` | **gap** | aggregation over matches = accumulator territory; classify mode + stores get partway, nothing folds |
| `number()` | **covered** | `number` |
| `generate-id`, `id()`, `document()`, `doc()` | **out of scope** | identity and secondary documents are pipeline concerns (reference data), not transform concerns — Stroom itself agrees, via `stroom:lookup` living outside XSLT |
| `current-dateTime()` etc. | **out of scope** | injecting wall-clock into a deterministic transform breaks golden parity by definition; Stroom feeds times through data or context |
| `analyze-string` (2.0) | **expressible** | that is what a level of regex templates over a captured value *is* |
| `unparsed-text` (2.0), `xsl:evaluate` (3.0), streaming/accumulators (3.0), `xsl:try` (3.0), maps/arrays (3.0) | **out of scope / gap-by-ruling** | 3.0's streaming machinery solves a problem the byte engine does not have; `try` partially met by `emit-error`+`fatal`; maps/arrays await evidence |

## 5. The score, and what it means

Counting rows: **~17 covered, ~9 expressible (3 proven), ~12 gaps, ~13 out of scope.**

The gaps cluster into exactly three families, which is the matrix's real finding:

1. **Whole-input state before output** — sort, group-by, keys, aggregation, distinct-values.
   One architectural question wearing five names. `keys_grouping` is the case that forces it.
2. **Value computation** — arithmetic, string-length-as-value, format-number,
   format-dateTime. Transform-vocabulary growth, evidence-driven, one function at a time —
   date formatting first, since Stroom's own configs lean on it hardest.
3. **Output routing** — result-document / multiple sinks. Already owned by D10/E15.

Everything else is either covered, an idiom awaiting its proving case, or refused with a
reason that survives being read aloud.
