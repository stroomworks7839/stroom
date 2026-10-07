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
import stroom.shapeshifter.ai.element.LearnerDocuments;
import stroom.shapeshifter.ai.element.StageFactory;
import stroom.shapeshifter.ai.element.StepDetails;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Page;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.shared.AmendTurnRequest;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.BacklogStream;
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.ImproveOutcome;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ServingCriteria;
import stroom.shapeshifter.shared.ServingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorGuidance;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// The Supervisor of A28: attempts across every Shapeshifter AI document, and what a person may do to
/// one they are reading.
///
/// Every attempt names the document it was learning for, and a person sees only the attempts of the
/// documents they may see: an attempt's transcript carries what the model was told about a feed's data,
/// so it is read by the document's permission and not by a permission of its own.
@AutoLogged
public class SupervisorResourceImpl implements SupervisorResource {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(SupervisorResourceImpl.class);

    /// The most rows a page of the serving rules may ask for, whatever it asks for.
    /// The attempts beside them have always clamped; these did not, so any person with VIEW on one
    /// document could ask for the whole table in one request, and a negative length reached the database
    /// as `LIMIT -1`.
    private static final int PAGE_LIMIT = 1000;

    private final Provider<Attempts> attemptsProvider;
    private final Provider<ShapeshifterAiStore> storeProvider;
    private final Provider<StageFactory> stageFactoryProvider;
    private final Provider<PipelineScopeRunnable> pipelineScopeProvider;
    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<Serving> servingProvider;
    private final Provider<Guidance> guidanceProvider;
    private final Provider<Deferrals> deferralsProvider;
    private final Provider<LearnerDocuments> learnerDocumentsProvider;

    @Inject
    SupervisorResourceImpl(final Provider<Attempts> attemptsProvider,
                           final Provider<ShapeshifterAiStore> storeProvider,
                           final Provider<StageFactory> stageFactoryProvider,
                           final Provider<PipelineScopeRunnable> pipelineScopeProvider,
                           final Provider<SecurityContext> securityContextProvider,
                           final Provider<Serving> servingProvider,
                           final Provider<Guidance> guidanceProvider,
                           final Provider<Deferrals> deferralsProvider,
                           final Provider<LearnerDocuments> learnerDocumentsProvider) {
        this.attemptsProvider = attemptsProvider;
        this.storeProvider = storeProvider;
        this.stageFactoryProvider = stageFactoryProvider;
        this.pipelineScopeProvider = pipelineScopeProvider;
        this.securityContextProvider = securityContextProvider;
        this.servingProvider = servingProvider;
        this.guidanceProvider = guidanceProvider;
        this.deferralsProvider = deferralsProvider;
        this.learnerDocumentsProvider = learnerDocumentsProvider;
    }

    /// What is waiting on a session (design 04 §6), a row per stream, the raised ones first and then the
    /// longest waiting: the backlog an operator watches form and drain. Raised is computed as it is read,
    /// against each document's maximum deferral age (A78, A79).
    @Override
    public ResultPage<BacklogStream> backlog(final String docUuid, final PageRequest pageRequest) {
        final PageRequest page = pageRequest == null
                ? new PageRequest(0, 100)
                : pageRequest;
        final Map<String, DocRef> readable = readable(docUuid);
        final long now = System.currentTimeMillis();
        final List<BacklogStream> all = new ArrayList<>();
        for (final DocRef docRef : readable.values()) {
            // A document that cannot be read — deleted since it was listed — must not take the whole
            // backlog down with it: its rows are shown against the default age.
            long maxAge = ShapeshifterAiDoc.builder().build().getMaxDeferralAgeMs();
            try {
                final ShapeshifterAiDoc doc = storeProvider.get().readDocument(docRef);
                if (doc != null) {
                    maxAge = doc.getMaxDeferralAgeMs();
                }
            } catch (final RuntimeException e) {
                LOGGER.debug(() -> LogUtil.message("Document {} could not be read for the backlog: {}",
                        docRef.getUuid(), e.getMessage()));
            }
            for (final Deferral row : deferralsProvider.get().forDocument(docRef.getUuid())) {
                all.add(new BacklogStream(docRef, row.inputId(), row.feed(), row.type(), row.failedAgainst(),
                        row.reason(), row.failingRecords(), row.firstDeferredMs(), row.attempts(),
                        row.raised(maxAge, now)));
            }
        }
        all.sort(Comparator.comparing(BacklogStream::isRaised).reversed()
                .thenComparingLong(BacklogStream::getFirstDeferredMs));
        final int offset = (int) Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final int length = pageLength(page);
        final List<BacklogStream> rows = all.stream().skip(offset).limit(length).toList();
        return new ResultPage<>(rows, new PageResponse((long) offset, rows.size(), (long) all.size(), true));
    }

