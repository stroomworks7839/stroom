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

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Records a SAX stream as one line per event, in the terms two parsers can be compared in.
 *
 * <p>Three normalisations, each a finding of design 21 phase 1 rather than a convenience, and each
 * recorded there: whitespace-only character data is dropped (DS3 emits no whitespace; the migrated
 * configuration writes the indentation DS3's <i>serialiser</i> wrote); {@code xmlns} attributes are
 * dropped (DS3 puts its two declarations in the root's {@code Attributes} as well as in
 * {@code startPrefixMapping}; a namespace-aware parser reports them only as prefix mappings); and
 * {@code endPrefixMapping} is not recorded (DS3 never calls it). Adjacent character events are
 * merged, since a parser may split what a writer emitted in one piece. Attributes are sorted by
 * name, because SAX does not promise an order.
 */
final class EventRecorder implements ContentHandler {

    private final List<String> events = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    List<String> events() {
        flush();
        return List.copyOf(events);
    }

    private void flush() {
        if (!text.isEmpty()) {
            if (!text.toString().isBlank()) {
                events.add("characters \"" + text + "\"");
            }
            text.setLength(0);
        }
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
    }

    @Override
    public void startDocument() {
        flush();
        events.add("startDocument");
    }

    @Override
    public void endDocument() {
        flush();
        events.add("endDocument");
    }

    @Override
    public void startPrefixMapping(final String prefix, final String uri) {
        flush();
        events.add("startPrefixMapping \"" + prefix + "\" -> " + uri);
    }

    @Override
    public void endPrefixMapping(final String prefix) {
    }

    @Override
    public void startElement(final String uri,
                             final String localName,
                             final String qName,
                             final Attributes atts) {
        flush();
        final SortedSet<String> attributes = new TreeSet<>();
        for (int i = 0; i < atts.getLength(); i++) {
            final String q = atts.getQName(i);
            if (q.equals("xmlns") || q.startsWith("xmlns:")) {
                continue;
            }
            attributes.add("{" + atts.getURI(i) + "}" + atts.getLocalName(i) + "=\"" + atts.getValue(i) + "\"");
        }
        events.add("startElement {" + uri + "}" + localName + " " + attributes);
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) {
        flush();
        events.add("endElement {" + uri + "}" + localName);
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) {
        text.append(ch, start, length);
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) {
    }

    @Override
    public void processingInstruction(final String target, final String data) {
        flush();
        events.add("processingInstruction " + target + " " + data);
    }

    @Override
    public void skippedEntity(final String name) {
        flush();
        events.add("skippedEntity " + name);
    }
}
