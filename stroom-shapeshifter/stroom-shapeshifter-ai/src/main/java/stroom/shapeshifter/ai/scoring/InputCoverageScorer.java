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

import stroom.shapeshifter.ai.extraction.InputCoverage;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerType;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;

/**
 * Input coverage (ruling A11) as a scorer: the smaller of the character and line ratios the split
 * consumed. Applies only to a step that reports record ranges — an extraction step — and only when it
 * produced output; a step that did not run has nothing to cover and is the compile gate's to fail.
 */
public final class InputCoverageScorer implements Scorer {

    private static final ElementId COVERAGE = new ElementId("InputCoverage");

    @Override
    public ScorerType type() {
        return ScorerType.INPUT_COVERAGE;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters, final Attempted step) {
        if (step.result().output() == null || step.result().recordRanges().isEmpty()) {
            return Optional.empty();
        }
        final InputCoverage coverage = InputCoverage.measure(step.input(), step.result().recordRanges());
        final double value = Math.min(coverage.charRatio(), coverage.lineRatio());
        final List<StoredError> diagnostics = value < 1.0
                ? List.of(new StoredError(Severity.WARNING, null, COVERAGE,
                "The split consumed " + coverage.linesCovered() + " of " + coverage.linesTotal()
                + " lines and " + coverage.charsCovered() + " of " + coverage.charsTotal()
                + " characters; the rest of the input was discarded"))
                : List.of();
        return Optional.of(new Score(type(), value, diagnostics));
    }
}
