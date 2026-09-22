# 45 — The DS3 head-to-head

Shapeshifter has been measured against XSLT (designs 13 and 40) and against itself
(`EngineBenchmark`), but never against the engine it exists to replace. This is that
measurement.

## 1. Why it can be fair

A head-to-head is usually hard because the two sides have to be doing the same job, and
proving that is most of the work. Here it was already done:

- the legacy fixtures carry a DS3 config, its input, and Stroom's own golden output;
- `Ds3Migration.importXml` turns that config into a project, so the *same* configuration runs
  on both engines rather than two hand-written approximations of it;
- `Ds3Oracle` already builds a real `DS3Parser` in-process for the golden test, so both engines
  run in one JVM under one JIT.

## 2. Both sides end in the same serialiser

DS3 emits SAX; shapeshifter writes bytes. Pairing those directly compares different amounts of
work. `XmlByteSinkDs3GoldenTest` had already shown the way out: DS3's events through
`SaxToSink` into `XmlByteSink` are **byte-identical to Stroom's goldens**. So the benchmark
sends DS3 through that bridge and shapeshifter into the same sink, and the difference measured
is the parse, the dispatch and the transformation — not the writing.

`SaxToSink` was lifted out of the golden test into its own class so there is one copy of it;
two would be two answers to the same question.

### Two floors, because the engines do not stand on the same one

The first cut of this benchmark had one floor row — the recorded events replayed through
`SaxToSink` into the sink — and subtracted it from both engines. **That was wrong, and wrong in
shapeshifter's favour.** `SaxToSink` builds a `String` per text node; DS3's row pays it, because
DS3 emits SAX, and shapeshifter's row does not, because the engine writes bytes into the sink
directly. Subtracting it from the shapeshifter row takes away a bridge that row never crosses.

So there are two:

- **`sinkViaSax`** — DS3's floor: the events across the bridge and through the sink.
- **`sinkDirect`** — shapeshifter's floor: the same document written into the same sink with
  `write(byte[], off, len)`, which is the call the engine makes.

The difference between them is what crossing SAX costs, and it belongs on DS3's side. Each floor
is still an upper bound on its own side's shared cost — both replays pay a switch per event that
neither engine pays.

## 3. The guard

Two engines that disagree are doing two different jobs, and their throughputs mean nothing side
by side. `@Setup` therefore runs both over the amplified input and **fails the run** unless the
bytes are identical, printing the first differing line. The goldens hold this on the fixture's
own input; the guard holds it at a hundred times the length, which is a different question and
is the one the benchmark asks.

## 4. The rows

The legacy fixtures whose meaning survives repetition — no header that would repeat into data,
and no `min`/`max`/`only` limit, which counts per stream and would mean something else at a
hundred times the length:

`002_csv_without_header`, `004_simple_regex`, `006_single_line_delimited`,
`019_single_line_split`, `020_escaped_values`, `021_trimmed_values`.

One op is 256 KiB of input — the engine suite's unit, so the two benchmarks can be read
together. Each row's config is compiled in setup, as a pipeline holds it; per op, DS3 makes a
parser instance with its own variables and shapeshifter runs the compiled project.

```
./gradlew :stroom-shapeshifter:stroom-shapeshifter-pipeline:jmh
```

writes `design/benchmarks/<date>-<time>-<commit>-ds3.json`, the same discipline as the engine
and XML suites (D21).

## 5. First signal — a probe, not a result

One fork, one iteration, no error bars (2026-09-22), and **measured before the second floor
existed**, so only the first three columns stand: the derived column is struck through because
it subtracted DS3's floor from shapeshifter's row. It is kept, struck, because the mistake is
worth not repeating. Not filed in `design/benchmarks`, and not to be quoted as a number.

| fixture | DS3 ops/s | shapeshifter ops/s | end-to-end | ~~floor-subtracted~~ |
|---|---|---|---|---|
| 002_csv_without_header | 96.0 | 235.1 | 2.4× | ~~5.4×~~ |
| 004_simple_regex | 80.7 | 148.9 | 1.8× | ~~5.2×~~ |
| 006_single_line_delimited | 113.2 | 252.5 | 2.2× | ~~4.6×~~ |
| 019_single_line_split | 123.3 | 278.9 | 2.3× | ~~5.1×~~ |
| 020_escaped_values | 61.0 | 115.1 | 1.9× | ~~3.1×~~ |
| 021_trimmed_values | 67.8 | 145.3 | 2.1× | ~~4.0×~~ |

