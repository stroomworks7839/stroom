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

package stroom.shapeshifter.ai.scoring;

import stroom.util.xml.SAXParserFactoryFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Counts the children of an XML document's root element — the records of a {@code records:2} document,
 * the events of an {@code event-logging:3} one. Parsed with the hardened reader every other parse in
 * this module uses; a document that does not parse counts as having no records.
 */
public final class Records {

    private Records() {
    }

    public static int count(final String xml) {
        final Counter counter = new Counter();
        try {
            final XMLReader reader = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
            reader.setContentHandler(counter);
            reader.parse(new InputSource(new StringReader(xml)));
        } catch (final SAXException | IOException | ParserConfigurationException e) {
            return 0;
        }
        return counter.records;
    }

    private static final class Counter extends DefaultHandler {

        private int depth;
        private int records;

        @Override
        public void startElement(final String uri,
                                 final String localName,
                                 final String qName,
                                 final Attributes attributes) {
            depth++;
            if (depth == 2) {
                records++;
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            depth--;
        }
    }
}
