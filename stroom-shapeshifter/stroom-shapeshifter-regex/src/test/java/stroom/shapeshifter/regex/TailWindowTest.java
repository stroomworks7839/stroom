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
 * Pins for the tail-window jump ({@code design/06-performance-plan.md} §6 Phase 2): an
 * unanchored search for an END_INPUT-anchored pattern of finite maximum length starts at
 * {@code regionTo - max}, because no earlier candidate can produce a match ending at the
 * region end. The jump must be invisible — same matches, same captures, same misses — so
 * these pins are the edges where an off-by-one would show, and the exclusions that must
 * never jump: line anchors, unbounded patterns, anchored questions.
 */
class TailWindowTest {

    private static ByteMatcher matcher(final String pattern) {
        return BytePattern.compile(pattern).matcher();
    }

    private static String group(final ByteMatcher matcher, final int index) {
        return new String(matcher.groupBytes(index), StandardCharsets.UTF_8);
    }

    @Test
    void aMatchSpanningExactlyTheMaximumSurvivesTheJump() {
        // The window is [regionTo - 4, regionTo); the match uses every byte of it.
        final ByteMatcher m = matcher("(a{1,4})$");
        assertThat(m.match("bbaaaa".getBytes(StandardCharsets.UTF_8), 0, 6,
                Anchoring.UNANCHORED)).isTrue();
        assertThat(group(m, 1)).isEqualTo("aaaa");
    }

    @Test
    void aShorterMatchInsideTheWindowIsStillFoundLeftmost() {
        // The jump lands on a non-matching byte; the walk inside the window finds the match.
        final ByteMatcher m = matcher("(a{1,4})$");
        assertThat(m.match("bbbaaa".getBytes(StandardCharsets.UTF_8), 0, 6,
                Anchoring.UNANCHORED)).isTrue();
        assertThat(group(m, 1)).isEqualTo("aaa");
    }

    @Test
    void theJumpRespectsASubRegion() {
        // Region [2, 8) of a larger array: the window is relative to the region end.
        final byte[] data = "ab12cd34ef".getBytes(StandardCharsets.UTF_8);
        final ByteMatcher m = matcher("(\\d{2})$");
        assertThat(m.match(data, 2, 8, Anchoring.UNANCHORED)).isTrue();
        assertThat(group(m, 1)).isEqualTo("34");
        assertThat(m.match(data, 0, 4, Anchoring.UNANCHORED)).isTrue();
        assertThat(group(m, 1)).isEqualTo("12");
    }

    @Test
    void anEmptyMatchAtTheRegionEndIsTheZeroSpanWindow() {
        // maxLength 0: the window collapses to the region end itself.
        final ByteMatcher m = matcher("$");
        assertThat(m.match("abc".getBytes(StandardCharsets.UTF_8), 0, 3,
                Anchoring.UNANCHORED)).isTrue();
    }

    @Test
    void aMissIsStillAMiss() {
        final ByteMatcher m = matcher("(\\d{3})$");
        assertThat(m.match("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6,
                Anchoring.UNANCHORED)).isFalse();
    }

    @Test
    void lineAnchorsNeverJump() {
        // (?m)$ is END_LINE: a match can end mid-region, far outside any tail window. The
        // jump firing here would lose this match.
        final ByteMatcher m = BytePattern.compile("(\\d+)$", Flag.MULTILINE).matcher();
        assertThat(m.match("12\nxxxxxxxx".getBytes(StandardCharsets.UTF_8), 0, 11,
                Anchoring.UNANCHORED)).isTrue();
        assertThat(group(m, 1)).isEqualTo("12");
    }

    @Test
    void unboundedPatternsNeverJump() {
        // No finite maximum, no window: the leftmost start can be arbitrarily early.
        final ByteMatcher m = matcher("([a-z]+)$");
        assertThat(m.match("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6,
                Anchoring.UNANCHORED)).isTrue();
        assertThat(group(m, 1)).isEqualTo("abcdef");
    }

    @Test
    void searchStartAnchorsNeverJump() {
        // \G holds where the search started; moving the start moves the anchor, and the
        // audit produced \Ga{1,2}$ matching where the JDK refuses. Bounded and end-anchored,
        // so only the \G exclusion keeps the jump away.
        final ByteMatcher m = matcher("\\Ga{1,2}$");
        assertThat(m.match("bbbaa".getBytes(StandardCharsets.UTF_8), 0, 5,
                Anchoring.UNANCHORED)).isFalse();
        assertThat(m.match("aa".getBytes(StandardCharsets.UTF_8), 0, 2,
                Anchoring.UNANCHORED)).isTrue();
    }

    @Test
    void everyForcedEngineAgreesThroughTheOneJumpSite() {
        final byte[] data = "xxxxxxxxxxxxxxxxxxxx 200 42".getBytes(StandardCharsets.UTF_8);
        for (final Engine engine : new Engine[]{
                Engine.TREE, Engine.SCAN_PLAN, Engine.SIMULATE}) {
            final ByteMatcher m = BytePattern
                    .compileForcing(engine, "(\\d{3}) (\\d+)$", EnumSet.noneOf(Flag.class))
                    .matcher();
            assertThat(m.match(data, 0, data.length, Anchoring.UNANCHORED))
                    .as("%s", engine)
                    .isTrue();
            assertThat(group(m, 1)).as("%s", engine).isEqualTo("200");
            assertThat(group(m, 2)).as("%s", engine).isEqualTo("42");
        }
    }
}
