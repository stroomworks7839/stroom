# Design 38 — One composition library: the step layer lowers onto the regex engine

*Proposed 2026-09-16 as design 37 phase 9's output, on the owner's reading of what the step
layer was for and what has changed since it was built.*

*Engine and regex library. It touches configuration: the progressive fixtures' configurations
move to the new surface, their inputs and outputs do not, and two real binary files from the
prototype's repository join them with their outputs kept. The regex library changes as little
as the coverage table demands — two combinators, nothing else.*

## 1. Where the step layer came from, and why it is now two things

The progressive step mode is a pattern built from primitive matchers — atoms — joined by
combinators, producing typed outputs a later step or the body can refer to. `Tag`, `TakeWhile`,
`TakeUntil`, `TakeN`, `ReadNumeric`, `ReadVarint`, the seeks and `Tell` are the atoms; `Sequence`,
`Choice`, `Optional`, `Repeat`, `Peek`, `Not` the combinators. Its purpose was composition: a
match built from named parts rather than one expression, with typed captures, reaching what a
regex cannot — binary structure and lengths that depend on data already read.

It was built in the Rust prototype around a regex library the prototype did not own, so it had
to be a second driver beside the regex: its own interpreter over its own instruction set, with
PEG commitment (D34) because that is what an interpreter over atoms gives cheaply. Design 9
recorded the consequence: *atoms are semantics-neutral*, the semantics belongs to the driver,
and E14 has held open since 2026-08-20 the question of lowering the textual subset onto the
engine's own regex library — an optimisation question, not a semantic one.

Two things have changed since. **The engine owns its regex library**, and that library already
has the composition layer: `comb` builds a pattern from named parts — `sequence(takeWhile("[a-z]")
.label("key"), tag("="), …)` — and lowers it to the same intermediate form a regex parses to,
with a test pinning that a composition and the regex meaning the same thing compile to the
*identical* plan. Labels are groups; an embedded regex keeps its groups, renumbered around the
labels; a `MatcherLibrary` holds named definitions and ships a standard library. **And design 37
priced the interpreter**: `Steps.step` is 1654 bytes and `Steps.match` 350, neither inlines,
and per match the interpreter allocates a `Result` record per step, a boxed integer per numeric
read, an output list, a groups array and a copy of the span — `progressive` is 8% under the
pre-design-35 floor and design 34's attempt on the list alone lost 4% for a reason never found.

So the step layer is now two things: a *composition model*, which the regex library also has,
and an *interpreter*, which is slower than the engines the regex library already runs on.
This design keeps the first and removes the second.

## 2. The model

**A match is a composition tree.** Its leaves are the atoms and a regex string; its interior
nodes are the combinators; a label on any node is a capture; a name refers to a part defined
once in a library — the project's own or the standard one. The tree is what the UI edits and
what the configuration stores. At compile time the tree is *decomposed* — every reference
inlined, every regex leaf parsed — to one plan, and that plan runs on the tiered engines like
any other regex. Nothing is interpreted at run time.

**Composition goes both ways.** A regex is one kind of leaf, so a long expression is written as
a tree of short ones: `sequence(ref("IP_ADDRESS"), tag(" "), ref("NAME"))` where `IP_ADDRESS`
and `NAME` are regexes a library defines. A combinator may wrap a combinator to any depth; the
compile-time decomposition flattens it. This is what "easy to author long expressions" means:
the mile-long pattern is never written, only its parts, and the parts have names.

**Semantics: one regime.** Design 9's first regime — within a match, full backtracking,
leftmost-first — is the semantics of every match. D34's PEG commitment for the step layer
is retired with the interpreter: commitment between alternatives is what *templates* are for,
ordered at a level with each match committed once emitted (design 9's second and third
regimes), and a configuration that needs a match to commit inside itself has atomic groups
and possessive quantifiers, which the fancy tier supports. `StepsTest`'s commitment pins are
re-read against this in phase 1, and each is either the same under backtracking (most are:
the first alternative that matches whole *is* leftmost-first) or is a semantic change recorded
as D34's successor.

