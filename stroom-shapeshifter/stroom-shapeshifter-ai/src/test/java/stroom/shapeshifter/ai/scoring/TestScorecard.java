/*
 * Copyright 2026 Crown Copyright
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

import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.StepOutcome;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestScorecard {

    private static Judgement judgement(final ScorerType type, final boolean gate, final double value) {
        return new Judgement(new ScorerSetting(type, 1.0, 0.5, gate, null), new Score(type, value, List.of()));
    }

    @Test
    void theOutcomeIsTheFailedGateBeforeAnyOtherShortfall() {
        // A document lists yield before extraction quality; both fall short; the gate is what decided it.
        final Verdict verdict = new Verdict(List.of(
                judgement(ScorerType.YIELD, false, 0.4),
                judgement(ScorerType.EXTRACTION_QUALITY, true, 0.2)), 0.3);
        assertThat(Scorecard.outcome(verdict)).isEqualTo(StepOutcome.QUALITY_SHORT);
        assertThat(Scorecard.outcome(new Verdict(List.of(
                judgement(ScorerType.YIELD, false, 0.4),
                judgement(ScorerType.EXTRACTION_QUALITY, false, 0.2)), 0.3))).isEqualTo(StepOutcome.YIELD_SHORT);
        assertThat(Scorecard.outcome(new Verdict(List.of(
                judgement(ScorerType.COMPILE, true, 0.0)), 0.0))).isEqualTo(StepOutcome.COMPILE_FAILED);
        assertThat(Scorecard.outcome(new Verdict(List.of(judgement(ScorerType.YIELD, false, 1.0)), 1.0)))
                .isEqualTo(StepOutcome.PASSED);
    }
}
