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


package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.LearningPlan;
import stroom.shapeshifter.shared.PlanStep;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One attempt, following the document's learning plan (A21, A31, A33, A37; design 01 §10.2). The plan is
 * a graph of typed questions: settle the chain; where the plan asks, settle what one record is
 * (<i>Split</i>) and propose what each kind of record should become (<i>Target</i>), validated at once;
 * and ask for each element's configuration, running the chain as far as it has been built so that the
 * next question carries real input, the parser held to preserving what the targets need and the
 * transform to reproducing them.
 * <p>
 * This class holds the question kinds with their reply grammars and judges, and the outcomes judging can
 * end in; the plan says which questions are asked, in what order, judged by which checks, and where to go
 * on each outcome. A step re-asks itself with the feedback of what fell short until its candidates are
 * spent; a transition the step declares on an outcome leaves at once, and one on {@code spent} says where
 * to go when the candidates are gone, abandoning unless told. A pass goes to the next line unless the step
 * says {@code on passed goto}; {@code end} names the end of the plan. Each transition is taken at most once
 * per attempt, so every plan ends. The interpreter knows nothing of which plan it is walking.
 * <p>
 * The chain is chosen from the document's allowed elements that this dialogue can actually run: offering
 * the model an element there is no runner for would be a trap. A document that allows nothing runnable,
 * or whose plan cannot be held, is abandoned before the model is asked anything, naming what is wrong.
 */
public final class Dialogue {

    private static final ElementId DIALOGUE = new ElementId("Dialogue");
    private static final Set<Check> SPLIT_CHECKS = EnumSet.of(Check.COVERAGE, Check.YIELD, Check.WHOLENESS);

    private final Advisor advisor;
    private final Map<String, StepRunner> runners;
    private final Scorecard scorecard;
    private final Clock clock;
    private Budget budget;

    public Dialogue(final Advisor advisor, final List<StepRunner> runners, final Scorecard scorecard) {
        this(advisor, runners, scorecard, Clock.systemUTC());
    }

    public Dialogue(final Advisor advisor,
                    final List<StepRunner> runners,
                    final Scorecard scorecard,
                    final Clock clock) {
        this.advisor = advisor;
        this.scorecard = scorecard;
        this.clock = clock;
        this.runners = runners.stream()
                .collect(Collectors.toUnmodifiableMap(StepRunner::elementType, Function.identity()));
    }

    public Outcome run(final ShapeshifterAiDoc policy, final Sample sample) {
        return run(policy, sample, List.of());
    }

    /**
     * @param opening What the model is told before its first answer — a relearn's account of how the
     *                incumbent fell short (A29) — carried as the feedback of every question's first asking.
     */
    public Outcome run(final ShapeshifterAiDoc policy, final Sample sample, final List<StoredError> opening) {
        final Walk walk = new Walk(policy, sample, opening);
        budget = new Budget(policy, clock.millis(), advisor.tokensUsed());
        try {
            return follow(walk);
        } catch (final BudgetExhausted e) {
            // A5: an attempt has a wall-clock and token budget for the whole dialogue, whichever mode; one that
            // runs out is abandoned, not left to block a task or spend without end.
            return abandoned(e.getMessage(), List.of(), walk.transcript);
        }
    }

