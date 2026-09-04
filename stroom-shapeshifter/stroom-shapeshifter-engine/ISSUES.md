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

### E1 — A `maxMatch` template warned about everything it was told not to consume
**`resolved` 2026-08-20, by E17.**

An artifact of the inherited `A*B*C*` dispatch plus per-template reporting: the header stopped
at its limit, as instructed, and was then accused of failing to consume the rest of the file.
Under D34's `(A|B|C)*` dispatch the pass after the limit lets the next template consume, and
unmatched content is reported once per level. `001`'s message golden is now empty and `003`'s
went from nineteen false warnings to eleven.

*Corrected 2026-08-21 by the audit: those eleven were called "true errors naming genuinely
unparsed audit lines". They were not. Ten of them were a second defect this note's own framing
hid — the DS3 importer gave each sibling expression its own dispatch level, so the siblings
rescanned the same content and each accused what the other had eaten. The audit fixed the
migration shape (siblings now share one mode, as they already did at the root and in a group),
and `003`'s messages golden went from eleven to one: the first record's stray `----` line,
which the configuration's own `^----\n` cannot match once the split has stripped the newline.
The output golden did not move a byte under either shape, which is what let the false
accusations survive being frozen.*

### E2 — Both `ignoreErrors` fixtures warned anyway
**`resolved` 2026-08-20, by E17.**

The question this asked — does `ignoreErrors` on a group cover the expression that contains it
— turned out to be the wrong shape. In DS3 the flag belongs to the *container* and gates the
level it dispatches, inheriting downward; the ported engine had it on templates and gated
nothing an author would expect. Now the group's flag rides the `ApplyDirective` it generates,
the root's rides the source configuration, and both inherit down the dispatch tree. `011`
(group flag) and `012` (root flag) are both silent, matching Stroom.

### E3 — `Template.encoding` is never read
**`resolved` 2026-08-21: implemented, per the user's ruling.** A template's declared encoding
now governs its own content end to end: its delimiters are encoded to bytes in it at compile
time, its captures are normalised from it, its body's reads of the current match's groups
convert from it, and its progressive steps classify characters under it (E5). Unknown labels
are a compile-time error naming the template. The implementation also split value conversion
by provenance — only the current match's bytes are converted; stored values were normalised
at capture and now pass through untouched — which quietly fixed a latent double-conversion of
variable reads under non-UTF-8 runs. Pinned by `EncodedInputTest`: one stream, two encodings,
0xE9 is é only where declared.

Original text:

The model has it, the format writes it, no engine reads it — a per-template encoding override
does not work and never has in either implementation. Ported as modelled, because silently
dropping a field would be worse than carrying a dead one.

Resolving it means either implementing the override, or removing the field and the format's
support for it.

### E4 — A `Regex` step consumes only its match, not what it skipped
**`resolved` 2026-08-21: the step is anchored.** The user's ruling restored the design's own
intent: the step vocabulary exists so grammar can be described with atoms and combinators
instead of regexes, and the `Regex` step is a pre-compiled fragment of that grammar — an atom
beside `Tag` and `TakeWhile` (the vendored combinator design says exactly this), so it matches
at the cursor and never skips. The old behaviour was traced to its source and found to be an
API accident, not a design: ds-rs's `find_bytes` returned matched bytes without their offsets,
so its caller could only advance by the match's length from the wrong place. Never used by any
configuration in either codebase. Pinned by `StepsTest`, which now asserts the atom refuses a
pattern it would have found by searching.

Original text, kept for the record:

Inside a progressive sequence, a `Regex` step searches forward for its pattern but advances the
cursor only by the length of the match — so the bytes it skipped over are neither consumed nor
part of any group, and the next step starts in the middle of them.

Pinned by `StepsTest`. Every other step is anchored at the cursor, which is what makes this one
surprising.

Resolving it means deciding whether the step should anchor, or consume through the match, or stay
as it is with the behaviour documented at the configuration level.

### E5 — `TakeWhile` predicates are ASCII, on UTF-8 input
**`resolved` 2026-08-21, per the user's ruling: accurate byte matches, dependent on template
or source encoding.** Predicates classify characters, and a character is what the effective
encoding says it is: multi-byte UTF-8 letters are letters, single-byte encodings decode
through a cached table, UTF-16 and the CJK encodings decode through their charset, and RAW
keeps the ASCII reading — bytes with no declared meaning earn none. Pinned by `StepsTest`:
0xE9 is a letter under windows-1252 and ends the run under raw.

Original text:

`alphabetic`, `alphanumeric`, `numeric` and `whitespace` are Unicode-aware over characters in the
Rust source, but it takes its byte path for every byte-oriented encoding — UTF-8 included — so in
practice an accented letter ends an "alphabetic" run.

Pinned by `StepsTest`. The matching layer already has correct Unicode classes
([D23](../design/00-decisions.md)), so the fix is cheap; it is a semantic change, not a bug fix.

---

## Fixtures whose goldens are wrong

Found by the phase 0 audit; the evidence is in
[08-fixture-audit.md](../design/08-fixture-audit.md). **All four are now diagnosed, fixed at the
configuration and re-frozen under review** — the quarantine is empty. Every one turned out to be
a configuration defect; the engine needed no changes beyond what D34 had already decided.

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

*Corrected 2026-08-25 by E25: this note's "two remaining multi-line attribute values
(`Privileges`, `Accesses`) ... are genuine multi-valued Windows fields" observed the output
of a broken cleaning chain, not an authored rendering — the configuration's own
newline-and-tab replaces read a name nothing wrote. The fields are genuinely multi-valued;
their multi-line spelling was a defect, now repaired and re-frozen.*

### E16 — `win_sec`'s templates were listed out of data order
**`resolved` 2026-08-20, both halves: `win_sec` by reordering, `win_sec_xml` by anchoring —
the two fixes D34's dispatch offers, each matched to its configuration's shape.**

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

**`win_sec_xml` resolved 2026-08-20, by anchoring rather than reordering.** Its record types
disagree about field order — 4624 puts Subject before Target, 4720 and 4732 the reverse — so no
single unanchored order can serve them all, which is exactly the case D34's decision 2
anticipated. All 55 field patterns are now start-anchored (`^\s*<Data Name="X">…`), so a
template only matches when its field is at the cursor, with a listed-last `unclaimed_line`
template consuming any line the specific templates did not claim. Dispatch then picks whichever
field is next in the data regardless of list position: order-independent, skip-free, and the
cheap path — an anchored failure costs a prefix comparison, not a scan.

