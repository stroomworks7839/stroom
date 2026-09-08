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

Grouped by the fix rather than the file. Hotness is the loop the site sits in.

### 3.1 The compiled node reads the authored model in the match loop

The compiled form exists and the loop reads through it to `Template` anyway.

| Site | What is read | Hotness |
|---|---|---|
| `exec/Level.java:144` | `matchLimits().maxMatch()`, `consume()` | per template, per pass, per record |
| `exec/Level.java:236` | `matchLimits().onlyMatch()` null check, then a `Set` probe | per counting match |
| `exec/Level.java:570` | `template.match() instanceof MatchExpression.Delimiter` to pick the content group | per wanted match, per eater match |
| `exec/Level.java:508` | `template.guard() == null` per template, and a `boolean[]` allocated | per level entry |
| `exec/Level.java:223` | the first-match store clear walks the **authored** captures, tests `instanceof KeyValue`, and reaches each store by name | per template's first match |
| `exec/Body.java:1024` | a recursive apply walks the authored captures and shadows each by name | per recursive apply |

*Fix:* `int maxMatch`, `boolean consume`, `int contentGroup`, a bound `IntPredicate` for
`onlyMatch`, a `boolean guarded`, and pre-built `Store[]` and `String[]` for the clear and the
shadow, all on `CompiledTemplate`. The match loop should not hold a `Template` reference at all.

### 3.2 A lookup by name or text where a reference would do

| Site | What is looked up | Hotness |
|---|---|---|
| `match/Steps.java:260` | the step's pattern, **by its text**, rebuilding `PatternKey` from the pattern string, its flags and the encoding | per regex step, per match attempt — the innermost loop of `progressive` |
| `exec/Body.java:1017` | the level, by the mode string, through a `HashMap` — under a comment reading "a compile-time fact read as a field" | per apply-templates |
| `exec/Body.java:939` | the target template, by name | per call-template |
| `exec/FunctionRuntime.java:112` | the bound call, by function name | per call-function |

*Fix:* the compiled op holds the thing. `MatchStep.Regex`'s compiled form holds its
`BytePattern`; `CompiledOp.Apply` holds its `List<CompiledTemplate>`; `CallTemplate` holds its
target; the function call holds its binding. `PatternKey`'s own javadoc admits keys are built at
match time; after this they are built only at compile time.

### 3.3 A per-run constant recomputed per record

| Site | What is recomputed | Hotness |
|---|---|---|
| `match/Steps.java:452` | `decode()` re-selects its decoder: two identity tests, `isUtf8Compatible()`, `isSingleByte()`, a twenty-arm switch, and for single-byte feeds a `ConcurrentHashMap.computeIfAbsent` for the table | **per character** in `TakeWhile` and `AnyChar` |
| `exec/Level.java:742` | `effective(candidate)` resolves the template's encoding against the run's | per match attempt, on every arm of `match()`, so failed attempts pay too |
| `value/TypedValue.java:59` | `encoding.isUtf8Compatible()` when a `Bytes` is constructed | per group, per match |
| `value/TypedValue.java:189` | `bytes(target)` re-tests the target's class | per write |
| `exec/Body.java:441`, `:520` | `project().source().strictValues()`, `maxSequenceEntries()` | per arithmetic transform, per append |

*Fix:* a decoder strategy and a resolved `Encoding` bound per template when the run's encoding
settles; the value factory bound with them, which is the question that began this survey; two
fields on `Body` for the source's two flags.

### 3.4 Work built eagerly for a path that rarely runs

| Site | What is built | Hotness |
|---|---|---|
| `output/XmlByteSink.java:102` and four more; `output/SaxEventSink.java:71` and four more | the description string for a structural refusal that almost never fires, concatenated before the check | per element, attribute and namespace call |
| `exec/Body.java:221`; `exec/Run.java:193` | the same one level up: `"element '" + name + "'"` per structural write | per structural write |
| `output/XmlByteSink.java:405`; `output/SaxEventSink.java:292` | `new HashMap<>(parent.scope)` — the namespace scope copied for every element, including configurations that declare no namespaces | per element |
| `output/XmlByteSink.java:275` | the indent continuation built on every start tag, used only when a tag wraps | per start tag |

*Fix:* take the name, not the message, and build the message in the thrower; share the parent's
scope until an element declares something; hoist the continuation inside its own condition.

### 3.5 A parse or a scan that could have happened once

| Site | What is redone | Hotness |
|---|---|---|
| `value/Transforms.java:436` | a fresh `ByteMatcher` allocated per `replace` call | per replace — `apache_httpd` runs 209 per record |
| `value/Transforms.java:447` | the replacement string's `$1`, `${name}` and `$$` syntax re-parsed character by character | per match of that replace |
| `exec/Body.java:244` | `ValueMap` linear-scans the authored entries with `String.equals`, then re-encodes the mapped literal | per value-map |
| `exec/Body.java:202` | `Switch` linear-scans its cases | per switch |
| `exec/Body.java:962` | call-template's unsupplied parameters: a stream `anyMatch` per declared parameter, then a string encoded to bytes | per call-template |
| `output/SaxEventSink.java:224` | the qualified name re-split for the element and for each attribute | per element, per attribute |
| `output/XmlByteSink.java:364` | tags and indents encoded to bytes on every write | per tag |
| `exec/Run.java:156` | the prologue re-finds the source template, re-walks its body for the apply directive, re-filters the roots and rebuilds the split | per run |

