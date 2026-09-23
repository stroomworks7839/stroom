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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorListenerAdaptor;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggedException;
import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.factory.HasStepDetails;
import stroom.pipeline.factory.PipelineProperty;
import stroom.pipeline.factory.PipelinePropertyDocRef;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiElements;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.Severity;
import stroom.util.xml.XMLUtil;

import jakarta.inject.Inject;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/**
 * The supervisor at the transformation stage (design 01 §3, §12 item 4): one supervised stage fed the
 * records a parser above it made, emitting the events its learned chain makes of them. Everything it
 * does is {@link Supervision}'s and is shared with {@link ShapeshifterAiParser}; what differs is only
 * its shape in a pipeline.
 * <p>
 * It exists because that shape is what the extract-then-transform pair of §3 needs and a parser cannot
 * have: the pipeline editor refuses a parser under a parser, so a supervisor that declares the parser
 * role cannot be placed below one, and the {@code RECORD} position of A1 could not be drawn at all.
 * This is a filter, so it can stand wherever a stylesheet can.
 * <p>
 * The events it is given are serialised back to text before the stage sees them, because what a stage
 * puts to a model, judges, scores and records is text (design 01 §4). One document in is one stream to
 * the stage: where a {@code SplitFilter} stands above, that is one record at a time and the stage is
 * asked once per record; where nothing splits, it is the whole of what the parser wrote.
 */
@ConfigurableElement(
        type = ShapeshifterAiFilter.TYPE,
        category = Category.FILTER,
        description = """
                A supervised stage over records: routes each record to the fragment its Shapeshifter \
                AI document binds for its shape, learns a fragment for a shape nothing binds, and \
                refuses a shape it has given up on.
                """,
        roles = {
                PipelineElementType.ROLE_TARGET,
                PipelineElementType.ROLE_HAS_TARGETS,
                PipelineElementType.VISABILITY_SIMPLE,
                PipelineElementType.VISABILITY_STEPPING,
                PipelineElementType.ROLE_MUTATOR},
        icon = SvgImage.AI)
public class ShapeshifterAiFilter extends AbstractXMLFilter implements HasStepDetails {

    public static final String TYPE = ShapeshifterAiElements.FILTER;

    private final Supervision supervision;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final LocationFactoryProxy locationFactory;

    private DocRef docRef;
    private boolean asProcessed;

    private ShapeshifterAiDoc doc;
    private TransformerHandler serialiser;
    private ByteArrayOutputStream records;

    @Inject
    public ShapeshifterAiFilter(final Supervision supervision,
                                final ErrorReceiverProxy errorReceiverProxy,
                                final LocationFactoryProxy locationFactory) {
        this.supervision = supervision;
        this.errorReceiverProxy = errorReceiverProxy;
        this.locationFactory = locationFactory;
    }

    @PipelineProperty(description = "The Shapeshifter AI document that governs this stage.", displayPriority = 1)
    @PipelinePropertyDocRef(types = ShapeshifterAiDoc.TYPE)
    public void setShapeshifterAi(final DocRef docRef) {
        this.docRef = docRef;
    }

    /// Which question a reprocess is asking (design 01 §7.3), as
    /// [ShapeshifterAiParser#setAsProcessed(boolean)] asks it.
    @PipelineProperty(
            description = "Process each record through the fragment that produced it before, rather "
                          + "than through whatever the routing table binds today.",
            defaultValue = "false",
            displayPriority = 2)
    public void setAsProcessed(final boolean asProcessed) {
        this.asProcessed = asProcessed;
    }

    /// The document and the position are settled before a record arrives: a stage that cannot learn
    /// anything usable where it stands should say so before it has spent a call finding out.
    @Override
    public void startProcessing() {
        try {
            doc = supervision.document(docRef, getElementId());
            supervision.checkPosition(doc, getElementId());
            final ErrorListener errorListener =
                    new ErrorListenerAdaptor(getElementId(), locationFactory, errorReceiverProxy);
            serialiser = XMLUtil.createTransformerHandler(errorListener, false, true);
        } catch (final TransformerConfigurationException e) {
            errorReceiverProxy.log(Severity.FATAL_ERROR, null, getElementId(), e.getMessage(), e);
            throw LoggedException.wrap(e);
        } finally {
            super.startProcessing();
        }
    }

    /// Nothing is forwarded while a document is being read: what goes downstream is what the stage's
    /// bound fragment makes of it, not what came in.
    @Override
    public void startDocument() throws SAXException {
        // Before anything else: what this element decided about the record before must not be shown as
        // this one's if this one never reaches endDocument (A30).
        supervision.startRecord(getElementId());
        records = new ByteArrayOutputStream();
        serialiser.setResult(new StreamResult(records));
        serialiser.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        serialiser.endDocument();
        supervision.supervise(doc, getElementId(), asProcessed,
                records.toString(StandardCharsets.UTF_8), getContentHandler());
        records = null;
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
        serialiser.setDocumentLocator(locator);
    }

    @Override
    public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
        serialiser.startPrefixMapping(prefix, uri);
    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        serialiser.endPrefixMapping(prefix);
    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        serialiser.startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        serialiser.endElement(uri, localName, qName);
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        serialiser.characters(ch, start, length);
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
        serialiser.ignorableWhitespace(ch, start, length);
    }

    @Override
    public void processingInstruction(final String target, final String data) throws SAXException {
        serialiser.processingInstruction(target, data);
    }

    @Override
    public void skippedEntity(final String name) throws SAXException {
        serialiser.skippedEntity(name);
    }

    /// What this stage decided about the record just captured, for the stepper's stage pane (A30).
    @Override
    public ShapeshifterAiStepDetails getStepDetails(final long recordIndex) {
        return supervision.stepDetails(getElementId(), recordIndex);
    }

}
