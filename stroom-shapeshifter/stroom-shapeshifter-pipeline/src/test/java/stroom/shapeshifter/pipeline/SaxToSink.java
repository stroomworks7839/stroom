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

import stroom.shapeshifter.engine.output.XmlByteSink;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;

import java.util.ArrayList;
import java.util.List;

/**
 * SAX events onto {@link XmlByteSink}, the way design 21 phase 3's migration drives it: prefix
 * mappings arrive before the element they belong to and are held until it opens; DS3's
 * {@code xmlns} attributes are the same declarations again and are dropped.
 *
 * <p>One copy, because two would be two answers to the same question. The golden test uses it to
 * prove DS3's events serialise to Stroom's own bytes; the head-to-head benchmark uses it so both
 * engines end in the same serialiser, and what it measures is the parse rather than the writing.
 */
public final class SaxToSink implements ContentHandler {

    public static final String DECLARATION = "<?xml version=\"1.1\" encoding=\"UTF-8\"?>\n";

    private final XmlByteSink sink;
    private final List<String[]> pendingNamespaces = new ArrayList<>();

    public SaxToSink(final XmlByteSink sink) {
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
