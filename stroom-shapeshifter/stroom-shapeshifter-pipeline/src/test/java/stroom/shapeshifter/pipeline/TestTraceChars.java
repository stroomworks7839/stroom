/*
 * Copyright 2026 Crown Copyright
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

import stroom.shapeshifter.engine.TraceRecorder;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// The trace's text as the client is shown it (design 46 §6.5)
class TestTraceChars {

    @Test
    void outputThatIsNoTextIsShownAByteToACharacter() {
        // Output that is not UTF-8 shown as UTF-8 would be replacement characters, one for every
        // bad byte, while its offsets counted a continuation byte as no character at all
        final byte[] output = {'<', 'a', '>', (byte) 0x80, (byte) 0x80};
        final TraceChars chars = new TraceChars(new TraceRecorder(), new byte[0], ShownText.UTF_8, output);

        assertThat(chars.output()).isEqualTo("<a>\u0080\u0080");
    }

    @Test
    void outputThatIsTextIsShownAsUtf8() {
        final byte[] output = "<a>é</a>".getBytes(StandardCharsets.UTF_8);
        final TraceChars chars = new TraceChars(new TraceRecorder(), new byte[0], ShownText.UTF_8, output);

        assertThat(chars.output()).isEqualTo("<a>é</a>");
    }

    @Test
    void theInputIsShownAsItsProjectSays() {
        final byte[] input = "ab".getBytes(StandardCharsets.UTF_16LE);
        final TraceChars chars = new TraceChars(new TraceRecorder(), input, ShownText.input(input, "utf-16le"),
                new byte[0]);

        assertThat(chars.input()).isEqualTo("ab");
    }
}
