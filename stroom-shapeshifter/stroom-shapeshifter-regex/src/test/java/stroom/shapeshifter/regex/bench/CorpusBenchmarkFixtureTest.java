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

package stroom.shapeshifter.regex.bench;

import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Flag;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every benchmark workload's pattern must match the records its template generates, on both
 * engines. A workload whose pattern quietly stopped matching would still produce a score — of
 * scanning and failing, which is a different measurement wearing the same name.
 */
class CorpusBenchmarkFixtureTest {

    @Test
    void everyWorkloadMatchesItsOwnRecords() {
        for (final CorpusBenchmark.Workload workload : CorpusBenchmark.Workload.values()) {
            final String record = String.format(workload.template(), 7, 7);
            final BytePattern ours = BytePattern.compile(workload.pattern(), Flag.MULTILINE);
            final Pattern theirs = Pattern.compile(workload.pattern(), Pattern.MULTILINE);

            if (workload == CorpusBenchmark.Workload.SPARSE) {
                // Never matching is this workload's whole point — but both engines must agree
                // on that too, or it measures different scanning.
                assertThat(ours.matcher().find(record.getBytes(StandardCharsets.UTF_8)))
                        .as("%s must not match on this engine", workload)
                        .isFalse();
                assertThat(theirs.matcher(record).find())
                        .as("%s must not match on the JDK", workload)
                        .isFalse();
                continue;
            }
            assertThat(ours.matcher().find(record.getBytes(StandardCharsets.UTF_8)))
                    .as("%s: this engine should match %s", workload, record)
                    .isTrue();
            assertThat(theirs.matcher(record).find())
                    .as("%s: the JDK should match %s", workload, record)
                    .isTrue();
            assertThat(BytePattern.compileForcing(stroom.shapeshifter.regex.Engine.TREE,
                            workload.pattern(),
                            java.util.EnumSet.of(Flag.MULTILINE))
                    .matcher().find(record.getBytes(StandardCharsets.UTF_8)))
                    .as("%s: the tree engine should match %s", workload, record)
                    .isTrue();
        }
    }
}
