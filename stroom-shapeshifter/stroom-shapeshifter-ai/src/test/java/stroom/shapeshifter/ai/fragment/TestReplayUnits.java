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

package stroom.shapeshifter.ai.fragment;

import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.XmlFragmentStep;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.ReplayUnit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ruling A1 (design 01 §4): what a chain can be replayed over is decided by the chain, not declared.
 * A parser in it means the stream, because the bytes above a parser cannot be replayed from a record;
 * no parser means one record, which is what the elements below a parser are given anyway.
 */
class TestReplayUnits {

    private static final Map<String, StepRunner> RUNNERS = List.of(
                    new DataSplitterStep(null), new JsonStep(), new XmlFragmentStep(), new XsltStep())
            .stream()
            .collect(Collectors.toMap(StepRunner::elementType, Function.identity()));

    private static final Predicate<String> PARSES = type -> RUNNERS.containsKey(type)
                                                            && RUNNERS.get(type).parser();

    @Test
    void aChainThatParsesIsReplayedOverTheStream() {
        assertThat(unitOf(List.of("DSParser", "XSLTFilter"))).isEqualTo(ReplayUnit.STREAM);
        assertThat(unitOf(List.of("JSONParser", "XSLTFilter"))).isEqualTo(ReplayUnit.STREAM);
        assertThat(unitOf(List.of("XMLFragmentParser", "XSLTFilter"))).isEqualTo(ReplayUnit.STREAM);
    }

    @Test
    void aChainThatBeginsWithATransformIsReplayedOverOneRecord() {
        assertThat(unitOf(List.of("XSLTFilter"))).isEqualTo(ReplayUnit.RECORD);
        assertThat(unitOf(List.of("XSLTFilter", "XSLTFilter"))).isEqualTo(ReplayUnit.RECORD);
    }

    @Test
    void aStageFedByAParserHostsRecordVariantsAndOneFedByTheSourceHostsStreamOnes() {
        assertThat(ReplayUnit.forStageFedByParser(true)).isEqualTo(ReplayUnit.RECORD);
        assertThat(ReplayUnit.forStageFedByParser(false)).isEqualTo(ReplayUnit.STREAM);
    }

    @Test
    void aMismatchSaysWhatTheStageIsGivenAndWhatTheVariantExpected() {
        assertThat(ReplayUnits.mismatch(ReplayUnit.RECORD, "Pipeline x"))
                .isEqualTo("Pipeline x has no parser, but this stage is fed by the source and is given "
                           + "raw data: something must parse it");
        assertThat(ReplayUnits.mismatch(ReplayUnit.STREAM, "Pipeline y"))
                .isEqualTo("Pipeline y has a parser, but this stage is fed by a parser and is given "
                           + "records: there is nothing left to parse");
    }

    @Test
    void whatFeedsAStageIsWhatStandsAboveItInItsPipeline() {
        // The position check reads this, and it has to be read from the pipeline rather than assumed:
        // a supervisor under a parser is given records, one under the source is given raw data.
        final PipelineData underParser = new PipelineDataBuilder()
                .addElement(PipelineDataUtil.createElement("Source", "Source", null, null))
                .addElement(PipelineDataUtil.createElement("dsParser", "DSParser", null, null))
                .addElement(PipelineDataUtil.createElement("shapeshifterAi", "ShapeshifterAI", null, null))
                .addLink(PipelineDataUtil.createLink("Source", "dsParser"))
                .addLink(PipelineDataUtil.createLink("dsParser", "shapeshifterAi"))
                .build();
        final PipelineData underSource = new PipelineDataBuilder()
                .addElement(PipelineDataUtil.createElement("Source", "Source", null, null))
                .addElement(PipelineDataUtil.createElement("shapeshifterAi", "ShapeshifterAI", null, null))
                .addLink(PipelineDataUtil.createLink("Source", "shapeshifterAi"))
                .build();

        assertThat(ReplayUnits.fedByParser(underParser, "shapeshifterAi", PARSES)).contains(true);
        assertThat(ReplayUnits.fedByParser(underSource, "shapeshifterAi", PARSES)).contains(false);
        assertThat(ReplayUnits.fedByParser(underSource, "somethingElse", PARSES))
                .describedAs("an element that is not in this pipeline says nothing, and a check that "
                             + "cannot be made is not a check that failed")
                .isEmpty();
    }

    @Test
    void aPipelineThatLoopsDoesNotHangTheWalk() {
        final PipelineData looping = new PipelineDataBuilder()
                .addElement(PipelineDataUtil.createElement("a", "XSLTFilter", null, null))
                .addElement(PipelineDataUtil.createElement("b", "XSLTFilter", null, null))
                .addLink(PipelineDataUtil.createLink("a", "b"))
                .addLink(PipelineDataUtil.createLink("b", "a"))
                .build();

        assertThat(ReplayUnits.fedByParser(looping, "a", PARSES)).contains(false);
    }

    private static ReplayUnit unitOf(final List<String> chain) {
        return ReplayUnits.ofElements(chain, type -> RUNNERS.containsKey(type) && RUNNERS.get(type).parser());
    }
}
