# Design 39 — Reads in the match sequence

*Proposed 2026-09-17, from design 38's closing reading: the match sequence parses a real
binary format correctly at half the retired interpreter's speed on small fields, and the
census says the cost is a regex call per field that does no matching. Ruled the same morning
by the owner (D56): the reads come back as a verb; the program does not; the regex library is
not touched.*

## 1. The finding

Design 38 §7's phase-5 record: on the binary path every field is a pattern part, a pattern
part is a regex call, and a regex call has a fixed cost — the matcher's setup, the anchoring
question, the plan runner's entry, a `MatchResult` and its group slices — that no one-byte
field can amortise. The synthetic row read 0.53× the interpreter (point 53 against 52), and
`avro_users`, the real row, runs at 246 ops/s where its fields are a varint, a fixed width or
a length-prefixed take — none of them matching.

The interpreter read a varint in three lines of byte arithmetic. It also carried
twenty-four step kinds, choice, repeat, a result list and a boxed integer per step, and PEG
commitment; design 38 retired all of that on purpose and the retirement stands.

## 2. The ruling — D56

**One verb, `read`: a binary cast applied at the cursor, its width the cast's own.** A
fixed-width cast reads that many bytes; a varint reads until the byte without its high bit;
`position` reads nothing and yields the offset. The value is the cast's — an integer, a
double, a flag — bound to the part's label, and the cursor moves past what was read. No
regex is compiled, no matcher runs, nothing is sliced; the bytes are read where they lie.

**What stays out, and it is the line design 38 §7 drew:** no choice, no repeat, no
conditional part, nothing that decides. A read is data at a known place; the template
layer's dispatch is where structure lives, and the Avro and protobuf fixtures already prove
it can. `take` and `seek` are unchanged; `read` is their third, and the last: the three
verbs between them are exactly what a binary format's framing is made of — a value, a span, a
position — and a fourth would be a program.

**The regex library is not touched.** Patterns remain the match sequence's matching part,
for text inside binary and for anything a read cannot name.

## 3. The surface

```
{"read": {"as": "zigzag", "label": "count"}}
{"read": {"as": "float64le", "label": "score"}}
{"read": "uint8"}                                  — unlabelled: advance past a byte
{"read": {"as": "position", "label": "here"}}
```

`as` is required; `label` is optional. A read with no label consumes and binds nothing,
which is `seek` by the cast's width for a fixed width and the only way to skip a varint.
`take` keeps its `length` and `label` and gains nothing: a take is bytes, a read is a value.

## 4. Compilation and execution

`MatchPart.Read(BinaryCast as, String label)` → `CompiledPart.Read(BinaryCast cast, int
group)`, the group allocated as a take's is. The level's `partsMatch` gains one arm: the
width from the cast (`BinaryCast.width()`: 1, 2, 4, 8, 0 for `position`, −1 for a varint,
which is measured at the cursor — up to ten bytes, the last without its high bit, else the
match fails as a truncated record should); fewer bytes than the width fails the match;
`BinaryCasts.apply` over the array, offset and width yields the value into the group. The
same reading a labelled pattern node's cast gives, from the same table, so a `read` and a
`{"take": 8, "as": "float64le"}` inside a pattern bind identical values — pinned.

## 5. What it is expected to measure

The claim is `avro_users`: seven zigzags, a double and a flag per user become nine reads
and one take, zero regex calls per record; the row should move by a large fraction, and the
size is the reading. `progressive_len_records`, rewritten to a read and a take, is run by
name against the interpreter's 470 ops/s at point 52 — the number the retirement was read
against — and should now exceed it, since a read is the interpreter's arithmetic without its
result list. Every other row cannot move. The fixtures that use a varint pattern are
rewritten to `read` and must stay byte-identical on output; `progressive_mixed_endian` keeps
its casts inside a pattern tree, so that path stays exercised.

### Built and read by day — 2026-09-17

Phase 1 built: the verb through model, JSON, compiler, level and casts; `BinaryCast.width()`;
`BinaryCasts.read` over the raw array; the take arm behind a call beside the seek so the
sequence loop stays at 311 bytes with four verbs in it. Five fixtures rewritten from varint
patterns to reads — `avro_users` is nine reads and one take per user — and byte-identical.
Pinned: a read binds the value the pattern's cast bound, for a varint in both signs, a fixed
width, a flag and a position; a truncated read fails the match. The pin caught one bug that
was not this design's: a `position` cast inside a pattern *part* counted from the part, not
the sequence, against `BinaryCast`'s contract; a pattern part now takes its offset in the
sequence as the base, so a position is from the start of the match whichever part it is in.

Interleaved against 60, three rounds: **`avro_users` +62, +84, +70 — 246 to about 420
ops/s; `progressive` (by name) +91, +80, +82 — 252 to 470–482, the interpreter's 470 at
point 52**, the number the retirement was read against. `progressive_text` flat.
`regex_lines` −3.4, −4.2, −4.8: three daytime interleaves in two days have read that row
down about four per cent whenever `Level` changed, and the one full-fidelity reading (60
against 59) read it up 5.7; it is recorded as an artefact of the live-tree comparison until
a full run says otherwise, and the evening point is the reading.

One behaviour the fixtures did not depend on changes with the rewrite, and is intended: the
varint *pattern* accepted a zero-byte run at the end of the data — its second class is
`{0,1}` — binding absence and letting the match succeed; a `read` fails the match on a
truncated value, as §4 says and as a short record should.

**Against the native crate, the same morning, back to back on an idle box** (the same
263,371-byte file and the method of design 38 §7's phase-5 reading: the crate decoding every
field into its `Value` tree and writing nothing; the engine with the record template's body
emptied — the match, nine reads, one take, four captures and the dispatch per record, nothing
written):

| the same file | mean |
|---|---|
| `apache-avro` crate, decode only | 1.47 ms |
| Shapeshifter, parse only | **0.60 ms** |
| Shapeshifter, parse and 784 KB of XML written (the `avro_users` row) | 2.48 ms |

The parse is 2.4× the crate's decode, where the day before it was two thirds of it. The
crate builds a heap value per field; the engine now reads the bytes where they lie into
slices and integers with no matcher, no result object and no tree — the interpreter's loop
without its result list. Three quarters of the row is now the XML the sinks write, which is
the same cost on every row. The claim is bounded as before: faster at this job, on this
box, by day; not a faster Avro library.

So D56 delivers what §5 asked: the retirement's binary cost recovered in full, with three
framing verbs and no program, and the regex library untouched.

## 6. Phases

1. The verb: model, JSON, compiler, level, casts by width; `EveryVariantTest`; the
   identical-value pin; the fixtures rewritten. One commit. Done 2026-09-17.
2. Read: `avro_users` and `progressive` (by name) against 60, interleaved by day, a point in
   the evening.
