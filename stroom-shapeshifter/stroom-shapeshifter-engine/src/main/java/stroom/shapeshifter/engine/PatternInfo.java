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

import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.PatternCompileException;

import java.util.ArrayList;
import java.util.List;

/**
 * What a pattern would capture, without running it.
 *
 * <p>For the thing that helps somebody write a configuration: an editor offering the groups a
 * pattern defines, and saying why one will not compile as it is typed rather than when it runs.
 *
 * <p>It compiles through exactly the same path the engine does, so what it reports is what the
 * engine would do — a separate, more forgiving parser for the editor would be worse than no
 * editor support at all. Group names come from the compiled pattern itself, never from reading
 * the pattern text: the parser owns the pattern's facts and publishes them.
 *
 * @param valid  whether the pattern compiles
 * @param error  why it does not, or null
 * @param groups the capture groups in order, not counting the whole match
 */
public record PatternInfo(boolean valid, String error, List<Group> groups) {

    public PatternInfo {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * One capture group.
     *
     * @param index its number, counting from one
     * @param name  its name, or null if it has none
     */
    public record Group(int index, String name) {

    }

    /** Inspect a pattern, as UTF-8 — the default the encoding-aware overload generalises. */
    public static PatternInfo inspect(final String pattern) {
        return inspect(pattern, stroom.shapeshifter.regex.Encoding.UTF_8);
    }

    /**
     * Inspect a pattern for an encoding, because validity is per-encoding (phase 4's audit):
     * {@code \p{L}} compiles under UTF-8 and is refused under RAW, {@code 中} under a table
     * that cannot express it — an editor answering from the UTF-8 parse alone would call
     * valid what the template's compile then refuses. Pass the template's mapped encoding
     * ({@code RegexEncodings.forMatch}) and this reports what the engine will actually do.
     */
    public static PatternInfo inspect(final String pattern,
                                      final stroom.shapeshifter.regex.Encoding encoding) {
        final BytePattern compiled;
        try {
            compiled = BytePattern.compile(pattern,
                    java.util.EnumSet.noneOf(stroom.shapeshifter.regex.Flag.class), encoding);
        } catch (final PatternCompileException e) {
            return new PatternInfo(false, e.getMessage(), List.of());
        }

        final List<String> names = compiled.groupNames();
        final List<Group> groups = new ArrayList<>(compiled.groupCount());
        for (int i = 1; i <= compiled.groupCount(); i++) {
            groups.add(new Group(i, i < names.size() ? names.get(i) : null));
        }
        return new PatternInfo(true, null, groups);
    }
}