**Rulings of 2026-09-16.** The node names are the library's, in snake case, so the UI, the
configuration and the compiler share one vocabulary and an explode maps without renaming. A
body reads a labelled capture as `{"capture": {"label": "ip"}}` beside the existing
`{"capture": {"group": 2}}`; the compiler resolves the label to its group at compile time.
`Tell` is a `position` cast on a labelled empty node — the group's offset within the match,
which the matcher already reports. `Decode` moves into the body as a transform over the
codecs the engine already has. A binary template declares `encoding: raw`, the library's
byte mode; the byte-strict ruling (D38) stands and a UTF-8 template still refuses
undecodable bytes. The rendering printer lives with the UI's model, outside the regex module.

## 3. Coverage: the step vocabulary against the library

| step | in the library | what it needs |
|---|---|---|
| `Tag`, `MatchByte` | `tag` | — |
| `TakeWhile` | `takeWhile`, `takeWhileOrNone` | — |
| `TakeUntil` | `takeUntil`, `takeThrough` | — |
| `TakeN`, `AnyChar` | `takeN`, `anyChar` | — |
| `Regex` | `regex` | — |
| `Sequence`, `Choice`, `Optional`, `Repeat` | `sequence`, `choice`, `optional`, `repeat` | — |
| `Peek`, `Not` | lookahead exists in the HIR (`Look`) and the regex; `comb` has no combinator for it | **two combinators**: `peek(m)`, `not(m)` lowering to `Look` |
| `Tell` | the offset of a labelled empty group, which the matcher reports (`start(g)`) | — (a label on `sequence()`) |
| `ReadNumeric` | regular: a fixed-width run, `takeN(width)` with a label | **a binary cast** on the capture: `int8/16/32/64`, signed or not, little- or big-endian; `float32/64` |
| `ReadVarint`, `ReadVarintZigZag` | regular: continuation bytes then a terminator, `[\x80-\xff]{0,9}[\x00-\x7f]` with a label | **a binary cast**: `varint`, `zigzag` |
| `Decode`, `Encode` | a transform of a captured region; the body has them | — (moves out of the match) |
| `Seek(n)` forward, fixed | `takeN(n)` unlabelled | — |
| `TakeBytes(ref)`, `Seek(ref)` | **not regular**: a length that is the value of an earlier capture | **not a pattern at all**: a *framing* verb in the template's match sequence (§3b) |
| `SeekAbs`, `SeekBack` | not regular, and need the whole input addressable — the configurations that use them cannot stream | a `seek` in the match sequence, to an offset from a label or a variable; forward only while the input streams |

Two additions to the library, then — the lookaround combinators — and a family of casts in the
engine. Everything else is a mapping, or framing.

**Why a count is not a pattern.** The first draft of this design put a counted repeat into
the fancy backtracker, mirrored on the backreference, and the owner's question was where a
count is really used. Not inside a match: a length inside a record — a varint then that many
bytes — decides *where the record ends*, and a length the data states once for every
following record is the same decision held in a variable. That is what the delimiter match
already is: a rule for cutting the next span out of the stream, which the body then works
on. Putting it in the regex would have taught the library that bytes are numbers, forced the
slowest tier on every binary pattern, and offered backtracking across a record boundary
that nothing wants. So the count is a verb in the template, and the library stays at two
additions. The in-match count instruction is kept in reserve for a case that needs a length
*inside* a choice or a repeat, which no fixture and no corpus does.

**The binary casts.** A capture today can be cast `as` string, number, integer, double or
boolean. Binary values are the same idea over bytes: the bytes a label matched, read as a
signed 32-bit little-endian integer, or a varint. They live where `as` lives — on the capture,
in the engine — and the regex library never knows a byte run means a number.

### What phase 1 read — 2026-09-16

*The pins.* All twenty `StepCombinatorsTest` programs were read under leftmost-first
backtracking: none changes its outcome. Fifteen are choice, optional, sequence, repeat and
lookaround programs whose PEG and backtracking results coincide on their inputs; the five about
the interpreter's output index space (`nestedSequenceIsOneOutput…`, `stepInsideANestedSequence…`,
`stepInsideTwoCombinators…`, `failedAlternativeOutputsDoNotSurvive…`, `theBufferGrows…`) become
pins about label scope — a label is reachable from anywhere after it and a label inside a failed
alternative does not participate, as a regex group does not. The one widening is unpinned and
named in D52: a possessive repeat becomes a greedy one that gives back what the rest of the
sequence needs.

