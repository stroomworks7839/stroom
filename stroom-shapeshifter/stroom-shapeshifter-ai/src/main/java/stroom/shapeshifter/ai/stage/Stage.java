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
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.FragmentWriter;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Dialogue;
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
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Decision.Waiting;
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

    public StageRun run(final ShapeshifterAiDoc policy, final Input input) {
        final String shape = ShapeSignature.of(input.data());
        final Scorecard scorecard = new Scorecard(policy.getScorers(), scorers);
        final Optional<RoutingRule> matched = router.route(policy.getRoutingTable(), input.routingAttributes(shape));

        // A rule bound to a fragment processes the stream, and a pinned one is never relearned.
        if (matched.isPresent() && matched.get().getPipeline() != null) {
            final RoutingRule rule = matched.get();
            final Judged judged = judge(scorecard, rule.getPipeline(), input.data());
            return new StageRun(policy, new Bound(rule), shape, judged.output(), judged.verdicts(), List.of());
        }

        if (policy.getLearningMode() == LearningMode.DISABLED) {
            return sentinel(policy, shape, "Shapeshifter AI is disabled for this document");
        }
        final Optional<String> givenUp = quarantine.reasonGivenUp(input.feed(), shape);
        if (givenUp.isPresent()) {
            return sentinel(policy, shape, "Shape given up: " + givenUp.get());
        }
        // A stream without a value for a key field has no shape under that key (design 01 §3), so it is
        // sentinelled here, before a model is asked or a document written, rather than failing later.
        final Map<String, Object> keyValues = input.routingAttributes(shape);
        for (final String field : policy.getLearningKey()) {
            if (keyValues.get(field) == null) {
                return sentinel(policy, shape,
                        "The learning key names '" + field + "' but the stream carries no value for it");
            }
        }

        // Learn on a prefix, judge on the whole stream.
        final Dialogue dialogue = new Dialogue(advisor, runners, scorecard);
        final Sample sample = Sample.of(learningPrefix(input.data(), policy), policy.getLearningKey(), keyValues);
        final Outcome outcome = dialogue.run(policy, sample);
        if (outcome instanceof final Abandoned abandoned) {
            quarantine.giveUp(input.feed(), shape, abandoned.reason());
            return new StageRun(policy, new GivenUp(abandoned.reason()), shape, null, List.of(), outcome.transcript());
        }
        final Learned learned = (Learned) outcome;

        // The candidate over the whole stream: this is the held-out judgement of A14/A15.
        final List<Attempted> attempted = rerun(learned.chain(), input.data());
        final List<Verdict> verdicts = attempted.stream()
                .map(scorecard::judge)
                .toList();
        final String output = attempted.get(attempted.size() - 1).result().output();
        final double score = candidateScore(verdicts);
        final int records = output == null
                ? 0
                : Records.count(output);

        if (records < policy.getMinRecordsPerShape()) {
            return new StageRun(policy, new Waiting(records, policy.getMinRecordsPerShape()), shape, output,
                    verdicts, learned.transcript());
        }
        final boolean passes = score >= policy.getPromotionFloor()
                               && verdicts.stream().allMatch(Verdict::gatesPassed)
                               && !regresses(input.feed(), shape, learned.chain(), scorecard);
        if (!passes) {
            if (matched.isPresent()) {
                return new StageRun(policy, new Kept(matched.get(), score), shape, null, verdicts,
                        learned.transcript());
            }
            quarantine.giveUp(input.feed(), shape, "Candidate scored " + score + " against a floor of "
                                                    + policy.getPromotionFloor());
            return new StageRun(policy, new GivenUp("Below the promotion floor"), shape, null, verdicts,
                    learned.transcript());
        }

        // Promote: new documents, a rule appended or rebound, the regression set extended, the
        // quarantine released.
        final DocRef fragment = writer.write(
                DocPath.fromParts(FOLDER, input.feed()),
                input.feed() + "-" + shape,
                learned.chain());
        final RoutingRule rule = RoutingRule.builder()
                .uuid(UUID.randomUUID().toString())
                .expression(RoutingRule.learnedSelector(policy.getLearningKey(), keyValues))
                .pipeline(fragment)
                .promotedTimeMs(clock.millis())
                .score(score)
                .build();
        final List<RoutingRule> table = new ArrayList<>(policy.getRoutingTable());
        if (matched.isPresent()) {
            table.set(table.indexOf(matched.get()), matched.get().copy()
                    .pipeline(fragment)
                    .promotedTimeMs(clock.millis())
                    .score(score)
                    .build());
        } else {
            table.add(0, rule);
        }
        regressionSet.accept(input.feed(), shape, List.of(new Accepted(input.data(), score)),
                policy.getRegressionCap());
        quarantine.release(input.feed(), shape);
        final ShapeshifterAiDoc after = policy.copy().routingTable(table).build();
        return new StageRun(after, new Promoted(matched.isPresent()
                ? table.get(table.indexOf(matched.get()))
                : rule, score), shape, output, verdicts, learned.transcript());
    }

    private static StageRun sentinel(final ShapeshifterAiDoc policy, final String shape, final String reason) {
        return new StageRun(policy, new Sentinel(reason), shape, null, List.of(), List.of());
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

    /**
     * A18: the candidate is re-run over every record the shape was previously accepted on and must not
     * score lower on any of them.
     */
    private boolean regresses(final String feed,
                              final String shape,
                              final List<LearnedStep> chain,
                              final Scorecard scorecard) {
        for (final Accepted accepted : regressionSet.accepted(feed, shape)) {
            final double score = candidateScore(rerun(chain, accepted.input()).stream()
                    .map(scorecard::judge)
                    .toList());
            if (score < accepted.score()) {
                return true;
            }
        }
        return false;
    }

    private Judged judge(final Scorecard scorecard, final DocRef fragment, final String input) {
        final List<Attempted> attempted = fragmentRunner.run(fragment, input);
        return new Judged(
                attempted.get(attempted.size() - 1).result().output(),
                attempted.stream().map(scorecard::judge).toList());
    }

    private record Judged(String output, List<Verdict> verdicts) {

    }
}
