# Encoding plan — the parameter the bytes-only ruling owes

D38 ruled the semantics in encoding terms — a character construct matches only well-formed
characters of `E` — while the implementation carries exactly one `E`. That was tolerable
until E29: templates declare encodings (E3, ruled and pinned), delimiters and progressive
steps honour them, and regex steps silently compile UTF-8 against whatever bytes the feed
carries. The spec has said what the parameter looks like since draft 1
([regex 01 §4.0–4.5](../stroom-shapeshifter-regex/design/01-regex-language.md)): three
compiled-in shapes — UTF-8, a 256-entry single-byte table, `RAW` identity — and a
`transcode` stage upstream for everything else. This is the plan for making that true.

What exists already, so the plan does not re-plan it: the engine's `Encoding` enum with
per-name refusal (E22, resolved — *an encoding decodes exactly what it says, or the
configuration naming it does not compile*; every refusal below inherits that doctrine);
per-template resolution (E3, resolved, `EncodedInputTest`); steps classifying characters
under the effective encoding at run time (E5, resolved). The regex module is
dependency-free (`verifyZeroDependencies`), so it takes its **own** three-shape encoding
type and the engine maps its enum onto it — the enum never crosses the module boundary.

The method is the fixed one: one change at a time, measured; any edit touching a search
path gates on the buffer-CSV and per-match-datetime canaries; cross-day comparisons are
paired runs; results are checked in. Statuses move here.

---

## Phase 0 — Make the gap honest *(small; no perf surface)* — **Done 2026-08-28**

**What the audit found, in the order the plan asked.** The refusal is in: a template whose
effective match encoding is not UTF-8-compatible and which carries a regex anywhere — match,
step, condition, or replace — is refused by name, pinned by three `EncodedInputTest` cases
(regex match under windows-1252, regex step under a template override, `matches` condition
under RAW). The fixture census found **exactly one leaner, and it was the E3 pin itself**:
`legacy_line` declared windows-1252 and matched with a regex, passing only because its
pure-ASCII pattern landed in the byte-permissive plan tier — the licensed deviation of D38,
holding up the test that pins per-template encoding. It now says the same thing in steps.
The `RAW → UTF_8` charset shortcut was the second silent approximation: a RAW template's
delimiter "é" compiled to `C3 A9` while its step tag "é" looked for `E9` — two byte forms
for one text in one template. Fixed by unifying delimiters onto the same `Encoding.encode`
the step vocabulary uses; one encode path, one truth. The UTF-16 story: benign — delimiters
and steps serve it byte-honestly through their charset paths, and regex is now refused. The
AUTO note: BOM sniffing is unimplemented, AUTO is UTF-8 everywhere today, so treating it as
compatible is sound — but if a BOM sniff ever lands, resolution must happen before this
refusal's question is asked.

The stopgap E29 names. `Compiler.compileMatch` (and the `intern()` path for condition and
replace patterns) refuses by name any template whose effective match encoding is not
UTF-8-compatible and which carries a regex anywhere — match expression, progressive step,
condition, or replace. "No match" stops standing in for "cannot do that".

Also in this phase, because they are the same audit:

- **Fixture census.** Which existing fixtures the refusal breaks is evidence of how much
  the silent gap is leaned on; any that break were mis-matching before and get recorded,
  not worked around.
- **The `RAW → UTF_8` charset shortcut** (`Compiler` line ~73) — decide whether encoding a
  RAW template's delimiters through UTF-8 is right (text delimiters in a binary feed) or a
  second silent approximation; record the answer either way.
- **The UTF-16 story.** No `transcode` stage exists (01 §6.6 is spec only). Establish what
  a UTF-16 source does today, and if the answer is "reaches matching un-transcoded", the
  refusal covers it too.

**Exit:** no configuration can silently get UTF-8 matching it did not ask for. E29 moves to
`deferred` with the stopgap named.

## Phase 1 — The parameter exists *(API; UTF-8 behaviour byte-identical)* — **Done 2026-08-28**

