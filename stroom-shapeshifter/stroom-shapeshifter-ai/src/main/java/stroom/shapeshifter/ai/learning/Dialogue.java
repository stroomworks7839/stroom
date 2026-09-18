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
import stroom.shapeshifter.shared.DialogueDefinition;
import stroom.shapeshifter.shared.DialogueStep;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepGuard;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One attempt of the dialogue of ruling A21 as ruling A31 reshapes it: settle the chain; settle what one
 * record is (<i>Split</i>), for every kind of input and without a target; propose what each kind of record
 * should become (<i>Target</i>), validated at once and open to review; then ask for one configuration
 * per document-bearing element in chain order, each running the chain as far as it has been built so
 * that the next question carries real input, the parser held to preserving what the targets need and
 * the transform to reproducing them. A step that fails is re-asked with the diagnostics of what failed;
 * the steps before it are kept.
 * <p>
 * The chain is chosen from the document's allowed elements that this dialogue can actually run: offering
 * the model an element there is no runner for would be a trap. A document that allows nothing runnable is
 * abandoned before the model is asked anything.
 * <p>
 * The document says which of these are asked, when, with what limits and in what words (A32, A33; design
 * 01 §10.2): its {@link DialogueDefinition} is an ordered list of the four question kinds, each with a
 * guard — always, only for raw text, only for input that is already records — and the candidates it may
 * spend. This class holds the kinds and their judges and walks the list; a definition it cannot hold is
 * abandoned before the model is asked, naming what is wrong.
 */
public final class Dialogue {

