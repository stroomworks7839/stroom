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

import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 21 phase 2a's spike, made as strong as it can be: Stroom's DS3 goldens are Saxon's
 * serialisation of DS3's events, so the same events through {@link XmlByteSink} must give the
 * same bytes — every legacy fixture, byte for byte, including the ones the engine cannot yet
 * reproduce, because this test is about the serialiser and not the engine.
 */
class XmlByteSinkDs3GoldenTest {

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy");
    private static final String DECLARATION = "<?xml version=\"1.1\" encoding=\"UTF-8\"?>\n";

    @TestFactory
    Stream<DynamicTest> legacyGoldens() throws IOException {
        try (Stream<Path> configs = Files.list(LEGACY)) {
            return configs
                    .filter(p -> p.getFileName().toString().endsWith(".ds3.xml"))
                    .map(p -> p.getFileName().toString().replace(".ds3.xml", ""))
                    .sorted()
                    .map(stem -> DynamicTest.dynamicTest(stem, () -> check(stem)))
                    .collect(Collectors.toList())
                    .stream();
        }
    }

    private static void check(final String stem) throws Exception {
        final Path goldenPath = LEGACY.resolve(stem + ".out.xml");
        if (!Files.exists(goldenPath)) {
            assertThat(stem).as("only the must-be-rejected fixture has no golden").isEqualTo("008_invalid_xml_FAIL");
            return;
        }
        final String config = Files.readString(LEGACY.resolve(stem + ".ds3.xml"));
        final byte[] input = Files.readAllBytes(LEGACY.resolve(stem + ".in"));

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final XMLReader ds3 = Ds3Oracle.parser(config);
        ds3.setContentHandler(new SinkHandler(new XmlByteSink(out)));
        ds3.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        ds3.parse(new InputSource(new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8)));

        final String expected = Files.readString(goldenPath);
        final String actual = out.toString(StandardCharsets.UTF_8);
        assertThat(firstDifference(expected, actual)).as(stem + ": bytes versus Stroom's golden").isNull();
    }

    private static String firstDifference(final String expected, final String actual) {
        final List<String> e = expected.lines().toList();
        final List<String> a = actual.lines().toList();
        for (int i = 0; i < Math.max(e.size(), a.size()); i++) {
            final String x = i < e.size() ? e.get(i) : "<end>";
            final String y = i < a.size() ? a.get(i) : "<end>";
            if (!x.equals(y)) {
                return "line " + (i + 1) + "\n  golden: [" + x + "]\n  sink:   [" + y + "]";
            }
        }
        return expected.equals(actual) ? null : "identical lines, different bytes (line endings or final newline)";
    }

    /**
     * SAX events onto the sink, the way phase 3's migration will drive it: prefix mappings arrive
     * before the element they belong to and are held until it opens; DS3's {@code xmlns}
     * attributes are the same declarations again and are dropped.
     */
    private static final class SinkHandler implements ContentHandler {

        private final XmlByteSink sink;
        private final List<String[]> pendingNamespaces = new ArrayList<>();

        private SinkHandler(final XmlByteSink sink) {
            this.sink = sink;
        }

        @Override
        public void startDocument() {
            sink.write(DECLARATION);
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) {
            pendingNamespaces.add(new String[]{prefix, uri});
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes atts) {
            sink.startElement(qName);
            for (final String[] ns : pendingNamespaces) {
                sink.namespace(ns[0], ns[1]);
            }
            pendingNamespaces.clear();
            for (int i = 0; i < atts.getLength(); i++) {
                final String name = atts.getQName(i);
                if (name.equals("xmlns") || name.startsWith("xmlns:")) {
                    continue;
                }
                sink.startAttribute(name);
                sink.write(atts.getValue(i));
                sink.endAttribute();
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            sink.endElement();
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            sink.write(new String(ch, start, length));
        }

        @Override
        public void setDocumentLocator(final Locator locator) {
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void endPrefixMapping(final String prefix) {
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length) {
        }

        @Override
        public void processingInstruction(final String target, final String data) {
        }

        @Override
        public void skippedEntity(final String name) {
        }
    }
}
