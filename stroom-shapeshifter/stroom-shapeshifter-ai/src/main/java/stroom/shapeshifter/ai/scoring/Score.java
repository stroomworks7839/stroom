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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.shared.ScorerType;
import stroom.util.shared.StoredError;

import java.util.List;

/**
 * One scorer's judgement of one step: a value in [0, 1] and the diagnostics that explain it, which are
 * what the model is told when the score loses marks (design §10).
 */
public record Score(ScorerType type, double value, List<StoredError> diagnostics) {

    public Score {
        if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
            throw new IllegalArgumentException("A score is a value in [0, 1], not " + value);
        }
        diagnostics = List.copyOf(diagnostics);
    }
}
