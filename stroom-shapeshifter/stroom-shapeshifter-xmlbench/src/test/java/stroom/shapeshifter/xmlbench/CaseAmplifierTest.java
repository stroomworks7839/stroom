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
import stroom.shapeshifter.engine.config.ProjectReader;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parity gate for the benchmark corpus: every case that {@code CaseCatalogueBenchmark}
 * will amplify must stay byte-identical against live Saxon <em>at amplified scale</em>, not
 * just at its authored size. This is what licenses the benchmark to claim it measures the
 * same job the catalogue proved — if an amplifier assumption is wrong (a unit boundary that
 * splits a group, whitespace that lands differently), it fails here, not silently in a
 * measurement.
 */
class CaseAmplifierTest {

    /** Enough repetition to cross unit-cycle boundaries several times, small enough to be quick. */
    private static final int UNITS = 500;

    @TestFactory
    List<DynamicTest> amplifiedCasesStayByteIdentical() {
        return CaseCorpus.names().stream()
                .map(name -> DynamicTest.dynamicTest(name + " x" + UNITS, () -> run(name)))
                .toList();
    }

    private void run(final String name) throws Exception {
        final byte[] input = CaseCorpus.amplify(name, UNITS);

        final TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
        final Templates templates = factory.newTemplates(new StreamSource(
                new ByteArrayInputStream(CaseCorpus.read(name, "transform.xsl"))));
        final ByteArrayOutputStream incumbent = new ByteArrayOutputStream();
        templates.newTransformer().transform(
                new StreamSource(new ByteArrayInputStream(input)), new StreamResult(incumbent));

        final var compiled = Shapeshifter.compile(ProjectReader.read(new String(
                CaseCorpus.read(name, "challenger.project.json"), StandardCharsets.UTF_8)));
        final ByteArrayOutputStream challenger = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                compiled, new ByteArrayInputStream(input), OutputSink.of(challenger));

        assertThat(messages)
                .as("%s amplified: the challenger must run clean", name)
                .noneMatch(m -> m.severity() == Severity.ERROR || m.severity() == Severity.FATAL);
        assertThat(challenger.toString(StandardCharsets.UTF_8))
                .as("%s amplified: byte-identical output", name)
                .isEqualTo(incumbent.toString(StandardCharsets.UTF_8));
    }
}
