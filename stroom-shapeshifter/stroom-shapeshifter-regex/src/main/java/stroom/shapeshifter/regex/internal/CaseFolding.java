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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Case folding for {@code (?i)}, applied to code points at compile time.
 * <p>
 * Folding at compile time is what keeps case-insensitivity free at match time: the class already
 * contains every spelling, so the matcher never converts anything. It is also the only way to do
 * it in a byte engine, since the cases of a character can differ in length once encoded.
 *
 * <h2>Why the equivalence classes are not just upper/lower pairs</h2>
 * Most letters fold in pairs, but not all. {@code K} and {@code k} also fold with the Kelvin sign
 * {@code K} (U+212A); {@code s} folds with the long s {@code ſ} (U+017F); Greek sigma has three
 * spellings. A pattern of {@code [a-z]} with {@code (?i)} is expected to match all of them, and a
 * pairwise conversion silently misses the third member. So the table below groups code points
 * into <em>equivalence classes</em>, keyed by lower-casing the upper case of each — which brings
 * the odd spellings back to the ordinary letter — and a folded class takes the whole group.
 * <p>
 * The groups are derived from the JDK's own case mappings rather than shipped as a table, for the
 * same reason {@link UnicodeClasses} is: it keeps this engine and {@code java.util.regex} saying
 * the same thing about the same input, which is what the differential tests depend on.
 */
public final class CaseFolding {

    /** Built on first use: one sweep of the code space, kept for the life of the JVM. */
    private static final class Table {

        private static final int[] MEMBERS;
        private static final int[][] GROUPS;

        static {
            final Map<Integer, List<Integer>> byKey = new HashMap<>();
            for (int codePoint = 0; codePoint <= CodePointSet.MAX; codePoint++) {
                if (Character.toUpperCase(codePoint) == codePoint
                    && Character.toLowerCase(codePoint) == codePoint) {
                    continue; // no case at all, so nothing to fold it with
                }
                byKey.computeIfAbsent(key(codePoint), ignored -> new ArrayList<>())
                        .add(codePoint);
            }

            final List<int[]> groups = new ArrayList<>();
            final List<Integer> members = new ArrayList<>();
            byKey.values().stream()
                    .filter(group -> group.size() > 1)
                    .forEach(group -> {
                        final int[] asArray = group.stream().mapToInt(Integer::intValue).toArray();
                        for (final int member : asArray) {
                            members.add(member);
                            groups.add(asArray);
                        }
                    });

            MEMBERS = members.stream().mapToInt(Integer::intValue).toArray();
            GROUPS = groups.toArray(new int[0][]);
        }

        private static int key(final int codePoint) {
            return Character.toLowerCase(Character.toUpperCase(codePoint));
        }
    }

    private CaseFolding() {
    }

    /**
     * The set with every case variant of every member added.
     * <p>
     * Applied before any negation, which is what makes {@code (?i)[^x]} fail to match {@code X}:
     * the class is "not x and not X", not "not x".
     *
     * @param unicode false under {@code (?-u)}, where folding stays inside ASCII — so
     *                {@code (?i)(?-u)[a-z]} matches {@code K} but not the Kelvin sign. Folding
     *                follows the same switch as the shorthands, because an "ASCII mode" that
     *                quietly went on folding across the whole of Unicode would not be one.
     */
    public static CodePointSet closure(final CodePointSet set, final boolean unicode) {
        final CodePointSet.Builder builder = new CodePointSet.Builder();
        builder.add(set);
        boolean grew = false;
        for (int i = 0; i < Table.MEMBERS.length; i++) {
            if (!set.contains(Table.MEMBERS[i]) || (!unicode && Table.MEMBERS[i] > 0x7F)) {
                continue;
            }
            for (final int variant : Table.GROUPS[i]) {
                if ((!unicode && variant > 0x7F) || set.contains(variant)) {
                    continue;
                }
                builder.add(variant, variant);
                grew = true;
            }
        }
        return grew
                ? builder.build()
                : set;
    }
}
