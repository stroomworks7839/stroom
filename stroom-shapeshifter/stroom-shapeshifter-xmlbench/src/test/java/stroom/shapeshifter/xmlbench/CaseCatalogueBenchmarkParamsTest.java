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
import org.openjdk.jmh.annotations.Param;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The benchmark measures every case the corpus has.
 *
 * <p>JMH's {@code @Param} needs a compile-time constant, so {@link CaseCatalogueBenchmark}
 * cannot read {@link CaseCorpus#names()} at the point it declares its cases — the two lists
 * are written twice and can drift apart in silence. They did: {@code arithmetic},
 * {@code value_types}, {@code comparison} and {@code dates} were added to the corpus, proved
 * byte-identical against Saxon at amplified scale, and then measured by nothing at all,
 * because the benchmark's own list had never heard of them. A case that is correct but
 * unmeasured is exactly the case a performance claim will later be made about.
 */
class CaseCatalogueBenchmarkParamsTest {

    @Test
    void everyCorpusCaseIsMeasured() throws Exception {
        final Param declared = CaseCatalogueBenchmark.class
                .getField("benchCase")
                .getAnnotation(Param.class);
        assertThat(List.of(declared.value()))
                .as("CaseCatalogueBenchmark's @Param must list every case in CaseCorpus.UNITS")
                .containsExactlyInAnyOrderElementsOf(CaseCorpus.names());
    }
}
