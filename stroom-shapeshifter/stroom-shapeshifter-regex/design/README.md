# The regex library's design record

Six documents, kept with the module they describe: the language it accepts, the engines that
run it, and every measurement that shaped them. The module is meant to be readable — and
buildable — on its own, so its design record lives here rather than one directory up.

| Document | What it is |
|---|---|
| [01-regex-language.md](01-regex-language.md) | The dialect: an RE2-style subset spelt Rust's way, plus the fancy tier (backreferences, lookaround, atomic groups, `\G`). Draft 1 — where it and the code disagree, the code and the decision log win. |
| [02-engine-design.md](02-engine-design.md) | The engine architecture: encoding compiler, scan plan, automaton tiers, tree engine. Draft 1, same caveat. |
| [03-baseline-results.md](03-baseline-results.md) | The measurements taken *before* any engine code: hand-written scanners against `java.util.regex`, which confirmed one design assumption and refuted two. |
| [04-corpus-analysis.md](04-corpus-analysis.md) | What this repository's own DS3 configurations actually use, and how much of it the cheapest tier covers. |
| [05-engine-benchmarks.md](05-engine-benchmarks.md) | The engine against `java.util.regex`, corpus coverage and throughput. Tables here are rendered from the run files, never transcribed. |
| [06-performance-plan.md](06-performance-plan.md) | The open performance work, its method notes, and the standing benchmark-gate rules. The first thing to read before touching a hot path. |

And beneath them, [benchmarks/](benchmarks/README.md): every JMH run this module has recorded,
one file per run, checked in so that any number in the prose above can be traced to the run that
produced it — plus the scoreboard charts the top-level README displays.

Beside them, in the module root: [README.md](../README.md) — where the library stands, in two
bounded sentences — and [ISSUES.md](../ISSUES.md), the open items and the accepted costs, each
with the measurement that priced it.

## What stayed shared, and why

Two things describe both this module and the engine module that consumes it, so they live
in the parent design folder:

- [00-decisions.md](../../design/00-decisions.md) — the decision log, D1–D37, spanning both.
- [15-audit-ledger.md](../../design/15-audit-ledger.md) — the adversarial audit record, likewise.
