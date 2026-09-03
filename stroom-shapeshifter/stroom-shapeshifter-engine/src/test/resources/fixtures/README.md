# The fixture corpus

The engine's acceptance corpus. Its oracle is Stroom's own DS3
([D41](../../../../../design/00-decisions.md)); its ledger is a ratchet.

`status.txt` is the index and the ratchet. Every fixture the suite knows about has a line
there; nothing is discovered by walking directories, so a fixture cannot be vendored and then
silently ignored. `PENDING` does not mean "skip" — the suite asserts the fixture still fails,
so when a phase of the port makes one work the build breaks until the line is promoted.

## The three families

- **`legacy/`** — 19 vendored DS3 XML configurations plus three of ours (020–022 — design 21
  phase 0 and its audit, 2026-09-03), with their inputs, and golden output produced by
  **Java Stroom's own DS3**: since D41 (2026-09-03) every `.out.xml` here is a byte-for-byte
  copy of `stroom-pipeline/src/test/resources/TestDS3/`, and 020 to 022 were produced through
  the same harness. The four the engine does not yet reproduce — 003, 007, 009, 019 — are
  `PENDING` under [E33](../../../../ISSUES.md) (DS3 trims) and E35 (Saxon wraps), and are the
  proof that the corpus is Stroom's, not the port's. One,
  `008_invalid_xml_FAIL`, has no golden output because its expectation is that the config is
  *rejected*; the Rust suite skips it, ours asserts the rejection.
- **`native/`** — 18 hand-written `project.json` configurations that reproduce the legacy
  configs in the modern format. They reuse the legacy inputs and the same Stroom goldens, so
  they check the new config format against the old engine's behaviour.
- **`projects/`** — 18 end-to-end fixtures with their own inputs and expected output. Three
  need Avro, Parquet or Protobuf and are skipped while those are deferred (D33).

  Five more sit beside them, each written to hold a feature the rest of the corpus cannot
  reach: `win_sec_strict`, `strict_kv`, `classify_alerts` and
  `lexer_tokens` for D36/E20's dispatch modes, and `log_sessions` for design/16 and /17 —
  iteration, grouping, keys, folds and value computation over delimited log lines. That last
  shape matters on its own: the catalogue in `stroom-shapeshifter-xmlbench` proves the same
  features against Saxon, but only over well-formed XML where no field is ever absent, and
  the first thing `log_sessions` did was find [E28](../../../../ISSUES.md) with an empty
  field.

## Two things beyond output parity

**`*.messages`** — the message goldens. Comparing output alone leaves the warning and error
paths dark (two fixtures ship `.err` files in Stroom's format that nothing reads). Each legacy
fixture has a `.messages` file recording the severity and text of every message the engine
raises, in order. Test-side: it cannot change output parity, only add signal.

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

**Quarantine — now empty.** Four `projects` goldens were found wrong when the corpus was
first audited — generated from the engine's own output and never checked. All four have since
been diagnosed, fixed at the configuration, and re-frozen under review: `win_sec` and
`win_sec_xml` (E6, E16), `apache_httpd` (E7) and `xml_to_json` (E8). Every one was a
configuration defect. The evidence is in [ISSUES.md](../../../../ISSUES.md).

