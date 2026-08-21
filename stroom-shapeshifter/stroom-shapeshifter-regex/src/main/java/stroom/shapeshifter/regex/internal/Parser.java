/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.shapeshifter.regex.internal;

import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.PatternCompileException;
import stroom.shapeshifter.regex.PatternCompileException.Reason;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses a pattern into {@link Hir}.
 * <p>
 * Accepts the whole surface syntax, including the constructs beyond the RE2 subset —
 * backreferences, lookaround, atomic groups — which compile to the fancy tier rather than being
 * refused. Anything valid but not yet implemented is {@code Reason.UNSUPPORTED}, never a silent
 * approximation.
 */
public final class Parser {

    public record Result(Hir root, int groupCount, List<String> groupNames) {

    }

    private final String pattern;
    private final Set<Flag> flags;
    private int pos;
    private int groupCount;

    /** How many groups a surrounding composition had already defined; 0 for a standalone parse. */
    private int firstGroupIndex;

    /**
     * Group names indexed by group number — seeded with the surrounding composition's names (and
     * the group-0 slot), so {@code indexOf} arithmetic and duplicate detection both work in
     * absolute group numbers.
     */
    private final List<String> groupNames = new ArrayList<>();

    /** Numeric backreferences seen, as (group, position) pairs — validated after the whole
     * pattern is parsed, because a reference ahead of its group is legal: {@code (\2two|(one))+}. */
    private final List<int[]> backrefs = new ArrayList<>();

    private Parser(final String pattern, final Set<Flag> flags) {
        this.pattern = pattern;
        // Unicode is the default for the shorthands; (?-u) is how a pattern opts out.
        this.flags = EnumSet.of(Flag.UNICODE);
        this.flags.addAll(flags);
    }

    /** The group-0 slot, which has no name; the seed for a standalone parse. */
    private static final List<String> NO_OUTER_GROUPS = Collections.singletonList(null);

    public static Result parse(final String pattern, final Set<Flag> flags) {
        return parse(pattern, flags, NO_OUTER_GROUPS);
    }

    /**
     * Parses with group numbering continuing after {@code outerNames}, for a regex embedded in a
     * composition whose labels are also capture groups. {@code outerNames} lists the groups
     * already defined, by group number, with the group-0 slot first. The result's names are the
     * full list — the outer names followed by the groups this call created — so a name's index is
     * its group number, and an embedded name that collides with an outer label is refused just
     * like a reused name within one pattern.
     */
    public static Result parse(final String pattern,
                               final Set<Flag> flags,
                               final List<String> outerNames) {
        final Parser parser = new Parser(pattern, flags);
        parser.firstGroupIndex = outerNames.size() - 1;
        parser.groupCount = parser.firstGroupIndex;
        parser.groupNames.addAll(outerNames);
        final Hir root = parser.parseAlternation();
        if (parser.pos < pattern.length()) {
            throw parser.fail(Reason.SYNTAX, "unbalanced ')'");
        }
        parser.validateBackrefs();
        return new Result(root, parser.groupCount, parser.groupNames);
    }

    /**
     * Parses a standalone class expression — {@code [a-z_]}, {@code [^,]} or a shorthand such as
     * {@code \d} — for the composition layer's predicates. Reusing the pattern parser is what
     * keeps class semantics identical between a composed {@code takeWhile} and the equivalent
     * regex, including the UTF-8 compilation.
     */
    public static CodePointSet parseClassExpression(final String expression) {
        final Parser parser = new Parser(expression, EnumSet.noneOf(Flag.class));
        if (expression.isEmpty()) {
            throw parser.fail(Reason.SYNTAX, "empty class expression");
        }
        final Hir parsed = expression.charAt(0) == '['
                ? parser.parseCharClass()
                : parser.parseAtom();
        if (parser.pos < expression.length()) {
            throw parser.fail(Reason.SYNTAX,
                    "a class expression must be a single class, such as [a-z] or \\d");
        }
        if (parsed instanceof Hir.Bytes bytes) {
            // A one-character literal is a singleton class. A single-byte character already
            // arrives as one ("a" parses to a CharClass); a multi-byte character parses to a
            // byte sequence, and rejecting "é" while accepting "a" would make acceptance
            // depend on the character's encoded length.
            final int codePoint = Utf8.decode(bytes.value(), 0, bytes.value().length);
            if (codePoint >= 0 && Utf8.encodedLength(codePoint) == bytes.value().length) {
                return CodePointSet.single(codePoint);
            }
        }
        if (!(parsed instanceof Hir.CharClass charClass)) {
            throw parser.fail(Reason.SYNTAX,
                    "'" + expression + "' is not a character class");
        }
        return charClass.set();
    }

