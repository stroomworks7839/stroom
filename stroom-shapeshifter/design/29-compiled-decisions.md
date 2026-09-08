# Design 29 — The compiled graph decides: configuration read once, not per record

*Proposed 2026-09-08, from the survey that followed design 25's full-suite gate; awaiting a
ruling (D51). Engine only. Touches no golden and no configuration: every change here replaces
a decision with its own answer.*

## 1. Where it stands

D35 says the `CompiledProject` **is** the executable graph, and design 10 §1 says what that
buys: the configuration is read at compile time, and the record loop runs over what compiling
concluded. The engine mostly does this. Matchers are fields of compiled nodes, references are
compiled strategies, the model's instructions are classified rather than enumerated (D47), and
since design 25 phase 3 a capture binding is compiled too.

Then the user asked a plain question of the value model — *is there a switch at run time, or is
the model compiled properly to supply the right factory?* — and the answer for that path was
the former. A survey of the whole run path found **twenty-five sites** of the same shape: a
question the configuration settles, asked again per record, per match, per group, per character
or per write. Every one was read at `file:line` and confirmed; three of them sit directly under
a comment claiming the opposite.

This is not twenty-five ideas. It is one idea in twenty-five places, and the fix is the same
sentence each time: *decide it where it is known, hold the answer on the node.*

**What the survey left out, and this design has since taken in.** The first draft fenced off
three things that are the same idea. The **step interpreter's shape** — it switches over the
authored `MatchStep` model, and two of the survey's hottest sites live inside it with nowhere to
put their answers, so §5's phase 2 compiles the steps and closes design 10 §2's remaining step
rows with them. **E39's conditions half** — the last place that resolves an authored expression
per evaluation, its capture half having gone to design 25 phase 3 by this design's argument; it
is phase 4, with the measurement ruling 7 asked for as the phase's opening act rather than a
precondition. And **E43's dispatch**, measured at about half of `csv_header`'s residue, which
belongs in phase 1's seam rather than as a special case landed ahead of it.

**Why now.** E43 measured design 25's tagged value at one to four per cent of the run rows and
recorded it as the price of the type model. That price is worth naming beside these: several of
the sites below are of the same order or larger, and none of them buys anything.

**But E43 goes first, and §5 says why.** The draft of this design had it the other way, on the
argument that the value model was the smaller problem. That confuses two questions. *What does
the tagged value cost?* can be answered at any baseline by building the counterfactual and
interleaving. *Did we get back to where we were?* can only be answered against the pre-design-25
point, and that point means something only while the code around it is otherwise unchanged.
Strip twenty-five per-record decisions out first and the same allocation reads as a larger share
of a smaller total, so the figure would move without the value moving. Percentages on a shifting
baseline do not compose.

## 2. The shape

A finding is a decision whose answer is fixed for the life of a compiled node or a run, computed
again inside a loop. The fix takes one of three forms.

- **A field on the compiled node.** The configuration's answer, resolved when the node compiles.
  `CompiledTemplate` already carries the compiled match, body, encoding and captures; it should
  carry the rest of what the match loop asks of `Template`.
- **A strategy bound at run start.** Where the answer needs something the run settles — the
  source encoding after a byte-order mark — the binding happens once when the run begins, as
  design 26 binds the function registry and design 25's seam pairs the sink with its encoding.
- **A pre-built structure.** A map where there is a scan, a parsed form where there is a parse,
  a pre-encoded array where there is an encode.

**What is not a finding.** A decision that depends on the record. `Splitter`'s one-byte fast
path asks a question about the data at the point it has the data. `Body`'s sort resolves keys
once per entry rather than per comparison, which is the same principle already applied. Guards
are evaluated on the way into a level rather than per pass, deliberately, because re-reading
them mid-level would compare a template against its own counter. The survey separated these and
they stay as they are.

## 3. The sites

Grouped by the fix rather than the file, with the phase each belongs to. Hotness is the loop the
site sits in.

### 3.1 The compiled node reads the authored model in the match loop — phase 1

