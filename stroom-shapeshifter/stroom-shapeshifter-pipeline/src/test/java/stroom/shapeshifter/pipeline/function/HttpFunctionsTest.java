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

import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.util.http.HttpClientConfiguration;
import stroom.util.jersey.HttpClientProvider;
import stroom.util.jersey.HttpClientProviderCache;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 26 phase 4: the HTTP pair against a server on this machine, through a client the way
 * Stroom's own client cache would hand it out.
 */
class HttpFunctionsTest {

    private final ContextFunctionsTest.Fixed context = new ContextFunctionsTest.Fixed();
    private HttpServer server;
    private CloseableHttpClient httpClient;
    private String base;
    private volatile String lastBody;
    private volatile String lastHeader;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastHeader = exchange.getRequestHeaders().getFirst("X-Test");
            final byte[] reply = ("echo:" + lastBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Reply", "yes");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.createContext("/json", exchange -> {
            final byte[] reply = "{\"name\": \"Joe\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        httpClient = HttpClients.createDefault();
        final HttpClientProviderCache cache = Mockito.mock(HttpClientProviderCache.class);
        Mockito.when(cache.get(Mockito.any(HttpClientConfiguration.class))).thenReturn(new HttpClientProvider() {
            @Override
            public org.apache.hc.client5.http.classic.HttpClient get() {
                return httpClient;
            }

            @Override
            public void close() {
            }
        });
        context.services.put(HttpClientProviderCache.class, cache);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
        httpClient.close();
    }

    private String call(final FunctionCall function, final Object... values) {
        final TypedValue result = function.call(StroomFunctionsTest.args(values));
        return result == null ? null : result.asString();
    }

    @Test
    void httpCallPostsAndRendersTheResponse() {
        final FunctionCall call = new HttpCallFunction().bind(context);
        final String response = call(call, base + "/echo", "X-Test: hello\nIgnored",
                "text/plain; charset=utf-8", "payload");
        assertThat(context.all).isEmpty();
        assertThat(response).contains("<response xmlns=\"stroom-http\">")
                .contains("<successful>true</successful>").contains("<code>200</code>")
                .contains("<key>X-reply</key>").contains("<value>yes</value>")
                .contains("<body>echo:payload</body>");
        assertThat(lastBody).isEqualTo("payload");
        assertThat(lastHeader).isEqualTo("hello");
    }

    @Test
    void httpCallThatCannotConnectIsAnErrorAndAnErrorElement() {
        final FunctionCall call = new HttpCallFunction().bind(context);
        final String response = call(call, "http://127.0.0.1:1/nowhere");
        assertThat(response).contains("<error xmlns=\"stroom-http\">").contains("Error calling function http-call()");
        assertThat(context.errors).singleElement().asString().contains("Error calling function http-call()");
        assertThat(call(call, "")).isNull();
        assertThat(context.warnings).singleElement().asString().isEqualTo("No URL specified for HTTP call");
    }

    @Test
    void fetchJsonGetsAndConvertsAndIsAbsentForNotFound() {
        final FunctionCall call = new FetchJsonFunction().bind(context);
        assertThat(call(call, base + "/json")).contains("<map xmlns=\"http://www.w3.org/2013/XSL/json\">")
                .contains("<string key=\"name\">Joe</string>");
        assertThat(call(call, base + "/missing")).isNull();
        assertThat(context.all).isEmpty();
        assertThat(call(call, base + "/broken")).isNull();
        assertThat(context.errors).singleElement().asString().contains("500");
    }
}
