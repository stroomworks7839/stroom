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

import stroom.meta.shared.Meta;
import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.pipeline.ShapeshifterFunctionModule;
import stroom.shapeshifter.pipeline.StroomFunctionLibrary;
import stroom.util.date.DateUtil;
import stroom.util.net.IpAddressUtil;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 26 phase 2: group A, each variant on the cases Stroom's own test of the Saxon class
 * uses — {@code TestHexToDec}, {@code TestFormatDate} and the rest in stroom-pipeline — and,
 * where a function has no test there, on cases read from the class. The comparison against
 * the Saxon classes themselves is {@code StroomFunctionsVersusSaxonTest}.
 */
class StroomFunctionsTest {

    /** A recording context: what a function said, what it may reach. */
    static final class Recording implements FunctionContext {

        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Map<String, Object> state = new HashMap<>();
        final Map<Class<?>, Object> services = new HashMap<>();

        @Override
        public void warn(final String message) {
            warnings.add(message);
        }

        @Override
        public void error(final String message) {
            errors.add(message);
        }

        @Override
        public long inputOffset() {
            return 0;
        }

        @Override
        public Map<String, Object> state() {
            return state;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T service(final Class<T> type) {
            return (T) services.get(type);
        }
    }

    private static final Map<String, FunctionDefinition> BY_NAME = new HashMap<>();

    static {
        ShapeshifterFunctionModule.groupA().forEach(d -> BY_NAME.put(d.name(), d));
    }

    private final Recording context = new Recording();

    /** Arguments from plain values: a String, Number, Boolean, Instant, List (a sequence) or null. */
    static Arguments args(final Object... values) {
        final List<TypedValue> cast = new ArrayList<>();
        final List<List<TypedValue>> sequences = new ArrayList<>();
        for (final Object value : values) {
            if (value instanceof List<?> list) {
                cast.add(null);
                sequences.add(list.stream().map(StroomFunctionsTest::typed).toList());
            } else {
                cast.add(typed(value));
                sequences.add(null);
            }
        }
        return new Arguments(cast, cast, sequences);
    }

    private static TypedValue typed(final Object value) {
        return switch (value) {
            case null -> null;
            case String s -> TypedValue.of(s);
            case Long l -> new TypedValue.Int(l);
            case Integer i -> new TypedValue.Int(i);
            case Double d -> new TypedValue.Real(d);
            case Boolean b -> new TypedValue.Bool(b);
            case Instant i -> new TypedValue.Instant(i.getEpochSecond(), i.getNano(), null);
            default -> throw new IllegalArgumentException(value.toString());
        };
    }

    private String call(final String function, final Object... values) {
        final TypedValue result = bind(function).call(args(values));
        return result == null ? null : result.asString();
    }

    private FunctionCall bind(final String function) {
        return BY_NAME.get(function).bind(context);
    }

    private void withMetaCreatedAt(final String isoInstant) {
        final Meta meta = Mockito.mock(Meta.class);
        Mockito.when(meta.getCreateMs()).thenReturn(Instant.parse(isoInstant).toEpochMilli());
        final MetaHolder holder = Mockito.mock(MetaHolder.class);
        Mockito.when(holder.getMeta()).thenReturn(meta);
        context.services.put(MetaHolder.class, holder);
    }

    @Test
    void theRegistryHoldsGroupAUnderStroomsNames() {
        final FunctionRegistry registry = FunctionRegistry.of(ShapeshifterFunctionModule.groupA());
        assertThat(registry.size()).isEqualTo(23);
        assertThat(registry.lookup("format-date").purity()).isEqualTo(Purity.CONTEXT);
        assertThat(registry.lookup("host-name").purity()).isEqualTo(Purity.IMPURE);
        assertThat(registry.lookup("hex-to-dec").signature().argKinds()).containsExactly(Kind.STRING);
        assertThat(registry.lookup("cosine-similarity").signature().argKinds())
                .containsExactly(Kind.SEQUENCE, Kind.SEQUENCE);
    }

    /**
     * Phase 2 audit: the Guice module binds classes and {@code groupA()} lists instances, and
     * nothing but this keeps the two the same.
     */
    @Test
    void theGuiceModuleBindsExactlyGroupA() {
        final Set<FunctionDefinition> bound = Guice.createInjector(new ShapeshifterFunctionModule())
                .getInstance(Key.get(new TypeLiteral<Set<FunctionDefinition>>() {
                }));
        final List<String> expected = ShapeshifterFunctionModule.all().stream()
                .map(FunctionDefinition::name).sorted().toList();
        assertThat(bound.stream().map(FunctionDefinition::name).sorted().toList()).isEqualTo(expected);
        assertThat(new StroomFunctionLibrary(bound).registry().size()).isEqualTo(57);
    }

