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

import stroom.shapeshifter.ai.stage.Attempts.Turn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// An advisor that answers from what an attempt was already answered (A28): the turns a previous run
/// recorded, in the order they were put, and then the model — or nothing, where the attempt is to stop
/// and wait.
///
/// This is how an attempt resumes. The dialogue is not a state machine that saves its own workings: it is
/// re-walked from the start with the answers it was given, which re-derives everything those answers
/// produced — the chain, the boundary, the records, the targets, each element's configuration and output
/// — because all of it follows from the sample and the answers, both of which are kept. What the model
/// said is a record; what running produced is a consequence, and consequences are cheaper to re-derive
/// than to store, being the stream's own text besides (A38).
///
/// A replayed answer is not re-scored by hand: the walk judges it exactly as it judged it the first time.
/// That the walk has reached the same place is checked rather than assumed — every replayed answer is
/// given only to the question the record says it answered, and a walk that asks anything else has
/// diverged and is refused (see [ReplayDiverged]). An attempt that resumes therefore either reaches the
/// state it stopped in or says it could not, and never binds a configuration learned from a mixture of
/// two walks.
public final class RecordedAdvisor implements Advisor {

    private final List<Turn> answered = new ArrayList<>();
    private final Advisor then;
    private int next;

    /// @param turns The turns of the attempt being resumed, in order; those with no answer are the ones it
    ///              stopped at and are not replayed.
    /// @param then  Who answers once the record runs out: the model for an attempt being carried on, or
    ///              [#awaiting()] for one that is to stop and wait for the worker.
    public RecordedAdvisor(final List<Turn> turns, final Advisor then) {
        turns.stream()
                .sorted(Comparator.comparingInt(Turn::number))
                .filter(turn -> turn.answer() != null)
                .forEach(answered::add);
        this.then = then;
    }

    /// An advisor that answers nothing: the dialogue stops at the first question the record does not
    /// answer, and the attempt waits for whoever will (A28).
    public static Advisor awaiting() {
        return (transcript, question) -> {
            throw new AwaitingAnswer(question);
        };
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        if (next < answered.size()) {
            final Turn turn = answered.get(next++);
            // The summary names the kind of question and what it is about, so one comparison covers both.
            if (!question.summary().equals(turn.question())) {
                throw new ReplayDiverged(turn, question);
            }
            return turn.answer();
        }
        return then.ask(transcript, question);
    }

    @Override
    public long tokensUsed() {
        // What the record cost was charged when it was first asked; this advisor charges for what it adds.
        return then.tokensUsed();
    }


    // --------------------------------------------------------------------------------


    /// Raised where a resumed walk asks something other than what the record says it asked at that turn
    /// (A45): the plan, the document or the sample has changed under the attempt, so the answers kept are
    /// not answers to the questions now being put. The attempt is refused rather than carried on, since an
    /// answer given to the wrong question would be judged, configured and possibly bound.
    public static final class ReplayDiverged extends RuntimeException {

        ReplayDiverged(final Turn turn, final Question question) {
            super("The attempt cannot be resumed: turn " + turn.number() + " answered '" + turn.question()
                  + "' but the attempt now asks '" + question.summary() + "'");
        }
    }


    // --------------------------------------------------------------------------------


    /// Raised where an attempt has reached a question nobody present can answer: it stops here, and the
    /// worker or a person takes it up (A28).
    public static final class AwaitingAnswer extends RuntimeException {

        private final transient Question question;

        AwaitingAnswer(final Question question) {
            super("The attempt awaits an answer to its next question");
            this.question = question;
        }

        public Question question() {
            return question;
        }
    }
}
