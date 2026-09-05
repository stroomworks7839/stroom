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

import stroom.docref.DocRef;
import stroom.meta.shared.Meta;
import stroom.pipeline.refdata.ReferenceData;
import stroom.pipeline.refdata.ReferenceDataResult;
import stroom.pipeline.refdata.store.FastInfosetValue;
import stroom.pipeline.refdata.store.MapDefinition;
import stroom.pipeline.refdata.store.RefDataValueProxy;
import stroom.pipeline.refdata.store.RefStreamDefinition;
import stroom.pipeline.refdata.store.StringValue;
import stroom.pipeline.refdata.store.offheapstore.TypedByteBuffer;
import stroom.pipeline.shared.data.PipelineReference;
import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.pipeline.ElementServices;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 26 phase 4: the lookups, with reference data stubbed the way Stroom's own
 * {@code TestLookup} stubs it — an answer that fills in the result — and each outcome's message.
 */
class LookupFunctionsTest {

    private final ContextFunctionsTest.Fixed context = new ContextFunctionsTest.Fixed();
    private final ReferenceData referenceData = Mockito.mock(ReferenceData.class);
    private final PipelineReference reference = new PipelineReference(
            new DocRef("Pipeline", UUID.randomUUID().toString(), "MyPipe"),
            new DocRef("Feed", UUID.randomUUID().toString(), "MY_FEED"),
            "Reference");
    private FunctionCall lookup;
    private FunctionCall bitmap;

    @BeforeEach
    void setUp() {
        context.services.put(ReferenceData.class, referenceData);
        context.services.put(ElementServices.PipelineReferences.class,
                new ElementServices.PipelineReferences(List.of(reference)));
        final Meta meta = Mockito.mock(Meta.class);
        Mockito.when(meta.getCreateMs()).thenReturn(1_000_000L);
        final MetaHolder holder = new MetaHolder();
        holder.setMeta(meta);
        context.services.put(MetaHolder.class, holder);
        lookup = new LookupFunction().bind(context);
        bitmap = new BitmapLookupFunction().bind(context);
    }

    private String call(final FunctionCall function, final Object... values) {
        final TypedValue result = function.call(StroomFunctionsTest.args(values));
        return result == null ? null : result.asString();
    }

    /** Reference data that answers every identifier the same way. */
    private void answer(final Answer answer) {
        Mockito.doAnswer(invocation -> answer.fill(invocation.getArgument(2)))
                .when(referenceData).ensureReferenceDataAvailability(Mockito.any(), Mockito.any(), Mockito.any());
    }

    private interface Answer {

        ReferenceDataResult fill(ReferenceDataResult result);
    }

    private static ReferenceDataResult found(final ReferenceDataResult result, final Object value,
                                             final RefStreamDefinition stream) {
        result.addEffectiveStream(null, stream);
        final ReferenceDataResult spy = Mockito.spy(result);
        final RefDataValueProxy proxy = Mockito.mock(RefDataValueProxy.class);
        final MapDefinition definition = Mockito.mock(MapDefinition.class);
        Mockito.when(definition.getRefStreamDefinition()).thenReturn(stream);
        Mockito.when(proxy.getSuccessfulMapDefinition()).thenReturn(Optional.of(definition));
        // The bytes as the store hands them to a consumer, typed; the function must not read them later.
        final TypedByteBuffer typed;
        if (value instanceof String string) {
            typed = new TypedByteBuffer(StringValue.TYPE_ID, ByteBuffer.wrap(string.getBytes(StandardCharsets.UTF_8)));
        } else if (value instanceof byte[] bytes) {
            typed = new TypedByteBuffer(FastInfosetValue.TYPE_ID, ByteBuffer.wrap(bytes));
        } else {
            typed = null;
        }
        Mockito.when(proxy.consumeBytes(Mockito.any())).thenAnswer(invocation -> {
            if (typed == null) {
                return false;
            }
            invocation.<Consumer<TypedByteBuffer>>getArgument(0).accept(typed);
            return true;
        });
        Mockito.doReturn(Optional.of(proxy)).when(spy).getRefDataValueProxy();
        return spy;
    }

    private static RefStreamDefinition stream(final long id) {
        return new RefStreamDefinition(UUID.randomUUID().toString(), UUID.randomUUID().toString(), id);
    }

    @Test
    void noReferenceLoadersIsAnError() {
        context.services.put(ElementServices.PipelineReferences.class,
                new ElementServices.PipelineReferences(List.of()));
        assertThat(call(lookup, "MAP", "key")).isNull();
        assertThat(context.errors).singleElement().asString().containsIgnoringCase("no reference loaders");
    }

