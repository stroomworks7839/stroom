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

import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.net.URI;

/**
 * {@code parse-uri}: as {@code stroom.pipeline.xsltfunctions.ParseUri}: the URI's parts as
 * elements in the {@code uri} namespace, as indented text (design 26 ruling 4). The Saxon class
 * returns the nine part elements as siblings with no parent; text needs one document, so here
 * they sit inside a {@code uri} element.
 */
public final class ParseUriFunction extends StroomFunction {

    private static final String NAMESPACE = "uri";
    private static final String[] PARTS = {
            "authority", "fragment", "host", "path", "port", "query", "scheme", "schemeSpecificPart", "userInfo"};

    public ParseUriFunction() {
        super("parse-uri", Signature.of(Kind.STRING, Kind.STRING), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final String value = requiredString(context, arguments, 0);
            if (value == null || value.isEmpty()) {
                return null;
            }
            try {
                final URI uri = URI.create(value);
                final String[] values = {
                        uri.getAuthority(), uri.getFragment(), uri.getHost(), uri.getPath(),
                        uri.getPort() != -1 ? String.valueOf(uri.getPort()) : null,
                        uri.getQuery(), uri.getScheme(), uri.getSchemeSpecificPart(), uri.getUserInfo()};
                final XmlText xml = new XmlText();
                final ContentHandler handler = xml.handler();
                handler.startDocument();
                handler.startPrefixMapping("", NAMESPACE);
                handler.startElement(NAMESPACE, NAMESPACE, NAMESPACE, new AttributesImpl());
                for (int i = 0; i < PARTS.length; i++) {
                    handler.startElement(NAMESPACE, PARTS[i], PARTS[i], new AttributesImpl());
                    if (values[i] != null) {
                        final char[] chars = values[i].toCharArray();
                        handler.characters(chars, 0, chars.length);
                    }
                    handler.endElement(NAMESPACE, PARTS[i], PARTS[i]);
                }
                handler.endElement(NAMESPACE, NAMESPACE, NAMESPACE);
                handler.endPrefixMapping("");
                handler.endDocument();
                return TypedValue.of(xml.text());
            } catch (final SAXException | RuntimeException e) {
                context.warn("Problem parsing URI: " + e.getMessage());
                return null;
            }
        };
    }
}
