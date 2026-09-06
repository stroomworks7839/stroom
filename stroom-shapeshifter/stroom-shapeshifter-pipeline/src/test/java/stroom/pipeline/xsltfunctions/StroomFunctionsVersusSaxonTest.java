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

package stroom.pipeline.xsltfunctions;

import stroom.meta.shared.Meta;
import stroom.pipeline.LocationFactory;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.pipeline.ShapeshifterFunctionModule;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.BooleanValue;
import net.sf.saxon.value.DateTimeValue;
import net.sf.saxon.value.Int64Value;
import net.sf.saxon.value.StringValue;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 26 §6's second pin: the variant against the Saxon class itself, on the same inputs.
 * In this package because the Saxon classes and their {@code call} are package-private, as
 * stroom-pipeline's own {@code AbstractXsltFunctionTest} is. The date functions run with the
 * same stream creation time on both sides, since both complete a missing year from it.
 */
class StroomFunctionsVersusSaxonTest {

    private static final Instant META_CREATE_TIME = Instant.parse("2010-03-04T12:45:22.643Z");

    private record Case(String function, Object... inputs) {

    }

    private static final List<Case> CASES = List.of(
            new Case("hex-to-dec", "2A"), new Case("hex-to-dec", "ff"), new Case("hex-to-dec", "foobar"),
            new Case("hex-to-oct", "2A"), new Case("hex-to-oct", "zz"),
            new Case("hex-to-string", "74 65 73 74 69 6e 67 20 31 32 33", "ASCII"),
            new Case("hex-to-string", "00 74 00 65", "UTF-16BE"),
            new Case("hex-to-string", "", "UTF-8"),
            new Case("numeric-ip", "192.168.1.1"), new Case("numeric-ip", "255.255.255.255"),
            new Case("ip-in-cidr", "192.168.1.10", "192.168.1.0/24"), new Case("ip-in-cidr", "10.21.0.250",
                    "10.20.0.0/16"),
            new Case("decode-url", "a+b%26c%3Dd%2Fe"), new Case("encode-url", "a b&c=d/e"),
            new Case("hash", "test"), new Case("hash", "test", "MD5"), new Case("hash", "test", "SHA-1",
                    "haveSomeSalt"),
            new Case("host-address", "127.0.0.1"),
            new Case("format-date", "1269270011640"),
            new Case("format-date", "29/08/24", "dd/MM/yy"),
            new Case("format-date", "2001/08/01 01:00:00", "yyyy/MM/dd HH:mm:ss", "+01:00"),
            new Case("format-date", "Wed Aug 14 2024 10:32:58 pm", "E MMM dd yyyy hh:mm:ss a"),
            new Case("format-date", "2009/06/01 12:34:11", "yyyy/MM/dd HH:mm:ss", "GMT/BST"),
            new Case("format-date", "01/04", "dd/MM", "UTC"),
            new Case("format-date", "Fri/40", "E/w", "UTC"),
            new Case("format-date", "Fri 05 Mar 2010", "eee dd MMM YYYY", "UTC"),
            new Case("format-date", "2001/08/01 14:30:59", "yyyy/MM/dd HH:mm:ss", "UTC", "E dd MMM yyyy HH:mm",
                    "+01:00"),
            new Case("parse-dateTime", "2024-08-29T00:00:00Z"),
            new Case("parse-dateTime", "2001-08-01T18:45:59.123+02:00"),
            new Case("parse-dateTime", "29/08/24", "dd/MM/yy"),
            new Case("parse-dateTime", "01/04", "dd/MM", "UTC"),
            new Case("format-dateTime", Instant.parse("2010-01-01T23:59:59.123456Z")),
            new Case("format-dateTime", Instant.parse("2001-08-01T09:00:00Z"), "yyyy/MM/dd HH:mm:ss", "-08:00"),
            new Case("from-unixTime", 1749727780400L),
            new Case("to-unixTime", Instant.parse("2025-06-12T11:29:40.400Z")));

    private static final Map<String, FunctionDefinition> VARIANTS = new HashMap<>();

