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

import stroom.data.store.api.AttributeMapFactory;
import stroom.data.store.api.DataService;
import stroom.data.store.api.Source;
import stroom.data.store.api.Store;
import stroom.dictionary.api.WordListProvider;
import stroom.docref.DocRef;
import stroom.feed.api.FeedProperties;
import stroom.meta.api.AttributeMap;
import stroom.meta.shared.Meta;
import stroom.pipeline.state.CurrentUserHolder;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaDataProvider;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.state.PipelineHolder;
import stroom.pipeline.state.SearchIdHolder;
import stroom.security.api.UserIdentity;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.output.XmlByteSink;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.pipeline.PipelineState;
import stroom.shapeshifter.pipeline.RunLocations;
import stroom.shapeshifter.pipeline.ShapeshifterFunctionModule;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 26 phase 3: group B, each variant against the holder or service it reads, set up as
 * Stroom's own tests set them up — {@code TestCurrentUser}, {@code TestGet}, {@code TestPut},
 * {@code TestLog}, {@code TestManifest}, {@code TestMetaStream} — and, for the rest, on cases
 * read from the Saxon class.
 */
class ContextFunctionsTest {

    /** A context whose location is fixed and whose services are a map. */
    static final class Fixed implements FunctionContext {

        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<String> all = new ArrayList<>();
        final Map<String, Object> state = new HashMap<>();
        final Map<Class<?>, Object> services = new HashMap<>();
        long offset = 10;
        long length = 5;
        long record = 3;

        @Override
        public void warn(final String message) {
            warnings.add(message);
            all.add("WARNING " + message);
        }

        @Override
        public void error(final String message) {
            errors.add(message);
            all.add("ERROR " + message);
        }

        @Override
        public void message(final Severity severity, final String message) {
            switch (severity) {
                case ERROR, FATAL -> errors.add(message);
                case WARNING -> warnings.add(message);
                default -> {
                }
            }
            all.add(severity + " " + message);
        }

        @Override
        public long inputOffset() {
            return offset;
        }

        @Override
        public long inputLength() {
            return length;
        }

        @Override
        public long recordNumber() {
            return record;
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
        ShapeshifterFunctionModule.groupB().forEach(d -> BY_NAME.put(d.name(), d));
    }

    private final Fixed context = new Fixed();
    /** Bound once per test, as the engine binds once per run: a function's own cache lives for the run. */
    private final Map<String, FunctionCall> bound = new HashMap<>();

    private String call(final String function, final Object... values) {
        final FunctionCall call = bound.computeIfAbsent(function, name -> BY_NAME.get(name).bind(context));
        final TypedValue result = call.call(StroomFunctionsTest.args(values));
        return result == null ? null : result.asString();
    }

    private Meta meta(final long id, final Long parent) {
        final Meta meta = Mockito.mock(Meta.class);
        Mockito.when(meta.getId()).thenReturn(id);
        Mockito.when(meta.getParentMetaId()).thenReturn(parent);
        final MetaHolder holder = new MetaHolder();
        holder.setMeta(meta);
        context.services.put(MetaHolder.class, holder);
        return meta;
    }

    @Test
    void groupBIsThirtyUnderStroomsNamesWithTheirPurities() {
        assertThat(BY_NAME).hasSize(30);
        assertThat(BY_NAME.get("put").purity()).isEqualTo(Purity.IMPURE);
        assertThat(BY_NAME.get("log").purity()).isEqualTo(Purity.IMPURE);
        assertThat(BY_NAME.get("add-meta").purity()).isEqualTo(Purity.IMPURE);
        assertThat(BY_NAME.get("link").purity()).isEqualTo(Purity.PURE);
        assertThat(BY_NAME.get("feed-name").purity()).isEqualTo(Purity.CONTEXT);
        assertThat(ShapeshifterFunctionModule.all()).hasSize(57);
    }

    @Test
    void feedPipelineSearchAndUser() {
        assertThat(call("feed-name")).isNull();
        final FeedHolder feedHolder = new FeedHolder();
        feedHolder.setFeedName("TEST_FEED");
        context.services.put(FeedHolder.class, feedHolder);
        assertThat(call("feed-name")).isEqualTo("TEST_FEED");

        final PipelineHolder pipelineHolder = new PipelineHolder();
        pipelineHolder.setPipeline(new DocRef("Pipeline", "uuid-1", "My Pipeline"));
        context.services.put(PipelineHolder.class, pipelineHolder);
        assertThat(call("pipeline-name")).isEqualTo("My Pipeline");

        final SearchIdHolder searchIdHolder = new SearchIdHolder();
        searchIdHolder.setSearchId("search-42");
        context.services.put(SearchIdHolder.class, searchIdHolder);
        assertThat(call("search-id")).isEqualTo("search-42");

        final UserIdentity user = Mockito.mock(UserIdentity.class);
        Mockito.when(user.subjectId()).thenReturn("sub-1");
        Mockito.when(user.getUserIdentityForAudit()).thenReturn("jbloggs");
        Mockito.when(user.getFullName()).thenReturn(Optional.of("Joe Bloggs"));
        final CurrentUserHolder userHolder = new CurrentUserHolder();
        userHolder.setCurrentUser(user);
        context.services.put(CurrentUserHolder.class, userHolder);
        assertThat(call("current-user")).isEqualTo("jbloggs");
        assertThat(call("current-user", "display")).isEqualTo("jbloggs");
        assertThat(call("current-user", "subject")).isEqualTo("sub-1");
        assertThat(call("current-user", "full")).isEqualTo("Joe Bloggs");

        final FeedProperties feedProperties = Mockito.mock(FeedProperties.class);
        Mockito.when(feedProperties.getDisplayClassification("TEST_FEED")).thenReturn("OFFICIAL");
        context.services.put(FeedProperties.class, feedProperties);
        assertThat(call("classification")).isEqualTo("OFFICIAL");
    }

