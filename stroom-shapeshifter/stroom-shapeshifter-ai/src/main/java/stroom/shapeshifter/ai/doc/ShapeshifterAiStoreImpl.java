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

import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.fragment.FragmentCheck;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Singleton
public class ShapeshifterAiStoreImpl
        extends AbstractDocumentStore<ShapeshifterAiDoc>
        implements ShapeshifterAiStore {

    private final FragmentCheck fragmentCheck;

    @Inject
    public ShapeshifterAiStoreImpl(final StoreFactory storeFactory,
                                      final SecurityContext securityContext,
                                      final ShapeshifterAiSerialiser serialiser,
                                      final FragmentCheck fragmentCheck) {
        super(storeFactory,
                securityContext,
                serialiser,
                ShapeshifterAiDoc.TYPE,
                ShapeshifterAiDoc::builder,
                ShapeshifterAiDoc::copy);
        this.fragmentCheck = fragmentCheck;
    }

    /**
     * What the editor cannot check is checked on save, with the fault named, rather than at run time by
     * the supervisor: a rule may point only at a fragment (A20) — the picker cannot tell a fragment from a
     * full pipeline — and the learning key may name only fields a selector can be written against (A29).
     * A rule saved without a {@code uuid} is given one here, so that the runtime state of A26 can name
     * every rule stably; the client never generates one.
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
