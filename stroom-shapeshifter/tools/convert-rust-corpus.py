#!/usr/bin/env python3
"""Convert the Rust `regex` crate's TOML test corpus into the line-based form read by
RustCorpusTest.

    git clone --depth 1 https://github.com/rust-lang/regex /tmp/regex
    tools/convert-rust-corpus.py /tmp/regex/testdata \
        stroom-shapeshifter-regex/src/test/resources/rust-regex

The conversion drops only tests that ask a *different question* from the one this engine
answers — a different match semantics, an API this engine does not offer, or a haystack that is
not UTF-8. It deliberately does NOT drop tests merely because this engine might refuse the
pattern: a refusal is a fact about scope, and RustCorpusTest counts refusals in its report, so
filtering them out here would hide the scope boundary rather than record it.

Nothing is altered beyond the change of file format. Source and licence are recorded in the
generated README.txt.
"""

import sys
import tomllib
from pathlib import Path

# Reasons a test is not comparable, counted and printed so the exclusions stay visible.
def excluded_reason(test, haystack):
    if isinstance(test.get("regex"), list):
        return "regex sets (several patterns searched at once)"
    if test.get("match-kind", "leftmost-first") != "leftmost-first":
        return "leftmost-longest match semantics"
    if test.get("search-kind", "standard") != "standard":
        return "earliest/overlapping search"
    if "match-limit" in test:
        return "match limits"
    if "line-terminator" in test:
        return "a configurable line terminator"
    if test.get("utf8") is False and any(ord(c) > 0x7F for c in haystack):
        # utf8 = false only changes what an engine may do *inside* a character: it permits
        # zero-width matches that split one, and byte-wise classes. Over an ASCII haystack there
        # is nothing to split, so those tests stay.
        return ("byte-oriented mode over a non-ASCII haystack, where a match may split a "
                "character; this engine reaches that through the RAW encoding, not through (?-u)")
    if test.get("compiles") is False:
        return "patterns Rust refuses to compile, for Rust's own reasons"
    return None


def unescape(text):
    """Rust's `unescape = true` form, which uses \\xNN to write bytes that TOML cannot."""
    out = bytearray()
    i = 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt == "x" and i + 3 < len(text) + 1:
                out.append(int(text[i + 2:i + 4], 16))
                i += 4
                continue
            mapped = {"n": b"\n", "r": b"\r", "t": b"\t", "\\": b"\\"}.get(nxt)
            if mapped is not None:
                out += mapped
                i += 2
                continue
        out += c.encode("utf-8")
        i += 1
    return bytes(out)


def haystack_bytes(test):
    raw = test["haystack"]
    return unescape(raw) if test.get("unescape") else raw.encode("utf-8")


def escape(text):
    """Back to one line, in the small escape vocabulary RustCorpusTest understands."""
    out = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 0x20 or ord(ch) == 0x7F:
            out.append("\\x%02x" % ord(ch))
        else:
            out.append(ch)
    return "".join(out)


def render_matches(matches):
    if not matches:
        return "-"
    rendered = []
    for match in matches:
        # A match is either [start, end] or a list of per-group spans, [] meaning absent.
        groups = [match] if match and isinstance(match[0], int) else match
        rendered.append(",".join("?" if not g else "%d-%d" % (g[0], g[1]) for g in groups))
    return ";".join(rendered)


def convert(source, target):
    target.mkdir(parents=True, exist_ok=True)
    kept_files = []
    excluded = {}
    total = 0
    kept = 0

    for toml in sorted(source.glob("*.toml")):
        with toml.open("rb") as handle:
            tests = tomllib.load(handle).get("test", [])
        lines = []
        for test in tests:
            total += 1
            try:
                haystack = haystack_bytes(test).decode("utf-8")
                reason = excluded_reason(test, haystack)
            except UnicodeDecodeError:
                reason = "haystacks that are not UTF-8"
            if reason is not None:
                excluded[reason] = excluded.get(reason, 0) + 1
                continue

            regex = test["regex"]
            if test.get("unicode") is False:
                # Rust's test-level `unicode = false` is the inline (?-u) this dialect spells the
                # same way. Here it selects the ASCII shorthands; in Rust it also switches the
                # whole pattern to byte semantics, which this engine reaches through the RAW
                # encoding instead — see 01-regex-language.md.
                regex = "(?-u)" + regex

            kept += 1
            lines.append("test %s/%s" % (toml.stem, test["name"]))
            lines.append("regex %s" % escape(regex))
            lines.append("haystack %s" % escape(haystack))
            if test.get("anchored"):
                lines.append("anchored true")
            if test.get("case-insensitive"):
                lines.append("caseless true")
            if "bounds" in test:
                bounds = test["bounds"]
                # Written either as a pair or as a table, so accept both spellings.
                start, end = ((bounds["start"], bounds["end"]) if isinstance(bounds, dict)
                              else (bounds[0], bounds[1]))
                lines.append("bounds %d %d" % (start, end))
            lines.append("matches %s" % render_matches(test["matches"]))
            lines.append("")

        if lines:
            (target / (toml.stem + ".cases")).write_text("\n".join(lines), encoding="utf-8")
            kept_files.append(toml.stem)

    (target / "index").write_text("\n".join(kept_files) + "\n", encoding="utf-8")
    (target / "README.txt").write_text(README % (
        "\n".join("  %4d  %s" % (count, reason)
                  for reason, count in sorted(excluded.items(), key=lambda e: -e[1]))), "utf-8")

    print("%d of %d tests converted into %d files" % (kept, total, len(kept_files)))
    for reason, count in sorted(excluded.items(), key=lambda e: -e[1]):
        print("  excluded %4d  %s" % (count, reason))


README = """Test cases derived from the Rust `regex` crate's corpus.

  Source:  https://github.com/rust-lang/regex/tree/master/testdata
  Licence: MIT OR Apache-2.0 (this project uses them under Apache-2.0)

Converted by tools/convert-rust-corpus.py from the original TOML into the line-based form read
by RustCorpusTest, so that this module keeps its zero runtime and test dependencies. Nothing was
altered beyond the change of file format.

Only tests asking a question this engine can answer at all were converted; the exclusions are:

%s

Tests this engine merely refuses to compile are NOT excluded here — RustCorpusTest counts those
in its report, so that the scope boundary stays visible rather than being filtered away.
"""


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    convert(Path(sys.argv[1]), Path(sys.argv[2]))
