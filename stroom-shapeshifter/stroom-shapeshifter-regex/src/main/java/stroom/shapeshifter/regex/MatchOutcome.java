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
 * The result of a match attempt.
 * <p>
 * The third outcome is the point. A matcher that can only answer yes or no must guess at the
 * edge of a buffer, and the guess is always the same one: report the short match it can see.
 * That is how a streaming parser silently truncates a record whose delimiter had not yet
 * arrived. Distinguishing "no" from "not yet" is what makes incremental matching correct rather
 * than approximately correct.
 */
public enum MatchOutcome {

    /** A match was found; the group accessors are valid. */
    MATCH,

    /** No match, and no amount of further input could change that. */
    NO_MATCH,

    /**
     * A match may still be possible if the window is extended.
     * <p>
     * Returned when the attempt was limited by the end of the available bytes rather than by the
     * input itself — a scan that reached the edge and could have continued, a literal that ran
     * out half way, or an end-anchor whose truth is not yet knowable. Never returned once the
     * window is marked complete, since then there is nothing more to wait for.
     */
    NEED_MORE_INPUT
}
