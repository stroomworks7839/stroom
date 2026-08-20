# stroom-shapeshifter-engine

The layers above matching: reading a configuration, running it over an input, and writing the
result. It is a port of the `shapeshifter` crate from the Rust `ds-rs` project
([D33](../design/00-decisions.md), [07-engine-port-plan.md](../design/07-engine-port-plan.md)),
and the port is complete — 49 of 49 in-scope fixtures, 198 tests.

```java
final Project project = ProjectReader.read(Files.readString(config));
final CompiledProject compiled = Shapeshifter.compile(project);

final List<Message> messages = Shapeshifter.run(
        compiled, input, OutputSink.of(output));
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
at all, the **guard** is a condition over what is already known, and only then is the match tried.

```
config/     the authored model — plain records, no framework annotations
config/json the whole wire format, in one file
compile/    patterns compiled, delimiters encoded, references inlined
exec/       the runtime: stores, scopes, references, steps, transforms
text/       encodings, at the two boundaries that need them
ds3/        reading Data Splitter v3 configurations and converting them
```

## Two things worth knowing before using it

**A match never spans two buffers.** Input is read in buffers of the configuration's own size,
and a record longer than one cannot be matched whole — the engine warns rather than emitting half
of it. This is the Rust engine's limitation, ported deliberately so that golden parity meant
something, and the matching layer underneath can do better. Lifting it is the first decision the
port sets up.

**Everything writes through `OutputSink`.** Today its only implementation writes bytes, because
configurations describe their output as text that happens to be XML. A Stroom pipeline element
will want something else, and that choice is still open ([D10](../design/00-decisions.md)) — the
interface is one place to answer it rather than twenty.

## Open issues

[ISSUES.md](ISSUES.md) tracks everything the port left open, with the evidence for each: the
ported behaviour worth deciding about, the four fixture goldens the audit found wrong, the scope
deliberately left out, and the decisions the port enables. Refer to them by id.

## What is not here

- **Avro, Parquet and Protobuf.** Modelled, and refused at compile time with a clear message.
  Each needs a large dependency; D33 deferred them. Three fixtures are skipped accordingly.
- **The compile-time optimiser** ds-rs has. It changes work, not output.
- **A performance story.** The engine is correct as far as 198 tests can show and entirely
  unmeasured. The matching layer has a benchmark suite and a scoreboard; this does not, and D33
  ruled out performance work until the suite was green. It now is.

## Testing

`src/test/resources/fixtures` holds the corpus — 132 files, three families, with the ledger that
drives them and the provenance of every golden ([its README](src/test/resources/fixtures/README.md)).
The ledger is a ratchet: a fixture recorded as failing that starts passing **fails the build**
until it is promoted, so progress is recorded on purpose and regressions cannot go quiet.

Everything the fixtures cannot reach is tested directly, and that is most of the interesting
surface: chunking, `call-template`, regex replacement, every combinator, both seeks, non-UTF-8
input, and the instrumentation seam. Three of the corpus's goldens are **quarantined** — the
phase 0 audit found four wrong and one has since been fixed (E6) — with the findings in
[08-fixture-audit.md](../design/08-fixture-audit.md).

`docs/` holds the Rust project's own design documents, vendored unedited, with an index saying
which of them apply. They are history and intent; where they and the ported behaviour disagree,
the behaviour is what was ported.
