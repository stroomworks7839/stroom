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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins for the answers an unanchored search gives on anchored patterns — the answers the
 * early-exit optimisation ({@code design/06-performance-plan.md} §1) must preserve exactly.
 *
 * <p>Each is stated per engine, because each engine owns its own search loop and each will
 * carry its own exit. The {@code ^}-at-region-start rule matters most: {@code ^} holds at the
 * {@code from} a search is given, wherever that is in the array — the template engine's
 * dispatch relies on it — so an early exit may conclude "nowhere past the first viable
 * position", never "nowhere at all".
 */
class AnchoredSearchTest {

    private static final Engine[] ENGINES = {
            Engine.TREE, Engine.SCAN_PLAN, Engine.BACKTRACK, Engine.SIMULATE,
    };

    private static ByteMatcher matcher(final Engine engine, final String pattern) {
        return BytePattern.compileForcing(engine, pattern, EnumSet.noneOf(Flag.class)).matcher();
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anchoredMissIsDecidedAtTheFirstPosition() {
        final byte[] data = bytes("no match anywhere in this content\n");
        for (final Engine engine : ENGINES) {
            assertThat(matcher(engine, "^BEGIN:").match(data, 0, data.length, Anchoring.UNANCHORED))
                    .as("%s: ^-anchored search over non-matching content", engine)
                    .isFalse();
        }
    }

    @Test
    void anchoredHitAtTheRegionStartStillWins() {
        final byte[] data = bytes("BEGIN:record\nmore\n");
        for (final Engine engine : ENGINES) {
            final ByteMatcher matcher = matcher(engine, "^BEGIN:");
            assertThat(matcher.match(data, 0, data.length, Anchoring.UNANCHORED))
                    .as("%s: ^-anchored search over matching content", engine)
                    .isTrue();
            assertThat(matcher.start()).isZero();
            assertThat(matcher.end()).isEqualTo(6);
        }
    }

    @Test
    void caretHoldsAtTheRegionStartWhereverItIs() {
        // The template engine hands a mid-array cursor as a search's from, and ^ holds there.
        // An interior match must stay found after the exit lands.
        final byte[] data = bytes("consumed;BEGIN:rest\n");
        for (final Engine engine : ENGINES) {
            final ByteMatcher matcher = matcher(engine, "^BEGIN:");
            assertThat(matcher.match(data, 9, data.length, Anchoring.UNANCHORED))
                    .as("%s: ^ at a mid-array region start", engine)
                    .isTrue();
            assertThat(matcher.start()).isEqualTo(9);
        }
    }

    @Test
    void anchoredMissFromAMidArrayRegionStartIsStillAMiss() {
        final byte[] data = bytes("BEGIN:early\nnothing later\n");
        for (final Engine engine : ENGINES) {
            assertThat(matcher(engine, "^BEGIN:").match(data, 3, data.length, Anchoring.UNANCHORED))
                    .as("%s: the match before the region does not count", engine)
                    .isFalse();
        }
    }

    @Test
    void lineAnchorsKeepSearchingPastTheFirstPosition() {
        // Line-anchored patterns have viable positions after every newline; the input-anchored
        // exit must not fire for them.
        final byte[] data = bytes("first line\nBEGIN:second\n");
        for (final Engine engine : ENGINES) {
            final ByteMatcher matcher = matcher(engine, "(?m)^BEGIN:");
            assertThat(matcher.match(data, 0, data.length, Anchoring.UNANCHORED))
                    .as("%s: line-anchored search finds an interior line start", engine)
                    .isTrue();
            assertThat(matcher.start()).isEqualTo(11);
        }
    }

    @Test
    void floatingPatternsAreUntouchedByAnchorReasoning() {
        final byte[] data = bytes("interior BEGIN:match\n");
        for (final Engine engine : ENGINES) {
            final ByteMatcher matcher = matcher(engine, "BEGIN:");
            assertThat(matcher.match(data, 0, data.length, Anchoring.UNANCHORED))
                    .as("%s: floating search", engine)
                    .isTrue();
            assertThat(matcher.start()).isEqualTo(9);
        }
    }
}
