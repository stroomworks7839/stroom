# Design 28 — The model classifies its instructions: holders, bindings, transforms, leaves

*Proposed 2026-09-06, from design 27's exit review (§5.6). Ruled the same day by Jon (D47),
every question in §5 as recommended: four classifications, the `Regexed` marker, `Containers`
goes, `BodyCompiler`'s switch stays.*

`OutputNode` is a sealed interface with fifty-four records and no structure between it and them.
Every walk that needs to know something about an instruction — does it hold bodies, does it bind
a name, does it carry a pattern — either enumerates all fifty-four or ends in a `default` arm,
and design 27's audits found the `default` arm wrong twice. This design gives the model the
three classifications its walks keep re-deriving, as sealed sub-interfaces. A sub-interface is a
classification, not a layer: the model stays the model and the graph stays the graph (D35).

## 1. Where it stands

The walks over a body, at `47afab83ba`:

| Walk | Arms | What it asks of each instruction | How it asks |
|---|---|---|---|
| `Containers.bodies` | 54 | which bodies it holds | exhaustive switch, 46 leaf arms returning nothing |
| `StructureCheck.producesContent` | 54 | whether it writes to the output | exhaustive switch; 37 arms are `value.name() == null` |
| `ReferenceCheck.visit` | 54 | what it reads and what it binds | exhaustive switch; 27 arms are `transform(value.select(), value.name())`, and `ValueMap`'s single-select variant |
| `BodyCompiler.compile` | 54 | its compiled form | exhaustive switch, one function per arm |
| `MatchCompiler.collect` | 3 + `default` | the patterns it carries | a `default` arm, the walk through `Containers` |
| `TemplateUses.collectUses` | 2 + `default` | the templates it names | a `default` arm, the walk through `Containers` |

The first four are exhaustive, which is what closed the bodies axis after the phase 1 audits
found two walks stopping short of an iteration's body. The price is that three of them say the
same thing fifty-four times: `Containers` that forty-six instructions hold nothing;
`producesContent` that thirty-seven write when unnamed and bind when named; `ReferenceCheck`
that twenty-seven read their selects and bind their name. `BodyCompiler`'s length is the
vocabulary's and is not this design's concern: each arm is a different function.

The last two are the open case. A `default` arm cannot tell a leaf from a container the author
forgot, which `Containers` fixed for bodies; it equally cannot tell a leaf that carries a pattern
from one that does not. Today one instruction does, `Replace`, and `MatchCompiler.collect`
names it. An instruction added tomorrow with a regex of its own would be interned by nobody and
die at run time with "Pattern was not compiled", the audits' bug class on the other axis.

The compiled side already has the abstraction the model lacks: `CompiledOp.Transform` is one
record for twenty-six of the select-and-name instructions, and `Body` switches over twenty-five
compiled forms, not fifty-four. The asymmetry is the tell. The model's records are the
language's vocabulary and stay one per instruction, because the JSON tags and the docs are per
instruction; what they lack is a word for what they have in common.

## 2. The shape

```java
public sealed interface OutputNode permits OutputNode.Holder, OutputNode.Binding, OutputNode.Leaf {

    /** An instruction that holds bodies, walked in written order. */
    sealed interface Holder extends OutputNode permits If, Choose, Switch, Variable, Element, Attribute, ForEach, ForEachGroup {
        List<List<OutputNode>> bodies();
    }

    /** An instruction that may bind a name instead of, or as well as, writing. */
    sealed interface Binding extends OutputNode permits Transform, Variable, Sequence, Key, KeyGet, Count, Sum, Avg, Min, Max, DistinctValues, ValueMap {
        String name();
        /** Whether this instruction binds rather than writes; the transforms bind when named. */
        default boolean binds() { return name() != null; }
    }

    /** A transform: one or more selected values in, one value out, written or bound. */
    sealed interface Transform extends Binding permits Translate, StringJoin, Replace, LowerCase, … , FormatDate {
        List<RefExpression> select();
    }

    /** Everything else: writes, reads or declares, and holds nothing. */
    sealed interface Leaf extends OutputNode permits Text, ValueOf, EmitError, ApplyTemplates, CallTemplate, Namespace, Append {
    }

    /** An instruction whose text may be a regular expression the compiler interns. */
    interface Regexed {
        String pattern();
        boolean isRegex();
    }
}
```