    // -----------------------------------------------------------------------------------
    // Grammar
    // -----------------------------------------------------------------------------------

    private Hir parseAlternation() {
        final List<Hir> branches = new ArrayList<>();
        branches.add(parseConcat());
        while (peek() == '|') {
            pos++;
            branches.add(parseConcat());
        }
        return branches.size() == 1
                ? branches.getFirst()
                : new Hir.Alt(branches);
    }

    private Hir parseConcat() {
        final List<Hir> items = new ArrayList<>();
        while (pos < pattern.length() && peek() != '|' && peek() != ')') {
            final Hir item = parseQuantified();
            if (!(item instanceof Hir.Empty)) {
                items.add(item);
            }
        }
        return switch (items.size()) {
            case 0 -> new Hir.Empty();
            case 1 -> items.getFirst();
            default -> new Hir.Concat(items);
        };
    }

    private Hir parseQuantified() {
        final int atomStart = pos;
        final Hir atom = parseAtom();
        if (pos >= pattern.length()) {
            return atom;
        }

        final int min;
        final int max;
        // Assigned exactly once on every path that does not return early.
        switch (peek()) {
            case '*' -> {
                min = 0;
                max = Hir.Repeat.UNBOUNDED;
                pos++;
            }
            case '+' -> {
                min = 1;
                max = Hir.Repeat.UNBOUNDED;
                pos++;
            }
            case '?' -> {
                min = 0;
                max = 1;
                pos++;
            }
            case '{' -> {
                final int[] bounds = tryParseBounds();
                if (bounds == null) {
                    return atom; // a literal '{'
                }
                min = bounds[0];
                max = bounds[1];
            }
            default -> {
                return atom;
            }
        }

        if (atom instanceof Hir.Assertion) {
            throw fail(Reason.SYNTAX, "cannot quantify a zero-width assertion", atomStart);
        }

        boolean greedy = true;
        boolean possessive = false;
        if (pos < pattern.length()) {
            if (peek() == '?') {
                greedy = false;
                pos++;
            } else if (peek() == '+') {
                // a*+ is (?>a*): match greedily, then never give anything back.
                possessive = true;
                pos++;
            }
        }
        if (max != Hir.Repeat.UNBOUNDED && max < min) {
            throw fail(Reason.SYNTAX, "repetition maximum is below its minimum", atomStart);
        }
        if (peek() == '{' && tryParseBounds() != null) {
            // a*{2} would otherwise read as a* followed by a literal {2}, which is a silent
            // approximation of something Rust and the JDK both refuse. (Oniguruma multiplies
            // stacked quantifiers, so silence here would also quietly disagree with it.)
            throw fail(Reason.SYNTAX,
                    "a quantifier cannot be applied to a quantifier; group the repetition, or "
                    + "escape the brace to match it literally", atomStart);
        }
        final Hir repeat = new Hir.Repeat(atom, min, max, greedy);
        return possessive
                ? new Hir.Atomic(repeat)
                : repeat;
    }

    /** Returns {min, max}, or null if this is not a bounds expression and '{' is a literal. */
    private int[] tryParseBounds() {
        final int close = pattern.indexOf('}', pos);
        if (close < 0) {
            return null;
        }
        final String body = pattern.substring(pos + 1, close);
        if (!body.matches("\\d+(,\\d*)?")) {
            return null;
        }
        final String[] parts = body.split(",", -1);
        final int min = parseBound(parts[0]);
        final int max = parts.length == 1
                ? min
                : (parts[1].isEmpty()
                        ? Hir.Repeat.UNBOUNDED
                        : parseBound(parts[1]));
        pos = close + 1;
        return new int[]{min, max};
    }

    /** A repetition bound, already known to be all digits — so a parse failure means overflow. */
    private int parseBound(final String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (final NumberFormatException e) {
            throw fail(Reason.SYNTAX, "repetition bound " + digits + " is too large");
        }
    }

