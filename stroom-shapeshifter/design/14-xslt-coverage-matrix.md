# XSLT coverage matrix — what shapeshifter covers, can express, lacks, and refuses

Status: living document, first drawn 2026-08-21, last scored 2026-08-27 at the close of
[16-sequences-and-aggregation.md](16-sequences-and-aggregation.md)'s tranche — the one that
closed the last gap family. Organised by the XSLT 2.0 specification's
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
| `xsl:sort` | **covered, proven** (2026-08-27) | `sort` on a `for-each`: a list of keys, each with its own `as` cast and `order`, compared in order and stably. It reorders an `int[]` of the store's indexes rather than the values, so the entries stay where they are and `__index` still reaches parallel stores ([16](16-sequences-and-aggregation.md) §5) | sort ✓ |
| `for-each-group group-by` (non-adjacent) | **covered, proven** (2026-08-27) | `for-each-group` with `group_by`, groups in first-appearance order, binding `__group_key`, `__group` and `__group_size`. This was the expected capability wall, and it came down: `keys_grouping` was promoted from the suite's `WALLS` to its `CASES` on the day a challenger existed | keys_grouping ✓ |
| `for-each-group group-adjacent` | **expressible** | adjacency is dispatch's native gait | — |
| `group-starting-with` / `ending-with` | **expressible, proven — with a found limit** | strict dispatch with a starting template; group position via `__match_count`; the boundary close via choose-on-count. Limit found at amplified scale (2026-08-21): a **trailing empty group** serializes as `<x></x>` where Saxon self-closes `<x/>`. **Rediagnosed 2026-08-27** by `keys_lookup`, which hit the identical divergence and could fix it: the limit was never about self-closing, it was about not knowing the size in advance. Anything that counts before it opens a tag dissolves it — `count` over a bound sequence (§4), or `__group_size` inside a native grouping, both of which are known before the first byte. The dispatch idiom in this row still cannot, since it learns the group is over by leaving it; what changed is that the author now has a form that can | adjacent_groups ✓, keys_lookup ✓ |
| `xsl:key` / `key()` | **covered, proven** (2026-08-27) | `key` builds the index; `key-get` reaches one entry by value and binds the hits as a sequence, so a miss binds an empty sequence a walk runs zero times rather than raising. One index builder serves both this and grouping — grouping walks every entry, a key reaches one | keys_lookup ✓ |

## 3. XPath semantics against matched content

| XPath | Verdict | Mechanism / idiom / reason | Case |
|---|---|---|---|
| Downward paths (`a/b/c`, predicates on structure) | **expressible** | nested levels, or a single pattern spanning the structure (nasty's five-deep pull) — proven ✓ |
| `position()` / `last()` | **covered, proven** | `__match_count` works as position, tested with `equals`; the dead `is-first`/`is-last` conditions the case found were **deleted by E21's ruling** (2026-08-21) — count equality is the documented idiom. [16](16-sequences-and-aggregation.md) §4.3 ruled that iteration is the case E21 said to wait for, and that both the conditions and `__position`/`__last` return with it. **Built 2026-08-27** (E23): inside an iteration `__position` is the position *after* sorting and `__last` is known before the first entry runs, which is what makes a last-item test correct rather than retrospective; `is-first`/`is-last` are their direct spellings. Outside any iteration nothing sets them and the conditions are false — E21's original hazard, now a compile warning rather than a silent wrong answer | adjacent_groups ✓, sequence_basics ✓ |
| Upward/sideways axes (`ancestor::`, `preceding-sibling::`) | **out of scope** | there is no tree to walk back up; state wanted from "above" is captured on the way down (the `batch` var in nasty is exactly `../@id`) — proven ✓ |
| General/value comparisons, arithmetic | **covered, proven** (2026-08-27) | comparisons are one strict typed vocabulary — `eq`/`ne`/`lt`/`le`/`gt`/`ge`, same-kind natively, cross-kind false, casts explicit on the operand (`as`), the legacy spellings kept as aliases carrying the casts their semantics implied. Arithmetic is `add`/`subtract`/`multiply`/`divide`/`mod`/`round`/`floor`/`ceiling`/`abs`, **as instructions rather than an expression language** — D35's declarative model is intact, and an expression front end could later compile *down* to these. [17](17-value-computation.md) §§5, 8 (E26's cast cost found and fixed 2026-08-27: 1.5–4.7× faster than Saxon on the three value-computation cases) | arithmetic ✓, comparison ✓, value_types ✓ |
| Sequences, `distinct-values` | **covered, proven** (2026-08-27) | a store **is** the sequence — the answer [16](16-sequences-and-aggregation.md) found was that the accumulator had been there since the port and what was missing was iteration. `sequence` declares, `append` adds, `for-each` walks, `tokenize` and `distinct-values` bind new ones. Two kinds of store share one walk rule: a capture-indexed store keeps its holes so an index still names the match that made it, a dense one has none | sequence_basics ✓, aggregate ✓ |
| `index-of`, quantifiers (`some`/`every`) | **gap** | the sequence machinery they would be written against now exists, so these are small; no case has asked for them, and the [16](16-sequences-and-aggregation.md) tranche deliberately built what `keys_grouping` forced rather than the whole of XPath's sequence library |

## 4. The function library

