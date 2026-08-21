# Strict dispatch — the cursor moves only by matching at it

Status: **proposal, for discussion**. Nothing here is implemented, and the numbered questions
at the end are the user's to decide. Written 2026-08-21 out of the anchoring investigation
([06-performance-plan.md §1](06-performance-plan.md), [10-engine-compilation.md §§6–10](10-engine-compilation.md)).

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

## 3. Explicit consumption — eaters

The replacement for implicit skipping is a template that *matches* the content to be
discarded, authored at the end of the group where its priority is lowest:

- **`consume: line`** — one line including its terminator. The idiomatic eater: line-shaped
  data skips line-wise, and the whole group iterates line-by-line at anchored-question speed.
- **`consume: bytes(n)`** — the `TakeN` shape; `bytes(1)` is the honest spelling of DS3's
  advance-by-one, priced visibly (skipping N bytes costs N passes — documented as the
  expensive spelling, for binary formats).
- **`consume: until(...)`** — scan to a delimiter; the one eater that is search-shaped
  inside, kept cheap by the library's first-byte machinery.

An eater is a template: it can have a body (usually empty, often just an error emission), it
is visible in review, and deleting it makes the group strict about that content.

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

## 5. Backward compatibility — and the subtlety that constrains it

Two strategies, not mutually exclusive:

- **A. A `search` dispatch mode retained in the engine** — current DS3-faithful behaviour,
  per group or per source. Two code paths, cheap fidelity, frozen goldens untouched.
- **B. Migration mapping** — `Ds3Migration` rewrites implicit behaviour into explicit
  config: a trailing `consume: bytes(1)` + `emit_error` reproducing the skip warnings.

**The subtlety: B is not semantics-preserving in multi-template groups.** DS3's winner
selection is *template-priority over position* — the first template in list order that
matches **anywhere** wins, even if a later-listed template matches earlier in the buffer. A
strict group with a byte eater is *position-priority over template*: the first cursor
position where any template matches decides, and only list order among templates at that
position. Where list order used to beat buffer position (exactly the E16/E18 territory —
win_sec's ordering bugs came from here), migrated configs would select different winners and
goldens would shift. So B alone cannot silently replace A for existing configs; it is either
B-with-accepted-golden-changes (each audited, as E6/E7 were) or A for legacy and B's style
for new configs. `matchOrder="any"` needs search dispatch regardless (§6).

## 6. What is preserved

- **Advance-to-group** (`advance` attribute): match a whole line, advance only to the end of
  group *g*. Unchanged — it is explicit, authored, and often essential.
- **`matchOrder="any"`** is search-shaped by definition (matched spans removed from the
  middle, prefix retained). Strict applies to sequence groups only; the two flags exclude
  each other, and any-mode templates are not expected to be anchored.
- **Guards, match limits, captures, stores, refs** — untouched.

## 7. Zero-advance matches

A match that does not move the cursor is today a silent level-ender; in a loop whose only
exit is "no progress", it is also one bug away from spinning forever. Proposal, two layers:

- **Compile time:** the library publishes each pattern's minimum match length (it already
  computes `byteLength[min,max]`; publishing follows the `leadingAnchor()` precedent — the
  parser's fact, one source). A strict-group pattern that *can* match empty is a
  compile-time error naming the template.
- **Run time** (still needed — advance-to-group can reach zero through an empty group even
  when the whole match is non-empty): a zero-advance match is an **error**, and the pass
  moves to the next template rather than accepting the non-advancing winner — "try matchers
  until one advances", bounded by the pass. Whether the zero-advance winner's body should
  have run at all is Q6.

## 8. What this does to the published anchor fact

In strict groups, `leadingAnchor()` stops buying speed — the anchored *question* carries the
anchoring, whatever the pattern says. It becomes a **validator**:

- A strict-group pattern that opens `.*` or is LINE-anchored (`(?m)^`) probably expects to
  search; warn with the template's name: *in a strict group this matches at the cursor only —
  did you mean a line eater above it?*
- A search-group pattern that is INPUT-anchored gets today's fast path, unchanged.

The performance role survives wherever search dispatch survives (§5 A, `matchOrder="any"`).

## Decisions required

1. **Compat strategy**: keep `search` mode in-engine, migrate-with-audited-golden-changes,
   or both — and which is the default for newly authored configs?
2. **Eater vocabulary**: `line` / `bytes(n)` / `until(...)` as match atoms — right set, right
   spelling?
3. **Eater counting**: do eater matches count toward `min/maxMatch` and `__match_count`?
   (Lean: no, by default — else every relative reference in the group shifts.)
4. **Error paths**: severity set; can an error path abort the record or stream, or only
   report?
5. **Publish `minLength()`** from the regex library for empty-match validation (same
   single-source principle as `leadingAnchor()`)?
6. **Zero-advance**: error-and-try-next (proposed) vs error-and-exit; and does the
   non-advancing winner's body run?
7. **Validation strictness**: are §8's lints warnings or errors?
