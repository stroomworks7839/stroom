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
**`blocked` on E17 — decided under [D34](../design/00-decisions.md). Root cause reframed.**

A template with `max_match` stops after its limit, as instructed, and then the unconsumed-content
check reports everything after it as unmatched — most visibly a CSV header, which is *supposed*
to consume one line. Seen in `fixtures/legacy/001_csv_with_header.messages` (one warning quoting
the whole body) and behind the nineteen warnings in `003_multiline_regex.messages`.

**The cause is not the check but the dispatch model** ([09-engine-semantics.md](../design/09-engine-semantics.md)):
real DS3 dispatches a level as `(A|B|C)*`, where the pass after a header hits its limit simply
lets the next expression consume, and the only report is per-level. The engine's inherited
`A*B*C*` dispatch plus per-template reporting is what manufactures the false positive. Closes
when E17 lands, taking its message goldens with it.

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
[08-fixture-audit.md](../design/08-fixture-audit.md). One (E6) has been diagnosed, fixed and
promoted; the rest are vendored and quarantined in `fixtures/status.txt`, and cannot be promoted
until corrected goldens exist.

### E6 — `win_sec` lost group identity to two dot-all flags
**`resolved` 2026-08-20. Not an engine defect; the fixture's configuration was wrong.**

The golden said `<Id></Id><Name></Name>` where the input plainly says `S-1-5-32-551` and
`Backup Operators`. The Java engine reproduced that golden byte for byte, so both
implementations agreed and neither was at fault; the `Group` template's pattern extracted all
three groups when run against the record on its own, so the pattern was not at fault either.

The templates *around* it were. Templates of a mode share one cursor, each consuming from where
the last stopped, and two patterns ended in a greedy dot under `(?ms)`:

| Template | Effect of dot-all |
|---|---|
| `Member` | `(.+)$` ran to the end of the record, consuming the Group block before `Group` was tried — and filing its text inside the `MemberDN` attribute, where the old golden preserved all of it |
| `Group` | `(.+)$` swallowed the record's tail into `GroupDomain`, which is why the second flag had to go too — the first fix alone produced `Value="Builtin\n"` |

Both are now `(?m)`. Neither pattern needs `.` to cross a newline: they spell their newlines out.

**Fixed and frozen.** The regenerated golden differs from the old one in exactly three places, all
corrections, each traceable to the input: the group id and name now appear, `MemberDN` no longer
carries the Group block, and `GroupDomain` is `Builtin` rather than empty. It is well-formed, has
the same 11 records, no raw ampersands, and its two remaining multi-line attribute values
(`Privileges`, `Accesses`) were in the old golden too and are genuine multi-valued Windows
fields. Promoted to `PASS`.

*The second flag is the reason this needed regenerating rather than reasoning about. Fixing
`Member` alone looked right and produced a newline inside an attribute; only the diff showed
it.*

### E16 — `win_sec`'s templates were listed out of data order
**`resolved` for `win_sec` 2026-08-20. Still `open` for `win_sec_xml` — re-diagnose under E17's
dispatch before any further fix.**

*Reframed by [D34](../design/00-decisions.md), then corrected: an earlier version of this note
claimed real DS3's `(A|B|C)*` "cannot strand content across a pass". Overstated. A pass is won
by list order, not buffer position, and the skip is consumed — so real DS3 SEQUENCE strands the
same blocks and merely* reports *them. The configuration was wrong under both models, the fixes
below are correct under both, and the engine's contribution was the silence that let the defect
fossilise into a generated golden. The fixes therefore stay. True order-insensitivity exists
only as `matchOrder="any"` excision (E18).*

With E6 fixed, nine empty elements and eighteen empty values remained. The cause is not dot-all
this time but something more structural, and worth understanding because it will recur in any
configuration of this shape.

**Sibling templates share a cursor, and an unanchored match consumes the prefix it skipped.** A
template matching at byte 616 when the cursor is at 441 consumes 441–616 as well, so everything
between is gone before any later template is tried. The list order therefore has to match the
order the fields appear in the data.

It did not. `ProcessID` was listed 20th but appears at byte 616; `ObjectServer` was listed 45th
but appears at byte 451 — so the entire Object block was consumed by a template looking for
something after it. The same happened three more times: the credentials block in event 4648, the
elevation and creator fields in 4688, and the password and expiry fields in 4720.

