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

/**
 * What a match produced.
 *
 * <p>{@code groups[0]} is always the whole match; what the rest mean depends on the kind of
 * match. A regex fills them with its capture groups. A delimiter split fills three: the segment
 * including its delimiter, the raw content before it, and the content with any container and
 * escape characters removed. A progressive match puts step {@code i}'s output in
 * {@code groups[i + 1]}.
 *
 * <p>A null entry is a group that did not participate, which is different from one that matched
 * emptily.
 *
 * @param groups     the captured values, group 0 first; entries may be null
 * @param advance    how far the cursor moves, measured from the start of the searched region
 *                   rather than from the start of the match
 * @param matchStart where the match began within the searched region, which is not zero when the
 *                   pattern had to search forward for it
 */
public record MatchResult(TypedValue[] groups, int advance, int matchStart) {

    /** The value of a group, or null if it is absent or out of range. */
    public TypedValue group(final int index) {
        return index >= 0 && index < groups.length ? groups[index] : null;
    }

    /** The bytes of a group, or an empty array. */
    public byte[] groupBytes(final int index) {
        final TypedValue value = group(index);
        return value == null ? new byte[0] : value.asBytes();
    }

    /** A result with no groups, for the places that need a match-shaped nothing. */
    public static MatchResult empty() {
        return new MatchResult(new TypedValue[0], 0, 0);
    }
}
