# Fixture audit

Eleven of the eighteen `projects` goldens were produced by ds-rs from its own output, by an
`#[ignore]`d helper called `gen_native_fixture_outputs`. D33 says generation is a starting
point, not a warrant: each of those eleven is checked once, here, and thereafter is a frozen
golden like any other. This is that check, run on 2026-08-20 during phase 0 of the port.

**Four of the eleven are wrong.** They are quarantined in `fixtures/status.txt` — vendored,
never run, and not promotable until a corrected golden replaces them. The other seven pass
every check applied and are now ordinary goldens.

The `legacy` goldens are not in scope here. They came from Java Stroom's own DS3 and are an
external oracle; auditing them would mean auditing Stroom.

## What was checked

Five things, chosen because each can fail silently behind a byte-equality comparison:

1. **Well-formedness** — every XML golden parsed; every JSON golden parsed line by line.
2. **Record count** — the number of output records against the number of input records,
   counted from the input's own delimiter. This is the check that would catch a dropped record.
3. **Empty elements** — `<X></X>` pairs, each traced back to the input to see whether the
   input had a value the output lost.
4. **Literal escapes** — `\n` surviving as two characters where a newline was meant.
5. **Unescaped markup** — a raw `&` or `<` reaching an attribute or text node.

Record counts are exact everywhere: ausearch 16/16, win_app 15/15, win_sec 11/11, win_app_xml
15/15, win_sec_xml 11/11, json_to_xml 12/12, apache_httpd 28/28. **No fixture drops a record.**
Every defect below is inside a record.

## The four defects

### `apache_httpd` — the golden is not well-formed XML

Two separate problems in one file.

A URL captured from the input carries a query string, and it is written into an attribute
without escaping:

```
<Data Name="URL" Value="/reports/quarterly?year=2026&q=1"/>
```

A bare `&` in an attribute value is not well-formed, so the golden cannot be parsed at all
(line 43, column 69). Everything downstream that treats engine output as XML — which in Stroom
is everything — would reject it.

Separately, 56 of the config's `text` nodes contain a literal backslash-n rather than a
newline, so most of the document's structure is one enormous line reading
`\n  <Event>\n    <EventTime>\n…`, while the `<Data/>` lines a few nodes away use real
newlines. That one is a defect in the fixture's `project.json` — something double-escaped it
during a migration — rather than in the engine, and the engine reproduced it faithfully. Both
have the same consequence for us: the file does not describe output any implementation should
aim at.

### `xml_to_json` — the golden is not valid JSON

The fixture flattens nested XML into JSON, and loses the braces:

```
input   <record><name>Alice</name><address><city>London</city><country>UK</country></address></record>
golden  {"name":"Alice","age":"30","address":"city":"London","country":"UK"}
```

`"address":"city":"London"` is not JSON — a key whose value is a key. Three of the four lines
are invalid; the fourth is the one record with no nesting. Note that `xml_to_json_attrs` and
`xml_to_json_unified` do the same job with different configs and both produce valid JSON, so
the engine can express this correctly; this config does not.

### `win_sec` and `win_sec_xml` — the goldens drop group identity

*Diagnosed 2026-08-20; see E6 in [the issue list](../stroom-shapeshifter-engine/ISSUES.md). It is
a configuration defect, not an engine one — the template before `Group` uses `(?ms)` where it
means `(?m)`, and dot-all makes its trailing capture swallow the Group block. The text is not
lost; it is inside the `MemberDN` attribute. The paragraphs below stand as the audit recorded
them.*

Eleven elements come out empty across each file — `Id`, `Name`, `Type`, `Domain`. Tracing one
back to the input shows data that plainly exists being lost:

```
input   Group:
            Security ID:    S-1-5-32-551
            Group Name:     Backup Operators

golden  <AddGroups>
          <Group>
            <Id></Id>
            <Name></Name>
          </Group>
        </AddGroups>
```

The record count is right and the XML is well-formed, which is precisely why byte-equality
never noticed: the golden faithfully records a failed extraction. A port that reproduced it
would be bug-compatible with an unnoticed bug.

## The seven that pass

`ausearch`, `identity_transform`, `json_to_xml`, `win_app`, `win_app_xml`,
`xml_to_json_attrs`, `xml_to_json_unified` — well-formed, correct record counts, no empty
elements, no stray escapes. They are frozen goldens from here on.

One note on naming, since it looks like a defect and is not: `identity_transform` is not an
identity. It renames `oldName` to `newName`, and its output is the same size as its input only
because the two names are the same length.

## What happens to the four

They stay vendored and quarantined. Correcting them is post-port work, and it needs the engine
first — a corrected golden has to be produced by something, and the only two candidates are a
fixed config run through a working engine, or Java Stroom where an equivalent config exists.
Until then the ledger records the defect next to the fixture, which is the point: a fixture
that cannot be trusted should say so where it lives, not in someone's memory.

**All four turned out to be configuration defects rather than engine defects.** `apache_httpd`'s
escaping and literal newlines and `xml_to_json`'s missing braces were evident at the time;
`win_sec` was the one that might have been an engine bug, and the re-examination this section
called for was done once the port was complete. It is not: both engines produce the same output
from the same configuration, and the configuration is wrong. The method that settled it — run the
failing template's pattern against the record on its own, and if it matches, look at what the
template before it consumed — is worth reusing on E16.
