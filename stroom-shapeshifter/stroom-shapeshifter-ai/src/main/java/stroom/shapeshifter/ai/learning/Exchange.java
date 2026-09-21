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

import stroom.shapeshifter.shared.StepOutcome;

/**
 * One turn of the dialogue: a question as put, the reply as given, and — once judged — which step of the
 * plan asked it, which candidate it was, and how it ended (A28, A37). The outcome is null until the
 * candidate is judged, and stays null for a turn that was never judged, such as a reply the budget cut off.
 */
public record Exchange(Question question, String reply, String step, int candidate, StepOutcome outcome) {

    public Exchange(final Question question, final String reply) {
        this(question, reply, null, 0, null);
    }

    /// This turn with its outcome known.
    public Exchange judged(final StepOutcome outcome) {
        return new Exchange(question, reply, step, candidate, outcome);
    }
}
