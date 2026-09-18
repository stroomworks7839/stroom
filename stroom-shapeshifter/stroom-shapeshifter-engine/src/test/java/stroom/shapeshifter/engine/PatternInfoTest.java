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

import stroom.shapeshifter.engine.PatternInfo.Group;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pattern introspection, for whatever helps somebody write a configuration.
 *
 * <p>The claim worth testing is that it agrees with the engine — a more forgiving inspection than
 * the one that runs would tell an author their pattern is fine when it is not.
 */
class PatternInfoTest {

    @Test
    void countsTheGroupsAPatternDefines() {
        final PatternInfo info = PatternInfo.inspect("^([0-9]+) \\[([^\\]]*)\\] (.*)$");
        assertThat(info.valid()).isTrue();
        assertThat(info.error()).isNull();
        assertThat(info.groups()).containsExactly(
                new Group(1, null, 1, 9), new Group(2, null, 12, 20), new Group(3, null, 23, 27));
    }

    @Test
    void findsTheNamesOfNamedGroups() {
        final PatternInfo info = PatternInfo.inspect("(?<year>\\d{4})-(?<month>\\d{2})");
        assertThat(info.groups()).containsExactly(
                new Group(1, "year", 0, 14), new Group(2, "month", 15, 30));
    }

    @Test
    void leavesUnnamedGroupsUnnamedAmongNamedOnes() {
        final PatternInfo info = PatternInfo.inspect("(\\w+)=(?<value>\\d+)");
        assertThat(info.groups()).containsExactly(new Group(1, null, 0, 5), new Group(2, "value", 6, 19));
    }

    @Test
    void isNotConfusedByLookbehind() {
        // "(?<=" and "(?<!" open a lookbehind rather than name a group, and a scan that mistook
        // one for the other would report a group called "=foo".
        final PatternInfo info = PatternInfo.inspect("(?<=x)(\\d+)");
        assertThat(info.valid()).isTrue();
        assertThat(info.groups()).containsExactly(new Group(1, null, 6, 11));
    }

    @Test
    void countsNoGroupsWhenThereAreNone() {
        final PatternInfo info = PatternInfo.inspect("^plain text$");
        assertThat(info.valid()).isTrue();
        assertThat(info.groups()).isEmpty();
    }

    @Test
    void neverThrowsOnAPatternEndingMidConstruct() {
        // The audit found the old text-scan reading past the end of a pattern whose last three
        // characters were "(?<" — valid or not, inspection must answer, never blow up.
        assertThat(PatternInfo.inspect("\\(?<")).isNotNull();
        assertThat(PatternInfo.inspect("x\\(?<")).isNotNull();
        assertThat(PatternInfo.inspect("(?<")).isNotNull();
    }

    @Test
    void saysWhyAPatternWillNotCompile() {
        final PatternInfo info = PatternInfo.inspect("(unclosed");
        assertThat(info.valid()).isFalse();
        assertThat(info.error()).isNotBlank();
        assertThat(info.groups()).isEmpty();
    }

    @Test
    void agreesWithTheEngineAboutWhatIsValid() {
        // The whole point: an inspection that accepted more than the engine would tell an author
        // their configuration is fine and then fail at run time.
        assertThat(PatternInfo.inspect("+").valid()).isFalse();
        assertThat(PatternInfo.inspect("(?>a+)b").valid()).isTrue();
        assertThat(PatternInfo.inspect("(a)\\1").valid()).isTrue();
    }

    @Test
    void spansComeInGroupOrderWithNestedGroupsInside() {
        // The parser records a group when its ')' closes, inner first; the report is by number.
        final PatternInfo info = PatternInfo.inspect("((a)(b))c");
        assertThat(info.groups()).containsExactly(
                new Group(1, null, 0, 8), new Group(2, null, 1, 4), new Group(3, null, 4, 7));
        assertThat("((a)(b))c".substring(2, 3)).isEqualTo("a");
    }
}