The regenerated golden fixed more than the eleven empties. **The old golden also contained
plausible-looking wrong values leaked from other records**: `administrator` (from the 4625
records) sat in the 4720 and 4732 outputs where `svc_backup` and `Backup Operators` belong, and
a stale `CORP` where the group domain `Builtin` belongs. Emptiness is detectable by an audit;
wrongness of this kind was only visible by diffing a corrected implementation's output — see
E19, which the discovery raises. Every corrected value was verified against its own record in
the input; the config diff is anchor-prefixes only, order preserved, one template added.

### E7 — `apache_httpd`'s golden was not well-formed XML
**`resolved` 2026-08-20. Both defects were the configuration's, and the repair is provably
minimal.**

Two problems, two fixes. Fifty-six of the configuration's text nodes carried a literal
backslash-n where a newline was meant — a double-escaping accident, faithfully reproduced by
both engines — now real newlines. And captured data was written into XML markup unescaped,
which is how a URL's `&` broke the document; every one of the 209 places a capture is written
into output now routes through XML escaping first, the same construction the DS3 importer
generates. Escaping happens at the output boundary, deliberately: `urlFull` is re-parsed by
five child modes, and escaping it in storage would corrupt the matching.

The verification is the strong part: the regenerated golden is **byte-for-byte equal to the old
golden with exactly the two defects repaired** — every literal `\n` made real, the single `&`
escaped — which simultaneously proves the golden's continuity and that all 209 escape routes
are transparent for values that need no escaping.

### E8 — `xml_to_json`'s golden was not valid JSON
**`resolved` 2026-08-20. One missing pair of braces in the configuration.**

The `element` template's nested branch wrote `"name":` and recursed into the children without
`{` and `}` around them — `"address":"city":"London"`, a key whose value is a key. Two text
nodes inserted around the recursion. All four lines are now valid JSON, the triple-nested
`org.dept.team` record included, and every value traces to the input. The two sibling fixtures
always did this correctly, which is what made the defect legible as a configuration slip rather
than an engine limitation.

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
**`open` — baseline recorded 2026-08-20** ([10-engine-compilation.md §5](../design/10-engine-compilation.md)):
1.5–67.6 MiB/s across the seven workloads, and one inversion — the anchored `win_sec_xml` is
3.6× *slower* than its unanchored sibling, because `^`-anchored patterns are dispatched as
unanchored searches and every attempt allocates a matcher. The two fixes are the named first
optimisation: anchored patterns dispatched `ANCHORED`, and the matcher held as a field of the
compiled node — the `CompiledProject` *is* the per-run executable graph. That first change
landed 2026-08-20 (`6582e96cd9`): `win_sec_xml` moved 1.5 → 16.2 MiB/s, the predicted order of
magnitude, with `ausearch` 3.25× and `apache_httpd` 1.38× as unpredicted bonuses from the same
mechanism and the controls flat. Change 2 (`d4935f1ddc`, dispatch
indexes owned by the graph) added 2–18% everywhere — cumulative vs baseline: `win_sec_xml`
11.2×, `ausearch` 3.8×, `apache_httpd` 1.5×. Change 3 (`08879108ae`, compiled
bodies: pre-encoded literals, classified references, transforms closed over their parameters)
added 12–31% on body-heavy workloads. Cumulative vs baseline after three changes:
`win_sec_xml` 11.0×, `ausearch` 4.0×, `apache_httpd` 1.67×, `regex_lines` 1.65× (112 MiB/s).
The outlier is `win_sec` at 5.8 MiB/s — unanchored `(?m)` scans.
Change 4 (`6131d1a371`, 2026-08-21) then retired change 1's anchor sniff: the regex library
now exits early for input-anchored patterns on its own parsed knowledge (its
06-performance-plan §1, an issue this port surfaced), and the engine asks the one honest
unanchored question — DS3's own shape — for 0–5% on the previously fast-pathed rows.
[10-engine-compilation.md §9](../design/10-engine-compilation.md) closes the arc; the next
choice stands: attack `win_sec`'s scan cost, or price E13 buffer-spanning.** `EngineBenchmark` runs seven
whole configurations over 256 KiB of repeated real records, five forks, results to
`design/benchmarks/` — the regex module's discipline. The status it measures against, and the
gap list of what is interpreted rather than compiled, is
[10-engine-compilation.md](../design/10-engine-compilation.md).

Correct as far as 198 tests can show, and entirely unmeasured. The matching layer has a benchmark
suite, checked-in results and a scoreboard ([D21](../design/00-decisions.md),
[05-engine-benchmarks.md](../stroom-shapeshifter-regex/design/05-engine-benchmarks.md)); this layer has none of it. D33
deferred it until the suite was green, which it now is.

Known costs nobody has measured, listed so they are not re-derived: `Tag` steps encode their text
on every match rather than once at compile time; `apply-templates` filters the template list per
call instead of grouping by mode once; and every captured group is copied out of the buffer even
when nothing reads it — which is what E10's optimiser was for.

### E23 — Sequences: iteration, grouping, sorting and aggregation
**`resolved` 2026-08-27 — designed, ruled, built in six audited phases, and closed.**
The coverage matrix's **last** gap family; with it gone, that document no longer names a
capability this engine cannot do for log transformation or data extraction, only a routing
question (D10/E15), two unrequested XPath functions and a list of deliberate refusals.

Built: `for-each` with `as`/`sort`, `sequence`, `append`, `for-each-group` with `group_by`,
`key`/`key-get`, the five folds, `distinct-values`, `tokenize` binding a sequence, the
restored `is-first`/`is-last`, and the engine names `__index`/`__position`/`__last`/
`__group`/`__group_key`/`__group_size`. Five catalogue cases prove it byte-for-byte against
Saxon — `sequence_basics`, `aggregate`, `sort`, `keys_lookup`, and `keys_grouping`, which was
**promoted from the suite's `WALLS` to its `CASES`**: the wall documented executably in 2021
terms came down exactly as designed, by starting to fail on the day a challenger existed.

**The design's central claim held.** No third layer ([D35](../design/00-decisions.md)):
sorting is an `int[]` of store indexes, grouping and keys are one index builder read two ways,
and the folds are folds. Everything is the existing two layers with a walk over them.

**Six phases, six audits, five real defects** — which is the argument for auditing each phase
rather than the tranche. In order: a lint that covered the conditions and not the variables;
an empty `select` that crashed the compiler by the name of nothing; a sort key that could read
the enclosing walk's position; a group key treated as though it were inside the group it
forms; and — the worst kind — a **false refusal**, the chunked-root guard firing on every
sequence read rather than only on `append`, which rejected a `tokenize`-and-walk that crosses
no record boundary. Phase 5 found no defect but two decisions that were undocumented and
untested, which for a decision is the same problem: nothing distinguished them from accidents.

