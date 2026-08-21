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

package stroom.shapeshifter.xmlbench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidenced memory note (design/13, Phase 2): the ruling dropped {@code SplitFilter} on
 * the expectation that a million records fit without it, and expectations here get evidence.
 * First measured 2026-08-21: 293 MB in, 557 MB out, ~9.6 s, 824 MB of heap after collection
 * against a 6 GB ceiling. The assertion pins only the claim that matters — it fits with room
 * to spare — not the timings, which belong to the benchmark.
 */
class OneMillionHeapSpikeTest {

    @Test
    void aMillionRecordsFitWithoutASplitFilter() throws Exception {
        final byte[] input = RecordsGenerator.generate(1_000_000);
        final byte[] output = XsltBaselineTest.transform(XsltBaselineTest.compile(), input);
        assertThat(output.length).isGreaterThan(input.length);
        System.gc();
        final Runtime runtime = Runtime.getRuntime();
        final long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576;
        assertThat(usedMb)
                .as("heap after the transform, MB")
                .isLessThan(runtime.maxMemory() / 1_048_576 / 2);
    }
}
