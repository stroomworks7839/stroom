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

package stroom.shapeshifter.pipeline;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.PatternNode;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.config.Severity;
import stroom.shapeshifter.config.Template.RegexFlags;
import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.ProjectJson;
import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.PatternExplode;
import stroom.shapeshifter.engine.PatternInfo;
import stroom.shapeshifter.engine.PatternPrint;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.TraceRecorder;
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.engine.output.XmlByteSink;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.shapeshifter.shared.ShapeshifterLibrary;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterPreviewRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.shapeshifter.shared.ShapeshifterText;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterValidation;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@AutoLogged
class ShapeshifterResourceImpl implements ShapeshifterResource {

    private final Provider<ShapeshifterStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<StroomFunctionLibrary> functionLibraryProvider;
    private final Provider<StreamSamples> streamSamplesProvider;

    @Inject
    ShapeshifterResourceImpl(final Provider<ShapeshifterStore> storeProvider,
                             final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                             final Provider<StroomFunctionLibrary> functionLibraryProvider,
                             final Provider<StreamSamples> streamSamplesProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.functionLibraryProvider = functionLibraryProvider;
        this.streamSamplesProvider = streamSamplesProvider;
    }

    @Override
    public ShapeshifterDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(storeProvider.get(), ShapeshifterDoc.getDocRef(uuid));
    }

    @Override
    public ShapeshifterDoc update(final String uuid, final ShapeshifterDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(storeProvider.get(), doc);
    }

    @Override
    public ShapeshifterDoc create(final String name) {
        final ShapeshifterStore store = storeProvider.get();
        final DocRef docRef = store.createDocument(name);
        return store.readDocument(docRef);
    }

    // The editor's questions (design 43 §5): pure functions of their input, over the engine's own
    // reader, compiler and pattern tools, with Stroom's functions registered so that a call to
    // one of them validates as it will run. None of them touches a document, so none is logged.

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public ShapeshifterValidation validate(final String projectText) {
        final List<ShapeshifterMessage> messages = new ArrayList<>();
        final Project project;
        try {
            project = ProjectReader.read(projectText);
        } catch (final ConfigException e) {
            messages.add(message(Severity.FATAL, e.getMessage()));
            return new ShapeshifterValidation(false, messages, null);
        }
        final String canonical = ProjectReader.writePretty(project);
        try {
            final CompiledProject compiled = Shapeshifter.compile(project, functionLibraryProvider.get().registry());
            for (final Message warning : compiled.warnings()) {
                messages.add(message(warning.severity(), warning.text()));
            }
            return new ShapeshifterValidation(true, messages, canonical);
        } catch (final ConfigException e) {
            messages.add(message(Severity.FATAL, e.getMessage()));
            return new ShapeshifterValidation(false, messages, canonical);
        }
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public ShapeshifterPatternInfo patternInfo(final ShapeshifterPatternRequest request) {
        final PatternInfo info = PatternInfo.inspect(request.getPattern());
        final List<ShapeshifterPatternInfo.Group> groups = info.groups().stream()
                .map(group -> new ShapeshifterPatternInfo.Group(group.index(), group.name(), group.start(),
                        group.end()))
                .toList();
        final String explain = info.valid() ? PatternInfo.explain(request.getPattern(), flags(request)) : null;
        return new ShapeshifterPatternInfo(info.valid(), info.error(), groups, explain);
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public ShapeshifterText explode(final ShapeshifterPatternRequest request) {
        final PatternNode tree = PatternExplode.explode(request.getPattern(), flags(request));
        return new ShapeshifterText(JsonText.printPretty(ProjectJson.writePatternNode(tree)));
    }

    /**
     * One record, run whole, with a recorder listening (design 43 §5): the trace the navigator
     * reads. The sample is supplied and never kept; the run is the engine's ordinary run with an
     * instrument, so what the trace shows is what production does (D35's decoration rule).
     */
    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public ShapeshifterTrace preview(final ShapeshifterPreviewRequest request) {
        final List<ShapeshifterMessage> messages = new ArrayList<>();
        final StreamSamples.Sample sample;
        try {
            sample = sampleOf(request);
        } catch (final RuntimeException e) {
            // The record could not be read - gone, or not this caller's to see. The editor shows
            // it as a message rather than an error page, since the project may still be sound.
            return new ShapeshifterTrace(false, false, "", null, List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), 0, List.of(),
                    List.of(message(Severity.FATAL, "The sample could not be read: " + e.getMessage())), 0);
        }
        final byte[] input = sample.bytes();
        if (sample.truncated()) {
            messages.add(message(Severity.WARNING, "The sample is the first " + input.length
                                                   + " bytes of the record, which holds more"));
        }
        // Read first, so the input can be shown in the encoding the project declares for it
        // whether or not it then compiles
        Project project = null;
        CompiledProject compiled = null;
        try {
            project = ProjectReader.read(request.getProject());
            // The compile's warnings are the run's first messages: the run says them again.
            compiled = Shapeshifter.compile(project, functionLibraryProvider.get().registry());
        } catch (final ConfigException e) {
            messages.add(message(Severity.FATAL, e.getMessage()));
        }
        final ShownText shown = ShownText.input(input, project == null || project.source() == null
                ? null
                : project.source().encoding());
        if (compiled == null) {
            return new ShapeshifterTrace(false, true, shown.text(input), null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), 0, List.of(), messages, 0,
                    shown.charset().name());
        }
        final TraceRecorder recorder = new TraceRecorder();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final long started = System.nanoTime();
        Shapeshifter.runWhole(compiled, input, new XmlByteSink(out), recorder);
        final long runNanos = System.nanoTime() - started;
        // Every message of the run, in order, with the frame it was said in.
        for (final TraceRecorder.Said said : recorder.said()) {
            messages.add(new ShapeshifterMessage(said.message().severity().name(), said.message().text(),
                    said.frameId()));
        }
        // The client holds strings: every offset crosses the wire in characters (TraceChars).
        final TraceChars chars = new TraceChars(recorder, input, shown, out.toByteArray());
        return new ShapeshifterTrace(true, true, chars.input(), chars.output(),
                chars.frames(),
                chars.captures(), chars.groups(), chars.outputs(), chars.attempts(), chars.guards(),
                chars.instructions(),
                recorder.attemptsSeen(),
                timings(recorder), messages, runNanos, shown.charset().name());
    }

    /**
     * The bytes to run over: the text the caller sent, as UTF-8, or the record it named, read as
     * it is stored, under the caller's own permissions (design 18 Q2 - the document never holds
     * data, so the editor asks for it by location every run; design 46 §6.5 - a binary record
     * reaches the engine as its bytes, not as text decoded and re-encoded).
     */
    private StreamSamples.Sample sampleOf(final ShapeshifterPreviewRequest request) {
        if (request.getSample() != null) {
            // In the charset it was shown in, where it came from a trace: a binary record's text
            // is its bytes a byte to a character, and encoded as UTF-8 would be other bytes
            final String text = request.getSample();
            return new StreamSamples.Sample(text.getBytes(ShownText.ofSample(request.getSampleEncoding(), text)),
                    false);
        }
        if (request.getSourceLocation() == null) {
            return new StreamSamples.Sample(new byte[0], false);
        }
        return streamSamplesProvider.get().read(request.getSourceLocation());
    }

    private static List<ShapeshifterTrace.Timing> timings(final TraceRecorder recorder) {
        final List<ShapeshifterTrace.Timing> timings = new ArrayList<>();
        for (final Map.Entry<String, TraceRecorder.Timing> e : recorder.timings().entrySet()) {
            timings.add(new ShapeshifterTrace.Timing(e.getKey(), e.getValue().attempts(), e.getValue().matched(),
                    e.getValue().nanos()));
        }
        return timings;
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public ShapeshifterLibrary library() {
        final List<ShapeshifterLibrary.Entry> entries = new ArrayList<>();
        for (final Map.Entry<String, String> entry : PatternPrint.library().entrySet()) {
            entries.add(new ShapeshifterLibrary.Entry(entry.getKey(), entry.getValue()));
        }
        return new ShapeshifterLibrary(entries);
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public List<String> encodings() {
        // The engine's vocabulary, in its order, less what this build has no charset for - the
        // compiler refuses those by name, so the forms should not offer them.
        final List<String> labels = new ArrayList<>();
        for (final Encoding encoding : Encoding.values()) {
            if (encoding.isAvailable()) {
                labels.add(encoding.label());
            }
        }
        return labels;
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public ShapeshifterText print(final ShapeshifterPatternRequest request) {
        final PatternNode tree = ProjectJson.readPatternNode(JsonText.parse(request.getPattern()));
        final Map<String, PatternNode> patterns = request.getPatterns() == null
                ? Map.of()
                : ProjectJson.readPatterns(JsonText.parse(request.getPatterns()));
        return new ShapeshifterText(PatternPrint.print(tree, flags(request), patterns));
    }

    private static RegexFlags flags(final ShapeshifterPatternRequest request) {
        return new RegexFlags(request.isCaseInsensitive(), request.isDotAll());
    }

    private static ShapeshifterMessage message(final Severity severity, final String text) {
        return new ShapeshifterMessage(severity.name(), text);
    }
}
