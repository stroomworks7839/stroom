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
import stroom.meta.api.AttributeMap;
import stroom.meta.shared.Meta;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.ProcessException;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.state.FeedHolder;
import stroom.pipeline.state.MetaData;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.state.PipelineHolder;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.fragment.ReplayUnits;
import stroom.shapeshifter.ai.stage.Bindings;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Retracted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.Stage.Served;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.ReplayUnit;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.pipeline.scope.PipelineScoped;
import stroom.util.shared.ElementId;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/// What a supervised stage does with the text in front of it, whichever element is standing there
/// (design 01 §3, §12 item 4). The stage sits where a parser sits when it is given a stream, and where a
/// filter sits when it is given records, and everything but that difference is here: the document, the
/// position check, the stream as the stage sees it, the decision, the bindings recorded on the output
/// stream, and the fragment's events played on to whatever is downstream.
///
/// Pipeline-scoped, because what it reads the stream's identity from — the meta, feed and pipeline
/// holders — is. One instance serves both supervisors in a pipeline: a [Stage] holds nothing between
/// calls, so one is as good as many.
@PipelineScoped
public class Supervision {

    private final ShapeshifterAiStore store;
    private final ElementRegistryFactory elementRegistryFactory;
    private final PipelineStore pipelineStore;
    private final PipelineDataCache pipelineDataCache;
    private final FeedHolder feedHolder;
    private final MetaHolder metaHolder;
    private final MetaDataHolder metaDataHolder;
    private final MetaData metaData;
    private final ErrorReceiverProxy errorReceiverProxy;
    private final PipelineHolder pipelineHolder;
    private final Stage stage;

    /// What each supervisor in this pipeline has recorded for this stream, by element id. Pipeline-
    /// scoped state, like the attributes it guards.
    private final Map<String, String> recordedBy = new HashMap<>();

    @Inject
    public Supervision(final ShapeshifterAiStore store,
                       final ElementRegistryFactory elementRegistryFactory,
                       final PipelineStore pipelineStore,
                       final PipelineDataCache pipelineDataCache,
                       final FeedHolder feedHolder,
                       final MetaHolder metaHolder,
                       final MetaDataHolder metaDataHolder,
                       final MetaData metaData,
                       final ErrorReceiverProxy errorReceiverProxy,
                       final PipelineHolder pipelineHolder,
                       final StageFactory stageFactory) {
        this.store = store;
        this.elementRegistryFactory = elementRegistryFactory;
        this.pipelineStore = pipelineStore;
        this.pipelineDataCache = pipelineDataCache;
        this.feedHolder = feedHolder;
        this.metaHolder = metaHolder;
        this.metaDataHolder = metaDataHolder;
        this.metaData = metaData;
        this.errorReceiverProxy = errorReceiverProxy;
        this.pipelineHolder = pipelineHolder;
        this.stage = stageFactory.create();
    }

    /// The document a supervisor names, or a refusal that says which element has none.
    ///
    /// @param docRef    What the element's property was set to; null where it was not set.
    /// @param elementId The element to name in the refusal.
    public ShapeshifterAiDoc document(final DocRef docRef, final ElementId elementId) {
        if (docRef == null) {
            throw ProcessException.create("No Shapeshifter AI document is set on element " + elementId);
        }
        final ShapeshifterAiDoc doc = store.readDocument(docRef);
        if (doc == null) {
            throw ProcessException.create("Shapeshifter AI document " + docRef + " was not found");
        }
        return doc;
    }

    /// What this stage may learn must match where it stands (A1, design 01 §4): a stage fed by the
    /// source is given raw data and its chains must parse; a stage fed by a parser is given records and
    /// its chains must not. The document's allowed elements are what a chain is chosen from, so they
    /// are what has to agree, and the pipeline this element sits in is what says which position it is
    /// in.
    ///
    /// Checked as the pipeline is built rather than when a model is asked: a document that cannot learn
    /// anything usable here should say so before it has spent a call finding out.
    ///
    /// Skipped where the answer is not knowable — the pipeline cannot be read, or the document names no
    /// allowed elements and so constrains nothing. A check that cannot be made is not a check that
    /// failed, and refusing on a guess would be worse than not looking.
    ///
    /// @param elementId The supervisor, which is what the walk starts from.
    public void checkPosition(final ShapeshifterAiDoc doc, final ElementId elementId) {
        if (NullSafe.isEmptyCollection(doc.getAllowedElements())) {
            return;
        }
        final Optional<Boolean> fedByParser = fedByParser(elementId);
        if (fedByParser.isEmpty()) {
            return;
        }
        final ReplayUnit allowed = ReplayUnits.ofElements(doc.getAllowedElements(), this::parses);
        final ReplayUnit here = ReplayUnit.forStageFedByParser(fedByParser.get());
        if (allowed != here) {
            throw ProcessException.create(ReplayUnits.mismatch(allowed,
                    "The allowed elements of Shapeshifter AI document " + doc.getName()));
        }
    }