**Fixed by reordering, not rewriting.** Thirteen templates moved so that each block precedes the
one that follows it in the data. The section order is identical in all eleven records — `Subject`,
then the event-specific block, then `Process Information`, then the rest — so one order serves
them all. One further `(?ms)` had to go at the same time: `AccountWhoseCredentialsWereUsed` was
E6's defect a third time, invisible until reordering let it match at all.

Verified: 61 templates before and after, exactly one whose *content* changed, everything else
byte-identical and merely moved. The golden goes from 11 empty elements and 19 empty values to
**none of either**, is well-formed, keeps its 11 records, and every value in it is traceable to
the input.

*An approach that did not work, recorded so it is not retried: sorting all 57 field templates by
the average position of their field across records. It moved 48 of them and fixed nothing —
averaging across event types blurs exactly the section order that makes a single ordering
possible. The targeted moves are both smaller and correct.*

**`win_sec_xml` remains.** It has no `(?ms)` patterns at all, so its eleven empty elements have a
third cause and it stays quarantined.

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

### E14 — Whether the textual step subset should be lowered onto the combinator layer
**`open`, reframed by [D34](../design/00-decisions.md) — now purely an optimisation question.
Waits for E17.**

The original framing said the steps "could not" be lowered because they are possessive. Wrong:
**atoms are semantics-neutral** — `comb` compiles the same vocabulary into the HIR and gets full
backtracking, while the step interpreter drives the same atoms with PEG commitment. The
semantics belongs to the driver, and D34 has now *chosen* PEG commitment for the step layer as
the design, not an accident.

So what remains is speed: lowering the textual subset (`Tag`, `TakeWhile`, `Choice`, `Repeat`…)
onto `comb` would reach the tiered engines instead of an interpreter, and would need the
lowered form to preserve PEG semantics — atomic groups and possessive quantifiers express
exactly that, and the fancy tier supports both. `StepsTest` pins the commitment behaviour so a
lowering that silently widened what matches fails loudly. The binary atoms cannot lower and stay
interpreted regardless.

### E15 — What the output sink's other implementation is
**`blocked` on [D10](../design/00-decisions.md).**

Configurations describe their output as bytes that happen to be XML. A Stroom pipeline element
will want something else — SAX events are the obvious candidate and explicitly not the only one.
Every write already funnels through `OutputSink`, so this is one place to answer rather than
twenty, but the question itself belongs to the pipeline module that does not exist yet.

---

## From the semantics discussion (D34)

### E17 — Implement `(A|B|C)*` dispatch and DS3-shaped reporting
**`open`. The first deliberate behavioural departure from ds-rs; the write-up is
[09-engine-semantics.md](../design/09-engine-semantics.md).**

The executor's dispatch loop changes from per-template exhaustion to per-pass ordered choice;
unconsumed reporting moves from per-template to per-level; a skipped-prefix report is added per
match; all gated on `ignoreErrors`. Match limits and `onlyMatch` keep their meanings.

The message goldens encoding the false-positive class (`001`, `003`, `011`, `012`, and the
`005`/`014` severities) are regenerated **with review** and compared against Stroom's `.err`
files, which should then agree in shape as well as substance. Output goldens are expected to
survive; the ratchet names any configuration that depended on `A*B*C*`. E1 closes with this.

The pre-fix `win_sec` configuration (git, `6907ad310c^`) is a ready-made acceptance input for
the skip reports: under D34's dispatch it must *still* strand the Object block — a pass is won
by list order, not buffer position — and the new reports must name exactly the stranded
content. A test that runs it and asserts the reports would pin decision 2 with real data.

### E18 — `matchOrder="any"` (excision) is not modelled
**`deferred`.**

Real DS3 has a second dispatch mode in which the matched span is *excised* from the buffer and
the skipped prefix survives for other expressions. The ds-rs importer silently dropped the
attribute, no corpus configuration uses it, and D34 defers it until a real configuration needs
it. If it arrives, it is a dispatch variant, not a new engine.

**The acceptance fixture already half-exists.** `win_sec`'s original template order — the one
E16 replaced — reads like it was authored *assuming* order-insensitive dispatch, which is
exactly what excision provides. So when this is implemented: take the current patterns in the
original order (`git show 6907ad310c:…/win_sec/project.json` for the order; the three dot-all
fixes must stay, because excision cannot rescue a swallow that happens *inside* a match). Under
`matchOrder="any"` that config must extract everything; under D34's sequence dispatch the same
config strands, with reports (E17's test). One config, both modes pinned. No commit ever held
exactly this combination — the third flag fix landed with the reorder — so it is reconstructed,
not restored.