**As built.** `stroom.shapeshifter.regex.Encoding` is a sealed interface whose permitted types
are the lowerings the library actually has — one, today — so an encoding it cannot compile is
a type that does not exist, E22's refusal-by-name enforced by `javac` instead of a check.
`BytePattern.compile(pattern, flags, encoding)` threads it to the four compiler entries
(`NfaCompiler.compile`/`compileFancy`, `PlanCompiler.compile`, `NodeTree.compile`), where the
parameter is deliberately unread and its javadoc says so: its job is to make each branch
point enumerable as per-encoding work rather than remembered as it. The encoding joins the
pattern's identity (`BytePattern.encoding()`). `EncodingSeamTest` pins the seam inert:
explicit UTF-8 equals the default to the byte across all three tiers, every compile path
reports its encoding, null is refused not defaulted.

**Audited 2026-08-28, after both phases landed, and the audit earned its keep — four
findings.** (1) *A pre-existing crash, any encoding:* both step walkers saw `PatternRef`
where the executor sees the inlined steps, so a regex inside a referenced library pattern
was never interned and every use died at match time with "Pattern was not compiled". Both
walkers now resolve first; pinned by a test the audit mutation-verified (reverting the fix
reproduces the exact crash). (2) *The phase-0 refusal shared the blindness:* a non-UTF-8
template reaching a regex through a `PatternRef` escaped it — same fix, own pin. (3) *The
phase-1 enumeration missed a lowering:* the reverse start-finder (`Reverse.program` →
`compileByteLevel`, byte-level class tables — precisely a table/RAW branch point) now
carries the seam parameter like the other four entries. (4) *Scope note for phases 2–3:*
`Words` decodes input at match time for word boundaries — a third runtime encoding consumer
beside backreference comparison and case folding; phase 2's "no engine decodes for class
membership" exit is unaffected, but phase 3's table decode must visit it. Verified clean:
the step walkers recurse every composite (`Choice`/`Optional`/`Repeat`/`Sequence`/`Peek`/
`Not`), the body walkers reach conditions and regex replaces at any nesting, and the only
other config "pattern"s are `DateTimeFormatter`'s, not this library's.

**One deviation from the phase as written, recorded rather than silent:** the engine's
`intern()` keys stay pattern text. Under phase 0's refusal every interned pattern is UTF-8,
so a composite key would be dead code guarding a state that cannot arise; it lands with
phase 3's integration, where a second key value first can. Original wording follows.

The regex module grows its encoding shape — `UTF8 | TABLE(256-entry inverse) | RAW` — and
`BytePattern.compile(pattern, flags, encoding)`, the old overloads delegating to `UTF8`.
The parameter threads to the lowering seams and to pattern identity; the engine's
`intern()` keys change from pattern text to (text, effective encoding). Only `UTF8` is
accepted at first, so behaviour is provably unchanged: full suites both modules, canary
pair, and a spot-check that compiled artifacts for UTF-8 are structurally identical.

This is the honesty mechanism as much as the capability: after this phase every UTF-8
assumption in the module flows through one named seam, and phase 2's audit falls out of
the compiler's own type checking rather than out of grep.

**Exit:** the signature exists everywhere, nothing behaves differently, and the places
that will need per-encoding work are enumerated by the compiler instead of by memory.

## Phase 2 — One boundary: the tree's classes byte-compile *(the measured risk)* — **Landed 2026-08-28, gated overnight**

