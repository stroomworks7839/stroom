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

import stroom.pipeline.xml.converter.json.JSONParser;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;

import org.xml.sax.InputSource;

import java.io.StringReader;

/**
 * {@code json-to-xml}: as {@code stroom.pipeline.xsltfunctions.JsonToXml}, through Stroom's own
 * {@link JSONParser}; the document as indented text (design 26 ruling 4).
 */
public final class JsonToXmlFunction extends StroomFunction {

    public JsonToXmlFunction() {
        super("json-to-xml", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String json = requiredString(context, arguments, 0);
            if (json == null || json.isEmpty()) {
                return null;
            }
            try {
                final XmlText xml = new XmlText();
                final JSONParser parser = new JSONParser(false);
                parser.setContentHandler(xml.handler());
                parser.parse(new InputSource(new StringReader(json)));
                return TypedValue.of(xml.text());
            } catch (final Exception e) {
                context.error("Error parsing JSON - " + e.getMessage());
                return null;
            }
        };
    }
}
