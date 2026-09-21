/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The run's timing as shares, ratios and heat; absolutes as detail. */
class ProfileTest {

    private static TraceModel trace() {
        return new TraceModel(new ShapeshifterTrace(true, "", "", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 0,
                List.of(new Timing("cheap", 100, 50, 100_000), new Timing("dear", 10, 0, 300_000)),
                List.of(), 500_000));
    }

    @Test
    void shareIsOfTheTimeSpentAttempting() {
        assertThat(Profile.share(trace(), "cheap")).isEqualTo(0.25);
        assertThat(Profile.share(trace(), "dear")).isEqualTo(0.75);
        assertThat(Profile.share(trace(), "none")).isEqualTo(0);
    }

    @Test
    void costCompareTheTemplatesPerAttemptWithTheRuns() {
        // The run averages 400 000 ns over 110 attempts, ~3.6 µs; cheap is 1 µs, dear is 30 µs.
        assertThat(Profile.cost(trace(), "cheap")).isEqualTo(0);
        assertThat(Profile.cost(trace(), "dear")).isEqualTo(2);
        assertThat(Profile.cost(trace(), "none")).isEqualTo(0);
    }

    @Test
    void describeLeadsWithTheRatios() {
        assertThat(Profile.describe(trace(), "dear"))
                .isEqualTo("10 attempts · 0 matched (0%) · 30 µs per attempt · 300 µs · 75% of the run");
        assertThat(Profile.describe(trace(), "none")).isEqualTo("not tried in this run");
        assertThat(Profile.runTotal(trace())).isEqualTo("500 µs · 110 attempts");
    }

    @Test
    void unitsScale() {
        assertThat(Profile.micros(1_500)).isEqualTo("1.5 µs");
        assertThat(Profile.micros(12_400)).isEqualTo("12 µs");
        assertThat(Profile.micros(2_340_000)).isEqualTo("2.3 ms");
        assertThat(Profile.percent(0.333)).isEqualTo("33%");
    }
}