| Function family | Verdict | Mechanism | Case |
|---|---|---|---|
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
| `sum`, `count`, `avg`, `min`, `max` | **covered, proven** (2026-08-27) | all five as folds over a sequence, with the empty sequence answered as XPath answers it and not once: `sum(())` is **0** — a real total of nothing — while `avg(())` is **empty**, because a mean of nothing is not a number and zero would look like an answer. `min`/`max` take an `as` cast, since which of two values is smaller depends on whether you asked as text or as a number | aggregate ✓ |
| `number()` | **covered** | `number` |
| `generate-id`, `id()`, `document()`, `doc()` | **out of scope** | identity and secondary documents are pipeline concerns (reference data), not transform concerns — Stroom itself agrees, via `stroom:lookup` living outside XSLT |
| `current-dateTime()` etc. | **out of scope** | injecting wall-clock into a deterministic transform breaks golden parity by definition; Stroom feeds times through data or context |
| `analyze-string` (2.0) | **expressible, proven** | a level of regex templates over a captured value | analyze_string ✓ |
| `unparsed-text` (2.0), `xsl:evaluate` (3.0), streaming/accumulators (3.0), `xsl:try` (3.0), maps/arrays (3.0) | **out of scope / gap-by-ruling** | 3.0's streaming machinery solves a problem the byte engine does not have; `try` partially met by `emit-error`+`fatal`; maps/arrays await evidence |

## 5. The score, and what it means

Counting the verdict column, exactly, across 46 rows: **27 covered, 7 expressible, 3 gaps,
9 out of scope**, with **16 rows proven by a catalogue case**. The row count went up by one
because a row split rather than moved: sequences and `distinct-values` are covered, while
`index-of` and the quantifiers are not, and one verdict could not honestly cover both.

The wall came down. `keys_grouping` existed as an *executable* statement that this engine
could not group — a case in the suite's `WALLS` that would start failing the day a challenger
appeared — and on 2026-08-27 it was promoted to `CASES` and proved byte-for-byte against
Saxon. Documenting a gap as something the build trips over turns out to be worth the trouble:
nothing had to be remembered.

**What the sequences-and-aggregation tranche moved** (2026-08-27,
[16](16-sequences-and-aggregation.md), E23):

| Row | Was | Now |
|---|---|---|
| `xsl:sort` (§2) | gap | **covered, proven** |
| `for-each-group group-by` non-adjacent (§2) | gap, walled | **covered, proven** |
| `xsl:key` / `key()` (§2) | gap, walled | **covered, proven** |
| Sequences, `distinct-values` (§3) | gap | **covered, proven** |
| `sum`, `count`, `avg`, `min`, `max` (§4) | gap, designed but unbuilt | **covered, proven** |
| `position()` / `last()` (§3) | covered, with `is-first`/`is-last` ruled-but-unbuilt | **covered, proven**, conditions built |

Five gaps closed and one new one opened deliberately (`index-of`, quantifiers), which is the
honest shape of building what a case forced rather than the whole of XPath. Two rows changed
their *diagnosis* rather than their verdict, and both are worth more than a verdict change:
`group-starting-with`'s trailing-empty-group limit turned out not to be about self-closing at
all but about not knowing a group's size in advance, found when `keys_lookup` hit the same
divergence in a place where it *could* be fixed; and `position()`/`last()`'s restored
`is-first`/`is-last` conditions come back with a compile warning outside any iteration, which
is E21's original hazard caught rather than merely avoided.

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

**There is one gap family left, not three.** The three that this document has carried since
it was first drawn have gone in the order they were understood:

1. ~~**Whole-input state before output**~~ — sort, group-by, keys, aggregation,
   distinct-values. **Closed 2026-08-27** (E23). One architectural question wearing five
   names, and the answer turned out much smaller than the framing implied: the accumulator was
   the store, which has been there since the port, and what was missing was *iteration*. Once
   a store could be walked, sorting was an `int[]` of indexes, grouping and keys were one
   index builder read two ways, and the folds were folds. The design's own summary of itself
   — [16](16-sequences-and-aggregation.md) §1 — is that it adds no third layer, and it did
   not: everything here is the existing two layers with a walk over them.
2. ~~**Value computation**~~ — **closed 2026-08-27.** Arithmetic, string-length-as-value,
   format-number and the date pair all shipped, each proven byte-identical against Saxon by
   its own catalogue case. The date vocabulary is the composed `format-date(parse-date())`
   pair the 2026-08-21 ruling required, not a port of `stroom:format-date`'s conflated
   signature. Two performance issues came out of it — E26 (the numeric casts answered "no" by
   throwing) **fixed 2026-08-27**, turning the three value-computation cases from losses into
   1.5–4.7× wins over Saxon, and E27 (three compile-time body walks) **fixed 2026-08-27** as
   one `BodyScan` pass. Neither was a capability gap.
3. **Output routing** — result-document / multiple sinks. Already owned by D10/E15, and
   walled executably: `dual_output` is the second wall, tripped the day routing lands.

Two gaps remain that are not a family and do not want to be described as one: **tunnel
parameters**, which await a real case before any ruling, and **`index-of` and the
quantifiers**, which are now small — the sequence machinery they would be written against
exists — and equally await a case asking for them.

Everything else is either covered, an idiom awaiting its proving case, or refused with a
reason that survives being read aloud. What that adds up to, said plainly: for log
transformation and data extraction, the shape this engine is for, there is no longer a
capability this document knows about and cannot do. What is left is one routing question, two
unrequested XPath functions, and a list of deliberate refusals.
