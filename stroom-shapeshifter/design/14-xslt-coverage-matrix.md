# XSLT coverage matrix — what shapeshifter covers, can express, lacks, and refuses

Status: living document, first drawn 2026-08-21, last scored 2026-08-27 at the close of
[17-value-computation.md](17-value-computation.md)'s tranche. Organised by the XSLT 2.0 specification's
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
| `position()` / `last()` | **covered, proven** | `__match_count` works as position, tested with `equals`; the dead `is-first`/`is-last` conditions the case found were **deleted by E21's ruling** (2026-08-21) — count equality is the documented idiom. [16](16-sequences-and-aggregation.md) §4.3 has since ruled that iteration is the case E21 said to wait for, and that both the conditions and `__position`/`__last` return with it — ruled, not yet built (E23) | adjacent_groups ✓ |
| Upward/sideways axes (`ancestor::`, `preceding-sibling::`) | **out of scope** | there is no tree to walk back up; state wanted from "above" is captured on the way down (the `batch` var in nasty is exactly `../@id`) — proven ✓ |
| General/value comparisons, arithmetic | **covered, proven** (2026-08-27) | comparisons are one strict typed vocabulary — `eq`/`ne`/`lt`/`le`/`gt`/`ge`, same-kind natively, cross-kind false, casts explicit on the operand (`as`), the legacy spellings kept as aliases carrying the casts their semantics implied. Arithmetic is `add`/`subtract`/`multiply`/`divide`/`mod`/`round`/`floor`/`ceiling`/`abs`, **as instructions rather than an expression language** — D35's declarative model is intact, and an expression front end could later compile *down* to these. [17](17-value-computation.md) §§5, 8 (E26's cast cost found and fixed 2026-08-27: 1.5–4.7× faster than Saxon on the three value-computation cases) | arithmetic ✓, comparison ✓, value_types ✓ |
| Sequences, `distinct-values`, `index-of`, quantifiers | **gap** | store arrays exist; sequence *operations* over them do not |

## 4. The function library

| Function family | Verdict | Mechanism |
|---|---|---|
| `concat` | **covered** | multi-part refs |
| `substring`, `substring-before/after` | **covered / proven** | `substring`'s base is **version-gated** (ruled 2026-08-27, superseding the 2026-08-21 documented-as-is ruling): 0-based below configuration version 5, 1-based from it — XSLT's own reading — with a start below 1 shrinking the window per XPath, an omitted start meaning "from the beginning" under either base, and a compile warning on any pre-5 configuration a bump would change. `substring-before`/`after` are now native instructions, **absent when the marker is missing** where XSLT returns `""` — the written output is identical, the testable value is not. [17](17-value-computation.md) §§6–7 | string_functions ✓ |
| `translate` | **covered** | `translate` |
| `upper-case`, `lower-case` | **covered** | same names |
| `normalize-space` | **covered** | `normalize-space`, plus `trim` |
| `replace` (regex), `matches`, `tokenize` | **covered** | same names, byte-level regex |
| `string-join` | **covered** | `string-join` |
| `string-length`, `starts-with`, `ends-with`, `contains` | **covered, proven** (2026-08-27) | all four are native value instructions now — `string-length` counts **code points**, the predicates bind `Bool`. The `matches`-condition idiom stays valid; what closed is length-as-a-value, and the three predicates as *values* rather than only as conditions | string_functions ✓ |
| `format-number` | **covered, proven** (2026-08-27) | `format-number` with its picture compiled once, `DecimalFormat` under `Locale.ROOT` — which matches XSLT's default decimal format on the ordinary pictures and diverges at the edges (infinity renders `∞` where XSLT says `Infinity`), pinned as a documented divergence rather than discovered later | string_functions ✓ |
| `format-dateTime`, date/duration arithmetic | **covered, proven** (2026-08-27) | the composed pair the 2026-08-21 ruling required, built: `parse-date` → a first-class `Instant` (epoch second, nanosecond, and the offset it arrived with, **inert** in comparison and arithmetic), `format-date` back out, patterns compiled once, `iso`/`epoch-millis`/`epoch-seconds` bypassing the formatter. Duration arithmetic falls out of the millis cast. The yearless syslog stamp — the case this row predicted would be hit first — takes its year from a **`reference` date supplied as data**, Stroom's nearest-year rule with the input made explicit, since the engine has no clock and must not acquire one. Measured **8.78× faster than Saxon**, with the disclosure that XSLT has no native nearest-year rule so the stylesheet builds one ([17](17-value-computation.md) §13) | dates ✓ |
| `sum`, `count`, `avg`, `min`, `max` | **gap — designed, ruled, unbuilt** | aggregation over matches = accumulator territory; classify mode + stores get partway, nothing folds. [16](16-sequences-and-aggregation.md) §8 designs all five as folds over a store, ruled 2026-08-25; implementation is E23 |
| `number()` | **covered** | `number` |
| `generate-id`, `id()`, `document()`, `doc()` | **out of scope** | identity and secondary documents are pipeline concerns (reference data), not transform concerns — Stroom itself agrees, via `stroom:lookup` living outside XSLT |
| `current-dateTime()` etc. | **out of scope** | injecting wall-clock into a deterministic transform breaks golden parity by definition; Stroom feeds times through data or context |
| `analyze-string` (2.0) | **expressible, proven** | a level of regex templates over a captured value | analyze_string ✓ |
| `unparsed-text` (2.0), `xsl:evaluate` (3.0), streaming/accumulators (3.0), `xsl:try` (3.0), maps/arrays (3.0) | **out of scope / gap-by-ruling** | 3.0's streaming machinery solves a problem the byte engine does not have; `try` partially met by `emit-error`+`fatal`; maps/arrays await evidence |