**As built, in two commits.** Preparation asked whether byte forms could change behaviour on
malformed input, and convicted the present first: `Utf8.decode` had no overlong, surrogate or
ceiling check, so `{ED,A0,80}` decoded to 0xD800, dotall contained it, and the tree matched
bytes no flat engine would — the greedy-scan conviction one layer down (`886066ec82`; five
adversarial encodings now pin both raw-bytes suites). With decode strict, both forms
recognise exactly the valid encodings of members, so the port is equivalence-preserving by
proof, not hope. Then `OneChar` grew the flat engines' compiled form — `Utf8.sequences`
alternatives, lead-sorted, ASCII left to the existing table — `accept()` walks ranges
instead of decoding, and `StarClass.scan`'s allNonAscii decode branch retired because there
was nothing left for it to skip. Mutation: two range-walk mutants killed, one equivalent
(the sorted early-exit — `continue` reaches the same conclusion the shortcut takes). A
random-byte-soup agreement test throws the wide/astral/negated/`\w` class shapes against
the flat engines, where two executors of one compilation must agree on windows that are
half invalid UTF-8. Match-time `Utf8` in the tree is now `isContinuation` backoff structure
only; the perf verdict is the overnight chain's (baseline → batch → this commit, one boot).

**Audited same day, one correction and a blast-radius record.** The correction: the first
cut reimplemented the byte-range walk inside `OneChar` with a linear alternative scan —
whose wide-class cost hazard turned out to be named, solved and javadoc'd in the codebase
already: `CharClass`, the scan plan's compiled class form, carries the lead-byte index that
makes a wide class cost its matching alternative rather than its alternative count. `OneChar`
now holds a `CharClass`; one compilation, two engines, forty lines gone, and the class nodes
carry their Hir labels through for diagnostics. The tree-side suites reach the shared walk
(mutation-verified). The strict-decode blast radius, checked consumer by consumer: `Words`
is shared by every engine, so the word-boundary shift on malformed input is uniform and
D38-aligned; `Backrefs` handles -1 on both sides (noting, recorded not fixed: a mid-region
invalid sequence on the input side reports TRUNCATED where MISMATCH is the truer name — a
pre-existing conflation, uniform across the two engines that run backrefs); the `Parser`
use is compile-time over a String's bytes, where strictness cannot fire.

Original phase text follows.

The tree engine is the unlisted third mechanism of 01 §4.0: the flat engines erase the
encoding at compile time (classes → byte automata via `Utf8.sequences`), the tree
re-derives it at match time (`OneChar.accept` → `Utf8.decode`). It is a port artifact of
the JDK architecture (D30) — `Utf8.decode`'s history says so — and it is where the greedy
divergence lived, because a runtime decode step is a surface a shortcut can disagree with.

This phase compiles the tree's class nodes (`OneChar`, and through it `StarClass` /
`CountedClass` items) to the same byte form the flat engines use: ASCII table plus
byte-sequence trie. `Utf8` retreats at match time to the two genuinely textual uses,
backreference comparison and case folding. A byte-compiled class exposes its lead bytes
directly, which the lazy-run skip's `leadingByte()` currently reconstructs by hand —
the skip gets simpler, not more complex — and the first-byte fact the dispatch row in
[regex 06 §1](../stroom-shapeshifter-regex/design/06-performance-plan.md) wants becomes a
read of the compiled form, whichever side of the seam that row's measurement puts it on.

Risk is priced honestly: this edits the primary engine's hottest nodes, the non-ASCII path
could move either way, and the phase ships only under the full gate — canaries, the
LazyRun rows, an evening paired full set — with `GreedyRunRawBytesTest` and
`LazyRunSkipTest` as the semantic pins. If the tree measurably loses, the fallback is
per-encoding decode (for `TABLE` a flat lookup, so phases 3–4 do not block on this one),
and the loss is recorded here with numbers.

**Exit:** no engine decodes input at match time to answer class membership, and 01 §4.0's
"two mechanisms" is true again.

## Phase 3 — Single-byte tables *(the one real feeds are waiting on)* — **Landed 2026-08-28, gated overnight**

