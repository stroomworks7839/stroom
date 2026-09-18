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
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.DocPath;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One supervised stage over one stream, as design 02 §4 spells it out: route, learn, judge, write,
 * emit. This is the supervisor's logic without the pipeline element around it; the element (design 01
 * §12 item 4) will delegate to it. The fragment is run by the step runners here, as it was learned.
 * <p>
 * The seed fixes the held-out split for the run (design 02 §7). Where the input is raw text and records
 * do not yet exist, the learning sample is a prefix and the whole stream is held out, as §7 also says;
 * the seeded shuffle applies once the replay unit is a record and is not needed by the first scenarios.
 */
public final class Stage {

    private static final String FOLDER = "Shapeshifter";

    private final Advisor advisor;
    private final List<StepRunner> runners;
    private final List<Scorer> scorers;
    private final FragmentWriter writer;
    private final FragmentRunner fragmentRunner;
    private final Quarantine quarantine;
    private final RegressionSet regressionSet;
    private final Router router = new Router();
    private final Clock clock;
    private final long seed;

    public Stage(final Advisor advisor,
                 final List<StepRunner> runners,
                 final List<Scorer> scorers,
                 final FragmentWriter writer,
                 final FragmentRunner fragmentRunner,
                 final Quarantine quarantine,
                 final RegressionSet regressionSet,
                 final Clock clock,
                 final long seed) {
        this.advisor = advisor;
        this.runners = List.copyOf(runners);
        this.scorers = List.copyOf(scorers);
        this.writer = writer;
        this.fragmentRunner = fragmentRunner;
        this.quarantine = quarantine;
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
                return sentinel(doc, shape, "Reserved: rule " + (doc.getRoutingTable().indexOf(rule) + 1)
                                            + " matches and binds nothing");
            }
            if (rule.isDraft()) {
                return sentinel(doc, shape, "Awaiting review: draft rule " + rule.getUuid() + " binds "
                                            + rule.getPipeline().getName() + " for shape " + shape.id());
            }
            return serve(doc, shape, rule, input, scorecard);
        }

        final Optional<String> givenUp = quarantine.reasonGivenUp(doc.getUuid(), shape.id());
        if (givenUp.isPresent()) {
            return sentinel(doc, shape, "Shape given up: " + givenUp.get());
        }
        // A stream without a value for a key field has no shape under that key (design 01 §3), so it is
        // sentinelled here, before a model is asked or a document written, rather than failing later.
        if (!shape.missing().isEmpty()) {
            return sentinel(doc, shape, "The learning key names '" + shape.missing().get(0)
                                        + "' but the stream carries no value for it");
        }
        final ExpressionOperator selector;
        try {
            selector = RoutingRule.learnedSelector(doc.getLearningKey(), attributes);
        } catch (final IllegalArgumentException e) {
            // A value the matcher cannot be made to take literally (an empty header, a '*') is as
            // unbindable as a missing one.
            return sentinel(doc, shape, e.getMessage());
        }

        // Before any call, the variants already bound for this feed and type are tried (design 01 §6):
        // a splitter written for one shape will often consume its neighbour, and that costs no question.
        for (final DocRef variant : compatibleVariants(doc, input)) {
            final Judged judged = judge(scorecard, variant, input.data());
            if (judged.clearsFloor(doc)) {
                return bind(doc, shape, selector, variant, judged, List.of());
            }
        }

        if (doc.getLearningMode() == LearningMode.DISABLED) {
            return sentinel(doc, shape, "Shapeshifter AI is disabled for this document and no bound variant fits");
        }

        // Learn on a prefix, judge on the whole stream.
        final Dialogue dialogue = new Dialogue(advisor, runners, scorecard);
        final Sample sample = Sample.of(learningPrefix(input.data(), doc), doc.getLearningKey(), attributes);
        final Outcome outcome = dialogue.run(doc, sample);
        if (outcome instanceof final Abandoned abandoned) {
            quarantine.giveUp(doc.getUuid(), shape.id(), abandoned.reason());
            return new StageRun(doc, new GivenUp(abandoned.reason()), shape, null, List.of(), outcome.transcript());
        }
        final Learned learned = (Learned) outcome;

        // The candidate over the whole stream: the held-out judgement of A14/A15 where there are enough
        // records for one, and the floor a provisional binding must clear where there are not.
        final Judged judged = Judged.of(rerun(learned.chain(), input.data()), scorecard);
        if (!judged.clearsFloor(doc)) {
            final String reason = "Candidate scored " + judged.score() + " against a floor of "
                                  + doc.getPromotionFloor();
            quarantine.giveUp(doc.getUuid(), shape.id(), reason);
            return new StageRun(doc, new GivenUp("Below the promotion floor"), shape, null, judged.verdicts(),
                    learned.transcript());
        }
        final DocRef fragment = writer.write(
                DocPath.fromParts(FOLDER, doc.getName()),
                shape.slug() + "-" + clock.millis(),
                learned.chain());
        return bind(doc, shape, selector, fragment, judged, learned.transcript());
    }

    /**
     * A matched rule's fragment processes the stream. A provisional rule is promoted the first time the
     * shape brings enough records for the gate and they clear the floor (A14, design 01 §6); otherwise it
     * keeps serving, and its retraction is the next slice's.
     */
    private StageRun serve(final ShapeshifterAiDoc doc,
                           final Shape shape,
                           final RoutingRule rule,
                           final Input input,
                           final Scorecard scorecard) {
        final Judged judged = judge(scorecard, rule.getPipeline(), input.data());
        if (rule.isProvisional() && judged.records() >= doc.getMinRecordsPerShape() && judged.clearsFloor(doc)) {
            final RoutingRule promoted = rule.copy()
                    .provisional(false)
                    .promotedTimeMs(clock.millis())
                    .score(judged.score())
                    .build();
            final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
            table.set(table.indexOf(rule), promoted);
            regressionSet.accept(promoted.getUuid(), List.of(new Accepted(input.data(), judged.score())),
                    doc.getRegressionCap());
            return new StageRun(doc.copy().routingTable(table).build(), new Promoted(promoted, judged.score()),
                    shape, judged.output(), judged.verdicts(), List.of());
        }
        return new StageRun(doc, new Bound(rule), shape, judged.output(), judged.verdicts(), List.of());
    }

    /**
     * Bind a fragment that cleared the floor on this shape: a new rule on the learning key, promoted if
     * the shape has enough records for a held-out judgement and provisional otherwise, the shape released
     * from the ledger (A12), and the stream's output emitted.
     */
    private StageRun bind(final ShapeshifterAiDoc doc,
                          final Shape shape,
                          final ExpressionOperator selector,
                          final DocRef fragment,
                          final Judged judged,
                          final List<Exchange> transcript) {
        final boolean provisional = judged.records() < doc.getMinRecordsPerShape();
        final RoutingRule rule = RoutingRule.builder()
                .uuid(UUID.randomUUID().toString())
                .expression(selector)
                .pipeline(fragment)
                .provisional(provisional)
                .promotedTimeMs(provisional
                        ? null
                        : clock.millis())
                .score(judged.score())
                .build();
        // Appended, not prepended (design 02 §4): learned rules are exclusive by key, so order among them
        // is moot, and an operator's more specific rule above them keeps its precedence.
        final List<RoutingRule> table = new ArrayList<>(doc.getRoutingTable());
        table.add(rule);
        if (!provisional) {
            regressionSet.accept(rule.getUuid(), List.of(new Accepted(judged.input(), judged.score())),
                    doc.getRegressionCap());
        }
        quarantine.release(doc.getUuid(), shape.id());
        final Decision decision = provisional
                ? new Provisional(rule, judged.score(), judged.records(), doc.getMinRecordsPerShape())
                : new Promoted(rule, judged.score());
        return new StageRun(doc.copy().routingTable(table).build(), decision, shape, judged.output(),
                judged.verdicts(), transcript);
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

    private static StageRun sentinel(final ShapeshifterAiDoc doc, final Shape shape, final String reason) {
        return new StageRun(doc, new Sentinel(reason), shape, null, List.of(), List.of());
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

    private static List<Attempted> rerun(final List<LearnedStep> chain, final String input) {
        final List<Attempted> attempted = new ArrayList<>();
        String current = input;
        for (final LearnedStep step : chain) {
            final Attempted attempt = new Attempted(
                    step.elementType(), current, step.runner().run(step.configuration(), current));
            attempted.add(attempt);
            if (!attempt.result().passed()) {
                break;
            }
            current = attempt.result().output();
        }
        return attempted;
    }

    /**
     * The candidate's score is the mean of its steps' weighted totals; a step that did not run scores
     * nothing.
     */
    private static double candidateScore(final List<Verdict> verdicts) {
        return verdicts.stream()
                .mapToDouble(verdict -> verdict.gatesPassed()
                        ? verdict.weightedTotal()
                        : 0.0)
                .average()
                .orElse(0.0);
    }

    private Judged judge(final Scorecard scorecard, final DocRef fragment, final String input) {
        return Judged.of(fragmentRunner.run(fragment, input), scorecard);
    }

    /**
     * A variant's run over a whole stream, scored: what promotion, provisional binding and the bound
     * path all decide on.
     */
    private record Judged(String input, String output, List<Verdict> verdicts, double score, int records) {

        static Judged of(final List<Attempted> attempted, final Scorecard scorecard) {
            final List<Verdict> verdicts = attempted.stream().map(scorecard::judge).toList();
            final String output = attempted.isEmpty()
                    ? null
                    : attempted.get(attempted.size() - 1).result().output();
            final String input = attempted.isEmpty()
                    ? null
                    : attempted.get(0).input();
            return new Judged(input, output, verdicts, candidateScore(verdicts), output == null
                    ? 0
                    : Records.count(output));
        }

        boolean clearsFloor(final ShapeshifterAiDoc doc) {
            return !verdicts.isEmpty()
                   && score >= doc.getPromotionFloor()
                   && verdicts.stream().allMatch(Verdict::gatesPassed);
        }
    }
}