**One defect escaped all six audits and was found by a fixture**: [E28](#e28--an-instruction-that-named-a-variable-did-not-always-bind-it),
`tokenize` skipping its binding when its input was absent. Every phase test fed values that
were present; the catalogue cases feed XML where no field is ever absent. It took writing a
fixture in log shape — `projects/log_sessions` — and putting an empty field in it.

**Measured** (2026-08-27, quiet box, `4160bf7c1c` against `45823464dc`): the requirement is
met — all eight `run` workloads indistinguishable, so a configuration using none of this pays
nothing. Compilation costs a fixed **90–290 ns**, which is −24% of the configuration that
compiles in 292 ns and invisible in the ones that take milliseconds; `BodyScan` seeds eight
engine names instead of two and carries two more namespaces. Left alone deliberately, with the
cheaper seeding named in [16 §14.3](../design/16-sequences-and-aggregation.md) if it ever
matters. Against Saxon the result is **weaker than the design predicted and is recorded as
such**: 1.13–1.62× on the five new cases, only `sort` clearing its error bars, and
`keys_grouping` a tie — because those cases spend under 1.5 µs per unit and almost all of it
parsing and writing, so the grouping is not the dominant cost and the measurement does not
test it.

**The memory contract**, the one architectural cost, is enforced rather than described: a
configuration that accumulates nothing keeps the sliding window's bound exactly, one that
accumulates is bounded by `max_sequence_entries` (100,000 per sequence by default), and
`append` under a root that reads in chunks is refused fatally — a per-chunk summary being a
number that looks like an answer.

Original entry:

**`open` — designed and ruled in full 2026-08-25, unbuilt**
([16-sequences-and-aggregation.md](../design/16-sequences-and-aggregation.md)). The coverage
matrix's first gap family — `xsl:sort`, `for-each-group group-by`, `xsl:key`, `sum`/`count`/
`avg`/`min`/`max`, `distinct-values` — designed as `for-each`, `for-each-group`, `key`/
`key-get`, `append` and the folds, all over **store indices**. The design's own finding is
why this is smaller than it looks: the accumulator the matrix said did not exist is the
store, which has been there since the port, and post-`apply` ordering already works — what
is missing is iteration. Sorting is an `int[]`, not a reorder buffer.

Eight decisions ruled, two against the draft's recommendation (`xsl:key` built rather than
deferred; the `is-first`/`is-last` conditions E21 deleted return alongside `__position`/
`__last`). Blocked on nothing — [17](../design/17-value-computation.md)'s phase 3 landed the
comparison spine it needs for `sort`/`min`/`max`.

**The plan was refreshed 2026-08-27** ([16 §14](../design/16-sequences-and-aggregation.md)),
after design/17 shipped underneath it. Three of the changes would have stopped phase 1 on its
first afternoon: the unknown-reference refusal (17 §10) rejects this design's own six engine
names until they are seeded, so `keys_grouping`'s challenger — its acceptance test — cannot
compile before that lands; sequence names are a second kind of name the refusal has to be
taught, and §9's two checks belong inside E27's single walk rather than adding two more; and
`BodyScan.visit` is exhaustive, so every instruction this design adds is a compile error
until its reads, writes and lints are considered. One item moved in: `tokenize` binding a
sequence, ruled in 17 §16.4 and blocked on exactly this work, is now phase 2. One
scheduling constraint: **E27's compile-time measurement must be taken before this starts**,
or the merge's recovery and this design's new checks are confounded in the same walk.

### E24 — Value computation: types, casting and the function library
**`resolved` 2026-08-27 — designed, ruled, built, audited and measured**
([17-value-computation.md](../design/17-value-computation.md)). The matrix's second gap
family, closed. The engine's type system existed and was discarded at every boundary: four
`TypedValue` variants, and every transform a `List<String> → String` that erased them. Seven
phases, each audited before the next began:

| Phase | What landed |
|---|---|
| 0 | Baseline at `8286556d1d`, pinned to a commit so implementation could start ahead of the run |
| 1 | `TypedValue` through `Transforms`, `CompiledOp` and `emit`; **no golden moved** |
| 2 | Arithmetic and the string additions; `arithmetic` and `value_types` cases |
| 3 | The strict comparison spine, `as`-casts, the `eq`–`ge` naming, legacy aliases |
| 4 | The `Instant` variant and the `parse-date`/`format-date` pair |
| 5 | The unknown-reference refusal, `strict_values`, **version 5** |
| 6 | Fixture migration to 1-based, and the §16 ruling-by-ruling sweep |
| 7 | The A/B, this closure, and the matrix rescored |

**What it cost to get right.** Each phase's audit found something the tests could not: the
legacy-alias mapping was wrong twice — first on typed counters (`__match_count` binds `Int`,
so equality must cast to string), then on the *absent* rule, where three legacy truth tables
would have silently flipped and no golden could have caught it because the corpus never
exercises them. `divide` wrapped silently on `MIN_VALUE / -1`, the one long division Java
does not throw for. `asInteger` threw mid-record on an extreme `Instant`, against the
never-throws contract. Version 5's degenerate `substring` start diverged from the XPath it
exists to align with. And phase 5's unknown-reference check, on its first corpus run, found
24 dead reads in the `win_sec` family — the privilege-cleaning chains dead since the port
(E25).

**What it is worth, measured** ([17](../design/17-value-computation.md) §13): the gate held —
every configuration using none of this vocabulary measures unchanged — and of the four new
catalogue cases, `dates` runs **8.78× faster than Saxon** and `arithmetic` **4× slower**. Two
of the design's own predictions were refuted, which is why they were written down first.

**What survives it, open:** E26 (the arithmetic exception, the one real loss) and E27 (three
compile-time body walks). Neither is a capability gap; both want a ruling and their own
measured change. Also open from the design's §16 sweep: `tokenize` is ruled to bind a
sequence and cannot until E23 lands, and run-time parameters are deferred to D10 after the
draft's premise was checked against the pipeline and found wrong — `XsltFilter` injects none,
and Stroom's context arrives through extension functions.

### E26 — The numeric casts answer "not a number" by throwing
**`resolved` 2026-08-27 — fixed and measured in `229c9dbd63`, completed in `594079468f`
after the audit found three sibling sites still paying it; the three losses became wins.**

