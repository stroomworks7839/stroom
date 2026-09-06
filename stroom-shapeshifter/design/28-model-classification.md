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
| `ReferenceCheck.visit` | 54 | what it reads and what it binds | exhaustive switch; 28 arms are `transform(value.select(), value.name())` |
| `BodyCompiler.compile` | 54 | its compiled form | exhaustive switch, one function per arm |
| `MatchCompiler.collect` | 3 + `default` | the patterns it carries | a `default` arm, the walk through `Containers` |
| `TemplateUses.collectUses` | 2 + `default` | the templates it names | a `default` arm, the walk through `Containers` |

The first four are exhaustive, which is what closed the bodies axis after the phase 1 audits
found two walks stopping short of an iteration's body. The price is that three of them say the
same thing fifty-four times: `Containers` that forty-six instructions hold nothing;
`producesContent` that thirty-seven write when unnamed and bind when named; `ReferenceCheck`
that twenty-eight read their selects and bind their name. `BodyCompiler`'s length is the
vocabulary's and is not this design's concern: each arm is a different function.

The last two are the open case. A `default` arm cannot tell a leaf from a container the author
forgot, which `Containers` fixed for bodies; it equally cannot tell a leaf that carries a pattern
from one that does not. Today one instruction does, `Replace`, and `MatchCompiler.collect`
names it. An instruction added tomorrow with a regex of its own would be interned by nobody and
die at run time with "Pattern was not compiled", the audits' bug class on the other axis.

The compiled side already has the abstraction the model lacks: `CompiledOp.Transform` is one
record for the thirty select-and-name instructions, and `Body` switches over twenty-five
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
    sealed interface Binding extends OutputNode permits Transform, Variable, Call, Sequence, Key, KeyGet, Count, Sum, Avg, Min, Max, DistinctValues, ValueMap, Tokenize {
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

    /** An instruction whose text is a regular expression the compiler interns. */
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
null. `Tokenize` and `ValueMap` are bindings with a single select and their own shape, not
transforms; `Call` is a binding whose select is a function's arguments.

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
`Containers`' exhaustive switch cost 6% on `csv_header`'s compile row at phase 1 and was
accepted; an interface call in its place is a different shape and is measured, not assumed.

What it costs: one `implements` clause on each of fifty-four records, five `permits` lists that
must name every record (which is the point: a record not in one is a compile error), and the
walks' arms rewritten as §3 says. One commit for the model, one per walk, each gated.

What it does not do: it does not shrink `BodyCompiler`, and it does not touch the JSON reader,
whose per-tag switch is the format's, not the model's.

## 5. Questions for the ruling (D47)

1. **Four classifications or three.** As drawn: `Holder`, `Binding`, `Transform` (a `Binding`
   with selects), `Leaf`. Without `Transform`, `ReferenceCheck`'s twenty-eight arms stay.
   *Recommended: four.*
2. **The `Regexed` marker.** Closes the patterns axis for the price of one interface that only
   `Replace` implements today. *Recommended: yes; it is the closure the audits asked for.*
3. **`Containers` stays or goes.** With `Holder.bodies()` on the model, the class is a
   one-line wrapper. *Recommended: it goes; its javadoc's lesson moves to `Holder`.*
4. **`BodyCompiler`'s switch stays a switch.** A table from record class to compile function
   would hide the compile shape behind a map. *Recommended: it stays.*

## 6. Record

*To be written as built: the D-number, the commits, the gate, the benchmark, the audit.*
