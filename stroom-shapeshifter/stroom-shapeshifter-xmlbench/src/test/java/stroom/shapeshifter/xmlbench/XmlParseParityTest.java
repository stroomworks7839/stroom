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

import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.output.SaxEventSink;

import com.ctc.wstx.stax.WstxInputFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parse head-to-head's parity gate (design 40): the three parsers, from bytes and from a
 * string, deliver the same events for the same document — every element with its namespace,
 * every attribute with its decoded value, every run of text. Whitespace-only text between
 * elements is set aside, since the engine's configuration writes structure and not the
 * indentation between it, and adjacent text is coalesced, since the parsers chunk it as they
 * please.
 */
class XmlParseParityTest {

    /** One event per line: {@code E uri local (attr=value)*}, {@code T text}, {@code /local}. */
    static List<String> saxEvents(final byte[] bytes, final String text) throws Exception {
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final Recording handler = new Recording();
        final InputSource source = bytes != null
                ? new InputSource(new ByteArrayInputStream(bytes))
                : new InputSource(new StringReader(text));
        factory.newSAXParser().parse(source, handler);
        return handler.done();
    }

    static List<String> staxEvents(final byte[] bytes, final String text) throws Exception {
        final WstxInputFactory factory = new WstxInputFactory();
        final XMLStreamReader reader = bytes != null
                ? factory.createXMLStreamReader(new ByteArrayInputStream(bytes))
                : factory.createXMLStreamReader(new StringReader(text));
        final Recording handler = new Recording();
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    final StringBuilder line = new StringBuilder("E ").append(reader.getNamespaceURI()).append(' ')
                            .append(reader.getLocalName());
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        line.append(' ').append(reader.getAttributeLocalName(i)).append('=')
                                .append(reader.getAttributeValue(i));
                    }
                    handler.element(line.toString());
                }
                case XMLStreamConstants.CHARACTERS -> handler.text(reader.getText());
                case XMLStreamConstants.END_ELEMENT -> handler.end(reader.getLocalName());
                default -> {
                }
            }
        }
        return handler.done();
    }

    static List<String> shapeshifterEvents(final CompiledProject compiled, final byte[] bytes) {
        final Recording handler = new Recording();
        Shapeshifter.run(compiled, new ByteArrayInputStream(bytes), new SaxEventSink(handler));
        return handler.done();
    }

    static CompiledProject compileParser() throws Exception {
        try (var config = XmlParseParityTest.class.getResourceAsStream("/xmlbench/parse.project.json")) {
            return Shapeshifter.compile(ProjectReader.read(new String(config.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Test
    void theThreeParsersDeliverTheSameEventsFromBytesAndFromAString() throws Exception {
        final byte[] bytes = RecordsGenerator.generate(1000);
        final String text = new String(bytes, StandardCharsets.UTF_8);
        final List<String> xerces = saxEvents(bytes, null);
        assertThat(xerces).hasSizeGreaterThan(1000 * 7 * 2);
        assertThat(xerces.getFirst()).isEqualTo("E records:2 records version=2.0");
        assertThat(xerces).anySatisfy(line -> assertThat(line).contains("value=Message 1 from run & batch <"));

        assertThat(saxEvents(null, text)).as("Xerces from a string").isEqualTo(xerces);
        assertThat(staxEvents(bytes, null)).as("Woodstox from bytes").isEqualTo(xerces);
        assertThat(staxEvents(null, text)).as("Woodstox from a string").isEqualTo(xerces);
        final CompiledProject parser = compileParser();
        assertThat(shapeshifterEvents(parser, bytes)).as("Shapeshifter from bytes").isEqualTo(xerces);
        assertThat(shapeshifterEvents(parser, text.getBytes(StandardCharsets.UTF_8)))
                .as("Shapeshifter from a string, encoded").isEqualTo(xerces);
    }

    /** Records events as lines, coalescing text and dropping whitespace-only runs. */
    static final class Recording extends DefaultHandler {

        private final List<String> lines = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        void element(final String line) {
            flush();
            lines.add(line);
        }

        void text(final String chars) {
            text.append(chars);
        }

        void end(final String local) {
            flush();
            lines.add("/" + local);
        }

        private void flush() {
            if (!text.isEmpty()) {
                if (!text.toString().isBlank()) {
                    lines.add("T " + text);
                }
                text.setLength(0);
            }
        }

        List<String> done() {
            flush();
            return lines;
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes attributes) {
            final StringBuilder line = new StringBuilder("E ").append(uri).append(' ').append(localName);
            for (int i = 0; i < attributes.getLength(); i++) {
                line.append(' ').append(attributes.getLocalName(i)).append('=').append(attributes.getValue(i));
            }
            element(line.toString());
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            text(new String(ch, start, length));
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            end(localName);
        }
    }
}
