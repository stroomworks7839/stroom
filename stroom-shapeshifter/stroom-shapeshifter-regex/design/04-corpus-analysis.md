# DS3 Pattern Corpus — how much does tier 0 actually cover?

Run: 2026-08-17, against this repository's own DS3 configurations.
Tool: `src/test/java/stroom/shapeshifter/regex/corpus/`.

**Updated:** the tool originally used a throwaway parser and analyser. It now runs patterns
through the **real compiler**, so the report cannot drift from what the engine actually does.
The two implementations produced identical classifications (3 / 8 / 10), which is a useful
cross-check of both.

The tier 0 scan plan is only worth building if real patterns qualify for it. This harvests
every `pattern="…"` from every DS3 config it can find, parses each one, and classifies it by
which execution tier it would land on.

**Run it against a real content export — the numbers below are from a small, simple sample:**

```
./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:test \
    --tests '*CorpusAnalysisTest*' -Dshapeshifter.corpus.dir=/path/to/content
```

---

## 1. Result

21 distinct patterns, 40 uses. Every one parsed — nothing was skipped or guessed at.

| Verdict | Distinct | Uses | Meaning |
|---|---:|---:|---|
| `java dialect` | 3 (14%) | 3 (8%) | Outside RE2 — needs the delegated JDK engine |
| `tier 0` | 8 (38%) | 17 (43%) | RE2 and one-pass — compiles to a straight-line scan plan |
| `tier 1` | 10 (48%) | 20 (50%) | RE2 but ambiguous — needs the Pike VM |

All eight tier 0 patterns are anchored at the start, so they run directly at a cursor with no
unanchored search.

The `java dialect` patterns vindicate [D7](../../design/00-decisions.md) — even this small sample contains
constructs the RE2 engine will never support, and they are not exotic:

```
((?>\n*|^)(?>.*\n)+?)\n((?>\n*|^)(?>.*\n)+?(?>\n|$))          atomic groups
([\w ]+)((?>,|$))                                              atomic group
^(?:INFO|DEBUG|WARN|ERROR|TRACE) +.*?(?=(?:(?:INFO|…) +)|\z)   lookahead
```

Had the plan been to *build* a backtracking engine for these, that work would already be on
the critical path for this repository's own test suite.

---

## 2. The interesting part: why patterns fail the one-pass test

The predicate reports which construct made a pattern ambiguous, and the answers fall into
three groups.

### 2.1 Genuinely ambiguous — tier 1 is the right answer

```
^(.+):(.+)                                    .+ and ':' overlap
^([0-9]+) \[([^\]]*)\] (\(.*\))$              .* and ')' overlap
argc=(.+) a0=([^\s]+) (.+)                    .+ and ' ' overlap
^([^ ]+) ("([^"]*)"|([^ ]+)) …                alternation branches both start with '"'
```

The last one is the Apache-log pattern, and its ambiguity is real: a field may be quoted or
unquoted, and `[^ ]+` can also match a leading quote. Ordered preference resolves it, which is
exactly what a Pike VM does and a committed one-pass scan cannot.

### 2.2 Accidentally ambiguous — the analysis doubles as a lint

```
"\s*(\d+.\d+.\d+.\d+) - ([^"]+)
"\s*(\d+.\d+.\d+.\d+) ot (\d+.\d+.\d+.\d+) - ([^"]+)
```

Reported as `\d+` and what follows both being able to start with ten byte values — the digits.
The cause is the **unescaped `.`** between the octets: the author meant a literal dot in an IP
address and wrote "any character". The pattern still works, but it is matching far more
loosely than intended, and it is ambiguous only because of the mistake. Written `\d+\.\d+`
it becomes one-pass.

**This is a capability worth having in its own right.** The same analysis that selects an
execution tier can tell an author their pattern is sloppier than they think — a real
diagnostic, produced free by machinery being built anyway, and exactly the kind of help a
node-editor audience needs.

### 2.3 Fixable by one more plan operation

Several patterns are ambiguous only in the specific shape *unbounded repeat over a permissive
class, followed by a literal*:

```
^(.+):(.+)              greedy .+ then ':'
(\(.*\))$               greedy .* then ')'
argc=(.+) …             greedy .+ then ' '
```

The strict one-pass predicate rejects these, correctly, because a scan cannot know where to
stop. But greedy `.*X` has a direct scanning implementation over a bounded window — **find the
last X** — just as lazy `.*?X` is **find the first X**. Neither needs an automaton.

**Corrected, 2026-08-17.** That estimate was wrong, and the error is worth recording. It counted
patterns by *shape* — an unbounded repeat followed by a literal — without checking when
last-occurrence is actually sound. It is sound only when **nothing consuming follows the
literal**. `^(.+):(.+)$` looks like a candidate but is not: if the last colon has nothing after
it, a correct engine backtracks to the previous one, and doing that in a scan plan reintroduces
backtracking and forfeits the linear-time guarantee.

Rechecked against the condition rather than the shape, **one** of the ten tier 1 patterns
qualifies (`^([0-9]+) \[([^\]]*)\] (\(.*\))$`, where the `\)` is followed only by `$`).

The corrected breakdown of why patterns land on tier 1 is more useful than the original claim:

| Cause | Count | Recoverable by widening tier 0? |
|---|---:|---|
| Optional-prefix ambiguity (`\n?`, `\(?`, `(...)?`) | 5 | No — genuine ambiguity |
| Alternation overlap | 2 | No |
| Unescaped `.` (§2.2) | 2 | Only by fixing the pattern |
| Greedy repeat then literal | 2 | 1 of the 2 |

**Tier 1's population is mostly genuine ambiguity, not analysis conservatism.** That is the
finding that matters: tier 0 cannot absorb much more, so the effort belongs in making tier 1
fast rather than in shrinking it.

---

## 3. What this does and does not tell us

**Does:** the tier 0 mechanism applies to a substantial share of real patterns rather than to
a contrived best case; the java dialect is needed for real configs, not hypothetically; and
the one-pass analysis produces a useful lint as a by-product.

**Does not:** generalise to production. This corpus is 21 patterns from a repository's test
fixtures, and production instances are known to hold larger and more deeply nested patterns.
Bigger patterns plausibly skew *away* from tier 0 — more alternation and more `.*` means more
ambiguity — so 38% should be treated as an optimistic reading until the tool is run against
real content.

**Limitations, to fix before quoting these numbers widely:**

- Flags are ignored. DS3 carries `dotAll` and `caseInsensitive` as XML attributes on the
  `<regex>` element, and the harvester reads only the `pattern` attribute. Under `dotAll`, `.`
  also matches `\n`, which widens first-sets and could reclassify patterns.
- Only `<regex>` patterns are harvested. DS3 `<split>` delimiters are trivially tier 0 and
  would raise the tier 0 share if counted; excluding them is the conservative choice.
- The predicate is conservative by construction. It rejects anything it cannot prove
  unambiguous, which is the right bias for a correctness-preserving optimisation.

All eight tier 0 patterns are now compiled by the real engine and checked against
`java.util.regex` in `DifferentialTest`, so this corpus is part of the correctness suite and
not only a survey.

---

## 4. Consequences

1. ~~**Build the two extra plan operations.**~~ Superseded by §2.3's correction: sound only when
   nothing consuming follows the literal, which is one pattern in ten rather than half. Low
   priority. The effort belongs in making tier 1 fast instead
   ([03-baseline-results.md §8.4](03-baseline-results.md)).
2. **Surface the one-pass analysis as an authoring lint**, not just a compiler internal.
3. **Re-run against production content before committing to tier 0's scope** — the tool takes
   a directory, so this costs nothing but access.