The compiled form exists and the loop reads through it to `Template` anyway.

| Site | What is read | Hotness |
|---|---|---|
| `exec/Level.java:144` | `matchLimits().maxMatch()`, `consume()` | per template, per pass, per record |
| `exec/Level.java:236` | `matchLimits().onlyMatch()` null check, then a `Set` probe | per counting match |
| `exec/Level.java:570` | `template.match() instanceof MatchExpression.Delimiter` to pick the content group | per wanted match, per eater match |
| `exec/Level.java:508` | `template.guard() == null` per template, and a `boolean[]` allocated | per level entry |
| `exec/Level.java:223` | the first-match store clear walks the **authored** captures, tests `instanceof KeyValue`, and reaches each store by name | per template's first match |
| `exec/Body.java:1024` | a recursive apply walks the authored captures and shadows each by name | per recursive apply |

*Fix:* `int maxMatch`, `boolean consume`, `int contentGroup`, a bound predicate for `onlyMatch`,
a `boolean guarded`, and pre-built `Store[]` and `String[]` for the clear and the shadow, all on
`CompiledTemplate`. The match loop should not hold a `Template` reference at all.

### 3.2 A lookup by name or text where a reference would do — phases 1, 2 and 3

| Site | What is looked up | Hotness | Phase |
|---|---|---|---|
| `match/Steps.java:260` | the step's pattern, **by its text**, rebuilding `PatternKey` from the pattern string, its flags and the encoding | per regex step, per match attempt — the innermost loop of `progressive` | 2 |
| `exec/Body.java:1017` | the level, by the mode string, through a `HashMap` — under a comment reading "a compile-time fact read as a field" | per apply-templates | 1 |
| `exec/Body.java:939` | the target template, by name | per call-template | 3 |
| `exec/FunctionRuntime.java:112` | the bound call, by function name | per call-function | 3 |

*Fix:* the compiled op holds the thing. `CompiledOp.Apply` holds its `List<CompiledTemplate>`;
`CallTemplate` holds its target; the function call holds its binding; and the regex step holds
its `BytePattern`, which is phase 2's because there is nowhere to hold it until the steps have
a compiled form. `PatternKey`'s own javadoc admits keys are built at match time.

### 3.3 A per-run constant recomputed per record — phases 1, 2 and 3

| Site | What is recomputed | Hotness | Phase |
|---|---|---|---|
| `match/Steps.java:452` | `decode()` re-selects its decoder: two identity tests, `isUtf8Compatible()`, `isSingleByte()`, a twenty-arm switch, and for single-byte feeds a `ConcurrentHashMap.computeIfAbsent` for the table | **per character** in `TakeWhile` and `AnyChar` | 2 |
| `exec/Level.java:742` | `effective(candidate)` resolves the template's encoding against the run's | per match attempt, on every arm of `match()`, so failed attempts pay too | 1 |
| `value/TypedValue.java` factory | `encoding.isUtf8Compatible()` when a value is constructed | per group, per match | 1 |
| `exec/Output.java:42` | the write seam asks the value to convert, through calls E43 gave more than one implementation | per write | 1 |
| `exec/Body.java:441`, `:520` | `project().source().strictValues()`, `maxSequenceEntries()` | per arithmetic transform, per append | 3 |

