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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;

import java.util.ArrayList;
import java.util.List;

/**
 * The simulated model of design 02 §3: an ordered list of expected question and scripted reply. Each
 * question the dialogue asks is checked against the next expectation and answered with its reply; a
 * question the script did not expect fails the scenario with both printed, and a script with lines
 * left over fails it at {@link #verifyExhausted()}. The script never looks at a question to decide
 * what to say — its author fixed the outcome.
 */
public final class Script implements Advisor {

    private final List<Line> lines = new ArrayList<>();
    private final List<Question> asked = new ArrayList<>();
    private int next;

    private Script() {
    }

    public static Script of() {
        return new Script();
    }

    public Expecting expect(final QuestionMatcher matcher) {
        return reply -> {
            lines.add(new Line(matcher, reply));
            return this;
        };
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        asked.add(question);
        if (next >= lines.size()) {
            throw new AssertionError("The dialogue asked a question " + (next + 1)
                                     + " the script did not expect:\n" + question);
        }
        final Line line = lines.get(next++);
        if (!line.matcher().matches(question)) {
            throw new AssertionError("Question " + next + " did not match the script.\nExpected: "
                                     + line.matcher().describe() + "\nActual:   " + question);
        }
        return line.reply();
    }

    /**
     * Every question asked, in order, for assertions after the run.
     */
    public List<Question> asked() {
        return List.copyOf(asked);
    }

    public void verifyExhausted() {
        if (next < lines.size()) {
            throw new AssertionError("The script had " + (lines.size() - next)
                                     + " reply(ies) the dialogue never asked for; next expected: "
                                     + lines.get(next).matcher().describe());
        }
    }

    public interface Expecting {

        Script reply(String reply);
    }

    private record Line(QuestionMatcher matcher, String reply) {

    }
}
