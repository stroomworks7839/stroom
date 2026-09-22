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
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.json.JsonUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ShapeshifterAiSerialiser implements DocumentSerialiser2<ShapeshifterAiDoc> {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShapeshifterAiSerialiser.class);

    /// The asset the delegate keeps the document's JSON in.
    private static final String META = "meta";
    private static final String PLAN = "plan";
    private static final String LEGACY_PLAN = "dialogue";
    private static final String LEGACY_PRESET = "preset";
    /// Where a document's rules were kept before A41 made them rows.
    private static final String LEGACY_RULES = "routingTable";

    private final Serialiser2<ShapeshifterAiDoc> delegate;
    private final Provider<Rules> rulesProvider;

    @Inject
    ShapeshifterAiSerialiser(final Serialiser2Factory serialiser2Factory, final Provider<Rules> rulesProvider) {
        this.delegate = serialiser2Factory.createSerialiser(ShapeshifterAiDoc.class);
        this.rulesProvider = rulesProvider;
    }

    /// The rules a document carried before A41, in their order, or empty where it carried none: what the
    /// store hands to [stroom.shapeshifter.ai.stage.Rules] the first time such a document is read, so that a
    /// feed that has already learned is not learned again.
    public static List<RoutingRule> legacyRules(final ImportExportDocument importExportDocument)
            throws IOException {
        final byte[] meta = importExportDocument.getExtAssetData(META);
        if (meta == null || !new String(meta, StandardCharsets.UTF_8).contains('"' + LEGACY_RULES + '"')) {
            return List.of();
        }
        final JsonNode rules = JsonUtil.getMapper().readTree(meta).get(LEGACY_RULES);
        if (rules == null || !rules.isArray()) {
            return List.of();
        }
        final List<RoutingRule> read = new ArrayList<>();
        for (final JsonNode rule : rules) {
            read.add(JsonUtil.getMapper().treeToValue(rule, RoutingRule.class));
        }
        return List.copyOf(read);
    }

    @Override
    public ShapeshifterAiDoc read(final ImportExportDocument importExportDocument) throws IOException {
        final ShapeshifterAiDoc document = delegate.read(importExportDocument);
        migrateRules(document, importExportDocument);
        // A document saved when the plan was called the dialogue (before A37) reads as it was written. The
        // shared class cannot carry the old name — its JSON is generated for the client too — so it is
        // honoured here, on the way in only, and only where the old name occurs at all.
        final byte[] meta = importExportDocument.getExtAssetData(META);
        if (document == null || meta == null
            || !new String(meta, StandardCharsets.UTF_8).contains('"' + LEGACY_PLAN + '"')) {
            return document;
        }
        final JsonNode json = JsonUtil.getMapper().readTree(meta);
        final JsonNode legacy = json.get(LEGACY_PLAN);
        if (legacy == null || legacy.isNull() || json.get(PLAN) != null) {
            return document;
        }
        LearningPlan plan = JsonUtil.getMapper().treeToValue(legacy, LearningPlan.class);
        // Before A34 the section carried a preset in place of steps; a document that chose one gets its steps.
        final JsonNode preset = legacy.get(LEGACY_PRESET);
        if (preset != null && !preset.isNull() && (legacy.get("steps") == null || legacy.get("steps").isNull())) {
            // A preset this build does not know — a renamed example, a hand-edited document — leaves the
            // steps as the default rather than making the document unopenable, and so unrepairable.
            plan = plan.withSteps(example(preset.asText()).steps());
        }
        return document.copy().plan(plan).build();
    }

    @Override
    public ImportExportDocument write(final ShapeshifterAiDoc document) throws IOException {
        return delegate.write(document);
    }

    /**
     * A document that learned before A41 carried its rules; they are rows now, so the first read of such a
     * document puts them where they belong — once, and only where the document has some and the rows have
     * none, so a table an operator has since edited is not overwritten by the document's old copy. Every
     * other read costs one {@code contains} over bytes already in hand. The A26 module will do this as a
     * migration of its own, over every document, rather than on the read of each.
     */
    private void migrateRules(final ShapeshifterAiDoc document, final ImportExportDocument importExportDocument)
            throws IOException {
        if (document == null || document.getUuid() == null) {
            return;
        }
        final List<RoutingRule> legacy = legacyRules(importExportDocument);
        if (legacy.isEmpty()) {
            return;
        }
        final Rules rules = rulesProvider.get();
        if (!rules.forDocument(document.getUuid()).isEmpty()) {
            return;
        }
        legacy.forEach(rule -> rules.append(document.getUuid(), rule));
        LOGGER.info(() -> LogUtil.message("Document {} carried {} routing rule(s) from before they were rows; "
                                          + "they are rows now (A41)", document.getUuid(), legacy.size()));
    }

    private static PlanExample example(final String name) {
        for (final PlanExample example : PlanExample.values()) {
            if (example.name().equalsIgnoreCase(name)) {
                return example;
            }
        }
        LOGGER.warn(() -> LogUtil.message("Plan example '{}' is not one this build knows: {}. The document's "
                                          + "steps are the default plan's until it is saved again.",
                name, Arrays.toString(PlanExample.values())));
        return PlanExample.DIRECT;
    }
}