    @Test
    void metaDataAndAttributes() {
        final MetaDataHolder metaDataHolder = new MetaDataHolder();
        final AttributeMap attributes = new AttributeMap();
        attributes.put("Feed", "TEST_FEED");
        attributes.put("ReceivedTime", "2026-09-04T10:00:00.000Z");
        metaDataHolder.setMetaDataProvider(new MetaDataProvider() {
            @Override
            public String get(final String key) {
                return attributes.get(key);
            }

            @Override
            public AttributeMap getMetaData() {
                return attributes;
            }
        });
        context.services.put(MetaDataHolder.class, metaDataHolder);
        assertThat(call("meta", "Feed")).isEqualTo("TEST_FEED");
        assertThat(call("feed-attribute", "ReceivedTime")).isEqualTo("2026-09-04T10:00:00.000Z");
        assertThat(call("meta", "Nope")).isNull();
        assertThat(call("meta-keys").split(",")).containsExactlyInAnyOrder("Feed", "ReceivedTime");
        assertThat(call("add-meta", "Added", "yes")).isNull();
        assertThat(call("meta", "Added")).isEqualTo("yes");
        assertThat(call("add-meta", " ", "ignored")).isNull();

        meta(77L, 12L);
        final DataService dataService = Mockito.mock(DataService.class);
        Mockito.when(dataService.metaAttributes(77L)).thenReturn(Map.of("Colour", "blue", "Animal", "cat"));
        context.services.put(DataService.class, dataService);
        assertThat(call("meta-attribute", "Colour")).isEqualTo("blue");
        assertThat(call("manifest")).isEqualTo("""
                <manifest xmlns="stroom-meta">
                   <string key="Animal">cat</string>
                   <string key="Colour">blue</string>
                </manifest>""");
        Mockito.when(dataService.metaAttributes(78L)).thenReturn(Map.of("Only", "one"));
        assertThat(call("manifest-for-id", "78")).contains("<string key=\"Only\">one</string>");
    }

    @Test
    void streamIdentityAndParents() throws Exception {
        assertThat(call("stream-id")).isNull();
        meta(77L, 12L);
        assertThat(call("stream-id")).isEqualTo("77");
        assertThat(call("source-id")).isEqualTo("77");
        assertThat(call("parent-id")).isEqualTo("12");
        assertThat(call("part-no")).isEqualTo("1");

        final Store store = Mockito.mock(Store.class);
        final Source source = Mockito.mock(Source.class);
        final Meta other = Mockito.mock(Meta.class);
        Mockito.when(other.getParentMetaId()).thenReturn(5L);
        Mockito.when(source.getMeta()).thenReturn(other);
        Mockito.when(store.openSource(90L)).thenReturn(source);
        context.services.put(Store.class, store);
        assertThat(call("parent-for-id", "90")).isEqualTo("5");
        assertThat(call("parent-for-id", "90")).isEqualTo("5");
        Mockito.verify(store, Mockito.times(1)).openSource(90L);
    }

    @Test
    void metaStream() {
        meta(77L, null);
        final AttributeMapFactory factory = Mockito.mock(AttributeMapFactory.class);
        final AttributeMap part = new AttributeMap();
        part.put("Size", "123");
        Mockito.when(factory.getAttributeMapForPart(77L, 0L)).thenReturn(part);
        final AttributeMap third = new AttributeMap();
        third.put("Part", "three");
        Mockito.when(factory.getAttributeMapForPart(77L, 2L)).thenReturn(third);
        context.services.put(AttributeMapFactory.class, factory);
        assertThat(call("meta-stream")).isEqualTo("""
                <meta-stream xmlns="stroom-meta">
                   <string key="Size">123</string>
                </meta-stream>""");
        assertThat(call("meta-stream-for-id", "77", 3L)).contains("<string key=\"Part\">three</string>");
    }

