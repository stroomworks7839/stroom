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
 * editor support at all.
 *
 * @param valid      whether the pattern compiles
 * @param error      why it does not, or null
 * @param groupCount how many capture groups it defines, not counting the whole match
 * @param groups     the groups, in order
 */
public record PatternInfo(boolean valid, String error, int groupCount, List<Group> groups) {

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

    /** Inspect a pattern. */
    public static PatternInfo inspect(final String pattern) {
        final BytePattern compiled;
        try {
            compiled = BytePattern.compile(pattern);
        } catch (final RuntimeException e) {
            return new PatternInfo(false, e.getMessage(), 0, List.of());
        }

        final int count = compiled.groupCount();
        final String[] names = new String[count + 1];
        for (final String candidate : candidateNames(pattern)) {
            final int index = compiled.groupIndex(candidate);
            if (index > 0 && index <= count) {
                names[index] = candidate;
            }
        }

        final List<Group> groups = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            groups.add(new Group(i, names[i]));
        }
        return new PatternInfo(true, null, count, groups);
    }

    /**
     * The names a pattern's text appears to define.
     *
     * <p>The matching layer answers name-to-index, which is what matching needs, and does not
     * publish the list — and this port does not change it. So the candidates are read off the
     * pattern text with a deliberately naive scan for {@code (?<name>}, and every one of them is
     * then <b>checked against the compiled pattern</b>. That check is what makes the naivety
     * safe: a name the scan invents is rejected because the pattern does not know it, and a name
     * the scan misses leaves its group unnamed rather than misnamed. The worst case is less
     * information, never wrong information.
     */
    private static List<String> candidateNames(final String pattern) {
        final List<String> names = new ArrayList<>();
        int i = 0;
        while (i + 3 < pattern.length()) {
            final int open = pattern.indexOf("(?<", i);
            if (open < 0) {
                break;
            }
            i = open + 3;
            // Lookbehind, not a name.
            if (pattern.charAt(i) == '=' || pattern.charAt(i) == '!') {
                continue;
            }
            final int close = pattern.indexOf('>', i);
            if (close < 0) {
                break;
            }
            names.add(pattern.substring(i, close));
            i = close + 1;
        }
        return names;
    }
}