    private static final ElementId DIALOGUE = new ElementId("Dialogue");

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
        final List<Exchange> transcript = new ArrayList<>();
        budget = new Budget(policy, clock.millis(), advisor.tokensUsed());
        try {
            return dialogue(policy, sample, opening, transcript);
        } catch (final BudgetExhausted e) {
            // A5: an attempt has a wall-clock and token budget for the whole dialogue, whichever mode; one that
            // runs out is abandoned, not left to block a task or spend without end.
            return abandoned(e.getMessage(), List.of(), transcript);
        }
    }

    private Outcome dialogue(final ShapeshifterAiDoc policy,
                             final Sample sample,
                             final List<StoredError> opening,
                             final List<Exchange> transcript) {
        final List<String> allowed = policy.getAllowedElements().stream()
                .filter(runners::containsKey)
                .toList();
        if (allowed.isEmpty()) {
            return abandoned("The document allows no element this dialogue can run: "
                             + policy.getAllowedElements(), List.of(), transcript);
        }
        final DialogueDefinition definition = policy.getDialogue();
        final List<String> problems = new ArrayList<>(definition.problems());
        problems.addAll(Templates.problems(definition));
        if (!problems.isEmpty()) {
            return abandoned("The document's dialogue cannot be held (see its Learning tab): "
                             + String.join("; ", problems), List.of(), transcript);
        }
        final List<DialogueStep> steps = definition.effectiveSteps();

        final Chosen chosen = chain(policy, allowed, sample, candidates(steps.get(0), policy), opening, transcript);
        if (chosen.chain() == null) {
            return abandoned("No usable chain after " + candidates(steps.get(0), policy) + " candidates",
                    chosen.refusal(), transcript);
        }
        final StepRunner first = runners.get(chosen.chain().get(0));
        // Raw text: the first element is a parser with a configuration to write. Otherwise the input is
        // already records — the sample's top-level children — and the guards read accordingly.
        final boolean raw = first.parser() && first.configured().isPresent();

        // A31: what one record is, then what each kind should become, before any configuration — where
        // the document asks for them, in the order it asks.
        String split = null;
        List<String> records = null;
        List<Target> targets = List.of();
        for (final DialogueStep step : steps.subList(1, steps.size() - 1)) {
            if (step.getWhen() == StepGuard.TEXT && !raw || step.getWhen() == StepGuard.XML && raw) {
                continue;
            }
            switch (step.getKind()) {
                case SPLIT -> {
                    final Cut cut = cut(policy, first, sample, candidates(step, policy), opening, transcript);
                    if (cut.records() == null) {
                        return abandoned("No record boundary after " + candidates(step, policy) + " candidates",
                                cut.refusal(), transcript);
                    }
                    split = cut.split();
                    records = cut.records();
                }
                case TARGET -> {
                    final List<String> over = records == null
                            ? recordsOf(sample, raw)
                            : records;
                    final int kinds = step.getKinds() == null
                            ? DialogueStep.DEFAULT_KINDS
                            : step.getKinds();
                    final List<Target> proposed = new ArrayList<>();
                    final List<String> representatives = TargetChecks.representatives(over, kinds);
                    for (int i = 0; i < representatives.size(); i++) {
                        final Aimed aimed = target(policy, sample, representatives.get(i), i + 1,
                                representatives.size(), TargetChecks.count(over, representatives.get(i)),
                                candidates(step, policy), opening, transcript);
                        if (aimed.target() == null) {
                            return abandoned("No acceptable target for record kind " + (i + 1) + " after "
                                             + candidates(step, policy) + " candidates", aimed.refusal(), transcript);
                        }
                        proposed.add(aimed.target());
                    }
                    targets = List.copyOf(proposed);
                }
                default -> {
                    // CHAIN and CONFIGURE hold the ends of the list; problems() has said so.
                }
            }
        }

        final int configureCandidates = candidates(steps.get(steps.size() - 1), policy);
        final List<LearnedStep> chain = new ArrayList<>();
        String input = sample.text();
        for (final String elementType : chosen.chain()) {
            final StepRunner runner = runners.get(elementType);
            // Only the chain's last element produces the events the targets are; a transform before it is
            // held to the scorecard alone.
            final boolean last = chain.size() == chosen.chain().size() - 1;
            final Optional<StepRunner.Configured> configured = runner.configured();
            if (configured.isEmpty()) {
                final StepResult result = runner.run(null, input);
                final Tried tried = judge(runner, null, input, result, targets, last);
                if (tried.learned() == null) {
                    return abandoned(elementType + " failed on its input", tried.diagnostics(), transcript);
                }
                chain.add(tried.learned());
            } else {
                final Tried tried = configure(policy, runner, configured.get(), sample, input,
                        runner.parser()
                                ? split
                                : null, targets, last, configureCandidates, opening, transcript);
                if (tried.learned() == null) {
                    return abandoned("No passing configuration for " + elementType + " after "
                                     + configureCandidates + " attempts", tried.diagnostics(), transcript);
                }
                chain.add(tried.learned());
            }
            input = chain.get(chain.size() - 1).result().output();
        }
        return new Learned(List.copyOf(chain), input, targets, List.copyOf(transcript));
    }

    /**
     * With one element there is nothing to choose, so the question is not asked (A21).
     *
     * @return The chain, or null with the refusal of the last reply — which never reached a question and
     * would otherwise be lost.
     */
    private Chosen chain(final ShapeshifterAiDoc policy,
                         final List<String> allowed,
                         final Sample sample,
                         final int candidates,
                         final List<StoredError> opening,
                         final List<Exchange> transcript) {
        if (allowed.size() == 1) {
            return new Chosen(allowed, List.of());
        }
        final Set<String> allowedSet = new HashSet<>(allowed);
        List<StoredError> feedback = opening;
        for (int candidate = 0; candidate < candidates; candidate++) {
            final Chain question = new Chain(sample, allowed, feedback);
            final String reply = ask(transcript, question);
            final Optional<List<String>> chain = ChainReply.chain(reply, allowedSet);
            if (chain.isPresent()) {
                return new Chosen(chain.get(), List.of());
            }
            feedback = refusal("The reply was not a chain of allowed element types: " + allowed);
        }
        return new Chosen(null, feedback);
    }

    private record Chosen(List<String> chain, List<StoredError> refusal) {

    }

    /**
     * What one record is. For an input that is already records — the first element is not a parser — the
     * records are the sample's own top-level children and nothing is asked. For raw text the parser's
     * runner is asked for a configuration that cuts and emits each record whole, judged by the scorecard
     * over the sample — coverage and yield, since nothing about meaning is in play yet — and by whether
     * the records emitted carry the input whole, which coverage alone cannot tell.
     */
    private Cut cut(final ShapeshifterAiDoc policy,
                    final StepRunner first,
                    final Sample sample,
                    final int candidates,
                    final List<StoredError> opening,
                    final List<Exchange> transcript) {
        if (!first.parser() || first.configured().isEmpty()) {
            return new Cut(null, recordsOf(sample, false), List.of());
        }
        final StepRunner.Configured configured = first.configured().get();
        List<StoredError> feedback = opening;
        for (int candidate = 0; candidate < candidates; candidate++) {
            final Split question = new Split(sample, first.elementType(), configured.documentType(), feedback);
            final String reply = ask(transcript, question);
            final Optional<String> configuration = ConfigurationReply.configuration(reply);
            if (configuration.isEmpty()) {
                feedback = refusal("The reply was not a single " + configured.documentType() + " document");
                continue;
            }
            final StepResult result = first.run(configuration.get(), sample.text());
            if (!result.passed()) {
                feedback = result.diagnostics();
                continue;
            }
            final Verdict verdict = scorecard.judge(Attempted.of(first, sample.text(), result));
            if (!verdict.passed()) {
                feedback = verdict.feedback();
                continue;
            }
            final List<String> records = recordTexts(result.output());
            if (records.isEmpty()) {
                feedback = refusal("The split produced no records; each record should be emitted whole, as one "
                                   + "data value");
                continue;
            }
            final Optional<StoredError> partial = TargetChecks.wholeness(sample.text(), records);
            if (partial.isPresent()) {
                feedback = List.of(partial.get());
                continue;
            }
            return new Cut(configuration.get(), records, List.of());
        }
        return new Cut(null, null, feedback);
    }

    private record Cut(String split, List<String> records, List<StoredError> refusal) {

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

    private static int candidates(final DialogueStep step, final ShapeshifterAiDoc policy) {
        return step.getCandidates() == null
                ? policy.getMaxAttempts()
                : step.getCandidates();
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

    /**
     * What one kind of record should become: proposed by the model, judged at once by the scorers of
     * meaning over the wrapped event, re-asked with the shortfall of the document it wrote; or
     * {@code none}, for a kind that yields no event. A header or a comment is seen once in a sample; a kind
     * seen more than once is what the feed exists to report, so its first {@code none} is questioned and
     * only its second taken (design 02 §6.3).
     */
    private Aimed target(final ShapeshifterAiDoc policy,
                         final Sample sample,
                         final String record,
                         final int kind,
                         final int total,
                         final int seen,
                         final int candidates,
                         final List<StoredError> opening,
                         final List<Exchange> transcript) {
        List<StoredError> feedback = opening;
        boolean noneQuestioned = false;
        // Questioning a none is not a candidate spent: with one candidate, a legitimate none must still pass.
        int spent = 0;
        while (spent < candidates) {
            final TargetFor question = new TargetFor(sample, record, kind, total, feedback);
            final String reply = ask(transcript, question);
            if (TargetChecks.NONE.equalsIgnoreCase(reply.strip())) {
                if (seen > 1 && !noneQuestioned) {
                    noneQuestioned = true;
                    feedback = List.of(new StoredError(Severity.WARNING, null, DIALOGUE, "This kind of record is "
                            + "seen " + seen + " times in the sample; a header or a comment is seen once. If these "
                            + "records carry no event, reply none again; otherwise write the event, with the "
                            + "fields the record has and without those it lacks"));
                    continue;
                }
                return new Aimed(Target.none(record), List.of());
            }
            spent++;
            final Optional<String> event = ConfigurationReply.configuration(reply);
            if (event.isEmpty()) {
                feedback = refusal("The reply was not a single event document, nor the word none");
                continue;
            }
            feedback = TargetChecks.judge(scorecard, record, TargetChecks.asDocument(event.get()));
            if (feedback.isEmpty()) {
                return new Aimed(Target.of(record, event.get()), List.of());
            }
        }
        return new Aimed(null, feedback);
    }

    /**
     * @param target  The target accepted, or null where none was.
     * @param refusal The shortfalls of the last candidate where none was accepted.
     */
    private record Aimed(Target target, List<StoredError> refusal) {

    }

    /**
     * @return The step that passed, or the diagnostics of the last try if none did.
     */
    private Tried configure(final ShapeshifterAiDoc policy,
                            final StepRunner runner,
                            final StepRunner.Configured configured,
                            final Sample sample,
                            final String input,
                            final String split,
                            final List<Target> targets,
                            final boolean last,
                            final int candidates,
                            final List<StoredError> opening,
                            final List<Exchange> transcript) {
        String previous = null;
        List<StoredError> feedback = opening;
        for (int attempt = 0; attempt < candidates; attempt++) {
            final Configuration question = new Configuration(
                    runner.elementType(), configured.documentType(), sample, input, previous, split, targets, feedback);
            final String reply = ask(transcript, question);
            final Optional<String> configuration = ConfigurationReply.configuration(reply);
            if (configuration.isEmpty()) {
                feedback = refusal("The reply was not a single " + configured.documentType() + " document");
                continue;
            }
            previous = configuration.get();
            final Tried tried = judge(runner, previous, input, runner.run(previous, input), targets, last);
            if (tried.learned() != null) {
                return tried;
            }
            feedback = tried.diagnostics();
        }
        return new Tried(null, feedback);
    }

    /**
     * The gate of §8.1 first — a step that did not run has nothing to score — then the scorecard, then
     * the targets (A31): a parser must preserve what they need, a transform must reproduce them.
     */
    private Tried judge(final StepRunner runner,
                        final String configuration,
                        final String input,
                        final StepResult result,
                        final List<Target> targets,
                        final boolean last) {
        if (!result.passed()) {
            return new Tried(null, result.diagnostics());
        }
        final Verdict verdict = scorecard.judge(Attempted.of(runner, input, result));
        if (!verdict.passed()) {
            return new Tried(null, verdict.feedback());
        }
        if (!targets.isEmpty()) {
            final List<StoredError> shortfalls = runner.parser()
                    ? TargetChecks.preservation(result.output(), targets)
                    : last
                            ? TargetChecks.fidelity(result.output(), targets)
                            : List.of();
            if (!shortfalls.isEmpty()) {
                return new Tried(null, shortfalls);
            }
        }
        return new Tried(new LearnedStep(runner, configuration, result, verdict), List.of());
    }

    /**
     * @param learned     The step that passed, or null if none did within the attempts.
     * @param diagnostics What the last try reported when nothing passed.
     */
    private record Tried(LearnedStep learned, List<StoredError> diagnostics) {

    }

    private static List<StoredError> lastFeedback(final List<Exchange> transcript) {
        return transcript.isEmpty()
                ? List.of()
                : transcript.get(transcript.size() - 1).question().feedback();
    }

    private static Abandoned abandoned(final String reason,
                                       final List<StoredError> diagnostics,
                                       final List<Exchange> transcript) {
        return new Abandoned(reason, List.copyOf(diagnostics), List.copyOf(transcript));
    }

    private String ask(final List<Exchange> transcript, final Question question) {
        budget.check(clock.millis(), advisor.tokensUsed());
        final String reply = advisor.ask(List.copyOf(transcript), question);
        transcript.add(new Exchange(question, reply));
        budget.check(clock.millis(), advisor.tokensUsed());
        return reply;
    }

    private static List<StoredError> refusal(final String message) {
        return List.of(new StoredError(Severity.FATAL_ERROR, null, DIALOGUE, message));
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
