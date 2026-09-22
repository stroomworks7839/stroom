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

import stroom.docref.DocRef;
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class ShapeshifterAiStoreImpl
        extends AbstractDocumentStore<ShapeshifterAiDoc>
        implements ShapeshifterAiStore {

    private final ShapeshifterAiSerialiser serialiser;

    @Inject
    public ShapeshifterAiStoreImpl(final StoreFactory storeFactory,
                                      final SecurityContext securityContext,
                                      final ShapeshifterAiSerialiser serialiser) {
        super(storeFactory,
                securityContext,
                serialiser,
                ShapeshifterAiDoc.TYPE,
                ShapeshifterAiDoc::builder,
                ShapeshifterAiDoc::copy);
        this.serialiser = serialiser;
    }

    /**
     * A document that learned before A41 carried its rules, and they are rows now: reading it here — a read
     * of what this node holds, not an import's look at what a pack would change — is where they are put
     * where they belong (design 02 §6.1).
     */
    @Override
    public ShapeshifterAiDoc readDocument(final DocRef docRef) {
        final ShapeshifterAiDoc document = super.readDocument(docRef);
        serialiser.migrateRules(document);
        return document;
    }

    /**
     * What the editor cannot check is checked on save, with the fault named, rather than at run time by the
     * supervisor: the learning key may name only fields a selector can be written against (A29), and the
     * plan must be one the stage can hold (A33). The rules are rows since A41, so the resource checks
     * their fragments (A20) where this once did.
     */
    @Override
    public ShapeshifterAiDoc writeDocument(final ShapeshifterAiDoc document) {
        final List<String> unknown = document.getLearningKey().stream()
                .filter(field -> !RoutingFields.NAMES.contains(field))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("The learning key names fields a selector cannot match: "
                                               + unknown + ". Choose from " + RoutingFields.NAMES);
        }
        if (document.getLearningKey().isEmpty()) {
            throw new IllegalArgumentException("The learning key must name at least one field");
        }
        // A33: a plan the stage cannot hold is refused here, naming the rule, rather than found when
        // the first stream arrives; and the document is stamped with the built-in text it was saved against.
        final List<String> problems = new ArrayList<>(document.getPlan().problems());
        problems.addAll(Templates.problems(document.getPlan()));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The plan cannot be held: " + String.join("; ", problems));
        }
        return super.writeDocument(document.copy()
                .plan(document.getPlan().withBuiltInVersion(Templates.VERSION))
                .build());
    }

    /**
     * A Shapeshifter AI document depends on its model document, and on nothing else: since A41 the routing
     * table is rows and the fragments are named by them, not by the document, so an exported document
     * carries its configuration and none of what it learned — as a processor filter's state is not part of
     * the pipeline it runs. Declaring the model here is what makes a content-pack import re-point it at the
     * imported copy, what lets a copied document follow it, and what draws the edge in the explorer's
     * dependency view; the inherited null would silently disable all three.
     */
    @Override
    protected DependencyRemapFunction<ShapeshifterAiDoc> getDependencyRemapFunction() {
        return (doc, remapper) -> {
            final ShapeshifterAiDoc.Builder builder = doc.copy();
            if (doc.getModel() != null) {
                builder.model(remapper.remap(doc.getModel()));
            }
            return builder.build();
        };
    }
}