    private Outcome follow(final Walk walk) {
        final ShapeshifterAiDoc policy = walk.policy;
        final List<String> allowed = policy.getAllowedElements().stream()
                .filter(runners::containsKey)
                .toList();
        if (allowed.isEmpty()) {
            return abandoned("The document allows no element this dialogue can run: "
                             + policy.getAllowedElements(), List.of(), walk.transcript);
        }
        final LearningPlan plan = policy.getPlan();
        final List<String> problems = new ArrayList<>(plan.problems());
        problems.addAll(Templates.problems(plan));
        if (!problems.isEmpty()) {
            return abandoned("The document's plan cannot be held (see its Learning tab): "
                             + String.join("; ", problems), List.of(), walk.transcript);
        }
        final List<PlanStep> steps = plan.getSteps();

        int at = 0;
        while (at < steps.size()) {
            final PlanStep step = steps.get(at);
            final Visit visit = enter(step, allowed, walk);
            switch (visit.kind()) {
                case NEXT -> {
                    walk.carried = List.of();
                    at++;
                }
                case ABANDON -> {
                    return abandoned(visit.reason(), visit.feedback(), walk.transcript);
                }
                case GOTO -> {
                    final String edge = step.effectiveId() + " " + visit.transition().format();
                    if (!walk.taken.add(edge)) {
                        return abandoned("The plan would take '" + visit.transition().format() + "' from step '"
                                         + step.effectiveId() + "' a second time; a transition is taken once "
                                         + "per attempt", visit.feedback(), walk.transcript);
                    }
                    walk.carried = visit.feedback();
                    at = Transition.END.equals(visit.transition().getGoTo())
                            ? steps.size()
                            : indexOf(steps, visit.transition().getGoTo());
                }
            }
        }
        for (int i = 0; i < walk.chain.size(); i++) {
            if (walk.learned[i] == null) {
                return abandoned(walk.chain.get(i) + " was never configured; the plan ended without a CONFIGURE "
                                 + "step for it", List.of(), walk.transcript);
            }
        }
        return new Learned(List.of(walk.learned), walk.learned[walk.learned.length - 1].result().output(),
                walk.targets, List.copyOf(walk.transcript));
    }

