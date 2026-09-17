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

import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.output.SaxEventSink;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON parse head-to-head's parity gate (design 40 §5): Jackson's token stream, from
 * bytes and from a string, and the engine's field events for the same JSON lines are the
 * same records — each object's fields in order, names and values, strings decoded and
 * numbers as their text.
 */
class JsonParseParityTest {

    /** One line per record: {@code name=value;name=value;...}. */
    static List<String> jacksonRecords(final byte[] bytes, final String text) throws Exception {
        final JsonFactory factory = JsonFactory.builder().build();
        final List<String> records = new ArrayList<>();
        try (JsonParser parser = bytes != null ? factory.createParser(bytes) : factory.createParser(text)) {
            StringBuilder record = null;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                switch (token) {
                    case START_OBJECT -> record = new StringBuilder();
                    case END_OBJECT -> records.add(record.toString());
                    case PROPERTY_NAME -> record.append(parser.currentName()).append('=');
                    case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE, VALUE_NULL ->
                            record.append(parser.getString()).append(';');
                    default -> {
                    }
                }
            }
        }
        return records;
    }

    static List<String> shapeshifterRecords(final CompiledProject compiled, final byte[] bytes) {
        final Recording handler = new Recording();
        Shapeshifter.run(compiled, new ByteArrayInputStream(bytes), new SaxEventSink(handler));
        return handler.records;
    }

    static CompiledProject compileParser() throws Exception {
        try (var config = JsonParseParityTest.class.getResourceAsStream("/xmlbench/json-parse.project.json")) {
            return Shapeshifter.compile(ProjectReader.read(new String(config.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Test
    void jacksonAndTheEngineDeliverTheSameRecordsFromBytesAndFromAString() throws Exception {
        final byte[] bytes = JsonRecordsGenerator.generate(1000);
        final String text = new String(bytes, StandardCharsets.UTF_8);
        final List<String> jackson = jacksonRecords(bytes, null);
        assertThat(jackson).hasSize(1000);
        assertThat(jackson.getFirst()).startsWith("date=2026-08-21;time=00:00:01;fileNo=2;lineNo=1;user=user1694;")
                .contains("message=Message 1 from run \"batch\" 1400\\n\ndone;");
        assertThat(jacksonRecords(null, text)).as("Jackson from a string").isEqualTo(jackson);
        final CompiledProject parser = compileParser();
        assertThat(shapeshifterRecords(parser, bytes)).as("Shapeshifter from bytes").isEqualTo(jackson);
        assertThat(shapeshifterRecords(parser, text.getBytes(StandardCharsets.UTF_8)))
                .as("Shapeshifter from a string, encoded").isEqualTo(jackson);
    }

    /** Turns the engine's record and field elements back into the same lines. */
    static final class Recording extends DefaultHandler {

        final List<String> records = new ArrayList<>();
        private StringBuilder record;

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                                 final Attributes attributes) {
            switch (localName) {
                case "record" -> record = new StringBuilder();
                case "field" -> record.append(attributes.getValue("name")).append('=')
                        .append(attributes.getValue("value")).append(';');
                default -> {
                }
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            if (localName.equals("record")) {
                records.add(record.toString());
            }
        }
    }
}