    @Test
    void hexToDecAndOct() {
        assertThat(call("hex-to-dec", "2A")).isEqualTo("42");
        assertThat(call("hex-to-dec", "foobar")).isNull();
        assertThat(context.warnings).singleElement().asString()
                .containsIgnoringCase("error converting").contains("foobar").containsIgnoringCase("decimal");
        assertThat(call("hex-to-oct", "2A")).isEqualTo("52");
    }

    @Test
    void hexToString() {
        assertThat(call("hex-to-string", "74 65 73 74 69 6e 67 20 31 32 33", "ASCII")).isEqualTo("testing 123");
        assertThat(call("hex-to-string", "74 65 73 74 69 6E 67 20 31 32 33", "UTF-8")).isEqualTo("testing 123");
        assertThat(call("hex-to-string", "00 74 00 65 00 73 00 74 00 69 00 6e 00 67 00 20 00 31 00 32 00 33",
                "UTF-16BE")).isEqualTo("testing 123");
        assertThat(call("hex-to-string", "", "UTF-8")).isEqualTo("");
        assertThat(call("hex-to-string", "74 65", "invalid charset")).isNull();
        assertThat(context.errors).singleElement().asString().containsIgnoringCase("invalid charset");
        assertThat(call("hex-to-string", "7 ", "UTF-8")).isNull();
        assertThat(context.warnings).singleElement().asString().containsIgnoringCase("invalid string length");
    }

    @Test
    void numericIpAndCidrs() throws Exception {
        assertThat(call("numeric-ip", "192.168.1.1")).isEqualTo("3232235777");
        assertThat(call("numeric-ip", "255.255.255.255")).isEqualTo("4294967295");
        assertThat(call("numeric-ip", "0.0.0.0")).isEqualTo("0");
        assertThat(call("numeric-ip", "1.1.1.1")).isEqualTo("16843009");

        assertThat(call("ip-in-cidr", "192.168.1.10", "192.168.1.0/24")).isEqualTo("true");
        assertThat(call("ip-in-cidr", "192.168.2.10", "192.168.1.0/24")).isEqualTo("false");
        assertThat(call("ip-in-cidr", "10.20.0.250", "10.20.0.0/16")).isEqualTo("true");
        assertThat(call("ip-in-cidr", "10.21.0.250", "10.20.0.0/16")).isEqualTo("false");
        assertThat(call("ip-in-cidr", "192.168.3.300", "192.168.1.0/24")).isNull();
        assertThat(context.errors).singleElement().asString().containsIgnoringCase("invalid ip address");
        context.errors.clear();
        assertThat(call("ip-in-cidr", "192.168.1.1", "192.168.1.0")).isNull();
        assertThat(context.errors).singleElement().asString().containsIgnoringCase("invalid cidr format");
        context.errors.clear();

        assertThat(call("cidr-to-numeric-ip-range", "192.168.1.0/24")).isEqualTo(
                IpAddressUtil.toNumericIpAddress("192.168.1.0") + ","
                + IpAddressUtil.toNumericIpAddress("192.168.1.255"));
        assertThat(call("cidr-to-numeric-ip-range", "10.0.10.32/27")).isEqualTo(
                IpAddressUtil.toNumericIpAddress("10.0.10.32") + "," + IpAddressUtil.toNumericIpAddress("10.0.10.63"));
        assertThat(call("cidr-to-numeric-ip-range", "10.1.0.0/16")).isEqualTo(
                IpAddressUtil.toNumericIpAddress("10.1.0.0") + "," + IpAddressUtil.toNumericIpAddress("10.1.255.255"));
        assertThat(call("cidr-to-numeric-ip-range", "192.168.1.0")).isNull();
        assertThat(context.errors).singleElement().asString().containsIgnoringCase("invalid cidr format");
    }