*Fix:* a resolved `Encoding` and a value factory bound to each compiled template when the run's
encoding settles; a decoder strategy bound with them, held by the compiled step; two fields on
`Body` for the source's flags; and the seam holding what it needs so the common write is a field
read (E43's measured residue, §5 phase 1).

### 3.4 Work built eagerly for a path that rarely runs — phase 5

| Site | What is built | Hotness |
|---|---|---|
| `output/XmlByteSink.java:102` and four more; `output/SaxEventSink.java:71` and four more | the description string for a structural refusal that almost never fires, concatenated before the check | per element, attribute and namespace call |
| `exec/Body.java:221`; `exec/Run.java:193` | the same one level up: `"element '" + name + "'"` per structural write | per structural write |
| `output/XmlByteSink.java:405`; `output/SaxEventSink.java:292` | `new HashMap<>(parent.scope)` — the namespace scope copied for every element, including configurations that declare no namespaces | per element |
| `output/XmlByteSink.java:275` | the indent continuation built on every start tag, used only when a tag wraps | per start tag |

*Fix:* take the name, not the message, and build the message in the thrower; share the parent's
scope until an element declares something; hoist the continuation inside its own condition.

### 3.5 A parse or a scan that could have happened once — phases 3 and 5

| Site | What is redone | Hotness | Phase |
|---|---|---|---|
| `value/Transforms.java:436` | a fresh `ByteMatcher` allocated per `replace` call | per replace — `apache_httpd` runs 209 per record | 3 |
| `value/Transforms.java:447` | the replacement string's `$1`, `${name}` and `$$` syntax re-parsed character by character | per match of that replace | 3 |
| `exec/Body.java:244` | `ValueMap` linear-scans the authored entries with `String.equals`, then re-encodes the mapped literal | per value-map | 3 |
| `exec/Body.java:202` | `Switch` linear-scans its cases | per switch | 3 |
| `exec/Body.java:962` | call-template's unsupplied parameters: a stream `anyMatch` per declared parameter, then a string encoded to bytes | per call-template | 3 |
| `output/SaxEventSink.java:224` | the qualified name re-split for the element and for each attribute | per element, per attribute | 5 |
| `output/XmlByteSink.java:364` | tags and indents encoded to bytes on every write | per tag | 5 |
| `exec/Run.java:156` | the prologue re-finds the source template, re-walks its body for the apply directive, re-filters the roots and rebuilds the split | per run | 5 |

*Fix:* the matcher as a field of the compiled transform — which is design 10's change 1, landed
on the match side in August and never carried to the body side; a pre-parsed replacement; maps
of pre-encoded results for `ValueMap` and `Switch`; the defaults pre-encoded on the op;
pre-split names and pre-encoded tags for the sinks; the prologue's findings on `CompiledProject`.

### 3.6 Brought in from elsewhere — phases 2 and 4

Not from the survey, but the same idea, and each with a home that only exists once a phase here
makes one.

| Site | What is recomputed | Owner | Phase |
|---|---|---|---|
| `match/Steps.java:155` | a `Tag` step encodes its own text to bytes on every match attempt | design 10 §2, open | 2 |
| `match/Steps.java:183` | `TakeUntil` likewise | design 10 §2, open | 2 |
| `match/Steps.java:172`, `:433` | `TakeWhile` classifies each byte through a predicate switch, where a 256-entry table would answer | design 10 §2, open | 2 |
| `exec/Conditions.java:78` | a `matches` condition looks its pattern up **by text** on every evaluation | E39 | 4 |
| `exec/Conditions.java:109`, `:129`, `:140` | conditions resolve the authored `RefExpression` through `Refs` on every evaluation | E39 | 4 |
| `exec/Refs.java:85`, `:97` | literal text in a condition's reference re-encoded to UTF-8 per evaluation | E39 | 4 |
| `exec/Conditions.java:83` | a `matches` subject decoded to text and immediately re-encoded for the matcher | E39, recorded and accepted at design 27 §5.6 | 4 |

## 4. What this does not touch

- **The op interpreter, and why nothing is bought by changing it.** `Body.body` is one switch
  over `CompiledOp`, ruled deliberately at design 27 ruling 2. It is not an instance of this
  design's thesis: it switches over the *compiled* form, not over configuration, so there is no
  earlier moment at which its answer could be decided — an interpreter has to dispatch on the
  instruction in front of it. The alternative shape, ops that run themselves, replaces a
  `typeSwitch` over about twenty-five cases with an interface call over about twenty-five
  implementations, which is megamorphic and gives up inlining, so it would very likely be slower
  rather than faster; E43's own measurement is the local evidence that going from one
  implementation to three costs something on a hot path. What the arms *do* is a different
  matter, and every re-read of configuration inside them is in §3.2, §3.3 and §3.5 above.
- **Capture elimination (E10).** Ruled out here as a branch-pruning concern rather than a
  compile-time-decision one: it removes work instead of deciding it earlier, it is the
  prototype's optimiser deliberately unported, and it is deferred by D33. It stays E10's, and
  §5 phase 6 names it as the natural successor.
- **The step interpreter's *arms*** beyond what §3.6 lists. Compiling the steps (phase 2) gives
  each kind a home; whether every kind then earns a precomputed field is that phase's business
  and is measured there, not asserted here.

## 5. Phasing

Each phase is gated and audited before the next, measured as §6 says, and carries a named
workload chosen for the loop it touches — so a phase that should move a row and does not is as
interesting as one that regresses.

**Before phase 1 — E43, measured against the baseline it still has.** The tagged value takes the
shape design 25's own family already implies and Stroom's `Val` family uses: the conversions
implemented by each variant rather than switched over in the interface, and `Bytes` split into a
one-field UTF-8 variant, which is sixteen bytes, what the ported record cost, and a three-field
encoded one for everything else. Measured twice: interleaved against the head before it, which
says what the fix wins, and against `a9ca4f2853`, the commit before design 25, which says
whether the loss is recovered. The second comparison is why this comes first — it is clean now
and will not be after phase 1. If the fix does not recover it, that is worth knowing before the
phases make the question unanswerable.

*Built and measured 2026-09-08 (`f9bcef57af`), and it half worked, which is why it came first.
The fix wins `csv_header` +2.3% and `progressive` +1.8% against design 25 complete, the sign
holding in all three rounds. Against the commit before design 25, `apache_httpd`, `progressive`
and `win_sec_xml` are level or better and `csv_header` is still about two per cent down. So the
value's size is answered — under a UTF-8 feed it is now the shape the ported record had — and
something else on the delimiter row is not. E43 records it as a residue with two candidates, and
reading the fixture narrows them. The `csv_header` configuration is five delimiter templates
writing text; it opens no element, so §3.4's sink work is not in its loop at all. What is in its
loop is §3.1 — `effective()` and the content-group `instanceof` per field, the match-limit reads
per template per pass, a `boolean[]` per level entry — and §3.3's factory branch. *Those sites
were phase 2 when this was written; the phases were reordered because of it, and they are phase
1 now.* The other candidate is not a survey finding at all: `csv_header` is the write-heaviest
row in the corpus, five writes per field of which three are literals, and E43 turned every
write's conversion from a final class's method into an interface call over six implementations.
That it is the one row not to recover is consistent with the dispatch, and the survey cannot
explain it because it is not a configuration decision. The dispatch was then tested on its own:
a variant giving the write seam a monomorphic fast path reads `csv_header` +1.2% against the
head, the sign in all three rounds, and `apache_httpd` level. So about half of the residue is
the dispatch and the rest is inside the noise. The variant is not committed — buying
monomorphism with a special case in the seam is a shape decision, and the seam is phase 1's to
rebuild, so it goes in there or not at all (E43, 2026-09-08).*

**Phase 1 — the match loop and the write seam.** *Workload:* `csv_header`, `win_sec`. §3.1 in
full; §3.2's mode lookup; §3.3's effective encoding, value factory and write seam. After this
the match loop holds no `Template` reference, the run's encoding is resolved once, and the
common write is a field read rather than a call the compiler must profile. **First, not the
hottest**, because it is where `csv_header`'s live residue is: that row was −3.8% at design 25,
is −2.0% after E43, and about half of what is left is the seam. This phase should close it, and
if it does not, the accounting is wrong somewhere and that is worth knowing before four more
phases.

**Phase 2 — the compiled step.** *Workload:* `progressive`, `regex_lines`. A compiled form for
the twenty-four step kinds and the four combinators, so that a step can hold what compiling knew:
§3.2's pattern, §3.3's decoder, and §3.6's encoded tag text and byte table. Today
`CompiledMatch.Progressive` holds the authored steps and its javadoc says "there is nothing to
compile them into"; design 10 §2's four open step rows say otherwise, and phase 1 of the first
draft tried to fix two of them inside an interpreter with nowhere to put the answers. **This
phase needs its own ruling** (§7.2): design 27 ruling 2 chose one interpreter over sealed records
for the *ops*, and the same choice was never made or measured for *steps*. The distinction this
design rests on is that the ops switch is over compiled forms and the steps switch is over the
authored model — but that is an argument to be ruled on, not assumed.

**Phase 3 — the body's ops.** *Workload:* `apache_httpd`. §3.5's replace matcher and replacement
parse, the `ValueMap` and `Switch` scans, call-template's parameter defaults; §3.2's template and
function lookups; §3.3's two source flags. The largest count of sites and the smallest each.

**Phase 4 — conditions.** *Workload:* `win_sec_strict`, `ausearch`. §3.6's E39 rows: the
authored expression compiled, the `matches` pattern held rather than looked up by text, and the
decode-then-re-encode of its subject removed. Design 27 ruling 7 kept both resolvers until
compiling conditions is *measured* to matter, so this phase opens with that measurement rather
than assuming it; if the probe says the conditions path does not matter, the phase closes E39
as measured-and-accepted instead of building it. Either outcome is a result.

**Phase 5 — the sinks and the prologue.** *Workload:* `win_sec_xml`. §3.4 in full, §3.5's
remaining rows. The only phase whose sites are absent from the delimiter and text workloads
entirely, which is why it is last of the building phases.

**Phase 6 — the record.** E12 pointed at what was measured; design 10 §2's table updated for the
rows phases 2 and 4 close; E39 closed or narrowed by phase 4's own answer; E43 closed or
restated, its residue having been phase 1's to explain; E10 named as the successor.

## 6. The gate, and the method

The suites as always. The measurement is where this design differs, because the design it
follows got it wrong: design 25 was probed phase by phase against adjacent commits on a subset
of rows, each probe read overlapping intervals as neutrality, and the full suite then found a
cost none of them had seen.

So, for every phase here:

- **The full suite**, all eight workloads, run and compile rows, at the benchmark's own
  fidelity, before any claim about a regression.
- **Interleaved**, alternating the two commits round by round and taking the sign across
  rounds, because measuring points in sequence lets the box's drift masquerade as a delta — it
  did on the night of 2026-09-07, and made the middle of three points look good on both sides.
- **The phase's named workload** read first, and its absence of movement treated as a finding.

Every file under `design/benchmarks`, named as the README there says.

## 7. Questions for the ruling (D51)

1. **Scope.** All five building phases, or phases 1 to 3 only? *Recommended: all five.* Phases 4
   and 5 are the same idea, and leaving them makes the record say the graph decides when it half
   does. Phase 4 may close itself on its own measurement, which costs a probe rather than a
   phase.
2. **The compiled step (phase 2).** Compile the steps into their own form, or keep fixing sites
   inside the interpreter over the authored model? *Recommended: compile them.* Two of the
   survey's hottest sites and two of design 10 §2's open rows all need somewhere to put an
   answer, and there is nowhere. The counter-argument is design 27 ruling 2, which chose one
   interpreter for the ops; the reply is that ops switch over compiled forms and steps over the
   model, but this is the ruling's to settle.
3. **Order.** Phase 1 first as written, or hottest first as the draft had it? *Recommended: as
   written.* `csv_header`'s residue is live and unexplained, and phase 1 is what should explain
   it; an unanswered figure ages badly, as design 25's did.
4. **E39 (phase 4).** Brought in here, or left as its own entry? *Recommended: here*, with its
   measurement as the phase's opening act. Its capture half went to design 25 phase 3 by exactly
   this design's argument, and splitting one entry across two designs is how a row goes
   unmeasured.

## 8. Record

*To be written as built: the D-number, the commits, what each phase measured, and anything the
survey said that turned out to be wrong.*