Three things to note about it.

**A record may sit in two classifications.** `Variable` holds a body and binds a name; it is a
`Holder` and a `Binding`, listed in both `permits` clauses. Java allows it, and a switch over
`Holder`, `Binding`, `Leaf` is still exhaustive: the first matching arm wins, and the order in the
switch says which classification a walk cares about. `Variable`, `Sequence` and `Key` require
their name; `binds()` is true for them by the same rule and the constructors already refuse a
null. `ValueMap` is a binding with a single select and its own shape, not a transform; `Call` and
`Tokenize` are transforms, a select list and a name being the shape the reference check reads.

**`Regexed` is a marker, not a classification.** It does not extend `OutputNode` and is not in
any `permits` clause; `Replace` implements it beside `Transform`. The pattern-collecting walk
asks `node instanceof Regexed r && r.isRegex()` and needs no switch and no `default`: a new
instruction with a regex declares it by implementing the marker, and one that forgets is a
compile-time gap in the same place the model already declares everything else about it — the
record's `implements` clause. That is as far as the patterns axis can be closed without a
per-instruction switch, which is the shape being removed.

**Nothing moves out of the model, nothing is added between the layers.** The records keep their
fields, their constructors and their JSON tags. The families in `config.json` do not change.
`CompiledOp` does not change. The graph does not change.

## 3. What each walk becomes

| Walk | After | Arms |
|---|---|---|
| `Containers.bodies` | `node instanceof Holder h ? h.bodies() : List.of()` — or the class goes and the two callers ask the model directly | 0 |
| `StructureCheck.body` | explicit arms for `Element`, `Attribute`, `Namespace`, `Variable` as today; `case Holder h` walks `h.bodies()`; `case Binding b` seen if `!b.binds()`; `case Leaf` per leaf | ~12 |
| `StructureCheck.producesContent` | folds into `body`'s arms above; the method goes | — |
| `ReferenceCheck.visit` | `case Substring`, `case ParseDate`, `case ValueMap` and the rest of the special arms first, then `case Transform t -> transform(t.select(), t.name())`; holders, bindings and leaves as today | ~26 |
| `BodyCompiler.compile` | unchanged: one function per instruction | 54 |
| `MatchCompiler.collect` | `Regexed` test, `If`/`Choose` conditions, then `Holder` bodies; no `default` | 3 |
| `TemplateUses.collectUses` | `CallTemplate`, `ApplyTemplates`, then `Holder` bodies; `case Binding, Leaf -> nothing` named, not defaulted | 4 |

