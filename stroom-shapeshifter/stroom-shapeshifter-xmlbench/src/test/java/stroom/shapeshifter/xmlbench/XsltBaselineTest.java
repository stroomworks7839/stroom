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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0's wiring spike (design/13): Saxon, the XSLT compiled to {@link Templates} once,
 * ten records through, bytes out — and the exit criterion, output equal to the audited
 * golden.
 */
class XsltBaselineTest {

    static Templates compile() throws Exception {
        final TransformerFactory factory =
                new net.sf.saxon.TransformerFactoryImpl();
        try (var xsl = XsltBaselineTest.class.getResourceAsStream("/xmlbench/events-adapted.xsl")) {
            return factory.newTemplates(new StreamSource(xsl));
        }
    }

    static byte[] transform(final Templates templates, final byte[] input) throws Exception {
        final Transformer transformer = templates.newTransformer();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 3);
        transformer.transform(
                new StreamSource(new ByteArrayInputStream(input)),
                new StreamResult(out));
        return out.toByteArray();
    }

    @Test
    void tenRecordsMatchTheGolden() throws Exception {
        final byte[] output = transform(compile(), RecordsGenerator.generate(10));
        Files.write(Path.of("/tmp/ssport/xmlbench-ten.xml"), output);
        final byte[] golden = getClass()
                .getResourceAsStream("/xmlbench/ten-records-golden.xml").readAllBytes();
        assertThat(new String(output, StandardCharsets.UTF_8))
                .isEqualTo(new String(golden, StandardCharsets.UTF_8));
    }
}