*The five synthetic fixtures, in the new form.* `progressive_text_steps` is one pattern:
`regex`, `tag`, `take_while` (the predicate names map to class expressions — Alphabetic to
`\p{L}`, Numeric to `\p{Nd}`, NonWhitespace to `\S`, a custom set to its expression),
`take_until` with a multi-byte terminator, which the engine maps to a regex leaf of a lazy
any-run and a lookahead (`(?:.*?)(?= pid=)`) so the library gains nothing for it, and
`take_through`. `progressive_mixed_endian` is one raw-mode pattern with no framing at all:
`take 2 as uint16le`, `take 2 as uint16be`, `take 4` unlabelled for the seek, an empty
labelled node read `as position`, `take 4 as uint32be`. `progressive_varint_zigzag` is one
varint pattern with the `zigzag` cast. `progressive_len_records` is a two-part match sequence:
a varint pattern labelled `len as varint`, then `take` by `len`. `progressive_embedded_codec`
is the same sequence with the body decoding the taken span from base64 through a `decode`
transform over the engine's codecs, which exist and have no body op yet.

*The two real files, byte by byte.* `avro_users`: `Obj\x01`; a map count (zigzag varint, 2);
for each entry a zigzag-length string key and value — `avro.schema` and the schema JSON,
`avro.codec` and `null`; a zero map terminator; a sixteen-byte sync marker; then one block: a
record count (zigzag varint, 3), a byte size (zigzag varint, 46), the records, the sync marker
again. A record for this schema: a zigzag-length string, a zigzag varint, eight bytes of
little-endian double, one byte of boolean. As templates: a `header` root template whose
match sequence is the magic, the map (a pattern for the count, then the entries dispatched
to an entry template of `take` by a zigzag length twice), the terminator and the sync; a
`block` template of count, size, `take` by size, sync, whose body applies the taken span to a
`user` template in strict dispatch; a `user` template of one match sequence — length, take,
varint, eight bytes, one byte — with four labelled captures and the casts `zigzag`,
`float64le`, `boolean`. `protobuf_events`: a `message` root template of a varint length and a
`take`, whose body applies the span to `field` templates in strict dispatch, one per tag —
`\x08` then a varint, `\x12` then a varint length and a `take`, `\x18` then a byte — each
capturing into a scalar declared on the message, so an absent field reads as absent and the
body's `choose` writes the default. Both parses need only: `tag`, `take` by literal, `take`
by label, the varint pattern, the casts `varint`, `zigzag`, `uint16/32 le/be`, `float64le`,
`boolean`, and strict dispatch of a span to templates.

*What the reading changes in the design.* Nothing in the library's budget. Two mapping notes
for phase 3: the multi-byte `take_until` is a regex leaf, not a library node; and the
predicate names are class expressions. And the `decode` body op is a new op, small, over
codecs that exist.

## 3a. The round trip: a regex explodes into the tree, and the tree renders as a regex

The UI edits the tree, and an author must be able to bring a regex in and see a regex out.

**Explode.** The parser's intermediate form maps onto the vocabulary almost node for node: a
literal is a `tag`, a class under a repeat is `take_while`, a concatenation a `sequence`, an
alternation a `choice`, a repeat a `repeat` or `optional`, a named group a label, a lookaround
a `peek` or `not`. The regex-only vocabulary — anchors, word boundaries, backreferences, atomic
groups, lazy and possessive quantifiers, inline flags, Unicode property classes — stays as a
`regex` leaf inside the tree. So an explode always succeeds, and is exactly as structured as
the vocabulary allows; what it cannot name it keeps as a leaf that says what it is.

**Render.** Every node lowers to the intermediate form, and the intermediate form prints as a
regex — a printer the library does not have, about a hundred and fifty lines, and the UI's
need rather than the engine's, so it lives beside the UI's model, not in the regex module's
budget. Two nodes cannot print: `count`, which has no regex spelling by construction, and
`ref`, which prints as its definition and loses its name. The rendered regex is therefore a
view, never the store.