    private Hir parseAtom() {
        final char c = pattern.charAt(pos);
        return switch (c) {
            case '(' -> parseGroup();
            case '[' -> parseCharClass();
            case '.' -> {
                pos++;
                yield Hir.CharClass.of(flags.contains(Flag.DOT_ALL)
                        ? CodePointSet.all()
                        : CodePointSet.single('\n').negate(), ".");
            }
            case '^' -> {
                pos++;
                // Resolved here rather than at match time, so that an inline (?m) applies to the
                // anchors it actually encloses instead of to whatever the caller passed in.
                yield new Hir.Assertion(flags.contains(Flag.MULTILINE)
                        ? Hir.Kind.START_LINE
                        : Hir.Kind.START_INPUT);
            }
            case '$' -> {
                pos++;
                yield new Hir.Assertion(flags.contains(Flag.MULTILINE)
                        ? Hir.Kind.END_LINE
                        : Hir.Kind.END_INPUT);
            }
            case '\\' -> parseEscape();
            case '*', '+', '?' -> throw fail(Reason.SYNTAX, "dangling quantifier");
            default -> {
                // Read a whole code point: an astral character arrives as a surrogate pair.
                final int codePoint = pattern.codePointAt(pos);
                pos += Character.charCount(codePoint);
                yield literal(codePoint);
            }
        };
    }

    private Hir parseGroup() {
        final int start = pos;
        pos++; // '('
        int index = -1;
        String name = null;

        if (peek() == '?') {
            if (pattern.startsWith("(?:", start)) {
                pos += 2;
            } else if (pattern.startsWith("(?=", start) || pattern.startsWith("(?!", start)) {
                final boolean negated = pattern.charAt(start + 2) == '!';
                pos += 2;
                final Hir body = parseAlternation();
                expect(')', start);
                return new Hir.Look(body, false, negated);
            } else if (pattern.startsWith("(?<=", start) || pattern.startsWith("(?<!", start)) {
                final boolean negated = pattern.charAt(start + 3) == '!';
                pos += 3;
                final Hir body = parseAlternation();
                expect(')', start);
                return new Hir.Look(body, true, negated);
            } else if (pattern.startsWith("(?>", start)) {
                pos += 2;
                final Hir body = parseAlternation();
                expect(')', start);
                return new Hir.Atomic(body);
            } else if (pattern.startsWith("(?<", start)) {
                final int close = pattern.indexOf('>', pos);
                if (close < 0) {
                    throw fail(Reason.SYNTAX, "unterminated group name", start);
                }
                name = pattern.substring(pos + 2, close);
                pos = close + 1;
                // Both reference dialects refuse a reused name, and \k<name> would silently
                // bind to the first occurrence here if this did not.
                if (groupNames.contains(name)) {
                    throw fail(Reason.SYNTAX, "duplicate group name '" + name + "'", start);
                }
                index = ++groupCount;
                groupNames.add(name);
            } else {
                // Inline flags: (?i), (?is), (?i:...)
                pos++; // '?'
                final Set<Flag> added = EnumSet.noneOf(Flag.class);
                final Set<Flag> removed = EnumSet.noneOf(Flag.class);
                boolean negating = false;
                while (pos < pattern.length() && peek() != ')' && peek() != ':') {
                    if (peek() == '-') {
                        // Everything after the dash is switched off rather than on.
                        negating = true;
                        pos++;
                        continue;
                    }
                    final Flag flag = Flag.fromSymbol(peek());
                    if (flag == null) {
                        throw fail(Reason.UNSUPPORTED, "unsupported inline flag '" + peek() + "'");
                    }
                    if (negating) {
                        removed.add(flag);
                    } else {
                        added.add(flag);
                    }
                    pos++;
                }
                if (pos >= pattern.length()) {
                    throw fail(Reason.SYNTAX, "unterminated inline flags", start);
                }
                if (peek() == ')') {
                    // Applies to the remainder of the enclosing group.
                    pos++;
                    flags.addAll(added);
                    flags.removeAll(removed);
                    return new Hir.Empty();
                }
                pos++; // ':'
                final Set<Flag> saved = EnumSet.copyOf(flags);
                flags.addAll(added);
                flags.removeAll(removed);
                final Hir body = parseAlternation();
                expect(')', start);
                flags.clear();
                flags.addAll(saved);
                return new Hir.Group(body, -1, null);
            }
        } else {
            index = ++groupCount;
            groupNames.add(null);
        }

        // Flags set by a bare (?i) apply to the end of the enclosing group and no further, so the
        // body is parsed against a copy. Without this, (?:(?i)foo)|Bar would fold case in Bar too.
        final Set<Flag> outer = EnumSet.copyOf(flags.isEmpty()
                ? EnumSet.noneOf(Flag.class)
                : flags);
        final Hir body = parseAlternation();
        expect(')', start);
        flags.clear();
        flags.addAll(outer);
        return new Hir.Group(body, index, name);
    }

