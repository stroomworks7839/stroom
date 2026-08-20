# Open issues

Everything the port left open, in one place. A finding buried in a completed plan is a finding
nobody reads again, and all of these were deliberately *not* fixed while the port was running —
a port that improves things as it goes cannot be checked against the thing it is porting
([D33](../design/00-decisions.md)).

Each entry says what was seen, where to see it again, and what resolving it would involve. Refer
to them by id in commits and tests.

**Status** is one of: `open` — decided against nothing yet; `deferred` — decided, with a reason,
and revisitable; `blocked` — needs something that does not exist.

---

## Ported behaviour worth deciding about

These are things the Rust engine does that look wrong. They are reproduced exactly, and the
fixtures encode them, so changing any of them means changing a golden and saying why.

### E1 — A `maxMatch` template warns about everything it was told not to consume
**`open`. Most likely of these to annoy a real user.**

A template with `max_match` stops after its limit, as instructed, and then the unconsumed-content
check reports everything after it as unmatched. Any `maxMatch` template triggers this by
construction — most visibly a CSV header, which is *supposed* to consume one line.

Seen in `fixtures/legacy/001_csv_with_header.messages`, whose single warning quotes the whole
body of the file. Also the likeliest cause of the nineteen warnings in
`003_multiline_regex.messages`, on a fixture whose output is correct.

Resolving it means deciding whether a template that stopped on purpose can be said to have failed
to consume, and if not, updating the message goldens that record it.

### E2 — Both `ignoreErrors` fixtures warn anyway
**`open`. Related to E1 but not the same.**

`011_ignore_group_errors` and `012_ignore_root_errors` exist to test that errors are suppressed,
and both emit a warning. In each the `ignoreErrors` is on a `<group>` while the warning comes
from the enclosing `<split>`, which carries no such attribute — so it is arguably correct and
certainly not what the fixtures were written to demonstrate. Java Stroom emits nothing for
either, and neither ships an `.err` file.

Seen in `fixtures/legacy/011_ignore_group_errors.messages` and `012_...messages`.

Resolving it means deciding whether `ignoreErrors` on a group covers the expression that contains
it.

### E3 — `Template.encoding` is never read
**`open`. A field that does nothing.**

The model has it, the format writes it, no engine reads it — a per-template encoding override
does not work and never has in either implementation. Ported as modelled, because silently
dropping a field would be worse than carrying a dead one.

Resolving it means either implementing the override, or removing the field and the format's
support for it.

### E4 — A `Regex` step consumes only its match, not what it skipped
**`open`. Surprising rather than wrong.**

Inside a progressive sequence, a `Regex` step searches forward for its pattern but advances the
cursor only by the length of the match — so the bytes it skipped over are neither consumed nor
part of any group, and the next step starts in the middle of them.

Pinned by `StepsTest`. Every other step is anchored at the cursor, which is what makes this one
surprising.

Resolving it means deciding whether the step should anchor, or consume through the match, or stay
as it is with the behaviour documented at the configuration level.

### E5 — `TakeWhile` predicates are ASCII, on UTF-8 input
**`open`.**

`alphabetic`, `alphanumeric`, `numeric` and `whitespace` are Unicode-aware over characters in the
Rust source, but it takes its byte path for every byte-oriented encoding — UTF-8 included — so in
practice an accented letter ends an "alphabetic" run.

Pinned by `StepsTest`. The matching layer already has correct Unicode classes
([D23](../design/00-decisions.md)), so the fix is cheap; it is a semantic change, not a bug fix.

---

## Fixtures whose goldens are wrong

Found by the phase 0 audit; the evidence is in
[08-fixture-audit.md](../design/08-fixture-audit.md). All four are vendored and quarantined in
`fixtures/status.txt`, and cannot be promoted until corrected goldens exist.

### E6 — `win_sec` and `win_sec_xml` lose group identity
**`open`. The one most likely to be a real ds-rs defect.**

The input says `Security ID: S-1-5-32-551` and `Group Name: Backup Operators`; the golden says
`<Id></Id><Name></Name>`. Eleven empty elements in each file. The record count is right and the
XML is well-formed, which is exactly why byte-equality never noticed.

