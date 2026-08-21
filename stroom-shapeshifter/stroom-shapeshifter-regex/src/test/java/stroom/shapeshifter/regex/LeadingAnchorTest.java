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
 * Pins for the published leading anchor — the parser's conclusion, which a dispatching caller
 * uses to ask the cheaper anchored question. The cases include everything a pattern-text
 * sniff gets wrong, which is the whole reason the fact is published instead.
 */
class LeadingAnchorTest {

    private static LeadingAnchor of(final String pattern) {
        return BytePattern.compile(pattern).leadingAnchor();
    }

    @Test
    void theParserKnowsTheAnchor() {
        assertThat(of("^a")).isEqualTo(LeadingAnchor.INPUT);
        assertThat(of("\\Aa")).isEqualTo(LeadingAnchor.INPUT);
        assertThat(of("(?s)^a.")).isEqualTo(LeadingAnchor.INPUT);
        assertThat(of("(?m)^a")).isEqualTo(LeadingAnchor.LINE);
        assertThat(of("a")).isEqualTo(LeadingAnchor.NONE);
    }

    @Test
    void theCasesATextSniffGetsWrong() {
        // The sniff refused any pattern containing '|' anywhere; the parser knows a leading
        // anchor governs everything after it.
        assertThat(of("^(a|b)")).isEqualTo(LeadingAnchor.INPUT);
        // The sniff matched on a literal leading '^', so a flag group hid the anchor from it.
        assertThat(of("(?s)^a.")).isEqualTo(LeadingAnchor.INPUT);
        // A caret inside a character class anchors nothing — a sniff that greps for '^' either
        // refuses this pattern or, worse, believes it.
        assertThat(of("[^a]b")).isEqualTo(LeadingAnchor.NONE);
    }

    @Test
    void anchoredAlternationsAreConservativelyUnanchored() {
        // Every branch of these is anchored, so INPUT would be the sharper answer — but the
        // analysis does not look through alternations, and NONE is the safe direction: a
        // caller loses a fast path, never a match. If the analysis ever sharpens, update
        // this pin and celebrate.
        assertThat(of("^a|^b")).isEqualTo(LeadingAnchor.NONE);
        assertThat(of("(^a|^b)")).isEqualTo(LeadingAnchor.NONE);
        assertThat(of("(^a|b)")).isEqualTo(LeadingAnchor.NONE);
    }

    @Test
    void everyCompiledArtifactAgreesBecauseAllReadTheParse() {
        for (final Engine engine : new Engine[]{
                Engine.TREE, Engine.SCAN_PLAN, Engine.BACKTRACK, Engine.SIMULATE}) {
            assertThat(BytePattern.compileForcing(engine, "^BEGIN:", EnumSet.noneOf(Flag.class))
                    .leadingAnchor()).as("%s ^", engine).isEqualTo(LeadingAnchor.INPUT);
            assertThat(BytePattern.compileForcing(engine, "(?m)^BEGIN:", EnumSet.noneOf(Flag.class))
                    .leadingAnchor()).as("%s (?m)^", engine).isEqualTo(LeadingAnchor.LINE);
            assertThat(BytePattern.compileForcing(engine, "BEGIN:", EnumSet.noneOf(Flag.class))
                    .leadingAnchor()).as("%s floating", engine).isEqualTo(LeadingAnchor.NONE);
        }
    }
}
