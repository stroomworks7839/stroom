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

package stroom.shapeshifter.ai.stage;

import stroom.docref.DocRef;
import stroom.query.api.ExpressionOperator;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.FragmentWriter;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.Dialogue;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.Outcome;
import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.learning.RecordedAdvisor;
import stroom.shapeshifter.ai.learning.RecordedAdvisor.AwaitingAnswer;
import stroom.shapeshifter.ai.learning.Sample;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.learning.Target;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.scoring.Records;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Decision.Retracted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.DocPath;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import net.sf.saxon.s9api.XdmNode;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One supervised stage over one stream, as design 02 §4 spells it out: route, learn, judge, write,
 * emit. This is the supervisor's logic without the pipeline element around it; the element (design 01
 * §12 item 4) will delegate to it. The fragment is run by the step runners here, as it was learned.
 * <p>
 * The seed fixes the held-out split for the run (design 02 §7). Where the input is raw text and records
 * do not yet exist, the learning sample is a prefix and the whole stream is held out, as §7 also says;
 * the seeded shuffle applies once the replay unit is a record and is not needed by the first scenarios.
 * <p>
 * Runtime state is what the stage is given (A26): the shape rows, the ledger, the outputs' bindings, the
 * reprocess requests and the regression set. A sentinel is an error and a ledger row (A4); binding a
 * shape releases its ledger as a reprocess request (A12); retracting a provisional rule requests its
 * outputs' inputs the same way (§6). In review mode (A25) a binding is a draft until {@link #approve}
 * makes it the promotion or {@link #reject} discards it.
 */
public final class Stage {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(Stage.class);

    private static final String FOLDER = "Shapeshifter";
    /**
     * Added to the attempt budget for the claim's expiry: an attempt that runs to its budget still holds
     * the shape it is learning.
     */
    private static final long CLAIM_GRACE_MS = 30_000L;
    /**
     * The window a document's spend is counted over (A44). A24 will make it a setting, with the budget it
     * is measured against; until then it is an hour, which is long enough to see a feed burning spend and
     * short enough that yesterday's does not hide it.
     */
    private static final long SPEND_WINDOW_MS = 3_600_000L;
    private static final JsonFactory JSON = new JsonFactory();
    private static final ElementId STAGE = new ElementId("Stage");

    private final Advisors advisors;
    private final List<StepRunner> runners;
    private final List<Scorer> scorers;
    private final FragmentWriter writer;
    private final FragmentRunner fragmentRunner;
    private final Attempts attempts;
    private final Rules rules;
    private final Shapes shapes;
    private final Spend spend;
    private final Ledger ledger;
    private final Outputs outputs;
    private final Reprocessing reprocessing;
    private final RegressionSet regressionSet;
    private final Router router = new Router();
    private final Clock clock;
    private final long seed;
    private final String node;

    public Stage(final Advisors advisors,
                 final List<StepRunner> runners,
                 final List<Scorer> scorers,
                 final FragmentWriter writer,
                 final FragmentRunner fragmentRunner,
                 final Attempts attempts,
                 final Rules rules,
                 final Shapes shapes,
                 final Spend spend,
                 final Ledger ledger,
                 final Outputs outputs,
                 final Reprocessing reprocessing,
                 final RegressionSet regressionSet,
                 final Clock clock,
                 final long seed,
                 final String node) {
        this.advisors = advisors;
        this.runners = List.copyOf(runners);
        this.scorers = List.copyOf(scorers);
        this.writer = writer;
        this.fragmentRunner = fragmentRunner;
        this.attempts = attempts;
        this.rules = rules;
        this.shapes = shapes;
        this.spend = spend;
        this.ledger = ledger;
        this.outputs = outputs;
        this.reprocessing = reprocessing;
        this.regressionSet = regressionSet;
        this.clock = clock;
        this.seed = seed;
        this.node = node;
    }

    /**
     * When a claim taken now lapses if nothing extends it (A45): long enough for an attempt that runs to
     * its whole budget still to hold the shape it is learning.
     */
    private long claimUntil(final ShapeshifterAiDoc doc) {
        return clock.millis() + doc.getAttemptBudgetMs() + CLAIM_GRACE_MS;
    }

    /**
     * Carry on an attempt that stopped at a question, with the answers it has been given (A28): the
     * dialogue is re-walked from the start over the same sample, answered from the record until the record
     * runs out, which re-derives everything those answers produced — the chain, the boundary, the records,
     * the targets, each element's configuration and output — because all of it follows from the sample and
     * the answers. What is asked beyond the record is asked of {@code answerer}: the model, for the worker
     * advancing a deferred attempt; nothing, for one that is to stop again.
     *
     * @param input The stream the attempt was raised on, which the attempt's row names.
     */
    public StageRun resume(final ShapeshifterAiDoc doc, final long attemptId, final Input input,
                           final Advisor answerer) {
        final Recorded recorded = attempts.byId(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("No attempt " + attemptId));
        final Map<String, Object> attributes = input.routingAttributes(ShapeSignature.of(input.data()));
        final Shape shape = Shape.of(doc.getLearningKey(), attributes);
        // The replay only re-derives what the attempt saw if it is walked over the same sample: a stream
        // of another shape would be answered from a record of questions never asked about it.
        if (!shape.id().equals(recorded.attempt().shape())) {
            throw new IllegalArgumentException("Attempt " + attemptId + " was learning shape "
                                               + recorded.attempt().shape() + ", and this stream is shape "
                                               + shape.id());
        }
        // Carrying it on is taking it (A45): an attempt that has finished is not resumed, and one another
        // node has taken since is that node's until its claim lapses.
        if (!attempts.claimed(attemptId, node, clock.millis(), claimUntil(doc))) {
            return sentinel(doc, shape, input, "Attempt " + attemptId + " is not this node's to carry on: it "
                                               + "has finished, or another node holds it");
        }
        final Scorecard scorecard = new Scorecard(doc.getScorers(), scorers);
        final ExpressionOperator selector = RoutingRule.learnedSelector(doc.getLearningKey(), attributes);
        final RecordedAdvisor replay = new RecordedAdvisor(recorded.turns(), answerer);
        // Who answered each turn the first time, by its number: a replayed turn is rewritten as it is
        // re-walked, and it must not come back saying the model answered what a person answered (A28).
        final Map<Integer, String> answeredBefore = recorded.turns().stream()
                .filter(turn -> turn.answeredBy() != null)
                .collect(Collectors.toMap(Attempts.Turn::number, Attempts.Turn::answeredBy,
                        (first, second) -> first));
        return carrying(doc, shape, input, attemptId, recorded.tokensSpent(), answeredBefore, replay,
                recorder -> learnAndBind(doc, shape, input, attributes, scorecard, selector, recorder));
    }

    /**
     * Route, learn, judge, write, emit — design 02 §4 — over one stream.
     */
    public StageRun run(final ShapeshifterAiDoc doc, final Input input) {
        final Map<String, Object> attributes = input.routingAttributes(ShapeSignature.of(input.data()));
        final Shape shape = Shape.of(doc.getLearningKey(), attributes);
        final Scorecard scorecard = new Scorecard(doc.getScorers(), scorers);
        final List<RoutingRule> table = rules.forDocument(doc.getUuid());
        final Optional<RoutingRule> matched = router.route(table, attributes);

        if (matched.isPresent()) {
            final RoutingRule rule = matched.get();
            if (rule.isReserved()) {
                // An operator has decided this selector is not to be learned (design 01 §3).
                return sentinel(doc, shape, input, "Reserved: rule " + (table.indexOf(rule) + 1)
                                                   + " matches and binds nothing");
            }
            if (rule.isDraft()) {
                return sentinel(doc, shape, input, "Awaiting review: draft rule " + rule.getUuid() + " binds "
                                                   + rule.getPipeline().getName() + " for shape " + shape.id());
            }
            return serve(doc, shape, rule, input, attributes, scorecard);
        }

        final Optional<String> givenUp = shapes.reasonGivenUp(doc.getUuid(), shape.id());
        if (givenUp.isPresent()) {
            return sentinel(doc, shape, input, "Shape given up: " + givenUp.get());
        }
        // A stream without a value for a key field has no shape under that key (design 01 §3), so it is
        // sentinelled here, before a model is asked or a document written, rather than failing later.
        if (!shape.missing().isEmpty()) {
            return sentinel(doc, shape, input, "The learning key names '" + shape.missing().get(0)
                                               + "' but the stream carries no value for it");
        }
        final ExpressionOperator selector;
        try {
            selector = RoutingRule.learnedSelector(doc.getLearningKey(), attributes);
        } catch (final IllegalArgumentException e) {
            // A value the matcher cannot be made to take literally (an empty header, a '*') is as
            // unbindable as a missing one.
            return sentinel(doc, shape, input, e.getMessage());
        }

        // Before any call, the variants already bound for this feed and type are tried (design 01 §6):
        // a splitter written for one shape will often consume its neighbour, and that costs no question.
        for (final RoutingRule variant : compatibleVariants(doc, input)) {
            final Judged judged = judge(scorecard, variant.getPipeline(), input.data(), variant.getRecordBoundary());
            if (judged.clearsFloor(doc)) {
                return bind(doc, shape, input, selector, variant.getPipeline(), judged, List.of(), List.of(),
                        variant.getRecordBoundary());
            }
        }

        if (doc.getLearningMode() == LearningMode.DISABLED) {
            return sentinel(doc, shape, input,
                    "Shapeshifter AI is disabled for this document and no bound variant fits");
        }

        // One learner per shape across the cluster (A42, A43, A45): the attempt's own row is the claim,
        // held until the rule is written and not merely while the model is asked — a second node that took
        // the shape in between would find no rule for it, learn it again, and append a second rule for one
        // selector, leaving its fragment orphaned behind the first. A node that does not take the shape
        // does not wait, which would hold this thread for the length of an attempt: it sentinels and
        // returns, and the winner's promotion releases the backlog as A12 releases any other.
        return recording(doc, shape, input,
                () -> sentinel(doc, shape, input, "Another node is learning this shape; this stream waits "
                                                  + "for what it learns"),
                recorder -> learnAndBind(doc, shape, input, attributes, scorecard, selector, recorder));
    }

    /**
     * One attempt, recorded (A28): a row when this node commits to learning a shape, a turn for every
     * question as the transcript has them, and what it came to. The record outlives the node that made it,
     * which is what lets a person read back what was said and — once the dialogue can be resumed (A45) —
     * what lets another node pick the attempt up.
     */
    private StageRun recording(final ShapeshifterAiDoc doc,
                               final Shape shape,
                               final Input input,
                               final Supplier<StageRun> taken,
                               final Function<Recorder, StageRun> attempt) {
        // One advisor for the attempt, since the node's makes a new one per call and each counts its own
        // tokens: asking twice would read two fresh counters and record nothing spent.
        final Advisor advisor = advisors.of(doc);
        final Optional<Long> opened = attempts.opened(new Attempts.Attempt(doc.getUuid(), shape.id(),
                input.feed(), input.type(), input.id(), node, doc.getExecutionMode(), doc.getPromotionMode(),
                claimUntil(doc)), clock.millis());
        if (opened.isEmpty()) {
            // Another attempt holds this shape (A45): it is still learning, parked or not, and this stream
            // takes what that attempt comes to rather than learning the same thing beside it.
            return taken.get();
        }
        return carrying(doc, shape, input, opened.get(), 0L, Map.of(), advisor, attempt);
    }

    /**
     * One attempt, carried to whatever it comes to and recorded as it goes (A28): whether this node opened
     * it or picked it up where another stopped.
     */
    private StageRun carrying(final ShapeshifterAiDoc doc,
                              final Shape shape,
                              final Input input,
                              final long attemptId,
                              final long alreadySpent,
                              final Map<Integer, String> answeredBefore,
                              final Advisor advisor,
                              final Function<Recorder, StageRun> attempt) {
        final long before = advisor.tokensUsed();
        final Recorder recorder = new Recorder(doc, advisor, attemptId, alreadySpent, answeredBefore,
                answeredBy(doc));
        final StageRun run;
        try {
            run = attempt.apply(recorder);
        } catch (final AwaitingAnswer awaiting) {
            // The attempt has reached a question nobody present can answer: it stops here, keeps its claim
            // on the shape, and waits for the worker or a person (A28, A45). The stream is sentinelled, as
            // an unknown shape's is, and released when the attempt finishes.
            record(() -> attempts.parked(attemptId, AttemptStatus.AWAITING_MODEL, claimUntil(doc),
                    Math.max(0L, advisor.tokensUsed() - before)));
            return sentinel(doc, shape, input, "Awaiting the model: attempt " + attemptId + " stopped at "
                                               + awaiting.question().summary());
        } catch (final RuntimeException e) {
            // The model, the node or the database failed: the attempt says so, with the turns it had got
            // to, rather than vanishing. Nothing here may throw over the failure that brought us.
            record(() -> attempts.closed(attemptId, AttemptStatus.ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null, null,
                    Math.max(0L, advisor.tokensUsed() - before)));
            throw e;
        }
        // The run is done and its rule is written: the record of it must not be what fails the stream.
        record(() -> attempts.closed(attemptId, statusOf(run.decision()), reason(run.decision()),
                ruleOf(run.decision()), scoreOf(run.decision()), Math.max(0L, advisor.tokensUsed() - before)));
        return run;
    }

    /**
     * Bookkeeping, which may not fail what it is a record of: an attempt is written after the fragment, the
     * rule and the output are, so a database that refuses the row must not undo a stream that succeeded.
     */
    private static void record(final Runnable write) {
        try {
            write.run();
        } catch (final RuntimeException e) {
            LOGGER.error(() -> LogUtil.message("The attempt could not be recorded: {}", e.getMessage()), e);
        }
    }

    /**
     * What an attempt writes as it goes: the advisor it is asking, so the tokens it charges are counted
     * once, and a turn for every exchange as it is answered and again as it is judged (A28).
     */
    private final class Recorder {

        private final ShapeshifterAiDoc doc;
        private final Advisor advisor;
        private final long attemptId;
        private final long alreadySpent;
        private final Map<Integer, String> answeredBefore;
        private final String answeredBy;

        private Recorder(final ShapeshifterAiDoc doc,
                         final Advisor advisor,
                         final long attemptId,
                         final long alreadySpent,
                         final Map<Integer, String> answeredBefore,
                         final String answeredBy) {
            this.doc = doc;
            this.advisor = advisor;
            this.attemptId = attemptId;
            this.alreadySpent = alreadySpent;
            this.answeredBefore = Map.copyOf(answeredBefore);
            this.answeredBy = answeredBy;
        }

        private Advisor advisor() {
            return advisor;
        }

        /// What this attempt had spent before it was last parked (A5): its budget is the whole of it.
        private long alreadySpent() {
            return alreadySpent;
        }

        /// The claim pushed out, before every question: an attempt held up by one slow model call would
        /// otherwise run past its expiry and have its shape learned again by another node (A45).
        private void heartbeat() {
            record(() -> attempts.heartbeat(attemptId, claimUntil(doc)));
        }

        private void turn(final int number, final Exchange exchange) {
            record(() -> attempts.turn(attemptId, new Attempts.Turn(number, exchange.step(),
                    exchange.candidate(), Question.kindOf(exchange.question()), exchange.question().summary(),
                    exchange.reply(), answeredBefore.getOrDefault(number, answeredBy),
                    exchange.outcome())));
        }
    }

    /**
     * Where an attempt stands, from what it decided (A28).
     */
    private static AttemptStatus statusOf(final Decision decision) {
        return switch (decision) {
            case Promoted ignored -> AttemptStatus.PROMOTED;
            case Rebound ignored -> AttemptStatus.PROMOTED;
            case Provisional ignored -> AttemptStatus.PROVISIONAL;
            case Drafted ignored -> AttemptStatus.AWAITING_REVIEW;
            default -> AttemptStatus.ABANDONED;
        };
    }

    /**
     * What an attempt came to, in the words the decision itself uses — and only those: a decision's
     * diagnostics quote the stream's own text, which may not be stored until it is redacted (A17, A38),
     * which is the same reason a turn records what it asked rather than the prompt it sent.
     */
    private static String reason(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> "Promoted " + promoted.score();
            case Rebound rebound -> "Rebound " + rebound.score();
            case Provisional provisional -> "Provisional " + provisional.score() + " on "
                                            + provisional.records() + " of " + provisional.required()
                                            + " records";
            case Drafted drafted -> "Awaiting review, scored " + drafted.score();
            case Kept kept -> "Kept the incumbent: " + kept.reason();
            case GivenUp givenUp -> givenUp.reason();
            case Retracted retracted -> retracted.reason();
            case Sentinel sentinel -> sentinel.reason();
            default -> decision.getClass().getSimpleName();
        };
    }

    private static String ruleOf(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> promoted.rule().getUuid();
            case Provisional provisional -> provisional.rule().getUuid();
            case Drafted drafted -> drafted.rule().getUuid();
            case Rebound rebound -> rebound.rule().getUuid();
            default -> null;
        };
    }

    private static Double scoreOf(final Decision decision) {
        return switch (decision) {
            case Promoted promoted -> promoted.score();
            case Provisional provisional -> provisional.score();
            case Drafted drafted -> drafted.score();
            case Rebound rebound -> rebound.score();
            default -> null;
        };
    }

    /**
     * Who answered a turn: the model this document names, since nothing else answers one yet — a person
     * answering instead is A28's, in a later slice, and writes their own name here.
     */
    private static String answeredBy(final ShapeshifterAiDoc doc) {
        // A DocRef may carry no name — a document stored as type and uuid alone — and the column may not be
        // null, so the uuid stands in: a record that cannot be written is worse than one that names a uuid.
        if (doc.getModel() == null) {
            return "model";
        }
        return doc.getModel().getName() == null
                ? doc.getModel().getUuid()
                : doc.getModel().getName();
    }

    private StageRun learnAndBind(final ShapeshifterAiDoc doc,
                                  final Shape shape,
                                  final Input input,
                                  final Map<String, Object> attributes,
                                  final Scorecard scorecard,
                                  final ExpressionOperator selector,
                                  final Recorder recorder) {
        // Learn on a prefix, judge on the whole stream.
        final Outcome outcome = learn(doc, input, attributes, scorecard, List.of(), recorder);
        if (outcome instanceof final Abandoned abandoned) {
            return givenUp(doc, shape, input, abandoned.reason(), abandoned.reason(), abandoned.diagnostics(),
                    List.of(), outcome.transcript());
        }
        final Learned learned = (Learned) outcome;

        // The candidate over the whole stream: the held-out judgement of A14/A15 where there are enough
        // records for one, and the floor a provisional binding must clear where there are not.
        final Judged judged = Judged.of(rerun(learned.chain(), input.data(), learned.boundary()), scorecard);
        if (!judged.clearsFloor(doc)) {
            return givenUp(doc, shape, input, "Below the promotion floor",
                    "Candidate scored " + judged.score() + " against a floor of " + doc.getPromotionFloor(),
                    judged.verdicts().stream().flatMap(verdict -> verdict.feedback().stream()).toList(),
                    judged.verdicts(), learned.transcript());
        }
        final DocRef fragment = write(doc, shape, learned);
        return bind(doc, shape, input, selector, fragment, judged, learned.targets(), learned.transcript(),
                learned.boundary());
    }

    /**
     * A matched rule's fragment processes the stream. A provisional rule is promoted the first time the
     * shape brings enough records for the gate and they clear the floor (A14, design 01 §6), and retracted
     * the first time they do not. A promoted rule's score over the stream feeds the shape's rolling score
     * (A29); once that has fallen below the relearn threshold, the next stream that brings enough records
     * to judge a candidate on is relearned while the incumbent serves it. A pinned rule is frozen: served,
     * never promoted, retracted or relearned (design 01 §7.3 rule 2).
     */
    private StageRun serve(final ShapeshifterAiDoc doc,
                           final Shape shape,
                           final RoutingRule rule,
                           final Input input,
                           final Map<String, Object> attributes,
                           final Scorecard scorecard) {
        final Judged judged = judge(scorecard, rule.getPipeline(), input.data(), rule.getRecordBoundary());
        final boolean enough = judged.records() >= doc.getMinRecordsPerShape();
        if (rule.isPinned()) {
            return emit(doc, new Bound(rule), shape, input, rule, judged, List.of());
        }
        if (rule.isProvisional()) {
            if (!enough) {
                return emit(doc, new Bound(rule), shape, input, rule, judged, List.of());
            }
            return judged.clearsFloor(doc)
                    ? promote(doc, shape, rule, input, judged)
                    : retract(doc, shape, rule, input, judged);
        }
        if (shapes.draftAwaiting(doc.getUuid(), shape.id()).isPresent()
            || shapes.reasonGivenUp(doc.getUuid(), shape.id()).isPresent()) {
            // A relearned candidate waits for a person (A25), or a person rejected one: until they decide
            // otherwise, the incumbent serves and the shape is not scored against or relearned again.
            return emit(doc, new Bound(rule), shape, input, rule, judged, List.of());
        }
        final Optional<String> marked = shapes.relearnReason(doc.getUuid(), shape.id());
        if (marked.isPresent()) {
            // Marked: relearned on a stream that can meet A14 for the candidate, once learning is allowed;
            // a smaller stream is served as it stands and the mark waits.
            return enough && doc.getLearningMode() == LearningMode.AUTOMATIC
                    ? relearn(doc, shape, rule, input, attributes, scorecard, judged, marked.get())
                    : emit(doc, new Bound(rule), shape, input, rule, judged, List.of());
        }
        final OptionalDouble rolling = shapes.scored(doc.getUuid(), shape.id(), judged.score(), judged.records(),
                doc.getMinRecordsPerShape());
        if (rolling.isPresent() && rolling.getAsDouble() < doc.getRelearnThreshold()) {
            shapes.markForRelearning(doc.getUuid(), shape.id(), "Rolling score " + rolling.getAsDouble()
                                                                + " fell below the relearn threshold of "
                                                                + doc.getRelearnThreshold());
        }
        return emit(doc, new Bound(rule), shape, input, rule, judged, List.of());
    }

    /**
     * A provisional rule met the gate: promoted in place — same rule, same {@code uuid} — and its
     * regression set begins with this stream (A18).
     */
    private StageRun promote(final ShapeshifterAiDoc doc,
                             final Shape shape,
                             final RoutingRule rule,
                             final Input input,
                             final Judged judged) {
        final RoutingRule promoted = rule.copy()
                .provisional(false)
                .promotedTimeMs(clock.millis())
                .score(judged.score())
                .build();
        regressionSet.accept(promoted.getUuid(), List.of(new Accepted(input.data(), judged.score())),
                doc.getRegressionCap());
        shapes.reset(doc.getUuid(), shape.id());
        rules.replace(doc.getUuid(), promoted);
        return emit(doc, new Promoted(promoted, judged.score()), shape, input, promoted, judged, List.of());
    }

    /**
     * A provisional rule failed the gate (design 01 §6): out of the table, the shape unknown again, the
     * inputs whose output it produced requested as-current, and this stream sentinelled — it has no
     * binding now, and the next stream of the shape learns afresh with these records behind it.
     */
    private StageRun retract(final ShapeshifterAiDoc doc,
                             final Shape shape,
                             final RoutingRule rule,
                             final Input input,
                             final Judged judged) {
        final String reason = "Provisional rule " + rule.getUuid() + " scored " + judged.score()
                              + " against a floor of " + doc.getPromotionFloor() + " on " + judged.records()
                              + " records and is retracted";
        rules.remove(doc.getUuid(), rule.getUuid());
        shapes.reset(doc.getUuid(), shape.id());
        final List<Long> produced = outputs.boundBy(rule.getUuid());
        if (!produced.isEmpty()) {
            reprocessing.request(doc.getUuid(), reason, produced);
        }
        ledger.sentinelled(doc.getUuid(), shape.id(), input.id(), reason);
        return new StageRun(doc, new Retracted(rule, judged.score(), reason),
                shape, null, null, judged.verdicts(), List.of());
    }

    /**
     * The incumbent serves the stream while a candidate is learned on it (A29). The candidate replaces
     * the incumbent's fragment under the same rule when it clears the floor, is not worse than the
     * incumbent on this stream (A15) and is not worse on any record the rule was accepted on (A18);
     * otherwise the incumbent is kept and nothing is written. Either way the mark is spent and the rolling
     * score starts again, and is not acted on until another memory's worth of records has been seen, so a
     * shape that cannot be fixed is not relearned on every stream. The model opens with why the incumbent
     * fell short. In review mode the candidate is a draft behind the incumbent (A25).
     */
    private StageRun relearn(final ShapeshifterAiDoc doc,
                             final Shape shape,
                             final RoutingRule incumbent,
                             final Input input,
                             final Map<String, Object> attributes,
                             final Scorecard scorecard,
                             final Judged served,
                             final String marked) {
        // Another node relearning this shape holds it (A45); the incumbent serves this stream, as it
        // serves every other until the relearning settles.
        return recording(doc, shape, input,
                () -> emit(doc, new Bound(incumbent), shape, input, incumbent, served, List.of()),
                recorder -> relearnUnderClaim(doc, shape, incumbent, input, attributes, scorecard, served,
                        marked, recorder));
    }

    private StageRun relearnUnderClaim(final ShapeshifterAiDoc doc,
                                       final Shape shape,
                                       final RoutingRule incumbent,
                                       final Input input,
                                       final Map<String, Object> attributes,
                                       final Scorecard scorecard,
                                       final Judged served,
                                       final String marked,
                                       final Recorder recorder) {
        shapes.reset(doc.getUuid(), shape.id());
        final List<StoredError> opening = new ArrayList<>();
        opening.add(new StoredError(Severity.ERROR, null, STAGE, "Relearning: " + marked
                                                                 + ". The bound fragment scored " + served.score()
                                                                 + " on this stream"));
        served.verdicts().forEach(verdict -> opening.addAll(verdict.feedback()));
        final Outcome outcome = learn(doc, input, attributes, scorecard, opening, recorder);
        if (outcome instanceof final Abandoned abandoned) {
            return emit(doc, new Kept(incumbent, "No candidate: " + abandoned.reason()), shape, input, incumbent,
                    served, outcome.transcript());
        }
        final Learned learned = (Learned) outcome;
        final Judged candidate = Judged.of(rerun(learned.chain(), input.data(), learned.boundary()), scorecard);
        if (!candidate.clearsFloor(doc)) {
            return emit(doc, new Kept(incumbent, "Candidate scored " + candidate.score() + " against a floor of "
                                                 + doc.getPromotionFloor()), shape, input, incumbent, served,
                    learned.transcript());
        }
        if (candidate.score() < served.score()) {
            return emit(doc, new Kept(incumbent, "Candidate scored " + candidate.score() + " against the incumbent's "
                                                 + served.score() + " on this stream"), shape, input, incumbent,
                    served, learned.transcript());
        }
        for (final Accepted accepted : regressionSet.accepted(incumbent.getUuid())) {
            final double onRecord = Judged.of(rerun(learned.chain(), accepted.input(), learned.boundary()), scorecard)
                    .score();
            if (onRecord < accepted.score()) {
                return emit(doc, new Kept(incumbent, "Candidate scored " + onRecord + " against " + accepted.score()
                                                     + " accepted on an earlier stream"), shape, input, incumbent,
                        served, learned.transcript());
            }
        }
        final DocRef fragment = write(doc, shape, learned);
        if (doc.getPromotionMode() == PromotionMode.REVIEW) {
            // Behind the incumbent, which keeps matching first; Approve rebinds the incumbent to this
            // fragment and carries the history over.
            final RoutingRule draft = RoutingRule.builder()
                    .uuid(UUID.randomUUID().toString())
                    .expression(incumbent.getExpression())
                    .pipeline(fragment)
                    .draft(true)
                    .score(candidate.score())
                    .recordBoundary(learned.boundary())
                    .build();
            rules.append(doc.getUuid(), draft);
            regressionSet.accept(draft.getUuid(), List.of(new Accepted(input.data(), candidate.score(),
                    learned.targets())), doc.getRegressionCap());
            shapes.awaitReview(doc.getUuid(), shape.id(), draft.getUuid());
            return emit(doc, new Drafted(draft, candidate.score()), shape, input,
                    incumbent, served, learned.transcript());
        }
        final RoutingRule rebound = incumbent.copy()
                .pipeline(fragment)
                .promotedTimeMs(clock.millis())
                .score(candidate.score())
                .recordBoundary(learned.boundary())
                .build();
        regressionSet.accept(rebound.getUuid(), List.of(new Accepted(input.data(), candidate.score(),
                learned.targets())), doc.getRegressionCap());
        rules.replace(doc.getUuid(), rebound);
        return emit(doc, new Rebound(incumbent, rebound, candidate.score()), shape,
                input, incumbent, served, learned.transcript());
    }

    /**
     * Bind a fragment that cleared the floor on this shape: a new rule on the learning key, promoted if
     * the shape has enough records for a held-out judgement and provisional otherwise; the shape's ledger
     * released as a reprocess request (A12) — under a provisional rule too, since those inputs are the
     * records the rule is waiting on to meet A14, and its outputs are retracted if it does not; and the
     * stream's output emitted. In review mode (A25) the rule is a draft instead: the router will not bind
     * it, nothing is released, and this stream joins the ledger for Approve to release.
     */
    private StageRun bind(final ShapeshifterAiDoc doc,
                          final Shape shape,
                          final Input input,
                          final ExpressionOperator selector,
                          final DocRef fragment,
                          final Judged judged,
                          final List<Target> targets,
                          final List<Exchange> transcript,
                          final RecordBoundary boundary) {
        final boolean provisional = judged.records() < doc.getMinRecordsPerShape();
        final boolean draft = doc.getPromotionMode() == PromotionMode.REVIEW;
        final RoutingRule rule = RoutingRule.builder()
                .uuid(UUID.randomUUID().toString())
                .expression(selector)
                .pipeline(fragment)
                .provisional(provisional)
                .draft(draft)
                .promotedTimeMs(provisional || draft
                        ? null
                        : clock.millis())
                .score(judged.score())
                .recordBoundary(boundary)
                .build();
        // Appended, not prepended (design 02 §4): learned rules are exclusive by key, so order among them
        // is moot, and an operator's more specific rule above them keeps its precedence.
        rules.append(doc.getUuid(), rule);
        if (!provisional || draft) {
            // A draft's records go in now, under its uuid, however few: they are what it was judged on
            // whether or not it is approved — and a person approving it on too few is the A14 exception
            // design 01 §6 allows them — and Reject takes them out again.
            regressionSet.accept(rule.getUuid(), List.of(new Accepted(input.data(), judged.score(), targets)),
                    doc.getRegressionCap());
        }
        shapes.reset(doc.getUuid(), shape.id());
        if (draft) {
            shapes.awaitReview(doc.getUuid(), shape.id(), rule.getUuid());
            ledger.sentinelled(doc.getUuid(), shape.id(), input.id(), "Awaiting review: draft rule " + rule.getUuid()
                                                                      + " binds " + fragment.getName()
                                                                      + " for shape " + shape.id());
            return new StageRun(doc, new Drafted(rule, judged.score()), shape, null,
                    null, judged.verdicts(), transcript);
        }
        final List<Long> released = ledger.release(doc.getUuid(), shape.id());
        if (!released.isEmpty()) {
            reprocessing.request(doc.getUuid(), "Shape " + shape.id() + " bound by rule " + rule.getUuid()
                                                + (provisional
                                                        ? " (provisional)"
                                                        : ""), released);
        }
        final Decision decision = provisional
                ? new Provisional(rule, judged.score(), judged.records(), doc.getMinRecordsPerShape())
                : new Promoted(rule, judged.score());
        return emit(doc, decision, shape, input, rule, judged, transcript);
    }

    private Outcome learn(final ShapeshifterAiDoc doc,
                          final Input input,
                          final Map<String, Object> attributes,
                          final Scorecard scorecard,
                          final List<StoredError> opening,
                          final Recorder recorder) {
        final Advisor advisor = recorder.advisor();
        // The attempt's claim is pushed out before every question (A45), and every turn is recorded as it
        // is answered and again as it is judged, so an attempt still running shows what it had got to
        // (A28).
        final Dialogue dialogue = new Dialogue(advisor, runners, scorecard, clock,
                recorder::heartbeat, recorder::turn)
                .alreadySpent(recorder.alreadySpent());
        final Sample sample = Sample.of(learningPrefix(input.data(), doc), doc.getLearningKey(), attributes);
        final long before = advisor.tokensUsed();
        final List<Exchange> asked = new ArrayList<>();
        try {
            final Outcome outcome = dialogue.run(doc, sample, opening);
            asked.addAll(outcome.transcript());
            return outcome;
        } finally {
            // What the attempt cost, counted for the document across the cluster (A44), whether it learned
            // anything or not and whether it returned or threw: an attempt that abandons costs what it
            // asked, and a model that fails after charging is the runaway A24's breaker exists to see. The
            // policy that reads the count is A24's, in phase E.
            spend.record(doc.getUuid(), Math.max(0L, advisor.tokensUsed() - before), asked.size(),
                    SPEND_WINDOW_MS);
        }
    }

    /**
     * Approve a draft (A25): the promotion. Where an active rule already binds the same selector — the
     * draft came of relearning — that rule is rebound to the draft's fragment and keeps its {@code uuid}
     * and its history; otherwise the draft itself goes live. Either way the shape's ledger is released as
     * a reprocess request (A12).
     *
     */
    public void approve(final ShapeshifterAiDoc doc, final String ruleUuid) {
        final List<RoutingRule> table = rules.forDocument(doc.getUuid());
        final RoutingRule draft = draft(doc, table, ruleUuid);
        final String shape = shapeAwaiting(doc, draft);
        final Optional<RoutingRule> incumbent = incumbentOf(table, draft);
        if (incumbent.isPresent()) {
            if (incumbent.get().isPinned()) {
                // Design 01 §7.3 rule 2: a pin exempts a rule from rebinding until it is unpinned.
                throw new IllegalStateException("Rule " + incumbent.get().getUuid() + " is pinned; unpin it before "
                                                + "approving draft " + ruleUuid + " in its place");
            }
            rules.replace(doc.getUuid(), incumbent.get().copy()
                    .pipeline(draft.getPipeline())
                    .provisional(false)
                    .promotedTimeMs(clock.millis())
                    .score(draft.getScore())
                    .recordBoundary(draft.getRecordBoundary())
                    .build());
            rules.remove(doc.getUuid(), draft.getUuid());
            regressionSet.accept(incumbent.get().getUuid(), regressionSet.accepted(draft.getUuid()),
                    doc.getRegressionCap());
            regressionSet.discard(draft.getUuid());
        } else {
            rules.replace(doc.getUuid(), draft.copy()
                    .draft(false)
                    .provisional(false)
                    .promotedTimeMs(clock.millis())
                    .build());
        }
        shapes.reset(doc.getUuid(), shape);
        // The attempt that wrote the draft is no longer awaiting review (A28).
        record(() -> attempts.decided(doc.getUuid(), ruleUuid, AttemptStatus.PROMOTED, "Approved"));
        final List<Long> released = ledger.release(doc.getUuid(), shape);
        if (!released.isEmpty()) {
            reprocessing.request(doc.getUuid(), "Draft rule " + ruleUuid + " approved for shape " + shape, released);
        }
    }

    /**
     * Reject a draft (A25): the rule goes, with its records; its documents stay, as every document does.
     * The shape is given up with the reason, so the model is not asked the same question again until an
     * operator says otherwise: a shape with no rule is sentinelled, and a relearned shape's incumbent
     * carries on serving without being scored against or relearned.
     *
     */
    public void reject(final ShapeshifterAiDoc doc, final String ruleUuid, final String reason) {
        final RoutingRule draft = draft(doc, rules.forDocument(doc.getUuid()), ruleUuid);
        final String shape = shapeAwaiting(doc, draft);
        rules.remove(doc.getUuid(), draft.getUuid());
        regressionSet.discard(draft.getUuid());
        shapes.reset(doc.getUuid(), shape);
        shapes.giveUp(doc.getUuid(), shape, "Rejected: " + reason);
        record(() -> attempts.decided(doc.getUuid(), ruleUuid, AttemptStatus.REJECTED, "Rejected: " + reason));
    }

    private static RoutingRule draft(final ShapeshifterAiDoc doc,
                                     final List<RoutingRule> table,
                                     final String ruleUuid) {
        return table.stream()
                .filter(rule -> ruleUuid.equals(rule.getUuid()))
                .findFirst()
                .filter(RoutingRule::isDraft)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Rule " + ruleUuid + " is not a draft on document " + doc.getName()));
    }

    private String shapeAwaiting(final ShapeshifterAiDoc doc, final RoutingRule draft) {
        return shapes.shapeAwaiting(doc.getUuid(), draft.getUuid())
                .orElseThrow(() -> new IllegalStateException("No shape awaits review under draft rule "
                                                             + draft.getUuid() + " on document " + doc.getName()));
    }

    /**
     * The active rule a draft would replace: one that binds the same selector.
     */
    private static Optional<RoutingRule> incumbentOf(final List<RoutingRule> table, final RoutingRule draft) {
        return table.stream()
                .filter(rule -> !rule.isDraft() && !rule.isReserved()
                                && Objects.equals(rule.getExpression(), draft.getExpression()))
                .findFirst();
    }

    private DocRef write(final ShapeshifterAiDoc doc, final Shape shape, final Learned learned) {
        return writer.write(DocPath.fromParts(FOLDER, doc.getName()), shape.slug() + "-" + clock.millis(),
                learned.chain());
    }

    /**
     * The fragments bound for this feed and type, in table order, each once: what an unknown shape is
     * tried against before the model is asked.
     */
    private List<RoutingRule> compatibleVariants(final ShapeshifterAiDoc doc, final Input input) {
        final List<RoutingRule> variants = new ArrayList<>();
        for (final RoutingRule rule : rules.forDocument(doc.getUuid())) {
            if (!rule.isReserved() && !rule.isDraft()
                && Router.compatible(rule, input.feed(), input.type())
                && variants.stream().noneMatch(v -> v.getPipeline().getUuid().equals(rule.getPipeline().getUuid()))) {
                variants.add(rule);
            }
        }
        return variants;
    }

    /**
     * The stream's output with the bindings that produced it (design 01 §7.3 rule 3).
     */
    private StageRun emit(final ShapeshifterAiDoc doc,
                          final Decision decision,
                          final Shape shape,
                          final Input input,
                          final RoutingRule rule,
                          final Judged judged,
                          final List<Exchange> transcript) {
        final Bindings bindings = new Bindings(doc.getUuid(), rule.getUuid(), rule.getPipeline(), rule.isProvisional(),
                judged.score());
        outputs.emitted(input.id(), bindings);
        return new StageRun(doc, decision, shape, bindings, judged.output(), judged.verdicts(), transcript);
    }

    /**
     * The stream is not processed and the model was not consulted: an error naming the shape and the
     * reason, and a ledger row (A4).
     */
    private StageRun sentinel(final ShapeshifterAiDoc doc, final Shape shape, final Input input, final String reason) {
        ledger.sentinelled(doc.getUuid(), shape.id(), input.id(), reason);
        return new StageRun(doc, new Sentinel(reason), shape, null, null, List.of(), List.of());
    }

    /**
     * No candidate passed: the shape is given up, and this stream is sentinelled like every later one of
     * the shape will be until a variant covers it.
     */
    private StageRun givenUp(final ShapeshifterAiDoc doc,
                             final Shape shape,
                             final Input input,
                             final String decision,
                             final String reason,
                             final List<StoredError> diagnostics,
                             final List<Verdict> verdicts,
                             final List<Exchange> transcript) {
        shapes.giveUp(doc.getUuid(), shape.id(), reason);
        ledger.sentinelled(doc.getUuid(), shape.id(), input.id(), reason);
        return new StageRun(doc, new GivenUp(decision, diagnostics), shape, null, null, verdicts, transcript);
    }

    /**
     * The first {@code 1 - heldOutFraction} of the non-blank lines: what the model learns from. The
     * whole stream is what it is judged on. Input that is already markup is not cut — a prefix of a
     * document is not a document, and which elements are its records is the split question's to settle
     * (A35) — so the model learns from the whole of it, within the document's sample size limit.
     */
    static String learningPrefix(final String data, final ShapeshifterAiDoc policy) {
        final int limit = policy.getSampleSizeLimit();
        // Markup that is one document is learned whole; markup that is not — a fragment per line, no root —
        // is lines, held out and cut like any text.
        if (ShapeSignature.isMarkup(data) && Records.parsed(data) >= 0) {
            return data.length() <= limit
                    ? data
                    : wholeChildren(data, limit);
        }
        final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
        if (isJsonDocument(data)) {
            // A prefix of a JSON document is not a document either: cut by lines it would not parse, and the
            // split question would abandon the attempt on a sample the parser cannot read. Over the limit it
            // is cut at its widest array's items and closed again, as wholeChildren closes an XML root.
            return data.length() <= limit
                    ? data
                    : wholeItems(data, limit);
        }
        if (lines.isEmpty()) {
            // A blank stream has nothing to learn from; the compile gate and coverage will say so.
            return "";
        }
        int keep = (int) Math.ceil(lines.size() * (1.0 - policy.getHeldOutFraction()));
        keep = Math.max(1, Math.min(keep, lines.size()));
        return wholeLines(lines, keep, limit);
    }

    /**
     * The first {@code keep} lines, as many of them as the size limit allows; one line is always shown.
     */
    private static String wholeLines(final List<String> lines, final int keep, final int limit) {
        int length = lines.get(0).length();
        int within = 1;
        while (within < keep && length + 1 + lines.get(within).length() <= limit) {
            length += 1 + lines.get(within).length();
            within++;
        }
        return String.join("\n", lines.subList(0, within)) + "\n";
    }

    /**
     * How many records a stream brought, the evidence a judgement rests on (A14), counted on the input so
     * that a variant that drops records is judged, not excused. Where a boundary was settled (A35) it is
     * counted by that — the elements of the name in XML input, the array's items in the XML the parser
     * makes of JSON; otherwise as {@link #recordsBrought(String)} counts the input alone.
     */
    static int recordsBrought(final Attempted first) {
        final RecordBoundary boundary = first.boundary();
        if (boundary != null) {
            // The parser's output is the document a JSON boundary applies to; XML input is its own.
            final String document = first.parser()
                    ? first.result().output()
                    : first.input();
            final int by = document == null
                    ? -1
                    : Records.parsed(document, boundary);
            if (by >= 0) {
                return by;
            }
        }
        return recordsBrought(first.input());
    }

    /**
     * How many records a stream brought where no boundary was settled: a document's root's children; the
     * lines of markup that is not one document — a fragment per line — as the yield scorer counts them; one
     * for a JSON document, however many lines it is printed over; the non-blank lines of text.
     */
    static int recordsBrought(final String input) {
        if (ShapeSignature.isMarkup(input)) {
            final int parsed = Records.parsed(input);
            if (parsed >= 0) {
                return parsed;
            }
        } else if (isJsonDocument(input)) {
            return 1;
        }
        return (int) input.lines().filter(line -> !line.isBlank()).count();
    }

    /**
     * Whether text is one JSON document spanning lines — one object or array, and nothing after it, whose
     * first line does not close it — rather than JSON lines, each a value of its own, which are cut like any
     * text. A bracket is not enough: a log whose lines open with {@code [Mon Sep 21 ...]} is text, so the
     * value is parsed.
     */
    static boolean isJsonDocument(final String data) {
        final String trimmed = ShapeSignature.withoutBom(data).stripLeading();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return false;
        }
        final String first = trimmed.lines().findFirst().orElse("").stripTrailing();
        if (first.endsWith("}") || first.endsWith("]")) {
            return false;
        }
        try (JsonParser parser = JSON.createParser(trimmed)) {
            parser.nextToken();
            parser.skipChildren();
            return parser.nextToken() == null;
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * A JSON document over the sample size limit, cut at the items of its widest array — the one a split will
     * name — and closed again, so that what the model is shown is still one JSON document. Where no array can
     * be cut that way, the whole document is shown: a sample the parser cannot read is worse than a long one,
     * and the budget of A5 is what stops a runaway.
     */
    private static String wholeItems(final String data, final int limit) {
        final String trimmed = ShapeSignature.withoutBom(data).stripLeading();
        final Optional<int[]> widest = widestArray(trimmed);
        if (widest.isEmpty()) {
            return data;
        }
        final int open = widest.get()[0];
        final int close = widest.get()[1];
        final String before = trimmed.substring(0, open + 1);
        final String after = trimmed.substring(close);
        final List<String> items = items(trimmed.substring(open + 1, close));
        final int room = limit - before.length() - after.length();
        final StringBuilder kept = new StringBuilder();
        for (final String item : items) {
            final int cost = kept.isEmpty()
                    ? item.length()
                    : item.length() + 1;
            if (!kept.isEmpty() && kept.length() + cost > room) {
                break;
            }
            if (!kept.isEmpty()) {
                kept.append(',');
            }
            kept.append(item);
        }
        return before + kept + after;
    }

    /**
     * The widest array in a JSON document — the outermost one holding the most — as the offsets of its
     * brackets, ignoring brackets inside strings.
     */
    private static Optional<int[]> widestArray(final String json) {
        int[] widest = null;
        final Deque<Integer> opens = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '[') {
                opens.push(i);
            } else if (!inString && c == ']' && !opens.isEmpty()) {
                final int open = opens.pop();
                if (widest == null || i - open > widest[1] - widest[0]) {
                    widest = new int[]{open, i};
                }
            }
        }
        return Optional.ofNullable(widest);
    }

    /**
     * An array's items as text, split at the commas between them rather than those inside them.
     */
    private static List<String> items(final String inside) {
        final List<String> items = new ArrayList<>();
        int depth = 0;
        int from = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < inside.length(); i++) {
            final char c = inside.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && (c == '{' || c == '[')) {
                depth++;
            } else if (!inString && (c == '}' || c == ']')) {
                depth--;
            } else if (!inString && c == ',' && depth == 0) {
                items.add(inside.substring(from, i).strip());
                from = i + 1;
            }
        }
        final String last = inside.substring(from).strip();
        if (!last.isEmpty()) {
            items.add(last);
        }
        return items;
    }

    /**
     * A document over the sample size limit, cut at a record boundary: the root's start tag, as many of its
     * children as fit — at least one — and its end tag, so that what the model is shown is still a document.
     * What looks like markup but is not one document — fragments, or text beginning with a bracketed word —
     * is cut by whole lines, as text is; the split question will find it what it is.
     */
    private static String wholeChildren(final String data, final int limit) {
        final Optional<OutputRecords> document = OutputRecords.parse(data);
        if (document.isEmpty() || document.get().records().isEmpty()) {
            final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
            return lines.isEmpty()
                    ? ""
                    : wholeLines(lines, lines.size(), limit);
        }
        final String rootName = document.get().root().getNodeName().toString();
        final int open = data.indexOf('<' + rootName);
        final int close = open < 0
                ? -1
                : endOfTag(data, open);
        if (close < 0) {
            return data;
        }
        final StringBuilder cut = new StringBuilder(data.substring(open, close + 1)).append('\n');
        int kept = 0;
        for (final XdmNode child : document.get().records()) {
            final String text = child.toString();
            if (kept > 0 && cut.length() + text.length() > limit) {
                break;
            }
            cut.append(text).append('\n');
            kept++;
        }
        return cut.append("</").append(rootName).append('>').toString();
    }

    /**
     * @return The index of the {@code >} that ends the tag opening at {@code from}, outside any quoted
     * attribute value, or -1.
     */
    private static int endOfTag(final String data, final int from) {
        char quote = 0;
        for (int i = from; i < data.length(); i++) {
            final char c = data.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The chain over an input, step by step. A step that produced nothing leaves the rest unrun; one that
     * produced output with errors is followed, as {@link FragmentRunner} follows it.
     */
    private static List<Attempted> rerun(final List<LearnedStep> chain,
                                         final String input,
                                         final RecordBoundary boundary) {
        final List<Attempted> attempted = new ArrayList<>();
        String current = input;
        for (final LearnedStep step : chain) {
            final Attempted attempt = Attempted.of(step.runner(), current,
                    step.runner().run(step.configuration(), current), boundary);
            attempted.add(attempt);
            if (attempt.result().output() == null) {
                break;
            }
            current = attempt.result().output();
        }
        return attempted;
    }

    /**
     * The candidate's score is the product of its steps' weighted totals: a record must survive every
     * step, and what one step discards no later step can recover. A step that did not run scores nothing.
     */
    private static double candidateScore(final List<Verdict> verdicts) {
        double score = 1.0;
        for (final Verdict verdict : verdicts) {
            score *= verdict.gatesPassed()
                    ? verdict.weightedTotal()
                    : 0.0;
        }
        return verdicts.isEmpty()
                ? 0.0
                : score;
    }

    private Judged judge(final Scorecard scorecard,
                         final DocRef fragment,
                         final String input,
                         final RecordBoundary boundary) {
        return Judged.of(fragmentRunner.run(fragment, input, boundary), scorecard);
    }

    /**
     * A variant's run over a whole stream, scored: what promotion, provisional binding, retraction and
     * relearning all decide on.
     *
     * @param records How many records the stream brought — its lines where it is text, its records by the
     *                boundary where one was settled, else the root's children of markup. The evidence the
     *                judgement rests on (A14), counted on the input so that a variant that drops records is
     *                judged, not excused.
     */
    private record Judged(String output, List<Verdict> verdicts, double score, int records) {

        static Judged of(final List<Attempted> attempted, final Scorecard scorecard) {
            final List<Verdict> verdicts = attempted.stream().map(scorecard::judge).toList();
            final String output = attempted.isEmpty()
                    ? null
                    : attempted.get(attempted.size() - 1).result().output();
            final int records = attempted.isEmpty()
                    ? 0
                    : recordsBrought(attempted.get(0));
            return new Judged(output, verdicts, candidateScore(verdicts), records);
        }


        boolean clearsFloor(final ShapeshifterAiDoc doc) {
            return !verdicts.isEmpty()
                   && score >= doc.getPromotionFloor()
                   && verdicts.stream().allMatch(Verdict::gatesPassed);
        }
    }
}
