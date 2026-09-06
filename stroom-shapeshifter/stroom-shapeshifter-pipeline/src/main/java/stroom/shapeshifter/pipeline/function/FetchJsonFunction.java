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
import stroom.pipeline.xsltfunctions.CommonHttpClient;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.util.jersey.HttpClientProvider;
import stroom.util.jersey.HttpClientProviderCache;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;

/**
 * {@code fetch-json}: as {@code stroom.pipeline.xsltfunctions.FetchJson}: a GET of a url whose
 * JSON body becomes XML through Stroom's {@code JSONParser}, as indented text (design 26 ruling
 * 4); absent for a 404; an ERROR for any other failure, where the Saxon class says nothing.
 * Impure.
 */
public final class FetchJsonFunction extends StroomFunction {

    public FetchJsonFunction() {
        super("fetch-json", Signature.of(1, Kind.STRING, Kind.STRING, Kind.STRING), Purity.IMPURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new FunctionCall() {
            private CommonHttpClient client;

            @Override
            public TypedValue call(final Arguments arguments) {
                final String url = arguments.string(0) == null ? "" : arguments.string(0);
                final String clientConfig = arguments.string(1) == null ? "" : arguments.string(1);
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
                    final String xml = httpClient.execute(new HttpGet(url), response -> {
                        switch (response.getCode()) {
                            case 200 -> {
                                try (BufferedReader in = new BufferedReader(
                                        new InputStreamReader(response.getEntity().getContent()))) {
                                    final String json = in.lines().reduce("", String::concat);
                                    final XmlText text = new XmlText();
                                    final JSONParser parser = new JSONParser(false);
                                    parser.setContentHandler(text.handler());
                                    parser.parse(new InputSource(new StringReader(json)));
                                    return text.text();
                                } catch (final Exception e) {
                                    throw new IllegalStateException("Could not make request to Annotations Service: "
                                                                    + e.getLocalizedMessage());
                                }
                            }
                            case 404 -> {
                                return null;
                            }
                            default -> throw new IllegalStateException(
                                    "Could not make request to Annotations Service: " + response.getCode());
                        }
                    });
                    return text(xml);
                } catch (final Exception e) {
                    context.error(String.valueOf(e.getMessage()));
                    return null;
                }
            }
        };
    }
}
