# Value computation: types, casting and the function library

Status: **decided 2026-08-25, in full — all eight decisions ruled (§16), three reshaping the
draft: comparisons become one strict typed vocabulary with explicit `as`-casts — ruled twice
the same day, first from a parallel `compare` condition to coercion, then from coercion to
strict (§8) — a date is a first-class `Instant` variant rather than epoch millis in an `Int`
(§9), and run-time parameters are deferred to D10 after the draft's premise was checked
against the pipeline and found wrong (§9.3).** Written 2026-08-25 against
[14-xslt-coverage-matrix.md](14-xslt-coverage-matrix.md)'s second gap family — *value
computation*: arithmetic, `string-length` as a value, `format-number`, `format-dateTime`,
and the comparison semantics under all of them. Implementation tracked as E24. The rulings at
the end are the user's.

[16-sequences-and-aggregation.md](16-sequences-and-aggregation.md) depends on this document
for two things: how `sum` promotes, and how `sort`, `min` and `max` order. This one lands
first.

Scope note, as in 16: the target is what a log-transformation and data-extraction engine
needs, not XSLT parity as an end. That is why `format-date`/`parse-date` are in, why the
syslog-with-no-year problem gets a section of its own (§9.2), and why `xsl:evaluate` and
`current-dateTime()` stay refused.

## 1. The finding: the type system exists and is thrown away

`exec/TypedValue.java` is a sealed interface with four variants — `Bytes`, `Int`, `Real`,
`Bool` — and complete conversions between them (`asBytes`, `asString`, `asNumber`). It is a
serviceable little value model, and it was ported for a real reason: binary match steps decode
integers, and rendering them to text so the other end can parse them back is work nobody
asked for.

Then the rest of the engine erases it:

- Every transform is `Function<List<String>, String>` (`CompiledOp.Transform`), so
  `Transforms` receives decoded text and returns text.
- `Executor.emit` binds every result as `TypedValue.of(value.getBytes(UTF_8))` — a `Bytes`,
  whatever it was.
- `Conditions.evaluate` compares as text for `Equals`, and re-parses a `Double` out of text
  for `GreaterThan`/`LessThan`.

So a type survives exactly until the first function touches it. `number()` exists and returns
a *string* that looks like a number. The matrix's "**partial** — arithmetic beyond `number()`
is a gap (no expression language, by design)" is accurate about the surface and understates
the cause: there is no arithmetic because there is nothing left to do arithmetic *on*.

This design does not add a type system. It stops discarding the one that is already there,
and then adds the functions that become possible.

## 2. The rule the codebase already has

Two sentences already written in the engine settle most of the hard questions, and this design
adopts them rather than inventing anything:

> **Empty is absent.** A part that resolves to nothing writes nothing, and an expression whose
> parts all resolve to nothing has no value at all rather than an empty one. — `exec/Refs.java`

> A numeric comparison against something that is not a number is **false, not an exception** …
> which is what a configuration written against messy data needs. — `exec/Conditions.java`

From which: **every cast is total and never throws, and its failure value is *absent*.** An
`add` whose input is `n/a` produces no value; the instruction that would have written it
writes nothing; a condition over it is false. That is already how a missing field behaves, and
log data supplies missing and malformed fields in roughly equal measure.

The cost is silence, and §10 is about buying some of it back.

## 3. The value model

**Four variants become five.** Two candidates were considered; one is refused and one is
adopted:

- **A sequence variant — refused.** [16 §2](16-sequences-and-aggregation.md) makes the store
  the sequence type. Adding `Seq` here would give the engine two ways to hold many values.
- **A date variant — adopted (ruled 2026-08-25; the draft proposed epoch millis in an `Int`
  and the ruling overrode it).** `Instant(long epochSecond, int nano, Integer offsetSeconds)`.
  Nanosecond precision is the reason — OTEL traces and kernel logs carry it, and a millisecond
  model rounds it away unrecoverably. The offset is carried but **inert in comparison and
  arithmetic**: an instant is a point on the timeline, `10:00+01:00` equals `09:00Z`, and
  ordering ignores the offset entirely — XPath's rule for zoned dateTimes, and the only one
  under which sorting by date is transitive. The offset's sole job is formatting provenance:
  `format-date` with no zone given renders in the instant's own offset (§9.1). `null` offset
  means none was parsed and none is claimed.

### 3.1 The casting table

The single source for every conversion in the engine. Read it as: *what this value is, when a
function or comparison asks for that kind*.

| | → string | → number (`Real`) | → integer (`Int`) | → boolean |
|---|---|---|---|---|
| **`Bytes`** | decode UTF-8 | parse trimmed; **absent** if not a number | parse trimmed; **absent** if not integral | lexical, trimmed: `true`/`1` → true, `false`/`0` → false, else **absent** |
| **`Int`** | decimal | the value | the value | `!= 0` |
| **`Real`** | whole numbers without `.0` (existing `TypedValue.format`) | the value | **absent** unless integral | `!= 0.0` |
| **`Bool`** | `true` / `false` | `1.0` / `0.0` | `1` / `0` | the value |
| **`Instant`** | ISO-8601, in the carried offset else `Z`, trailing zero nanos trimmed | epoch **milliseconds** (documented lossy: nanos truncate) | epoch milliseconds, same loss | **absent** |
| **absent** | absent | absent | absent | false |