    private Hir parseEscape() {
        final int start = pos;
        pos++; // '\'
        if (pos >= pattern.length()) {
            throw fail(Reason.SYNTAX, "trailing backslash", start);
        }
        final char c = pattern.charAt(pos++);
        switch (c) {
            case 'd', 'D', 'w', 'W', 's', 'S' -> {
                return Hir.CharClass.of(fold(shorthand(c, flags.contains(Flag.UNICODE))), "\\" + c);
            }
            case 'n' -> {
                return literal('\n');
            }
            case 'r' -> {
                return literal('\r');
            }
            case 't' -> {
                return literal('\t');
            }
            case 'f' -> {
                return literal('\f');
            }
            case '0' -> {
                return literal('\0');
            }
            case 'A' -> {
                return new Hir.Assertion(Hir.Kind.START_INPUT);
            }
            case 'z' -> {
                return new Hir.Assertion(Hir.Kind.END_INPUT);
            }
            case 'Z' -> {
                throw fail(Reason.UNSUPPORTED,
                        "\\Z means 'end, except for a final line terminator'; write \\z for the end "
                        + "of the input, or (?m)$ for the end of a line", start);
            }
            case 'b' -> {
                if (peek() == '{') {
                    // \b{start}, \b{end} and friends are a Rust extension. Refusing beats
                    // reading the brace as a literal and quietly matching something else.
                    throw fail(Reason.UNSUPPORTED,
                            "\\b{...} is not part of this dialect", start);
                }
                return new Hir.Assertion(flags.contains(Flag.UNICODE)
                        ? Hir.Kind.WORD_BOUNDARY
                        : Hir.Kind.WORD_BOUNDARY_ASCII);
            }
            case 'B' -> {
                return new Hir.Assertion(flags.contains(Flag.UNICODE)
                        ? Hir.Kind.NOT_WORD_BOUNDARY
                        : Hir.Kind.NOT_WORD_BOUNDARY_ASCII);
            }
            case 'x' -> {
                return literal(parseHex(start));
            }
            case 'u' -> {
                return literal(parseUnicode(start));
            }
            case 'Q' -> {
                return parseQuoted(start);
            }
            case 'G' -> {
                return new Hir.Assertion(Hir.Kind.PREVIOUS_MATCH_END);
            }
            case 'k' -> {
                if (peek() != '<') {
                    throw fail(Reason.SYNTAX, "\\k must be followed by <name>", start);
                }
                final int close = pattern.indexOf('>', pos);
                if (close < 0) {
                    throw fail(Reason.SYNTAX, "unterminated \\k<name>", start);
                }
                final String name = pattern.substring(pos + 1, close);
                pos = close + 1;
                // A named group must already exist — the JDK's rule too. Numeric references may
                // point ahead, but a name that has not been seen is far more likely a typo.
                // The list is indexed by absolute group number (seeded with any surrounding
                // composition's labels), so this resolves an embedded regex's own names and a
                // surrounding label's alike.
                final int index = groupNames.indexOf(name);
                if (index <= 0) {
                    throw fail(Reason.SYNTAX,
                            "there is no group named '" + name + "' before this reference", start);
                }
                return backref(index);
            }
            case 'p', 'P' -> {
                final CodePointSet set = fold(parseUnicodeClass(start));
                return Hir.CharClass.of(c == 'P'
                        ? set.negate()
                        : set, "\\" + c);
            }
            default -> {
                if (c >= '1' && c <= '9') {
                    // Greedy, like the JDK: \12 is group 12 if the pattern has one, else
                    // group 1 followed by a literal '2'. Validated against the total group
                    // count after the parse, since a reference may run ahead of its group.
                    // The bound counts the outer composition's groups too, exactly as
                    // validateBackrefs will.
                    final int total = firstGroupIndex + countGroups();
                    int index = c - '0';
                    while (pos < pattern.length() && Character.isDigit(peek())
                           && index * 10 + (peek() - '0') <= total) {
                        index = index * 10 + (peek() - '0');
                        pos++;
                    }
                    backrefs.add(new int[]{index, start});
                    return backref(index);
                }
                return literal(c); // escaped metacharacter
            }
        }
    }

