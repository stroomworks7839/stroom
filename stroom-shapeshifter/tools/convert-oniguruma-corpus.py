#!/usr/bin/env python3
"""Convert Oniguruma's test_utf8.c into the line-based form read by OnigurumaCorpusTest.

    tools/convert-oniguruma-corpus.py \
        stroom-shapeshifter-regex/src/test/resources/oniguruma/test_utf8.c \
        stroom-shapeshifter-regex/src/test/resources/oniguruma/oniguruma.cases

The suite is three C macros: x2(pattern, text, start, end) asserts the whole match's span,
x3(pattern, text, start, end, group) asserts one group's span, n(pattern, text) asserts no
match. Spans are byte offsets over UTF-8, which is also this engine's coordinate system, so
they convert without translation.

The conversion drops, with a tally, only the cases whose spelling this parser would silently
misread: Oniguruma-only escapes that this dialect reads as literals (\\O, \\h, \\g<...>, \\c
and friends), and haystacks that are not valid UTF-8. It deliberately does NOT drop cases the
engine merely refuses to compile — a refusal is a fact about scope, and OnigurumaCorpusTest
counts refusals in its report, so filtering them here would hide the boundary rather than
record it. Cases that compile but disagree for a documented dialect reason belong in the
`ignore` file next to the output, never here.
"""

import re
import sys
from collections import Counter
from pathlib import Path

# Escapes Oniguruma gives a meaning that this parser reads as a plain literal instead. A case
# using one would silently test the wrong pattern, which is worse than not testing it at all.
# (\b inside a class is a backspace in Oniguruma but a parse error here, so it is caught, not
# silent; \Z and (?( and friends are refusals, which the Java test counts.)
SILENT_ESCAPES = set("aeghovyYHKORXNV")  # \a bell, \e escape, \g subroutine, \h hex, ...
SILENT_PREFIXES = ("c", "C", "M", "o{")  # control and meta escapes, and braced octal


def c_unescape(raw: bytes):
    """The C string reader: octal, hex and the single-character escapes."""
    out = bytearray()
    i = 0
    while i < len(raw):
        b = raw[i]
        if b != 0x5C:  # backslash
            out.append(b)
            i += 1
            continue
        nxt = raw[i + 1:i + 2].decode("latin-1")
        if nxt == "x":
            j = i + 2
            digits = ""
            while j < len(raw) and len(digits) < 2 and chr(raw[j]) in "0123456789abcdefABCDEF":
                digits += chr(raw[j])
                j += 1
            out.append(int(digits, 16))
            i = j
            continue
        if nxt.isdigit():
            j = i + 1
            digits = ""
            while j < len(raw) and len(digits) < 3 and chr(raw[j]) in "01234567":
                digits += chr(raw[j])
                j += 1
            out.append(int(digits, 8))
            i = j
            continue
        mapped = {"n": 0x0A, "r": 0x0D, "t": 0x09, "f": 0x0C, "v": 0x0B,
                  "a": 0x07, "b": 0x08, "e": 0x1B, "0": 0x00,
                  "\\": 0x5C, '"': 0x22, "'": 0x27}.get(nxt)
        if mapped is None:
            raise ValueError(f"unknown C escape \\{nxt}")
        out.append(mapped)
        i += 2
    return bytes(out)


def silently_misread(pattern: str):
    """The regex escape, if the pattern contains one this dialect reads as a literal."""
    i = 0
    while i < len(pattern):
        if pattern[i] != "\\":
            i += 1
            continue
        rest = pattern[i + 1:]
        for prefix in SILENT_PREFIXES:
            if rest.startswith(prefix):
                return "\\" + prefix
        if rest and rest[0] in SILENT_ESCAPES:
            return "\\" + rest[0]
        i += 2
    return None