**Fidelity, and the oracle.** Explode then render preserves *meaning* exactly — both sides
compile to the same plan, which is the identical-plan pin `CombinatorTest` already holds — and
does not preserve text: `[a-z]+` and `[a-z]{1,}` are one plan. **The tree is canonical**: it is
what the configuration stores; a regex typed in is exploded on import, with names arriving
through named groups; the regex shown is a rendering, and an edit to it re-explodes, through
which labels survive as named groups and `ref`s do not unless the tree is kept. The gate is a
test over every regex in the fixture corpus: the plan of its explode equals its own plan.

### What phase 1a built — 2026-09-16

**The explode lives in the regex library**, not beside the UI's model — budget item 3, ruled
D53: `Matchers.explode(regex, flags)` returns the `Matcher`
composition a regex is, and the engine's `PatternExplode` maps that onto `PatternNode` node for
node — the same mapping `PatternCompiler` runs the other way. It is there because the
intermediate form is `internal`, "none of it API", and the only alternative was the engine
reaching into that package. The engine's side is thirty lines; the library's is one method
and one class reading its own parser.

**What it maps**: literal → tag (adjacent single characters folded, as the normaliser folds
them); class → character run, a class exactly once being a tag when it is a character; concat →
sequence; alt → choice; quantifier → repeat, `{0,1}` greedy being `optional`, and a class under
a greedy quantifier being the run itself; capturing group → label, an unnamed one named
`_N` by its number; lookahead → `peek`/`not`; non-capturing group → its body, since the tree
has structure without it. **What stays a leaf**: an anchor, a line anchor as `(?m:^)`, a word
boundary with its `u` spelled, `\G`, a backreference with its flags spelled on it, a raw byte
escape. **What cannot be a leaf** — an atomic group, a lookbehind — because a leaf needs source
text and the intermediate form has none: a regex holding either explodes as one leaf, whole,
until the printer exists. So the explode always succeeds, and is exactly as structured as the
vocabulary allows.

**The flags are absorbed**, not carried: the parser has already made a case-insensitive
`a` into `[Aa]` and a dot under `s` into every character, so the tree that comes out has no
flags and means what the regex meant. The cost is text: `(?i)[a-z]+` comes back as the run
`[A-Za-z\x{130}-\x{131}\x{17F}\x{212A}]` — the set it was, Kelvin sign and all — and the
oracle is what makes that an acceptable trade.

**The oracle holds, twice.** `ExplodeTest` (regex module): twenty-five regexes chosen to cover
every construct, under flags and in raw form, each explode compiling to the identical plan.
`PatternExplodeTest` (engine): **every regex in the fixture corpus** — 298 of them, template
matches under their template's encoding, `pattern` leaves, `matches` conditions and regex
replaces — exploded, compiled back through `PatternCompiler`, and equal in plan, group count
and every named group's number. The comparison is of instructions and sets; a character class's
label in the listing (the text it was written as) is set aside, since rewriting that text is
the one thing the explode does on purpose.

**The audit found two holes in the leaves**, both pinned: a rendered set beginning with `:`
read as a POSIX class opener, so `:` and `&` are escaped in a rendering; and the backreference
leaf spelled `u` unconditionally, which RAW refuses — `u` only decides how a fold compares, so
it is spelled only with `i`, and a raw regex with a backreference now explodes and compiles.

**Two changes in the library it took**, both semantic no-ops. `Normalise` now reads a repeat of
exactly once as its body — `a{1}` is `a`, and a composed run of one character is that
character's class — which is what lets a bare class explode as a run of one and compile to the
same instruction; the module's own explain pins did not move. And `Lowering`'s literal now
encodes through the pattern's byte form rather than UTF-8, so a `tag("é")` under RAW is the one
byte the parser makes of `é` in a regex — a hole in §6 item 2, found by writing the raw-form
oracle.

## 3b. Framing: a template's match is a sequence of parts

A binary parse is a sequence: fixed-width fields, one of which says how long the next thing
is; take that many bytes; perhaps skip to an offset a table gave; again for the next part. The
fixed-width fields are regular and a raw-mode pattern with binary casts reads them. What is
not regular is two verbs: *take this many bytes* and *seek to here*, with the amounts coming
from values already read. So a template's `match` may be a **sequence of parts**, each a
pattern or one of the two verbs, run in order over the content by the level:

```json
"match": [
    {"pattern": {"sequence": [{"take": 4, "label": "len", "as": "uint32le"},
                              {"take": 2, "label": "type", "as": "uint16be"}]}},
    {"take": {"label": "len"}},
    {"pattern": {"tag": "\r\n"}}
]
```

Each pattern contributes its labels; a `take` contributes one group holding the span; a
`seek` moves the cursor by a value or to an offset, forward only while the input streams. The
match is the whole sequence and consumes from the start of the first part to the end of the
last. Nothing backtracks across parts, which is right for a format; everything inside a part
is the regex engine. Lengths come from labels in earlier parts or from variables, through the
engine's casts, so the library never sees a number. Fewer bytes than a take asks for fails
the match, which is what a truncated record should do, and the window refills or reports as
for any failed root match. A single-pattern `match` is the one-part case, so the common
configuration does not change shape. The level's driver for this is three kinds and a loop,
against the twenty-four kinds and six combinators of the interpreter it replaces.

**Where the repeat and the choice went.** In a format whose fields come in any order and may
be absent — a protobuf message — the fields are *templates*: the message's bytes are applied
to per-field templates in strict dispatch, each a tag pattern and a take by its own length,
and the template loop is the repeat and the choice. An Avro block is applied to a record
template until it is consumed. A take never needs to sit inside a pattern.

**What is held in reserve.** A pattern with holes filled from variables at run time, compiled
when the variables are read and cached by their values, would serve a format whose *shape*
depends on data — a separator declared in a header. The cache is simple (a small map per
template keyed by the values, bounded) and the hazard is written down: a compile per distinct
value, ruinous if the value changes per record. No named case needs it.

## 4. The configuration surface

A template's `match` gains a form beside `regex` and `delimiter`, and `progressive` retires:

```json
"match": {"pattern": {"sequence": [
    {"ref": "IP_ADDRESS", "label": "ip"},
    {"tag": " "},
    {"take_while": "[A-Za-z]+", "label": "name"},
    {"regex": "\\s+(\\d+)$"}
]}}
```

A node is one of `tag`, `take_while`, `take_until`, `take_through`, `take`, `any`, `regex`,
`ref`, `sequence`, `choice`, `optional`, `repeat` (with `min`, `max`, `greedy`), `peek`,
`not`, `count` (with the label it counts by); any node may carry `label`. A label is a capture
the body reaches by name, as a group is reached by number; a capture's `as` gains the binary
casts. `regex` alone stays the shorthand it is. A project may carry a `patterns` library of
named nodes, and the standard library's names are always in scope.

**The fixtures, grounded.** The prototype's repository holds two real binary files with fixed
expected outputs that it parsed with native libraries: `avro_users`, a genuine Avro object
container — magic, a metadata map of zigzag-varint-prefixed strings, a sixteen-byte sync
marker, a block with a record count and a byte size, records of a length-prefixed string, a
zigzag varint, a little-endian double and a byte boolean — and `protobuf_events`, three
length-delimited protobuf messages of tag-prefixed varints and strings, one omitting a field
so the output shows its default. Both are parseable with nothing but patterns, takes, casts and
template dispatch, and both are ported here as library-free parses with their outputs kept:
they are design 38's binary fixtures, and the `progressive` benchmark row becomes the Avro
container amplified to many blocks — a real binary workload where the current row is a
manufactured one. Parquet is not ported; its footer is Thrift and its columns are encoded,
which is a library's job and a separate decision if ever wanted. The five synthetic
progressive fixtures are all rewritten in the new form and kept (ruled 2026-09-16). The
protobuf fixture's compiled descriptor is kept beside it as documentation of the message
type; the engine never reads it. DS3 migration is untouched: DS3 has no steps.

**The oracle for the amplified row** (ruled 2026-09-16): the Avro Java library, test scope
only and never on the engine's classpath, writes the amplified container and produces its
expected output — as Saxon does for the XSLT catalogue — so the parser is checked by an
independent writer rather than by one we wrote ourselves.

## 5. Compilation