Worth re-running now that a second engine can execute the same configuration: if the Java engine
extracts those fields, ds-rs has a bug the corpus has been hiding. If it does not, the
configuration is wrong.

### E7 — `apache_httpd`'s golden is not well-formed XML
**`open`. Two problems in one file.**

A captured URL puts a bare `&` into an attribute (`Value="…?year=2026&q=1"`), so the document
cannot be parsed at all. Separately, 56 of the configuration's text nodes carry a literal
backslash-n rather than a newline — a double-escaping accident in the fixture's own
`project.json`, faithfully reproduced.

### E8 — `xml_to_json`'s golden is not valid JSON
**`open`.**

Nested objects lose their braces: `{"name":"Alice","address":"city":"London"}` — a key whose
value is a key. Three of its four lines are invalid. Its two sibling fixtures do the same job
with different configurations and both produce valid JSON, so the engine can express this and
that configuration does not.

---

## Scope deliberately not ported

### E9 — Avro, Parquet and Protobuf
**`deferred` ([D33](../design/00-decisions.md)).**

Each needs a large third-party library. The match variants are modelled and refused at compile
time with a clear message; their three fixtures stay vendored and are reported as skipped.
ds-rs's own default-features build skips the same three.

### E10 — The compile-time optimiser
**`deferred`.**

ds-rs eliminates unused captures and prunes statically-false branches. It changes work, not
output, and D33 ruled out performance work during the port. Its fourteen tests are not ported
either. Revisit alongside E12.

### E11 — `RecordingInstrument`
**`deferred`.**

The `Instrument` seam is ported and tested; the recording implementation is not. Its only
consumer was the ds-rs node editor, which is not being ported. Whatever authoring tool Stroom
grows will want its own shape rather than that one.

---

## Decisions the port sets up

### E12 — The engine has no performance story at all
**`open`. Now unblocked.**

Correct as far as 198 tests can show, and entirely unmeasured. The matching layer has a benchmark
suite, checked-in results and a scoreboard ([D21](../design/00-decisions.md),
[05-engine-benchmarks.md](../design/05-engine-benchmarks.md)); this layer has none of it. D33
deferred it until the suite was green, which it now is.

Known costs nobody has measured, listed so they are not re-derived: `Tag` steps encode their text
on every match rather than once at compile time; `apply-templates` filters the template list per
call instead of grouping by mode once; and every captured group is copied out of the buffer even
when nothing reads it — which is what E10's optimiser was for.

### E13 — Whether a match may span two buffers
**`open`. The most valuable follow-up the port enables.**

Input is read in buffers and a match never crosses one, so a configuration's buffer size is also
the largest record it can handle. This is ds-rs's limitation, kept on purpose so that golden
parity meant something — and the matching layer underneath already answers
`NEED_MORE_INPUT` for exactly this case.

Pinned by `EngineBehaviourTest`, so the current behaviour is a test rather than an assumption,
which is what makes the change safe to attempt.

### E14 — Whether progressive steps should be lowered onto the combinator layer
**`open`.**

`stroom.shapeshifter.regex.comb` has almost exactly the progressive vocabulary, and
[D8](../design/00-decisions.md) says compose at authoring time and flatten at compile time.
Lowering would be faster — the steps would reach the tiered engines instead of an interpreter —
and would also **change the language**: the interpreter is greedy and does not backtrack, a
compiled pattern does both, so `Repeat(Tag "ab")` then `Tag("ab")` against `abab` fails as steps
and matches as a pattern.

Pinned by `StepsTest` so an accidental change fails loudly. The binary atoms cannot be lowered at
all, so any change here is partial by nature.

### E15 — What the output sink's other implementation is
**`blocked` on [D10](../design/00-decisions.md).**

Configurations describe their output as bytes that happen to be XML. A Stroom pipeline element
will want something else — SAX events are the obvious candidate and explicitly not the only one.
Every write already funnels through `OutputSink`, so this is one place to answer rather than
twenty, but the question itself belongs to the pipeline module that does not exist yet.
