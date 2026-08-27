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

package stroom.shapeshifter.xmlbench;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.config.ProjectReader;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parity gate (design/13, Phase 3): the challenger's output must be byte-identical to
 * the incumbent's before any timing happens — at ten records against the audited golden,
 * and at a hundred thousand against Saxon run live. Ordinary template emission only; no
 * special writer.
 */
class ChallengerParityTest {

    static CompiledProject compile() throws Exception {
        try (var config = ChallengerParityTest.class
                .getResourceAsStream("/xmlbench/challenger.project.json")) {
            return Shapeshifter.compile(ProjectReader.read(
                    new String(config.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    static byte[] transform(final CompiledProject compiled, final byte[] input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 2);
        final List<Message> messages = Shapeshifter.run(
                compiled, new ByteArrayInputStream(input), OutputSink.of(out));
        assertThat(messages)
                .as("the challenger must run clean — every byte explicitly consumed")
                .noneMatch(m -> m.severity() == Severity.ERROR || m.severity() == Severity.FATAL);
        return out.toByteArray();
    }

    @Test
    void tenRecordsMatchTheGoldenByteForByte() throws Exception {
        final byte[] output = transform(compile(), RecordsGenerator.generate(10));
        final byte[] golden = getClass()
                .getResourceAsStream("/xmlbench/ten-records-golden.xml").readAllBytes();
        assertThat(new String(output, StandardCharsets.UTF_8))
                .isEqualTo(new String(golden, StandardCharsets.UTF_8));
    }

    @Test
    void hundredThousandRecordsMatchSaxonByteForByte() throws Exception {
        final byte[] input = RecordsGenerator.generate(100_000);
        final byte[] challenger = transform(compile(), input);
        final byte[] incumbent = XsltBaselineTest.transform(XsltBaselineTest.compile(), input);
        assertThat(challenger).isEqualTo(incumbent);
    }
}
