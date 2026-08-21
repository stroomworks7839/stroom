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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * Code-point sets for {@code \p{...}} properties.
 * <p>
 * Built by asking the JDK about every code point once and caching the result, rather than
 * shipping tables that would need maintaining against each Unicode revision. The JDK is already
 * the source of truth for the encoding side of the compiler, so using it here keeps
 * {@code \p{L}} meaning exactly what {@code java.util.regex} means by it — which is what the
 * differential tests require.
 * <p>
 * A full sweep is around a million predicate calls, so each property is built at most once per
 * JVM and shared. The sets are immutable.
 */
final class UnicodeClasses {

    private static final Map<String, CodePointSet> CACHE = new ConcurrentHashMap<>();

    private UnicodeClasses() {
    }

    /** True if the name is one of the ASCII POSIX classes rather than a Unicode property. */
    static boolean isPosixName(final String name) {
        return posixPredicate(name.trim()) != null;
    }

    /** The set for a property name, or null if the name is not recognised. */
    static CodePointSet byName(final String name) {
        final String key = name.trim();
        final IntPredicate predicate = predicateFor(key);
        if (predicate == null) {
            return null;
        }
        return CACHE.computeIfAbsent(key, ignored -> build(predicate));
    }

    private static IntPredicate predicateFor(final String name) {
        final IntPredicate posix = posixPredicate(name);
        return posix != null
                ? posix
                : unicodePredicate(name);
    }

    /**
     * The POSIX names are <b>US-ASCII only</b> in {@code java.util.regex} unless
     * {@code UNICODE_CHARACTER_CLASS} is set — {@code \p{Alpha}} does not match {@code é}, while
     * {@code \p{L}} does. Conflating the two families is an easy mistake and a silently wrong
     * one, so they are kept apart here and each matches the JDK's default behaviour.
     */
    private static IntPredicate posixPredicate(final String name) {
        return switch (name) {
            case "Lower" -> codePoint -> codePoint >= 'a' && codePoint <= 'z';
            case "Upper" -> codePoint -> codePoint >= 'A' && codePoint <= 'Z';
            case "ASCII" -> codePoint -> codePoint <= 0x7F;
            case "Alpha" -> UnicodeClasses::isAsciiAlpha;
            case "Digit" -> codePoint -> codePoint >= '0' && codePoint <= '9';
            case "Alnum" -> codePoint -> isAsciiAlpha(codePoint)
                                         || (codePoint >= '0' && codePoint <= '9');
            case "Punct" -> codePoint -> codePoint <= 0x7F
                                         && "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
                                                 .indexOf(codePoint) >= 0;
            case "Graph" -> codePoint -> isAsciiGraph(codePoint);
            case "Print" -> codePoint -> isAsciiGraph(codePoint) || codePoint == 0x20;
            case "Blank" -> codePoint -> codePoint == ' ' || codePoint == '\t';
            case "Cntrl" -> codePoint -> codePoint <= 0x1F || codePoint == 0x7F;
            case "XDigit" -> codePoint -> (codePoint >= '0' && codePoint <= '9')
                                          || (codePoint >= 'a' && codePoint <= 'f')
                                          || (codePoint >= 'A' && codePoint <= 'F');
            case "Space" -> codePoint -> codePoint == ' ' || (codePoint >= 0x09 && codePoint <= 0x0D);
            case "Word" -> codePoint -> isAsciiAlpha(codePoint)
                                        || (codePoint >= '0' && codePoint <= '9')
                                        || codePoint == '_';
            default -> null;
        };
    }

    private static boolean isAsciiAlpha(final int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z');
    }

    private static boolean isAsciiGraph(final int codePoint) {
        return codePoint > 0x20 && codePoint < 0x7F;
    }