A match sequence compiles part by part: each `pattern` → the library's `Matcher` tree (a
direct mapping, node for node, references resolved against the standard library) →
`Lowering.lower` → the plan →
a `BytePattern`. Labels become groups and the compiler records the label-to-group table for
the body's references, as it records names for `(?<name>…)` today. The match arm in
`Level.match` runs the parts in order: a pattern is the regex arm, a `take` and a `seek` are
cursor arithmetic with a slice for the span; a one-part sequence is the regex arm alone. Captures of labelled binary runs carry
their cast; the value is made when the capture binds, as `as: integer` is made today.

What is deleted: `CompiledMatch.Progressive`, `CompiledStep` and its twenty-four records,
`CompiledSteps`, `StepRef`, `Steps` (the interpreter, 1654 + 350 bytes of the census's largest
methods), the `Result` record, the step-program checks in the compiler, and the `progressive`
JSON. What the census priced as the interpreter's per-match cost — the result per step, the
boxed integer per read, the list, the span copy — goes with it; a match produces groups as
slices of the span, as every regex match does since design 37 phase 3.

## 6. What the regex library gains, and no more

1. `Matcher.Peek(body)` and `Matcher.Not(body)`, lowering to `Hir.Look(body, behind=false,
   negated)` — the HIR node exists; the combinators are two records, two factory methods and
   two lowering arms, about twenty lines, all new. The identical-plan pin (`CombinatorTest`)
   extends to them since both have a regex spelling.
2. **The composition compile path takes an encoding** (found in phase 3, 2026-09-16). A
   composition compiled only in UTF-8: `BytePattern.compile(matcher, …)` fixed the encoding
   and the lowering fixed the UTF-8 byte form for every class and one-byte literal, so a
   binary tree could not run in raw mode. One overload on `BytePattern` and `MatcherLibrary`
   with an `Encoding`, and the lowering carrying a `ByteForm` it uses where it used the UTF-8
   one; the old overloads delegate as UTF-8. Additive, and pinned: a varint composition in raw
   form matches the bytes an anchored UTF-8 compile of the same tree refuses under D38.
3. **`Matchers.explode`** (phase 1a, 2026-09-16; ruled D53): the
   parser's intermediate form as a composition, for the UI's import. One public method and one
   internal class, reading nothing the module did not already have; the alternative was the
   engine reading `internal`.
4. Nothing else. No instruction, no tier change, no parser in the backtracker; the byte-strict
   ruling (D38) stands. A pattern that works today takes the same tier and runs the same
   instructions, and the module's own test corpus proves it on the first build.

## 7. What it measures, and what would make it a mistake

**Gate one is the fixtures**: five progressive fixtures byte-for-byte on output and message,
and `StepsTest`'s twenty cases re-expressed as compositions and passing under backtracking
semantics — each case that would *not* pass named in the D34 successor as a deliberate change.

**Gate two is the two rows.** `progressive_text` is the textual subset and should run on the
lower tiers at a fraction of the interpreter's cost; the expectation is a large gain and the
size is unknown. `progressive` becomes the Avro container amplified: raw-mode patterns for the
fixed fields, takes for the lengths, dispatch of each block to the record template. Its
history ends with the row it replaces and it starts a new one; the reading that matters is
against the retired row's last point on the same job — the interpreter over the synthetic
records — and against the Rust prototype's native-crate time for the same file, which the
prototype's design asked for and never recorded. Both rows interleaved against point 52, six
rounds, as design 34 was read.

**What would make it a mistake.**

- *A step program that means something different under backtracking.* Phase 1 reads every
  pin and every fixture for this before a line moves; the ones that differ are decided, not
  discovered.
- *Framing that grows back into an interpreter.* Two verbs and a loop is the whole of it. A
  third verb — a repeat by count, a conditional part — is the interpreter returning, and is a
  ruling; the template layer's dispatch is where repetition and choice live.
- *The UI's model diverging from the engine's.* The tree the UI edits must be the tree the
  configuration stores and the tree the compiler lowers, with no translation between: one
  vocabulary in three places.
- *Growing the regex library.* Two combinators and the encoding on the composition path is the
  budget; anything beyond is a sign the coverage table was wrong and is a ruling, not a commit.

### What phase 3 found — 2026-09-16

