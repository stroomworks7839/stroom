/*
 * Copyright 2026 Crown Copyright
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
import stroom.event.logging.rs.api.AutoLogged;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.element.StageFactory;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Page;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.shared.AmendTurnRequest;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.RejectAttemptRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.PageResponse;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.function.Consumer;

/// The Supervisor of A28: attempts across every Shapeshifter AI document, and what a person may do to
/// one they are reading.
///
/// Every attempt names the document it was learning for, and a person sees only the attempts of the
/// documents they may see: an attempt's transcript carries what the model was told about a feed's data,
/// so it is read by the document's permission and not by a permission of its own.
@AutoLogged
public class SupervisorResourceImpl implements SupervisorResource {

    private final Provider<Attempts> attemptsProvider;
    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<StageFactory> stageFactoryProvider;
    private final Provider<PipelineScopeRunnable> pipelineScopeProvider;
    private final Provider<SecurityContext> securityContextProvider;

    @Inject
    SupervisorResourceImpl(final Provider<Attempts> attemptsProvider,
                           final Provider<ShapeshifterAiStore> storeProvider,
                           final Provider<StageFactory> stageFactoryProvider,
                           final Provider<PipelineScopeRunnable> pipelineScopeProvider,
                           final Provider<SecurityContext> securityContextProvider) {
        this.attemptsProvider = attemptsProvider;
        this.storeProvider = storeProvider;
        this.stageFactoryProvider = stageFactoryProvider;
        this.pipelineScopeProvider = pipelineScopeProvider;
        this.securityContextProvider = securityContextProvider;
    }

    @Override
    public ResultPage<SupervisorAttempt> find(final AttemptCriteria criteria) {
        final AttemptCriteria asked = criteria == null
                ? new AttemptCriteria()
                : criteria;
        // The documents this person may see go into the query rather than filtering its answer: a count
        // taken before the filtering would tell them how many attempts exist on documents they may not
        // see, and the pages they turned would come back short with no way to reach the end.
        final Page page = attemptsProvider.get().found(asked, mayRead(asked.getDocUuid()));
        final List<SupervisorAttempt> rows = page.attempts().stream()
                .map(SupervisorResourceImpl::summary)
                .toList();
        final long offset = asked.getPageRequest() == null || asked.getPageRequest().getOffset() == null
                ? 0L
                : asked.getPageRequest().getOffset();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), page.total(), true));
    }

    @Override
    public SupervisorAttempt fetch(final long id) {
        return detail(read(id));
    }

    @Override
    public SupervisorAttempt amend(final long id, final int number, final AmendTurnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("No answer was given for turn " + number);
        }
        final Recorded attempt = change(id);
        final String answeredBy = securityContextProvider.get().getUserIdentityForAudit();
        onStage(attempt, (stage, doc) -> stage.amend(doc, id, number, request.getAnswer(), answeredBy));
        return detail(read(id));
    }

    @Override
    public SupervisorAttempt approve(final long id) {
        final Recorded attempt = change(id);
        onStage(attempt, (stage, doc) -> stage.approve(doc, ruleOf(attempt)));
        return detail(read(id));
    }

    @Override
    public SupervisorAttempt reject(final long id, final RejectAttemptRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("A rejection says why: the shape is given up with the reason");
        }
        final Recorded attempt = change(id);
        onStage(attempt, (stage, doc) -> stage.reject(doc, ruleOf(attempt), request.getReason()));
        return detail(read(id));
    }

    @Override
    public SupervisorAttempt relearn(final long id) {
        final Recorded attempt = change(id);
        final String who = securityContextProvider.get().getUserIdentityForAudit();
        onStage(attempt, (stage, doc) -> stage.relearn(doc, attempt.attempt().shape(),
                "Sent back to be learned again by " + who));
        return detail(read(id));
    }

    /// An attempt a person may read. An attempt they may not see and an attempt that never existed
    /// answer the same way, or the answer itself says which documents exist and what has been learned
    /// for them.
    private Recorded read(final long id) {
        return permitted(id, DocumentPermission.VIEW);
    }

    /// An attempt a person may change. Approving a draft binds a rule, rejecting one gives a shape up,
    /// and running an attempt again spends a model's tokens: these are edits of the document's routing
    /// and are held to the same permission as editing its routing table by hand.
    private Recorded change(final long id) {
        return permitted(id, DocumentPermission.EDIT);
    }

    private Recorded permitted(final long id, final DocumentPermission permission) {
        final Recorded attempt = attemptsProvider.get().byId(id).orElse(null);
        if (attempt == null || !may(attempt.attempt().docUuid(), permission)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read attempt " + id);
        }
        return attempt;
    }

    private boolean may(final String docUuid, final DocumentPermission permission) {
        return securityContextProvider.get().hasDocumentPermission(
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid), permission);
    }

    /// The documents this person may see, narrowed to the one they asked about if they asked about one.
    /// A handful of documents per installation, so asking the store about each costs nothing next to the
    /// query it saves.
    private List<String> mayRead(final String asked) {
        return storeProvider.get().listDocuments().stream()
                .map(DocRef::getUuid)
                .filter(uuid -> asked == null || asked.equals(uuid))
                .filter(uuid -> may(uuid, DocumentPermission.VIEW))
                .toList();
    }

    private String ruleOf(final Recorded attempt) {
        if (attempt.ruleUuid() == null) {
            throw new IllegalStateException("Attempt " + attempt.id() + " drafted no rule to decide about");
        }
        return attempt.ruleUuid();
    }

    /// In a pipeline scope, though there is no pipeline: what a stage is built from reports through the
    /// scoped error receiver, as it does for the deferred worker.
    private void onStage(final Recorded attempt, final OnStage action) {
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                new DocRef(ShapeshifterAiDoc.TYPE, attempt.attempt().docUuid()));
        if (doc == null) {
            throw new IllegalStateException("The Shapeshifter AI document of attempt " + attempt.id()
                                            + " has been deleted");
        }
        pipelineScopeProvider.get().scopeRunnable(() ->
                action.run(stageFactoryProvider.get().create(), doc));
    }

    private static SupervisorAttempt summary(final Recorded attempt) {
        return attempt(attempt, List.of());
    }

    private static SupervisorAttempt detail(final Recorded attempt) {
        return attempt(attempt, attempt.turns().stream()
                .map(SupervisorResourceImpl::turn)
                .toList());
    }

    private static SupervisorAttempt attempt(final Recorded attempt, final List<SupervisorTurn> turns) {
        return new SupervisorAttempt(
                attempt.id(),
                new DocRef(ShapeshifterAiDoc.TYPE, attempt.attempt().docUuid()),
                attempt.attempt().shape(),
                attempt.attempt().feed(),
                attempt.attempt().type(),
                attempt.attempt().inputId(),
                attempt.attempt().node(),
                attempt.attempt().executionMode(),
                attempt.attempt().promotionMode(),
                attempt.status(),
                attempt.decision(),
                attempt.ruleUuid(),
                attempt.score(),
                attempt.tokensSpent(),
                attempt.createTimeMs(),
                attempt.updateTimeMs(),
                turns);
    }

    private static SupervisorTurn turn(final Turn turn) {
        return new SupervisorTurn(turn.number(), turn.stepId(), turn.candidate(), turn.kind(),
                turn.question(), turn.answer(), turn.answeredBy(), turn.outcome());
    }


    // --------------------------------------------------------------------------------


    @FunctionalInterface
    private interface OnStage {

        void run(stroom.shapeshifter.ai.stage.Stage stage, ShapeshifterAiDoc doc);
    }
}