    static {
        ShapeshifterFunctionModule.groupA().forEach(d -> VARIANTS.put(d.name(), d));
    }

    @TestFactory
    Stream<DynamicTest> variantAgreesWithTheSaxonClass() {
        return CASES.stream().map(c -> DynamicTest.dynamicTest(c.function() + " " + List.of(c.inputs()), () -> {
            final String saxon = saxon(c);
            final String variant = variant(c);
            assertThat(variant).as(c.function() + " " + List.of(c.inputs())).isEqualTo(saxon);
        }));
    }

    private static String saxon(final Case c) throws XPathException {
        final MetaHolder metaHolder = Mockito.mock(MetaHolder.class);
        final Meta meta = Mockito.mock(Meta.class);
        Mockito.when(meta.getCreateMs()).thenReturn(META_CREATE_TIME.toEpochMilli());
        Mockito.when(metaHolder.getMeta()).thenReturn(meta);
        final StroomExtensionFunctionCall function = switch (c.function()) {
            case "hex-to-dec" -> new HexToDec();
            case "hex-to-oct" -> new HexToOct();
            case "hex-to-string" -> new HexToString();
            case "numeric-ip" -> new NumericIP();
            case "ip-in-cidr" -> new IPInCidr();
            case "decode-url" -> new DecodeUrl();
            case "encode-url" -> new EncodeUrl();
            case "hash" -> new Hash();
            case "host-address" -> new HostAddress();
            case "format-date" -> new FormatDate(metaHolder);
            case "parse-dateTime" -> new ParseDateTime(metaHolder);
            case "format-dateTime" -> new FormatDateTime();
            case "from-unixTime" -> new FromUnixTime();
            case "to-unixTime" -> new ToUnixTime();
            default -> throw new IllegalArgumentException(c.function());
        };
        function.configure(Mockito.mock(ErrorReceiver.class), Mockito.mock(LocationFactory.class), List.of());
        final Sequence[] arguments = new Sequence[c.inputs().length];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = switch (c.inputs()[i]) {
                case String s -> StringValue.makeStringValue(s);
                case Long l -> Int64Value.makeIntegerValue(l);
                case Boolean b -> BooleanValue.get(b);
                case Instant instant -> DateTimeValue.fromJavaInstant(instant);
                default -> throw new IllegalArgumentException(String.valueOf(c.inputs()[i]));
            };
        }
        final Sequence result = function.call(c.function(), Mockito.mock(XPathContext.class), arguments);
        final Item head = result == null ? null : result.head();
        return head == null ? null : head.getStringValue();
    }

    private static String variant(final Case c) {
        final MetaHolder metaHolder = Mockito.mock(MetaHolder.class);
        final Meta meta = Mockito.mock(Meta.class);
        Mockito.when(meta.getCreateMs()).thenReturn(META_CREATE_TIME.toEpochMilli());
        Mockito.when(metaHolder.getMeta()).thenReturn(meta);
        final FunctionContext context = new FunctionContext() {
            @Override
            public void warn(final String message) {
            }

            @Override
            public void error(final String message) {
            }

            @Override
            public long inputOffset() {
                return 0;
            }

            @Override
            public Map<String, Object> state() {
                return new HashMap<>();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T service(final Class<T> type) {
                return type == MetaHolder.class ? (T) metaHolder : null;
            }
        };
        final List<TypedValue> values = new ArrayList<>();
        final List<List<TypedValue>> sequences = new ArrayList<>();
        for (final Object input : c.inputs()) {
            final TypedValue value = switch (input) {
                case String s -> TypedValue.of(s);
                case Long l -> new TypedValue.Int(l);
                case Boolean b -> new TypedValue.Bool(b);
                case Instant instant -> new TypedValue.Instant(instant.getEpochSecond(), instant.getNano(), null);
                default -> throw new IllegalArgumentException(String.valueOf(input));
            };
            values.add(value);
            sequences.add(null);
        }
        final TypedValue result = VARIANTS.get(c.function()).bind(context).call(new Arguments(values, values,
                sequences));
        return result == null ? null : result.asString();
    }
}
