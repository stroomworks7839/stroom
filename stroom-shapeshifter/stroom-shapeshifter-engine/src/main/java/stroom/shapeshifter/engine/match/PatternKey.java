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

package stroom.shapeshifter.engine.match;

import stroom.shapeshifter.engine.config.Template.RegexFlags;
import stroom.shapeshifter.regex.Encoding;
import stroom.shapeshifter.regex.Flag;

import java.util.Set;

/**
 * The interned-pattern key: the same source text compiled for two encodings, or under two
 * flag sets, is two different byte machines, so the key is the text, the flags and the
 * encoding (design 19; design 27 ruling 9 for the flags).
 */
public record PatternKey(String text, Set<Flag> flags, Encoding encoding) {

    private static final Set<Flag> NONE = Set.of();
    private static final Set<Flag> CASE_INSENSITIVE = Set.of(Flag.CASE_INSENSITIVE);
    private static final Set<Flag> DOT_ALL = Set.of(Flag.DOT_ALL);
    private static final Set<Flag> BOTH = Set.of(Flag.CASE_INSENSITIVE, Flag.DOT_ALL);

    /**
     * The flag set is one of four shared immutable values, so a key costs the record and
     * nothing else: keys are built on every progressive regex step at match time as well as at
     * compile time.
     */
    public PatternKey {
        flags = Set.copyOf(flags);
    }

    /** A key with no flags: a body's or a condition's pattern, which the model gives none. */
    public static PatternKey of(final String text, final Encoding encoding) {
        return new PatternKey(text, NONE, encoding);
    }

    /**
     * The key for a pattern over resolved values — a body's replace or a condition's matches.
     * A value's internal form is UTF-8 whatever the feed's encoding, so the compilation is the
     * UTF-8 one, always; only the match vocabulary sees feed bytes (design 19 phase 3). Said
     * once here, so the compile side and the lookup side cannot build the key differently.
     */
    public static PatternKey ofValue(final String text) {
        return new PatternKey(text, NONE, Encoding.UTF_8);
    }

    /** A key for a pattern the model flags — a template's match, or a progressive regex step. */
    public static PatternKey of(final String text, final RegexFlags flags, final Encoding encoding) {
        return new PatternKey(text, flags(flags), encoding);
    }

    /** The library's flag set for the model's two booleans: one of four shared values. */
    public static Set<Flag> flags(final RegexFlags flags) {
        if (flags.caseInsensitive()) {
            return flags.dotAll() ? BOTH : CASE_INSENSITIVE;
        }
        return flags.dotAll() ? DOT_ALL : NONE;
    }
}
