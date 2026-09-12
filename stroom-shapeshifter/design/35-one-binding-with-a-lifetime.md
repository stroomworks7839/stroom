# Design 35 — One binding, with a lifetime and a multiplicity

*Opened 2026-09-11, after E49 was built, measured against the corpus, and reverted.*

**Status: open. Nothing is built. §7 lists what has to be ruled before anything is.**

## 1. The observation this starts from

Captures and variables are not two things. They are one thing with different **binding times**.

Everything that binds a name in a run writes through the same two calls — `vars.store(name)` and
`vars.put(name, store)` — into one `VarRegistry`, one slot space, one `Store`. The compiler's own
refusal message already says so: *"no capture, variable, transform bind or parameter has that
name."* Four binders, one namespace, and a reference cannot tell which of them wrote what it reads.

| binder | bound when | value from |
|---|---|---|
| capture | before the body runs, so a later template can read it | the match |
| variable | during the body, in order | running instructions |
| parameter, call argument | at the call | the caller |
| transform bind (`as`) | at the instruction | the transform's result |

A capture is a variable whose value comes from the match and is bound before the body runs. That
is the whole difference.

## 2. What is actually missing, stated three ways

**One namespace, four binders, one lifetime rule.** Only compiled captures have a rule: E19's
clear on the template's first match of a dispatch. Key-value captures are excluded from
`clearNames` outright. Variables, parameters and transform binds have no rule at all — and E19's
own residual predicted they would "tail-leak in the same shape", which E28 then was.

**A scope mechanism that captures never touch.** `VarRegistry` already has real scopes — `push`,
`pop`, `shadow`, an undo log that restores on unwind. Every one of its callers is `Body`:
grouping, for-each, variables, calls. Captures are bound outside any push. So the engine has a
working lifetime mechanism and a lifetime problem, and they are not connected.

**Three meanings for one index.** A `Store` is `TypedValue[]` addressed by an int, and the int is
always "the declaring template's match count" — but what a *match* is differs per template, so
the same number means three different things:

| binder | what the index counts |
|---|---|
| compiled capture on a per-record template | which record |
| compiled capture on a per-column template | which column |
| key-value capture on a per-token template | which token of the record |
| parameter, loop variable, transform bind | nothing — the scalar convention, always 1 |

`latest()` — the highest populated index — is well defined in all of them and *means* something
in only some of them.

## 3. Why every fix so far has broken something else

Three rules have been proposed for one question, each inferred from where a template sits rather
than from what its author meant:

- **E19's rule** — clear on the template's first match — mirrors DS3's
  `parentMatchCount == 0 → clearStores()`. Half fixed, half pinned, resolved 2026-08-20.
- **No rule** for key-value captures, which is why a record that *did* write `key` can still read
  an earlier record's token (§4).
- **E49's rule** — clear on entering a level. Built 2026-09-11 and reverted the same day.

E49's failure is the informative one. It fixed its first demonstration and broke four golden
fixtures — `win_sec`, `win_sec_strict`, `win_app`, `win_app_xml` — because **DS3 carries captures
forward and the goldens record it.** `DS3Parser.parse` calls `root.clear()` once per stream and
nothing clears between records. Direct evidence: the 4625 event at `win_sec/input.txt` line 165
has no `Logon Type:` line at all, and DS3's own output still emits
`<LogonType>Interactive</LogonType>`, carried from the 4624 event at line 21.

E49's design had already rejected "a scope per dispatch" on the grounds that it destroys the CSV
headings. That was the signal: the mechanism could not express *"this one is per-record and that
one is per-stream"*, so it had to pick one and lose the other. Three rules, each correct for the
case it was derived from, is what a missing concept looks like.

## 4. The narrow defect inside the broad one

E49's third demonstration is not the pinned case and is worth separating out.

```
a=1 b=2 key=old
key=new          → reads "old"
```

The second record **does** bind `key`. The kv template matches once, writing index 1; the first
record's three tokens wrote indices 1–3, and nothing cleared, so `latest()` finds `old` at 3.

This is fixable on its own by clearing key-value captures per dispatch, and **it does not touch
DS3 fidelity, because DS3 has no key-value captures to be faithful to.** It is a Shapeshifter
construct whose lifetime was never settled. Whether to take it now as a patch or only as part of
this design is §7's first ruling.