The `Instant` → number cast being *milliseconds, lossy* is deliberate: it is the escape hatch
that keeps date arithmetic ordinary (§9.1) without forcing every numeric site to learn about
nanoseconds. Where nanosecond precision matters — comparison, sorting, parse→format round
trips — the value stays an `Instant` and no cast happens. The other direction, number →
`Instant`, does not exist in the table; it is spelt `parse-date` with `epoch-millis` or
`epoch-seconds`, so a unit is always named.

Three points that are choices rather than consequences:

- **`Real` → integer is absent, not truncated.** Silent truncation is how a total of `£9.99`
  becomes `£9`. An author who wants a whole number says which one: `round`, `floor` or
  `ceiling` (§5).
- **Boolean of a string is the lexical cast** (`true`/`1`/`false`/`0`, anything else absent) —
  XPath's *constructor* rule, not its effective-boolean-value rule (non-emptiness, under which
  the string `"false"` is true). The distinction matters because §8's explicit `as: "boolean"`
  is this column's only consumer, and a cast is what `as` says — so `eq` of a false flag
  against the literal `"false"` read `as: "boolean"` is true, as a reader expects (uncast,
  the operands are cross-kind and the comparison is simply false). EBV is deliberately not
  modelled at all: the engine's conditions are explicit predicates and `Exists` covers
  presence, so non-emptiness has no call site — and with it goes the `"false"`-is-true trap
  the draft had documented. *(Corrected 2026-08-25 on review: the draft's non-emptiness row
  conflated the two rules and would have diverged from the specification it cited.)*
- **A multi-part reference is a string by construction.** `Refs.resolve` concatenates parts
  into bytes, so `$a$b` is text even when `$a` and `$b` are both `Int`. Only a *single-part*
  reference to a single capture can preserve a non-`Bytes` type. This wants saying out loud
  because it is the one place a value's type depends on how its reference was written.

### 3.2 The mechanical change

Small, and almost all of it in one file:

- `CompiledOp.Transform`'s function becomes `Function<List<TypedValue>, TypedValue>`.
- `Transforms`' methods take and return `TypedValue`. The existing string functions cast their
  input to string at the top and wrap their result — identical behaviour, one indirection.
- `Executor.emit` binds the `TypedValue` it was given instead of re-wrapping bytes.
- `Refs` gains `resolveValue(...) → TypedValue`, returning the typed value for a single-part
  capture reference and `Bytes` otherwise (§3.1). `resolve` and `resolveText` keep their
  signatures and become thin over it, so nothing on the write path changes shape.

No existing golden should move. That is the phase 1 acceptance criterion, and if one does, the
diff is the finding.

## 4. Instructions, not expressions

The crux, and the reason this document is longer than the function list would suggest.

An expression language is the obvious answer to `@price * @qty` and it is refused, for the
reason D35 already gives: the model is declarative, and a configuration's meaning should be
readable without executing a parser the engine also has to own, optimise and error-report
through. Every existing computation in this engine is an instruction with a `select` list and
a `name`, and that shape is why a configuration can be rendered in a node editor at all.

So arithmetic is instructions:

```json
{ "multiply": { "select": [ { "parts": [ { "capture": { "var_id": "price", "group": 0 } } ] },
                            { "parts": [ { "capture": { "var_id": "qty",   "group": 0 } } ] } ],
                "name": "line_total" } }
```

Verbose beside `$price * $qty`. Three things make the trade worth taking:

1. It is the shape every other computation already has, so it needs no new authoring concept,
   no new error vocabulary and no new rendering.
2. It stays analysable: the compiler can see every input of every computation without a
   dataflow pass over an expression tree.
3. **The order is safe.** An expression layer, if authoring pain ever proves real rather than
   suspected, can be added later as a *front end that compiles down to these instructions* —
   the runtime would not move. The reverse order is the one that cannot be undone: ship
   expressions first and the instruction set becomes a compilation target nobody authors, i.e.
   the third layer D35 exists to refuse.

(Decision 1.)

## 5. Arithmetic

| Instruction | Inputs | Result |
|---|---|---|
| `add`, `multiply` | one or more | fold over all inputs |
| `subtract`, `divide`, `mod` | exactly two | `a op b` |
| `round`, `floor`, `ceiling`, `abs` | exactly one | as named |

- **Type.** `Int` if every input is `Int` and the operation is exact; `Real` otherwise.
  `divide` is `Real` unless the division is exact. `mod` follows Java's `%` — the sign follows
  the dividend, which is what XPath's `mod` does too.
- **Any absent or non-numeric input makes the whole result absent** (§2). Not zero, and not a
  partial fold over the inputs that happened to parse.
- **`divide` by zero is absent**, not an infinity and not an exception — the same treatment
  `Conditions` gives a comparison it cannot make. `TypedValue.format` would otherwise render
  `Infinity` into someone's XML.
- **`round` is half-up on ties**, matching XPath's `round()` rather than Java's
  `Math.round` on negatives. Both round `2.5` to `3`; they disagree about `-2.5`, where XPath
  says `-2` and this follows XPath.

Overflow and precision are §11.

## 6. String functions

Everything in the matrix's function-library section that is currently a gap or expressible-
only, and nothing else:

| Instruction | XSLT | Notes |
|---|---|---|
| `string-length` | `string-length()` | **`Int`**, counted in code points, matching `substring`'s existing code-point counting. The matrix's "length-as-value stays a gap" row. |
| `substring-before`, `substring-after` | same | absent when the marker is not found — *not* the empty string, so a condition can tell the two apart |
| `starts-with`, `ends-with`, `contains` | same | as **values** (`Bool`). The conditions of the same name stay; these are for binding and for `if` over a computed flag |
| `format-number` | `format-number()` | picture string via `DecimalFormat` under `Locale.ROOT` |
| `concat` | `concat()` | **not added** — a multi-part reference already is `concat`, and a second spelling would be dead vocabulary |
| `string-join`, `translate`, `upper-case`, `lower-case`, `normalize-space`, `trim`, `replace`, `tokenize`, `number`, `substring` | — | exist; typed under §3.2, otherwise unchanged |

**`format-number` divergences to expect.** `DecimalFormat` under `Locale.ROOT` matches XSLT's
default decimal format for the ordinary pictures (`#,##0.00`, `0.###`) and diverges on the
edges: per-mille, explicit `+` patterns, and infinity/NaN rendering. The proving case (§14)
should include the ordinary shapes and one edge, so the boundary is recorded rather than
discovered.

**`tokenize` should bind a sequence.** Today it returns its pieces joined with `\n`
(`Transforms.tokenize`), which is a sequence pretending to be a string because there was
nowhere to put a sequence. [16](16-sequences-and-aggregation.md) gives it somewhere: bound to
a `name`, `tokenize` fills a dense sequence; written straight to output it keeps the joined
rendering. **No fixture or catalogue case uses `tokenize`** — checked — so the change costs no
golden. (Decision 4.)

## 7. The 0-based / 1-based question, reopened

E21 ruled on 2026-08-21 that `substring` **stays 0-based** where XSLT is 1-based: faithful to
the ported library and to existing configurations, with the trap documented in
`OutputNode.Substring`'s javadoc and in the matrix.

That ruling was made under a stated goal of *fidelity to the port*. The goal has since been
restated as *what a transformation engine needs to usefully do*, and the surface is about to
grow `string-length`, `substring-before` and `substring-after` — after which an author will be
mixing a 0-based `substring` with a 1-based mental model borrowed from every XSLT they have
read. Reopening is legitimate; quietly changing it is not.

Three options:

1. **Keep 0-based everywhere.** Cheapest, and the existing 32 fixture uses stay untouched. The
   trap persists and now has more neighbours to be inconsistent with.
2. **Align to 1-based.** Correct against the specification people will be porting from, and it
   silently changes the output of every existing configuration that uses `substring` — the
   worst possible failure mode, because the result is still a string of the right shape.
3. **Version-gate it**, exactly as dispatch already is: `Dispatch.effective` reads
   `project.version() >= 4` to decide strict versus lax, so "the version decides the
   behaviour" is established precedent with an implementation to copy. Configurations at
   version ≤ 4 keep 0-based; version 5 and later are 1-based; the migration path is a version
   bump the author makes deliberately, and the compiler can warn on a `substring` in a
   version-4 configuration that a bump would change.

**Ruled 2026-08-25: option 3 — version 5 is 1-based; version ≤ 4 stays 0-based; the compiler
warns on a version-4 `substring` that a bump would change.** It is the only option that is
both correct for new authors and safe for the existing corpus, and the mechanism already
exists. The decision also opens version 5 as the home for anything else this family aligns —
taken once, here.

The ruling came with a principle worth recording, because it shapes the vocabulary beyond
this one function: **stay 1-based for an analyst-facing DSL, then work to make raw indices
rare** — most index bugs come from indexing at all, so the investment goes into the
index-avoiding vocabulary (`substring-before`/`substring-after`, `tokenize`-as-sequence,
16's iteration) rather than into index machinery. After this tranche, `substring` is nearly
the only place an integer offset survives, which is the "rare" the principle asks for. Two
stronger forms were considered and declined for this engine: opaque string indices
(Swift-style) pay off only where indices are values that travel through a program, and here
an index is a literal in configuration JSON that never flows; first-class ranges likewise.
Unicode is handled at the level that matters — `substring` counts code points, not UTF-16
units, which is XPath's own axis and what Saxon byte-parity requires (graphemes would be more
correct and would break parity).

## 8. Comparison and ordering — one spine

[16](16-sequences-and-aggregation.md) needs ordering for `sort`, `min` and `max`; conditions
need it for `greater-than` and `less-than`; they should not answer differently.

**Ruled 2026-08-25, in two steps that converged the same day:** the first ruling replaced
the draft's parallel typed `compare` condition with one vocabulary of typed semantics; the
second replaced that vocabulary's implicit coercion with **strict comparison plus explicit
operand casts** — since casting is available, the engine never guesses what bytes mean. One
vocabulary, no second condition set, and no untyped-atomic rule.

**The strict rule:**

- **Same kind compares natively.** Two `Bytes` as strings — code-point order,
  `String.compareTo`'s order; not a collator, because a locale-dependent order would make
  output depend on where it ran, the reason `lowerCase` already pins `Locale.ROOT`. Two
  `Instant`s on the timeline (§3). Two `Bool`s with false < true. `Int` against `Real`
  numerically — **promotion within the one numeric kind, not coercion**, without which an
  arithmetic result could never meet an integer literal.
- **A cross-kind comparison is false.** No implicit reading of bytes as numbers, booleans or
  dates. False rather than an error, per the engine's existing rule for a comparison that
  cannot be made — and consistent with absent: false in a condition and last in an ordering
  are the same statement, *this value did not participate*.
