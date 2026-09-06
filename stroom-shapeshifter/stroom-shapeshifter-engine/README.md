# stroom-shapeshifter-engine

The layers above matching: reading a configuration, running it over an input, and writing the
result. Stroom's own DS3 is the oracle it is measured against ([D41](../design/00-decisions.md)):
every in-scope fixture passes on Stroom's goldens since design 21 phase 3 (2026-09-04) — the
legacy and native families byte-identical to Stroom's DS3, the structured emitters serialising
as Saxon does.

```java
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

final Project project = ProjectReader.read(Files.readString(config));
final CompiledProject compiled = Shapeshifter.compile(project);

final List<Message> messages = Shapeshifter.run(
        compiled, input, new XmlByteSink(output));
```

Compile once, run per input. Compilation is where a configuration's own errors surface — a
pattern that will not compile, an encoding this build has no charset for, a named pattern that
refers to itself — so a configuration that starts is one that can finish. Messages are collected
rather than thrown, because one bad record in a million is a message, not a failure.

## What a configuration is

A flat list of templates, dispatched rather than nested. The model is XSLT's, taken deliberately:
people who will write these already know what `value-of` and `apply-templates` do.

Each template combines three things. A **match** tests the content in front of it and always
consumes bytes if it succeeds — a regex, a delimiter with quoting and escaping, a progressive
sequence of steps, or the document itself. Its **captures** bind what the match produced to
names. Its **body** is a flat sequence of instructions that write the output, and one of those
instructions can hand a captured group down to another set of templates, which is how a record
becomes fields and a field becomes parts.

Dispatch is three stages, cheapest first: the **mode** decides whether a template is a candidate
at all, the **guard** is a condition over what is already known, and only then is the match
tried. The templates of a mode are dispatched as iterated ordered choice — `(A|B|C)*`, DS3's own
model ([D34](../design/00-decisions.md)): each pass the first template that matches wins one
match, and the choice re-opens. Content a match skips, and content nothing can match, is
reported rather than lost, gated by the dispatching container's `ignore_errors`.

```
config/      the authored model — plain records, no framework annotations
config/json  the wire format: one reader-and-writer class per family, over shared primitives
ds3/         reading Data Splitter v3 configurations and converting them
compile/     the passes: patterns interned and compiled, references checked, the graph built
value/       what a match captures and a body computes with — depends on nothing above the model
match/       what a match produces, and the two ways of matching that are not a regex
exec/        one run over the graph: the window, the level dispatcher, the body interpreter
output/      the sinks: bytes as Saxon would write them, SAX events, characters
text/        encodings, at the boundaries that need them
function/    the extension-function contract (design 26)
```

Two layers, never three ([D35](../design/00-decisions.md)): the model and the executable graph,
and the run is the graph's state for one input. A value knows nothing of a match, a match
nothing of a run; the package line enforces it
([design 27](../design/27-engine-structure.md), D45).

## Two things worth knowing before using it

**A record must fit the window.** Input streams through a window of the configuration's
`buffer_size`: a record is never cut by where a read happened to end, and one larger than the
window is fatal, by name ([design 23](../design/23-streaming-contract.md)). Memory is the window,
not the stream.

**Everything writes through `OutputSink`.** Three sinks in `output`: `XmlByteSink` writes bytes
as Saxon would ([D41](../design/00-decisions.md)), `SaxEventSink` forwards the structure as
events, and `CharacterSink` delivers a text configuration's writes as characters (D42). The
pipeline module chooses per element; the interface is one place to answer that rather than
twenty.

## Open issues

[ISSUES.md](ISSUES.md) tracks everything the port left open, with the evidence for each: the
ported behaviour worth deciding about, the four fixture goldens the audit found wrong, the scope
deliberately left out, and the decisions the port enables. Refer to them by id.

## What is not here

- **Avro, Parquet and Protobuf.** Modelled, and refused at compile time with a clear message.
  Each needs a large dependency; D33 deferred them. Three fixtures are skipped accordingly.
- **A compile-time optimiser** — unused-capture elimination, dead-branch pruning. It would
  change work, not output.
- **A performance story, yet.** The engine now has a benchmark suite
  (`stroom.shapeshifter.engine.bench`, run with the `jmh` task, results in
  `../design/benchmarks/`), and [10-engine-compilation.md](../design/10-engine-compilation.md)
  records what is compiled, what is still interpreted, and the decoration design. Optimisation
  follows the baseline, one measured change at a time.

## Testing

`src/test/resources/fixtures` holds the corpus — 203 files, three families, with the ledger that
drives them and the provenance of every golden ([its README](src/test/resources/fixtures/README.md)).
The ledger is a ratchet: a fixture recorded as failing that starts passing **fails the build**
until it is promoted, so progress is recorded on purpose and regressions cannot go quiet.

Everything the fixtures cannot reach is tested directly, and that is most of the interesting
surface: chunking, `call-template`, regex replacement, every combinator, both seeks, non-UTF-8
input, and the instrumentation seam. Four `projects` goldens were found wrong when the corpus
was first audited; all four were fixed at the configuration and re-frozen under review
(E6–E8, E16), and the quarantine is empty.

