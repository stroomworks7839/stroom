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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.pipeline.textconverter.TextConverterStore;
import stroom.pipeline.xslt.XsltStore;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepRunner.Configured;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a learned chain into content: one configuration document per document-bearing step, and a
 * pipeline fragment (proposed ruling A20) whose elements reference them. The fragment starts at the
 * explicit {@code Source} element every base pipeline carries and ends at the last step, with no writer
 * or destination; the supervisor attaches its own capture when it runs one.
 * <p>
 * Every document is new (design §7.3 rule 1): a learned chain never overwrites what an earlier one wrote.
 * Where the documents go is the {@link ContentCreator}'s business; what they hold is this class's.
 */
public final class FragmentWriter {

    private static final String SOURCE = "Source";
    private static final Set<String> WRITABLE = Set.of(TextConverterDoc.TYPE, XsltDoc.TYPE);

    private final ContentCreator creator;
    private final PipelineStore pipelineStore;
    private final TextConverterStore textConverterStore;
    private final XsltStore xsltStore;

    @Inject
    public FragmentWriter(final ContentCreator creator,
                          final PipelineStore pipelineStore,
                          final TextConverterStore textConverterStore,
                          final XsltStore xsltStore) {
        this.creator = creator;
        this.pipelineStore = pipelineStore;
        this.textConverterStore = textConverterStore;
        this.xsltStore = xsltStore;
    }

    /**
     * @param folder Where the fragment and its configuration documents are created.
     * @param name   The name for the fragment; configuration documents take it with the element id appended.
     * @param chain  The learned steps in chain order.
     * @return The fragment's DocRef, for the routing table.
     */
    public DocRef write(final DocPath folder, final String name, final List<LearnedStep> chain) {
        // Checked for the whole chain before anything is created: a step whose document this writer
        // cannot write must not leave the earlier steps' documents behind.
        chain.stream()
                .map(step -> step.runner().configured())
                .flatMap(Optional::stream)
                .map(Configured::documentType)
                .filter(type -> !WRITABLE.contains(type))
                .findFirst()
                .ifPresent(type -> {
                    throw new IllegalArgumentException("No store to write a " + type + " document");
                });
        final PipelineDataBuilder builder = new PipelineDataBuilder()
                .addElement(new PipelineElement(SOURCE, SOURCE));
        String previous = SOURCE;
        final Map<String, Integer> idsUsed = new HashMap<>();
        for (final LearnedStep step : chain) {
            final String elementId = uniqueId(step.runner().elementId(), idsUsed);
            builder.addElement(new PipelineElement(elementId, step.elementType()));
            builder.addLink(previous, elementId);
            step.runner().configured().ifPresent(configured -> {
                final DocRef configuration = writeConfiguration(
                        folder, name + "-" + elementId, configured, step.configuration());
                builder.addProperty(new PipelineProperty(
                        elementId, configured.propertyName(), new PipelinePropertyValue(configuration)));
            });
            previous = elementId;
        }
        final PipelineData pipelineData = builder.build();

        final DocRef fragment = creator.create(folder, PipelineDoc.TYPE, name);
        pipelineStore.writeDocument(pipelineStore.readDocument(fragment)
                .copy()
                .pipelineData(pipelineData)
                .build());
        return fragment;
    }

    /**
     * A chain may hold the same element type twice — two transforms in a row, say — and element ids are
     * unique within a pipeline, so the second and later take a suffix.
     */
    private static String uniqueId(final String elementId, final Map<String, Integer> idsUsed) {
        final int count = idsUsed.merge(elementId, 1, Integer::sum);
        return count == 1
                ? elementId
                : elementId + count;
    }

    private DocRef writeConfiguration(final DocPath folder,
                                      final String name,
                                      final Configured configured,
                                      final String data) {
        // The type is checked before anything is created, so an unsupported one leaves no orphan behind.
        return switch (configured.documentType()) {
            case TextConverterDoc.TYPE -> {
                final DocRef docRef = creator.create(folder, TextConverterDoc.TYPE, name);
                textConverterStore.writeDocument(textConverterStore.readDocument(docRef)
                        .copy()
                        .converterType(TextConverterType.DATA_SPLITTER)
                        .data(data)
                        .build());
                yield docRef;
            }
            case XsltDoc.TYPE -> {
                final DocRef docRef = creator.create(folder, XsltDoc.TYPE, name);
                xsltStore.writeDocument(xsltStore.readDocument(docRef)
                        .copy()
                        .data(data)
                        .build());
                yield docRef;
            }
            default -> throw new IllegalArgumentException(
                    "No store for configuration documents of type " + configured.documentType());
        };
    }
}
