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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Streaming behaviour, and the invariant that matters most:
 * <p>
 * <b>For any input and any chunking of that input, the results are identical to matching the
 * whole thing at once.</b>
 * <p>
 * That is checked by replaying every case one byte at a time, at random split points, and
 * whole, and comparing. Any place the engine guesses at a buffer edge shows up here as a
 * difference — most often as a short match, which is precisely the bug
 * {@link MatchOutcome#NEED_MORE_INPUT} exists to prevent.
 */
class StreamingTest {

    private static final List<String> PATTERNS = List.of(
            "^([^,]+),([^,]+)$",
            "^(\\w+)=(\\w+);",
            "([0-9]{4})-([0-9]{2})-([0-9]{2})",
            "(ERROR|WARN|INFO) ([^\\n]*)",
            "\\[([^\\]]*)\\]",
            "^(a+)b",
            "(x|xy)z",
            "^(.+):(.+)$");

    private static final List<String> INPUTS = List.of(
            "",
            "one,two",
            "key=value;rest",
            "2026-08-17",
            "ERROR something failed",
            "[bracketed]",
            "aaab",
            "xyz",
            "a:b:c",
            "no match at all",
            "prefix [one] middle [two] suffix");

    // -----------------------------------------------------------------------------------
    // Outcome semantics
    // -----------------------------------------------------------------------------------

    @Test
    void asksForMoreRatherThanReturningAShortMatch() {
        // The delimiter has not arrived yet. Reporting "one" here would truncate the field.
        final ByteMatcher matcher = BytePattern.compile("^([^,]+),").matcher();
        assertThat(matcher.match(partial("one"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NEED_MORE_INPUT);

        assertThat(matcher.match(partial("one,"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.MATCH);
        assertThat(matcher.groupString(1)).isEqualTo("one");
    }

    @Test
    void greedyScanAtTheEdgeIsUndetermined() {
        // The scan could continue if more letters follow, so the match is not yet decided.
        final ByteMatcher matcher = BytePattern.compile("[a-z]+").matcher();
        assertThat(matcher.match(partial("abc"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NEED_MORE_INPUT);

        // Complete: the same bytes now decide.
        assertThat(matcher.match(ByteWindow.of("abc"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.MATCH);
        assertThat(matcher.groupString(0)).isEqualTo("abc");
    }

    @Test
    void definiteMismatchIsNotDeferred() {
        // Nothing that arrives later can make this match, so waiting would be wrong.
        final ByteMatcher matcher = BytePattern.compile("^abc").matcher();
        assertThat(matcher.match(partial("axc"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NO_MATCH);
    }

    @Test
    void endAnchorsAreUndeterminedAtTheEdge() {
        // Whether this is the end of input is not yet knowable.
        final ByteMatcher matcher = BytePattern.compile("^ab$").matcher();
        assertThat(matcher.match(partial("ab"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NEED_MORE_INPUT);
        assertThat(matcher.match(ByteWindow.of("ab"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.MATCH);
    }

    @Test
    void characterSplitAcrossTheEdgeIsUndetermined() {
        // 'é' is two bytes; only the first has arrived.
        final byte[] full = "é".getBytes(StandardCharsets.UTF_8);
        final ByteMatcher matcher = BytePattern.compile("^.").matcher();
        assertThat(matcher.match(ByteWindow.partial(full, 0, 1), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NEED_MORE_INPUT);
        assertThat(matcher.match(ByteWindow.complete(full), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.MATCH);
    }

    @Test
    void tierOneAlsoReportsUndetermined() {
        final BytePattern pattern = BytePattern.compile("^(.+):(.+)$");
        assertThat(pattern.tier()).isEqualTo(1);
        final ByteMatcher matcher = pattern.matcher();

        assertThat(matcher.match(partial("a:b"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.NEED_MORE_INPUT);
        assertThat(matcher.match(ByteWindow.of("a:b"), 0, Anchoring.ANCHORED))
                .isEqualTo(MatchOutcome.MATCH);
    }

    // -----------------------------------------------------------------------------------
    // The chunking invariant
    // -----------------------------------------------------------------------------------

    @Test
    void everyChunkingAgreesWithTheWholeInput() {
        for (final String pattern : PATTERNS) {
            final BytePattern compiled = BytePattern.compile(pattern);
            for (final String input : INPUTS) {
                final List<String> whole = matchesOf(compiled, input, input.length() + 1);
                for (int chunk = 1; chunk <= input.length() + 1; chunk++) {
                    assertThat(matchesOf(compiled, input, chunk))
                            .as("pattern=%s input=\"%s\" chunk=%d", pattern, input, chunk)
                            .isEqualTo(whole);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {41, 42, 43, 44})
    void randomChunkingAgreesWithTheWholeInput(final int seed) {
        final Random random = new Random(seed);
        for (final String pattern : PATTERNS) {
            final BytePattern compiled = BytePattern.compile(pattern);
            for (int i = 0; i < 60; i++) {
                final String input = randomInput(random);
                final List<String> whole = matchesOf(compiled, input, Integer.MAX_VALUE);
                for (int attempt = 0; attempt < 3; attempt++) {
                    final int chunk = 1 + random.nextInt(6);
                    assertThat(matchesOf(compiled, input, chunk))
                            .as("pattern=%s input=\"%s\" chunk=%d", pattern, input, chunk)
                            .isEqualTo(whole);
                }
            }
        }
    }

    /** Runs the whole input through a stream that yields at most {@code chunk} bytes per read. */
    private static List<String> matchesOf(final BytePattern pattern,
                                          final String input,
                                          final int chunk) {
        final List<String> found = new ArrayList<>();
        try (StreamMatcher matcher = new StreamMatcher(
                pattern, new ChunkedStream(input.getBytes(StandardCharsets.UTF_8), chunk),
                4, 1 << 16)) {
            while (matcher.findNext()) {
                found.add(matcher.groupString(0));
            }
        }
        return found;
    }

    // -----------------------------------------------------------------------------------
    // The stream driver
    // -----------------------------------------------------------------------------------

    @Test
    void iteratesRecordsFromAStream() {
        final String text = """
                2026-08-17 ERROR disk full
                2026-08-18 WARN disk filling
                2026-08-19 INFO disk fine
                """;
        final BytePattern pattern = BytePattern.compile("([0-9-]+) (ERROR|WARN|INFO) ([^\\n]*)");

        final List<String> levels = new ArrayList<>();
        try (StreamMatcher matcher = new StreamMatcher(
                pattern, new ChunkedStream(text.getBytes(StandardCharsets.UTF_8), 3), 8, 1 << 16)) {
            while (matcher.findNext()) {
                levels.add(matcher.groupString(2));
            }
        }
        assertThat(levels).containsExactly("ERROR", "WARN", "INFO");
    }

    @Test
    void growsTheWindowToHoldALongMatch() {
        // The initial window is far smaller than the record, so this only works if the window
        // grows on NEED_MORE_INPUT rather than giving up.
        final String text = "start" + "x".repeat(5000) + "end,";
        final BytePattern pattern = BytePattern.compile("^([^,]+),");

        try (StreamMatcher matcher = new StreamMatcher(
                pattern, new ChunkedStream(text.getBytes(StandardCharsets.UTF_8), 7), 8, 1 << 16)) {
            assertThat(matcher.findNext()).isTrue();
            assertThat(matcher.groupString(1)).hasSize(5008);
        }
    }

    @Test
    void refusesToBufferWithoutLimit() {
        // A delimiter that never arrives must fail with a diagnostic rather than consume memory
        // until something else breaks.
        final String text = "x".repeat(10_000);
        final BytePattern pattern = BytePattern.compile("^([^,]+),");

        assertThatThrownBy(() -> {
            try (StreamMatcher matcher = new StreamMatcher(
                    pattern, new ChunkedStream(text.getBytes(StandardCharsets.UTF_8), 64), 8, 1024)) {
                matcher.findNext();
            }
        })
                .isInstanceOf(StreamMatcher.WindowOverflowException.class)
                .hasMessageContaining("1024 bytes");
    }

    @Test
    void reportsAbsoluteStreamOffsets() {
        final String text = "aaa[one]bbb[two]";
        final BytePattern pattern = BytePattern.compile("\\[([^\\]]*)\\]");

        final List<Long> offsets = new ArrayList<>();
        try (StreamMatcher matcher = new StreamMatcher(
                pattern, new ChunkedStream(text.getBytes(StandardCharsets.UTF_8), 2), 4, 1 << 16)) {
            while (matcher.findNext()) {
                offsets.add(matcher.matchStart());
            }
        }
        // Offsets are against the stream, not against a buffer that keeps being compacted.
        assertThat(offsets).containsExactly(3L, 11L);
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private static ByteWindow partial(final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return ByteWindow.partial(bytes, 0, bytes.length);
    }

    private static String randomInput(final Random random) {
        final char[] alphabet = "abxyz01,;:=[]- \nERINFOW".toCharArray();
        final int length = random.nextInt(20);
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    /** Hands out at most {@code chunk} bytes per read, so the matcher meets real partial windows. */
    private static final class ChunkedStream extends InputStream {

        private final byte[] data;
        private final int chunk;
        private int pos;

        ChunkedStream(final byte[] data, final int chunk) {
            this.data = data;
            this.chunk = Math.max(1, chunk);
        }

        @Override
        public int read() {
            return pos < data.length
                    ? data[pos++] & 0xFF
                    : -1;
        }

        @Override
        public int read(final byte[] target, final int off, final int len) throws IOException {
            if (pos >= data.length) {
                return -1;
            }
            final int count = Math.min(Math.min(len, chunk), data.length - pos);
            System.arraycopy(data, pos, target, off, count);
            pos += count;
            return count;
        }
    }
}
