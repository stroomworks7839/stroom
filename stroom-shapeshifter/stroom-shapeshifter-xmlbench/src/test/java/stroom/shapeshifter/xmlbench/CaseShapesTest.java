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

import stroom.shapeshifter.config.Severity;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
 * A case's alternative shapes (design 37 phase 8): the same job as the case's challenger in a
 * different configuration shape, byte-identical against live Saxon at amplified scale, so
 * that {@link CaseShapesBenchmark}'s numbers compare like with like.
 */
class CaseShapesTest {

    @ParameterizedTest
    @CsvSource({
            "keys_lookup, challenger-positions.project.json",
            "keys_lookup, challenger-lists.project.json",
            "keys_lookup, challenger-pairs.project.json",
    })
    void shapeMatchesSaxonByteForByte(final String benchCase, final String shape) throws Exception {
        final byte[] input = CaseCorpus.amplify(benchCase, 2_000);

        final TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
        final Templates templates = factory.newTemplates(new StreamSource(
                new ByteArrayInputStream(CaseCorpus.read(benchCase, "transform.xsl"))));
        final ByteArrayOutputStream incumbent = new ByteArrayOutputStream();
        templates.newTransformer().transform(
                new StreamSource(new ByteArrayInputStream(input)), new StreamResult(incumbent));

        final var compiled = Shapeshifter.compile(ProjectReader.read(
                new String(CaseCorpus.read(benchCase, shape), StandardCharsets.UTF_8)));
        final ByteArrayOutputStream challenger = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                compiled, new ByteArrayInputStream(input), new XmlByteSink(challenger));

        assertThat(messages).as("%s/%s: the shape must run clean", benchCase, shape)
                .noneMatch(m -> m.severity() == Severity.ERROR || m.severity() == Severity.FATAL);
        assertThat(challenger.toString(StandardCharsets.UTF_8)).as("%s/%s: byte-identical output", benchCase, shape)
                .isEqualTo(incumbent.toString(StandardCharsets.UTF_8));
    }
}