    private static int indexOf(final List<PlanStep> steps, final String id) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).effectiveId().equals(id)) {
                return i;
            }
        }
        // problems() has said every goto names a step.
        throw new IllegalStateException("No step named " + id);
    }

    /**
     * One step of the plan: asked and judged by its kind, under its guard and its checks.
     */
    private Visit enter(final PlanStep step, final List<String> allowed, final Walk walk) {
        if (walk.chain != null && (step.getWhen() == StepGuard.TEXT && !walk.raw
                                   || step.getWhen() == StepGuard.XML && walk.raw)) {
            return Visit.next();
        }
        return switch (step.getKind()) {
            case CHAIN -> chain(step, allowed, walk);
            case SPLIT -> split(step, walk);
            case TARGET -> target(step, walk);
            case CONFIGURE -> configure(step, walk);
        };
    }

    /**
     * Asks a step's question for one unit of its work — the chain, the split, one kind of record, one
     * element — candidate by candidate until one passes, a transition fires, or the candidates are spent.
     *
     * @param unit  Asks and judges one candidate, given the feedback of the last.
     * @param spent The reason to abandon when the candidates are gone and the step says nothing else.
     */
    private Visit candidates(final PlanStep step, final Walk walk, final Unit unit, final String spent) {
        return candidates(step, walk, unit, spent, candidates(step, walk.policy));
    }

    private Visit candidates(final PlanStep step,
                             final Walk walk,
                             final Unit unit,
                             final String spent,
                             final int limit) {
        List<StoredError> feedback = walk.carried.isEmpty()
                ? walk.opening
                : walk.carried;
        walk.carried = List.of();
        for (int candidate = 1; candidate <= limit; candidate++) {
            final Judged judged = unit.attempt(candidate, feedback);
            judge(walk.transcript, step, candidate, judged.outcome());
            if (judged.outcome() == StepOutcome.PASSED) {
                return Visit.next();
            }
            final Transition at = step.transitionOn(judged.outcome());
            if (at != null) {
                return at.abandons()
                        ? Visit.abandon(spent + ": " + at.format(), judged.feedback())
                        : Visit.take(at, judged.feedback());
            }
            feedback = judged.feedback();
        }
        final Transition when = step.transitionOnSpent();
        if (when != null && !when.abandons()) {
            return Visit.take(when, feedback);
        }
        return Visit.abandon(spent, feedback);
    }

    /**
     * Where a step goes once every unit of its work has passed: the next line, unless it says
     * {@code on passed}. A step of several units — a CONFIGURE over the whole chain, a TARGET over several
     * kinds — passes as a whole, not at its first unit.
     */
    private static Visit passed(final PlanStep step, final Visit last) {
        if (last.kind() != Visit.Kind.NEXT) {
            return last;
        }
        final Transition at = step.transitionOn(StepOutcome.PASSED);
        if (at == null) {
            return Visit.next();
        }
        return at.abandons()
                ? Visit.abandon("The plan abandons the attempt on a pass of step '" + step.effectiveId() + "': "
                                + at.format(), List.of())
                : Visit.take(at, List.of());
    }

    /**
     * With one element there is nothing to choose, so the question is not asked (A21).
     */
    private Visit chain(final PlanStep step, final List<String> allowed, final Walk walk) {
        if (allowed.size() == 1) {
            walk.chosen(allowed, runners);
            return passed(step, Visit.next());
        }
        final Set<String> allowedSet = new HashSet<>(allowed);
        return passed(step, candidates(step, walk, (candidate, feedback) -> {
            final String reply = ask(walk, step, candidate, new Chain(walk.sample, allowed, feedback));
            final Optional<List<String>> chain = ChainReply.chain(reply, allowedSet);
            if (chain.isEmpty()) {
                return Judged.refused("The reply was not a chain of allowed element types: " + allowed);
            }
            walk.chosen(chain.get(), runners);
            return Judged.passed();
        }, "No usable chain after " + candidates(step, walk.policy) + " candidates"));
    }

    /**
     * What one record is. For raw text the parser's runner is asked for a configuration that cuts and
     * emits each record whole, judged by the checks the step names — coverage and yield, since nothing
     * about meaning is in play yet, and whether the records emitted carry the input whole, which coverage
     * alone cannot tell. For an input that is already XML — the first element is not a parser — the model
     * is asked which element is one record (A35), judged by whether those elements exist, are not the
     * root, and together hold the document whole.
     */
    private Visit split(final PlanStep step, final Walk walk) {
        final StepRunner first = walk.first(runners);
        final Set<Check> checks = checks(step, SPLIT_CHECKS);
        final String spent = "No record boundary after " + candidates(step, walk.policy) + " candidates";
        if (!walk.raw) {
            final Optional<OutputRecords> document = OutputRecords.parse(walk.sample.text());
            if (document.isEmpty()) {
                walk.cut(null, List.of(walk.sample.text()));
                return passed(step, Visit.next());
            }
            return passed(step, candidates(step, walk, (candidate, feedback) -> {
                final String reply = ask(walk, step, candidate,
                        new Split(walk.sample, first.elementType(), null, feedback)).strip().replaceAll("^<|>$", "");
                if (!reply.matches("[A-Za-z_][\\w.-]*")) {
                    return Judged.refused("The reply was not an element name");
                }
                final List<String> records = TargetChecks.elementsNamed(document.get(), reply);
                if (records.isEmpty()) {
                    return Judged.refused("No element named " + reply + " occurs in the input");
                }
                if (checks.contains(Check.WHOLENESS)) {
                    final Optional<StoredError> shortfall = TargetChecks.recordElement(document.get(), reply);
                    if (shortfall.isPresent()) {
                        return new Judged(StepOutcome.WHOLENESS_SHORT, List.of(shortfall.get()), null);
                    }
                }
                walk.cut(reply, records);
                return Judged.passed();
            }, spent));
        }
        final StepRunner.Configured configured = first.configured().orElseThrow();
        final Scorecard over = scorecard.only(checks);
        return passed(step, candidates(step, walk, (candidate, feedback) -> {
            final String reply = ask(walk, step, candidate,
                    new Split(walk.sample, first.elementType(), configured.documentType(), feedback));
            final Optional<String> configuration = ConfigurationReply.configuration(reply);
            if (configuration.isEmpty()) {
                return Judged.refused("The reply was not a single " + configured.documentType() + " document");
            }
            final StepResult result = first.run(configuration.get(), walk.sample.text());
            if (!result.passed()) {
                return Judged.failed(result);
            }
            final Verdict verdict = over.judge(Attempted.of(first, walk.sample.text(), result));
            if (!verdict.passed()) {
                return new Judged(Scorecard.outcome(verdict), verdict.feedback(), null);
            }
            final List<String> records = recordTexts(result.output());
            if (records.isEmpty()) {
                return new Judged(StepOutcome.WHOLENESS_SHORT, refusal("The split produced no records; each record "
                        + "should be emitted whole, as one data value"), null);
            }
            if (checks.contains(Check.WHOLENESS)) {
                final Optional<StoredError> partial = TargetChecks.wholeness(walk.sample.text(), records);
                if (partial.isPresent()) {
                    return new Judged(StepOutcome.WHOLENESS_SHORT, List.of(partial.get()), null);
                }
            }
            walk.cut(configuration.get(), records);
            return Judged.passed();
        }, spent));
    }

    /**
     * What one kind of record should become: proposed by the model, judged at once by the scorers of
     * meaning over the wrapped event, re-asked with the shortfall of the document it wrote; or
     * {@code none}, for a kind that yields no event. A header or a comment is seen once in a sample; a kind
     * seen more than once is what the feed exists to report, so its first {@code none} is questioned and
     * only its second taken (design 02 §6.3). Questioning a none is not a candidate spent.
     */
    private Visit target(final PlanStep step, final Walk walk) {
        final List<String> over = walk.records == null
                ? recordsOf(walk.sample, walk.raw)
                : walk.records;
        final int kinds = step.getKinds() == null
                ? PlanStep.DEFAULT_KINDS
                : step.getKinds();
        final Scorecard meaning = step.getChecks().isEmpty()
                ? scorecard.meaning()
                : scorecard.meaning().only(step.getChecks());
        final List<String> representatives = TargetChecks.representatives(over, kinds);
        final List<Target> proposed = new ArrayList<>();
        for (int i = 0; i < representatives.size(); i++) {
            final String record = representatives.get(i);
            final int kind = i + 1;
            final int seen = TargetChecks.count(over, record);
            final Visit visit = candidates(step, walk, (candidate, feedback) -> {
                String reply = ask(walk, step, candidate,
                        new TargetFor(walk.sample, record, kind, representatives.size(), feedback));
                if (TargetChecks.NONE.equalsIgnoreCase(reply.strip()) && seen > 1) {
                    reply = ask(walk, step, candidate, new TargetFor(walk.sample, record, kind,
                            representatives.size(), List.of(new StoredError(Severity.WARNING, null, DIALOGUE,
                                    "This kind of record is seen " + seen + " times in the sample; a header or a "
                                    + "comment is seen once. If these records carry no event, reply none again; "
                                    + "otherwise write the event, with the fields the record has and without "
                                    + "those it lacks"))));
                }
                if (TargetChecks.NONE.equalsIgnoreCase(reply.strip())) {
                    proposed.add(Target.none(record));
                    return Judged.passed();
                }
                final Optional<String> event = ConfigurationReply.configuration(reply);
                if (event.isEmpty()) {
                    return Judged.refused("The reply was not a single event document, nor the word none");
                }
                final String document = TargetChecks.asDocument(event.get());
                final Optional<StoredError> refusal = TargetChecks.refusal(document);
                if (refusal.isPresent()) {
                    return new Judged(StepOutcome.REFUSED, List.of(refusal.get()), null);
                }
                final Verdict verdict = TargetChecks.verdict(meaning, record, document);
                if (!verdict.passed()) {
                    return new Judged(Scorecard.outcome(verdict), verdict.feedback(), null);
                }
                proposed.add(Target.of(record, event.get()));
                return Judged.passed();
            }, "No acceptable target for record kind " + kind + " after " + candidates(step, walk.policy)
                + " candidates");
            if (visit.kind() != Visit.Kind.NEXT) {
                return visit;
            }
        }
        walk.targets = List.copyOf(proposed);
        return passed(step, Visit.next());
    }

    /**
     * Each element the step's role names, in chain order: asked for its configuration where it takes one,
     * run over the real output of the element before it, and judged by the step's checks — the scorecard,
     * and against the targets (A31): a parser must preserve what they need, the chain's last element must
     * reproduce them. A role the chain does not have is skipped.
     */
    private Visit configure(final PlanStep step, final Walk walk) {
        final Set<Check> checks = checks(step, EnumSet.allOf(Check.class));
        final Scorecard over = scorecard.only(checks);
        for (final int index : walk.elements(step.getRole(), runners)) {
            final StepRunner runner = runners.get(walk.chain.get(index));
            if (index > 0 && walk.learned[index - 1] == null) {
                return Visit.abandon(runner.elementType() + " cannot be configured before "
                                     + walk.chain.get(index - 1) + " has been", List.of());
            }
            final String input = index == 0
                    ? walk.sample.text()
                    : walk.learned[index - 1].result().output();
            final boolean last = index == walk.chain.size() - 1;
            final Optional<StepRunner.Configured> configured = runner.configured();
            final Visit visit;
            if (configured.isEmpty()) {
                // Nothing is asked for a run-only element, so a second candidate could only repeat the first.
                visit = candidates(step, walk, (candidate, feedback) -> {
                    final Judged judged = judge(over, checks, runner, null, input, runner.run(null, input),
                            walk, last);
                    walk.learn(index, judged.learned());
                    return judged;
                }, runner.elementType() + " failed on its input", 1);
            } else {
                // A parser is held to the split's records; a transform over XML input is told the record element.
                final boolean carriesSplit = runner.parser() || (walk.split != null && !walk.split.startsWith("<"));
                final String split = carriesSplit
                        ? walk.split
                        : null;
                visit = candidates(step, walk, (candidate, feedback) -> {
                    final String reply = ask(walk, step, candidate, new Configuration(runner.elementType(),
                            configured.get().documentType(), walk.sample, input, walk.previous[index], split,
                            walk.targets, feedback));
                    final Optional<String> configuration = ConfigurationReply.configuration(reply);
                    if (configuration.isEmpty()) {
                        return Judged.refused("The reply was not a single " + configured.get().documentType()
                                              + " document");
                    }
                    walk.previous[index] = configuration.get();
                    final Judged judged = judge(over, checks, runner, configuration.get(), input,
                            runner.run(configuration.get(), input), walk, last);
                    walk.learn(index, judged.learned());
                    return judged;
                }, "No passing configuration for " + runner.elementType() + " after "
                   + candidates(step, walk.policy) + " attempts");
            }
            if (visit.kind() != Visit.Kind.NEXT) {
                return visit;
            }
        }
        return passed(step, Visit.next());
    }

    /**
     * The gate of §8.1 first — a step that did not run has nothing to score — then the scorecard, then
     * the targets (A31): a parser must preserve what they need, a transform must reproduce them. A
     * transform that cannot reproduce a target because its input lacks the value is the parser's
     * shortfall, not its own, and is reported as such (§10.1 rule 6) for the plan to route.
     */
    private Judged judge(final Scorecard over,
                         final Set<Check> checks,
                         final StepRunner runner,
                         final String configuration,
                         final String input,
                         final StepResult result,
                         final Walk walk,
                         final boolean last) {
        if (!result.passed()) {
            return Judged.failed(result);
        }
        final Verdict verdict = over.judge(Attempted.of(runner, input, result));
        if (!verdict.passed()) {
            return new Judged(Scorecard.outcome(verdict), verdict.feedback(), null);
        }
        if (!walk.targets.isEmpty()) {
            if (runner.parser() && checks.contains(Check.PRESERVATION)) {
                final List<StoredError> lacking = TargetChecks.preservation(result.output(), walk.targets);
                if (!lacking.isEmpty()) {
                    return new Judged(StepOutcome.PRESERVATION_SHORT, lacking, null);
                }
            } else if (last && checks.contains(Check.FIDELITY)) {
                final List<StoredError> unlike = TargetChecks.fidelity(result.output(), walk.targets);
                if (!unlike.isEmpty()) {
                    final List<StoredError> lacking = walk.raw
                            ? TargetChecks.preservation(input, walk.targets)
                            : List.of();
                    return lacking.isEmpty()
                            ? new Judged(StepOutcome.FIDELITY_SHORT, unlike, null)
                            : new Judged(StepOutcome.PRESERVATION_SHORT, lacking, null);
                }
            }
        }
        return new Judged(StepOutcome.PASSED, List.of(), new LearnedStep(runner, configuration, result, verdict));
    }

    /**
     * The records a target is proposed over where no split has been asked: the sample's top-level
     * children where it is already records, its non-blank lines where it is raw text.
     */
    private static List<String> recordsOf(final Sample sample, final boolean raw) {
        if (raw) {
            return sample.text().lines().filter(line -> !line.isBlank()).toList();
        }
        return OutputRecords.parse(sample.text())
                .map(parsed -> parsed.records().stream().map(Object::toString).toList())
                .orElse(List.of(sample.text()));
    }

    private static int candidates(final PlanStep step, final ShapeshifterAiDoc policy) {
        return step.getCandidates() == null
                ? policy.getMaxAttempts()
                : step.getCandidates();
    }

    /**
     * The checks that judge a step: those it names, or the kind's own.
     */
    private static Set<Check> checks(final PlanStep step, final Set<Check> defaults) {
        return step.getChecks().isEmpty()
                ? defaults
                : EnumSet.copyOf(step.getChecks());
    }

    /**
     * Each record as its text: the one data value the split was asked to emit, or every data value of the
     * record joined, where it emitted more.
     */
    private static List<String> recordTexts(final String recordsDocument) {
        return OutputRecords.parse(recordsDocument)
                .map(parsed -> parsed.records().stream()
                        .map(record -> parsed.evaluate(record,
                                        "string-join(descendant::*[local-name() = 'data']/@value, ' ')")
                                .itemAt(0).getStringValue())
                        .filter(text -> !text.isBlank())
                        .toList())
                .orElse(List.of());
    }

    private static Abandoned abandoned(final String reason,
                                       final List<StoredError> diagnostics,
                                       final List<Exchange> transcript) {
        return new Abandoned(reason, List.copyOf(diagnostics), List.copyOf(transcript));
    }

    private String ask(final Walk walk, final PlanStep step, final int candidate, final Question question) {
        budget.check(clock.millis(), advisor.tokensUsed());
        final String reply = advisor.ask(List.copyOf(walk.transcript), question);
        walk.transcript.add(new Exchange(question, reply, step.effectiveId(), candidate, null));
        budget.check(clock.millis(), advisor.tokensUsed());
        return reply;
    }

    /**
     * Records how a candidate ended on every turn it took (A28): the turns of this step and candidate
     * not yet judged.
     */
    private static void judge(final List<Exchange> transcript,
                              final PlanStep step,
                              final int candidate,
                              final StepOutcome outcome) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            final Exchange turn = transcript.get(i);
            if (turn.outcome() != null || !step.effectiveId().equals(turn.step()) || turn.candidate() != candidate) {
                return;
            }
            transcript.set(i, turn.judged(outcome));
        }
    }

    private static List<StoredError> refusal(final String message) {
        return List.of(new StoredError(Severity.FATAL_ERROR, null, DIALOGUE, message));
    }

    /**
     * One candidate of a step's unit of work, asked and judged.
     */
    @FunctionalInterface
    private interface Unit {

        Judged attempt(int candidate, List<StoredError> feedback);
    }

    /**
     * How one candidate ended, with what to tell the model if it did not pass and what was learned if it did.
     */
    private record Judged(StepOutcome outcome, List<StoredError> feedback, LearnedStep learned) {

        static Judged passed() {
            return new Judged(StepOutcome.PASSED, List.of(), null);
        }

        static Judged refused(final String message) {
            return new Judged(StepOutcome.REFUSED, refusal(message), null);
        }

        /**
         * A step that did not pass the gate of §8.1: nothing ran where it left no output, which is the compile
         * gate; it ran and raised errors otherwise.
         */
        static Judged failed(final StepResult result) {
            return new Judged(result.output() == null
                    ? StepOutcome.COMPILE_FAILED
                    : StepOutcome.RUN_FAILED, result.diagnostics(), null);
        }
    }

    /**
     * Where a step's visit leaves the walk: at the next step, at the step a transition names, or abandoned.
     */
    private record Visit(Kind kind, Transition transition, String reason, List<StoredError> feedback) {

        static Visit next() {
            return new Visit(Kind.NEXT, null, null, List.of());
        }

        static Visit take(final Transition transition, final List<StoredError> feedback) {
            return new Visit(Kind.GOTO, transition, null, feedback);
        }

        static Visit abandon(final String reason, final List<StoredError> feedback) {
            return new Visit(Kind.ABANDON, null, reason, feedback);
        }

        enum Kind {
            NEXT,
            GOTO,
            ABANDON
        }
    }

    /**
     * The state of one attempt as the plan is walked: what has been settled, learned and carried so far.
     */
    private static final class Walk {

        private final ShapeshifterAiDoc policy;
        private final Sample sample;
        private final List<StoredError> opening;
        private final List<Exchange> transcript = new ArrayList<>();
        private final Set<String> taken = new HashSet<>();
        private List<StoredError> carried = List.of();
        private List<String> chain;
        private boolean raw;
        private String split;
        private List<String> records;
        private List<Target> targets = List.of();
        private LearnedStep[] learned = new LearnedStep[0];
        private String[] previous = new String[0];

        private Walk(final ShapeshifterAiDoc policy, final Sample sample, final List<StoredError> opening) {
            this.policy = policy;
            this.sample = sample;
            this.opening = opening;
        }

        /**
         * The chain settled, or settled again: what was cut, aimed at and learned was over the old chain
         * and is forgotten with it. Raw text is a first element that is a parser with a configuration to
         * write; otherwise the input is already records — the sample's top-level children — and the guards
         * read accordingly.
         */
        private void chosen(final List<String> chain, final Map<String, StepRunner> runners) {
            this.chain = List.copyOf(chain);
            final StepRunner first = runners.get(chain.get(0));
            raw = first.parser() && first.configured().isPresent();
            split = null;
            records = null;
            targets = List.of();
            learned = new LearnedStep[chain.size()];
            previous = new String[chain.size()];
        }

        private StepRunner first(final Map<String, StepRunner> runners) {
            return runners.get(chain.get(0));
        }

        private void cut(final String split, final List<String> records) {
            this.split = split;
            this.records = records;
        }

        /**
         * An element learned, or learned again: what the elements after it produced was over its old output
         * and is forgotten, so that a plan which stops re-configuring them is caught at the end.
         */
        private void learn(final int index, final LearnedStep step) {
            learned[index] = step;
            if (step != null) {
                Arrays.fill(learned, index + 1, learned.length, null);
            }
        }

        /**
         * The chain positions a CONFIGURE step's role names: the parser is the first element where it parses;
         * the transform is every element after it; no role is every element.
         */
        private List<Integer> elements(final ConfigureRole role, final Map<String, StepRunner> runners) {
            final boolean parserFirst = runners.get(chain.get(0)).parser();
            final List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < chain.size(); i++) {
                final boolean parser = i == 0 && parserFirst;
                if (role == null || (role == ConfigureRole.PARSER) == parser) {
                    indices.add(i);
                }
            }
            return indices;
        }
    }

    /**
     * The attempt's budget (A5): how long it may take and how many tokens it may spend, from where it began.
     */
    private record Budget(ShapeshifterAiDoc policy, long startedMs, long tokensAtStart) {

        void check(final long nowMs, final long tokensNow) {
            final long elapsed = nowMs - startedMs;
            if (elapsed > policy.getAttemptBudgetMs()) {
                throw new BudgetExhausted("The attempt's budget of " + policy.getAttemptBudgetMs()
                                          + " ms is spent after " + elapsed + " ms");
            }
            final long tokens = tokensNow - tokensAtStart;
            if (policy.getTokenBudget() != null && tokens > policy.getTokenBudget()) {
                throw new BudgetExhausted("The attempt's budget of " + policy.getTokenBudget()
                                          + " tokens is spent after " + tokens + " tokens");
            }
        }
    }

    private static final class BudgetExhausted extends RuntimeException {

        private BudgetExhausted(final String message) {
            super(message);
        }
    }
}
