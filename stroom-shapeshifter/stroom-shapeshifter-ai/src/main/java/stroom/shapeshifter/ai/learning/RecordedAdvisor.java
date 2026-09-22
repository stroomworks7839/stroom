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
/// A replayed answer is not re-scored by hand: the walk judges it exactly as it judged it the first time,
/// so a resumed attempt that reaches the same question has reached the same state.
public final class RecordedAdvisor implements Advisor {

    private final List<String> answers = new ArrayList<>();
    private final Advisor then;
    private int next;

    /// @param turns The turns of the attempt being resumed, in order; those with no answer are the ones it
    ///              stopped at and are not replayed.
    /// @param then  Who answers once the record runs out: the model for an attempt being carried on, or
    ///              [#awaiting()] for one that is to stop and wait for the worker.
    public RecordedAdvisor(final List<Turn> turns, final Advisor then) {
        turns.stream()
                .sorted((a, b) -> Integer.compare(a.number(), b.number()))
                .filter(turn -> turn.answer() != null)
                .forEach(turn -> answers.add(turn.answer()));
        this.then = then;
    }

    /// An advisor that answers nothing: the dialogue stops at the first question the record does not
    /// answer, and the attempt waits for whoever will (A28).
    public static Advisor awaiting() {
        return (transcript, question) -> {
            throw new AwaitingAnswer(question);
        };
    }

    /// How many answers the record holds: the turns a resumed attempt will not ask again.
    public int recorded() {
        return answers.size();
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        if (next < answers.size()) {
            return answers.get(next++);
        }
        return then.ask(transcript, question);
    }

    @Override
    public long tokensUsed() {
        // What the record cost was charged when it was first asked; this advisor charges for what it adds.
        return then.tokensUsed();
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
