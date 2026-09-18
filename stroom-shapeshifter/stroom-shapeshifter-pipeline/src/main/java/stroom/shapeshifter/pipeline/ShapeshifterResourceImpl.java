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
import stroom.shapeshifter.engine.graph.CompiledProject;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterResource;
import stroom.shapeshifter.shared.ShapeshifterText;
import stroom.shapeshifter.shared.ShapeshifterValidation;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.List;

@AutoLogged
class ShapeshifterResourceImpl implements ShapeshifterResource {

    private final Provider<ShapeshifterStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<StroomFunctionLibrary> functionLibraryProvider;

    @Inject
    ShapeshifterResourceImpl(final Provider<ShapeshifterStore> storeProvider,
                             final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                             final Provider<StroomFunctionLibrary> functionLibraryProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.functionLibraryProvider = functionLibraryProvider;
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
                .map(group -> new ShapeshifterPatternInfo.Group(group.index(), group.name()))
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

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public ShapeshifterText print(final ShapeshifterPatternRequest request) {
        final PatternNode tree = ProjectJson.readPatternNode(JsonText.parse(request.getPattern()));
        return new ShapeshifterText(PatternPrint.print(tree, flags(request)));
    }

    private static RegexFlags flags(final ShapeshifterPatternRequest request) {
        return new RegexFlags(request.isCaseInsensitive(), request.isDotAll());
    }

    private static ShapeshifterMessage message(final Severity severity, final String text) {
        return new ShapeshifterMessage(severity.name(), text);
    }
}