    /// Route, learn, judge and serve one stream, and play what the bound fragment made of it on to the
    /// element's own downstream.
    ///
    /// The fragment is run once and once only (§12 item 2): the run the stage judged is the run that is
    /// served, and its events are carried back on the [StageRun]. A decision that binds nothing — a
    /// sentinel, a shape given up, a draft awaiting review — emits nothing and says why on the error
    /// stream (A4).
    ///
    /// @param data        The stream as text: the whole of it for a stage fed by the source, one
    ///                    record's markup for a stage fed by a parser.
    /// @param asProcessed Which question a reprocess is asking (design 01 §7.3).
    /// @param downstream  Where the fragment's events go.
    public void supervise(final ShapeshifterAiDoc doc,
                          final ElementId elementId,
                          final boolean asProcessed,
                          final String data,
                          final ContentHandler downstream) throws SAXException {
        final Input input = input(data);
        final StageRun run = asProcessed
                ? stage.reprocess(doc, input)
                : stage.run(doc, input);
        final Bindings bindings = run.bindings();
        if (bindings == null) {
            errorReceiverProxy.log(Severity.ERROR, null, elementId,
                    "Shape " + run.shape().id() + ": " + reason(run.decision()), null);
            return;
        }
        record(bindings, elementId);
        // A stream a rule already bound carries the events of the run it was judged on; a stream the
        // shape was learned on carries none, because the chain was judged as it was learned and the
        // fragment it became has not been run as a pipeline yet.
        final Served served = run.events() == null
                ? stage.serve(bindings, input)
                : new Served(run.events(), run.diagnostics());
        // What the fragment said, on this pipeline's error stream (A20). The fragment runs under an
        // error receiver of its own, so that the complaints of a candidate nobody keeps go to the model
        // rather than to the operator; the run that is served is the one the operator has to hear, and
        // each of its elements is named as itself. Once per distinct message over the stream, which is
        // the rule a re-ask already follows.
        served.diagnostics().forEach(error -> errorReceiverProxy.log(error.getSeverity(),
                error.getLocation(), error.getElementId(), error.getMessage(), null));
        if (served.events() == null) {
            // Nothing downstream can be served from that, and silently writing an empty stream would
            // look like a feed that had nothing in it.
            errorReceiverProxy.log(Severity.ERROR, null, elementId,
                    "Shape " + run.shape().id() + " is bound to fragment " + bindings.fragment().getName()
                    + ", which produced nothing for this stream", null);
            return;
        }
        served.events().fire(downstream);
    }

    /// The stream as the [Stage] sees it: its meta id, feed, type, attributes and content.
    private Input input(final String data) {
        final Meta meta = metaHolder.getMeta();
        final AttributeMap attributeMap = metaDataHolder.getMetaData();
        final Map<String, String> attributes = new HashMap<>();
        if (attributeMap != null) {
            attributeMap.forEach(attributes::put);
        }
        return new Input(
                meta == null
                        ? -1L
                        : meta.getId(),
                feedHolder.getFeedName(),
                meta == null
                        ? null
                        : meta.getTypeName(),
                attributes,
                data,
                // Which pipeline is processing it: what the ledger keeps, so that a stream sentinelled
                // here is replayed here when its shape settles — wherever that happens (A12).
                pipelineHolder.getPipeline() == null
                        ? null
                        : pipelineHolder.getPipeline().getUuid());
    }

    /// The output stream's attributes are one set for the whole stream, and a stream of several parts is
    /// served part by part. The first part's bindings stand for the stream; a later part that bound
    /// differently is reported, since the attributes cannot say so and an as-processed reprocess of it
    /// would be misled (design 01 §7.3).
    ///
    /// A pipeline may also hold two supervised stages, and they share this one set of attributes. Each
    /// stage is remembered by itself, so the part check compares a stage with itself rather than with
    /// the one above it; and the stage that records first — the one nearest the source — takes the plain
    /// attribute names while any behind it are named by their element.
    private void record(final Bindings bindings, final ElementId elementId) {
        final String recorded = recordedBy.get(elementId.getId());
        if (recorded == null) {
            final boolean first = recordedBy.isEmpty();
            recordedBy.put(elementId.getId(), bindings.ruleUuid());
            bindings.asAttributes(first
                    ? null
                    : elementId.getId()).forEach(metaData::put);
        } else if (!recorded.equals(bindings.ruleUuid())) {
            errorReceiverProxy.log(Severity.WARNING, null, elementId,
                    "Part " + metaHolder.getPartIndex() + " was bound by rule " + bindings.ruleUuid()
                    + " but the stream's bindings name rule " + recorded + " from an earlier part", null);
        }
    }

    /// Whether anything above an element in its pipeline parses, or empty where the pipeline it is
    /// running in cannot be read.
    private Optional<Boolean> fedByParser(final ElementId elementId) {
        final DocRef pipelineRef = pipelineHolder.getPipeline();
        if (pipelineRef == null) {
            return Optional.empty();
        }
        final PipelineDoc pipelineDoc = pipelineStore.readDocument(pipelineRef);
        if (pipelineDoc == null) {
            return Optional.empty();
        }
        return ReplayUnits.fedByParser(pipelineDataCache.get(pipelineDoc), elementId.getId(), this::parses);
    }

    /// Whether an element type parses, as the node's own element registry has it — the same question
    /// `FragmentCheckImpl` asks of a fragment, asked the same way, because the two must agree.
    private boolean parses(final String elementType) {
        final PipelineElementType type = elementRegistryFactory.get().getElementType(elementType);
        return type != null && type.hasRole(PipelineElementType.ROLE_PARSER);
    }

    private static String reason(final Decision decision) {
        return switch (decision) {
            case Sentinel sentinel -> sentinel.reason();
            case GivenUp givenUp -> "Shape given up: " + givenUp.reason();
            case Retracted retracted -> retracted.reason();
            case Drafted drafted -> "Awaiting review: draft rule " + drafted.rule().getUuid() + " binds "
                                    + drafted.rule().getPipeline().getName();
            default -> throw new IllegalStateException("Decision " + decision + " bound nothing and refused nothing");
        };
    }
}