**As built, two commits.** The regex side (`b0154e52e2`) found UTF-8 far above the
compilers — the parser lowered literals at parse time, the HIR baked UTF-8 lead bytes,
`Analysis` derived UTF-8 lengths, and every engine's boundary gates assumed UTF-8 byte
structure — so the architecture became the spec's own sentence: the HIR stays byte-level
and is *built for* the encoding, with `ByteForm` carrying one encoding's byte facts to the
parser, the compilers and the runtime gates, and `Encoding.Table` the public shape (256
entries, low half identity enforced, unmapped -1, injective). Strictness on unmapped bytes
falls out of the mapping. Three own-bugs caught by the first table run, recorded in the
commit: the lazy skip's UTF-8-spelled lead byte, `emitScan`'s unconditional high-byte fill,
and a hashcode in a refusal message. The engine side (this commit) adds `RegexEncodings` —
the one seam where the two vocabularies meet, tables built from the same `Charset` the
steps decode with so the two views cannot disagree — keys the interned patterns by (text,
encoding) as phase 1 deferred, narrows the refusal to RAW and the transcode family, and
flips the phase-0 refusal tests into the capability tests they were holding the door for.
`TableEncodingTest`'s 1,200-comparison JDK differential (exact oracle: offsets map 1:1)
and the reworked `EncodedInputTest` hold it. **E29 closes.** Perf: the overnight chain
gates the batch; the UTF-8 path is pinned byte-identical by the existing suites.

**Audited same day, and the audit found the model the phase had missed: there are two regex
domains.** Match expressions and progressive steps run over raw feed bytes — the template's
encoding is theirs. Guards' `matches` conditions and body replaces run over *resolved
values*, whose internal form is UTF-8 whatever the feed carries (`CompiledOp.Text` encodes
UTF-8; `normalise()` says so in words) — the phase had moved them to the template encoding,
which surfaced as a real crash: a guard's pattern interned under the template's table,
looked up at run time under the executor's project encoding, "Pattern was not compiled".
The fix is the domain split, not the lookup patch: value-domain patterns compile and key
as UTF-8 always, the feed-domain refusal covers only the match vocabulary (so a `matches`
under RAW rightly compiles now — phase 0's four-carrier refusal was conservative, and two
of its carriers turn out never to have needed refusing), and the guard-crash test pins the
working behaviour. Two smaller findings: `RegexEncodings`' cache was `synchronized` on the
per-step path of every single-byte feed — built eagerly now, one unsynchronised read — and
`TableForm.decode`'s limit guard and `sequences`' flush-at-256 verified correct.

Original phase text follows.

`TABLE` lowering: literals and classes through the 256-entry inverse, so every class is one
byte wide — which the scan plan's byte ops take directly, and dispatchability should
improve, not suffer. Case folding expands within the table's repertoire at compile time.
`\w \d \s`, `\b` (`Words`) and `\p{...}` (`UnicodeClasses`) answer over the 256-code-point
repertoire by intersection; the `u` flag semantics of 01 §4.2 get their per-table statement
recorded in 01.

Two notes that keep later arguments short: under a single-byte encoding **every byte is
well-formed**, so D38's strictness is vacuously satisfied and the UTF-8 deviation pin does
not extend — stated in the tests, not just here. And the differential oracle is unusually
clean: decode with the table, run the JDK on the decoded `String`, and offsets map
one-to-one, so the whole `LazyRunSkipTest`-style apparatus transfers.

Engine integration lands here too: `compileMatch` and `intern()` pass the effective
encoding through, the phase-0 refusal narrows to the still-unimplemented shapes,
`EncodedInputTest` gains a regex step under `windows-1252`, and **E29 closes**. Benchmarks:
a windows-1252 corpus row alongside the canaries — which encodings matter beyond
Latin-1/1252 is a question the arriving real DS3 configs answer, not this plan.

**Exit:** a `windows-1252` template's regex matches the bytes its feed carries, proven by
the extended `EncodedInputTest` and a checked-in benchmark row.

## Phase 4 — `RAW` *(small once phase 3 exists)* — **Done 2026-08-28**