- **The cast is explicit, on the operand:** `as`: `string` | `number` | `boolean` | `date`,
  applying §3.1's casts — absent on failure, false in comparison. It lives on the operand
  rather than only as a bind-first instruction because **guards need it**: a template guard
  is a condition with no body before it, so there is nowhere to run a `number` instruction
  first. This is not an expression language — it is a typed read, one keyword, still
  declarative. `as: "date"` is the ISO-8601 cast; anything non-ISO goes through `parse-date`,
  which has the pattern.

**Naming (amended 2026-08-25, on the user's ruling):** the comparison conditions are spelt
**`eq`, `ne`, `lt`, `le`, `gt`, `ge`** — XPath 2.0's own value-comparison operators, the
vocabulary this engine's authors already read. Each takes `left` and `right`, and an operand
is a reference or a literal, with **the literal's JSON type as its declared type**: a JSON
string is untyped (`Bytes`), a JSON number is `Int` or `Real`, a JSON boolean is `Bool` —
and either operand may carry an `as`. Six uniform conditions therefore subsume all three
existing shapes: `equals`/`not-equals`/`ref-equals` are `eq`/`ne` with **`as: "string"` on
both operands**; `greater-than`/`less-than` (ref against numeric literal) are `gt`/`lt` with
**`as: "number"` on the left**. In each case the alias carries the cast the old condition's
semantics always implied, preserved *visibly* in the mapped form rather than by an invisible
rule. The old spellings **stay readable for ever as aliases** — the format's own precedent
is `Store`/`capture` in `ProjectJson.readRefPart`. The writer emits the new spellings. The
non-comparison conditions — `matches`, `contains`, `starts-with`, `exists`, the boolean
connectives — keep their names; they are predicates, not comparisons, and have no operator
to borrow.

**Why the equality aliases carry `as: "string"` rather than no cast — a phase 1 audit
finding (2026-08-25), and the corpus-safety argument corrected.** The draft claimed every
value is `Bytes` today, so uncast `eq` would preserve legacy `equals`. False: the engine's
own counters are already typed — `__match_count`/`__match_idx` bind as `Int`, binary-step
captures bind as `Int`/`Real` — and today's `Conditions.evaluate` compares their *string
forms* via `resolveText` (an `Int` 42 renders `"42"` and matches the literal). An uncast
`eq` would read `Int` against a string literal as cross-kind, always false — breaking,
among other things, the documented `equals`-on-`__match_count` idiom that `adjacent_groups`
proves. `as: "string"` is the total cast, so the alias compares string forms for every type,
which is exactly what the legacy conditions do. `greater-than`/`less-than` map with
`as: "number"`, the numeric parse they already perform, false on an unparseable value as
today. Nothing shifts silently anywhere, which is the point of strictness. The proving case
(§14's `comparison`) pins both halves — including a legacy `equals` against a typed counter
surviving the mapping.

**The failure mode, and its lint.** Strictness moves the foot-gun: coercion's failure was a
silent wrong guess; strict's is a forgotten cast reading always-false — `gt` of a captured
field against a numeric literal with no `as`. That one is **statically detectable**: a typed
literal compared against an uncast reference earns a compile-time warning ("captures are
text; add `as: number` if a numeric comparison is meant"), in D36's warning tier — warnings
until a lint can prove confusion rather than suspect it. Coercion's failure mode was not
detectable at all, which is the trade the ruling takes.

**Ordering uses the same `as`, and `data_type` dissolves.** A sort key is
`{by, as, order}`; `min` and `max` take `as` the same way. An ordering needs a *total* order
over its whole column, which pairwise cross-kind-false cannot supply — so an **uncast key
orders by the values' string forms** (§3.1's string cast, the one total cast), which is the
old `string` default made explicit, and `as: number` or `as: date` is the same one keyword
as everywhere else. The conditions-coerce-while-sorts-declare split the coercion model
needed is gone: one rule for every typed read in the engine. Under any `as`, **a value
that does not cast is absent, and absent sorts last in ascending order and last in
descending order too** — the unparseable entries end up together at the bottom either way,
rather than migrating to the top when the order flips, which is what a reader would read as
data.

## 9. Dates

The matrix calls this "the likeliest **first real gap** a production-shaped case hits", and it
is right: Stroom's own configurations lean on `stroom:format-date` harder than on anything
else in this family.

The shape was already ruled on 2026-08-21: **not a verbatim port** of `stroom:format-date`,
which conflates parsing and formatting in one call, but a composed pair. This design executes
that ruling.

### 9.1 The pair

```json
{ "parse-date":  { "select": [ … ], "pattern": "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                   "timezone": "UTC", "name": "when" } }
{ "format-date": { "select": [ { "parts": [ { "capture": { "var_id": "when" } } ] } ],
                   "pattern": "iso", "timezone": "UTC" } }
```

- `parse-date` → **`Instant`** (§3): epoch second, nanosecond, and the parsed offset when the
  pattern parses one. Absent if the input does not match the pattern. Nanosecond-stamped
  sources — OTEL traces, kernel logs — keep their precision, which is the reason the ruling
  chose a variant over the draft's epoch-millis `Int`.
- `format-date` → string. Its input is an `Instant`, or anything §3.1 can cast to one — a
  `Bytes` holding ISO-8601 passes straight through parse.
- `pattern` is a `DateTimeFormatter` pattern under `Locale.ROOT`, plus three reserved names:
  `iso` (ISO-8601 instant), `epoch-millis`, `epoch-seconds`.
- `timezone` is an IANA zone id. On `parse-date` it supplies the zone only when the pattern
  parses none, defaulting to `UTC`; a parsed offset always wins and is carried. On
  `format-date` the precedence is: the instruction's `timezone` if given, else the instant's
  own carried offset, else `UTC` — so a value round-trips through its original offset unless
  the author says otherwise, and the offset never affects *which* instant is rendered, only
  its spelling.
- **Comparison and arithmetic are on the timeline, always.** The carried offset is formatting
  provenance and nothing else (§3): two parses of the same moment through different offsets
  are equal, sort together, and subtract to zero.

Date arithmetic works through the §3.1 cast: `Instant` → number is **epoch milliseconds,
documented lossy**, so `subtract` of two dates is a millisecond duration and adding a number
of milliseconds to a date's cast is ordinary arithmetic. Nanoseconds survive inside the
`Instant` — parse→compare→sort→format never casts — and truncate only when the author reaches
for arithmetic, which is the explicit act that names the unit. No duration type, no calendar
arithmetic — "one month later" is not expressible, and that is the right thing to lack in a
log pipeline. If a case ever needs nanosecond *arithmetic* rather than nanosecond fidelity,
this paragraph is where the millisecond choice was made.

### 9.2 The year that is not there

The single most common real-world date in log transformation is the syslog timestamp, which
has no year:

```
Aug 25 11:06:41 host sshd[1234]: ...
```

Stroom's `stroom:format-date` handles this by reaching for the stream's receipt time and
choosing the nearest year. This engine cannot do that and should not learn to: it has no
clock, no stream metadata, and a deliberate determinism property that golden parity depends
on — `current-dateTime()` is refused in the matrix for exactly this reason.

The honest answer is that the reference instant is **data, and must be supplied by the
configuration**:

```json
{ "parse-date": { "select": [ … ], "pattern": "MMM ppd HH:mm:ss",
                  "reference": { "parts": [ { "capture": { "var_id": "$received" } } ] },
                  "name": "when" } }
```

When `pattern` yields no year, `reference` (itself a date) supplies the year that puts the
result **nearest** to it — the same rule Stroom applies, with the input made explicit. Without
a `reference`, a yearless pattern is a **compile-time error** naming the instruction, rather
than a silent 1970.

### 9.3 Run-time parameters — deferred, and the premise checked

The draft proposed `run(compiled, input, sink, Map<String, String> params)` — stylesheet-level
`xsl:param` — on the assumption that the pipeline would need it on day one. **The ruling sent
that assumption back for validation, and it was wrong.** Checked 2026-08-25 against the
pipeline source: `XsltFilter` never calls `setParameter` on the Saxon transformer — no
parameter-injection path exists; every `xsl:param` in the repository's stylesheets is
template-local or `xsl:function`-local (the one stylesheet-level param, TEST_TRACES'
`indent-spaces`, has a static default and is never fed); and external context — receipt time,
feed attributes, reference lookups — arrives exclusively through `stroom:` extension
functions, i.e. through the integration layer, not through declared parameters.

**Ruled: deferred to D10.** Stroom's own architecture puts the context seam in the
integration layer, and the adapter should follow it — however D10 chooses to spell "the
embedder supplies context", that is where a receipt time enters, not a parameter list on the
core run API. Until then, §9.2's `reference` is data: a captured field, which real feeds
carrying a receipt-time header genuinely have. The compile-time error on a yearless pattern
with no reference stands either way, so nothing ships half-working in the interim.

## 10. Absent, and buying back some silence

§2's rule is right and it is quiet: a configuration with a typo'd variable name computes
nothing, writes nothing, and produces output that is well-formed and wrong. Log data supplies
enough genuine absence that a loud run-time complaint per occurrence would be unusable —
which is why the rule exists — but "silent by default forever" is how the four wrong goldens in
[08-fixture-audit.md](08-fixture-audit.md) survived being frozen.

Two mechanisms, both cheap, neither on the hot path:

1. **Compile-time**, where most typos actually are: a reference to a name that no template
   captures, no `variable` binds, no `sequence` declares and no parameter declares is a
   **compile-time error** naming the reference. The engine already collects every capture name
   before matching starts (`Executor.run` registers them), so the name set is known. This is
   the one that catches the typo, and it costs nothing at run time.
2. **Run-time, opt-in**: a `strict_values` flag on `SourceConfig`, default off, turning a
   non-numeric input to an arithmetic or aggregate instruction into a `WARNING` naming the
   instruction and the value. Off by default because messy data is the normal case; available
   because "why is this element empty" is otherwise a debugging session with no evidence in it.

(Decision 6.)

## 11. Overflow and precision

- `Int` is a `long`. `add`, `subtract` and `multiply` over `Int`s use `Math.addExact` and
  friends and, **on overflow, promote the result to `Real`** rather than wrapping. Wrapping
  produces a plausible wrong number; promotion produces an approximate right one and says so
  by its type. Both are worse than nothing at all, and this picks the one whose failure is
  visible in the value.
- `sum` over a sequence accumulates as `long` while every entry is `Int` and no step
  overflows; it switches to `double` at the first `Real` or the first overflow, and does not
  switch back. So a column of integers sums exactly, which is the case people check by hand.
- `avg` is always `Real`.
- `Real` rendering keeps `TypedValue.format`'s existing behaviour — whole numbers without a
  trailing `.0`, which is the ported Rust rendering. **This diverges from XSLT's `xs:double`
  serialization** (`1.0E10`, `NaN`, `-0`), and a byte-parity case against Saxon will find it.
  The recommendation is to keep the existing rendering as the default — it is what existing
  configurations produce — and let `format-number` be the answer when a specific rendering is
  wanted. If a parity case makes that untenable, this is the paragraph to revisit.

## 12. Encoding

Unchanged by all of the above, and worth one line so it stays that way. Values are UTF-8
internally (E3 split conversion by provenance: only the current match's bytes convert; stored
values were normalised at capture). Numbers render as ASCII, which is safe in every supported
encoding — `TypedValue.asBytes` already relies on this. A typed value model changes nothing
here because the conversion boundary is where it was.

## 13. Performance — predictions, and the measurement protocol

Predictions first, so tonight's numbers judge them rather than inform them (D21's discipline:
a hypothesis is stated before the run that tests it). Per mechanism:

- **Configs that use none of this measure unchanged.** The new instructions are `CompiledOp`
  variants in a `switch` that is already a jump table; nothing on the existing path moves.
  This is the headline guarantee, it is what the baseline exists to verify, and a regression
  here is a defect, not a trade-off.
- **Phase 1 (typed transforms): neutral to slightly positive.** The `Function` signature
  change is invisible to dispatch. The bind path *saves* work: `emit` currently re-encodes
  every result (`String.getBytes`) before storing; a typed result binds directly. String
  functions still decode at the top — unchanged work, one indirection. The one risk is
  accidental allocation in `resolveValue` on the hot single-part path; the implementation
  note is that stores already hold `TypedValue`, so the common case is a field read, not a
  wrap.
- **String comparison can drop its decodes — a real win available on the hottest path.**
  Today `Equals` resolves both sides to `String` before comparing. Two UTF-8 properties make
  that unnecessary under §8: UTF-8 is injective, so byte equality *is* code-point string
  equality; and UTF-8's lexicographic byte order *is* code-point order. So `eq`/`lt` over two
  `Bytes` can run entirely on bytes — no decode, no `String` allocation, on the guard path
  that runs per dispatch attempt. Per [10-engine-compilation.md](10-engine-compilation.md)'s
  rule this is implemented straightforwardly first and optimised when the numbers ask; the
  prediction is that this is where the numbers will ask first.
- **Arithmetic: trivial beside matching.** `Math.addExact` and friends are JIT intrinsics;
  the fold is a loop over already-resolved values.
- **Dates: the one genuinely heavy addition.** `DateTimeFormatter.parse` costs on the order
  of a microsecond — per-record `parse-date` will dominate the body cost of date-heavy
  configurations, and this is intrinsic to the job, not to the design (Saxon pays the same
  or more in `format-dateTime`). Two mitigations are built in from the start, not retrofits:
  the formatter is **compiled once** into the `CompiledOp` closure (never per value), and the
  reserved `iso`/`epoch-millis`/`epoch-seconds` names bypass the formatter entirely. A
  hand-rolled fixed-width ISO fast path is the known next lever if the `dates` case's numbers
  ask for it. Expect the `dates` row to show the narrowest Saxon multiple in the catalogue;
  record it, do not gate on it.
- **Legacy conditions: identical work by construction.** An aliased `greater-than` performs
  the same per-evaluation numeric parse it performs today.
- **Memory: noise.** `Instant` is a three-field record; whether the absent offset is a
  sentinel `int` or a boxed `Integer` is an implementation detail the allocation profile can
  decide.

**The protocol.** The shared box's rules apply: full-suite runs in the evening, targeted
one-minute combos by day, check for other sessions' JMH runs before starting, and validate
drift on untouched rows before reading any moved one.

1. **Tonight, before any phase lands: the baseline.** Full engine benchmark suite plus
   `CaseCatalogueBenchmark` at both sizes, at the pre-implementation commit, recorded to
   `design/benchmarks/` by date and commit. Every later comparison is against this run.
2. **After phases 1 and 3** (the two that touch existing paths): same suite, same box,
   evening slot, compared with `render-benchmark.py`'s error-bar rule. The gate: every row
   not exercising a new feature is **indistinguishable** from baseline. A distinguishable
   regression stops the phase from closing.
3. **After phase 4**: the `dates` rows join the catalogue benchmark — new rows, recorded
   against Saxon, no before to compare.
4. **Phase 7 closes with the full A/B** against the phase-0 baseline, and the write-up goes
   in this section's follow-on the way every other measured change is recorded.

## 14. Proving cases

| Case | Proves | Status |
|---|---|---|
| `string_functions` | extended with `string-length`, `substring-before`/`after`, `format-number` including one picture edge | exists ✓ — extend |
| `value_types` | casting table end to end: numeric strings, non-numeric input going absent, the boolean lexical cast through `as: "boolean"` (`"1"` true, `"yes"` absent, a false flag equalling the literal `"false"`), `Real`→integer refusing to truncate | new |
| `arithmetic` | `add`/`subtract`/`multiply`/`divide`/`mod`, `round`/`floor`/`ceiling`/`abs`, divide-by-zero absent, overflow promotion | new |
| `dates` | `parse-date`/`format-date` round trip preserving nanoseconds and the original offset, two offsets of one instant comparing equal, a real syslog line with `reference` supplying the year from a captured field, one duration by subtraction | new |
| `comparison` | §8's strict rule: two `Bytes` still comparing as strings (the corpus-safety half), cross-kind without a cast reading false, `as: "number"` making the same pair compare numerically, a failed cast reading false, absent sorting last in both directions; plus legacy `greater-than` reading as its `gt`+`as:number` alias unchanged, legacy `equals` against a typed counter (`__match_count`) surviving its `as:string` alias, and the uncast-against-typed-literal lint firing | new |

The catalogue's contract is unchanged: byte-identical to Saxon run live, engine messages clean,
and each case amplifiable under `CaseAmplifierTest` before it earns a benchmark row. The
`dates` case is the one to write first if only one gets written — it is the gap the matrix
predicted a production configuration would hit first.

## 15. The phased plan

Each phase ends green in the ledger's usual ratchet, carries its own tests — most of this
surface is unreachable from fixtures, so the engine's rule applies: everything the fixtures
cannot reach is tested directly — and states the criterion that closes it. Benchmarked
phases follow §13's protocol.

**Phase 0 — baseline and audit. No code.**
The baseline pins to a **commit**, not to the working tree: this plan's own commit is the
pre-implementation reference, so implementation may begin ahead of the run. Tonight's evening
slot then runs the full engine suite and `CaseCatalogueBenchmark` **checked out at that
commit**, recorded to `design/benchmarks/` under its hash — the *before* of every later
comparison (§13). The
`substring` audit is already taken (2026-08-25): **five configs** carry the 0-based form —
four fixtures, all version 3 (`apache_httpd` ×29, `xml_to_json`/`_attrs`/`_unified` ×1
each), and the `string_functions` challenger at version 4 (×1). That list is phase 6's
work-list, frozen here so the migration can be checked complete against it.
*Exit: baseline recorded and drift-validated on untouched rows; audit list in this section.*

**Phase 1 — typed values.**
§3.2's mechanical change: `TypedValue` through `Transforms`, `CompiledOp.Transform`, `emit`,
`Refs.resolveValue`. Tests: a casting-table unit test with **one assertion per cell of
§3.1**, including every absent; `Transforms` signature round-trips.
*Exit: no golden moves — the whole fixture corpus byte-identical; evening comparison against
phase 0 indistinguishable on every row (§13 gate).*

*Audited 2026-08-25, diff-scoped, landed as `71860c17a8`. Code clean: every hunk checked
equivalent (all four `LocalGroup` encoding sub-cases, composite contribution sets, the
`ValueMap`/`emit` re-wrap, the test rewrap 1:1). The phase is provably inert — every
`Transforms` return is `null` or wrapped text, so no non-`Bytes` value can reach a store
through `emit` yet; the typed-bind surface wakes in phase 2, whose audit should inventory
which instructions first bind non-`Bytes`. One material finding, in the design rather than
the code: §8's legacy-alias mapping said `equals` aliases uncast, on the premise that every
value is `Bytes` today — but the store-writer inventory shows `__match_count`/`__match_idx`
and binary captures already bind `Int`, and legacy `equals` compares string forms; the
equality aliases therefore carry `as: "string"`, corrected in §8 with the evidence. Two
notes: `asBoolean` trims before the lexical match, now documented in §3.1's cell; and
`resolve` now allocates a wrapper on the literal and composite paths — expected
scalar-replaceable, tonight's comparison decides, a fast path is the fix if a row moves.*

**Phase 2 — arithmetic and the string additions.**
§§5–6 and §11's overflow rules. Tests, direct: each function's edges — overflow promoting to
`Real` not wrapping, `divide` by zero absent, `round` half-up on the negative tie, `mod`'s
sign, `string-length` counting code points not bytes, `substring-before` absent-not-empty on
a missing marker, `format-number`'s ordinary pictures and one documented edge. Cases:
`arithmetic`, `value_types`, `string_functions` extended.
*Exit: the three cases byte-identical to Saxon live, messages clean.*

**Phase 3 — the comparison spine.**
§8 in full: the strict rule and `Int`↔`Real` promotion through `Conditions`, the operand
`as`-cast, the `eq`/`ne`/`lt`/`le`/`gt`/`ge` spellings with the legacy aliases carrying
their implied casts, the uncast-literal lint, and the ordering
[16](16-sequences-and-aggregation.md) needs for `sort`/`min`/`max`. Tests, direct: cross-kind
false for every kind pair; each `as` cast succeeding and failing; alias mapping asserted at
the reader (`greater-than` constructs `gt` + `as:number`); the lint firing and not firing;
byte-wise string comparison agreeing with decoded comparison on multi-byte input. Case:
`comparison`.
*Exit: the corpus byte-identical (the legacy-alias proof); evening comparison against
baseline indistinguishable on non-new rows; 16's phase 2 unblocks.*

**Phase 4 — dates.**
§9: the `Instant` variant, `parse-date`/`format-date` with the formatter compiled once and
the reserved names bypassing it, the `reference` mechanism, the yearless compile-time error.
No run API change (§9.3 — deferred to D10). Tests, direct: nanosecond round-trip; two
offsets of one instant equal, sorting together, subtracting to zero; offset-precedence on
`format-date` (instruction zone > carried offset > UTC); the nearest-year rule at both year
boundaries; the yearless-without-reference refusal naming the instruction. Case: `dates`.
*Exit: `dates` passes against Saxon; its per-record parse cost measured and recorded (§13 —
recorded, not gated).*

**Phase 5 — diagnostics and version 5.**
§10's compile-time unknown-reference error and `strict_values`; §7's ruling executed —
version 5 opened, `substring` 1-based from it, the version-4 warning on affected
configurations. Tests: the unknown-name refusal naming the reference; `strict_values` warning
once per instruction site; **the same `substring` config at version 4 and version 5
producing outputs one position apart** — the gate's semantics pinned from both sides.
*Exit: both bases under test; the v4 warning fires on exactly the audit list's configs.*

**Phase 6 — fixture migration to 1-based, and the new-feature test sweep.**
Phase 0's five-config list, migrated: version bumped to 5, every `substring` start +1. **The
trap this phase exists to not fall into: a version bump moves two defaults, not one** — the
four fixture configs are version 3, so a bare bump to 5 would silently flip their dispatch
default from lax to strict at the same moment the base changes, two semantic changes riding
one edit. So the bump **pins the previously effective dispatch explicitly**
(`"dispatch": "lax"` on the source) before the version moves, and only the base changes.
One fixture stays deliberately at version 4 with a 0-based `substring` (a copy if need be),
so the old base remains under test for as long as the aliases live. Alongside the migration,
the sweep the ruling asked for: every §16 ruling has a test that names it — the checklist is
§16 itself, walked entry by entry against the test suite.
*Exit: all five migrated configs produce **byte-identical goldens** — the proof the version
gate maps semantics exactly; the ledger records the migration; the §16 sweep finds no
untested ruling.*

**Phase 7 — close.**
The full evening A/B against phase 0 (§13), the write-up recorded like every measured
change. [14-xslt-coverage-matrix.md](14-xslt-coverage-matrix.md) updated: the
function-library gaps close, §3's arithmetic row moves to covered, and §5's second gap
family is done. E24 closed in ISSUES with pointers here.
*Exit: matrix and ISSUES agree with the shipped engine; benchmark write-up committed.*

## 16. Decisions — ruled 2026-08-25

All eight ruled by the user; 3, 5 and 7 reshape the draft, and the sections above are
rewritten to the rulings.

1. **Instructions, not expressions** — `add`/`multiply`/… as declarative nodes, no expression
   language, with an expression front end left available as a later compile-down. **Ruled:
   yes**, as recommended; it is the only ordering that stays undoable.
2. **Version gate** — ≤ 4 stays 0-based, version 5 is 1-based, following
   `Dispatch.effective`'s precedent, with a compile-time warning on affected version-4
   configurations. **Ruled: yes**, with the make-indices-rare principle recorded in §7: the
   real investment is the index-avoiding vocabulary, so raw offsets stay rare; opaque indices
   and first-class ranges declined for a DSL whose indices are literals that never travel.
3. **One condition vocabulary with strict typed semantics and explicit `as`-casts** (§8) —
   ruled in two same-day steps: first from the draft's parallel typed `compare` condition to
   one coercing vocabulary, then from coercion to strict, on the ground that casting is
   available so the engine should never guess. Same kind compares natively (`Int`↔`Real`
   promotion included), cross-kind is false, `as: string|number|boolean|date` on either
   operand applies §3.1's casts — on the operand because guards have no body to pre-bind in.
   `sort`/`min`/`max` take the same `as` and `data_type` dissolves; an uncast sort key
   orders by string forms, the one total cast. **The comparisons are named
   `eq`/`ne`/`lt`/`le`/`gt`/`ge`** — XPath 2.0's value-comparison operators — each taking
   `left` and `right` as ref-or-typed-literal; the equality trio aliases with
   `as: "string"` on both sides and `greater-than`/`less-than` with `as: "number"` on the
   left — the casts their semantics always implied, made visible (the string cast corrected
   from "no casts" by the phase 1 audit: the engine's counters are already `Int`, and legacy
   `equals` compares string forms), per the `Store`/`capture` precedent. A typed literal
   against an uncast ref is a compile-time warning.
4. **`tokenize` binds a sequence when it binds a name**, keeping the joined rendering when it
   writes. No fixture uses it, so the change is free. **Ruled: yes**, as recommended.
5. **Run-time parameters: deferred to D10** (§9.3) — the draft's premise ("the pipeline needs
   it on day one") was validated against the pipeline source at the user's direction and found
   wrong: `XsltFilter` injects no parameters; context arrives via `stroom:` extension
   functions, so the context seam belongs to the integration layer here too. **Ruled: defer.**
6. **Unknown references are a compile-time error; run-time value complaints are opt-in**
   (`strict_values`, default off). **Ruled: yes**, as recommended — the loud check lives where
   typos are, and messy data stays quiet.
7. **A date is a first-class `Instant`** — `(epochSecond, nano, offset)`, nanosecond
   precision preserved, comparison and arithmetic on the timeline with the offset inert,
   offset used only as `format-date`'s default rendering zone; number casts are epoch millis,
   documented lossy (§§3, 9). **Ruled so** — the draft proposed epoch millis in an `Int` and
   the ruling overrode it for nanosecond sources.
8. **`Real` keeps its existing Rust-style rendering** as the default, with `format-number` as
   the answer for anything specific — revisited only if a Saxon parity case makes it
   untenable. **Ruled: yes**, as recommended.
