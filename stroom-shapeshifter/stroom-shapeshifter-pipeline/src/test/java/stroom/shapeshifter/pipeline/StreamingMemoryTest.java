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

package stroom.shapeshifter.pipeline;

import stroom.shapeshifter.engine.OutputSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 23's first property — memory is the window, not the stream — for the trace that rides
 * along with a streamed run (phase 1 audit).
 */
class StreamingMemoryTest {

    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID CHILD = UUID.randomUUID();

    @Test
    void openMatchesFallBackToTheEnclosingOneOnTheEventPathAndDoNotAccumulate() {
        final InputLocations locations = new InputLocations();
        locations.onMatch(ROOT, "root", 100, 50, 1, 0);
        locations.onMatch(CHILD, "child", 120, 10, 1, 1);
        assertThat(locations.currentInputOffset()).isEqualTo(120);
        locations.onOutput(CHILD, 1, 0, 3, OutputSink.Unit.EVENTS);
        // The parent's later events belong to the parent, not to the child that just closed.
        assertThat(locations.currentInputOffset()).isEqualTo(100);
        for (int i = 2; i <= 10_000; i++) {
            locations.onMatch(CHILD, "child", 120L + i, 10, i, 1);
            locations.onOutput(CHILD, i, 0, 3, OutputSink.Unit.EVENTS);
        }
        assertThat(locations.openMatches()).isEqualTo(1);
        locations.onOutput(ROOT, 1, 0, 30_000, OutputSink.Unit.EVENTS);
        assertThat(locations.openMatches()).isZero();
    }

    @Test
    void theLineIndexForgetsWhatNoOpenMatchCanReachAndStillCountsLines() throws Exception {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            text.append("line").append(i).append('\n');
        }
        final byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        final InputLocations.LineIndex index = new InputLocations.LineIndex(new ByteArrayInputStream(bytes));
        assertThat(index.readAllBytes()).hasSize(bytes.length);
        assertThat(index.held()).isEqualTo(10_001);

        // The offset of "line9990" is what the outermost open match could still refer to.
        final long floor = text.indexOf("line9990");
        index.forget(floor);
        assertThat(index.held()).isLessThan(20);
        assertThat(index.locate(floor).line()).isEqualTo(9991);
        assertThat(index.locate(text.indexOf("line9999")).line()).isEqualTo(10_000);
        // Forgetting up to the last line keeps that line, since a match may still begin in it.
        index.forget(text.indexOf("line9999"));
        assertThat(index.held()).isEqualTo(2);
    }

    @Test
    void surrogatePairSplitAcrossAChunkBoundaryIsEncodedWhole() throws Exception {
        // 4096 characters per chunk: 4095 filler characters put the pair's high half at the end
        // of the first chunk and its low half at the start of the next.
        final String text = "a".repeat(4095) + "\uD800\uDF48" + "b"; // U+10348, four bytes in UTF-8
        final ShapeshifterReader.ReaderBytes bytes = new ShapeshifterReader.ReaderBytes(new StringReader(text));
        final String decoded = new String(bytes.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(text);
        assertThat(decoded).doesNotContain("\uFFFD"); // U+FFFD, the replacement character
    }
}
