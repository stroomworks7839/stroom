# Engine semantics

The write-up of the semantics discussion of 2026-08-20, which E16 forced and the atoms question
crystallised: what the engine's matching, dispatch and transformation layers *mean*, where the
ported behaviour diverges from the DS3 it descends from, and the decisions (D34) that resolve
the divergence. The evidence is read from Java Stroom's own DS3
(`stroom-pipeline/src/main/java/stroom/pipeline/xml/converter/ds3/DS3Parser.java`), not from
anybody's memory of it.

The engine is a streaming parser and transformer: match content against a cursor, bind captures
to variables, run template bodies that write output or hand content down to further templates —
deconstruct, then reconstruct. Everything below is about which guarantees hold at which layer.

---

## 1. The model: three regimes

| Regime | Semantics | Why |
|---|---|---|
| **Within a match** | Full backtracking, leftmost-first — the tiered regex engines | Expressiveness. Bounded by the record, and the linear-time simulation caps the cost, so catastrophic behaviour is unrepresentable |
| **Between constructs at a level** | Iterated ordered choice with rewind — `(A\|B\|C)*` | DS3's model. A failed alternative consumes nothing; order expresses priority; each match commits and the choice re-opens |
| **Across emitted output** | Commitment — a consumed match is never reconsidered | Streaming. Output cannot be unwritten, so no semantics that requires revisiting it can stream |

The middle row is the one everything hinged on. The bottom row is the one DS3, PEG and the
progressive step layer all already agree on, and it is what makes the whole thing streamable.

**A clarification this discussion forced: atoms are semantics-neutral.** `Tag`, `TakeWhile`,
`ReadNumeric` answer "do I match here, and how far". Whether a composition of them backtracks
is a property of the *driver*, not the atoms — the regex library's `comb` layer compiles the
same vocabulary into the HIR and gets full backtracking from the tiered engines, while the step
interpreter drives the same atoms with PEG commitment. Phase 5's claim that the atoms "could
not be used" was wrong, and E14 is reworded accordingly: the question was never whether, but
with which driver semantics.

---

## 2. What DS3 actually does

`DS3Parser.java:184`, the dispatch loop, abridged:

```java
while (advance > 0) {
    advance = 0;
    for (final Node node : parent.getChildNodes()) {
        if (node.isExpression() && advance <= 0) {
            advance = processExpression(...);   // first match wins this pass
        }
    }
}
```

Each pass tries the level's expressions **in order from the first**; the first that matches
consumes one match; the next pass starts again from the first expression. A level is
`(A|B|C)*`. A maxed-out or non-matching expression eats nothing and the next is tried — which
is the behaviour DS3 authors write against: choices tried from the same start position until
one matches.

Around that loop, three more deliberate behaviours (`processExpression`):

- **A match that skips content is an error.** In the default `matchOrder="sequence"`, a match
  starting anywhere but position 0 logs *"Expression failed to match from the start of the
  content"* with the skipped text — unless `ignoreErrors`. The design intent: no content is
  ever dropped silently, and authors are pushed toward start-anchored expressions, which are
  also the cheap ones — an anchored failure costs the prefix comparison, an unanchored failure
  costs a scan of the whole buffer.
- **`matchOrder="any"` excises instead.** The matched span is removed from the buffer
  (`buffer.remove(start, end)`) and the skipped prefix *survives* for other expressions. A
  genuinely order-insensitive mode.
- **Unconsumed content is reported per level, once** — *"expressions failed to match all of the
  content"* — when a whole pass matches nothing and content remains. Not per expression.

---

## 3. Where ds-rs diverged, and the port inherited

ds-rs designed its dispatch differently — its own design document draws the shape explicitly
(`docs/ds3_design_document.md`, the "Outer Root Loop" diagram): each expression is exhausted
before the next is tried, one pass, never returning. The migration code's comment claims this
"matches DS3's shared input cursor semantics". It does not.

| Behaviour | Real DS3 | ds-rs, and this port |
|---|---|---|
| Dispatch at a level | `(A\|B\|C)*` — first match wins each pass, restart from the first | `A* B* C*` — exhaust each template, one pass |
| Skipped prefix on a match | ERROR with the skipped text (`sequence`), or excised (`any`) | Consumed silently |
| Unconsumed content | Reported once per level | Reported per template |
| `matchOrder` | Parsed, two modes | Silently dropped by the importer |

The observed symptoms, previously treated as separate issues, are all this one substitution:

- **E1** — the `maxMatch` false-positive warning. Under `(A|B|C)*` it cannot occur: the pass
  after the header hits its limit skips it and the body split consumes; the only report is
  per-level, when nothing matched at all. The warning is an artifact of `A*B*C*` plus
  per-template reporting.
