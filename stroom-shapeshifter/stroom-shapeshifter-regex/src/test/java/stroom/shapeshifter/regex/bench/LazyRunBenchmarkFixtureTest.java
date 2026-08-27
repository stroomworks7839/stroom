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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every lazy-run shape must hit or miss exactly as its name claims, on all three forced
 * engines and on the JDK — a shape that quietly stopped matching would still produce a score,
 * of scanning and failing, which is a different measurement wearing the same name.
 *
 * <p>One check here is not boilerplate and is the reason the benchmark can be believed at
 * all: the {@code *_DOTALL} and {@code *_LINE} forms of a shape must capture <b>the same
 * bytes</b>. They are two spellings of one question, and the whole claim — that the gap
 * between them is the engine's to close — collapses if they are quietly asking different
 * ones.
 */
class LazyRunBenchmarkFixtureTest {

    /** The engines the benchmark measures: the one naturally selected for these patterns,
     * and the second tier that can also take every shape. The other three cannot run all
     * eight — the benchmark's javadoc records which and why. */
    private static final Engine[] ENGINES = {Engine.SIMULATE, Engine.FANCY};

    @Test
    void everyShapeAnswersAsItsNameClaims() {
        for (final LazyRunBenchmark.Shape shape : LazyRunBenchmark.Shape.values()) {
            final byte[] data = shape.data();
            for (final Engine engine : ENGINES) {
                final ByteMatcher matcher = BytePattern
                        .compileForcing(engine, shape.pattern(), EnumSet.noneOf(Flag.class))
                        .matcher();
                assertThat(matcher.match(data, 0, data.length, Anchoring.ANCHORED))
                        .as("%s on %s", shape, engine)
                        .isEqualTo(shape.matches());
            }
            final Matcher java = Pattern.compile(shape.pattern())
                    .matcher(new String(data, StandardCharsets.UTF_8));
            assertThat(java.lookingAt())
                    .as("%s on the JDK", shape)
                    .isEqualTo(shape.matches());
        }
    }

    /**
     * The two forms of each shape must agree byte for byte on what they captured. Without
     * this the benchmark could report the line form as cheaper simply because it was doing
     * less, and the gap it measures would be an artefact rather than a target.
     */
    @Test
    void theDotallAndLineFormsCaptureTheSameBytes() {
        for (final String prefix : new String[]{"ENTRY", "BATCH", "FAR"}) {
            final LazyRunBenchmark.Shape dotall =
                    LazyRunBenchmark.Shape.valueOf(prefix + "_DOTALL");
            final LazyRunBenchmark.Shape line =
                    LazyRunBenchmark.Shape.valueOf(prefix + "_LINE");
            assertThat(line.data())
                    .as("%s: the two forms must be measured over identical input", prefix)
                    .isEqualTo(dotall.data());
            for (final Engine engine : ENGINES) {
                assertThat(captured(engine, line))
                        .as("%s captured by %s", prefix, engine)
                        .isEqualTo(captured(engine, dotall));
            }
        }
    }

    /** What group 2 — the lazy run itself — captured, as text. */
    private static String captured(final Engine engine, final LazyRunBenchmark.Shape shape) {
        final byte[] data = shape.data();
        final ByteMatcher matcher = BytePattern
                .compileForcing(engine, shape.pattern(), EnumSet.noneOf(Flag.class))
                .matcher();
        assertThat(matcher.match(data, 0, data.length, Anchoring.ANCHORED)).isTrue();
        return new String(data, matcher.start(2), matcher.end(2) - matcher.start(2),
                StandardCharsets.UTF_8);
    }
}
