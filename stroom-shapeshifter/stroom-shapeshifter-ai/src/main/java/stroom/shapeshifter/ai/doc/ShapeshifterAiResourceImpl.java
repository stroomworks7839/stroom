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
import stroom.pipeline.factory.ElementRegistry;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.element.StageFactory;
import stroom.shapeshifter.ai.fragment.FragmentCheck;
import stroom.shapeshifter.ai.fragment.ReplayUnits;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.shared.BuiltInTemplates;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ReplayUnit;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

@AutoLogged
public class ShapeshifterAiResourceImpl implements ShapeshifterAiResource {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShapeshifterAiResourceImpl.class);

    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<DocumentResourceHelper> documentResourceHelperProvider;
    private final Provider<Rules> rulesProvider;
    private final Provider<FragmentCheck> fragmentCheckProvider;
    private final Provider<ElementRegistryFactory> elementRegistryFactoryProvider;
    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<StageFactory> stageFactoryProvider;
    private final Provider<PipelineScopeRunnable> pipelineScopeProvider;

    @Inject
    ShapeshifterAiResourceImpl(final Provider<ShapeshifterAiStore> storeProvider,
                                  final Provider<DocumentResourceHelper> documentResourceHelperProvider,
                                  final Provider<Rules> rulesProvider,
                                  final Provider<FragmentCheck> fragmentCheckProvider,
                                  final Provider<ElementRegistryFactory> elementRegistryFactoryProvider,
                                  final Provider<SecurityContext> securityContextProvider,
                                  final Provider<StageFactory> stageFactoryProvider,
                                  final Provider<PipelineScopeRunnable> pipelineScopeProvider) {
        this.storeProvider = storeProvider;
        this.documentResourceHelperProvider = documentResourceHelperProvider;
        this.rulesProvider = rulesProvider;
        this.fragmentCheckProvider = fragmentCheckProvider;
        this.elementRegistryFactoryProvider = elementRegistryFactoryProvider;
        this.securityContextProvider = securityContextProvider;
        this.stageFactoryProvider = stageFactoryProvider;
        this.pipelineScopeProvider = pipelineScopeProvider;
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
            checkFragment(uuid, rule);
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
            checkFragment(uuid, rule);
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

    /// Approve a draft (A25, §11.3), which is the promotion it has been waiting for: the rule goes live
    /// — or, where it came of relearning, the incumbent is rebound to its fragment and keeps its history
    /// — and the shape's ledger is released as a reprocess request, so that nothing which arrived while
    /// it waited is lost.
    ///
    /// The same act is offered in the Supervisor view beside the attempt that drafted it (A28), and both
    /// go to the same place: [stroom.shapeshifter.ai.stage.Stage#approve].
    @Override
    public List<RoutingRule> approveRule(final String uuid, final String ruleUuid) {
        return permitted(uuid, DocumentPermission.EDIT, rules -> {
            known(rules, uuid, ruleUuid);
            onStage(uuid, stage -> stage::approve, ruleUuid, null);
            return rules.forDocument(uuid);
        });
    }

    /// Reject a draft (A25, §11.3): the rule goes with its records, and the shape is given up with the
    /// reason, so that the model is not asked the same question again until somebody says otherwise.
    ///
    /// A rejection says why. Without a reason the shape would be given up over a blank, and the person
    /// who finds it given up a month from now would have nothing to read.
    @Override
    public List<RoutingRule> rejectRule(final String uuid, final String ruleUuid, final RejectRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("A rejection says why: the shape is given up with the reason");
        }
        return permitted(uuid, DocumentPermission.EDIT, rules -> {
            known(rules, uuid, ruleUuid);
            onStage(uuid, stage -> (doc, rule) -> stage.reject(doc, rule, request.getReason()), ruleUuid,
                    request.getReason());
            return rules.forDocument(uuid);
        });
    }

    /// In a pipeline scope, though there is no pipeline: what a stage is built from reports through the
    /// scoped error receiver, as it does for the deferred worker and for the Supervisor's own actions.
    private void onStage(final String uuid,
                         final Function<Stage, OnRule> action,
                         final String ruleUuid,
                         final String reason) {
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                ShapeshifterAiDoc.buildDocRef().uuid(uuid).build());
        if (doc == null) {
            throw new IllegalStateException("Shapeshifter AI document " + uuid + " has been deleted");
        }
        pipelineScopeProvider.get().scopeRunnable(() ->
                action.apply(stageFactoryProvider.get().create()).run(doc, ruleUuid));
        // After it happened, not before. The stage refuses a draft another operator has already decided,
        // and one whose incumbent is pinned; a line written first would record an approval that never
        // took effect, in the one log somebody reads to find out who decided what.
        LOGGER.info(() -> LogUtil.message("Shapeshifter AI document {}: rule {} {}", uuid, ruleUuid,
                reason == null
                        ? "approved"
                        : "rejected: " + reason));
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

    /// A rule's fragment must be a fragment (A20) and must be replayable over what this stage is given
    /// (A1, §4): a document whose stage is fed by the source learns chains that parse, and one fed by a
    /// parser learns chains that do not. The document's allowed elements are what say which, since they
    /// are what a chain is chosen from.
    ///
    /// Both units are derived the same way, from the element registry, because a disagreement between
    /// the two would refuse a rule that is perfectly good. A document naming no allowed elements
    /// constrains nothing and is left alone.
    private void checkFragment(final String uuid, final RoutingRule rule) {
        if (rule.getPipeline() == null) {
            return;
        }
        final ReplayUnit variant = fragmentCheckProvider.get().check(rule.getPipeline());
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                ShapeshifterAiDoc.buildDocRef().uuid(uuid).build());
        if (doc == null || NullSafe.isEmptyCollection(doc.getAllowedElements())) {
            return;
        }
        final ElementRegistry registry = elementRegistryFactoryProvider.get().get();
        final ReplayUnit stage = ReplayUnits.ofElements(doc.getAllowedElements(), type -> {
            final PipelineElementType elementType = registry.getElementType(type);
            return elementType != null && elementType.hasRole(PipelineElementType.ROLE_PARSER);
        });
        if (variant != stage) {
            throw new EntityServiceException(ReplayUnits.mismatch(variant,
                    "Pipeline " + rule.getPipeline().getName()));
        }
    }

    private static void known(final Rules rules, final String uuid, final String ruleUuid) {
        if (rules.byUuid(uuid, ruleUuid).isEmpty()) {
            throw new EntityServiceException("Rule " + ruleUuid + " is not a rule of this document");
        }
    }


    // --------------------------------------------------------------------------------


    /// What a person's decision does to one rule of one document, so that approving and rejecting take
    /// the same path to the stage.
    @FunctionalInterface
    private interface OnRule {

        void run(ShapeshifterAiDoc doc, String ruleUuid);
    }
}
