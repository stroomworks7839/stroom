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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The experimental tree-walking engine. Shape checks only: its semantics are established
 * against the JDK and the other engines in {@link DifferentialTest}, where it is pinned into
 * the all-engines agreement test and the fancy corpus.
 */
class NodeTreeTest {

    @Test
    void reachedOnlyByForcing() {
        final BytePattern forced =
                BytePattern.compileForcing(Engine.TREE, "(\\w+) \\1", java.util.Set.of());
        assertThat(forced.engine()).isEqualTo(Engine.TREE);
        assertThat(forced.tier()).isEqualTo(Engine.TREE.ordinal());
        assertThat(forced.explain()).contains("node-tree backtracking");

        // The compiler never chooses it.
        assertThat(BytePattern.compile("(\\w+) \\1").engine()).isEqualTo(Engine.FANCY);
    }

    @Test
    void runsEveryConstructTheOtherEnginesRun() {
        assertThat(matcher("^([^,]+),(\\d+)$").find(bytes("alpha,42"))).isTrue();
        assertThat(matcher("(\\w+) \\1").find(bytes("say hey hey"))).isTrue();
        assertThat(matcher("foo(?=bar)").find(bytes("foobar"))).isTrue();
        assertThat(matcher("(?<=foo)bar").find(bytes("foobar"))).isTrue();
        assertThat(matcher("^(?>a|ab)c$").find(bytes("abc"))).isFalse(); // atomic commits
        assertThat(matcher("a++ab").find(bytes("aaaab"))).isFalse();     // possessive
    }

    @Test
    void capturesRestoreThroughRecursion() {
        final ByteMatcher matcher = matcher("(?:(a)|b)\\1|bb");
        // The first alternative binds group 1 then fails on \1; the overall match must come
        // from the second alternative with group 1 unset — which only holds if the recursion
        // restored the capture on the way out.
        assertThat(matcher.find(bytes("bb"))).isTrue();
        assertThat(matcher.matchedGroup(1)).isFalse();
    }

    @Test
    void keepsTheStepBudget() {
        final ByteMatcher matcher = matcher("(a+)+b\\1");
        assertThatThrownBy(() -> matcher.find(bytes("a".repeat(40))))
                .isInstanceOf(MatchLimitException.class);
        assertThat(matcher.find(bytes("aabaa"))).isTrue(); // usable afterwards
    }

    @Test
    void keepsTheStreamingContract() {
        final ByteMatcher matcher = matcher("(a+)-\\1");
        assertThat(matcher.match(ByteWindow.partial(bytes("aa-a"), 0, 4), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NEED_MORE_INPUT);
        assertThat(matcher.match(ByteWindow.of("aa-aa"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.MATCH);
        assertThat(matcher.match(ByteWindow.partial(bytes("aa-b"), 0, 4), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NO_MATCH);
    }

    private static ByteMatcher matcher(final String pattern) {
        return BytePattern.compileForcing(Engine.TREE, pattern, java.util.Set.of()).matcher();
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