    @Test
    void locationsComeFromTheRunNotAHolder() {
        assertThat(call("line-from")).isNull();
        final RunLocations locations = Mockito.mock(RunLocations.class);
        Mockito.when(locations.line(10L)).thenReturn(2);
        Mockito.when(locations.column(10L)).thenReturn(4);
        Mockito.when(locations.line(14L)).thenReturn(2);
        Mockito.when(locations.column(14L)).thenReturn(8);
        context.services.put(RunLocations.class, locations);
        assertThat(call("line-from")).isEqualTo("2");
        assertThat(call("col-from")).isEqualTo("4");
        assertThat(call("line-to")).isEqualTo("2");
        assertThat(call("col-to")).isEqualTo("8");
        assertThat(call("record-no")).isEqualTo("3");
        meta(77L, null);
        assertThat(call("source")).isEqualTo("""
                <source xmlns="stroom-meta">
                   <id>77</id>
                   <partNo>1</partNo>
                   <recordNo>3</recordNo>
                   <lineFrom>2</lineFrom>
                   <colFrom>4</colFrom>
                   <lineTo>2</lineTo>
                   <colTo>8</colTo>
                </source>""");
        context.record = 0;
        assertThat(call("record-no")).isNull();
    }

    @Test
    void stateAndLogging() {
        assertThat(call("get", "k")).isNull();
        assertThat(call("put", "k", "v")).isNull();
        assertThat(call("get", "k")).isEqualTo("v");
        final PipelineState pipelineState = new PipelineState();
        context.services.put(PipelineState.class, pipelineState);
        assertThat(call("get", "k")).isNull();
        call("put", "k", "w");
        assertThat(pipelineState.values()).containsEntry("k", "w");

        call("log", "WARN", "careful");
        call("log", "INFO", "fyi");
        call("log", "ERROR", "oops");
        call("log", "FATAL", "dead");
        call("log", "SHOUT", "loud");
        assertThat(context.all).containsExactly(
                "WARNING careful", "INFO fyi", "ERROR oops", "FATAL dead", "ERROR Unknown severity specified: SHOUT");

        assertThat(call("link", "http://x")).isEqualTo("[http://x](http://x)");
        assertThat(call("link", "here", "http://x")).isEqualTo("[here](http://x)");
        assertThat(call("link", "here", "http://x", "browser")).isEqualTo("[here](http://x){browser}");
    }

    /**
     * Phase 3 audit: {@code log} at FATAL puts a FATAL in the run's messages, which the pipeline
     * hears after the run, and the run itself goes on — a message is not a failure.
     */
    @Test
    void logAtFatalIsReportedAndTheRunGoesOn() {
        final String json = """
                {"name": "log", "version": 5,
                 "source": {"buffer_size": 4096, "ignore_errors": false, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                   "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "l"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "l",
                   "match": {"regex": {"pattern": "([^\\\\n]*)\\\\n"}},
                   "body": [{"call": {"function": "log", "select": [{"parts": [{"text": "FATAL"}]},
                                                                    {"parts": [{"capture": {"group": 1}}]}]}},
                            {"value-of": {"parts": [{"capture": {"group": 1}}]}}, {"text": "|"}]}
                 ]}
                """;
        final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        final List<stroom.shapeshifter.engine.Message> messages = stroom.shapeshifter.engine.Shapeshifter.run(
                stroom.shapeshifter.engine.Shapeshifter.compile(
                        stroom.shapeshifter.engine.config.ProjectReader.read(json),
                        stroom.shapeshifter.engine.function.FunctionRegistry.of(new LogFunction())),
                new java.io.ByteArrayInputStream("a\nb\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new XmlByteSink(output),
                stroom.shapeshifter.engine.Instrument.NONE,
                stroom.shapeshifter.engine.function.RunMode.NORMAL,
                stroom.shapeshifter.engine.function.Services.NONE);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("a|b|");
        assertThat(messages).extracting(m -> m.severity() + " " + m.text())
                .containsExactly("FATAL log: a", "FATAL log: b");
    }

    @Test
    void dictionary() {
        final WordListProvider provider = Mockito.mock(WordListProvider.class);
        final DocRef byName = new DocRef("Dictionary", "d-1", "words");
        Mockito.when(provider.findByUuid("words")).thenReturn(Optional.empty());
        Mockito.when(provider.findByName("words")).thenReturn(List.of(byName));
        Mockito.when(provider.getCombinedData(byName)).thenReturn("alpha\nbeta");
        Mockito.when(provider.findByUuid("missing")).thenReturn(Optional.empty());
        Mockito.when(provider.findByName("missing")).thenReturn(List.of());
        context.services.put(WordListProvider.class, provider);
        assertThat(call("dictionary", "words")).isEqualTo("alpha\nbeta");
        assertThat(call("dictionary", "words")).isEqualTo("alpha\nbeta");
        Mockito.verify(provider, Mockito.times(1)).getCombinedData(byName);
        assertThat(call("dictionary", "missing")).isNull();
        assertThat(context.warnings).singleElement().asString().contains("Dictionary not found with name 'missing'");
    }
}
