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

import java.util.List;

/**
 * The AI seam of design §10, as the dialogue of A21 sees it: one question at a time, each carrying the
 * exchanges before it. A node implements this over {@code stroom-ai}; a test implements it with canned
 * replies. Nothing on this side of the seam knows which.
 */
public interface Advisor {

    /**
     * @param transcript Every exchange of the attempt so far, oldest first. Empty for the first question.
     * @param question   The question to put to the model.
     * @return The model's reply, verbatim. Parsing it is the caller's job.
     */
    String ask(List<Exchange> transcript, Question question);

    /**
     * Tokens the model has charged this advisor for so far, where it says; zero where it does not. The
     * dialogue reads it before and after each question to hold an attempt to its token budget (A5).
     */
    default long tokensUsed() {
        return 0;
    }
}
