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

import stroom.pipeline.xsltfunctions.CommonHttpClient;
import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.util.io.StreamUtil;
import stroom.util.jersey.HttpClientProvider;
import stroom.util.jersey.HttpClientProviderCache;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@code http-call}: as {@code stroom.pipeline.xsltfunctions.HttpCall}: a POST to a url with
 * headers ({@code name: value}, one per line), a media type, a body and a client configuration,
 * through Stroom's own {@code CommonHttpClient} and its client cache; the response as a
 * {@code response} element in the {@code stroom-http} namespace, as text (design 26 ruling 4),
 * or an {@code error} element and an ERROR when the call could not be made. Impure.
 */
public final class HttpCallFunction extends StroomFunction {

    static final String URI = "stroom-http";

    public HttpCallFunction() {
        super("http-call",
                Signature.of(1, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING, Kind.STRING),
                Purity.IMPURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new FunctionCall() {
            private CommonHttpClient client;

            @Override
            public TypedValue call(final Arguments arguments) {
                final String url = orEmpty(arguments.string(0));
                final String headers = orEmpty(arguments.string(1));
                final String mediaType = arguments.string(2) == null
                        ? "application/json; charset=utf-8"
                        : arguments.string(2);
                final String data = orEmpty(arguments.string(3));
                final String clientConfig = orEmpty(arguments.string(4));
                if (url.isEmpty()) {
                    context.warn("No URL specified for HTTP call");
                    return null;
                }
                final HttpClientProviderCache cache = context.service(HttpClientProviderCache.class);
                if (cache == null) {
                    context.error("No HTTP client is available to this run");
                    return null;
                }
                if (client == null) {
                    client = new CommonHttpClient(cache);
                }
                try (HttpClientProvider provider = client.createClientProvider(clientConfig)) {
                    final HttpClient httpClient = provider.get();
                    final HttpPost post = new HttpPost(url);
                    if (!data.isEmpty()) {
                        // parse, not create: the media type carries a charset, as the default does,
                        // and create refuses parameters.
                        post.setEntity(new StringEntity(data, ContentType.parse(mediaType)));
                    }
                    for (final String line : headers.split("\n")) {
                        final int colon = line.indexOf(':');
                        if (colon > 0) {
                            post.setHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                        }
                    }
                    return TypedValue.of(httpClient.execute(post, HttpCallFunction::render));
                } catch (final Exception e) {
                    final String message = "Error calling function http-call(): "
                                           + String.valueOf(e.getMessage())
                                                   .replaceAll("(\"[^\"]+Password\"\\s*:\\s*)\"[^\"]+\"",
                                                           "$1\"XXXXXX\"");
                    context.error(message);
                    try {
                        final XmlText xml = new XmlText();
                        final ContentHandler handler = xml.handler();
                        handler.startDocument();
                        handler.startPrefixMapping("", URI);
                        data(handler, "error", message);
                        handler.endPrefixMapping("");
                        handler.endDocument();
                        return TypedValue.of(xml.text());
                    } catch (final SAXException inner) {
                        return null;
                    }
                }
            }
        };
    }

    private static String orEmpty(final String value) {
        return value == null ? "" : value;
    }

    /** The response as the Saxon class builds it: successful, code, message, headers, body. */
    static String render(final ClassicHttpResponse response) throws IOException {
        try {
            final XmlText xml = new XmlText();
            final ContentHandler handler = xml.handler();
            handler.startDocument();
            handler.startPrefixMapping("", URI);
            handler.startElement(URI, "response", "response", new AttributesImpl());
            data(handler, "successful", String.valueOf(response.getCode() == 200));
            data(handler, "code", String.valueOf(response.getCode()));
            data(handler, "message", response.getReasonPhrase());
            if (response.getHeaders() != null && response.getHeaders().length > 0) {
                handler.startElement(URI, "headers", "headers", new AttributesImpl());
                for (final Header header : response.getHeaders()) {
                    handler.startElement(URI, "header", "header", new AttributesImpl());
                    data(handler, "key", header.getName());
                    data(handler, "value", header.getValue());
                    handler.endElement(URI, "header", "header");
                }
                handler.endElement(URI, "headers", "headers");
            }
            final HttpEntity entity = response.getEntity();
            if (entity != null) {
                try (InputStream inputStream = entity.getContent()) {
                    data(handler, "body", StreamUtil.streamToString(inputStream));
                }
            }
            handler.endElement(URI, "response", "response");
            handler.endPrefixMapping("");
            handler.endDocument();
            return xml.text();
        } catch (final SAXException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void data(final ContentHandler handler, final String name, final String value) throws SAXException {
        if (value != null) {
            handler.startElement(URI, name, name, new AttributesImpl());
            final char[] chars = value.toCharArray();
            handler.characters(chars, 0, chars.length);
            handler.endElement(URI, name, name);
        }
    }
}
