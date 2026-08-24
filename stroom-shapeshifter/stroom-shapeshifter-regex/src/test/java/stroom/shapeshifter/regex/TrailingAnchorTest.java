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

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins for the published trailing anchor — the mirror of {@link LeadingAnchorTest}. The
 * INPUT conclusion is what lets a dispatching caller refuse an edge-touching match on a
 * partial buffer outright, and what licenses the end-anchor programme's search shortcuts,
 * so the pins include every conservatism: a wrong NONE loses a fast path; a wrong INPUT
 * would lose a match.
 */
class TrailingAnchorTest {

    private static TrailingAnchor of(final String pattern) {
        return BytePattern.compile(pattern).trailingAnchor();
    }

    @Test
    void theParserKnowsTheAnchor() {
        assertThat(of("a$")).isEqualTo(TrailingAnchor.INPUT);
        assertThat(of("a\\z")).isEqualTo(TrailingAnchor.INPUT);
        assertThat(of("(?s).a$")).isEqualTo(TrailingAnchor.INPUT);
        assertThat(of("(?m)a$")).isEqualTo(TrailingAnchor.LINE);
        assertThat(of("a")).isEqualTo(TrailingAnchor.NONE);
        assertThat(of("(\\d{3}) (\\d+)$")).isEqualTo(TrailingAnchor.INPUT);
        assertThat(of("([a-z ]+)=[^ ]+$")).isEqualTo(TrailingAnchor.INPUT);
    }

    @Test
    void theAnchorGovernsThroughStructure() {
        assertThat(of("(a$)")).isEqualTo(TrailingAnchor.INPUT);
        assertThat(of("x(a|b)$")).isEqualTo(TrailingAnchor.INPUT);
        // Every branch anchored: unlike the leading analysis, this one does look through
        // alternations — the weakest branch governs.
        assertThat(of("a$|b$")).isEqualTo(TrailingAnchor.INPUT);
        assertThat(of("(?m)a$|b$")).isEqualTo(TrailingAnchor.LINE);
        assertThat(of("a$|b")).isEqualTo(TrailingAnchor.NONE);
        // Repetition: the last iteration carries the body's anchor — unless zero iterations
        // are allowed, when a match can be empty and end anywhere.
        assertThat(of("(a$){2}")).isEqualTo(TrailingAnchor.INPUT);
        assertThat(of("(a$)*")).isEqualTo(TrailingAnchor.NONE);
    }

    @Test
    void consumingTailsAreConservativelyUnanchored() {
        // Looking through x? would be sound here (nothing is consumable past the input end),
        // but the same rule under (?m) is not: (?m)a$\n? can end just past a line end. One
        // rule, the safe direction — NONE loses a fast path, never a match. If the analysis
        // ever splits the two cases, update this pin and celebrate.
        assertThat(of("a$x?")).isEqualTo(TrailingAnchor.NONE);
        assertThat(of("(?m)a$\\n?")).isEqualTo(TrailingAnchor.NONE);
        // A pure zero-width tail is looked through.
        assertThat(of("a$(?=x)")).isEqualTo(TrailingAnchor.INPUT);
    }

    @Test
    void everyCompiledArtifactAgreesBecauseAllReadTheParse() {
        for (final Engine engine : new Engine[]{
                Engine.TREE, Engine.SCAN_PLAN, Engine.BACKTRACK, Engine.SIMULATE}) {
            assertThat(BytePattern
                    .compileForcing(engine, "END:.$", EnumSet.noneOf(Flag.class))
                    .trailingAnchor())
                    .as("%s", engine)
                    .isEqualTo(TrailingAnchor.INPUT);
        }
    }
}