*Filed as "arithmetic on fractional text pays a thrown exception per operand" — the symptom
the closing A/B surfaced. Retitled once the audit established the mechanism: it is the
numeric casts, which conditions and the function library reach as readily as arithmetic
does. The entry's own lesson, applied to the entry.*

`asInteger` now parses without throwing (Java's own algorithm, accumulating negatively so
`MIN_VALUE` stays representable) and `asNumber` gates its parser on the first character,
since every string `Double.valueOf` accepts begins with a digit, sign, point, `N` or `I` —
so the gate refuses only what would have thrown. Neither the contract nor what parses moves:
a differential test asserts equivalence against the two parsers it replaced across ~4050
inputs — both `long` boundaries and one past each, hex floats, `NaN`, `Infinity`, the
`d`/`f` suffixes, and non-ASCII digits, which Java reads and which `Character.digit` keeps
reading rather than an ASCII range check quietly narrowing them.

Measured, `2026-08-27-0749-37825da20d-xml` → `2026-08-27-1048-229c9dbd63-xml`:

| Case (100k) | Before | After | | vs Saxon before | vs Saxon after |
|---|---|---|---|---|---|
| `arithmetic` | 897 ms | 149 ms | **−83%** | 0.25× (4× slower) | **1.50× faster** |
| `comparison` | 289 ms | 56 ms | **−81%** | 0.93× | **4.73× faster** |
| `value_types` | 138 ms | 50 ms | **−64%** | 0.77× | **2.17× faster** |
| `string_functions` | 183 ms | 142 ms | −22% | 1.66× | 2.18× faster |

**The issue was filed too narrowly, and the numbers say so.** It was written up as an
*arithmetic* defect because that is the case the A/B surfaced; it was really a defect in the
two numeric *casts*, which conditions reach as readily as arithmetic does. `comparison` — a
case with no arithmetic instruction in it, only `as: "number"` reads and a legacy
`greater-than` alias — improved by 81%, the largest relative gain of the four, and
`string_functions` improved without being suspected at all. The lesson for the next one:
name the mechanism, not the symptom that found it.

The engine corpus is flat and **provably so**: no fixture in it uses a numeric condition or
an arithmetic instruction, so the changed casts are unreachable there. Three rows separated
at ~1.5% (`win_sec_xml` compile, `apache_httpd` run, `progressive` run) with no causal path —
one of them a compile-path row the fix does not touch — which is the same small
harness-variance separation seen in both directions across all four runs of this tranche.

*Audited 2026-08-27 (`594079468f`), and the audit found the fix incomplete — for exactly the
reason the paragraph above had just given. Having written "name the mechanism, not the
symptom", the fix then reached only the two casts the benchmark pointed at, leaving three
sibling sites answering "not a number" by throwing: `Transforms.number`, a published
instruction of the function library one method away in the same file; the
replacement-expansion group index, which cost an exception on **every** `$name` expansion,
per match; and `parse-date`'s epoch arms, once per record. The root cause was structural
rather than inattention — the non-throwing parse was a **private helper inside
`TypedValue`**, so no other site could reach the cheap answer even in principle. It is now
`Numbers`: one home, four callers, with the class comment recording why it lives there.
Equivalence is asserted differentially across all four sites. The DS3 config-load parses
(`Ds3Parser`, `LegacyRefs`) are deliberately left throwing — once per load is not the same
problem, and sweeping them up for symmetry would be a change with no reason behind it.*

*The completion is **not measurable on the current corpus, by construction**, and its
attempted measurement is void: no catalogue case feeds malformed input to any of the three
sites — `string_functions` and `value_types` hand `number` only well-formed text (and
`value_types` guards it behind a `[0-9]` match), `dates` parses valid epoch millis, and no
case uses a named-group regex replace — so the throwing path is never reached and there is
nothing to speed up. The run taken to check this (`2026-08-27-1231-594079468f-xml`) landed on
a busy box and is recorded as a comparability break in the benchmarks README rather than
read. The value here is latent: it appears on real data with malformed fields, which the
corpus does not have, and the headline numbers above — which came from the casts — are
unaffected either way.*

Original text:

**`open` — found and priced 2026-08-27 by design/17's closing A/B**
([17-value-computation.md §13](../design/17-value-computation.md)). The `arithmetic`
catalogue case runs **4× slower than Saxon** (897 ms against 223 ms at 100k units), the one
loss in a tranche whose other rows are wins or parity, and the cause is measured rather than
supposed. A probe over identical shapes:

```
whole-text operands  ("3", "20")     42 ns/op
fractional operands  ("3", "19.5") 3450 ns/op     <-- 82x
already-typed        (Int, Real)      27 ns/op
```

`TypedValue.asInteger()` honours the total-cast contract — every cast is total and never
throws (design/17 §2) — by calling `Long.valueOf` inside a `try`. So an operand that is not
*integral text* constructs a `NumberFormatException`, fills in its stack trace, and has it
discarded; `Transforms.fold()` then re-parses the whole operand list through `numbers()`,
paying it a second time. `19.5` and `-2.5` are ordinary log data — prices, rates, durations —
so this is the common path for fractional arithmetic, not an exotic one.

The contract is right and should not change: absent-not-throwing is what makes messy data
survivable. What is wrong is implementing it with an exception. Resolving it means deciding
the numeric kind *before* committing to a parse that can throw — inspecting the text for a
decimal point and exponent, or a non-throwing parse — so the fractional path costs a scan
rather than a stack trace. Local to `TypedValue`, with `TypedValueTest`'s casting table
already pinning the behaviour that must not move.

Recorded rather than fixed at the point of discovery, per the engine's own rule that
optimisation follows the baseline one measured change at a time (E12): the fix wants its own
before-and-after, and the before is `2026-08-27-0749-37825da20d-xml.json`.

### E27 — Compilation walks every template body three times
**`resolved` 2026-08-27 (`3dd0d3cc9a`) — one `BodyScan` pass. Measured 2026-08-27, and the
merge recovered what it was meant to:** at `4160bf7c1c` against the post-merge
`2026-08-27-0738-37825da20d-engine` run, `csv_header` compile is **+20.5%** and `regex_lines`
**+1.9%**, both clear of their error bars, with `progressive` indistinguishable and every
run-side workload unchanged. This was the measurement [16 §14.2](../design/16-sequences-and-aggregation.md)
pinned to a commit rather than to the calendar, so that E23 could start without waiting for a
quiet box; the pin worked, and the number was taken four days of work later against exactly
the commit it named.

