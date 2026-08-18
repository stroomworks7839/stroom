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

package stroom.shapeshifter.regex;

import java.util.regex.Pattern;

/**
 * Compiles a pattern with {@code java.util.regex} configured to mean what this engine means by it,
 * so the JDK can go on serving as the differential oracle after the dialect moved to Rust
 * semantics.
 *
 * <h2>Why a translation is needed at all</h2>
 * The differential suites are only evidence if both sides are asked the same question. Three of
 * the JDK's defaults now ask a different one:
 * <ul>
 *   <li><b>{@code \w \d \s \b} are ASCII by default.</b> This engine makes them Unicode, which
 *       {@link Pattern#UNICODE_CHARACTER_CLASS} also does — so the flag, rather than a rewrite,
 *       carries this one.</li>
 *   <li><b>{@code $} matches before a final line terminator.</b> That is the JDK's inherited Perl
 *       behaviour, and the wart this dialect declined to inherit: here {@code $} means the end of
 *       the haystack, which the JDK spells {@code \z}. So an unescaped {@code $} outside a
 *       character class is rewritten, unless the pattern is multiline, where the two agree.</li>
 *   <li><b>A line terminator is any of six characters.</b> {@link Pattern#UNIX_LINES} narrows it
 *       to {@code \n}, which is what this engine and Rust both mean.</li>
 * </ul>
 * The rewrite is deliberately narrow — a translation layer that papered over real disagreement
 * would turn the differential suite into a tautology. Everything not listed above is compared
 * unaltered.
 */
public final class JdkOracle {

    private JdkOracle() {
    }

    /** The reference pattern for a pattern written in this engine's dialect. */
    public static Pattern compile(final String pattern) {
        return Pattern.compile(translate(pattern),
                Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }

    /** As {@link #compile(String)}, with extra JDK flags such as {@code CASE_INSENSITIVE}. */
    public static Pattern compile(final String pattern, final int extraFlags) {
        return Pattern.compile(translate(pattern),
                Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES | extraFlags);
    }

    /**
     * The pattern as the JDK must be given it. Public so a failing comparison can report what the
     * reference side was actually asked.
     */
    public static String translate(final String pattern) {
        if (isMultiline(pattern)) {
            // Under (?m) the two dialects agree: before each \n, and at the end of the input.
            return pattern;
        }
        final StringBuilder translated = new StringBuilder(pattern.length() + 4);
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                translated.append(c).append(pattern.charAt(++i));
            } else if (c == '[') {
                inClass = true;
                translated.append(c);
            } else if (c == ']') {
                inClass = false;
                translated.append(c);
            } else if (c == '$' && !inClass) {
                translated.append("\\z");
            } else {
                translated.append(c);
            }
        }
        return translated.toString();
    }

    /**
     * Whether the pattern turns multiline on anywhere. Crude on purpose: a pattern that switches
     * the flag part way through cannot be translated by this rule, and none of the corpora
     * contain one, so treating any {@code (?m} as global is safe and visible rather than subtly
     * wrong.
     */
    private static boolean isMultiline(final String pattern) {
        return pattern.matches("(?s).*\\(\\?[a-z]*m[a-z]*[:)].*");
    }
}
