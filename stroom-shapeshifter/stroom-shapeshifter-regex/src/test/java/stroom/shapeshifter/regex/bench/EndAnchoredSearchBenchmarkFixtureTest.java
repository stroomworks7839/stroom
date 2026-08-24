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

package stroom.shapeshifter.regex.bench;

import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Engine;
import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.MatchLimitException;
import stroom.shapeshifter.regex.TrailingAnchor;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every end-anchored shape must hit or miss exactly as its name claims, on all three forced
 * engines and on the JDK — a shape that quietly stopped matching would still produce a score,
 * of scanning and failing, which is a different measurement wearing the same name
 * ({@code design/06-performance-plan.md}, the fourth blind spot). The forced compiles here
 * also guard the benchmark's own setup: if an engine ever refuses one of these patterns,
 * this test fails the build instead of JMH silently dropping the rows.
 */
class EndAnchoredSearchBenchmarkFixtureTest {

    @Test
    void everyShapeAnswersAsItsNameClaims() {
        for (final EndAnchoredSearchBenchmark.Shape shape
                : EndAnchoredSearchBenchmark.Shape.values()) {
            final byte[] data = shape.data();
            for (final Engine engine
                    : new Engine[]{Engine.TREE, Engine.SCAN_PLAN, Engine.SIMULATE}) {
                final ByteMatcher matcher = BytePattern
                        .compileForcing(engine, shape.pattern(), EnumSet.noneOf(Flag.class))
                        .matcher();
                if (engine == Engine.TREE && !shape.treeCanRun()) {
                    // The benchmark omits these rows because the engine refuses them; the
                    // refusal is pinned here so the omission cannot silently become stale.
                    assertThatThrownBy(() ->
                            matcher.match(data, 0, data.length, Anchoring.UNANCHORED))
                            .as("%s on TREE must exceed the step budget", shape)
                            .isInstanceOf(MatchLimitException.class);
                    continue;
                }
                assertThat(matcher.match(data, 0, data.length, Anchoring.UNANCHORED))
                        .as("%s on %s", shape, engine)
                        .isEqualTo(shape.matches());
            }
            final Matcher java = Pattern.compile(shape.pattern())
                    .matcher(new String(data, StandardCharsets.UTF_8));
            assertThat(java.find())
                    .as("%s on the JDK", shape)
                    .isEqualTo(shape.matches());
        }
    }

    /** Every shape's tail-window claim must agree with the published facts — the gate once
     * believed the WEBLOG tail was bounded when {@code \d+} is not, and measured a phase
     * against rows it could never move. The facts are the arbiter, not the label. */
    @Test
    void tailWindowClaimsAgreeWithThePublishedFacts() {
        for (final EndAnchoredSearchBenchmark.Shape shape
                : EndAnchoredSearchBenchmark.Shape.values()) {
            final BytePattern compiled = BytePattern.compile(shape.pattern());
            assertThat(compiled.trailingAnchor())
                    .as("%s trailing anchor", shape)
                    .isEqualTo(TrailingAnchor.INPUT);
            assertThat(compiled.maxLength() != Integer.MAX_VALUE)
                    .as("%s bounded", shape)
                    .isEqualTo(shape.tailWindowed());
        }
    }

    /** The tree state's param list must be exactly the shapes the engine accepts — a drifted
     * list would silently drop rows, the blind spot the row-set lesson closed. */
    @Test
    void treeParamListMatchesWhatTheEngineAccepts() throws Exception {
        final String[] declared = EndAnchoredSearchBenchmark.TreeShapes.class
                .getField("shape")
                .getAnnotation(Param.class)
                .value();
        final String[] accepted = Arrays.stream(EndAnchoredSearchBenchmark.Shape.values())
                .filter(EndAnchoredSearchBenchmark.Shape::treeCanRun)
                .map(Enum::name)
                .toArray(String[]::new);
        assertThat(declared).containsExactly(accepted);
    }

    /**
     * The KV shape's leftmost-first capture is {@code "bob create time"}, not the
     * {@code "create time"} a human would name — the earliest viable candidate start wins,
     * and the JDK agrees. Pinned so a future phase cannot change what the row measures
     * without this test saying so.
     */
    @Test
    void kvShapeCapturesTheLeftmostKeyAndTheJdkAgrees() {
        final byte[] data = EndAnchoredSearchBenchmark.Shape.KV_HIT.data();
        final ByteMatcher ours = BytePattern
                .compile(EndAnchoredSearchBenchmark.Shape.KV_HIT.pattern())
                .matcher();
        assertThat(ours.match(data, 0, data.length, Anchoring.UNANCHORED)).isTrue();

        final Matcher java = Pattern
                .compile(EndAnchoredSearchBenchmark.Shape.KV_HIT.pattern())
                .matcher(new String(data, StandardCharsets.UTF_8));
        assertThat(java.find()).isTrue();
        assertThat(new String(ours.groupBytes(1), StandardCharsets.UTF_8))
                .isEqualTo(java.group(1));
    }
}
