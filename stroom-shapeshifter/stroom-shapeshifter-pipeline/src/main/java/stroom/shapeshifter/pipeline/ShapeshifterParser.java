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
import stroom.pipeline.LocationFactoryProxy;
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
import stroom.pipeline.filter.PipelineDocFinder;
import stroom.pipeline.parser.AbstractParser;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.pipeline.shared.data.PipelineReference;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.PipelineHolder;
import stroom.pipeline.xml.converter.ParserFactory;
import stroom.shapeshifter.shared.ShapeshifterDoc;
import stroom.svg.shared.SvgImage;
import stroom.util.io.PathCreator;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The pipeline element: a Shapeshifter configuration standing where a DS3 parser stands. Design 21
 * phase 1b, on the pattern of {@code DSParser} — the document is loaded fresh per stream so a
 * cached pipeline never holds a stale configuration, the compiled factory comes from a pool keyed
 * on the document, and stepping's code injection bypasses the pool.
 */
@ConfigurableElement(
        type = PipelineElementType.TYPE_SHAPESHIFTER_PARSER,
        category = Category.PARSER,
        description = """
                A parser that runs a Shapeshifter configuration over the input. A configuration that \
                emits structure produces XML events; one that emits text produces characters, for a \
                TextWriter. The configuration is a Shapeshifter document.
                """,
        roles = {
                PipelineElementType.ROLE_PARSER,
                PipelineElementType.ROLE_HAS_TARGETS,
                PipelineElementType.VISABILITY_SIMPLE,
                PipelineElementType.VISABILITY_STEPPING,
                PipelineElementType.ROLE_MUTATOR,
                PipelineElementType.ROLE_HAS_CODE},
        icon = SvgImage.PIPELINE_TEXT)
public class ShapeshifterParser extends AbstractParser implements SupportsCodeInjection {

    private final ShapeshifterParserFactoryPool pool;
    private final ShapeshifterStore store;
    private final Provider<FeedHolder> feedHolder;
    private final Provider<PipelineHolder> pipelineHolder;
    private final LocationFactoryProxy locationFactory;
    private final PathCreator pathCreator;
    private final List<PipelineReference> pipelineReferences = new ArrayList<>();
    private final PipelineDocFinder<ShapeshifterDoc> pipelineDocFinder;

    private DocRef shapeshifterRef;
    private String namePattern;
    private boolean suppressDocumentNotFoundWarnings;
    private String injectedCode;
    private boolean usePool = true;
    private PoolItem<StoredParserFactory> poolItem;

    @Inject
    public ShapeshifterParser(final ErrorReceiverProxy errorReceiverProxy,
                              final LocationFactoryProxy locationFactory,
                              final ShapeshifterParserFactoryPool pool,
                              final ShapeshifterStore store,
                              final PathCreator pathCreator,
                              final Provider<FeedHolder> feedHolder,
                              final Provider<PipelineHolder> pipelineHolder,
                              final DocFinder docFinder) {
        super(errorReceiverProxy, locationFactory);
        this.pool = pool;
        this.store = store;
        this.feedHolder = feedHolder;
        this.locationFactory = locationFactory;
        this.pathCreator = pathCreator;
        this.pipelineHolder = pipelineHolder;
        this.pipelineDocFinder = new PipelineDocFinder<>(ShapeshifterDoc.TYPE, pathCreator, docFinder);
    }

    @Override
    protected XMLReader createReader() throws SAXException {
        ShapeshifterDoc doc = loadDoc();
        if (injectedCode != null) {
            // Stepping has edited the configuration; run the edit, and keep it out of the pool.
            doc = doc.copy().data(injectedCode).build();
            usePool = false;
        }

        poolItem = pool.borrowObject(doc, usePool);
        final StoredParserFactory stored = poolItem.getValue();
        final StoredErrorReceiver storedErrorReceiver = stored.getErrorReceiver();
        final ParserFactory parserFactory = stored.getParserFactory();

        if (storedErrorReceiver.getTotalErrors() == 0 && parserFactory != null) {
            final XMLReader reader = parserFactory.getParser();
            if (reader instanceof ShapeshifterReader shapeshifter) {
                // Design 26: what this document's functions may reach — the element's holders.
                shapeshifter.setServices(ElementServices.of(
                        getErrorReceiverProxy(), locationFactory, pathCreator,
                        feedHolder, pipelineHolder, pipelineReferences));
            }
            return reader;
        }
        storedErrorReceiver.replay(new ErrorReceiverIdDecorator(getElementId(), getErrorReceiverProxy()));
        throw ProcessException.create("Unable to create parser");
    }

    /**
     * The pipeline hands a parser the raw feed ({@code setInputStream}) unless a reader element
     * sits in front of it, and {@code AbstractParser.getInputSource} would decode that feed into
     * a {@code BufferedReader} before the reader saw it. A byte engine is given bytes: a byte
     * stream passes through with its declared encoding, and only a character stream — already
     * decoded upstream — is taken as such (design 23 §5.3).
     */
    @Override
    protected InputSource getInputSource(final InputSource inputSource) throws IOException {
        final InputSource raw = rawBytes(inputSource);
        return raw != null ? raw : super.getInputSource(inputSource);
    }

    /** The source unchanged if it carries a byte stream, else null. */
    static InputSource rawBytes(final InputSource inputSource) {
        if (inputSource == null || inputSource.getByteStream() == null) {
            return null;
        }
        final InputSource raw = new InputSource(inputSource.getByteStream());
        raw.setEncoding(inputSource.getEncoding());
        raw.setSystemId(inputSource.getSystemId());
        return raw;
    }

    @Override
    public void endProcessing() {
        try {
            super.endProcessing();
        } catch (final LoggedException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw ProcessException.wrap(e);
        } finally {
            if (poolItem != null) {
                pool.returnObject(poolItem, usePool);
            }
        }
    }

    @Override
    public void setInjectedCode(final String injectedCode) {
        this.injectedCode = injectedCode;
    }

    @PipelineProperty(
            description = "The Shapeshifter configuration that should be used to parse the input data.",
            displayPriority = 1)
    @PipelinePropertyDocRef(types = ShapeshifterDoc.TYPE, canEmbed = true)
    public void setShapeshifter(final DocRef shapeshifterRef) {
        this.shapeshifterRef = shapeshifterRef;
    }

    @PipelineProperty(description = "A list of places to load reference data from if required.",
            displayPriority = 5)
    public void setPipelineReference(final PipelineReference pipelineReference) {
        pipelineReferences.add(pipelineReference);
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
                shapeshifterRef,
                namePattern,
                feedName,
                pipelineName,
                errorConsumer,
                suppressDocumentNotFoundWarnings);
    }

    private ShapeshifterDoc loadDoc() {
        final DocRef docRef = findDoc(
                feedName(),
                pipelineName(),
                message -> getErrorReceiverProxy().log(Severity.WARNING, null, getElementId(), message, null));
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
