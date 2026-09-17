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
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A learned chain becomes real content: two configuration documents and a pipeline fragment that links
 * {@code Source} to a parser to a transform and references the documents from the elements' properties.
 * The writer only needs each step's element and configuration text, so the steps here carry the text
 * without having been run.
 */
class TestFragmentWriter {

    private static final DocPath FOLDER = DocPath.fromParts("Shapeshifter", "SYSLOG");
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);
    private static final String DS3 = "<dataSplitter xmlns=\"data-splitter:3\" version=\"3.0\"/>";
    private static final String XSLT =
            "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"2.0\"/>";

    @Test
    void writesTheDocumentsAndAFragmentThatReferencesThem() {
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new DataSplitterStep(null), DS3, new StepResult("<records/>", List.of()), UNSCORED),
                new LearnedStep(new XsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "csv-logon-v1", chain);

        assertThat(fragment.getType()).isEqualTo(PipelineDoc.TYPE);
        assertThat(fragment.getName()).isEqualTo("csv-logon-v1");
        final PipelineData data = stores.pipelines.readDocument(fragment).getPipelineData();
        assertThat(data.getElements().getAdd())
                .extracting(PipelineElement::getId, PipelineElement::getType)
                .containsExactly(
                        tuple("Source", "Source"),
                        tuple("dsParser", "DSParser"),
                        tuple("xsltFilter", "XSLTFilter"));
        assertThat(data.getLinks().getAdd())
                .extracting(PipelineLink::getFrom, PipelineLink::getTo)
                .describedAs("a chain from Source with no destination: the fragment of A20")
                .containsExactly(
                        tuple("Source", "dsParser"),
                        tuple("dsParser", "xsltFilter"));

        final List<PipelineProperty> properties = data.getProperties().getAdd();
        assertThat(properties).extracting(PipelineProperty::getElement, PipelineProperty::getName)
                .containsExactly(
                        tuple("dsParser", "textConverter"),
                        tuple("xsltFilter", "xslt"));

        final DocRef textConverterRef = properties.get(0).getValue().getEntity();
        assertThat(textConverterRef.getType()).isEqualTo(TextConverterDoc.TYPE);
        final TextConverterDoc textConverter = stores.textConverters.readDocument(textConverterRef);
        assertThat(textConverter.getName()).isEqualTo("csv-logon-v1-dsParser");
        assertThat(textConverter.getConverterType()).isEqualTo(TextConverterType.DATA_SPLITTER);
        assertThat(textConverter.getData()).isEqualTo(DS3);

        final DocRef xsltRef = properties.get(1).getValue().getEntity();
        assertThat(xsltRef.getType()).isEqualTo(XsltDoc.TYPE);
        assertThat(stores.xslts.readDocument(xsltRef).getData()).isEqualTo(XSLT);
    }

    @Test
    void aRepeatedElementTypeGetsDistinctIds() {
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new XsltStep(), XSLT, new StepResult("<a/>", List.of()), UNSCORED),
                new LearnedStep(new XsltStep(), XSLT, new StepResult("<b/>", List.of()), UNSCORED));

        final DocRef fragment = writer.write(FOLDER, "two-pass", chain);
        final PipelineData data = stores.pipelines.readDocument(fragment).getPipelineData();

        assertThat(data.getElements().getAdd()).extracting(PipelineElement::getId)
                .containsExactly("Source", "xsltFilter", "xsltFilter2");
        assertThat(data.getLinks().getAdd()).extracting(PipelineLink::getFrom, PipelineLink::getTo)
                .containsExactly(tuple("Source", "xsltFilter"), tuple("xsltFilter", "xsltFilter2"));
        assertThat(data.getProperties().getAdd()).extracting(PipelineProperty::getElement)
                .containsExactly("xsltFilter", "xsltFilter2");
        assertThat(stores.xslts.list()).hasSize(2);
    }

    @Test
    void everyWriteCreatesNewDocuments() {
        // Design §7.3 rule 1: an improvement is a new document, never a new version of one that has run.
        final ContentStores stores = new ContentStores();
        final FragmentWriter writer = stores.writer();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new XsltStep(), XSLT, new StepResult("<Events/>", List.of()), UNSCORED));

        final DocRef first = writer.write(FOLDER, "v1", chain);
        final DocRef second = writer.write(FOLDER, "v2", chain);

        assertThat(second.getUuid()).isNotEqualTo(first.getUuid());
        assertThat(stores.pipelines.list()).hasSize(2);
        assertThat(stores.xslts.list()).hasSize(2);
    }
}