    @Test
    void hash() {
        assertThat(call("hash", "test")).isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        assertThat(call("hash", "foobar"))
                .isEqualTo("c3ab8ff13720e8ad9047dd39466b3c8974e592c2fa383d4a3960714caef0c4f2");
        assertThat(call("hash", "test", "MD5")).isEqualTo("098f6bcd4621d373cade4e832627b4f6");
        assertThat(call("hash", "test", "SHA-1")).isEqualTo("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
        assertThat(call("hash", "test", "MD5", "haveSomeSalt")).isEqualTo("14953be9f11b60a3decd4a40c6eee67a");
        assertThat(call("hash", "test", "SHA-256", "haveSomeSalt"))
                .isEqualTo("074eb41bbffbd87315fc4095a9f082a646efa1c51e146ebd616e5d47fc3ff9d7");
        assertThat(call("hash", "test", "my-bad-algo")).isNull();
        assertThat(context.errors).singleElement().asString().contains("my-bad-algo")
                .containsIgnoringCase("messagedigest not available");
        assertThat(call("hash", "")).isNull();
    }

    @Test
    void urlsAndHosts() {
        assertThat(call("encode-url", "a b&c=d/e")).isEqualTo("a+b%26c%3Dd%2Fe");
        assertThat(call("decode-url", "a+b%26c%3Dd%2Fe")).isEqualTo("a b&c=d/e");
        assertThat(call("host-address", "127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(call("host-name", "127.0.0.1")).isNotEmpty();
        assertThat(call("host-address", "no-such-host.invalid")).isNull();
        assertThat(context.warnings).hasSize(1);
        assertThat(call("host-address", "no-such-host.invalid", true)).isNull();
        assertThat(context.warnings).hasSize(1);
    }

    @Test
    void formatDateInItsThreeForms() {
        assertThat(call("format-date", "1269270011640")).isEqualTo("2010-03-22T15:00:11.640Z");
        assertThat(call("format-date", "29/08/24", "dd/MM/yy")).isEqualTo("2024-08-29T00:00:00.000Z");
        assertThat(call("format-date", "08/29/24", "MM/dd/yy")).isEqualTo("2024-08-29T00:00:00.000Z");
        assertThat(call("format-date", "2001/08/01", "yyyy/MM/dd", "-07:00")).isEqualTo("2001-08-01T07:00:00.000Z");
        assertThat(call("format-date", "2001/08/01 01:00:00", "yyyy/MM/dd HH:mm:ss", "+01:00"))
                .isEqualTo("2001-08-01T00:00:00.000Z");
        assertThat(call("format-date", "Wed Aug 14 2024 10:32:58 pm", "E MMM dd yyyy hh:mm:ss a"))
                .isEqualTo("2024-08-14T22:32:58.000Z");
        assertThat(call("format-date", "2001/12/31", "yyyy/MM/dd[ HH:mm:ss.SSS]"))
                .isEqualTo("2001-12-31T00:00:00.000Z");
        assertThat(call("format-date", "2009/06/01 12:34:11", "yyyy/MM/dd HH:mm:ss", "GMT/BST"))
                .isEqualTo("2009-06-01T11:34:11.000Z");
        assertThat(call("format-date", "2009/02/01 23:34:11", "yyyy/MM/dd HH:mm:ss", "US/Eastern"))
                .isEqualTo("2009-02-02T04:34:11.000Z");
        assertThat(call("format-date", "2001/08/01 14:30:59", "yyyy/MM/dd HH:mm:ss", "UTC",
                "yyyy-MM-dd'T'HH:mm", "+01:00"))
                .isEqualTo("2001-08-01T15:30");
        assertThat(call("format-date", "2001/08/01 14:30:59", "yyyy/MM/dd HH:mm:ss", null, "E dd MMM yyyy HH:mm"))
                .isEqualTo("Wed 01 Aug 2001 14:30");
        assertThat(call("format-date", "2001 12 31", "yyy MMM ddd")).isNull();
        assertThat(context.warnings).singleElement().asString().contains("Failed to parse date");
    }

    @Test
    void formatDateCompletesAMissingYearFromTheStreamsCreationTime() {
        withMetaCreatedAt("2010-03-01T12:45:22.643Z");
        assertThat(call("format-date", "01/01", "dd/MM", "UTC")).isEqualTo("2010-01-01T00:00:00.000Z");
        assertThat(call("format-date", "01/04", "dd/MM", "UTC")).isEqualTo("2009-04-01T00:00:00.000Z");
        assertThat(call("format-date", "04", "dd", "UTC")).isEqualTo("2010-02-04T00:00:00.000Z");
        assertThat(call("format-date", "12:30", "HH:mm", "UTC")).isEqualTo("2010-03-01T12:30:00.000Z");
        assertThat(call("format-date", "Mon/1/2018", "ccc/w/YYYY", "UTC")).isEqualTo("2018-01-01T00:00:00.000Z");
        withMetaCreatedAt("2010-03-04T12:45:22.643Z");
        assertThat(call("format-date", "Fri/2", "ccc/w", "UTC")).isEqualTo("2010-01-08T00:00:00.000Z");
        assertThat(call("format-date", "Fri/40", "E/w", "UTC")).isEqualTo("2009-10-02T00:00:00.000Z");
        assertThat(call("format-date", "Fri", "ccc", "UTC")).isEqualTo("2010-02-26T00:00:00.000Z");
        assertThat(call("format-date", "Fri 05 Mar 2010", "eee dd MMM YYYY", "UTC"))
                .isEqualTo("2010-03-05T00:00:00.000Z");
    }

    @Test
    void parseAndFormatDateTimeAndUnixTime() {
        assertThat(call("parse-dateTime", "2024-08-29T00:00:00Z")).isEqualTo("2024-08-29T00:00:00Z");
        assertThat(call("parse-dateTime", "2001-08-01T18:45:59.123+02:00")).isEqualTo("2001-08-01T16:45:59.123Z");
        assertThat(call("parse-dateTime", "29/08/24", "dd/MM/yy")).isEqualTo("2024-08-29T00:00:00Z");
        assertThat(call("parse-dateTime", "2001/08/01", "yyyy/MM/dd", "-07:00")).isEqualTo("2001-08-01T07:00:00Z");
        withMetaCreatedAt("2010-03-01T12:45:22.643Z");
        assertThat(call("parse-dateTime", "01/04", "dd/MM", "UTC")).isEqualTo("2009-04-01T00:00:00Z");

        final Instant zero = Instant.parse("2024-08-29T00:00:00Z");
        assertThat(call("format-dateTime", zero)).isEqualTo("2024-08-29T00:00:00.000Z");
        assertThat(call("format-dateTime", Instant.parse("2010-01-01T23:59:59.123456Z")))
                .isEqualTo("2010-01-01T23:59:59.123Z");
        assertThat(call("format-dateTime", zero, "dd/MM/yy")).isEqualTo("29/08/24");
        assertThat(call("format-dateTime", Instant.parse("2001-08-01T09:00:00Z"), "yyyy/MM/dd HH:mm:ss", "-08:00"))
                .isEqualTo("2001/08/01 01:00:00");
        assertThat(call("format-dateTime", "not a date")).isNull();
        assertThat(context.warnings).singleElement().asString().containsIgnoringCase("non dateTime");

        assertThat(call("from-unixTime", 1749727780400L)).isEqualTo("2025-06-12T11:29:40.4Z");
        assertThat(call("to-unixTime", Instant.parse("2025-06-12T11:29:40.400Z"))).isEqualTo("1749727780400");
    }

    @Test
    void theClockAndRandom() {
        final long before = System.currentTimeMillis();
        final long now = DateUtil.parseNormalDateTimeString(call("current-time"));
        assertThat(now).isBetween(before - 1000, System.currentTimeMillis() + 1000);
        assertThat(Long.parseLong(call("current-unixTime"))).isBetween(before - 1000,
                System.currentTimeMillis() + 1000);
        final double random = Double.parseDouble(call("random"));
        assertThat(random).isBetween(0.0, 1.0);
    }

    @Test
    void cosineSimilarityAndPolygons() {
        assertThat(call("cosine-similarity", List.of(1.0, 0.0), List.of(1.0, 0.0))).isEqualTo("1");
        assertThat(call("cosine-similarity", List.of(1.0, 0.0), List.of(0.0, 1.0))).isEqualTo("0");
        assertThat(call("cosine-similarity", List.of(1.0, 2.0), List.of(1.0))).isNull();
        assertThat(context.errors).singleElement().asString().contains("dimensions must match");

        final List<Double> xs = List.of(0.0, 2.0, 2.0, 0.0);
        final List<Double> ys = List.of(0.0, 0.0, 2.0, 2.0);
        assertThat(call("pointIsInsideXYPolygon", 1.0, 1.0, xs, ys)).isEqualTo("true");
        assertThat(call("pointIsInsideXYPolygon", 3.0, 1.0, xs, ys)).isEqualTo("false");
        assertThat(call("pointIsInsideXYPolygon", 1.0, 1.0, List.of(0.0, 1.0), List.of(0.0, 1.0))).isEqualTo("false");
        assertThat(context.warnings).singleElement().asString().contains("Too few points");
    }

    @Test
    void jsonToXmlAndParseUriAsText() {
        final String xml = call("json-to-xml", """
                {
                    "firstName": "Joe",
                    "secondName": "Bloggs"
                }
                """);
        assertThat(xml).contains("<map xmlns=\"http://www.w3.org/2013/XSL/json\">")
                .contains("<string key=\"firstName\">Joe</string>")
                .contains("<string key=\"secondName\">Bloggs</string>")
                .doesNotContain("<?xml");
        assertThat(call("json-to-xml", "")).isNull();
        assertThat(call("json-to-xml", "{ \"firstName\": \"Joe\"")).isNull();
        assertThat(context.errors).singleElement().asString().containsIgnoringCase("error parsing json");

        final String uri = call("parse-uri", "http://user@example.com:8080/path/x?q=1#frag");
        assertThat(uri).contains("<uri xmlns=\"uri\">").contains("<host>example.com</host>")
                .contains("<port>8080</port>")
                .contains("<scheme>http</scheme>").contains("<userInfo>user</userInfo>")
                        .contains("<fragment>frag</fragment>")
                .contains("<path>/path/x</path>").contains("<query>q=1</query>");
        assertThat(call("parse-uri", "")).isNull();
    }
}