Original entry, written when the number was still owed: one `BodyScan` pass, with the
compile-time number deliberately unmeasured. Two of the three checks decide as they go; the refusal cannot,
since a read in the first template may name what the last one writes, so reads are collected
and judged against the finished write set afterwards — which is also what lets one walk
replace the two that check needed by itself. The observable order is preserved: every lint is
emitted before any reference error is thrown, and the substring warning still comes last,
after the refusal that can prevent it.

**The repair is not the microseconds.** `BodyScan.visit` is exhaustive over the sealed
`OutputNode` hierarchy with **no `default` arm**, so an instruction added to the vocabulary
without being considered here is a compile error — where before it was three separate
switches that would each ignore it in silence. The vocabulary grew by sixteen instructions
during design/17, which is precisely when three silently-incomplete switches would have
bitten. The same now holds for `Condition`.

*Audited 2026-08-27. One drift found and corrected: the merged walk read a template's
**captures before its guard**, where the three separate checks read guard first — which
decides nothing about whether a configuration compiles, but does decide *which* unknown name
a template with two of them reports. Restored, with the reason written at the site so a
later tidy does not undo it. Everything else compared clean, path by path against each of
the three originals: the same names bound, the same references collected (including guards
and both capture-source shapes), the same lint sites, the same key-value stand-down, and the
same reporting order — lints during the walk, the refusal in `report()` before the substring
warning that the refusal can prevent. Coverage is now pinned rather than reasoned about:
`BodyScanBindingsTest` compiles one configuration per binding instruction, each writing a
name and reading it straight back, and was mutation-checked — dropping `format-date`'s bind
fails it by name. 362 engine tests.*

**What is not verified:** the −38% this issue was filed for. The box was busy when the fix
landed, and the previous run taken on a busy box is already checked in as unreadable
(`2026-08-27-1231`); taking another would repeat a mistake this repository has now documented
twice. Resolved on the shape it was filed for — three walks becoming one, verified by
reading the code and by the tests — with the timing owed on a quiet box. If it is ever taken
and the compile rows have not recovered, this entry is where to reopen from.

Original text:

**`open` — found 2026-08-27 by the same A/B.** `Compiler.compile` now makes three
independent full walks of every template body — `comparisonChecks` (design/17 §8's lint),
`referenceChecks` (§10's unknown-name refusal) and `substringVersionCheck` (§7's bump
warning) — each added by a different phase, each visiting the same tree.

On configurations whose compile is otherwise trivial it is plainly visible:

| Config | Baseline `8286556d1d` | Close `37825da20d` | |
|---|---|---|---|
| `csv_header` | 1008 ns | 1634 ns | **−38%** |
| `progressive` | 203 ns | 284 ns | −28% |
| `regex_lines` | 6807 ns | 7231 ns | −6% |

Every configuration where real regex compilation dominates — `apache_httpd`, the `win_sec`
family, `ausearch` — is indistinguishable, and compile is a once-per-load cost by design
("compile once, run per input"), so 0.6 µs added to a pipeline load is nothing anybody will
feel. This is filed for the **shape**, not the magnitude: three walks is where a fourth check
becomes four walks, and the checks are only going to accumulate as the vocabulary grows.

Resolving it means one walk that feeds several visitors, which is also the natural home for
any later check. Cheap, and worth doing before the next check is written rather than after.

### E13 — The window should slide, as DS3's does; the bounded contract stays
**`resolved` 2026-08-21.** The root level streams through a sliding window: consumption
advances an offset, and the window compacts and refills only when a match runs into its edge
with input unread, or when a pass finds nothing and more input might complete a record — DS3's
semantics at none of DS3's per-match compaction cost. Match counts live for the whole stream,
so minimum-match is judged once at the end; the truncation warning fires exactly as before for
a match that swallows a full window. Whole-buffer inputs and the non-consuming root dispatches
(classify, any) keep the window-at-a-time path. Pinned by three behavioural tests, including a
record that straddles a read boundary and parses. The zero-advance error replaces DS3's
recovery mode, per D36: no silent half-buffer skips. Known price, convicted by a same-hour
A/B ([10-engine-compilation.md §12](../design/10-engine-compilation.md)): 8% on
`apache_httpd` alone, six workloads flat, mechanism undiagnosed after two eliminated
theories — an open profiler-diff item, not a mystery to forget.

Original scoping note, kept for the record:

The desirable contract is DS3's and is not in question: memory bounded by a user-set buffer
size, a single match must fit the buffer's capacity or fail (DS3 pairs the failure with its
recovery mode), and those bounds are behaviour and performance guarantees. What differs is
the window's motion. DS3 consumes from the front and **refills to capacity every round**
(`reader.fillBuffer()` inside the pass loop) — a sliding window, so chunk boundaries are
invisible and only genuinely oversized matches fail. Our port reads **fixed independent
chunks** and abandons each unconsumed tail: a record well within capacity fails if it merely
straddles where a chunk happened to end, making failures depend on stream *position* rather
than record *size* — something no author can reason about.

Resolving it means a sliding refill under the same bounded contract: carry the unconsumed
tail, refill behind it, report unmatched content only when the window is full and nothing
matches or at end of stream — DS3's own shape. One D36 interplay to decide during
implementation: DS3's recovery advance is an implicit cursor movement, which the strict
world would express as an error (or fatal) rather than a silent half-buffer skip. Compaction
cost gets the usual treatment: benchmark either side.

