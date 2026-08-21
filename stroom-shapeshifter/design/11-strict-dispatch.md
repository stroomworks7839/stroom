# Strict dispatch — the cursor moves only by matching at it

Status: **decided 2026-08-21 except §7 (zero-advance), which is open**. Written out of the
anchoring investigation ([06-performance-plan.md §1](06-performance-plan.md),
[10-engine-compilation.md §§6–10](10-engine-compilation.md)); the rulings at the end are the
user's. The two modes are named **strict** and **lax**.

## 1. Why

Today's dispatch has three ways to move the cursor without any template matching at it, and
none of them was chosen — all are inherited:

1. **Skip consumption.** An unanchored match landing at cursor+N consumes the N unmatched
   bytes as a side effect, with a warning (unless `ignoreErrors`). The skip is an accident
   with an advisory note, not an authored decision.
2. **Buffer-exhaustion recovery.** DS3's level-0 `RECOVERY_MODE` advances by half the buffer
   to survive a match that swallowed everything.
3. **The zero-advance quirk.** A match with `advance() == 0` runs its body, counts as a
   match, and silently ends the level — behaviour by accident of loop shape.

These mechanics were authored into DS3 for two legitimate goals: **survive corrupted input**
and **guarantee the stream reaches its end**. The goals stay. The proposal replaces the
mechanism: both become things an author writes down, not things the engine does behind their
back. DS3 is precedent here, not oracle — its author says so.

Performance is the second motive and it follows automatically: a group whose every template
must match *at the cursor* asks only the anchored question — one attempt, ~15–110ns,
measured — and the unanchored-search cost structure (the [§2 gap-list
row](10-engine-compilation.md) about `(?m)` scans, the failed-search memo idea) dissolves for
such groups rather than being engineered around.

## 2. The rule

A **strict group** moves its cursor only by a template matching at it.

- Dispatch asks every template the anchored question. The pattern's own text need not start
  with `^` — the *question* anchors it. First match in list order wins; the choice reopens.
- When no template matches at the cursor: the group's authored failure path runs if one
  exists (an eater, §3, possibly emitting an error, §4); otherwise the engine reports
  *unmatched content at offset X* — today's trailing warning, arriving earlier, pointing at
  an exact byte — and the group exits.
- Nothing else moves the cursor. No skip consumption, no recovery advance.

## 3. Explicit consumption — eaters (decided: a marker, not a vocabulary)

The replacement for implicit skipping is a template that *matches* the content to be
discarded, authored at the end of the group where its priority is lowest. **There is no new
atom vocabulary**: an eater uses the same match expressions as every other template — regex,
delimiter, steps, whatever fits — and carries a **`consume` marker** telling the engine this
match exists to advance, not to count. One mechanism for matching; the marker only changes
what the match *means*:

- a consume-marked match does **not** advance `__match_count` / the match number — counting
  belongs to real matches, and an author who wants an eater to count simply removes the
  marker;
- its body still runs (usually empty, often one `emit_error`), its captures still bind;
- the optimiser must never prune it (its side effect *is* the cursor movement — an E10 note).

The idiomatic eater is a line consumer (`^[^\n]*\n?`); a one-byte consumer is the honest
spelling of DS3's advance-by-one, priced visibly at one pass per byte.

## 4. Error-emitting paths

A body instruction that emits into the run's message stream exactly as `value-of` emits into
output: severity, message text built from refs — so the message can quote the offending
content — and the engine's location attribution attached. The cautionary eater becomes:

```
{ "match": { "consume": "line" },
  "body": [ { "emit_error": { "severity": "warn",
              "message": ["unrecognised line: ", {"group": 0}] } } ] }
```

This is the authored replacement for DS3's engine-generated skip warnings — same
information, but written where it applies, hittable-by-design for paths added out of caution.
**Decided:** severities include `fatal`, and a fatal emission aborts the run (scope — record
vs stream — refinable when records exist as an engine concept).

## 5. Backward compatibility — decided: both modes, strict by default

The engine keeps two dispatch modes. **`strict`** is the default for newly authored
configurations; **`lax`** is today's DS3-faithful search dispatch, and migration marks
migrated DS3 configurations `lax` so their goldens stay frozen. An author converting a group
to strict deliberately simulates the old non-match advance with a trailing one-byte
`consume` eater (plus an `emit_error` if they want the old warnings) — accepting the
winner-selection change below with their eyes open:

**The subtlety: B is not semantics-preserving in multi-template groups.** DS3's winner
selection is *template-priority over position* — the first template in list order that
matches **anywhere** wins, even if a later-listed template matches earlier in the buffer. A
strict group with a byte eater is *position-priority over template*: the first cursor
position where any template matches decides, and only list order among templates at that
position. Where list order used to beat buffer position (exactly the E16/E18 territory —
win_sec's ordering bugs came from here), migrated configs would select different winners and
goldens would shift. That is why migration marks configs `lax` rather than rewriting them,
and why conversion to strict is an authoring act with audited output changes, not a mapping.
`matchOrder="any"` needs lax dispatch regardless (§6).

## 6. What is preserved

- **Advance-to-group** (`advance` attribute): match a whole line, advance only to the end of
  group *g*. Unchanged — it is explicit, authored, and often essential.
- **`matchOrder="any"`** is search-shaped by definition (matched spans removed from the
  middle, prefix retained). Strict applies to sequence groups only; the two flags exclude
  each other, and any-mode templates are not expected to be anchored.
- **Guards, match limits, captures, stores, refs** — untouched.

## 7. The control-flow space — dispatch modes, not markers

The `peek` proposal was withdrawn on the user's observation that it put control flow at two
levels: the group's match mode *and* per-template markers, clashing with `matchOrder="any"`
and failing shapes like "several matchers all inspect the same input, all have a go, then
exit." The underlying problem is that DS3's `matchOrder` was a **bundle** of independent
choices. Unbundled, a group's dispatch has four dimensions:

| Dimension | Choices |
|---|---|
| **Binding** | at-cursor (the anchored question) / search (leftmost) |
| **Iteration** | until-no-progress (the `*`) / once |
| **Selection** | first-in-list / every-matching-template / (longest-match, deferred) |
| **Consumption** | advance-to-end / excise-the-span / none |

Most of the 36 combinations are incoherent, and saying why prunes the space fast:
*consumption none* with *iteration until-no-progress* cannot terminate; *selection every*
with any consumption has no defined cursor; *excise* only means anything under *search*.
What survives is a short list of named modes:

| Mode | Binding | Iteration | Selection | Consumption | Standing |
|---|---|---|---|---|---|
| **`strict`** | cursor | until-no-progress | first | advance | new default (§2) |
| **`lax`** | search | until-no-progress | first | advance + skip | DS3 sequence, compat |
| **`any`** | search | until-no-progress | first | excise | DS3 any, compat |
| **`classify`** | search | **once** | **every** | **none** | **new** — the missing shape |
| *(lexer)* | cursor | until-no-progress | longest | advance | deferred — maximal-munch tokenising; recorded, not built |

**`classify`** is the user's scenario as a first-class mode: one pass over the region, every
template whose match succeeds runs its body and binds its captures (each at most once, its
leftmost match), nothing consumes, then the group exits. Classification, validation sweeps,
multi-angle flag-setting — shapes DS3 could not express at all. And *classify-then-consume*
needs no per-template marker either: it is **composition** — apply the same content to a
`classify` group, then to a `strict` group, two `apply-templates` in a row. Control flow
stays at exactly one level: the group.

Per-template markers that remain are precisely the ones that do not alter control flow:
`consume` (counting semantics, §3), guards, match limits.

### Zero-advance, resolved by the above

With legitimate non-consuming matching now owning proper homes — `classify` mode, the
progressive `Peek` step, composition — a zero-advance match in a consuming mode has no
innocent reading left. **Ruling proposed: error and exit the group, no exceptions, in strict
and lax alike** (lax today ends the level silently on zero-advance; the error is strictly
better diagnostics for the same halt). The self-healing scenario is served by fixing the
pattern or restructuring as classify-then-consume — both of which say what they mean.

## 8. What this does to the published anchor fact

In strict groups, `leadingAnchor()` stops buying speed — the anchored *question* carries the
anchoring, whatever the pattern says. It becomes a **validator**:

- A strict-group pattern that opens `.*` or is LINE-anchored (`(?m)^`) probably expects to
  search; warn with the template's name: *in a strict group this matches at the cursor only —
  did you mean a line eater above it?*
- A lax-group pattern that is INPUT-anchored gets today's fast path, unchanged.

The performance role survives wherever lax dispatch survives. **Decided:** the lints are
errors in strict mode, warnings in lax.

## Rulings (2026-08-21)

1. **Compat**: both modes, in-engine — `strict` (default for new configs) and `lax`
   (DS3-faithful; migration marks migrated configs lax). Strict conversion is an authoring
   act using a trailing one-byte `consume` eater, with audited output changes.
2. **Eaters**: no new vocabulary — any match expression plus a `consume` marker meaning
   "advance, don't count".
3. **Counting**: follows from 2 — consume-marked matches don't advance the match number;
   want counting, drop the marker. The engine's only obligation is never to prune them.
4. **Error paths**: severities with `fatal`; fatal aborts the run.
5. **`minLength()` publication**: no — `*`-authored patterns legitimately smell empty;
   handle zero-advance at run time instead.
6. **Zero-advance**: resolved via §7's control-flow unbundling — `peek` withdrawn;
   `classify` mode covers legitimate non-consuming matching; zero-advance in consuming
   modes is error-and-exit. *Awaiting the user's confirmation of the §7 mode table.*
7. **Lints**: errors in strict, warnings in lax.
