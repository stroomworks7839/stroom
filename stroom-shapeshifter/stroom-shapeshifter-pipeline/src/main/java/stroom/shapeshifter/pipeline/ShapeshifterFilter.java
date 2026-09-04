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

package stroom.shapeshifter.pipeline;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.pipeline.SupportsCodeInjection;
import stroom.pipeline.cache.PoolItem;
import stroom.pipeline.cache.StoredParserFactory;
import stroom.pipeline.errorhandler.ErrorReceiverIdDecorator;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggedException;
import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.errorhandler.StoredErrorReceiver;
import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.factory.PipelineProperty;
import stroom.pipeline.factory.PipelinePropertyDocRef;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.filter.PipelineDocFinder;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.PipelineHolder;
import stroom.pipeline.xml.converter.ParserFactory;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.svg.shared.SvgImage;
import stroom.util.io.PathCreator;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Shapeshifter in the middle of a pipeline: events in, events out (design 22).
 *
 * <p>The same document, pool and properties as {@link ShapeshifterParser}; what differs is
 * where the bytes come from. Each document's events are written as the byte image
 * ({@link EventImage}) into a pipe the engine reads on a worker thread, with the pipe's
 * capacity as back-pressure; at {@code endDocument} the worker is joined and the output is
 * forwarded downstream on this thread, which is the pipeline's.
 */
@ConfigurableElement(
        type = PipelineElementType.TYPE_SHAPESHIFTER_FILTER,
        category = Category.FILTER,
        description = """
                A filter that runs a Shapeshifter configuration over the XML it receives and emits the XML the \
                configuration produces. The input is matched as the text an indenting XMLWriter would write at \
                this point in the pipeline. The configuration is a Shapeshifter document.
                """,
        roles = {
                PipelineElementType.ROLE_TARGET,
                PipelineElementType.ROLE_HAS_TARGETS,
                PipelineElementType.VISABILITY_SIMPLE,
                PipelineElementType.VISABILITY_STEPPING,
                PipelineElementType.ROLE_MUTATOR,
                PipelineElementType.ROLE_HAS_CODE},
        icon = SvgImage.PIPELINE_TEXT)
public class ShapeshifterFilter extends AbstractXMLFilter implements SupportsCodeInjection {

    /** The pipe's capacity — how far the pipeline may run ahead of the engine, in bytes. */
    static final int PIPE_CAPACITY = 64 * 1024;

    private final ErrorReceiverProxy errorReceiverProxy;
    private final ShapeshifterParserFactoryPool pool;
    private final ShapeshifterStore store;
    private final Provider<FeedHolder> feedHolder;
    private final Provider<PipelineHolder> pipelineHolder;
    private final PipelineDocFinder<ShapeshifterDoc> pipelineDocFinder;

    private DocRef shapeshifterRef;
    private String namePattern;
    private boolean suppressDocumentNotFoundWarnings;
    private String injectedCode;
    private boolean usePool = true;
    private PoolItem<StoredParserFactory> poolItem;
    private ParserFactory factory;
    private FilterRun document;

    @Inject
    public ShapeshifterFilter(final ErrorReceiverProxy errorReceiverProxy,
                              final ShapeshifterParserFactoryPool pool,
                              final ShapeshifterStore store,
                              final PathCreator pathCreator,
                              final Provider<FeedHolder> feedHolder,
                              final Provider<PipelineHolder> pipelineHolder,
                              final DocFinder docFinder) {
        this.errorReceiverProxy = errorReceiverProxy;
        this.pool = pool;
        this.store = store;
        this.feedHolder = feedHolder;
        this.pipelineHolder = pipelineHolder;
        this.pipelineDocFinder = new PipelineDocFinder<>(ShapeshifterDoc.TYPE, pathCreator, docFinder);
    }

    // -----------------------------------------------------------------------------------
    // Lifecycle: the compiled configuration per stream, a run per document
    // -----------------------------------------------------------------------------------

    @Override
    public void startProcessing() {
        try {
            ShapeshifterDoc doc = loadDoc();
            if (injectedCode != null) {
                doc = doc.copy().data(injectedCode).build();
                usePool = false;
            }
            poolItem = pool.borrowObject(doc, usePool);
            final StoredParserFactory stored = poolItem.getValue();
            final StoredErrorReceiver storedErrorReceiver = stored.getErrorReceiver();
            if (storedErrorReceiver.getTotalErrors() == 0 && stored.getParserFactory() != null) {
                factory = stored.getParserFactory();
            } else {
                storedErrorReceiver.replay(new ErrorReceiverIdDecorator(getElementId(), errorReceiverProxy));
                throw ProcessException.create("Unable to create parser");
            }
        } catch (final LoggedException e) {
            throw e;
        } catch (final RuntimeException e) {
            errorReceiverProxy.log(Severity.FATAL_ERROR, null, getElementId(), e.getMessage(), e);
            throw ProcessException.wrap(e);
        } finally {
            super.startProcessing();
        }
    }

    @Override
    public void endProcessing() {
        try {
            abandonIfOpen("the stream ended before the document did");
            super.endProcessing();
        } finally {
            if (poolItem != null) {
                pool.returnObject(poolItem, usePool);
                poolItem = null;
            }
        }
    }

    /** A document left open — no endDocument came — would leave its worker waiting for ever. */
    private void abandonIfOpen(final String why) {
        if (document != null) {
            document.abandon(new IllegalStateException(why));
            document = null;
            errorReceiverProxy.log(Severity.WARNING, null, getElementId(), "Document abandoned: " + why, null);
        }
    }

