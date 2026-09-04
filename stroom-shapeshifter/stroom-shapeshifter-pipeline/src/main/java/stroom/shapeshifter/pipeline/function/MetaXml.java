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

package stroom.shapeshifter.pipeline.function;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.util.Map;
import java.util.TreeMap;

/**
 * The document the meta functions build, as {@code StroomExtensionMetaFunctionCall} builds it:
 * an element in the {@code stroom-meta} namespace holding one {@code string} element per entry,
 * keyed by attribute, sorted by key; and the {@code source} element's numbered parts. As text.
 */
final class MetaXml {

    static final String URI = "stroom-meta";

    private MetaXml() {
    }

    static String entries(final String elementName, final Map<String, String> entries) throws SAXException {
        final XmlText xml = new XmlText();
        final ContentHandler handler = xml.handler();
        handler.startDocument();
        handler.startPrefixMapping("", URI);
        handler.startElement(URI, elementName, elementName, new AttributesImpl());
        for (final Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
            final AttributesImpl attributes = new AttributesImpl();
            attributes.addAttribute(URI, "key", "key", "string", entry.getKey());
            handler.startElement(URI, "string", "string", attributes);
            characters(handler, String.valueOf(entry.getValue()));
            handler.endElement(URI, "string", "string");
        }
        handler.endElement(URI, elementName, elementName);
        handler.endPrefixMapping("");
        handler.endDocument();
        return xml.text();
    }

    static void data(final ContentHandler handler, final String name, final long value) throws SAXException {
        handler.startElement(URI, name, name, new AttributesImpl());
        characters(handler, String.valueOf(value));
        handler.endElement(URI, name, name);
    }

    private static void characters(final ContentHandler handler, final String text) throws SAXException {
        final char[] chars = text.toCharArray();
        handler.characters(chars, 0, chars.length);
    }
}
