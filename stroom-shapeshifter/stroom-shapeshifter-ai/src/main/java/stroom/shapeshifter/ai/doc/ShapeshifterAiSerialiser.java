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
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ShapeshifterAiSerialiser implements DocumentSerialiser2<ShapeshifterAiDoc> {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShapeshifterAiSerialiser.class);

    /// The asset the delegate keeps the document's JSON in.
    private static final String META = "meta";
    private static final String FLOW = "flow";
    /// The names the flow was saved under before it was named so, newest first: `plan` until A81, and
    /// `dialogue` before A37. These are names in **stored data** and so can never be renamed: a document
    /// written then has them in its JSON whatever this feature decides to call the thing today. The
    /// shared class cannot carry them, since its JSON codec is generated for the client too, so they
    /// are honoured here, on the way in only.
    private static final List<String> LEGACY_FLOW = List.of("plan", "dialogue");
    /// Where the chain question's elements were kept before A95 put them on the Chain step.
    private static final String LEGACY_ELEMENTS = "allowedElements";
    private static final String LEGACY_PRESET = "preset";
    /// Where a document's rules were kept before A41 made them rows.
    private static final String LEGACY_RULES = "routingTable";

    private final Serialiser2<ShapeshifterAiDoc> delegate;
    private final Provider<Rules> rulesProvider;
    /// The documents this node has already had the chance to migrate, so that a read is a read.
    private final Set<String> migrated = ConcurrentHashMap.newKeySet();
    /// What the read on *this thread* found, for the store to migrate on the same thread and at once.
    ///
    /// Per thread, and that is the whole of its correctness. There is one `read` for every reason a
    /// document is deserialised, and an import's confirmation screen deserialises the **incoming pack**
    /// to show what it would change — a pack whose document carries the same uuid as the local one it
    /// would replace. Held in a map keyed by uuid, the pack's rules would sit there waiting, and the
    /// next ordinary read of the *local* document would take them and write them into its table, even
    /// if the person cancelled the import.
    ///
    /// The store reads and migrates in one call on one thread ([ShapeshifterAiStoreImpl#readDocument]),
    /// so what it carried is what it drains. Every other read leaves its carry on its own thread, where
    /// the next read overwrites it and nothing ever reads it. That also ends the map's unbounded growth:
    /// a document read but never opened kept its rules for the life of the node.
    private final ThreadLocal<Carried> carried = new ThreadLocal<>();

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

    /// A read is a read: what it finds of a pre-A41 document's rules is remembered for the store to put
    /// where it belongs, and nothing is written here. An import's confirmation screen reads to show what
    /// would change, and must not write while showing it.
    @Override
    public ShapeshifterAiDoc read(final ImportExportDocument importExportDocument) throws IOException {
        final ShapeshifterAiDoc read = delegate.read(importExportDocument);
        rememberLegacyRules(read, importExportDocument);
        final ShapeshifterAiDoc document = legacyElements(read, importExportDocument);
        // A document saved when the flow was the plan (before A81) or the conversation (before A37) reads
        // as it was written, and only where an old name occurs at all.
        final byte[] meta = importExportDocument.getExtAssetData(META);
        if (document == null || meta == null) {
            return document;
        }
        final String text = new String(meta, StandardCharsets.UTF_8);
        final String name = LEGACY_FLOW.stream()
                .filter(legacyName -> text.contains('"' + legacyName + '"'))
                .findFirst()
                .orElse(null);
        if (name == null) {
            return document;
        }
        final JsonNode json = JsonUtil.getMapper().readTree(meta);
        final JsonNode legacy = json.get(name);
        if (legacy == null || legacy.isNull() || json.get(FLOW) != null) {
            return document;
        }
        LearningFlow flow = JsonUtil.getMapper().treeToValue(legacy, LearningFlow.class);
        // Before A34 the section carried a preset in place of steps; a document that chose one gets its steps.
        final JsonNode preset = legacy.get(LEGACY_PRESET);
        if (preset != null && !preset.isNull() && (legacy.get("steps") == null || legacy.get("steps").isNull())) {
            // A preset this build does not know — a renamed example, a hand-edited document — leaves the
            // steps as the default rather than making the document unopenable, and so unrepairable.
            flow = flow.withSteps(example(preset.asText()).steps());
        }
        return document.copy().flow(flow).build();
    }

    /// A document saved before A95 carried the chain question's elements as its own field: they go on its
    /// Chain step, where the step names none of its own.
    private static ShapeshifterAiDoc legacyElements(final ShapeshifterAiDoc document,
                                                    final ImportExportDocument importExportDocument)
            throws IOException {
        final byte[] meta = importExportDocument.getExtAssetData(META);
        if (document == null || meta == null
            || !new String(meta, StandardCharsets.UTF_8).contains('"' + LEGACY_ELEMENTS + '"')) {
            return document;
        }
        final JsonNode elements = JsonUtil.getMapper().readTree(meta).get(LEGACY_ELEMENTS);
        if (elements == null || !elements.isArray() || elements.isEmpty()) {
            return document;
        }
        for (final FlowStep step : document.getFlow().getSteps()) {
            if (step.getKind() == QuestionKind.CHAIN && !step.getElements().isEmpty()) {
                return document;
            }
        }
        final List<String> named = new ArrayList<>();
        elements.forEach(element -> named.add(element.asText()));
        return document.copy().allowedElements(named).build();
    }

    @Override
    public ImportExportDocument write(final ShapeshifterAiDoc document) throws IOException {
        return delegate.write(document);
    }

    /**
     * What a read found of a pre-A41 document's rules, kept until the store asks for it: every other read
     * costs one {@code contains} over bytes already in hand.
     */
    private void rememberLegacyRules(final ShapeshifterAiDoc document,
                                     final ImportExportDocument importExportDocument) throws IOException {
        if (document == null || document.getUuid() == null || migrated.contains(document.getUuid())) {
            return;
        }
        final List<RoutingRule> legacy = legacyRules(importExportDocument);
        if (legacy.isEmpty()) {
            // Cleared and not left: what this thread carried from the read before must not be taken for
            // this document's.
            carried.remove();
        } else {
            carried.set(new Carried(document.getUuid(), legacy));
        }
    }

    /**
     * A document that learned before A41 carried its rules; they are rows now, so the store's first read of
     * such a document puts them where they belong — once per node, and only where the rows have none, so a
     * table an operator has since emptied is theirs and not the old document's to refill. The A26 module
     * will do this as a migration of its own, over every document, rather than on the read of each.
     */
    public void migrateRules(final ShapeshifterAiDoc document) {
        if (document == null || document.getUuid() == null) {
            return;
        }
        final Carried found = carried.get();
        carried.remove();
        if (found == null || !found.docUuid().equals(document.getUuid())
            || !migrated.add(document.getUuid())) {
            return;
        }
        final List<RoutingRule> legacy = found.rules();
        final Rules rules = rulesProvider.get();
        if (!rules.forDocument(document.getUuid()).isEmpty()) {
            return;
        }
        legacy.forEach(rule -> rules.append(document.getUuid(), rule));
        LOGGER.info(() -> LogUtil.message("Document {} carried {} routing rule(s) from before they were rows; "
                                          + "they are rows now (A41)", document.getUuid(), legacy.size()));
    }

    private static FlowExample example(final String name) {
        for (final FlowExample example : FlowExample.values()) {
            if (example.name().equalsIgnoreCase(name)) {
                return example;
            }
        }
        LOGGER.warn(() -> LogUtil.message("Flow example '{}' is not one this build knows: {}. The document's "
                                          + "steps are the default flow's until it is saved again.",
                name, Arrays.toString(FlowExample.values())));
        return FlowExample.DIRECT;
    }


    // --------------------------------------------------------------------------------


    /// The rules one read found, and which document they were read for. The uuid is checked on the way
    /// out as well as in: a thread that read one document and migrated another would otherwise carry
    /// the first's rules into the second.
    private record Carried(String docUuid, List<RoutingRule> rules) {

    }
}
