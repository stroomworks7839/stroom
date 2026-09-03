# stroom-shapeshifter-pipeline

The adapter [D10](../design/00-decisions.md) named on 2026-08-17 and
[design 21](../design/21-sax-bridge-plan.md) phase 0 created on 2026-09-03: the one module that
knows both `stroom-pipeline`'s parser contract and the Shapeshifter engine. The engine stays
Stroom-free; this is where a configuration becomes a pipeline element.

What it does today is prove it can exist. `ShapeshifterParserFactory` compiles a project once
and hands out `ShapeshifterParser`s, each an `XMLReader` over the engine — and `parse` refuses by
name, because its body is phase 1: run the configuration to a byte buffer, parse the buffer,
forward the events, and keep the engine's messages and the parser's errors apart on their way
to the error handler. Structured emitters (design 20) arrive in phase 2 and give the same
element a second, native path; the parse-and-forward path stays, permanently, for every
configuration whose output is not XML.

How a factory is *found* — the document type, the chooser beside `DSChooser`, the Guice
binding — is also phase 1's, and depends on nothing this module has decided yet.
