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
        assertThat(info.groupCount()).isEqualTo(3);
        assertThat(info.groups()).containsExactly(
                new Group(1, null), new Group(2, null), new Group(3, null));
    }

    @Test
    void findsTheNamesOfNamedGroups() {
        final PatternInfo info = PatternInfo.inspect("(?<year>\\d{4})-(?<month>\\d{2})");
        assertThat(info.groupCount()).isEqualTo(2);
        assertThat(info.groups()).containsExactly(
                new Group(1, "year"), new Group(2, "month"));
    }

    @Test
    void leavesUnnamedGroupsUnnamedAmongNamedOnes() {
        final PatternInfo info = PatternInfo.inspect("(\\w+)=(?<value>\\d+)");
        assertThat(info.groups()).containsExactly(new Group(1, null), new Group(2, "value"));
    }

    @Test
    void isNotConfusedByLookbehind() {
        // "(?<=" and "(?<!" open a lookbehind rather than name a group, and a scan that mistook
        // one for the other would report a group called "=foo".
        final PatternInfo info = PatternInfo.inspect("(?<=x)(\\d+)");
        assertThat(info.valid()).isTrue();
        assertThat(info.groups()).containsExactly(new Group(1, null));
    }

    @Test
    void countsNoGroupsWhenThereAreNone() {
        final PatternInfo info = PatternInfo.inspect("^plain text$");
        assertThat(info.valid()).isTrue();
        assertThat(info.groupCount()).isZero();
        assertThat(info.groups()).isEmpty();
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
}
