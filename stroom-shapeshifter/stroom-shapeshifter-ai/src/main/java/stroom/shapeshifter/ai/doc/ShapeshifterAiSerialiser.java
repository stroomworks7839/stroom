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

package stroom.shapeshifter.ai.doc;

import stroom.docstore.api.DocumentSerialiser2;
import stroom.docstore.api.Serialiser2;
import stroom.docstore.api.Serialiser2Factory;
import stroom.importexport.api.ImportExportDocument;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.json.JsonUtil;

import jakarta.inject.Inject;
import tools.jackson.databind.JsonNode;

import java.io.IOException;

public class ShapeshifterAiSerialiser implements DocumentSerialiser2<ShapeshifterAiDoc> {

    /// The asset the delegate keeps the document's JSON in.
    private static final String META = "meta";
    private static final String PLAN = "plan";
    private static final String LEGACY_PLAN = "dialogue";

    private final Serialiser2<ShapeshifterAiDoc> delegate;

    @Inject
    ShapeshifterAiSerialiser(final Serialiser2Factory serialiser2Factory) {
        this.delegate = serialiser2Factory.createSerialiser(ShapeshifterAiDoc.class);
    }

    @Override
    public ShapeshifterAiDoc read(final ImportExportDocument importExportDocument) throws IOException {
        final ShapeshifterAiDoc document = delegate.read(importExportDocument);
        // A document saved when the plan was called the dialogue (before A37) reads as it was written. The
        // shared class cannot carry the old name — its JSON is generated for the client too — so it is
        // honoured here, on the way in only.
        final JsonNode json = JsonUtil.getMapper().readTree(importExportDocument.getExtAssetData(META));
        final JsonNode legacy = json.get(LEGACY_PLAN);
        if (legacy != null && !legacy.isNull() && json.get(PLAN) == null) {
            return document.copy().plan(JsonUtil.getMapper().treeToValue(legacy, LearningPlan.class)).build();
        }
        return document;
    }

    @Override
    public ImportExportDocument write(final ShapeshifterAiDoc document) throws IOException {
        return delegate.write(document);
    }
}