Three things the design did not say, each now pinned.

1. **A nested dispatch runs over the bytes as read, not their UTF-8 form.** A raw template's
   body handing a labelled slice to `apply-templates` handed the slice's UTF-8 transcoding —
   `0xD6` became `C3 96` — and the child's raw-mode patterns matched the wrong bytes. `Bytes`
   gained `readArray`/`readOffset`/`readLength` (a whole value's is its array from zero; a
   slice's is the slice) and `source()` over the read array, and `Body.apply` dispatches over
   those. The Avro fixture's `sync` marker and every block after the first depend on it.
2. **A labelled capture is never the whole-parent-content shortcut.** `BodyCompiler` treats a
   bare `{"capture": {"group": 0}}` as "the parent's content, no copy"; a `{"capture":
   {"label": …}}` compiles to a group number too, and the shortcut had to exclude it or a
   label whose group happened to be 0 read the whole match.
3. **The casts read raw bytes.** `BinaryCasts` first read a slice's `utf8Array()`, which for a
   raw slice transcodes; it reads `readArray`/`readOffset`/`readLength` now, and a byte above
   0x7F is one byte.

The two real files: `avro_users` parses the container as a state machine of templates — file
header, a metadata map of `entry` templates until `map_end`, then blocks, each dispatching its
records — and `protobuf_events` as a message whose length-prefixed body dispatches fields, each
a template assigning a declared scalar. Both pass byte-for-byte against the outputs the Rust
prototype's native crates produced, with no library.

### What phase 4 deleted — 2026-09-16

Twelve main files and 3,200 lines: `MatchStep` and its twenty-four records, `StepRef`,
`Predicate` and `Predicates`, `NumericType`, `Endianness`, `CombinatorPattern` and
`Project.patterns`, `StepCompiler`, `CompiledStep`, `CompiledSteps`, `Decoding`, `Steps` (the
interpreter), `CompiledMatch.Progressive`, the `progressive` JSON arm and the step reader and
writer, and with them the `avro`, `parquet` and `protobuf` match kinds that were only ever
refused by name, the `step` and `field` capture sources that served them, and
`ConfigException.notYet`, which nothing else raised.

The pins moved before the deletion: `StepsTest`'s nineteen cases and `StepCombinatorsTest`'s
twenty are `PatternTreeTest`'s twenty-one, on `{"pattern": …}` and `{"parts": …}` — atoms,
casts in both byte orders, varint and zigzag, position, a take by label, seeks forward and
absolute, a short take failing the match, choice, optional and repeat, peek and not, a label
inside a failed alternative not participating, labels numbered as parentheses, and D52's one
widening (a repeat gives back) pinned to its new outcome. The `EngineBehaviourTest` and
`EncodedInputTest` pins that drove the interpreter drive the tree: a reference to nothing is
refused by the library's own message, a library entry used twice compiles, a regex node's
flags are honoured, a template encoding override reaches a regex node and a library reference,
and a transcode-family override is refused through either.

**Not built: the project's own `patterns` library** (§2, §4). The old one held step
sequences and went with the interpreter; a library of named tree nodes was never needed by a
fixture or either real file, so `ref` resolves against the standard library alone. It is a
`MatcherLibrary` layered on the standard one and a map in `Project` when the UI's "defined
once" wants it — an addition, not a change to anything here. The phase-4 audit also found the
`decode` transform reading its input's UTF-8 form, the same hole phase 3 closed for dispatch
and the casts; it reads the bytes as read now, and a failed decode is absence rather than a
value holding null. `Codecs`' encode side, which only the `Encode` step ever called, went too.

`parquet_cities` keeps its input and golden output for the day Parquet decoding exists, but
its configuration names a retired match kind, so the round-trip test no longer reads `SKIPPED`
fixtures — a skipped fixture was already defined as never run. The benchmark rows keep their
names: `progressive` is the match sequence (a varint cast and a take), `progressive_text` the
pattern tree over text, so the ledger reads across the retirement.

### Phase 5, the first reading — 2026-09-16, the prototype's number

**The Avro row exists.** `avro_users` in `EngineBenchmark`: the container written by the Avro
Java library (`AvroContainers`, test scope; 12,762 users in blocks of 100, 263,371 bytes —
sized in two passes to sit just past the 256 KiB target as the repeated rows do, so an op is
the same volume on every row — from a fixed seed), parsed whole-buffer by the fixture's
configuration. `AvroAmplifiedTest`
holds the engine to the library's own reading of a 2,500-user container of the same shape,
byte for byte, on every test run — the parity gate §7 asked for. The row was *added* beside
`progressive` rather than replacing it, because point 53 reads the retirement on
`progressive`'s old job; whether `progressive` then retires is a ruling for after tonight's
run.

**The prototype's native-crate time, obtained — and decomposed.** The Rust prototype at
`/mnt/shared/ds-rs` builds offline with its `binary-formats` feature. Two timing examples
(`engine/examples/avro_time.rs` and `avro_crate_time.rs`, left untracked there) run over the
same 263,371-byte file: the first is the prototype's whole path through its public `parse` —
the `apache-avro` crate decoding into its generic `Value` tree, then the prototype's template
engine writing the XML — checked against the library's expected bytes (784,496, identical);
the second is the crate alone, every field touched and nothing written. On the engine's side,
the same configuration with the record template's body emptied is the parse without the
output: matching, casts, four captures per record and the dispatch, nothing written. Five
hundred parses each after warm-up:

| the same file | mean |
|---|---|
| `apache-avro` crate, decode only | 1.5 ms |
| Shapeshifter, parse only — the match sequence, casts, captures, dispatch | 2.0 ms |
| Shapeshifter, parse and 784 KB of XML written (the `avro_users` row; JMH 237.6 ± 4.4 ops/s) | 4.2 ms |
| ds-rs prototype, crate and its template engine and the same XML | 13.3 ms |

A daytime reading on the shared box, so the ratios are the finding and the digits are not.
Read carefully, because the first reading of this was wrong: the engine is *not* faster than
the native crate. The crate's schema-driven decode is about a third quicker than the
library-free parse — 1.5 against 2.0 ms — which is the honest cost of parsing a binary format
as templates over a regex engine rather than with a decoder that knows the schema. What the
engine is three times faster than is the *prototype*, and the prototype's twelve remaining
milliseconds are its own template engine, not the crate. So the number the prototype's design
asked for is: a library-free parse within a third of the native decoder, on the JVM, with the
template layer's cost on top of it a matter of what the body writes. That settles the
question §4 raised — a real binary format parsed as templates is more than a demonstration —
without pretending it beats a decoder that has the schema. Tonight's full run gives the row
its first ledger point.

## 8. Phases

1. **Read.** The five synthetic fixtures and the twenty `StepsTest` cases against backtracking
   semantics; the D34 successor drafted from what differs; the two real files read byte by
   byte against their formats so the match sequences that parse them are written down before
   any code. No code.
1a. **The explode and its oracle.** The mapping from the intermediate form to the tree, and
   the test that every fixture regex's explode has the plan of the regex itself. The printer
   follows when the UI needs it. Done 2026-09-16 (§3a's record); the printer still follows.
2. **The library's two combinators**, in the regex module with their tests and the
   identical-plan pins extended. The engine does not change.
3. **The `pattern` surface, the match sequence and the binary casts**: compilation to a
   `Matcher` per part, the label table, `take` and `seek` in the level; `progressive` still
   accepted beside it. `avro_users` and `protobuf_events` ported and passing; the synthetic
   fixtures rewritten or retired. Done 2026-09-16 (`179197cf7b`, `ffff67e6b7`).
4. **The interpreter deleted** with the `progressive` form; the census's two largest methods
   gone. Done 2026-09-16.
5. **Measured**: gate two, the `progressive` row re-founded on the Avro container, and the
   design's record. In progress: the Avro row and the prototype's number 2026-09-16 (above);
   the evening reading of points 53 and 54 owed.

**Sequencing against design 37** (ruled 2026-09-16): point 52's evening reading first, since it
says whether dispatcher splits pay on this engine; then phases 1 and 2 here, which touch
nothing the splits touch; the three remaining splits (`resolveValue`, `Conditions.evaluate`,
the body's categories) go between this design's later phases as the box allows; the
`Steps.step` split is struck, since phase 4 deletes the method.