    /// Give a parked session more to spend (A77), and carry it on.
    @Override
    public SupervisorAttempt grant(final long id, final GrantRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Nothing was granted");
        }
        final Recorded attempt = change(id);
        onStage(attempt, (stage, doc) -> stage.grant(doc, id, request.getTokens(), request.getMs()));
        return detail(read(id));
    }

    /// Send a parked session to the exhaustion step (A65), and carry it on.
    @Override
    public SupervisorAttempt exhaust(final long id) {
        final Recorded attempt = change(id);
        onStage(attempt, (stage, doc) -> stage.exhaust(doc, id));
        return detail(read(id));
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

    /// The rules that are serving (A46), ordered by the traffic each carries.
    ///
    /// Not an alert list. A rule serving at 0.93 is above every threshold and nothing is wrong with it;
    /// this is where a person goes when they have an hour and want to spend it where it counts, which is
    /// on whatever carries the most streams.
    ///
    /// The documents this person may read go into the query rather than filtering its answer, as they do
    /// for [#find].
    @Override
    public ResultPage<ServingRule> serving(final ServingCriteria criteria) {
        final ServingCriteria asked = criteria == null
                ? new ServingCriteria()
                : criteria;
        final Map<String, DocRef> readable = readable(asked.getDocUuid());
        final PageRequest page = asked.getPageRequest() == null
                ? new PageRequest(0, 100)
                : asked.getPageRequest();
        final long offset = Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final int length = pageLength(page);
        final Serving.Page found = servingProvider.get()
                .rules(readable.keySet(), asked.getBelow(), offset, length);
        // Each row named with its document rather than its uuid, since the view is over all of them, and
        // held against its document's relearn threshold (design 04 §6): the threshold triggers nothing
        // by itself now, and this list is where it is read.
        final Map<String, Double> thresholds = new LinkedHashMap<>();
        final List<ServingRule> rows = found.rules().stream()
                .map(rule -> named(readable.get(rule.getDoc().getUuid()), rule,
                        thresholds.computeIfAbsent(rule.getDoc().getUuid(), this::relearnThreshold)))
                .toList();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), found.total(), true));
    }

    /// A document's relearn threshold, or none where the document cannot be read.
    private Double relearnThreshold(final String docUuid) {
        try {
            final ShapeshifterAiDoc doc = storeProvider.get().readDocument(new DocRef(ShapeshifterAiDoc.TYPE, docUuid));
            return doc == null
                    ? null
                    : doc.getRelearnThreshold();
        } catch (final RuntimeException e) {
            LOGGER.debug(() -> LogUtil.message("Document {} could not be read for its threshold: {}", docUuid,
                    e.getMessage()));
            return null;
        }
    }

    /// Ask for a rule that is already serving to be made better (A46).
    ///
    /// Held to EDIT, as approving a draft is: it spends a model's tokens and may rebind the document's
    /// routing.
    ///
    /// It runs while the request is open, which is the one thing here that is not yet what A46 asks for:
    /// the ruling says an improvement is taken by the deferred worker. The stage says why in
    /// `Stage.improve` — the worker would need the attempt to remember that it is an improvement and to
    /// find its sample in the regression set rather than in the stream store. Nothing waits on it
    /// meanwhile: the incumbent serves every stream throughout, and a candidate takes over only through
    /// the ordinary gate.
    @Override
    public ImproveOutcome improve(final String docUuid, final String ruleUuid, final ImproveRequest request) {
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change the rules of this document");
        }
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final String message = request == null
                ? null
                : request.getMessage();
        final ShapeshifterAiDoc doc = document(docUuid);
        final Decision decision = pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().improve(doc, ruleUuid, message, by).decision());
        // Described from the one switch that describes every decision: the stage pane, the error stream
        // and this button say the same thing because they say it from the same place.
        return new ImproveOutcome(StepDetails.describe(decision));
    }

    /// Accept a provisional binding now (design 01 §6, §11.6), rather than waiting for the records
    /// that would promote it.
    ///
    /// Held to EDIT, as the other acts that change the document's routing are. Not a way round the
    /// gate: the rule cleared the promotion floor when it was bound, and what is skipped is the wait
    /// for more records to judge it on.
    @Override
    public Boolean accept(final String docUuid, final String ruleUuid) {
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change the rules of this document");
        }
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final ShapeshifterAiDoc doc = document(docUuid);
        pipelineScopeProvider.get().scopeRunnable(() ->
                stageFactoryProvider.get().create().accept(doc, ruleUuid, by));
        return Boolean.TRUE;
    }

    /// Take a rule that is serving back out of the table (A28, design 01 §11.6).
    ///
    /// The reason is asked for rather than assumed: it is recorded against the attempt that bound the rule.
    ///
    /// Answers how many streams the rule produced output for, which is the one consequence the person
    /// cannot see for themselves, and what they would reprocess (A129).
    ///
    /// Held to EDIT, as approving a draft is: it changes what serves the document's pipelines.
    @Override
    public Integer retract(final String docUuid, final String ruleUuid, final RejectRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("A retraction says why: the reason is recorded against "
                                               + "the attempt that bound the rule");
        }
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change the rules of this document");
        }
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final ShapeshifterAiDoc doc = document(docUuid);
        return pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().retract(doc, ruleUuid, request.getReason(), by));
    }

    /// What has been said to a learner (A46), oldest first.
    @Override
    public List<SupervisorGuidance> guidance(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId) {
        if (!may(docUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read this document");
        }
        return said(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)));
    }

    /// Tell a learner something, without asking for anything to be run.
    ///
    /// Held to EDIT rather than VIEW: what is written here is carried into every question the learner
    /// asks from now on, which is a change to how the document learns.
    @Override
    public List<SupervisorGuidance> hint(final String docUuid, final GuidanceRequest request) {
        if (request == null || NullSafe.isBlankString(request.getMessage())) {
            throw new IllegalArgumentException("There is nothing to record: the message is empty");
        }
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how this document learns");
        }
        final Learner learner = learnerOf(docUuid, request.getLearner());
        guidanceProvider.get().given(learner, docUuid, request.getMessage(),
                securityContextProvider.get().getUserIdentityForAudit());
        return said(learner);
    }

    /// Take back something said to a learner. A hint that turned out to be wrong is worse than no hint:
    /// it is carried into every question the learner asks until it is withdrawn.
    ///
    /// The turns that already carried it still say they did (A45), which is what a re-walk replays: what
    /// was withdrawn stops being carried, and does not stop having been.
    @Override
    public List<SupervisorGuidance> withdraw(final String docUuid,
                                             final long id,
                                             final String pipelineUuid,
                                             final String elementId) {
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how this document learns");
        }
        final Learner learner = learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId));
        guidanceProvider.get().withdraw(docUuid, id);
        return said(learner);
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
        final Recorded attempt = stillUnder(change(id));
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
    public SupervisorAttempt reject(final long id, final RejectRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("A rejection says why: the learner gives up with the reason");
        }
        final Recorded attempt = change(id);
        onStage(attempt, (stage, doc) -> stage.reject(doc, ruleOf(attempt), request.getReason()));
        return detail(read(id));
    }

    private List<SupervisorGuidance> said(final Learner learner) {
        return guidanceProvider.get().standing(learner).stream()
                .map(given -> new SupervisorGuidance(given.id(), given.message(), given.author(),
                        given.timeMs()))
                .toList();
    }

    @Override
    public SupervisorAttempt relearn(final long id) {
        final Recorded attempt = stillUnder(change(id));
        final String who = securityContextProvider.get().getUserIdentityForAudit();
        onStage(attempt, (stage, doc) -> stage.relearn(doc, attempt.attempt().learner(),
                "Sent back to be learned again by " + who));
        return detail(read(id));
    }

    /// How many rows a page may hold: what was asked for, within what the server will give. A request
    /// is not to be trusted with either end of it — an unbounded length hands over the whole table in
    /// one call, and a negative one reaches the database as `LIMIT -1` and answers with a server error
    /// rather than a refusal.
    private static int pageLength(final PageRequest page) {
        final int asked = NullSafe.getOrElse(page.getLength(), Integer::intValue, 100);
        return Math.max(1, Math.min(asked, PAGE_LIMIT));
    }

    /// The documents this person may read, by uuid and keeping their names, narrowed to the one they
    /// asked about if they asked about one. A handful per installation, so asking the store about each
    /// costs nothing next to the query it saves.
    private Map<String, DocRef> readable(final String asked) {
        final Map<String, DocRef> readable = new LinkedHashMap<>();
        storeProvider.get().listDocuments().stream()
                .filter(docRef -> asked == null || asked.equals(docRef.getUuid()))
                .filter(docRef -> may(docRef.getUuid(), DocumentPermission.VIEW))
                .forEach(docRef -> readable.put(docRef.getUuid(), docRef));
        return readable;
    }

    /// The same row with its document named rather than merely identified: the seam answers with uuids,
    /// since it has no store to ask, and the view is over every document at once.
    private static ServingRule named(final DocRef doc, final ServingRule rule, final Double threshold) {
        final boolean below = threshold != null
                              && rule.getRollingScore() != null
                              && rule.getRollingScore() < threshold;
        return new ServingRule(doc, rule.getLearner(), rule.getRuleUuid(), rule.getFragment(),
                rule.isPinned(), rule.isProvisional(), rule.getPromotedScore(), rule.getPromotedTimeMs(),
                rule.getRollingScore(), rule.getRecords(), rule.getGuidance(), below);
    }

    /// The learner a request names, as the row it was made from carried it (design 06 phase J slice 2b).
    private static Learner learner(final LearnerRef ref) {
        if (ref == null || NullSafe.isBlankString(ref.getPipelineUuid())
            || NullSafe.isBlankString(ref.getElementId())) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        return new Learner(ref.getPipelineUuid(), ref.getElementId());
    }

    /// The learner a request names, held to the document the request was permitted on. The request names
    /// the two separately and the permission was the document's: without this, a person who may edit one
    /// document could act on another's learner by naming it.
    private Learner learnerOf(final String docUuid, final LearnerRef ref) {
        final Learner learner = learner(ref);
        if (!runsUnder(learner, docUuid)) {
            throw new IllegalArgumentException("Element " + learner.elementId() + " of pipeline "
                                               + learner.pipelineUuid() + " does not run under this document");
        }
        return learner;
    }

    /// An attempt whose learner still runs under the document it was learning for. An attempt is history
    /// and keeps its document, but a change made from one is a change to its learner: once the pipeline
    /// has been pointed at another document, the old document's editors are no longer the ones to make it.
    private Recorded stillUnder(final Recorded attempt) {
        if (!runsUnder(attempt.attempt().learner(), attempt.attempt().docUuid())) {
            throw new IllegalStateException("Attempt " + attempt.id() + " was learning for a pipeline that no "
                                            + "longer runs under its document; act on it from the document "
                                            + "the pipeline names now");
        }
        return attempt;
    }

    private boolean runsUnder(final Learner learner, final String docUuid) {
        return learnerDocumentsProvider.get().documentOf(learner)
                .filter(docUuid::equals)
                .isPresent();
    }

    private static LearnerRef ref(final Learner learner) {
        return new LearnerRef(learner.pipelineUuid(), learner.elementId());
    }

    private ShapeshifterAiDoc document(final String docUuid) {
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid));
        if (doc == null) {
            throw new IllegalArgumentException("There is no Shapeshifter AI document " + docUuid);
        }
        return doc;
    }

    /// An attempt a person may read. An attempt they may not see and an attempt that never existed
    /// answer the same way, or the answer itself says which documents exist and what has been learned
    /// for them.
    private Recorded read(final long id) {
        return permitted(id, DocumentPermission.VIEW);
    }

    /// An attempt a person may change. Approving a draft binds a rule, rejecting one gives a learner up,
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
                ref(attempt.attempt().learner()),
                attempt.attempt().feed(),
                attempt.attempt().type(),
                attempt.attempt().inputId(),
                attempt.attempt().node(),
                attempt.attempt().promotionMode(),
                attempt.status(),
                attempt.decision(),
                attempt.ruleUuid(),
                attempt.score(),
                attempt.tokensSpent(),
                attempt.grant().tokens(),
                attempt.grant().ms(),
                attempt.exhaustNext(),
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