**As built.** RAW's lowering is literally the identity table — `ByteForm.RAW` delegates to a
`TableForm` over the identity map, zero new lowering code — and what is genuinely RAW's own
is dialect semantics, in the parser: no Unicode default, `u` refused rather than ignored
(explicit flag and inline `(?u)` both), `\p` refused at the shared class parser, `\xHH`
meaning the byte because identity makes it so. The engine maps `Encoding.RAW` through
`RegexEncodings`, and the refusal reaches its floor: only the transcode family remains,
match vocabulary only. `RawEncodingTest` pins the semantics plus a 600-case JDK differential
through ISO-8859-1, whose identity over every byte makes it RAW's exact oracle off the
shorthands; `EncodedInputTest` runs a regex match over binary bytes end to end.

**The catch that paid for the phase:** `ByteMatcher` — the public entry, carrying the "one
shared search-start gate" — still asked `Utf8.splitsCharacter` at the anchored entry and
the search seeding, silently unseeding every match that would start at bytes 0x80–0xBF
under RAW *or a table*. Phase 3's tests never started a match in that range (é is E9, À–ÿ
is C0+), so the gap survived its differential; RAW's first `[\x80-\xFF]` run caught it.
The gate now asks the pattern's compiled form, the 1252 differential's byte pool gained the
curly-quote range, and a named test pins a match starting on a byte UTF-8 would call a
continuation.

**Audited same day.** Verified: the `(?u:...)` scoped form is refused by the same guard as
`(?u)`; `(?-u)` stays a tolerated no-op; `\uHHHH` up to FF means the byte and above it is
refused by name (both now pinned); the identity table passes every `Table` validation rule;
`RawForm`'s delegation is total, with `encodedLength` honestly constant; word boundaries
compile to the ASCII kinds because RAW never carries the Unicode flag; backreference
folding is ASCII-only for the same reason, and the fancy tier's byte-compare is pinned by a
new backref test. Two findings acted on: `PatternInfo.inspect` answered from the UTF-8
parse alone, so an editor would call `\p{L}` valid for a RAW template whose compile then
refuses it — it now takes the encoding, old signature delegating to UTF-8. And one deferred,
recorded here as the plan's style requires: `compileForcing` is UTF-8-pinned, so cross-engine
agreement under tables and RAW cannot be asserted by forcing — each tier is instead covered
by its per-encoding JDK differential, and the forcing entry grows its encoding parameter
when a divergence ever needs localising, not before.

Identity lowering per 01 §4.4: `.` is any byte except `\n` unless `s`; `u` forced off;
`\p{...}` a compile error naming the mode. The phase-0 answer on RAW delimiters is
implemented rather than shortcut. This gives D38's composition story its in-dialect mode
for binary formats.

## Phase 5 — `\B{HH}` byte escapes *(completes D38's in-pattern leg)* — **Done 2026-08-28**

**The spelling ruled first, as required, and recorded in 01 §4.4:** braces always —
`\Bad` stays a boundary and its literal, `\B{AD}` is the byte, and the quantified-boundary
corner is spelt `(?:\B){n}` by whoever means it. As built: a byte escape is an
`Hir.Bytes` node, matched raw and never re-encoded, under every encoding; in classes it
means the byte's character and so exists exactly where one byte is one (tables, RAW), with
a directions-bearing refusal under UTF-8; multi-byte literals carry the straddle warning
through the parser's new warnings channel, which `Parser.Result` and the comb path's
`Lowering.Result` now thread into `BytePattern.warnings()` beside the analysis's own.
`ByteEscapeTest` pins the dialect corner to corner, including the traded corner and the
documented can-never-start-on-a-continuation-byte limit.

**And the phase found a phase-3 escapee:** `Normalise.literalBytes` re-encoded
single-code-point classes as UTF-8 — spelt via `String.getBytes`, invisible to the
`Utf8.`-sweep — so a table pattern's `“` became `E2 80 9C` during literal factoring. Found
by this phase's coincidence test (`\B{93}` worked where the literal `“` did not), fixed by
threading the form, and the lesson recorded where the next sweep will look: grep for the
charset, not the class.