## 5. Lifetime and multiplicity are different questions

This is the decomposition the whole design turns on, and it is the owner's.

**Lifetime** is *how long a binding survives* — and belongs on scopes. A frame stack, where a
lookup walks outward and an inner frame may either shadow an outer binding or surface it. The
mechanism exists (`VarRegistry`'s scopes, `Frames` for the engine's own); what is missing is that
a binding cannot *declare* which frame it belongs to, so the engine guesses from dispatch shape.

**Multiplicity** is *how many values a binding holds and how they are addressed* — and is not a
lifetime question at all. The CSV heading case is the proof:

```json
{"capture": {"var_id": "heading", "match_index": {"var_ref": "__match_count"}}}
```

`heading` holds one value per column, captured by `header_column` in one dispatch, and read by
`data_column` in a different dispatch on every subsequent row — the Nth column reading the Nth
heading. It is a **multi-valued binding addressed by the reader's own match index**, and the
whole point is that it outlives the row that made it.

Conflating the two is why each proposed rule broke the other case. Clearing per record is right
for a per-record binding and destroys a multi-valued one. Keeping everything is right for the
headings and leaks for everything else.

So a binding wants to say two orthogonal things: **how long** it lives, and **how many** values it
holds and what indexes them. Today it says neither, and the engine infers both.

## 6. What this would subsume

A concept earns its place by closing open questions rather than adding one. This one would take:

- **E19's pinned half** — becomes "DS3 lifetime, declared", faithful by construction for migrated
  configurations rather than by a heuristic that happens to match.
- **E49's demonstration 3** — becomes "record lifetime", which nothing can currently express.
- **E19's residual and E28** — `Variable` and transform results tail-leaking "in the same shape"
  is the same missing declaration, on binders that never had a rule.
- **The `latest()` question** — a multiplicity that states what indexes it can say whether
  `latest()` is meaningful, instead of it being well defined and meaningless.

And it should **supersede E49**, which is wrong as written: its first two demonstrations are E19's
deliberately pinned behaviour, rediscovered without finding E19.

## 7. What has to be ruled before anything is built

1. **Take §4's narrow fix now, or only within this design?** It is small, safe and does not touch
   the goldens.
2. **Does a migrated DS3 configuration declare DS3's lifetime, or does the engine keep a DS3
   mode?** The first keeps one engine with a declared difference; the second keeps two behaviours.
3. **What is the default lifetime for a native configuration that declares nothing?** Whatever it
   is will be what most configurations get.
4. **Does an inner frame shadow an outer binding of the same name, or surface it?** The owner's
   note is that it might do either; if both are wanted, that is a third thing a binding declares.
5. **Is multiplicity declared, or inferred from the binding time?** A per-column capture is
   plainly multi-valued and a call argument is plainly scalar; the awkward case is the key-value
   capture, which is many bindings under many names rather than one binding with many values.

## 8. How it would be gated

The corpus cannot be trusted here and the reason is on the record: E49 passed every unit test it
was given and was caught only by four golden fixtures, and the thing that finally settled it was
reading DS3's source. So:

- **Every lifetime rule needs a fixture that distinguishes it from the others**, not a unit test
  that exercises the happy path. E49's three demonstrations are the start of that set.
- **DS3's source is the oracle for anything a migration emits**, not our reading of what is
  reasonable. `root.clear()` in `DS3Parser.parse` settled E49 in one grep after a day of
  reasoning.
- **The golden fixtures must not change.** If a rule changes one, either the rule is wrong or the
  divergence is deliberate and ruled — and E19 is the precedent for how that is recorded.

## 9. What would make this a mistake

- **If lifetime turns out to be genuinely per-binder** rather than per-scope, then four rules is
  the right answer and one concept is a forced unification.
- **If declaring lifetime pushes the choice onto authors who cannot make it.** DS3 authors never
  chose one; they got DS3's. A declaration that every configuration must carry, and that most get
  wrong, is worse than a default that is occasionally surprising.
- **If the scope stack cannot hold a multi-valued binding cheaply.** `VarRegistry`'s undo log is
  on the hottest path in the engine, and design 33 spent a week getting the run state to arrays.
  A lifetime mechanism that reintroduces per-binding bookkeeping there would pay for correctness
  with the throughput that work bought.
