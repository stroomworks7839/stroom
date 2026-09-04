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

import stroom.util.xml.XMLUtil;

import java.io.StringWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/**
 * XML fragments as text (design 26 ruling 4): a SAX handler that serialises, indented, with no
 * declaration, the way Stroom's own serialiser writes a document, and what it wrote.
 */
final class XmlText {

    private final StringWriter writer = new StringWriter();
    private final TransformerHandler handler;

    XmlText() {
        try {
            handler = XMLUtil.createTransformerHandler(true);
        } catch (final TransformerConfigurationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        handler.getTransformer().setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        handler.setResult(new StreamResult(writer));
    }

    TransformerHandler handler() {
        return handler;
    }

    /** What has been serialised, without a trailing newline. */
    String text() {
        return writer.toString().stripTrailing();
    }
}
