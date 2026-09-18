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
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.Dialogue;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.Outcome;
import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.Sample;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.Records;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.ai.scoring.Verdict;
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
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.DocPath;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

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

    private static final String FOLDER = "Shapeshifter";
    private static final ElementId STAGE = new ElementId("Stage");

    private final Advisors advisors;
    private final List<StepRunner> runners;
    private final List<Scorer> scorers;
    private final FragmentWriter writer;
    private final FragmentRunner fragmentRunner;
    private final Shapes shapes;
    private final Ledger ledger;
    private final Outputs outputs;
    private final Reprocessing reprocessing;
    private final RegressionSet regressionSet;
    private final Router router = new Router();
    private final Clock clock;
    private final long seed;

    public Stage(final Advisors advisors,
                 final List<StepRunner> runners,
                 final List<Scorer> scorers,
                 final FragmentWriter writer,
                 final FragmentRunner fragmentRunner,
                 final Shapes shapes,
                 final Ledger ledger,
                 final Outputs outputs,
                 final Reprocessing reprocessing,
                 final RegressionSet regressionSet,
                 final Clock clock,
                 final long seed) {
        this.advisors = advisors;
        this.runners = List.copyOf(runners);
        this.scorers = List.copyOf(scorers);
        this.writer = writer;
        this.fragmentRunner = fragmentRunner;
        this.shapes = shapes;
        this.ledger = ledger;
        this.outputs = outputs;
        this.reprocessing = reprocessing;
        this.regressionSet = regressionSet;
        this.clock = clock;
        this.seed = seed;
    }

    /**
     * Route, learn, judge, write, emit — design 02 §4 — over one stream.
     */
    public StageRun run(final ShapeshifterAiDoc doc, final Input input) {
        final Map<String, Object> attributes = input.routingAttributes(ShapeSignature.of(input.data()));
        final Shape shape = Shape.of(doc.getLearningKey(), attributes);
        final Scorecard scorecard = new Scorecard(doc.getScorers(), scorers);
        final Optional<RoutingRule> matched = router.route(doc.getRoutingTable(), attributes);

        if (matched.isPresent()) {
            final RoutingRule rule = matched.get();
            if (rule.isReserved()) {
                // An operator has decided this selector is not to be learned (design 01 §3).
                return sentinel(doc, shape, input, "Reserved: rule " + (doc.getRoutingTable().indexOf(rule) + 1)
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
        for (final DocRef variant : compatibleVariants(doc, input)) {
            final Judged judged = judge(scorecard, variant, input.data());
            if (judged.clearsFloor(doc)) {
                return bind(doc, shape, input, selector, variant, judged, List.of());
            }
        }

        if (doc.getLearningMode() == LearningMode.DISABLED) {
            return sentinel(doc, shape, input,
                    "Shapeshifter AI is disabled for this document and no bound variant fits");
        }

        // Learn on a prefix, judge on the whole stream.
        final Outcome outcome = learn(doc, input, attributes, scorecard);
        if (outcome instanceof final Abandoned abandoned) {
            return givenUp(doc, shape, input, abandoned.reason(), abandoned.reason(), abandoned.diagnostics(),
                    List.of(), outcome.transcript());
        }
        final Learned learned = (Learned) outcome;

        // The candidate over the whole stream: the held-out judgement of A14/A15 where there are enough
        // records for one, and the floor a provisional binding must clear where there are not.
        final Judged judged = Judged.of(rerun(learned.chain(), input.data()), scorecard);
        if (!judged.clearsFloor(doc)) {
            return givenUp(doc, shape, input, "Below the promotion floor",
                    "Candidate scored " + judged.score() + " against a floor of " + doc.getPromotionFloor(),
                    judged.verdicts().stream().flatMap(verdict -> verdict.feedback().stream()).toList(),
                    judged.verdicts(), learned.transcript());
        }
        final DocRef fragment = write(doc, shape, learned);
        return bind(doc, shape, input, selector, fragment, judged, learned.transcript());
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
        final Judged judged = judge(scorecard, rule.getPipeline(), input.data());
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
        return emit(replace(doc, rule, promoted), new Promoted(promoted, judged.score()), shape, input, promoted,
                judged, List.of());
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
        final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
        table.remove(rule);
        shapes.reset(doc.getUuid(), shape.id());
        final List<Long> produced = outputs.boundBy(rule.getUuid());
        if (!produced.isEmpty()) {
            reprocessing.request(doc.getUuid(), reason, produced);
        }
        ledger.sentinelled(doc.getUuid(), shape.id(), input.id(), reason);
        return new StageRun(doc.copy().routingTable(table).build(), new Retracted(rule, judged.score(), reason),
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
        shapes.reset(doc.getUuid(), shape.id());
        final List<StoredError> opening = new ArrayList<>();
        opening.add(new StoredError(Severity.ERROR, null, STAGE, "Relearning: " + marked
                                                                 + ". The bound fragment scored " + served.score()
                                                                 + " on this stream"));
        served.verdicts().forEach(verdict -> opening.addAll(verdict.feedback()));
        final Outcome outcome = learn(doc, input, attributes, scorecard, opening);
        if (outcome instanceof final Abandoned abandoned) {
            return emit(doc, new Kept(incumbent, "No candidate: " + abandoned.reason()), shape, input, incumbent,
                    served, outcome.transcript());
        }
        final Learned learned = (Learned) outcome;
        final Judged candidate = Judged.of(rerun(learned.chain(), input.data()), scorecard);
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
            final double onRecord = Judged.of(rerun(learned.chain(), accepted.input()), scorecard).score();
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
                    .build();
            final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
            table.add(draft);
            regressionSet.accept(draft.getUuid(), List.of(new Accepted(input.data(), candidate.score())),
                    doc.getRegressionCap());
            shapes.awaitReview(doc.getUuid(), shape.id(), draft.getUuid());
            return emit(doc.copy().routingTable(table).build(), new Drafted(draft, candidate.score()), shape, input,
                    incumbent, served, learned.transcript());
        }
        final RoutingRule rebound = incumbent.copy()
                .pipeline(fragment)
                .promotedTimeMs(clock.millis())
                .score(candidate.score())
                .build();
        regressionSet.accept(rebound.getUuid(), List.of(new Accepted(input.data(), candidate.score())),
                doc.getRegressionCap());
        return emit(replace(doc, incumbent, rebound), new Rebound(incumbent, rebound, candidate.score()), shape,
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
                          final List<Exchange> transcript) {
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
                .build();
        // Appended, not prepended (design 02 §4): learned rules are exclusive by key, so order among them
        // is moot, and an operator's more specific rule above them keeps its precedence.
        final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
        table.add(rule);
        if (!provisional || draft) {
            // A draft's records go in now, under its uuid, however few: they are what it was judged on
            // whether or not it is approved — and a person approving it on too few is the A14 exception
            // design 01 §6 allows them — and Reject takes them out again.
            regressionSet.accept(rule.getUuid(), List.of(new Accepted(input.data(), judged.score())),
                    doc.getRegressionCap());
        }
        shapes.reset(doc.getUuid(), shape.id());
        if (draft) {
            shapes.awaitReview(doc.getUuid(), shape.id(), rule.getUuid());
            ledger.sentinelled(doc.getUuid(), shape.id(), input.id(), "Awaiting review: draft rule " + rule.getUuid()
                                                                      + " binds " + fragment.getName()
                                                                      + " for shape " + shape.id());
            return new StageRun(doc.copy().routingTable(table).build(), new Drafted(rule, judged.score()), shape, null,
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
        return emit(doc.copy().routingTable(table).build(), decision, shape, input, rule, judged, transcript);
    }

    private Outcome learn(final ShapeshifterAiDoc doc,
                          final Input input,
                          final Map<String, Object> attributes,
                          final Scorecard scorecard) {
        return learn(doc, input, attributes, scorecard, List.of());
    }

    private Outcome learn(final ShapeshifterAiDoc doc,
                          final Input input,
                          final Map<String, Object> attributes,
                          final Scorecard scorecard,
                          final List<StoredError> opening) {
        final Dialogue dialogue = new Dialogue(advisors.of(doc), runners, scorecard, clock);
        final Sample sample = Sample.of(learningPrefix(input.data(), doc), doc.getLearningKey(), attributes);
        return dialogue.run(doc, sample, opening);
    }

    /**
     * Approve a draft (A25): the promotion. Where an active rule already binds the same selector — the
     * draft came of relearning — that rule is rebound to the draft's fragment and keeps its {@code uuid}
     * and its history; otherwise the draft itself goes live. Either way the shape's ledger is released as
     * a reprocess request (A12).
     *
     * @return The document with its routing table rewritten; the caller saves it.
     */
    public ShapeshifterAiDoc approve(final ShapeshifterAiDoc doc, final String ruleUuid) {
        final RoutingRule draft = draft(doc, ruleUuid);
        final String shape = shapeAwaiting(doc, draft);
        final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
        final Optional<RoutingRule> incumbent = incumbentOf(doc, draft);
        if (incumbent.isPresent()) {
            if (incumbent.get().isPinned()) {
                // Design 01 §7.3 rule 2: a pin exempts a rule from rebinding until it is unpinned.
                throw new IllegalStateException("Rule " + incumbent.get().getUuid() + " is pinned; unpin it before "
                                                + "approving draft " + ruleUuid + " in its place");
            }
            table.set(table.indexOf(incumbent.get()), incumbent.get().copy()
                    .pipeline(draft.getPipeline())
                    .provisional(false)
                    .promotedTimeMs(clock.millis())
                    .score(draft.getScore())
                    .build());
            table.remove(draft);
            regressionSet.accept(incumbent.get().getUuid(), regressionSet.accepted(draft.getUuid()),
                    doc.getRegressionCap());
            regressionSet.discard(draft.getUuid());
        } else {
            table.set(table.indexOf(draft), draft.copy()
                    .draft(false)
                    .provisional(false)
                    .promotedTimeMs(clock.millis())
                    .build());
        }
        shapes.reset(doc.getUuid(), shape);
        final List<Long> released = ledger.release(doc.getUuid(), shape);
        if (!released.isEmpty()) {
            reprocessing.request(doc.getUuid(), "Draft rule " + ruleUuid + " approved for shape " + shape, released);
        }
        return doc.copy().routingTable(table).build();
    }

    /**
     * Reject a draft (A25): the rule goes, with its records; its documents stay, as every document does.
     * The shape is given up with the reason, so the model is not asked the same question again until an
     * operator says otherwise: a shape with no rule is sentinelled, and a relearned shape's incumbent
     * carries on serving without being scored against or relearned.
     *
     * @return The document with its routing table rewritten; the caller saves it.
     */
    public ShapeshifterAiDoc reject(final ShapeshifterAiDoc doc, final String ruleUuid, final String reason) {
        final RoutingRule draft = draft(doc, ruleUuid);
        final String shape = shapeAwaiting(doc, draft);
        final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
        table.remove(draft);
        regressionSet.discard(draft.getUuid());
        shapes.reset(doc.getUuid(), shape);
        shapes.giveUp(doc.getUuid(), shape, "Rejected: " + reason);
        return doc.copy().routingTable(table).build();
    }

    private static RoutingRule draft(final ShapeshifterAiDoc doc, final String ruleUuid) {
        return doc.getRoutingTable().stream()
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
    private static Optional<RoutingRule> incumbentOf(final ShapeshifterAiDoc doc, final RoutingRule draft) {
        return doc.getRoutingTable().stream()
                .filter(rule -> !rule.isDraft() && !rule.isReserved()
                                && Objects.equals(rule.getExpression(), draft.getExpression()))
                .findFirst();
    }

    private DocRef write(final ShapeshifterAiDoc doc, final Shape shape, final Learned learned) {
        return writer.write(DocPath.fromParts(FOLDER, doc.getName()), shape.slug() + "-" + clock.millis(),
                learned.chain());
    }

    private static ShapeshifterAiDoc replace(final ShapeshifterAiDoc doc,
                                             final RoutingRule was,
                                             final RoutingRule now) {
        final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
        table.set(table.indexOf(was), now);
        return doc.copy().routingTable(table).build();
    }

    /**
     * The fragments bound for this feed and type, in table order, each once: what an unknown shape is
     * tried against before the model is asked.
     */
    private static List<DocRef> compatibleVariants(final ShapeshifterAiDoc doc, final Input input) {
        final List<DocRef> variants = new ArrayList<>();
        for (final RoutingRule rule : doc.getRoutingTable()) {
            if (!rule.isReserved() && !rule.isDraft()
                && Router.compatible(rule, input.feed(), input.type())
                && variants.stream().noneMatch(v -> v.getUuid().equals(rule.getPipeline().getUuid()))) {
                variants.add(rule.getPipeline());
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
     * whole stream is what it is judged on.
     */
    static String learningPrefix(final String data, final ShapeshifterAiDoc policy) {
        final List<String> lines = data.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            // A blank stream has nothing to learn from; the compile gate and coverage will say so.
            return "";
        }
        final int keep = (int) Math.ceil(lines.size() * (1.0 - policy.getHeldOutFraction()));
        return String.join("\n", lines.subList(0, Math.max(1, Math.min(keep, lines.size())))) + "\n";
    }

    /**
     * The chain over an input, step by step. A step that produced nothing leaves the rest unrun; one that
     * produced output with errors is followed, as {@link FragmentRunner} follows it.
     */
    private static List<Attempted> rerun(final List<LearnedStep> chain, final String input) {
        final List<Attempted> attempted = new ArrayList<>();
        String current = input;
        for (final LearnedStep step : chain) {
            final Attempted attempt = Attempted.of(step.runner(), current,
                    step.runner().run(step.configuration(), current));
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

    private Judged judge(final Scorecard scorecard, final DocRef fragment, final String input) {
        return Judged.of(fragmentRunner.run(fragment, input), scorecard);
    }

    /**
     * A variant's run over a whole stream, scored: what promotion, provisional binding, retraction and
     * relearning all decide on.
     *
     * @param records How many records the stream brought — its lines where it is text, its records where
     *                it is already XML. The evidence the judgement rests on (A14), counted on the input
     *                so that a variant that drops records is judged, not excused.
     */
    private record Judged(String output, List<Verdict> verdicts, double score, int records) {

        static Judged of(final List<Attempted> attempted, final Scorecard scorecard) {
            final List<Verdict> verdicts = attempted.stream().map(scorecard::judge).toList();
            final String output = attempted.isEmpty()
                    ? null
                    : attempted.get(attempted.size() - 1).result().output();
            final int records = attempted.isEmpty()
                    ? 0
                    : units(attempted.get(0).input());
            return new Judged(output, verdicts, candidateScore(verdicts), records);
        }

        private static int units(final String input) {
            return ShapeSignature.isMarkup(input)
                    ? Records.count(input)
                    : (int) input.lines().filter(line -> !line.isBlank()).count();
        }

        boolean clearsFloor(final ShapeshifterAiDoc doc) {
            return !verdicts.isEmpty()
                   && score >= doc.getPromotionFloor()
                   && verdicts.stream().allMatch(Verdict::gatesPassed);
        }
    }
}
