# Design 41 — The structure path, censused and cut

*Proposed and built 2026-09-17, on the owner's "let's chase" after design 40 read the engine
at 300 ns and a kilobyte per structural event it emits — 1.5× behind Xerces, 3.3× behind
Woodstox, 14× behind Jackson, on parses where the matching was already a scan plan. Design
37's method, applied to the one path it never reached: the ops that emit structure, the sink
that carries it, and the transforms a value passes through on the way.*

## 1. The census — the XML parse row at 100,000 records (29 MB, 700,000 `data` elements)

Not a guess; the row with pieces of its configuration removed, timed and allocation-counted
in one process:

| configuration | ms/op | MB/op | the difference is |
|---|---|---|---|
| as written | 182 | 821 | |
| without the three-replace entity chain | 108 | 506 | **the chain: 74 ms, 315 MB — 40% of the row** |
| without the `value` attribute | 85 | 401 | one attribute: 23 ms, 105 MB |
| without any attribute | 61 | 213 | the `name` attribute: 24 ms, 187 MB |
| what is left | 61 | 213 | elements, dispatch, matching: ~300 B and 87 ns each |

Then JFR allocation samples by class and site, and CPU samples by frame. Three findings.

**The replace chain was the wrong tool, three times over.** Decoding `&lt; &gt; &amp;` took
three `replace` ops with two intermediate variables. Each op resolved a list of inputs into a
fresh `ArrayList`, turned the value into a `String`, called `String.replace`, re-encoded the
result to bytes *whether or not anything matched*, and bound the intermediate — and the two
declarations on the template forced a `VarRegistry` frame push and pop per element (8.6% of
CPU on their own). Two million transform calls per operation. And for JSON the same idiom is
*wrong*: `\\n` — an escaped backslash then an n — decodes as a newline through a chain.

**The sink allocated per attribute and per element what it could keep.** An `Attribute`
object holding a `ByteArrayOutputStream` per attribute, grown from empty (`ensureCapacity`
was 9.8% of CPU by itself), then `toString`; an `AttributesImpl` per element; an `Element`
object per element, an eighth of all allocation.

**The matching was never the cost.** The configuration was first written with
`<record>(.*?)</record>` — the NFA tier, D55's shape — and rewritten so every template is a
scan plan; the time did not move. After the cuts below the matching is the largest item
left, which is the right way round.

## 2. The cuts — each built, gated, measured