The `Holder.bodies()` order is the written order `Containers` already returns (Choose's branches
then its otherwise; Switch's cases then its default), and `StructureCheck`'s content-seen flag
threads through it as it does today.

## 4. The gate, and the cost

Structure only: no golden moves, no message changes. The gate is the three suites (engine,
pipeline, app), `StructureTest` and `ReferenceCheckBindingsTest` in particular, which pin every
binding instruction and every structure rule, and the compile rows of `EngineBenchmark`.
`Containers`' exhaustive switch took `csv_header`'s phase 1 compile-row gain from 9–12% to
+6.0% and the price was accepted; an interface call in its place is a different shape and is
measured, not assumed.

What it costs: one `implements` clause on each of fifty-four records, five `permits` lists that
must name every record (which is the point: a record not in one is a compile error), and the
walks' arms rewritten as §3 says. One commit for the model, one per walk, each gated.

What it does not do: it does not shrink `BodyCompiler`, and it does not touch the JSON reader,
whose per-tag switch is the format's, not the model's.

## 5. Questions for the ruling (D47)

1. **Four classifications or three.** As drawn: `Holder`, `Binding`, `Transform` (a `Binding`
   with selects), `Leaf`. Without `Transform`, `ReferenceCheck`'s twenty-seven arms stay.
   *Recommended: four.*
2. **The `Regexed` marker.** Closes the patterns axis for the price of one interface that only
   `Replace` implements today. *Recommended: yes; it is the closure the audits asked for.*
3. **`Containers` stays or goes.** With `Holder.bodies()` on the model, the class is a
   one-line wrapper. *Recommended: it goes; its javadoc's lesson moves to `Holder`.*
4. **`BodyCompiler`'s switch stays a switch.** A table from record class to compile function
   would hide the compile shape behind a map. *Recommended: it stays.*

## 6. Record

*Built 2026-09-06 (D47), five commits: the model (`d8fa1782a2`), then one per walk — the
structure check (`6c87ee08e6`), the reference check (`1354cc6d78`), the pattern walk
(`c33c21a6ed`), the uses walk, `Containers`' last caller (`f04469a3d5`). Not each gated, as §4
asked: the model commit deleted `Containers.java` while three walks still called it, so it and
the three after it do not build alone; the five were gated together at the last.*

*As built.* Every record declares its classification in its `implements` clause, as §2 draws
it but for two: `Call` and `Tokenize` are transforms, having a select list and a name, which is
the shape the reference check reads. `EveryVariantTest`, which walks the sealed hierarchy to
prove one of everything round-trips, now sees through the sub-interfaces to the records. The
walks are in §3's shape: `StructureCheck.body` has twelve arms and `producesContent` is gone;
`ReferenceCheck.visit` has one `Transform` arm for twenty-seven; `MatchCompiler.collect`, five
arms, asks the `Regexed` marker and has no `default`; `TemplateUses.collectUses`, five arms,
names the bindings and leaves it passes. `BodyCompiler`'s switch is untouched.

*Gate.* Engine 566, pipeline 154, app 5, xmlbench compiles, fresh results. Compile rows, three
forks, against `3369ab21cc` (the commit before `BodyCompiler`, so the two compile-path changes
are measured together; both files under `design/benchmarks`, `…-compile-rows.json`):

| Row | Δ |
|---|---|
| csv_header | +12.3% |
| progressive | +5.4% |
| regex_lines | +1.2% |
| win_sec_strict, win_sec_xml | +0.4%, −0.1% |
| apache_httpd, ausearch, win_sec | −1.4%, −1.5%, −1.8% (inside one interval) |

The `csv_header` row recovers what `Containers`' exhaustive switch took from the phase 1 gain
and more; the `regex_lines` −3.2% the `BodyCompiler` probe had shown on two forks is +1.2% here
against the same base. No regression.

*Audited 2026-09-07, one reviewer over the code since the phase 8 audit (D46, E40, the typed
readers, `BodyCompiler`, this design), one over the documents; both read-only. Code: a class
javadoc in `Compiler` still linked `CompiledOp#compile`; `Variable`'s constructor did not refuse
a missing name, which `Binding`'s javadoc and §2 said it did — it does now, as `Sequence` and
`Key` do, and the reader required the name already; four parameter wraps left at their old
column by the `BodyCompiler` move; four lines over 100 columns; two issue tags carrying a ruling
date. Everything else verified: the fifty-four records placed once each, the acceptance of
`StructureCheck.body` identical to `producesContent` for every record, the twenty-seven removed
arms each exactly the transform call, the three splices and the SAX call byte-identical under
`Utf8.Carry` and `SaxEvents`, the `BodyCompiler` move a move. Accepted notes: booleans still
read through Jackson's `asBoolean`, and `readDispatch`/`readCast` take an optional node's text
unchecked, so a number there is refused by the enum's message rather than a shape's; the
optional text readers' refusal names the field without its owner; the reserved-mode refusal
fires only when a `template_ref` to the name exists, so a hand-spelt `__rec_` mode with no
reference is left alone — the two xmlbench challengers do exactly that and keep their recursive
scope; a `template_ref` to a name several templates share dispatches to the first, silently, as
`templatesByName` is first-wins — both moot under D48. Documents: the record above claimed a
commit per walk each gated and a hash the amend had replaced; §2's sketch had `Call` and
`Tokenize` as bindings; §1 counted twenty-eight arms and thirty compiled transforms for
twenty-seven and twenty-six; §4 and this record misread phase 1's `csv_header` figure as the
helper's cost rather than the gain that survived it; design 27's `BodyCompiler` line count and
its `StructureCheck` row, D46's reading of D11 and D47's spelling of D35's artifacts. The
`BodyCompiler` probe's numbers in design 27 §5.6 stand on no filed run; this gate supersedes
them.*
