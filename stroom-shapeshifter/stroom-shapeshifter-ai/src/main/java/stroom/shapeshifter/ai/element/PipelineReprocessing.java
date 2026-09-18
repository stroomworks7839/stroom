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

package stroom.shapeshifter.ai.element;

import stroom.meta.shared.MetaFields;
import stroom.pipeline.state.PipelineHolder;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.processor.shared.ReprocessDataInfo;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.stage.Reprocessing;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A reprocess request in a node is a reprocess filter (design 01 §5.2, A12), as an operator's is: a
 * filter over the outputs — error streams, or the output a retracted rule produced — that this pipeline
 * made from the named inputs, which task creation turns back into their inputs and runs as-current. At
 * the lowest priority: the release of a backlog is bursty by nature and belongs behind live data.
 */
public class PipelineReprocessing implements Reprocessing {

    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineReprocessing.class);
    private static final int LOWEST_PRIORITY = 1;

    private final ProcessorFilterService processorFilterService;
    private final PipelineHolder pipelineHolder;

    @Inject
    public PipelineReprocessing(final ProcessorFilterService processorFilterService,
                                final PipelineHolder pipelineHolder) {
        this.processorFilterService = processorFilterService;
        this.pipelineHolder = pipelineHolder;
    }

    @Override
    public void request(final String docUuid, final String reason, final List<Long> inputIds) {
        final ExpressionOperator outputs = ExpressionOperator.builder()
                .addTerm(MetaFields.PARENT_ID.getFldName(), Condition.IN, inputIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(",")))
                .addDocRefTerm(MetaFields.PIPELINE, Condition.IS_DOC_REF, pipelineHolder.getPipeline())
                .build();
        final List<ReprocessDataInfo> outcome = processorFilterService.reprocess(CreateProcessFilterRequest.builder()
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(outputs)
                        .build())
                .reprocess(true)
                .priority(LOWEST_PRIORITY)
                .enabled(true)
                .build());
        LOGGER.info("Shapeshifter AI document {}: {}; requested reprocessing of inputs {}: {}", docUuid, reason,
                inputIds, outcome);
        if (outcome != null) {
            outcome.stream()
                    .filter(info -> info.getSeverity().greaterThanOrEqual(Severity.ERROR))
                    .forEach(info -> LOGGER.error("Reprocess filter not created for inputs {}: {} {}", inputIds,
                            info.getMessage(), info.getDetails()));
        }
    }
}
