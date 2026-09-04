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

import stroom.shapeshifter.engine.XmlByteSink;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The byte image of an event stream (design 22 §1, I1): the events written through
 * {@link XmlByteSink}, so that what the engine matches is what an indenting {@code XMLWriter}
 * would have written at the same point in the pipeline.
 *
 * <p>Prefix mappings arrive before their element and are held until it opens; {@code xmlns}
 * attributes, which some upstreams report as well, are the same declarations again and are
 * dropped. Processing instructions and skipped entities have no place in the image. With
 * {@code preserveWhitespace} the sink adds no indentation and drops no whitespace.
 */
final class EventImage implements ContentHandler {

    private static final String DECLARATION = "<?xml version=\"1.1\" encoding=\"UTF-8\"?>\n";

    private final XmlByteSink sink;
    private final List<String[]> pendingNamespaces = new ArrayList<>();

    EventImage(final OutputStream into) {
        this(into, false);
    }

    /**
     * @param preserveWhitespace the faithful image (design 22 phase 3): every character kept as
     *                           it came and no indentation added, for a document whose whitespace
     *                           is its own
     */
    EventImage(final OutputStream into, final boolean preserveWhitespace) {
        this.sink = new XmlByteSink(
                into, preserveWhitespace ? XmlByteSink.Layout.FAITHFUL : XmlByteSink.Layout.INDENTED);
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
        for (final String[] namespace : pendingNamespaces) {
            sink.namespace(namespace[0], namespace[1]);
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
    public void ignorableWhitespace(final char[] ch, final int start, final int length) {
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
    public void processingInstruction(final String target, final String data) {
    }

    @Override
    public void skippedEntity(final String name) {
    }
}
