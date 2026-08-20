# The fixture corpus

The test corpus of the Rust `shapeshifter` project, vendored from `ds-rs` at commit
`996aa7acb9c0820cd7617d62cc799ca37160e0f1` on 2026-08-20. It is the acceptance test for the
port ([D33](../../../../design/00-decisions.md),
[07-engine-port-plan.md](../../../../design/07-engine-port-plan.md)).

`status.txt` is the index and the ratchet. Every fixture the suite knows about has a line
there; nothing is discovered by walking directories, so a fixture cannot be vendored and then
silently ignored. `PENDING` does not mean "skip" — the suite asserts the fixture still fails,
so when a phase of the port makes one work the build breaks until the line is promoted.

## The three families

- **`legacy/`** — 19 DS3 XML configurations with their inputs, and golden output produced by
  **Java Stroom's own DS3**. These are the corpus's real external oracle. One,
  `008_invalid_xml_FAIL`, has no golden output because its expectation is that the config is
  *rejected*; the Rust suite skips it, ours asserts the rejection.
- **`native/`** — 18 hand-written `project.json` configurations that reproduce the legacy
  configs in the modern format. They reuse the legacy inputs and the same Stroom goldens, so
  they check the new config format against the old engine's behaviour.
- **`projects/`** — 18 end-to-end fixtures with their own inputs and expected output. Three
  need Avro, Parquet or Protobuf and are skipped while those are deferred; ds-rs's own
  default-features run skips the same three.

## Two things that are ours, not ds-rs's

**`*.messages`** — the message goldens. The Rust runners compare output only, leaving the
warning and error paths dark, and two fixtures ship `.err` files that nothing reads. Each
legacy fixture now has a `.messages` file recording the severity and text of every message the
engine raises, in order. This is the one deliberate deviation from porting faithfully, and it
is test-side: it cannot change output parity, only add signal.

The `.err` files stay vendored beside them as external evidence, but they are *not* the
expectation. They are Stroom's format — `DS3Parser [2:1] ERROR:` with element paths and the
original XML — which the ported engine does not produce and is not being asked to. Where they
can be compared, they agree on substance: `005`'s unmatched content is character-for-character
the same in both, and `014`'s three `minMatch` failures are three errors in both. They differ
on severity, Stroom calling `005` an ERROR where the engine warns.

Recording those messages immediately turned up behaviour worth knowing about, none of it
visible to output comparison:

- `001_csv_with_header` warns that the whole body is unconsumed. Its first `<split maxMatch="1">`
  is a header template that is *supposed* to stop after one line, so any `maxMatch` template
  will trigger this. A false positive by construction.
- `011_ignore_group_errors` and `012_ignore_root_errors` — the two fixtures that exist to test
  that errors are ignored — both still emit a warning.
- `003_multiline_regex` raises 19 warnings on a fixture whose output is correct.

These are recorded, not endorsed. The goldens capture what the Rust engine does, because that
is what a port is faithful to; they are also the top candidates for a decision after the port.

**Quarantine.** The phase 0 audit found four `projects` goldens wrong — they were generated
from ds-rs's own output and never checked. They are vendored, marked `QUARANTINED` in
`status.txt`, and cannot be promoted until a corrected golden replaces them. The findings are
in [08-fixture-audit.md](../../../../design/08-fixture-audit.md).

## What was left behind

Four files in the Rust corpus are not fixtures and were not vendored: `migrate_captures.py` and
`migrate_fixtures.py` (one-off migration scripts), `projects/ausearch/actual_output.xml` (a
debugging leftover, byte-identical to the golden beside it), and
`projects/win_app_xml/project_graph.json` (a node-editor artifact no runner reads).

The four fixture *regenerators* in the Rust suite were not ported either, and deliberately: a
fixture that can rewrite its own expectation is not a test.
