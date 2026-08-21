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

import java.util.UUID;

/**
 * A way to watch a run without changing it.
 *
 * <p>An editor needs to show which template matched which bytes and what each capture held; a
 * production run needs none of that and should not pay for it. So the engine reports what it is
 * doing through this interface, and {@link #NONE} — whose methods are empty — is what it reports
 * to when nobody is listening. A single implementation at a call site is one the JIT can inline
 * away entirely, so the production cost is the branch that never happens.
 *
 * <p>Every method has a default that does nothing, so an implementation can answer only the
 * questions it cares about.
 *
 * <p>Offsets are byte counts from the start of the input and the start of the output
 * respectively. An offset into content that came from a variable rather than from the input
 * cannot be meaningful, and the engine says so by reporting {@link #UNLOCATABLE} rather than a
 * plausible-looking number.
 */
public interface Instrument {

    /** An instrument that does nothing, and can be optimised away for doing it. */
    Instrument NONE = new Instrument() {

    };

    /**
     * The offset reported when content did not come from the input.
     *
     * <p>Content handed to a template by a variable has no position in the original bytes. Any
     * number would be a lie, so this is a number that cannot be one. Half of
     * {@code Long.MAX_VALUE} rather than the maximum, so that offset arithmetic on top of it
     * cannot overflow before a {@code >= UNLOCATABLE} guard catches it.
     */
    long UNLOCATABLE = Long.MAX_VALUE / 2;

    /**
     * A template matched.
     *
     * @param templateId   which template
     * @param templateName its name, for display
     * @param inputOffset  where the match begins in the input, or {@link #UNLOCATABLE}
     * @param inputLength  the match's length in bytes, from where it begins to where its
     *                     consumption ends — so offset and length describe the same span
     * @param matchIndex   which match this is for that template, counting from one
     * @param depth        how deep in the dispatch this happened
     */
    default void onMatch(final UUID templateId,
                         final String templateName,
                         final long inputOffset,
                         final int inputLength,
                         final int matchIndex,
                         final int depth) {
    }

    /**
     * A capture was bound.
     *
     * @param templateId which template bound it
     * @param name       the variable's name
     * @param value      its value, in the engine's internal form — UTF-8, already normalised
     *                   from the template's declared encoding — not necessarily the input bytes
     * @param matchIndex which match it belongs to
     */
    default void onCapture(final UUID templateId,
                           final String name,
                           final byte[] value,
                           final int matchIndex) {
    }

    /**
     * The bytes a template matched, when they came from a variable rather than the input.
     *
     * <p>Reported separately because there is no offset that would let a caller find them.
     */
    default void onMatchContent(final UUID templateId, final byte[] content) {
    }

    /**
     * About to try a template. The value returned is handed back to
     * {@link #stopTiming}, and zero means "not timing" — which is what {@link #NONE} returns, so
     * a production run never reads the clock at all.
     */
    default long startTiming() {
        return 0;
    }

    /**
     * Finished trying a template, whether or not it matched.
     *
     * <p>Attempts that <i>fail</i> are reported too, and that is the point: a template that never
     * matches but is tried at every position is exactly the thing worth finding.
     *
     * @param templateId which template was tried
     * @param token      whatever {@link #startTiming} returned
     * @param matched    whether the attempt succeeded
     */
    default void stopTiming(final UUID templateId, final long token, final boolean matched) {
    }

    /**
     * A match's body finished writing.
     *
     * @param templateId   which template
     * @param matchIndex   which match
     * @param outputOffset where its output starts
     * @param outputLength how much it wrote
     */
    default void onOutput(final UUID templateId,
                          final int matchIndex,
                          final long outputOffset,
                          final long outputLength) {
    }
}
