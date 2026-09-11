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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.value.TypedValue;

import java.util.Arrays;

/**
 * The values one variable took, indexed by which match produced them.
 *
 * <p>A variable captured inside a repeating match does not have <i>a</i> value; it has one per
 * match, and a reference elsewhere has to be able to ask for the third, or the one belonging to
 * the match currently being processed. So a store is a sparse list indexed by match count, not a
 * cell.
 *
 * <p>Sparse matters: a capture that did not match leaves a hole rather than shifting everything
 * after it, so index three still means the third match.
 */
public final class Store {

    /** What a store starts with; most hold one value, and a second is the common growth. */
    private static final int INITIAL = 2;

    /**
     * The values, indexed by match count, with nulls for the holes.
     *
     * <p>An array rather than a {@code List<TypedValue>}: this is read on every value a
     * reference resolves and written on every capture a template binds, which with
     * {@link VarRegistry}'s slots is the hottest pair of reads in the engine (design 33 §11 E).
     * What it does is what {@code ArrayList} does — grow, pad with nulls, index — minus the
     * interface call at each of those sites.
     */
    private TypedValue[] values = new TypedValue[INITIAL];

    /** How far the store spans, holes included; the array is longer once it has grown. */
    private int size;

    /** Put a value at a match index, growing the store as needed. */
    public void set(final int matchCount, final TypedValue value) {
        if (matchCount >= values.length) {
            // Doubling until it fits, rather than once per index: a capture binding its
            // hundredth match should not copy the store a hundred times.
            int bigger = Math.max(values.length, 1);
            while (bigger <= matchCount) {
                bigger *= 2;
            }
            values = Arrays.copyOf(values, bigger);
        }
        if (matchCount >= size) {
            size = matchCount + 1;
        }
        values[matchCount] = value;
    }

    /** The value at a match index, or null. */
    public TypedValue get(final int matchCount) {
        return matchCount >= 0 && matchCount < size ? values[matchCount] : null;
    }

    /**
     * Clear the value at a match index.
     *
     * <p>Used when a capture does not match: without it the previous record's value would still
     * be there, and the reference would quietly read stale data rather than nothing.
     */
    public void remove(final int matchCount) {
        if (matchCount >= 0 && matchCount < size) {
            values[matchCount] = null;
        }
    }

    /** How many match indices the store spans, holes included. */
    public int size() {
        return size;
    }

    /** The highest index holding a value, or -1. */
    public int lastIndex() {
        for (int i = size - 1; i >= 0; i--) {
            if (values[i] != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Forget everything.
     *
     * <p>A new match sequence starts from nothing — DS3's rule, whose stores are cleared on the
     * first store of a sequence — so a record producing fewer matches than the one before it
     * cannot leave the previous record's tail hanging past its own length.
     */
    public void clear() {
        // The spanned range is emptied rather than the whole array: what is past it is already
        // null, because nothing writes beyond size without moving it.
        Arrays.fill(values, 0, size, null);
        size = 0;
    }

    /** The most recently stored value, for references that do not say which they want. */
    public TypedValue latest() {
        final int last = lastIndex();
        return last < 0 ? null : values[last];
    }
}
