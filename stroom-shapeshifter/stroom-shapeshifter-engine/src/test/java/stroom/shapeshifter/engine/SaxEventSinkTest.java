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

/** Design 21 phase 2b: the same structure, spent as events. */
class SaxEventSinkTest {

    private final List<String> events = new ArrayList<>();
    private final SaxEventSink sink = new SaxEventSink(new Recorder(events));

    @Test
    void theRootBeginsAndEndsTheDocumentAndDeclarationsScopeToTheirElement() {
        sink.startElement("records", "records:2");
        sink.namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");
        sink.startAttribute("xsi:schemaLocation");
        sink.write("records:2 file://records-v2.0.xsd");
        sink.endAttribute();
        sink.startElement("record");
        sink.endElement();
        sink.endElement();
        assertThat(events).containsExactly(
                "startDocument",
                "startPrefixMapping  -> records:2",
                "startPrefixMapping xsi -> http://www.w3.org/2001/XMLSchema-instance",
                "startElement {records:2}records records "
                + "[{http://www.w3.org/2001/XMLSchema-instance}schemaLocation=records:2 file://records-v2.0.xsd]",
                "startElement {records:2}record record []",
                "endElement {records:2}record",
                "endElement {records:2}records",
                "endPrefixMapping xsi",
                "endPrefixMapping ",
                "endDocument");
        assertThat(sink.position()).isEqualTo(10);
    }

    @Test
    void contentIsCharactersAndAttributeBytesAreTheValue() {
        sink.startElement("a");
        sink.startAttribute("v");
        final byte[] e = "é".getBytes(StandardCharsets.UTF_8);
        sink.write(e, 0, 1);
        sink.write(e, 1, 1);
        sink.endAttribute();
        sink.write("x < y");
        sink.endElement();
        assertThat(events).containsExactly(
                "startDocument", "startElement {}a a [{}v=é]", "characters \"x < y\"", "endElement {}a", "endDocument");
    }

    @Test
    void whitespaceInsideTextIsKeptAndWhitespaceBeforeAChildIsNot() {
        sink.startElement("d");
        sink.write("a");
        sink.write(" ");
        sink.write("b");
        sink.write("\n   ");
        sink.startElement("e");
        sink.endElement();
        sink.endElement();
        assertThat(events).containsExactly(
                "startDocument", "startElement {}d d []", "characters \"a\"", "characters \" \"",
                "characters \"b\"", "startElement {}e e []", "endElement {}e", "endElement {}d", "endDocument");
    }

    @Test
    void anUnprefixedAttributeIsInNoNamespaceEvenUnderADefaultNamespace() {
        sink.startElement("a", "urn:a");
        sink.startAttribute("plain");
        sink.write("1");
        sink.endAttribute();
        sink.endElement();
        assertThat(events).contains("startElement {urn:a}a a [{}plain=1]");
    }

    @Test
    void documentLevelWhitespaceIsDroppedAndOtherTextIsRefused() {
        sink.write("\n   ");
        assertThat(events).isEmpty();
        assertThatThrownBy(() -> sink.write("<records>"))
                .isInstanceOf(OutputSink.StructureException.class)
                .hasMessageContaining("outside any element");
    }

    @Test
    void anUnboundPrefixIsRefusedWhenTheElementIsEmitted() {
        sink.startElement("p:a");
        assertThatThrownBy(sink::endElement)
                .isInstanceOf(OutputSink.StructureException.class)
                .hasMessageContaining("prefix 'p'");
    }

    @Test
    void anAttributeAfterContentIsRefusedByName() {
        sink.startElement("a");
        sink.write("text");
        assertThatThrownBy(() -> sink.startAttribute("late"))
                .isInstanceOf(OutputSink.StructureException.class)
                .hasMessageContaining("'late'");
    }

    /** One line per event, in the terms the pipeline module's recorder uses. */
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
            into.add("startPrefixMapping " + prefix + " -> " + uri);
        }

        @Override
        public void endPrefixMapping(final String prefix) {
            into.add("endPrefixMapping " + prefix);
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes atts) {
            final List<String> attributes = new ArrayList<>();
            for (int i = 0; i < atts.getLength(); i++) {
                attributes.add("{" + atts.getURI(i) + "}" + atts.getLocalName(i) + "=" + atts.getValue(i));
            }
            into.add("startElement {" + uri + "}" + localName + " " + qName + " " + attributes);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            into.add("endElement {" + uri + "}" + localName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            into.add("characters \"" + new String(ch, start, length) + "\"");
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