def translate_flags(pattern: str):
    """Ruby's (?m) means dot-matches-newline, this dialect's (?s). Anchors need no flag at
    all: in Ruby ^ and $ are always line anchors, which the test compiles under MULTILINE."""
    return re.sub(r"\(\?([a-zA-Z-]*)([:)])",
                  lambda m: "(?" + m.group(1).replace("m", "s") + m.group(2),
                  pattern)


def more_silent_differences(pattern: str):
    """Constructs both dialects accept with different meanings — the dangerous kind. Class
    set operations and stacked quantifiers are NOT dropped here: the parser refuses both, so
    they flow through as refusals the Java test counts by reason."""
    if re.search(r"\\x[89a-fA-F][0-9a-fA-F]", pattern):
        return "\\xHH above 0x7F is a raw byte in Oniguruma, a code point here (as in Rust)"
    return None


def escape_field(text: str):
    """One logical line per field: backslash, newlines and C0 controls escaped."""
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
            out.append(f"\\x{ord(ch):02x}")
        else:
            out.append(ch)
    return "".join(out)


def main():
    source = Path(sys.argv[1]).read_bytes()
    out_path = Path(sys.argv[2])

    c_string = rb'"((?:\\.|[^"\\])*)"'
    call = re.compile(
        rb"^\s*(x2|x3|n)\(" + c_string + rb",\s*" + c_string + rb"\s*(?:,\s*([^)]*?))?\);",
        re.M)

    dropped = Counter()
    cases = []
    for match in call.finditer(source):
        kind, raw_pattern, raw_text, raw_args = match.groups()
        line = source.count(b"\n", 0, match.start()) + 1
        kind = kind.decode()
        args = [a.strip() for a in raw_args.decode().split(",")] if raw_args else []
        if (kind == "n" and len(args) not in (0, 1)) \
                or (kind == "x2" and len(args) not in (2, 3)) \
                or (kind == "x3" and len(args) not in (3, 4)):
            dropped["carries Oniguruma options or a non-numeric argument"] += 1
            continue
        if args and not all(a.lstrip("-").isdigit() for a in args[:3]):
            dropped["carries Oniguruma options or a non-numeric argument"] += 1
            continue
        if kind == "n" and args:  # n(pattern, text, option)
            dropped["carries Oniguruma options or a non-numeric argument"] += 1
            continue

        try:
            pattern = c_unescape(raw_pattern).decode("utf-8")
            text = c_unescape(raw_text).decode("utf-8")
        except UnicodeDecodeError:
            dropped["pattern or haystack is not UTF-8"] += 1
            continue
        except ValueError:
            dropped["unparseable C escape"] += 1
            continue

        misread = silently_misread(pattern)
        if misread:
            dropped[f"Oniguruma-only escape this dialect reads as a literal ({misread})"] += 1
            continue
        difference = more_silent_differences(pattern)
        if difference:
            dropped[difference] += 1
            continue
        pattern = translate_flags(pattern)

        if kind == "n":
            cases.append((line, pattern, text, 0, None))
        elif kind == "x2":
            cases.append((line, pattern, text, 0, (int(args[0]), int(args[1]))))
        else:
            cases.append((line, pattern, text, int(args[2]), (int(args[0]), int(args[1]))))

    lines = []
    for line_number, pattern, text, group, span in cases:
        # Named by source line in test_utf8.c, so the name survives converter changes and can
        # be looked up in the vendored file directly.
        lines.append(f"test L{line_number:04d}")
        lines.append("pattern " + escape_field(pattern))
        lines.append("input " + escape_field(text))
        lines.append(f"group {group}")
        lines.append("span " + (f"{span[0]}-{span[1]}" if span else "none"))
        lines.append("")
    out_path.write_text("\n".join(lines), encoding="utf-8")

    total = len(cases) + sum(dropped.values())
    print(f"{total} cases parsed, {len(cases)} converted, {sum(dropped.values())} dropped:")
    for reason, count in dropped.most_common():
        print(f"  {count:4d}  {reason}")


if __name__ == "__main__":
    main()
