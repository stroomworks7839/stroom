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

import stroom.shapeshifter.shared.RecordBoundary;
import stroom.util.shared.StoredError;

import java.util.List;

/**
 * How an attempt ended: with a chain every step of which passed the gate, or abandoned with the reason.
 * Either way the transcript is kept, because it is what the audit log records (design §10) and what the
 * next attempt is told.
 */
public sealed interface Outcome {

    List<Exchange> transcript();

    /**
     * @param chain   The learned steps in chain order.
     * @param output  What the last step produced over the sample: the translation, for a transform stage.
     * @param targets What each kind of record was to become (A31), as validated; empty under the direct
     *                conversation. They go onto the regression set at promotion as its goldens.
     * @param boundary What one record is, as the split settled it (A35), for the rule to carry and the stage
     *                 to count by; null where the input is raw text or no split was asked.
     */
    record Learned(List<LearnedStep> chain,
                   String output,
                   List<Target> targets,
                   List<Exchange> transcript,
                   RecordBoundary boundary) implements Outcome {

    }

    /**
     * @param diagnostics What the last failing step reported, if a step failed; the last question's
     *                    feedback otherwise never reached a question, so it is kept here.
     */
    record Abandoned(String reason, List<StoredError> diagnostics, List<Exchange> transcript) implements Outcome {

    }
}