- **E16** — `win_sec`'s template order. First reframed as "written for a model in which order
  cannot strand content" — and that reframe was itself overstated, caught when the fixture
  fixes were questioned. Under `(A|B|C)*` a pass is won by *list* order, not buffer position:
  an earlier-listed unanchored expression matching later still beats a later-listed one
  matching earlier, and the skip is consumed. Real DS3 strands the same blocks — it just
  *reports* them, loudly, at authoring time. So the configuration was wrong under both models,
  the reorder is the fix under both, and the engine's contribution was the silence. True
  order-insensitivity exists only as `matchOrder="any"` excision (E18, deferred).
- **The `005`/`014` `.err` divergences** — Stroom reports per level at ERROR where this engine
  reports per template at WARNING. Same substitution, message-shaped.

---

## 4. Decisions (D34)

**1. Dispatch is `(A|B|C)*`.** The templates of a level (a mode, in engine terms) are tried in
order at each position; the first match wins; the choice re-opens after every match. This is
what every existing configuration was written against, it dissolves E1's false-positive class,
and it changes nothing for the single-template case. It is also XSLT's instinct — one template
chosen per item, not one template exhausted — so the model the engine borrowed its vocabulary
from agrees. Explicit dispatch variants can be added later if a configuration needs them;
none so far does.

**2. Skipping is reported, never silent.** A match that starts past the cursor reports the
skipped content (suppressed by `ignoreErrors`), and unconsumed content is reported once per
level, aligning severity and shape with Stroom's own messages. The rationale is DS3's,
deliberately kept: it prevents silent data loss, and it pushes configurations toward
start-anchored expressions — which also opens an engine optimisation, since anchored-first
matching is the cheap path and the scan is only needed to *describe* a skip, not to find one.
`matchOrder="any"` (excision) exists in DS3, is used by no corpus configuration, and is
deferred until a real one needs it (E18).

**3. The step layer keeps PEG commitment, documented rather than accidental.** The typical use
is chained deconstruction — match, bind to a variable, apply a further pattern set to the
variable, reassemble with concatenation and references — which needs no reconsideration across
constructs. Ordered choice with rewind at every level plus full backtracking within each
pattern covers the corpus and every configuration style seen. If a configuration one day needs
regex-style reconsideration across steps, that becomes an explicit option, not a silent
default. Lowering the textual subset of steps onto `comb` is now purely an optimisation
question (E14) and waits until the dispatch change (E17) has landed.

---

## 5. What implementing this touches (E17)

The change is confined to the executor's dispatch loop and its reporting:

- `Executor.template`/`apply`: per-pass alternation over the level's templates instead of
  per-template exhaustion; match limits and `onlyMatch` guards keep their meanings (they are
  per-expression counts in DS3 too).
- Unconsumed reporting moves from per-template to per-level; a new skipped-prefix report is
  added per match; both gated on `ignoreErrors`.
- The message goldens that encode the false-positive class (`001`, `003`, `011`, `012`, and
  the `005`/`014` severities) will change — regenerated *with review*, per the freeze rule, and
  compared against Stroom's `.err` files, which should now agree in shape as well as substance.
- Output goldens are expected to survive unchanged; the fixture ratchet adjudicates, and any
  output that does change names precisely the configuration that depended on `A*B*C*`.
- E1 closes when this lands. `win_sec`'s reorder stays; `win_sec_xml` (E16's open half) gets
  re-diagnosed under the new dispatch before any further fix.

This is the first deliberate behavioural departure from ds-rs since the port completed, which
is exactly the sequence D33 prescribed: port faithfully first, so that every departure after it
is a decision with a diff, not a drift.

**Implemented 2026-08-20.** Every output golden survived the dispatch change, as predicted —
with one exception the ratchet caught immediately: fixture `018` depends on a DS3 subtlety the
first cut got wrong. Its onlyMatch guard reads the *parent's* match counter, and a guard
re-evaluated mid-level reads the last winner's counter instead; DS3 avoids this by passing the
parent's count down as a parameter, so guards are now evaluated once at level entry, while the
scope still describes the parent. Regenerating the message goldens taught two refinements, both
read off Stroom's own `.err` evidence: the level's unmatched-content report stands down when a
minimum-match error already explains the same failure (005 reports, 014 does not — exactly
Stroom's record for each), and `ignoreErrors` *inherits* down the dispatch tree, which is how
the root flag silences an inner group's report (012). The new goldens: `001`, `011`, `012`
empty; `003` down from nineteen false warnings to eleven true errors naming genuinely unparsed
audit lines; `005` and `014` matching Stroom's messages in count, severity and substance. E1
and E2 close with this. The pre-fix `win_sec` configuration runs as the skip-report acceptance
test, and one more of its defects surfaced on the way: it set `ignore_errors` at the source — a
flag ds-rs never enforced, so the author asked for silence and got it by accident. Under D34
the flag works.
