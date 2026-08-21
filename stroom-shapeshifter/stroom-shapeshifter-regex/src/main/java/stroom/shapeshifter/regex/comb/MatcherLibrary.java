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

package stroom.shapeshifter.regex.comb;

import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Flag;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Named, reusable matchers, and the entry point for compiling a composition.
 * <p>
 * References are resolved and <b>inlined</b> at compile time, with cycles reported as an error
 * rather than discovered at match time. That is deliberate: inlining is what keeps the engine
 * free of recursion, which is what keeps its time bounds linear and its stack depth constant.
 * A recursive grammar is a different kind of language, and would need a different engine.
 * <p>
 * This mirrors how a config is expected to be authored — a composition referring to library
 * patterns by name, fully realised only when it is compiled.
 */
public final class MatcherLibrary {

    private final Map<String, Matcher> definitions = new LinkedHashMap<>();

    /** Defines, or redefines, a named matcher. */
    public MatcherLibrary define(final String name, final Matcher matcher) {
        definitions.put(name, matcher);
        return this;
    }

    /** The definition of a named matcher, or null if the name is not defined. */
    public Matcher get(final String name) {
        return definitions.get(name);
    }

    /** The defined names in definition order — an unmodifiable snapshot, not a live view. */
    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(definitions.keySet()));
    }

    /** Compiles a composition, resolving any references against this library. */
    public BytePattern compile(final Matcher matcher) {
        return compile(matcher, EnumSet.noneOf(Flag.class));
    }

    public BytePattern compile(final Matcher matcher, final Set<Flag> flags) {
        return BytePattern.compile(matcher, definitions, flags);
    }

    /** Compiles a named matcher from this library. */
    public BytePattern compile(final String name) {
        final Matcher matcher = definitions.get(name);
        if (matcher == null) {
            throw new IllegalArgumentException("No matcher named '" + name + "'");
        }
        return compile(matcher);
    }
}