    @Override
    public void startDocument() throws SAXException {
        abandonIfOpen("a new document began before the last ended");
        if (factory == null) {
            throw new SAXException("No Shapeshifter configuration is loaded");
        }
        final XMLReader reader = factory.getParser();
        if (!(reader instanceof ShapeshifterReader shapeshifter)) {
            throw new SAXException("The configuration's parser is not a Shapeshifter reader");
        }
        document = new FilterRun(shapeshifter, PIPE_CAPACITY);
        document.input().startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        if (document == null) {
            return;
        }
        try {
            document.finish(getContentHandler(), errorHandler());
        } catch (final IOException e) {
            errorReceiverProxy.log(Severity.FATAL_ERROR, null, getElementId(), e.getMessage(), e);
        } finally {
            document = null;
        }
    }

    // -----------------------------------------------------------------------------------
    // Events in: the image
    // -----------------------------------------------------------------------------------

    @Override
    public void setDocumentLocator(final Locator locator) {
        // The downstream locator is the reader's, set when the output is forwarded (I4).
    }

    @Override
    public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
        image(h -> h.startPrefixMapping(prefix, uri));
    }

    @Override
    public void endPrefixMapping(final String prefix) throws SAXException {
        image(h -> h.endPrefixMapping(prefix));
    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        checkTermination();
        image(h -> h.startElement(uri, localName, qName, atts));
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        image(h -> h.endElement(uri, localName, qName));
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        image(h -> h.characters(ch, start, length));
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) {
    }

    @Override
    public void processingInstruction(final String target, final String data) {
    }

    @Override
    public void skippedEntity(final String name) {
    }

    private interface Event {

        void to(org.xml.sax.ContentHandler handler) throws SAXException;
    }

    private void image(final Event event) throws SAXException {
        if (document == null) {
            throw new SAXException("Event outside a document");
        }
        try {
            event.to(document.input());
        } catch (final RuntimeException e) {
            // The pipe failed under us — the worker died — so the document is abandoned and the
            // failure reported once, here, where the pipeline can see it.
            document.abandon(e);
            document = null;
            errorReceiverProxy.log(Severity.FATAL_ERROR, null, getElementId(), e.getMessage(), e);
            throw new SAXException(e);
        }
    }

    private ErrorHandler errorHandler() {
        return new ErrorHandler() {
            @Override
            public void warning(final SAXParseException exception) {
                errorReceiverProxy.log(Severity.WARNING, null, getElementId(), exception.getMessage(), exception);
            }

            @Override
            public void error(final SAXParseException exception) {
                errorReceiverProxy.log(Severity.ERROR, null, getElementId(), exception.getMessage(), exception);
            }

            @Override
            public void fatalError(final SAXParseException exception) {
                errorReceiverProxy.log(Severity.FATAL_ERROR, null, getElementId(), exception.getMessage(), exception);
            }
        };
    }

    // -----------------------------------------------------------------------------------
    // Configuration: the parser's, shared
    // -----------------------------------------------------------------------------------

    @Override
    public void setInjectedCode(final String injectedCode) {
        this.injectedCode = injectedCode;
    }

    @PipelineProperty(
            description = "The Shapeshifter configuration that should be used to transform the input.",
            displayPriority = 1)
    @PipelinePropertyDocRef(types = ShapeshifterDoc.TYPE, canEmbed = true)
    public void setShapeshifter(final DocRef shapeshifterRef) {
        this.shapeshifterRef = shapeshifterRef;
    }

    @PipelineProperty(description = "A name pattern to load a Shapeshifter configuration dynamically.",
            displayPriority = 2)
    public void setNamePattern(final String namePattern) {
        this.namePattern = namePattern;
    }

    @PipelineProperty(
            description = "If the configuration cannot be found to match the name pattern suppress warnings.",
            defaultValue = "false",
            displayPriority = 3)
    public void setSuppressDocumentNotFoundWarnings(final boolean suppressDocumentNotFoundWarnings) {
        this.suppressDocumentNotFoundWarnings = suppressDocumentNotFoundWarnings;
    }

    @Override
    public DocRef findDoc(final String feedName, final String pipelineName, final Consumer<String> errorConsumer) {
        return pipelineDocFinder.findDoc(
                shapeshifterRef, namePattern, feedName, pipelineName, errorConsumer, suppressDocumentNotFoundWarnings);
    }

    private ShapeshifterDoc loadDoc() {
        final DocRef docRef = findDoc(
                feedName(),
                pipelineName(),
                message -> errorReceiverProxy.log(Severity.WARNING, null, getElementId(), message, null));
        if (docRef == null) {
            throw ProcessException.create(
                    "No Shapeshifter configuration is configured or can be found to match the provided name pattern");
        }
        final ShapeshifterDoc doc = store.readDocument(docRef);
        if (doc == null) {
            throw ProcessException.create(
                    "Shapeshifter configuration \"" + docRef.getName() + "\" appears to have been deleted");
        }
        return doc;
    }

    private String feedName() {
        final FeedHolder holder = feedHolder == null ? null : feedHolder.get();
        return holder == null ? null : holder.getFeedName();
    }

    private String pipelineName() {
        final PipelineHolder holder = pipelineHolder == null ? null : pipelineHolder.get();
        final DocRef pipeline = holder == null ? null : holder.getPipeline();
        return pipeline == null ? null : pipeline.getName();
    }
}
