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
went from nineteen false warnings to eleven true errors naming genuinely unparsed audit lines.

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
[05-engine-benchmarks.md](../design/05-engine-benchmarks.md)); this layer has none of it. D33
deferred it until the suite was green, which it now is.

Known costs nobody has measured, listed so they are not re-derived: `Tag` steps encode their text
on every match rather than once at compile time; `apply-templates` filters the template list per
call instead of grouping by mode once; and every captured group is copied out of the buffer even
when nothing reads it — which is what E10's optimiser was for.

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
(the compiled level's first-byte candidate table). Remaining: the E18 original-order fixture
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