    private int parseHex(final int start) {
        if (peek() == '{') {
            final int close = pattern.indexOf('}', pos);
            if (close < 0) {
                throw fail(Reason.SYNTAX, "unterminated \\x{...}", start);
            }
            final String digits = pattern.substring(pos + 1, close);
            if (!digits.matches("[0-9a-fA-F]+")) {
                throw fail(Reason.SYNTAX, "\\x{...} needs hexadecimal digits", start);
            }
            final int value;
            try {
                value = Integer.parseInt(digits, 16);
            } catch (final NumberFormatException e) {
                throw fail(Reason.SYNTAX,
                        "\\x{" + digits + "} is past the last code point, U+10FFFF", start);
            }
            if (value > Character.MAX_CODE_POINT) {
                throw fail(Reason.SYNTAX,
                        "\\x{" + digits + "} is past the last code point, U+10FFFF", start);
            }
            pos = close + 1;
            return value;
        }
        if (pos + 2 > pattern.length()) {
            throw fail(Reason.SYNTAX, "\\x needs two hex digits", start);
        }
        final String digits = pattern.substring(pos, pos + 2);
        if (!digits.matches("[0-9a-fA-F]{2}")) {
            throw fail(Reason.SYNTAX, "\\x needs two hex digits", start);
        }
        pos += 2;
        return Integer.parseInt(digits, 16);
    }

    /** {@code [:alpha:]} inside a bracket expression, which POSIX allows and Java accepts. */
    private CodePointSet parsePosixClass(final int classStart) {
        final int close = pattern.indexOf(":]", pos);
        if (close < 0) {
            throw fail(Reason.SYNTAX, "unterminated [:...:] class", classStart);
        }
        String name = pattern.substring(pos + 2, close);
        final boolean negated = name.startsWith("^");
        if (negated) {
            name = name.substring(1);
        }
        // POSIX spells them in lower case; the property table uses the java.util.regex spelling.
        final String property = name.isEmpty()
                ? name
                : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        final CodePointSet set = UnicodeClasses.byName(property);
        if (set == null) {
            throw fail(Reason.UNSUPPORTED, "unknown POSIX class '[:" + name + ":]'", classStart);
        }
        pos = close + 2;
        return negated
                ? set.negate()
                : set;
    }

    /** {@code \p{Name}} — the property name in braces, or a single-letter name without them. */
    private CodePointSet parseUnicodeClass(final int start) {
        final String name;
        if (peek() == '{') {
            final int close = pattern.indexOf('}', pos);
            if (close < 0) {
                throw fail(Reason.SYNTAX, "unterminated \\p{...}", start);
            }
            name = pattern.substring(pos + 1, close);
            pos = close + 1;
        } else {
            if (pos >= pattern.length()) {
                throw fail(Reason.SYNTAX, "\\p needs a property name", start);
            }
            name = String.valueOf(pattern.charAt(pos++));
        }
        if (UnicodeClasses.isPosixName(name)) {
            // \p{Alpha} looks like a Unicode property but is ASCII-only in some dialects. Rather
            // than pick a meaning for an ambiguous spelling, insist on the unambiguous ones.
            throw fail(Reason.UNSUPPORTED,
                    "'" + name + "' is an ASCII POSIX class, not a Unicode property; write "
                    + "[[:" + name.toLowerCase(Locale.ROOT) + ":]] for the ASCII "
                    + "meaning, or a Unicode property such as \\p{L} or \\p{Alphabetic}", start);
        }
        final CodePointSet set = UnicodeClasses.byName(name);
        if (set == null) {
            throw fail(Reason.UNSUPPORTED, "unknown character property '" + name + "'", start);
        }
        return set;
    }