What survives the correction: **end-to-end, shapeshifter runs these at about twice DS3's
throughput**, and the serialiser is a large enough share of its op time to be worth reading the
code over — which §7 does. How large, and therefore how much faster the parsing alone is, are
the questions `sinkDirect` was added to answer and neither has an answer yet.

## 6. Next — the run is owed, and deferred

**Nothing here has been measured properly yet.** The harness is built, gated and parity-checked;
the only numbers are §5's probe, which predates the second floor and cannot answer the question
the benchmark was written for.

A real run was attempted on 2026-09-22 and **stopped before it produced a single number** — not
a failure of the benchmark: the box had 2 GB available of 31 GB with swap exhausted, holding
~11 GB of Gradle daemons, a headless Chrome and a Super Dev Mode server from another checkout.
It would not have been worth filing even had it finished; a run competing with three daemons on
a swapping kernel is the contamination D21 recorded, arriving by a different route.

Deferred until the box is free. When it is:

1. `./gradlew --stop`, and stop any stale Super Dev Mode server, then check `free -g`.
2. The default five-by-five, two forks, in an evening slot, filed as JSON.
3. Read **`sinkDirect`** first: it is the first honest measure of what serialising costs the
   engine, and §7's reading of the code predicts it is large. `sinkViaSax` minus `sinkDirect` is
   what crossing SAX costs DS3, which is worth knowing separately before anyone treats the
   end-to-end ratio as the engine's doing.
4. Only then is §5's struck column worth replacing, and only then is §7 worth acting on.

## 7. Where the serialiser's time goes, read from the code

Unmeasured — mechanisms found by reading `XmlByteSink`, listed so the profile has something to
confirm or refute (D57: a cheap check gates the benchmark).

**The sink round-trips bytes through `String`.** The engine hands it bytes; `write` decodes them
(`carry.take`) to a `String`, `escapeContent` copies that into a `StringBuilder` character by
character, `toString` copies again, and `emit` does `getBytes(UTF_8)` to get bytes back. For
ASCII content — which is most of it — that is a decode and a re-encode of every byte of output,
plus three allocations, to arrive at nearly the bytes it started with. This is per-character work
over the whole document and is the first thing to look at.

**Escaping allocates even when nothing is escaped.** `escapeAttribute` and `escapeContent` always
build a `StringBuilder(length + 16)` and append per character, whatever the content. The
overwhelmingly common case is that not one character needs escaping, and it pays a full copy for
that.

**Every start tag is built three times.** `emitStartTag` allocates a `StringBuilder` per element
(default 16 chars, so it grows), appends the tag, `toString()` copies it, and `emit` encodes it.
End tags are worse in the indented case: `"\n" + spaces(...) + "</" + qName + ">"` is a
concatenation per closing element.

Three things to try, cheapest first:

1. **Escape on bytes.** All seven escapable characters are ASCII, and UTF-8 is
   self-synchronising — a byte below 0x80 *is* that character, and no multi-byte sequence
   contains one. So the content path can scan the incoming `byte[]` for `&`, `<`, `>`, `\r` and
   bulk-copy the clean runs straight to the stream. No decode, no `String`, no re-encode, and the
   carry is only needed where a sequence is actually split. This removes the per-character cost
   from the common path entirely.
2. **Pre-encode the tags.** An element's qName repeats thousands of times in these fixtures, and
   `<qName`, `</qName>` and the indent prefix are constant per name and depth. The names are
   known when the project compiles, so these can be encoded once into `byte[]` rather than
   rebuilt and re-encoded per element.
3. **Return the original when it is clean.** Even keeping the `String` path, scanning first and
   returning the argument unchanged when nothing needs escaping avoids a copy on almost every
   value.

Worth checking before any of it: what the pipeline hands the sink. `raw` writes each fragment
straight to the `OutputStream`, and (1) and (2) would make the fragments smaller and more
numerous, so if that stream is not already buffered the win could be eaten by syscalls.