    @Test
    void noEffectiveStreamsIsAWarningUnlessIgnored() {
        answer(result -> result);
        assertThat(call(lookup, "MAP", "key")).isNull();
        assertThat(context.warnings).singleElement().asString().containsIgnoringCase("no effective streams")
                .contains("'MY_FEED'");
        context.warnings.clear();
        assertThat(call(lookup, "MAP", "key", "2010-01-01T00:00:00.000Z", true)).isNull();
        assertThat(context.warnings).isEmpty();
    }

    @Test
    void mapNotFoundInEffectiveStreamsIsAWarning() {
        answer(result -> {
            result.addEffectiveStream(reference, stream(123L));
            return result;
        });
        assertThat(call(lookup, "MAP", "key")).isNull();
        assertThat(context.warnings).singleElement().asString()
                .containsIgnoringCase("map not found in effective streams").contains("MY_FEED:123");
    }

    @Test
    void keyNotFoundIsAWarningAndKeyFoundIsTheValue() {
        answer(result -> found(result, null, stream(5L)));
        assertThat(call(lookup, "MAP", "key")).isNull();
        assertThat(context.warnings).singleElement().asString().containsIgnoringCase("key not found");
        context.warnings.clear();
        context.all.clear();

        answer(result -> found(result, "the value", stream(5L)));
        assertThat(call(lookup, "MAP", "key")).isEqualTo("the value");
        assertThat(context.all).isEmpty();
        // With trace, the success is said, with the stream it came from.
        assertThat(call(lookup, "MAP", "key", "2010-01-01T00:00:00.000Z", false, true)).isEqualTo("the value");
        assertThat(context.all).singleElement().asString().startsWith("INFO Key found ").contains("found in stream: 5");
    }

    @Test
    void anXmlValueIsSerialisedAsText() throws Exception {
        final byte[] fastInfoset = fastInfoset("<value xmlns=\"reference-data:2\"><a>1</a></value>");
        answer(result -> found(result, fastInfoset, stream(7L)));
        assertThat(call(lookup, "MAP", "key")).contains("<value xmlns=\"reference-data:2\">").contains("<a>1</a>")
                .doesNotContain("<?xml");
    }

    @Test
    void theLookupTimeIsTheStreamsCreationOrTheArgument() {
        answer(result -> {
            assertThat(result.getCurrentLookupIdentifier().getEventTime()).isEqualTo(1_000_000L);
            return found(result, "v", stream(1L));
        });
        assertThat(call(lookup, "MAP", "key")).isEqualTo("v");
        answer(result -> {
            assertThat(result.getCurrentLookupIdentifier().getEventTime()).isEqualTo(1_262_304_000_000L);
            return found(result, "w", stream(1L));
        });
        assertThat(call(lookup, "MAP", "key", "2010-01-01T00:00:00.000Z")).isEqualTo("w");
        assertThat(call(lookup, "MAP", "key", "not a date")).isNull();
        assertThat(context.warnings).singleElement().asString().contains("Lookup failed to parse date: not a date");
        // Written but absent is Stroom's empty date: no value and a warning, unless warnings are ignored.
        context.warnings.clear();
        assertThat(call(lookup, "MAP", "key", null)).isNull();
        assertThat(context.warnings).singleElement().asString().contains("Lookup failed to parse empty date");
        context.warnings.clear();
        assertThat(call(lookup, "MAP", "key", null, true)).isNull();
        assertThat(context.warnings).isEmpty();
    }

    @Test
    void bitmapLookupLooksUpEachSetBit() {
        answer(result -> {
            final String bit = result.getCurrentLookupIdentifier().getKey();
            return "2".equals(bit) ? result : found(result, "bit-" + bit, stream(9L));
        });
        // 13 = 0b1101: bits 0, 2 and 3; bit 2 is not found.
        assertThat(call(bitmap, "MAP", "13")).isEqualTo("bit-0,bit-3");
        assertThat(context.warnings).anySatisfy(w -> assertThat(w).contains("keys = {2}"));
        assertThat(call(bitmap, "MAP", "0x5")).isEqualTo("bit-0");
        assertThat(BitmapLookupFunction.bits("0")).isEmpty();
        assertThat(call(bitmap, "MAP", "zz")).isNull();
        assertThat(context.errors).anySatisfy(e -> assertThat(e).contains("unable to parse number 'zz'"));
    }

    private static byte[] fastInfoset(final String xml) throws Exception {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final com.sun.xml.fastinfoset.sax.SAXDocumentSerializer serializer =
                new com.sun.xml.fastinfoset.sax.SAXDocumentSerializer();
        serializer.setOutputStream(out);
        final javax.xml.parsers.SAXParserFactory factory = javax.xml.parsers.SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final org.xml.sax.XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setContentHandler(serializer);
        reader.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        return out.toByteArray();
    }
}