    /** {@code \\uHHHH}, four hex digits, or {@code \\u{...}} for a code point of any size. */
    private int parseUnicode(final int start) {
        if (peek() == '{') {
            return parseHex(start);
        }
        if (pos + 4 > pattern.length()) {
            throw fail(Reason.SYNTAX, "\\u needs four hex digits", start);
        }
        final String digits = pattern.substring(pos, pos + 4);
        if (!digits.matches("[0-9a-fA-F]{4}")) {
            throw fail(Reason.SYNTAX, "\\u needs four hex digits", start);
        }
        pos += 4;
        return Integer.parseInt(digits, 16);
    }

    private Hir parseCharClass() {
        final int start = pos;
        pos++; // '['
        boolean negated = false;
        if (peek() == '^') {
            negated = true;
            pos++;
        }
        final CodePointSet.Builder builder = new CodePointSet.Builder();
        boolean first = true;
        while (pos < pattern.length() && (pattern.charAt(pos) != ']' || first)) {
            first = false;
            if (pattern.startsWith("[:", pos)) {
                builder.add(parsePosixClass(start));
                continue;
            }
            // Rust reads an unescaped '[' inside a class as a nested class and '&&' as an
            // intersection. Neither is implemented here, and reading them as literals would be
            // a silent approximation of both — so they are refused until they are built.
            if (pattern.charAt(pos) == '[') {
                throw fail(Reason.UNSUPPORTED,
                        "character class set operations (nested classes) are not implemented; "
                        + "escape the '[' to match a literal bracket", start);
            }
            if (pattern.startsWith("&&", pos)) {
                throw fail(Reason.UNSUPPORTED,
                        "character class set operations ('&&' intersection) are not "
                        + "implemented; escape the ampersands to match them literally", start);
            }
            final int lo = classChar(builder, start);
            if (lo < 0) {
                continue; // a shorthand added itself
            }
            if (pos + 1 < pattern.length() && pattern.charAt(pos) == '-' && pattern.charAt(pos + 1) != ']') {
                pos++;
                final int hi = classChar(builder, start);
                if (hi < 0 || hi < lo) {
                    throw fail(Reason.SYNTAX, "invalid range in character class", start);
                }
                builder.add(lo, hi);
            } else {
                builder.add(lo, lo);
            }
        }
        if (pos >= pattern.length()) {
            throw fail(Reason.SYNTAX, "unterminated character class", start);
        }
        pos++; // ']'

        // Folded before any negation, so (?i)[^x] excludes X as well as x.
        final CodePointSet set = fold(builder.build());
        final String label = pattern.substring(start, pos);
        return Hir.CharClass.of(negated
                ? set.negate()
                : set, label);
    }