Input is read in buffers and a match never crosses one, so a configuration's buffer size is also
the largest record it can handle. This is ds-rs's limitation, kept on purpose so that golden
parity meant something. (The matching layer's `NEED_MORE_INPUT` answer that once served
exactly this case retired with D37; a sliding-refill fix now has to detect the boundary
itself, which the executor's own buffering already does.)

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
**`resolved` 2026-08-20.** The write-up is [09-engine-semantics.md](../design/09-engine-semantics.md),
including what landing it taught.

The executor dispatches each level as iterated ordered choice; skips and unmatched content are
reported in DS3's shape, gated by the container's `ignoreErrors` (directive or source), which
now inherits down the tree. Every output golden survived — the ratchet caught one dependency
(`018`), which exposed that guards must be evaluated at level entry, while the scope still
describes the parent, exactly as DS3's parent-count parameter implies. Message goldens
regenerated under review: `005` and `014` now match Stroom's own record in count, severity and
substance. E1 and E2 closed with it. Three new tests pin the semantics: interleaved records
dispatch `[A:1][B:2][A:3]`, a pass is won by list order with the skip reported, and the
original `win_sec` configuration strands loudly — with real data.

### E18 — `matchOrder="any"` (excision) is not modelled
**`resolved` 2026-08-21.** The mode itself landed with E20 (`dispatch: "any"`, DS3's excision
semantics, behaviourally pinned). The original-order win_sec fixture question is closed by the
user's ruling: the current fixture stays as E16 left it — ordering is an authoring concern for
whoever writes the next version — and `win_sec_strict` already serves as the optimally-authored
comparison fixture, byte-identical output included.

Original text:

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

### E19 — A capture not re-matched keeps the previous record's value
**`resolved` 2026-08-20 — half fixed, half pinned, split exactly where DS3 splits it.**

Reading DS3's `storeData` settled the lifecycle: a var's stores are cleared on the **first store
of a new match sequence** (`parentMatchCount == 0 → clearStores()`), and never otherwise. That
divides the leak into two cases with different verdicts:

- **Fewer matches than the previous record** — DS3's clear wipes the old sequence before the
  new one stores, so the previous record's tail is unreadable. Our engine only overwrote index
  by index, leaving the tail for `latest()` to find. **A real divergence, fixed**: a template's
  first match of a dispatch now clears its captures' stores, mirroring DS3 exactly. The fix is
  mutation-tested — with the clear disabled, the regression test reads the stale tail.
- **No match at all** — DS3's clear lives inside the store path, and a template that never
  stores never clears. **DS3 leaks here too.** The behaviour is faithful and is now pinned by a
  test as documented semantics rather than an accident: `administrator` in the old
  `win_sec_xml` golden was this case. A configuration that does not want it anchors its
  patterns or guards its references.

No fixture golden changed, which is itself confirmation: the legacy goldens came from Stroom,
which has the clear — a fixture exercising the divergence would have been failing parity since
phase 4.

Residual, deliberately out of scope: `Variable` and named transform results are stored the same
way and can tail-leak in the same shape, but they are XSLT-side constructs with no DS3
counterpart to be faithful to, and every corpus use reads them at the current match index where
no leak is possible. If one ever reads `latest()` across records, this entry is the precedent
for what to do.

**That happened. See [E28](#e28--an-instruction-that-named-a-variable-did-not-always-bind-it).**
Design/16's iteration removed the "reads at the current match index" premise this residual
rested on, and design/17 multiplied the instructions that name a result. E28 is that precedent
being applied.

**Addendum 2026-08-21, from the coverage catalogue's `modes` case:** the pinned no-match case
is now demonstrated at authoring level. A first draft captured each `EventDetail` branch into
its own optional variable and `exists`-tested them at a trailing emit template; a record whose
branch template never matched read the *previous* record's value straight through the test
(event 2 reported event 1's `Logon` as its action). The idiom that avoids the trap: decide the
branch at dispatch time — the matching template emits its own output from current-match groups
— rather than exists-testing optional captures after the fact. `nasty_xml`'s `deep` variable
is the same shape and passes only because its optional field sits on the last entry; the live
byte-parity contract would catch any reordering, so it stays as-is, noted here.
### E20 — Strict dispatch: the cursor moves only by matching at it
**`in progress` — core implemented 2026-08-21: modes strict/lax/classify/lexer live, `consume`
and `emit-error` live, zero-advance errors, version-gated defaults (v4+ strict), validation
and lints; `StrictDispatchTest` covers it behaviourally. Update, same day: `any` implemented
(DS3's excision, list-priority over data position, attribution goes dark after the first
excision rather than lying); four fixtures landed — `win_sec_strict` (the lax config converted
with two dispatch attributes and two line eaters, **byte-identical output**), `strict_kv`,
`classify_alerts`, `lexer_tokens`; benchmark workload `win_sec_strict` added as the direct
A/B. The measurement landed clean
(2026-08-21-1323): strict 1.36× over lax on win_sec, byte-identical output — the
order-of-magnitude expectation amended honestly in both design docs, the headroom now named
(the library's anchored entry refuting on its first byte before setup — regex 07 Phase 5,
nothing published; the compiled-level candidate table first named here is withdrawn unless
the per-call scaffolding is what remains after that). Remaining: the E18 original-order fixture
question only**
([design/11-strict-dispatch.md](../design/11-strict-dispatch.md)). Implicit cursor movement —
skip consumption, recovery advance, the zero-advance quirk — replaced by authored eaters
(`consume: line` / `bytes(n)` / `until`) and authored error-emitting paths; dispatch asks only
the anchored question in strict groups, dissolving the unanchored-search cost structure
instead of engineering around it. Key constraint found while drafting: migration cannot
silently replace search mode, because DS3's winner selection is template-priority-over-
position while a strict group with an eater is position-priority-over-template — the E16/E18
territory. Blocked on the doc's seven numbered decisions.

### E21 — `is-first`/`is-last` conditions read a flag nothing sets
**`resolved` 2026-08-21 — ruled: deleted, both same-day.**
Found by the coverage catalogue's `adjacent_groups` case: `Conditions` evaluated
`IsFirst`/`IsLast` against `__foreach_is_first`/`__foreach_is_last`, variables no dispatch
mode ever sets — ported vocabulary whose ds-rs context did not survive the port. **Ruling:
delete until a case needs for-each positional index and count semantics.** The conditions,
their evaluator arm and its flag reader, and the `is-first`/`is-last` codec spellings are
gone; the `equals`-on-`__match_count` idiom (proven by `adjacent_groups`) is the documented
way to test position. If a case ever demands richer positional vocabulary, this entry is
where the deleted shape is recorded.

The same ruling settled the case's second finding: **`substring` stays 0-based, documented,
not aligned** to XSLT's 1-based — faithful to the ported transform library and existing
configurations. The trap note lives in `OutputNode.Substring`'s javadoc and the matrix.

### E25 — The win_sec family read names nothing wrote; the cleaning chains were dead
**`resolved` 2026-08-25, found by design/17 §10's unknown-reference check on its first corpus
run — repaired at the configurations and re-frozen, per the user's ruling.** All three
win_sec configurations carried reads of ds-rs node-editor display names — `Var:
privilegesClean`, `Var: subjectSID` and eight more, 24 read sites — where the port should
have carried the variable ids they aliased. Every one read absent for ever: the
privilege/access cleaning chains (newlines to a space, tabs deleted) had been authored,
ported, and dead the whole time, and the six trim templates trimmed nothing. The output
consequence was exactly the multi-line attribute values E6's audit note called "genuine
multi-valued Windows fields ... in the old golden too" — true about the fields, wrong about
the spelling: the cleaner that would have flattened them was broken, in ds-rs as here, so
both engines agreed on defective output and the golden froze it. Repaired by mapping each
display name to its intended source (the adjacent UUID variable for the cleaning chains, the
template's own capture for the trims), goldens regenerated and diffed: the only movement is
the four multi-line values collapsing to clean single lines — the trim repairs are
output-neutral because the capture groups already exclude the whitespace. The historical
copy under `e17/` is patched identically, since its subject is stranding, not references.
The check that found this stands down for configurations with key-value captures, whose
names are read out of the data and cannot be known statically.

*The phase 5 audit added the second layer: the six trim templates' repaired reads bind
names that nothing reads either — the artifact ran in both directions, display-name reads
and unread binds — which is why their repair was provably output-neutral. The trims stay
as faithful ported shape; deleting them is beyond the repair ruling's scope.*

### E22 — Charset fallback chains substitute near-equivalents silently
**`resolved` 2026-08-21: pinned and made loud, per the user's ruling.** ds-rs parity is no
longer a constraint — the port is done, and divergence from here is a choice this
implementation gets to make — and exotic encodings are accepted as something to be dealt with
outside the engine. That removes the only argument for keeping an approximate fallback. The
approximating names are gone: `SHIFT_JIS` resolves `Shift_JIS` or nothing, `WINDOWS_874`
resolves its own spellings or nothing, and the remaining multi-name entries are alternative
spellings of one charset rather than neighbours. A declared encoding this runtime lacks is now
refused by name at compile time from the template path too, which is where the hole actually
was: the source path already refused it, a template's `encoding=` did not, and would have
reached `new String(bytes, null)` at run time. An encoding now decodes exactly what it says, or
the configuration naming it does not compile.

Original text:

`Encoding`'s multi-name entries treat their later names as interchangeable, and they are not:
`SHIFT_JIS` falls back to `windows-31j` (differs on the NEC/IBM vendor rows) and
`WINDOWS_874` to `TIS-620` (lacks the 0x80–0x9F assignments), so a slim runtime without
`jdk.charsets` decodes differently from a full JDK with no message — at odds with the
compile-time refusal of unavailable encodings. Also worth settling against ds-rs: if it used
`encoding_rs`, its `shift_jis` *is* windows-31j, so preferring JDK `Shift_JIS` first may
itself diverge from ported parity on extension characters. The approximation is now documented
on `Encoding.charset()`; the open question is whether to pin one name per encoding and fail
loud, and which Shift JIS mapping parity actually requires.

### E28 — An instruction that named a variable did not always bind it
**`resolved` 2026-08-27, found by the `log_sessions` fixture on the day it was written.**

A log line whose `tags` field was empty was given the **previous** line's tags. `tokenize` with
a `name` skipped its binding when its input resolved absent, so the name still held the last
record's pieces and the `for-each` over it walked them. Every existing test missed it: the unit
tests bind from values that are always present, and the nine catalogue cases feed well-formed
XML where every element carries every attribute, so no field is ever absent in them.

The rule it exposed is one line — **naming a variable binds it, absence included** — and the
engine already kept it in the sequence arms and not in the scalar ones. `distinct-values` and
`key-get` bind unconditionally; `tokenize`, `parse-date`, the folds and every `Transform` (the
whole design/17 function library) guarded the *bind* with the same `!= null` that correctly
guards the *write*. Writing nothing is right — that is the "empty is absent" rule. Leaving the
name alone is not: a reference with no index reads `latest()`, so an untouched name answers
with whatever answered last.

Fixed in one place, `Executor.emit`, which now clears the name at the match index when the
value is absent instead of returning; the four arms call it unconditionally. `tokenize` binds
the empty sequence, which a walk runs over zero times. Two regression tests, both
mutation-checked; no golden moved, which is the confirmation that nothing depended on the
stale read.

Design/16's iteration is what made this reachable and what makes the fix sufficient where it
matters: inside a walk the enclosing match index does not move, so every entry binds the same
cell, and clearing that cell is a real clear. Across *records* the index advances and
`latest()` still falls back to the previous match — a named result now behaves exactly as a
capture that did not match already does (`Store.remove`), which is E19's pinned, DS3-faithful
half. That is deliberately not changed here: it is one rule for both, rather than a special
case for one. A configuration that does not want it uses the idiom E19's `modes` addendum
names — decide at dispatch time rather than testing an optional value after the fact.

**Swept the same day.** One bug caught by accident says nothing about its neighbours, so the
whole design/17 library was put through the case that caught this one:
`AbsentAndMalformedValuesTest` runs all 28 value-producing instructions over a present value, an
absent one and a malformed one, written and bound, with the absent record between two that have
values. Nothing else was wrong — every instruction writes nothing and binds nothing for an
absent input, answers a malformed one with absence rather than an exception, and raises no
message at all. The edges are pinned in the same file (division by zero, `MIN_VALUE / -1` and
overflow, code-point counting and cutting, XPath's substring bounds, `round` half towards
positive infinity). Mutation-checked: reverting the fix in `Executor.emit` fails 24 of the 28.

### E29 — Regex steps ignore the template's declared encoding
**`resolved` 2026-08-28, same day it was found — phase 3 of design 19.** The regex library
took its encoding parameter and its single-byte table lowering, `RegexEncodings` maps this
vocabulary onto the library's at one seam, the interned patterns key by (text, encoding)
because one source text under two encodings is two byte machines, and a windows-1252
template's regex now matches the `E9` its feed actually carries — pinned by
`EncodedInputTest`, whose old refusal cases became the capability tests the refusal was
holding the door for. The refusal itself narrows rather than retires — twice, the second
by the same-day audit: RAW until phase 4 and the transcode family by design, and only for
the match vocabulary, because guards' conditions and body replaces match resolved values
whose internal form is UTF-8 whatever the feed carries — two of phase 0's four carriers
never needed refusing. Originally: found 2026-08-28, during the D38 encoding discussion. E3 gave templates a declared
encoding, and two of the three matching vocabularies honour it: delimiters compile their byte
forms through it, progressive steps classify characters under it at run time (E5). The third
does not: `Compiler.compileMatch` receives the resolved template charset and compiles regex
steps with `BytePattern.compile(pattern, flags)` — no encoding, because the regex module's API
has nowhere to put one; its lowering is hardwired UTF-8. So a `windows-1252` template's regex
matches UTF-8 byte sequences against 1252 bytes: `[é]` compiles to `C3 A9` and can never match
the `E9` the feed actually carries — and under D38's strictness `.` refuses the byte outright,
since a lone `E9` is not well-formed UTF-8. `RAW` templates are mis-served the same way. This
is a silent approximation of exactly the kind E22 rules out: the configuration asked for one
thing and got a neighbour, with "no match" standing in for "cannot do that".

Resolving it is the regex module's encoding parameter (regex design 01 §4.0–4.5: UTF-8, a
256-entry single-byte table, or `RAW` identity — the module is dependency-free, so it takes its
own encoding shape and the engine maps its `Encoding` onto it). Until that lands, the honest
stopgap is a compile-time refusal by name in `compileMatch` for any template whose effective
match encoding is not UTF-8-compatible and whose match or steps carry a regex — pending a check
of what existing fixtures that breaks, which is itself evidence of how much the gap is leaned on.
The phased plan for the whole of it, stopgap through `\BHH`, is
[design 19](../design/19-encoding-plan.md); this issue is its driver, closes at its phase 3,
and carries the stopgap from its phase 0.

---

## What Stroom integration needs (opened 2026-08-28, Jon)

Three capabilities the engine does not have and a Stroom deployment will want. Each needs a
design before code; each is recorded here so the shape of the gap is written down rather than
remembered. They are independent of one another and of the port's own backlog.

### E30 — SAX events as input
**`open` — needs a design.**

The engine reads bytes: `Shapeshifter.run` takes an `InputStream`, and `Executor` *pulls* —
filling a `bufferSize` window, probing exhaustion through a `PushbackInputStream`. SAX pushes.
Something has to invert that, and the choice is the design's first question:

- **Serialise first.** Write the events into a byte array, then run as today. Simplest, and the
  engine's contract is unchanged; the cost is holding the whole document.
- **Adapt with back-pressure.** A bounded queue or pipe between the SAX producer and the
  executor's pull, on its own thread. The pull design survives; the costs are a thread per run,
  error propagation across it, and instrument-ordering care.
- **Make the executor feedable.** Invert the engine itself. This is the shape D37 retired for
  the regex library, and for the same reason — every consumer paid for a mode with no user.

The harder question is not the plumbing, though, and the design should lead with it: **what byte
image does an event stream have?** The configuration matches text. Once the original bytes are
gone, the serialisation has to choose prefix bindings, attribute order, whitespace, entity forms
and self-closing versus paired tags — and configurations will be written against whatever it
chooses, so it is a compatibility contract, not a formatting preference. Canonical XML (C14N) is
the obvious candidate precisely because it has already answered these questions.

The alternative worth naming and rejecting explicitly rather than silently: matching the *event
stream* — element names, paths, attributes — as a second matching vocabulary beside bytes. That
is a much larger design, it splits the engine's model in two, and the fixtures show the text
route already works (`xml_to_json` matches serialised XML today).

### E31 — SAX events as output
**`open` — designed 2026-08-28 in [design 20](../design/20-sax-output.md), awaiting the
rulings in its §10. Overlaps E15, which is `blocked` on D10; this is the concrete form that
unblocks it.**

`OutputSink` exists for exactly this and says so in its javadoc: every write funnels through one
interface so the second implementation is one place to answer. What it does not solve is that
**the instructions emit text** — and design 20 found the cost of that twice over while it was
being written: `apache_httpd`'s configuration carries **244 `translate` steps** whose only job
is to escape `& " < >` by hand, and `Ds3Migration.dataReference` splices *reference* values
into attributes with no escaping at all, so a captured field carrying `&` or `<` emits
ill-formed XML (latent: no fixture captures such a field today). Stroom's own DS3 cannot have
that bug, because it emits events and the serialiser escapes by construction. `Text` and `ValueOf` write bytes that happen to be XML; nothing
in the model names an element, an attribute or a namespace. So the design chooses:

- **Parse and forward.** Serialise as today, parse the bytes, emit events. Configurations are
  unchanged and the whole corpus keeps working; the cost is a parse of everything the engine
  just wrote, and ill-formed output becomes an error at a confusing distance from its cause.
- **Structured emitters.** New instructions (`element`, `attribute`, `namespace`, `text`) that
  emit events directly, with the byte sink serialising them when the target is bytes. Real
  fidelity, prefix control and well-formedness enforced where the mistake is made — at the cost
  of a second output vocabulary, and of configurations that behave differently on the two
  targets unless the mapping both ways is defined.

Two things the design must not skip. **Namespace handling**: prefix binding and scope are the
part of SAX that a text-emitting configuration currently gets to ignore. And **attribution**:
`OutputSink.position()` is a byte count, `Instrument.onOutput` reports byte spans, and the
editor's output pane (design 18 §5.4) colours those spans by the instruction that wrote them.
Under events there are no byte offsets — the trace needs an event-indexed span, or a synthesised
one, and that decision reaches the UI.

### E32 — An extensible function library
**`open` — needs a design.**

There is no registry, and the shape of the code is the reason: transform functions are a
**closed** set of records in `OutputNode` (`Translate`, `StringJoin`, `Replace`, `LowerCase`, …),
compiled by an exhaustive `switch` in `CompiledOp`, and implemented as static methods in
`exec/Transforms`, `Numbers`, `Dates`, `Codecs`. Adding one means editing the config model, the
JSON codec, the compiler and the runtime — four edits inside the engine module. Stroom cannot
contribute a function at all, which is what `http-call` and the rest of its XSLT integrations
would need. Saxon's `ExtensionFunctionDefinition` is the comparison to draw.

What the design has to settle:

- **Resolution.** A name-dispatched `Function(name, args…)` node beside the sealed built-ins, or
  the built-ins migrated onto the same mechanism. Unknown names must fail at compile time, by
  name, as unknown match kinds already do.
- **Signatures and types.** Arity and argument types over doc 17's `TypedValue` model, checked
  at compile time, so a wrong call is a configuration error rather than a runtime surprise.
- **A call context.** Registered functions need services — an HTTP client, feed and stream
  metadata, a cache — passed in rather than reached for.
- **Purity, and this is the one with teeth.** `http-call` is impure, and the editor
  (design 18, Q6) **re-runs the whole configuration on every edit, debounced**, with the preview
  endpoint re-running it again to draw the trace. A configuration carrying an impure function
  would fire it on every keystroke. The registry has to let a function declare itself impure and
  the engine has to do something honest with that: memoise per run, refuse in preview mode, or
  gate the editor's auto-run — a ruling that belongs to this design and reaches design 18.
- **Failure semantics and limits.** What a timeout or a 500 does to the record being built
  (message and continue, or fatal), and whether a configuration may reach arbitrary URLs at all
  — a pipeline that can call out is a security surface, not only a feature.