    /**
     * General categories and the {@code Is...} names, which are Unicode-wide.
     * <p>
     * Categories are accepted by short code and by long name — {@code \p{Lu}},
     * {@code \p{Uppercase_Letter}} and {@code \p{uppercaseletter}} are the same class — because
     * the long names are what the Unicode standard uses and what a reader of a pattern stands a
     * chance of understanding.
     */
    private static IntPredicate unicodePredicate(final String name) {
        final IntPredicate binary = binaryProperty(name);
        if (binary != null) {
            return binary;
        }
        final IntPredicate category = CATEGORIES.get(normalise(name));
        if (category != null) {
            return category;
        }
        // \p{IsLu} is the same class as \p{Lu}; \p{IsGreek} is a script.
        final IntPredicate prefixed = name.startsWith("Is") || name.startsWith("is")
                ? CATEGORIES.get(normalise(name.substring(2)))
                : null;
        return prefixed != null
                ? prefixed
                : script(name);
    }

    /** The handful of binary properties that are not general categories. */
    private static IntPredicate binaryProperty(final String name) {
        return switch (name) {
            case "IsAlphabetic" -> Character::isAlphabetic;
            case "IsLetter" -> Character::isLetter;
            case "IsUppercase" -> Character::isUpperCase;
            case "IsLowercase" -> Character::isLowerCase;
            case "IsDigit" -> ofType(Character.DECIMAL_DIGIT_NUMBER);
            // The Unicode White_Space property, which is NOT Character.isWhitespace: that method
            // excludes the no-break spaces and includes the file/group separators, so using it
            // would put \s a few code points away from every other engine.
            case "IsWhite_Space", "IsWhitespace", "IsWSpace" -> UnicodeClasses::isWhiteSpace;
            default -> null;
        };
    }

    /** Lower case, with the separators the various spellings use removed. */
    private static String normalise(final String name) {
        return name.replace("_", "").replace("-", "").replace(" ", "")
                .toLowerCase(Locale.ROOT);
    }

    private static final Map<String, IntPredicate> CATEGORIES = categories();

    private static Map<String, IntPredicate> categories() {
        final Map<String, IntPredicate> map = new HashMap<>();
        category(map, "Lu", "Uppercase_Letter", ofType(Character.UPPERCASE_LETTER));
        category(map, "Ll", "Lowercase_Letter", ofType(Character.LOWERCASE_LETTER));
        category(map, "Lt", "Titlecase_Letter", ofType(Character.TITLECASE_LETTER));
        category(map, "Lm", "Modifier_Letter", ofType(Character.MODIFIER_LETTER));
        category(map, "Lo", "Other_Letter", ofType(Character.OTHER_LETTER));
        category(map, "L", "Letter", Character::isLetter);
        category(map, "LC", "Cased_Letter", ofTypes(Character.UPPERCASE_LETTER,
                Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER));

        category(map, "Mn", "Nonspacing_Mark", ofType(Character.NON_SPACING_MARK));
        category(map, "Mc", "Spacing_Mark", ofType(Character.COMBINING_SPACING_MARK));
        category(map, "Me", "Enclosing_Mark", ofType(Character.ENCLOSING_MARK));
        category(map, "M", "Mark", ofTypes(Character.NON_SPACING_MARK,
                Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK));

        category(map, "Nd", "Decimal_Number", ofType(Character.DECIMAL_DIGIT_NUMBER));
        category(map, "Nl", "Letter_Number", ofType(Character.LETTER_NUMBER));
        category(map, "No", "Other_Number", ofType(Character.OTHER_NUMBER));
        category(map, "N", "Number", ofTypes(Character.DECIMAL_DIGIT_NUMBER,
                Character.LETTER_NUMBER, Character.OTHER_NUMBER));

        category(map, "Pc", "Connector_Punctuation", ofType(Character.CONNECTOR_PUNCTUATION));
        category(map, "Pd", "Dash_Punctuation", ofType(Character.DASH_PUNCTUATION));
        category(map, "Ps", "Open_Punctuation", ofType(Character.START_PUNCTUATION));
        category(map, "Pe", "Close_Punctuation", ofType(Character.END_PUNCTUATION));
        category(map, "Pi", "Initial_Punctuation", ofType(Character.INITIAL_QUOTE_PUNCTUATION));
        category(map, "Pf", "Final_Punctuation", ofType(Character.FINAL_QUOTE_PUNCTUATION));
        category(map, "Po", "Other_Punctuation", ofType(Character.OTHER_PUNCTUATION));
        category(map, "P", "Punctuation", UnicodeClasses::isPunctuation);

        category(map, "Sm", "Math_Symbol", ofType(Character.MATH_SYMBOL));
        category(map, "Sc", "Currency_Symbol", ofType(Character.CURRENCY_SYMBOL));
        category(map, "Sk", "Modifier_Symbol", ofType(Character.MODIFIER_SYMBOL));
        category(map, "So", "Other_Symbol", ofType(Character.OTHER_SYMBOL));
        category(map, "S", "Symbol", UnicodeClasses::isSymbol);

        category(map, "Zs", "Space_Separator", ofType(Character.SPACE_SEPARATOR));
        category(map, "Zl", "Line_Separator", ofType(Character.LINE_SEPARATOR));
        category(map, "Zp", "Paragraph_Separator", ofType(Character.PARAGRAPH_SEPARATOR));
        category(map, "Z", "Separator", ofTypes(Character.SPACE_SEPARATOR,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR));

        category(map, "Cc", "Control", ofType(Character.CONTROL));
        category(map, "Cf", "Format", ofType(Character.FORMAT));
        category(map, "Cs", "Surrogate", ofType(Character.SURROGATE));
        category(map, "Co", "Private_Use", ofType(Character.PRIVATE_USE));
        category(map, "Cn", "Unassigned", ofType(Character.UNASSIGNED));
        category(map, "C", "Other", ofTypes(Character.CONTROL, Character.FORMAT,
                Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED));

        map.put("any", codePoint -> true);
        return map;
    }