    /** Returns the code point, or -1 if a shorthand was consumed and merged into {@code builder}. */
    private int classChar(final CodePointSet.Builder builder, final int classStart) {
        int c = pattern.codePointAt(pos);
        pos += Character.charCount(c);
        if (c == '\\') {
            if (pos >= pattern.length()) {
                throw fail(Reason.SYNTAX, "trailing backslash in character class", classStart);
            }
            c = pattern.codePointAt(pos);
            pos += Character.charCount(c);
            switch (c) {
                case 'd', 'D', 'w', 'W', 's', 'S' -> {
                    builder.add(shorthand((char) c, flags.contains(Flag.UNICODE)));
                    return -1;
                }
                case 'n' -> {
                    return '\n';
                }
                case 'r' -> {
                    return '\r';
                }
                case 't' -> {
                    return '\t';
                }
                case 'f' -> {
                    return '\f';
                }
                case 'x' -> {
                    return parseHex(classStart);
                }
                case 'u' -> {
                    return parseUnicode(classStart);
                }
                case 'p', 'P' -> {
                    final CodePointSet set = parseUnicodeClass(classStart);
                    builder.add(c == 'P'
                            ? set.negate()
                            : set);
                    return -1;
                }
                case 'Q', 'E' -> throw fail(Reason.UNSUPPORTED,
                        "\\Q...\\E inside a character class is not supported; list the "
                        + "characters, escaping each metacharacter", classStart);
                default -> {
                    return c;
                }
            }
        }
        return c;
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private Hir backref(final int index) {
        return new Hir.Backref(index,
                flags.contains(Flag.CASE_INSENSITIVE),
                flags.contains(Flag.UNICODE));
    }

    /** Capturing groups in the whole pattern, counted without disturbing the parse position. */
    private int countGroups() {
        int count = 0;
        boolean inClass = false;
        int i = 0;
        while (i < pattern.length()) {
            final char c = pattern.charAt(i);
            if (c == '\\') {
                if (pattern.startsWith("\\Q", i)) {
                    // Everything up to \E (or the end) is literal text, parentheses included.
                    final int end = pattern.indexOf("\\E", i + 2);
                    i = end < 0
                            ? pattern.length()
                            : end + 2;
                    continue;
                }
                i += 2; // skip the escaped character
                continue;
            }
            if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(' && (i + 1 >= pattern.length() || pattern.charAt(i + 1) != '?')) {
                count++;
            } else if (pattern.startsWith("(?<", i) && i + 3 < pattern.length()
                       && pattern.charAt(i + 3) != '=' && pattern.charAt(i + 3) != '!') {
                count++;
            }
            i++;
        }
        return count;
    }

    private void validateBackrefs() {
        for (final int[] ref : backrefs) {
            if (ref[0] > groupCount) {
                throw fail(Reason.SYNTAX, "there is no group " + ref[0], ref[1]);
            }
        }
    }

    /** {@code \Q...\E} — everything up to {@code \E} (or the end) matches literally. */
    private Hir parseQuoted(final int start) {
        int end = pattern.indexOf("\\E", pos);
        if (end < 0) {
            end = pattern.length();
        }
        final List<Hir> items = new ArrayList<>();
        int at = pos;
        while (at < end) {
            final int codePoint = pattern.codePointAt(at);
            at += Character.charCount(codePoint);
            items.add(literal(codePoint));
        }
        pos = end < pattern.length()
                ? end + 2
                : end;
        return switch (items.size()) {
            case 0 -> new Hir.Empty();
            case 1 -> items.getFirst();
            default -> new Hir.Concat(items);
        };
    }

    private void expect(final char c, final int start) {
        if (pos >= pattern.length() || pattern.charAt(pos) != c) {
            throw fail(Reason.SYNTAX, "unterminated group", start);
        }
        pos++;
    }

    private char peek() {
        return pos < pattern.length()
                ? pattern.charAt(pos)
                : '\0';
    }

    private Hir literal(final int codePoint) {
        if (flags.contains(Flag.CASE_INSENSITIVE)) {
            final CodePointSet folded = fold(CodePointSet.single(codePoint));
            if (folded.singleCodePoint() < 0) {
                return Hir.CharClass.of(folded, new String(Character.toChars(codePoint)));
            }
        }
        final String text = new String(Character.toChars(codePoint));
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return bytes.length == 1
                ? Hir.CharClass.of(CodePointSet.single(codePoint), text)
                : new Hir.Bytes(bytes, text);
    }

    /** Case folding, applied to code points before they are encoded, and only under {@code (?i)}. */
    private CodePointSet fold(final CodePointSet set) {
        return flags.contains(Flag.CASE_INSENSITIVE)
                ? CaseFolding.closure(set, flags.contains(Flag.UNICODE))
                : set;
    }

    /**
     * The Perl shorthands. Unicode by default — {@code \w} matches {@code é} — with {@code (?-u)}
     * selecting the ASCII definitions.
     * <p>
     * ASCII-by-default is the older convention and a trap for international data: a pattern that
     * quietly fails to match accented text tends to be discovered in a report that has been
     * under-counting for months. The Unicode definitions used here are the ones
     * {@code java.util.regex} applies under {@code UNICODE_CHARACTER_CLASS}, which keeps the JDK
     * usable as a differential oracle for this engine's default behaviour.
     */
    private static CodePointSet shorthand(final char c, final boolean unicode) {
        final CodePointSet set = switch (Character.toLowerCase(c)) {
            case 'd' -> unicode
                    ? UnicodeClasses.byName("Nd")
                    : CodePointSet.of('0', '9');
            case 'w' -> unicode
                    ? Words.unicode()
                    : Words.ascii();
            case 's' -> unicode
                    ? UnicodeClasses.byName("IsWhite_Space")
                    : CodePointSet.of(' ', ' ', '\t', '\r');
            default -> throw new IllegalArgumentException("Not a shorthand: " + c);
        };
        return Character.isUpperCase(c)
                ? set.negate()
                : set;
    }

    private PatternCompileException fail(final Reason reason, final String message) {
        return fail(reason, message, pos);
    }

    private PatternCompileException fail(final Reason reason, final String message, final int at) {
        return new PatternCompileException(reason, pattern, at, message);
    }
}
