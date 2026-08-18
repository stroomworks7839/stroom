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

/**
 * Pattern flags. Also settable inline as {@code (?i)}, {@code (?s)}, {@code (?m)}.
 */
public enum Flag {

    /** {@code i} — ASCII case folding, applied at compile time. */
    CASE_INSENSITIVE('i'),

    /** {@code s} — {@code .} also matches a newline. */
    DOT_ALL('s'),

    /** {@code m} — {@code ^} and {@code $} match at line boundaries rather than input boundaries. */
    MULTILINE('m'),

    /**
     * {@code u} — {@code \w}, {@code \d}, {@code \s} and {@code \b} use their Unicode
     * definitions. <b>On unless switched off with {@code (?-u)}</b>, which is why it is not in the
     * set a caller passes: the caller's set is what is added to the default, and {@code (?-u)}
     * is what removes it.
     */
    UNICODE('u');

    private final char symbol;

    Flag(final char symbol) {
        this.symbol = symbol;
    }

    public char symbol() {
        return symbol;
    }

    /** The flag for an inline flag character such as the {@code i} in {@code (?i)}, or null. */
    public static Flag fromSymbol(final char symbol) {
        for (final Flag flag : values()) {
            if (flag.symbol == symbol) {
                return flag;
            }
        }
        return null;
    }
}