1. **Two text codecs on `decode`, so entity and escape decoding is one op with no
   intermediates: `xml_entities` and `json_string`** (`Codec`, `Codecs`). Byte-wise over the
   value's UTF-8 form — a text codec reads characters, so it reads the UTF-8 form whatever the
   value was read under, and a Latin-1 slice with `&amp;` in it decodes correctly — and a value
   with no `&` or `\` in it is returned as itself, no allocation. The five predefined entities
   and numeric references in both bases; the eight JSON short escapes and the unicode escape
   with surrogate pairs joined; anything else left as written, which is what a value that was
   never escaped needs. This is not a benchmark trick: it is what any configuration reading
   XML or JSON needs and did not have, and the JSON corpus now carries the `\\n` case the
   chain got wrong. Pinned in `TransformsTest`.
2. **`replaceLiteral` on bytes** (`Transforms`): a byte-wise search over the value where it
   lies — a UTF-8 slice in place, no copy — returning the value itself when the pattern is
   absent; `String.replace` and the re-encode only when it is present, and then only the
   result. The same search as character-wise for well-formed UTF-8, since the encoding is
   self-synchronising.
3. **One scratch inputs list on `Body`** (`Body.inputs`): a transform reads its inputs and
   returns a value, never the list, and nothing re-enters a transform while one runs, so one
   list cleared per call replaces one allocated per instruction.
4. **The sink keeps what it can** (`SaxEventSink`): the open attribute is three fields and one
   growable byte buffer the sink owns, not an object and a stream per attribute; one
   `AttributesImpl`, cleared per element, since SAX lets a handler read it only during the
   call; ended elements go on a spare list and are reopened, their arrays kept and their
   entries nulled, so an element per event is an element per depth.

Not touched: the match's own results — a `MatchResult`, a groups array and a slice per group
per element are now half the remaining allocation, and that is design 37 §5's 3d question,
answered there and not reopened here; the regex library.

## 3. The reading

The XML row at 100,000 records, in one process, each cut cumulative:

| after | ms/op | MB/op |
|---|---|---|
| nothing | 182 | 821 |
| `replaceLiteral` on bytes | 177 | 722 |
| + the scratch list, the slice searched in place | 178 | 610 |
| + `decode` with `xml_entities` in place of the chain | 121 | 543 |
| + the sink's buffer, list and spare elements | **106** | **282** |

At full fidelity (`2026-09-17-0850-7a43dd9498-parse41.json`, against design 40's first reading):

| at 1M records | before | after | Xerces | Woodstox / Jackson |
|---|---|---|---|---|
| XML, bytes | 1,753 ms (159 MiB/s) | **1,035 ms (270 MiB/s)** | 1,164 (240) | 535 (522) |
| JSON, bytes | 2,388 ms (59 MiB/s) | **1,849 ms (76 MiB/s)** | — | 169 (836) |

**On this XML shape the engine is now faster than the JDK's SAX parser from bytes and half
Woodstox's speed**, at a third of the allocation it had. JSON gained less — only its string
fields were on the chain, and what remains there is the match path — and stays an order of
magnitude behind Jackson, which is a different kind of parser and was never the target.

What the profile says is left, in order: the matching (a third: the plan runner doing its
job, plus `regexMatch` binding its groups); the match's results (half the allocation);
`String` creation for attribute values, which SAX requires; the sink at about a tenth.

## 3a. The JSON row: two shapes, no engine change — the same day

*On the owner's "any further thoughts on the JSON issue?"* JSON emitted the same structure
as XML per record — seven elements, two attributes each — and took 1.8× as long, so the
difference was in the dispatches, not the sink. Two things in the configuration, measured in
one process at 100,000 records:

| JSON configuration | ms/op |
|---|---|
| as committed at point 62 | 181 |
| separators folded into the field patterns — `[{,]"name":…` — so a field is one dispatch, not a field, a separator consume and (for a number) a failed attempt at the string template first | 148 |
| + the escape-aware run in unrolled-loop form — `[^"\\]*(?:\\.[^"\\]*)*` rather than `(?:[^"\\]\|\\.)*` — the same language, but a scan instruction per run and a branch per *escape* where the other form was a branch per *character* | **102** |

XML's row is 106 for the same output. Parity holds; adopted as the committed configuration.
At full fidelity (`2026-09-17-1017-a38255c678-jsonparse41.json`): **1,849 → 1,273 ms at a million records — 111 MiB/s
from bytes, 119 from a string** — against Jackson's 836. Six times behind rather than
eleven, and now for the same reason XML is two behind Woodstox: the match path per field.

Two lessons for anyone writing a configuration, and they belong in the editor's guidance:
one dispatch per thing, so fold what separates things into the pattern that matches them;
and write a run that may contain escapes in the unrolled form, because the plan compiler
scans a class run and branches a repeated alternation. The second is something the library
could do itself — rewrite a repeat of "a class or an escape" into the unrolled loop at
normalisation — and is noted here for D53's ledger of what the library is not asked for.

## 4. The engine's own rows

Three of the cuts sit under every configuration — `Body.inputs` under every transform,
`replaceLiteral` under every literal replace, the sink under every SAX-target run — so the
engine rows were interleaved against HEAD (`7a43dd9498`), three rounds: `apache_httpd` +3.1,
+0.2, +0.8 (68,000 transforms per op, three of three up); `csv_header` +3.3, +1.1, −0.5;
`win_sec` +1.6, −0.2, +0.6; `log_sessions` −1.3, −0.7, +0.7; `regex_lines` −5.3, −0.9, −6.2.
That last is the fourth daytime interleave in two days to read `regex_lines` down by a few
per cent on a change the row does not execute — it runs `value-of` and nothing else, and no
cut here is on that path — and the two full-fidelity readings so far (points 60 and 61
against their predecessors) both read it flat or up. It is recorded as an artefact of the
live-tree comparison on that row until a full run says otherwise; the evening point is the
reading, and the row is named in it.

## 5. Point 62

One commit: the codecs, the three engine cuts, the sink, the pins, the two parse
configurations moved onto `decode`, the JSON corpus with its `\\n`. Expected against 61:
`apache_httpd` up a point, every other default row flat, `regex_lines` flat at fidelity —
and the parse rows as §3 reads, which are xmlbench's to re-read.
