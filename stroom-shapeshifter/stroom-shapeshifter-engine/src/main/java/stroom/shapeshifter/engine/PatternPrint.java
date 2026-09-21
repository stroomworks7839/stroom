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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Template.RegexFlags;
import stroom.shapeshifter.regex.comb.Matcher;
import stroom.shapeshifter.regex.comb.MatcherLibrary;
import stroom.shapeshifter.regex.comb.Matchers;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pattern tree written back as the regex it means — {@link PatternExplode}'s inverse, so the
 * editor can show a tree as a regex while it is edited, and a configuration can leave as text
 * (design 43 §5). Meaning, not text: an exploded regex printed and compiled has the plan the
 * regex had ({@code PatternPrintTest} over the fixture corpus), but its spelling is the
 * tree's — every literal escaped, every group spelt out.
 *
 * <p>Each node prints as the form {@code PatternCompiler} lowers it to, so print and compile
 * agree by construction: a take is {@code [\s\S]{n}}, a multi-character exclusive until is a
 * lazy run and a lookahead, a reference is the standard library entry it names, written out.
 * A cast on a labelled node has no regex form and is dropped — the regex has the bytes, the
 * cast is what the match makes of them.
 */
public final class PatternPrint {

    private PatternPrint() {
    }

    /** The regex, under the flags the template compiles it with, naming nothing of a project's. */
    public static String print(final PatternNode node, final RegexFlags flags) {
        return print(node, flags, Map.of());
    }

    /**
     * The regex, under the flags the template compiles it with; a {@code ref} prints as its
     * definition — the project's (design 44 §3) before the standard library's — and so loses
     * its name, which is why the rendering is a view and the tree the store.
     */
    public static String print(final PatternNode node,
                               final RegexFlags flags,
                               final Map<String, PatternNode> patterns) {
        final StringBuilder out = new StringBuilder();
        new Printer(flags == null ? RegexFlags.none() : flags, patterns).print(node, out, Context.TOP);
        return out.toString();
    }

    /**
     * The standard library as the editor lists it: each name with the regex its definition
     * means, in definition order. What a {@code ref} node can name, and nothing else can.
     */
    public static Map<String, String> library() {
        final Map<String, String> entries = new LinkedHashMap<>();
        final MatcherLibrary library = Matchers.standardLibrary();
        for (final String name : library.names()) {
            entries.put(name, print(PatternExplode.node(library.get(name)), RegexFlags.none()));
        }
        return entries;
    }

    /** Where a node is printed, which decides whether it needs a group around it. */
    private enum Context {
        /** The whole pattern, or a group's body: a choice needs no group. */
        TOP,
        /** An item of a sequence: a choice needs a group. */
        SEQUENCE,
        /** The body of a quantifier: anything but an atom needs a group. */
        QUANTIFIED
    }

    private static final class Printer {

        private final RegexFlags outer;
        private final Map<String, PatternNode> patterns;
        private final Deque<String> printing = new ArrayDeque<>();

        Printer(final RegexFlags outer, final Map<String, PatternNode> patterns) {
            this.outer = outer;
            this.patterns = patterns;
        }

        void print(final PatternNode node, final StringBuilder out, final Context context) {
            switch (node) {
                case final PatternNode.Tag tag -> {
                    final boolean atom = tag.text().codePointCount(0, tag.text().length()) == 1;
                    group(out, context == Context.QUANTIFIED && !atom, () -> out.append(quote(tag.text())));
                }
                case final PatternNode.TakeWhile take ->
                        quantified(out, take.classExpression(), take.min(), take.max(), true);
                case final PatternNode.TakeUntil until -> until(until, out, context);
                case final PatternNode.Take take ->
                        out.append("[\\s\\S]").append(count(take.count(), take.count()));
                case final PatternNode.Regex regex -> {
                    final RegexFlags flags = regex.flags() == null ? RegexFlags.none() : regex.flags();
                    final String on = (flags.caseInsensitive() && !outer.caseInsensitive() ? "i" : "")
                                      + (flags.dotAll() && !outer.dotAll() ? "s" : "");
                    final String off = (!flags.caseInsensitive() && outer.caseInsensitive() ? "i" : "")
                                       + (!flags.dotAll() && outer.dotAll() ? "s" : "");
                    if (on.isEmpty() && off.isEmpty()) {
                        group(out, context == Context.QUANTIFIED
                                   || (context == Context.SEQUENCE && hasTopLevelAlternation(regex.pattern())),
                                () -> out.append(regex.pattern()));
                    } else {
                        out.append("(?").append(on).append(off.isEmpty() ? "" : "-" + off).append(':')
                                .append(regex.pattern()).append(')');
                    }
                }
                case final PatternNode.Ref ref -> {
                    final PatternNode part = patterns.get(ref.name());
                    if (part != null) {
                        if (printing.contains(ref.name())) {
                            throw new IllegalArgumentException("Pattern '" + ref.name() + "' refers to itself via "
                                                               + String.join(" -> ", printing) + " -> " + ref.name());
                        }
                        printing.addLast(ref.name());
                        try {
                            print(part, out, context);
                        } finally {
                            printing.removeLast();
                        }
                        break;
                    }
                    final Matcher definition = Matchers.standardLibrary().get(ref.name());
                    if (definition == null) {
                        throw new IllegalArgumentException("Unknown library pattern: " + ref.name());
                    }
                    print(PatternExplode.node(definition), out, context);
                }
                case final PatternNode.Sequence sequence -> {
                    if (sequence.items().size() == 1) {
                        print(sequence.items().getFirst(), out, context);
                    } else {
                        group(out, context == Context.QUANTIFIED, () -> items(sequence.items(), out));
                    }
                }
                case final PatternNode.Choice choice -> group(out, context != Context.TOP, () -> {
                    for (int i = 0; i < choice.alternatives().size(); i++) {
                        if (i > 0) {
                            out.append('|');
                        }
                        print(choice.alternatives().get(i), out, Context.TOP);
                    }
                });
                case final PatternNode.Optional optional -> {
                    print(optional.body(), out, Context.QUANTIFIED);
                    out.append('?');
                }
                case final PatternNode.Repeat repeat -> {
                    print(repeat.body(), out, Context.QUANTIFIED);
                    out.append(count(repeat.min(), repeat.max()));
                    if (!repeat.greedy()) {
                        out.append('?');
                    }
                }
                case final PatternNode.Peek peek -> {
                    out.append("(?=");
                    print(peek.body(), out, Context.TOP);
                    out.append(')');
                }
                case final PatternNode.Not not -> {
                    out.append("(?!");
                    print(not.body(), out, Context.TOP);
                    out.append(')');
                }
                case final PatternNode.Labelled labelled -> {
                    // An unnamed group explodes as a label of its number, {@code _3}; printed
                    // back as the unnamed group it was, it takes that number again.
                    out.append(isNumbered(labelled.label()) ? "(" : "(?<" + labelled.label() + ">");
                    print(labelled.body(), out, Context.TOP);
                    out.append(')');
                }
            }
        }