**Audited same day.** Findings, all closed: four stale `\BHH` spellings survived in 01's
own table, RAW section and D38 cross-references — the ruling paragraph landed and its
neighbours didn't, fixed. The straddle warning overclaimed for lead-range bytes ("a match
can never start" is true of 0x80–0xBF and false of 0xC2+), so the two ranges now warn
differently, because they fail differently — both pinned. Two behaviours verified natural
and pinned rather than assumed: a byte escape quantifies like any atom, and `(?i)` folds
characters never bytes (`\B{45}` matches `E`, not `e`). Verified clean: the escape
parser's edge cases, `factor()`'s structural prefix sharing over already-lowered bytes,
`Parser.Result`'s single construction site, the benign unreachable null in `literalBytes`,
and honest {1,1} lookbehind bounds for byte escapes.

Permitted under any encoding, never re-encoded, straddle warning per 01 §4.4. One spec
decision to confirm before parsing starts: `\B` is also the conventional non-word-boundary
escape, and 01 must say which spelling wins or how `\B{hex}{hex}` disambiguates — settled
in 01 first, implemented second.

## Phase 6 — `transcode` *(was deferred; un-deferred by direction)* — **Done 2026-08-28, whole-source scope**

**As built.** The whole-source case of 01 §6.6: a transcode-family source — UTF-16, the CJK
multi-byte family, anything the library has no lowering for but the JDK has a charset for
(`RegexEncodings.needsTranscode`) — is decoded to UTF-8 *bytes* by `Transcode.wrap` before
the window machinery reads it, and everything downstream compiles and runs as a UTF-8 feed:
delimiters, steps, regexes, capture decoding. Malformed input follows §6.6's rule that no
default silently corrupts: `report` by default — surfacing through the executor's existing
stream-failure contract as a FATAL message naming the charset — and the source's
`ignore_errors` selects `replace`. The transcoder holds back a trailing high surrogate per
chunk so astral characters survive chunk boundaries. A per-template transcode-family
declaration is refused with directions ("declare it on the source, where the stream can be
transcoded whole"), because a template shares the source's byte stream and has nothing it
could transcode alone.

**Scope, honestly bounded.** Spans downstream are offsets into the transcoded bytes —
§4.0's accepted trade for encodings that never preserved offsets. The checkpointed
source-offset map, the fixed-length and step-referenced extents, and the `transcode(...)`
scope combinator all belong to the composition layer's vocabulary and stay with E14's
lowering question, not this stage. The E29 refusal's reachable set on a full JRE is now
empty — it remains as the guard for slim runtimes whose charsets are absent, and its pins
moved to the template-override refusal.

**Audited same day, completing the pattern.** Two findings acted on. A template
*single-byte* override under a transcoded source compiled a table machine over what is now
a UTF-8 stream — E3's per-template encodings describe rows of a mixed byte stream, and a
transcoded source has none left, so *any* template override under transcode is refused with
the explanation, pinned. And the transcoder's surrogate hold-back proved unreachable
through `InputStreamReader` — whose `StreamDecoder` happens to never split pairs across
reads — so its first mutation test survived; the `Reader` contract makes no such promise,
so the hold-back stays, now pinned through a reader-fed test entry against a reader that
splits pairs on purpose, and the end-to-end test's comment stops claiming a boundary it
cannot reach. Verified clean: EOF-with-pending emits the replacement and terminates,
bounds and exception routing (plain `IOException` rides the window machinery's existing
wrap; the report's `UncheckedIOException` lands in the executor's FATAL contract), AUTO
stays outside the transcode family, and `needsTranscode`'s cache path costs one read.

---

Folded issues, for the ledger cross-refs: **E29** (driver; stopgap phase 0, closes phase
3), **E22** (doctrine inherited by every refusal here), **E3/E5** (the prior art this
completes: the third vocabulary joins the two that already honour declared encodings),
**D38** (per-encoding strictness statements land with each phase), **D30** (phase 2 retires
the port artifact it left). The tier-0 strictness deviation and its pin are UTF-8-specific
and unmoved by any phase.