*Fix:* the matcher as a field of the compiled transform — which is design 10's change 1,
landed on the match side in August and never carried to the body side; a pre-parsed
replacement; maps of pre-encoded results for `ValueMap` and `Switch`; the defaults pre-encoded
on the op; pre-split names and pre-encoded tags for the sinks; the prologue's findings on
`CompiledProject`.

## 4. What this does not touch

- **The op interpreter.** One switch over compiled ops is design 27 ruling 2, ruled
  deliberately. Nothing here reopens it.
- **The step interpreter's shape.** `CompiledMatch.Progressive` holds authored `MatchStep`s and
  its javadoc says there is nothing to compile them into, which design 10 §2's four open step
  rows contradict. A compiled step form is a design of its own; §3.2 and §3.3 fix the two
  hottest sites *inside* the existing interpreter and leave the shape alone.
- **E39.** Conditions resolving authored expressions, and a `matches` condition looking its
  pattern up by text, are the same shape and already owned, with their own measurement.
- **E43.** The tagged value's size, and whether the encoding moves into the class. §3.3 binds
  the *factory*; which classes it makes is E43's, and E43 lands before phase 1 (§5). The two
  compose: E43 takes a field off the value, §3.3 takes the branch off the factory.
- **E10.** Capture elimination is the deferred optimiser and would remove work rather than
  decide it earlier.
- **Design 10's step rows.** `Tag` and `TakeUntil` re-encoding their text per attempt, and
  `TakeWhile`'s missing byte table, are listed there and confirmed still live.

## 5. Phasing

Each phase is gated and audited before the next, and each is measured as §6 says.

**Before phase 1 — E43, measured against the baseline it still has.** The tagged value takes
the shape design 25's own family already implies and Stroom's `Val` family uses: the conversions
implemented by each variant rather than switched over in the interface, and `Bytes` split into a
one-field UTF-8 variant, which is sixteen bytes, what the ported record cost, and a three-field
encoded one for everything else. Measured twice: interleaved against the head before it, which
says what the fix wins, and against `a9ca4f2853`, the commit before design 25, which says
whether the loss is recovered. The second comparison is why this comes first — it is clean now
and will not be after phase 1. If the fix does not recover it, that is worth knowing before four
phases of other work make the question unanswerable.

**Phase 1 — the innermost loops.** §3.2's pattern lookup and §3.3's decoder, both in the step
interpreter; §3.5's matcher and replacement parse in the body's replace. The four hottest sites
in the survey, and the ones with a measurable workload each: `progressive` for the first two,
`apache_httpd` for the second two.

**Phase 2 — the match loop.** §3.1 in full, plus §3.3's effective encoding and value factory
bound when the run's encoding settles. After this the match loop reads no `Template`.

**Phase 3 — the references.** §3.2's remaining three lookups: the mode, the template name and
the function name resolved into their compiled ops.

**Phase 4 — the sinks and the prologue.** §3.4 in full, §3.5's remaining rows.

**Phase 5 — the record.** E12 pointed at what was measured; design 10 §2's table updated for
the rows this closes; E43 closed or restated, depending on what its own measurement said before
phase 1.

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
- **A named workload per phase**, chosen for the loop the phase touches, so a phase that
  should move a row and does not is as interesting as one that regresses.

Every file under `design/benchmarks`, named as the README there says.

## 7. Questions for the ruling (D51)

1. **Scope.** All four phases, or phases 1 and 2 only? *Recommended: all four.* Phases 3 and 4
   are the same idea and smaller, and leaving them makes the record say the graph decides when
   it half does.
2. **The step interpreter.** Fix the two hot sites inside it (§3.2, §3.3), or compile the steps
   into their own form now? *Recommended: fix inside.* A compiled step form is design 10's four
   open rows plus a shape ruling of its own, and it should be measured against a clean baseline
   rather than tangled with this.
3. **E43's place.** Before phase 1 as §5 now has it, or after the hot phases? *Recommended:
   before*, because the pre-design-25 baseline is the only reference that answers "did we
   recover it" and it stops meaning that as soon as anything else moves. The factory *binding*
   stays in phase 2 either way: E43 takes a field off the value, §3.3 takes the branch off the
   factory, and neither needs the other.
4. **The order of phases 1 and 2.** Hottest first as written, or the match loop first because
   it is the largest single group? *Recommended: hottest first*, so the earliest measurement is
   the one most likely to show something.

## 8. Record

*To be written as built: the D-number, the commits, what each phase measured, and anything the
survey said that turned out to be wrong.*
