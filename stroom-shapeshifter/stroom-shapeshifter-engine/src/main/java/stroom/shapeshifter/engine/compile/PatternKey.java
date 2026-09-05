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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.config.Template.RegexFlags;
import stroom.shapeshifter.regex.Encoding;
import stroom.shapeshifter.regex.Flag;

import java.util.EnumSet;
import java.util.Set;

/**
 * The interned-pattern key: the same source text compiled for two encodings, or under two
 * flag sets, is two different byte machines, so the key is the text, the flags and the
 * encoding (design 19; design 27 ruling 9 for the flags).
 */
public record PatternKey(String text, Set<Flag> flags, Encoding encoding) {

    public PatternKey {
        flags = flags.isEmpty() ? Set.of() : Set.copyOf(flags);
    }

    /** A key with no flags: a body's or a condition's pattern, which the model gives none. */
    public static PatternKey of(final String text, final Encoding encoding) {
        return new PatternKey(text, Set.of(), encoding);
    }

    /** A key for a pattern the model flags — a template's match, or a progressive regex step. */
    public static PatternKey of(final String text, final RegexFlags flags, final Encoding encoding) {
        return new PatternKey(text, flags(flags), encoding);
    }

    /** The library's flag set for the model's two booleans. */
    public static Set<Flag> flags(final RegexFlags flags) {
        final Set<Flag> set = EnumSet.noneOf(Flag.class);
        if (flags.caseInsensitive()) {
            set.add(Flag.CASE_INSENSITIVE);
        }
        if (flags.dotAll()) {
            set.add(Flag.DOT_ALL);
        }
        return set;
    }
}
