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

import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.shapeshifter.ai.fragment.FragmentCheck;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.BuiltInTemplates;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@AutoLogged
public class ShapeshifterAiResourceImpl implements ShapeshifterAiResource {

    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<Rules> rulesProvider;
    private final Provider<FragmentCheck> fragmentCheckProvider;

    @Inject
    ShapeshifterAiResourceImpl(final Provider<ShapeshifterAiStore> storeProvider,
                                  final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                                  final Provider<Rules> rulesProvider,
                                  final Provider<FragmentCheck> fragmentCheckProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.rulesProvider = rulesProvider;
        this.fragmentCheckProvider = fragmentCheckProvider;
    }

    @Override
    public ShapeshifterAiDoc fetch(final String uuid) {
        return documentResourceHelperProvider.get().read(
                storeProvider.get(),
                ShapeshifterAiDoc.buildDocRef().uuid(uuid).build());
    }

    @Override
    public BuiltInTemplates templates() {
        return new BuiltInTemplates(Templates.VERSION, Templates.builtIns());
    }

    @Override
    public ShapeshifterAiDoc update(final String uuid, final ShapeshifterAiDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        return documentResourceHelperProvider.get().update(storeProvider.get(), doc);
    }

    @Override
    public List<RoutingRule> rules(final String uuid) {
        return rulesProvider.get().forDocument(uuid);
    }

    /**
     * The rules an operator edited on the Routing tab: what they sent becomes the document's table, in
     * their order. A rule may point only at a fragment (A20), which the picker cannot tell from a full
     * pipeline, so it is checked here as it was checked on save when the table was part of the document.
     * Rules the operator removed go; those they added are given a uuid.
     */
    @Override
    public List<RoutingRule> updateRules(final String uuid, final List<RoutingRule> rules) {
        final FragmentCheck check = fragmentCheckProvider.get();
        rules.stream()
                .map(RoutingRule::getPipeline)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(check::check);
        final Rules store = rulesProvider.get();
        final Set<String> keeping = rules.stream()
                .map(RoutingRule::getUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        store.forDocument(uuid).stream()
                .map(RoutingRule::getUuid)
                .filter(had -> !keeping.contains(had))
                .toList()
                .forEach(gone -> store.remove(uuid, gone));
        for (final RoutingRule rule : rules) {
            if (rule.getUuid() == null) {
                store.append(uuid, rule);
            } else {
                store.replace(uuid, rule);
            }
        }
        return store.forDocument(uuid);
    }
}