    private static void category(final Map<String, IntPredicate> map,
                                 final String code,
                                 final String longName,
                                 final IntPredicate predicate) {
        map.put(normalise(code), predicate);
        map.put(normalise(longName), predicate);
    }

    private static IntPredicate ofType(final int type) {
        return codePoint -> Character.getType(codePoint) == type;
    }

    private static IntPredicate ofTypes(final int... types) {
        return codePoint -> {
            final int type = Character.getType(codePoint);
            for (final int candidate : types) {
                if (type == candidate) {
                    return true;
                }
            }
            return false;
        };
    }

    /** {@code \p{IsGreek}} and the like, resolved through the JDK's own script lookup. Scripts
     * only: the {@code In...} block names are not recognised anywhere in this parser. */
    private static IntPredicate script(final String name) {
        final String bare = name.startsWith("Is")
                ? name.substring(2)
                : name;
        final Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.forName(bare.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        return codePoint -> Character.UnicodeScript.of(codePoint) == script;
    }

    private static boolean isWhiteSpace(final int codePoint) {
        return Character.isSpaceChar(codePoint)
               || (codePoint >= 0x09 && codePoint <= 0x0D)
               || codePoint == 0x85;
    }

    private static boolean isPunctuation(final int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static boolean isSymbol(final int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.MATH_SYMBOL,
                 Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL,
                 Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    /** One sweep of the code space, coalescing runs into ranges as it goes. Shared with
     * {@link Words}, whose word-character set is built the same way. */
    static CodePointSet build(final IntPredicate predicate) {
        final CodePointSet.Builder builder = new CodePointSet.Builder();
        int runStart = -1;
        for (int codePoint = 0; codePoint <= CodePointSet.MAX; codePoint++) {
            if (predicate.test(codePoint)) {
                if (runStart < 0) {
                    runStart = codePoint;
                }
            } else if (runStart >= 0) {
                builder.add(runStart, codePoint - 1);
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            builder.add(runStart, CodePointSet.MAX);
        }
        return builder.build();
    }
}
