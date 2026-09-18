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
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
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
 * One attempt of the dialogue proposed as ruling A21: settle the shape of the fragment first, then ask
 * for one configuration per document-bearing element in chain order, running the chain as far as it has
 * been built before each question so that the next question carries real input. A step that fails the
 * gate or the scorecard is re-asked with the diagnostics of what failed; the steps before it are kept.
 * <p>
 * The shape is chosen from the document's allowed elements that this dialogue can actually run: offering
 * the model an element there is no runner for would be a trap. A Shapeshifter AI document that allows nothing
 * runnable is
 * abandoned before the model is asked anything.
 * <p>
 * The document's {@code maxAttempts} bounds the re-asks of any one question. Its wall-clock and token
 * budgets are not enforced here: this is the dialogue, and budgets belong to whatever runs it under a
 * task context.
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
        final Chosen chosen = chain(policy, allowed, sample, opening, transcript);
        if (chosen.chain() == null) {
            return abandoned("No usable chain after " + policy.getMaxAttempts() + " candidates",
                    chosen.refusal(), transcript);
        }

        final List<LearnedStep> chain = new ArrayList<>();
        String input = sample.text();
        for (final String elementType : chosen.chain()) {
            final StepRunner runner = runners.get(elementType);
            final Optional<StepRunner.Configured> configured = runner.configured();
            if (configured.isEmpty()) {
                final StepResult result = runner.run(null, input);
                final Tried tried = judge(runner, null, input, result);
                if (tried.learned() == null) {
                    return abandoned(elementType + " failed on its input", tried.diagnostics(), transcript);
                }
                chain.add(tried.learned());
            } else {
                final Tried tried = configure(policy, runner, configured.get(), sample, input, opening, transcript);
                if (tried.learned() == null) {
                    return abandoned("No passing configuration for " + elementType + " after "
                                     + policy.getMaxAttempts() + " attempts", tried.diagnostics(), transcript);
                }
                chain.add(tried.learned());
            }
            input = chain.get(chain.size() - 1).result().output();
        }
        return new Learned(List.copyOf(chain), input, List.copyOf(transcript));
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
                         final List<StoredError> opening,
                         final List<Exchange> transcript) {
        if (allowed.size() == 1) {
            return new Chosen(allowed, List.of());
        }
        final Set<String> allowedSet = new HashSet<>(allowed);
        List<StoredError> feedback = opening;
        for (int candidate = 0; candidate < policy.getMaxAttempts(); candidate++) {
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
     * @return The step that passed, or the diagnostics of the last try if none did.
     */
    private Tried configure(final ShapeshifterAiDoc policy,
                            final StepRunner runner,
                            final StepRunner.Configured configured,
                            final Sample sample,
                            final String input,
                            final List<StoredError> opening,
                            final List<Exchange> transcript) {
        String previous = null;
        List<StoredError> feedback = opening;
        for (int attempt = 0; attempt < policy.getMaxAttempts(); attempt++) {
            final Configuration question = new Configuration(
                    runner.elementType(), configured.documentType(), sample, input, previous, feedback);
            final String reply = ask(transcript, question);
            final Optional<String> configuration = ConfigurationReply.configuration(reply);
            if (configuration.isEmpty()) {
                feedback = refusal("The reply was not a single " + configured.documentType() + " document");
                continue;
            }
            previous = configuration.get();
            final Tried tried = judge(runner, previous, input, runner.run(previous, input));
            if (tried.learned() != null) {
                return tried;
            }
            feedback = tried.diagnostics();
        }
        return new Tried(null, feedback);
    }

    /**
     * The gate of §8.1 first — a step that did not run has nothing to score — then the scorecard.
     */
    private Tried judge(final StepRunner runner,
                        final String configuration,
                        final String input,
                        final StepResult result) {
        if (!result.passed()) {
            return new Tried(null, result.diagnostics());
        }
        final Verdict verdict = scorecard.judge(Attempted.of(runner, input, result));
        return verdict.passed()
                ? new Tried(new LearnedStep(runner, configuration, result, verdict), List.of())
                : new Tried(null, verdict.feedback());
    }

    /**
     * @param learned     The step that passed, or null if none did within the attempts.
     * @param diagnostics What the last try reported when nothing passed.
     */
    private record Tried(LearnedStep learned, List<StoredError> diagnostics) {

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

    private static List<StoredError> refusal(final String message) {
        return List.of(new StoredError(Severity.FATAL_ERROR, null, DIALOGUE, message));
    }
}