## 5. The score, and what it means

Counting the verdict column, exactly, across 45 rows: **22 covered, 7 expressible, 7 gaps
(one of them — non-adjacent grouping/keys — executably documented as a wall the suite trips
on the day it is solved), 9 out of scope**, with **11 rows proven by a catalogue case**. The
figures before 2026-08-27 were approximate and summed past the row count, so the deltas below
are stated as *which rows moved*, not as arithmetic on the old totals.

**What the value-computation tranche moved** (2026-08-27, [17](17-value-computation.md)):

| Row | Was | Now |
|---|---|---|
| General/value comparisons, arithmetic (§3) | *partial* | **covered, proven** |
| `string-length`, `starts-with`, `ends-with`, `contains` (§4) | expressible | **covered, proven** |
| `format-number` (§4) | gap | **covered, proven** |
| `format-dateTime`, date/duration arithmetic (§4) | gap | **covered, proven** |

Two gaps closed, one idiom promoted to a mechanism, and the *partial* verdict retired
entirely — it was the only row carrying it. `substring`'s row keeps its verdict but changes
its ruling: the 0-based/1-based trap is now a version gate rather than a documented wart.

The authoring traps the proving cases found, and where they now stand: `substring`'s base is
**settled by version 5** rather than documented-as-is (superseding the 2026-08-21 ruling);
the `is-first`/`is-last` conditions deleted by E21 are **ruled to return** with iteration
([16](16-sequences-and-aggregation.md) §4.3), which is the case E21 said to wait for; and
E19's stale-capture trap — an optional capture that fails to re-match reading the previous
record's value through an `exists` test — stands, met live by `modes` and dodged by deciding
branches at dispatch time.

The gaps still cluster into families, but there are **two of them now, not three**:

1. **Whole-input state before output** — sort, group-by, keys, aggregation, distinct-values.
   One architectural question wearing five names. `keys_grouping` is the case that forces it.
   **Designed and ruled in full** ([16](16-sequences-and-aggregation.md), 2026-08-25), where
   the answer turned out smaller than this framing implied — the accumulator is the store,
   which has been there since the port, and what was missing is iteration. Unbuilt: E23.
2. ~~**Value computation**~~ — **closed 2026-08-27.** Arithmetic, string-length-as-value,
   format-number and the date pair all shipped, each proven byte-identical against Saxon by
   its own catalogue case. The date vocabulary is the composed `format-date(parse-date())`
   pair the 2026-08-21 ruling required, not a port of `stroom:format-date`'s conflated
   signature. Two performance issues came out of it — E26 (the numeric casts answered "no" by
   throwing) **fixed 2026-08-27**, turning the three value-computation cases from losses into
   1.5–4.7× wins over Saxon, and E27 (three compile-time body walks) still open. Neither was
   a capability gap.
3. **Output routing** — result-document / multiple sinks. Already owned by D10/E15, and
   walled executably: `dual_output` is the second wall, tripped the day routing lands.

Everything else is either covered, an idiom awaiting its proving case, or refused with a
reason that survives being read aloud.
