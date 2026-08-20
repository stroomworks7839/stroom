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
can be compared they now agree in count, severity and substance: `005`'s unmatched content is
character-for-character the same in both at ERROR, and `014` is exactly Stroom's three errors —
the alignment D34/E17 was for.

Recording those messages immediately turned up behaviour worth knowing about, none of it
visible to output comparison:

- **[E1]** `001_csv_with_header` warns that the whole body is unconsumed. Its first `<split maxMatch="1">`
  is a header template that is *supposed* to stop after one line, so any `maxMatch` template
  will trigger this. A false positive by construction.
- **[E2]** `011_ignore_group_errors` and `012_ignore_root_errors` — the two fixtures that exist
  to test that errors are ignored — both still emit a warning.
- **[E1]** `003_multiline_regex` raises 19 warnings on a fixture whose output is correct.

All three were artifacts of the ported dispatch model, and were resolved by D34/E17: the
goldens now record the DS3-shaped reporting — `001`, `011` and `012` are empty, and `003` holds
eleven true errors in place of nineteen false warnings. The bullets stand as what recording the
messages found, which is what justified recording them.

**Quarantine — now empty.** The phase 0 audit found four `projects` goldens wrong — generated
from ds-rs's own output and never checked. All four have since been diagnosed, fixed at the
configuration, and re-frozen under review: `win_sec` and `win_sec_xml` (E6, E16),
`apache_httpd` (E7) and `xml_to_json` (E8). Every one was a configuration defect. The evidence
is in [08-fixture-audit.md](../../../../design/08-fixture-audit.md) and
[ISSUES.md](../../../../ISSUES.md).

## What was left behind

Four files in the Rust corpus are not fixtures and were not vendored: `migrate_captures.py` and
`migrate_fixtures.py` (one-off migration scripts), `projects/ausearch/actual_output.xml` (a
debugging leftover, byte-identical to the golden beside it), and
`projects/win_app_xml/project_graph.json` (a node-editor artifact no runner reads).

The four fixture *regenerators* in the Rust suite were not ported either, and deliberately: a
fixture that can rewrite its own expectation is not a test.
