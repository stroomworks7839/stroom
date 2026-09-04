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

package stroom.shapeshifter.engine;

import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design 24: a text configuration's bytes leave as characters events, one per write, whole. */
class CharacterSinkTest {

    private final List<String> events = new ArrayList<>();
    private final CharacterSink sink = new CharacterSink(new Recorder(events));

    @Test
    void eachWriteIsOneCharactersEventAndTheDocumentBracketsThem() {
        sink.write("<r>".getBytes(StandardCharsets.UTF_8));
        sink.write("text".getBytes(StandardCharsets.UTF_8));
        sink.end();
        assertThat(events).containsExactly("startDocument", "characters <r>", "characters text", "endDocument");
        assertThat(sink.position()).isEqualTo(4);
        assertThat(sink.unit()).isEqualTo(OutputSink.Unit.EVENTS);
    }

    @Test
    void characterSplitBetweenWritesArrivesWhole() {
        final byte[] euro = {(byte) 0xE2, (byte) 0x82, (byte) 0xAC};
        sink.write("a".getBytes(StandardCharsets.UTF_8));
        sink.write(euro, 0, 1);
        sink.write(euro, 1, 2);
        sink.write("b".getBytes(StandardCharsets.UTF_8));
        sink.end();
        assertThat(events).containsExactly(
                "startDocument", "characters a", "characters €", "characters b", "endDocument"); // euro sign
    }

    @Test
    void anIncompleteSequenceAtTheEndIsDeliveredAsItDecodesNotDropped() {
        sink.write(new byte[]{'x', (byte) 0xE2, (byte) 0x82});
        assertThat(events).containsExactly("startDocument", "characters x");
        sink.end();
        assertThat(events).containsExactly(
                "startDocument", "characters x", "characters �", "endDocument"); // replacement character
    }

    @Test
    void runWithNoOutputIsStillOneDocument() {
        sink.end();
        sink.end();
        assertThat(events).containsExactly("startDocument", "endDocument");
    }

    @Test
    void structureIsRefused() {
        assertThatThrownBy(() -> sink.startElement("a"))
                .isInstanceOf(OutputSink.StructureException.class)
                .hasMessageContaining("does not carry structure");
        assertThatThrownBy(() -> sink.startAttribute("a")).isInstanceOf(OutputSink.StructureException.class);
    }

    @Test
    void writingAfterTheEndIsRefused() {
        sink.end();
        assertThatThrownBy(() -> sink.write("late".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(OutputSink.StructureException.class)
                .hasMessageContaining("after the document has ended");
    }

    private record Recorder(List<String> into) implements ContentHandler {

        @Override
        public void setDocumentLocator(final Locator locator) {
        }

        @Override
        public void startDocument() {
            into.add("startDocument");
        }

        @Override
        public void endDocument() {
            into.add("endDocument");
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) {
            into.add("startPrefixMapping " + prefix);
        }

        @Override
        public void endPrefixMapping(final String prefix) {
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes atts) {
            into.add("startElement " + qName);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            into.add("endElement " + qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            into.add("characters " + new String(ch, start, length));
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