        private void items(final List<PatternNode> items, final StringBuilder out) {
            for (final PatternNode item : items) {
                print(item, out, Context.SEQUENCE);
            }
        }

        private void until(final PatternNode.TakeUntil until, final StringBuilder out, final Context context) {
            final String terminator = until.terminator();
            if (terminator.codePointCount(0, terminator.length()) == 1) {
                // The forms PatternCompiler.lowerUntil lowers to, spelt as a class and its tag.
                final String excluded = "[^" + classMember(terminator.codePointAt(0)) + "]*";
                if (!until.inclusive()) {
                    out.append(excluded);
                } else {
                    group(out, context == Context.QUANTIFIED,
                            () -> out.append(excluded).append(quote(terminator)));
                }
            } else {
                group(out, context == Context.QUANTIFIED, () -> out.append("(?s:.*?)")
                        .append(until.inclusive() ? quote(terminator) : "(?=" + quote(terminator) + ")"));
            }
        }
    }

    private static void quantified(final StringBuilder out, final String atom, final int min, final int max,
                                   final boolean greedy) {
        out.append(atom).append(count(min, max));
        if (!greedy) {
            out.append('?');
        }
    }

    private static String count(final int min, final int max) {
        if (max == PatternNode.Repeat.UNBOUNDED) {
            return min == 0 ? "*" : min == 1 ? "+" : "{" + min + ",}";
        }
        if (min == 1 && max == 1) {
            return "";
        }
        if (min == 0 && max == 1) {
            return "?";
        }
        return min == max ? "{" + min + "}" : "{" + min + "," + max + "}";
    }

    private static void group(final StringBuilder out, final boolean needed, final Runnable body) {
        if (needed) {
            out.append("(?:");
        }
        body.run();
        if (needed) {
            out.append(')');
        }
    }

    private static boolean isNumbered(final String label) {
        return label.length() > 1 && label.charAt(0) == '_'
               && label.chars().skip(1).allMatch(c -> c >= '0' && c <= '9');
    }

    /** Whether a regex leaf has a {@code |} outside any group or class, and so needs a group in a sequence. */
    private static boolean hasTopLevelAlternation(final String pattern) {
        int depth = 0;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '|' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    /** Literal text as a regex: metacharacters escaped one by one, control characters by name. */
    static String quote(final String text) {
        final StringBuilder sb = new StringBuilder(text.length() + 8);
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (cp < 0x20 || cp == 0x7F) {
                        sb.append("\\x{").append(Integer.toHexString(cp).toUpperCase()).append('}');
                    } else if (cp < 0x80 && "\\.[]{}()*+?^$|#".indexOf(cp) >= 0) {
                        sb.append('\\').appendCodePoint(cp);
                    } else {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        });
        return sb.toString();
    }

    /** A code point as a member of a character class, escaped where the class reads it as syntax. */
    private static String classMember(final int cp) {
        return switch (cp) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> cp > 0x20 && cp < 0x7F && "\\[]^-:&".indexOf(cp) < 0
                    ? new String(Character.toChars(cp))
                    : "\\x{" + Integer.toHexString(cp).toUpperCase() + "}";
        };
    }
}
