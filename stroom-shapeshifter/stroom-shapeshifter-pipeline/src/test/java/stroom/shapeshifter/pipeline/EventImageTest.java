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

import stroom.util.xml.SAXParserFactoryFactory;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 22 phase 3: the two images of one document, each pinned as a file. The indented image
 * is what an indenting writer shows and leaves mixed content alone; the faithful image is the
 * document's own whitespace with nothing added, for the document whose whitespace matters.
 */
class EventImageTest {

    private static String image(final boolean preserveWhitespace) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final XMLReader parser = SAXParserFactoryFactory.newInstance().newSAXParser().getXMLReader();
        parser.setContentHandler(new EventImage(out, preserveWhitespace));
        try (InputStream in = EventImageTest.class.getResourceAsStream("/images/mixed.xml")) {
            parser.parse(new InputSource(in));
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String golden(final String name) throws Exception {
        try (InputStream in = EventImageTest.class.getResourceAsStream("/images/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theIndentedImageIndentsElementContentAndLeavesMixedContentAlone() throws Exception {
        assertThat(image(false)).isEqualTo(golden("mixed.indented.xml"));
    }

    @Test
    void theFaithfulImageIsTheDocumentsOwnWhitespace() throws Exception {
        assertThat(image(true)).isEqualTo(golden("mixed.faithful.xml"));
    }
}
