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
import stroom.docstore.api.DocumentResourceHelper;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.fragment.FragmentCheck;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.BuiltInTemplates;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.function.Function;

@AutoLogged
public class ShapeshifterAiResourceImpl implements ShapeshifterAiResource {

    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<Rules> rulesProvider;
    private final Provider<FragmentCheck> fragmentCheckProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    ShapeshifterAiResourceImpl(final Provider<ShapeshifterAiStore> storeProvider,
                                  final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                                  final Provider<Rules> rulesProvider,
                                  final Provider<FragmentCheck> fragmentCheckProvider,
                                  final Provider<SecurityContext> securityContextProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.rulesProvider = rulesProvider;
        this.fragmentCheckProvider = fragmentCheckProvider;
        this.securityContextProvider = securityContextProvider;
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

    /**
     * The document's rules are rows (A41), so they are asked for by document rather than read from it.
     * Reading them is reading the document: a person who may not view the document may not see what it
     * learned, since a rule names the feed it binds and the fragment that runs on it.
     */
    @Override
    public List<RoutingRule> rules(final String uuid) {
        return permitted(uuid, DocumentPermission.VIEW, rules -> rules.forDocument(uuid));
    }

    /**
     * An operator's rule goes where they put it — the top, by default, since they have just decided it is
     * the more specific — and the router takes the first match, so the position is the decision. A rule may
     * point only at a fragment (A20), which the picker cannot tell from a full pipeline, so it is checked
     * here as it was checked on save when the table was part of the document.
     */
    @Override
    public List<RoutingRule> addRule(final String uuid, final Integer at, final RoutingRule rule) {
        return permitted(uuid, DocumentPermission.EDIT, rules -> {
            checkFragment(rule);
            rules.insert(uuid, rule, at == null
                    ? rules.forDocument(uuid).size()
                    : at);
            return rules.forDocument(uuid);
        });
    }

    @Override
    public List<RoutingRule> updateRule(final String uuid, final String ruleUuid, final RoutingRule rule) {
        return permitted(uuid, DocumentPermission.EDIT, rules -> {
            if (!ruleUuid.equals(rule.getUuid())) {
                throw new EntityServiceException("The rule UUID must match the update UUID");
            }
            known(rules, uuid, ruleUuid);
            checkFragment(rule);
            rules.replace(uuid, rule);
            return rules.forDocument(uuid);
        });
    }

    @Override
    public List<RoutingRule> moveRule(final String uuid, final String ruleUuid, final int to) {
        return permitted(uuid, DocumentPermission.EDIT, rules -> {
            known(rules, uuid, ruleUuid);
            rules.move(uuid, ruleUuid, to);
            return rules.forDocument(uuid);
        });
    }

    @Override
    public List<RoutingRule> deleteRule(final String uuid, final String ruleUuid) {
        return permitted(uuid, DocumentPermission.EDIT, rules -> {
            known(rules, uuid, ruleUuid);
            rules.remove(uuid, ruleUuid);
            return rules.forDocument(uuid);
        });
    }

    /**
     * Every rule call answers with the document's whole table as it now stands, so that a client showing it
     * is showing what the server has rather than what it last sent — a rule promoted while a person had the
     * tab open is theirs to see, not theirs to overwrite.
     */
    private List<RoutingRule> permitted(final String uuid,
                                        final DocumentPermission permission,
                                        final Function<Rules, List<RoutingRule>> work) {
        final SecurityContext securityContext = securityContextProvider.get();
        final DocRef docRef = ShapeshifterAiDoc.buildDocRef().uuid(uuid).build();
        if (!securityContext.hasDocumentPermission(docRef, permission)) {
            throw new PermissionException(securityContext.getUserRef(),
                    "You do not have permission to " + permission.getDisplayValue() + " " + docRef);
        }
        return work.apply(rulesProvider.get());
    }

    private void checkFragment(final RoutingRule rule) {
        if (rule.getPipeline() != null) {
            fragmentCheckProvider.get().check(rule.getPipeline());
        }
    }

    private static void known(final Rules rules, final String uuid, final String ruleUuid) {
        if (rules.byUuid(uuid, ruleUuid).isEmpty()) {
            throw new EntityServiceException("Rule " + ruleUuid + " is not a rule of this document");
        }
    }
}
