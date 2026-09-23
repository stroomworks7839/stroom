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

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.LoggedException;
import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.factory.Pipeline;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.factory.PipelineFactory;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.stepping.capture.HeadlessCapture;
import stroom.pipeline.xml.event.EventList;
import stroom.pipeline.xml.event.simple.SimpleEventList;
import stroom.pipeline.xml.event.simple.SimpleEventListBuilder;
import stroom.shapeshifter.ai.extraction.PerRecord;
import stroom.shapeshifter.ai.extraction.RecordJoin;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.task.api.TaskContextFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.ElementId;
import stroom.util.shared.Indicators;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The fragment run as a pipeline (§12 item 2): the real elements, the real pools, the real filters, and
/// what each of them made of the stream taken from the capture the build was given.
///
/// This is what a node judges a candidate and serves a bound rule with, and the reason it exists is that
/// a stand-in can only be as right as the person who wrote it. The pipeline runs a `SplitFilter` for
/// real, compiles a stylesheet through the pool for real, and applies whatever the fragment inherits
/// from its parent — none of which the module's step runners do, and all of which decide whether a
/// promoted rule works on the next stream.
///
/// Each element's *input* is taken as the one before it wrote rather than from the capture, because the
/// recorders above the parser report what they read by source span and a headless run has no span to ask
/// for (see [HeadlessCapture]). The first element's input is the stream itself, which is what the
/// input-coverage scorer needs (A11), and every element after it is given what its predecessor wrote —
/// exactly the chain the scorers already read.
public final class PipelineFragmentRunner implements FragmentRunner {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PipelineFragmentRunner.class);

    private static final String SOURCE = "Source";

    /// What turns the text a stage is given into the events every element of a transformation-stage
    /// fragment expects. A fragment learned for a stage fed by a parser (A1) holds no parser of its
    /// own — there is nothing left to parse — but it is still handed a stream of bytes here, so one
    /// stands in front of it. It is no part of the chain: the walk that says what each element made of
    /// the input reads the fragment as the fragment has it.
    private static final String XML_PARSER = "XMLParser";
    private static final String XML_PARSER_ID = "shapeshifterAiFragmentParser";

    /// Where the chain's tail writes, so that the events it makes can be played on to the supervisor's
    /// own downstream rather than made a second time (see [FragmentRunner#lastOutput]).
    private static final String OUTPUT_ID = "shapeshifterAiOutput";

    /// How many records of a run to keep. A judgement is made on a sample (§3), not on a production
    /// stream, and the capture holds what it keeps in memory.
    private static final int MOST_RECORDS = 10_000;

    /// Elements that shape the stream into records without changing what any record says: run by the
    /// pipeline, not a step anything scores (§12 item 25).
    private static final Set<String> SHAPING = Set.of("SplitFilter");

    private final PipelineStore pipelineStore;
    private final PipelineDataCache pipelineDataCache;
    private final ElementRegistryFactory elementRegistryFactory;
    private final Provider<PipelineFactory> pipelineFactoryProvider;
    private final Provider<HeadlessCapture> captureProvider;
    private final Provider<ErrorReceiverProxy> errorReceiverProvider;
    private final Provider<FragmentOutput> fragmentOutputProvider;
    private final TaskContextFactory taskContextFactory;
    private final Map<String, Boolean> parsers = new HashMap<>();

    private SimpleEventList lastOutput;

    @Inject
    public PipelineFragmentRunner(final PipelineStore pipelineStore,
                                  final PipelineDataCache pipelineDataCache,
                                  final ElementRegistryFactory elementRegistryFactory,
                                  final Provider<PipelineFactory> pipelineFactoryProvider,
                                  final Provider<HeadlessCapture> captureProvider,
                                  final Provider<ErrorReceiverProxy> errorReceiverProvider,
                                  final Provider<FragmentOutput> fragmentOutputProvider,
                                  final TaskContextFactory taskContextFactory,
                                  final List<StepRunner> runners) {
        this.pipelineStore = pipelineStore;
        this.pipelineDataCache = pipelineDataCache;
        this.elementRegistryFactory = elementRegistryFactory;
        this.pipelineFactoryProvider = pipelineFactoryProvider;
        this.captureProvider = captureProvider;
        this.errorReceiverProvider = errorReceiverProvider;
        this.fragmentOutputProvider = fragmentOutputProvider;
        this.taskContextFactory = taskContextFactory;
        // The runners are consulted for one thing only: whether an element parses raw input into records
        // (design 01 §4), which decides where the scorers of meaning apply.
        runners.forEach(runner -> parsers.put(runner.elementType(), runner.parser()));
    }

    @Override
    public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
        final PipelineDoc fragmentDoc = pipelineStore.readDocument(fragment);
        final PipelineData merged = pipelineDataCache.get(fragmentDoc);

        final Map<String, PipelineElement> elements = new HashMap<>();
        merged.getAddedElements().forEach(element -> elements.put(element.getId(), element));
        final Map<String, String> next = new HashMap<>();
        for (final PipelineLink link : merged.getAddedLinks()) {
            if (next.put(link.getFrom(), link.getTo()) != null) {
                throw new IllegalStateException("Fragment " + fragment.getName()
                                                + " forks: a fragment is a single chain");
            }
        }

        // One capture per run: what this fragment did with this stream and nothing else.
        final HeadlessCapture capture = captureProvider.get();
        capture.setMaxRecords(MOST_RECORDS);

        // And one recorder per run, for the caller that means to play the tail's events on rather than
        // run the fragment again. Kept whatever the run came to, since a run that stopped has an empty
        // list and not a stale one.
        final SimpleEventListBuilder emitted = new SimpleEventListBuilder();
        lastOutput = (SimpleEventList) emitted.getEventList();
        final FragmentOutput fragmentOutput = fragmentOutputProvider.get();
        final ContentHandler borrowed = fragmentOutput.getHandler();
        fragmentOutput.setHandler(emitted);

        // Somewhere for the elements to log. A candidate that will not compile says so through the
        // receiver, and without one in place the first thing it says is a NullPointerException that
        // takes the attempt with it. The one that was there is put back: this run is a judgement made
        // inside somebody else's pipeline, and its errors are not theirs.
        final ErrorReceiverProxy errorReceiverProxy = errorReceiverProvider.get();
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        final LoggingErrorReceiver logging = new LoggingErrorReceiver();
        errorReceiverProxy.setErrorReceiver(logging);
        String failure = null;
        try {
            final Pipeline pipeline = pipelineFactoryProvider.get()
                    .create(runnable(fragment, merged, elements, next), taskContextFactory.current(), capture);
            pipeline.process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8.name());
        } catch (final LoggedException e) {
            // Already said through the receiver, which is where the diagnostics are read from. A
            // candidate that will not compile stops the pipeline in exactly this way, and stopping is
            // the answer to the question being asked, not a failure of the asking.
            LOGGER.debug("Fragment {} stopped: {}", fragment.getName(), e.getMessage());
        } catch (final RuntimeException e) {
            // Something nobody recorded. Kept, so that a step reporting nothing is not also silent.
            LOGGER.debug(() -> LogUtil.message("Fragment {} failed: {}", fragment.getName(), e.getMessage()), e);
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            errorReceiverProxy.setErrorReceiver(previous);
            fragmentOutput.setHandler(borrowed);
        }

        final List<Attempted> steps = new ArrayList<>();
        final Set<String> visited = new HashSet<>();
        String current = input;
        for (String id = next.get(SOURCE); id != null; id = next.get(id)) {
            if (!visited.add(id)) {
                throw new IllegalStateException("Fragment " + fragment.getName() + " loops at " + id);
            }
            final PipelineElement element = elements.get(id);
            if (element == null) {
                throw new IllegalStateException("Fragment " + fragment.getName() + " links to " + id
                                                + ", which it does not define");
            }
            if (SHAPING.contains(element.getType())) {
                continue;
            }
            final String output = written(capture, id);
            // A step that produced nothing stops the chain, as the stand-in does — and takes the reason
            // with it. Where a pipeline stops, the element that stopped it is often not the one that
            // produced nothing: a stylesheet that will not compile refuses before the parser it sits
            // below has written a single record. So the step that stops carries what every element from
            // here on said, or the run would report a chain that stopped for no stated reason.
            final List<String> speaking = output == null
                    ? remaining(id, next, elements)
                    : List.of(id);
            final StepResult result = new StepResult(output,
                    diagnostics(capture, speaking, logging, output == null
                            ? failure
                            : null));
            steps.add(new Attempted(element.getType(), parsers.getOrDefault(element.getType(), false),
                    current, result, boundary));
            if (output == null) {
                break;
            }
            current = output;
        }
        return steps;
    }

    @Override
    public Optional<EventList> lastOutput() {
        // A run that emitted nothing has nothing to serve, and an empty list is not an output: firing it
        // at a downstream would write an empty stream, which reads as a feed that had nothing in it. The
        // caller is told there are no events and says why.
        return Optional.ofNullable(lastOutput)
                .filter(events -> !events.getEvents().isEmpty())
                .map(EventList.class::cast);
    }

    /// The fragment as a pipeline that can be handed a stream. Two elements are added to what the
    /// fragment itself holds, and neither belongs to the chain: a parser in front where the chain has
    /// none, because a fragment learned for a stage fed by a parser is pushed events and is given text
    /// here; and an output filter at the tail, so that the events the last element makes are kept.
    ///
    /// The walk that says what each element made of the input reads the fragment as the fragment has
    /// it, so nothing added here is scored, re-asked or shown to anyone.
    private PipelineData runnable(final DocRef fragment,
                                  final PipelineData merged,
                                  final Map<String, PipelineElement> elements,
                                  final Map<String, String> next) {
        final PipelineDataBuilder builder = new PipelineDataBuilder(merged);
        final String first = next.get(SOURCE);
        if (first == null) {
            throw new IllegalStateException("Fragment " + fragment.getName() + " links nothing to its source");
        }
        if (!parses(elements.get(first))) {
            // Out of the add list, not onto the remove list: `removeLink` records a link taken away from
            // an *inherited* pipeline, and this one is not inherited from anywhere. Left on, the source
            // would still be linked straight to an element that cannot take a stream.
            builder.getLinks().getAddList()
                    .removeIf(link -> SOURCE.equals(link.getFrom()) && first.equals(link.getTo()));
            builder.addElement(new PipelineElement(XML_PARSER_ID, XML_PARSER));
            builder.addLink(SOURCE, XML_PARSER_ID);
            builder.addLink(XML_PARSER_ID, first);
        }
        String tail = first;
        final Set<String> seen = new HashSet<>();
        seen.add(first);
        for (String id = next.get(tail); id != null && seen.add(id); id = next.get(id)) {
            tail = id;
        }
        builder.addElement(new PipelineElement(OUTPUT_ID, FragmentOutputFilter.TYPE));
        builder.addLink(tail, OUTPUT_ID);
        return builder.build();
    }

    /// Whether an element of a fragment turns raw input into records, as the node's own element registry
    /// has it — the same answer [ReplayUnits] derives a fragment's replay unit from, asked the same way.
    ///
    /// The step runners are not asked. A fragment may hold a parser no runner stands in for — stroom's
    /// XML parser, the combined parser, anything a person put there by hand — and a parser mistaken for
    /// a filter gets another parser spliced in front of it and a pipeline that cannot be linked.
    private boolean parses(final PipelineElement element) {
        if (element == null) {
            return false;
        }
        final PipelineElementType type = elementRegistryFactory.get().getElementType(element.getType());
        return type != null && type.hasRole(PipelineElementType.ROLE_PARSER);
    }

    /// This element and every one after it in the chain: who might have something to say about a run
    /// that stopped here.
    private static List<String> remaining(final String from,
                                          final Map<String, String> next,
                                          final Map<String, PipelineElement> elements) {
        final List<String> ids = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (String id = from; id != null && seen.add(id); id = next.get(id)) {
            if (elements.containsKey(id) && !SHAPING.contains(elements.get(id).getType())) {
                ids.add(id);
            }
        }
        return ids;
    }

    /// What one element wrote for the whole stream: each record's output joined back into one document,
    /// since that is what the scorers and the goldens read (see [RecordJoin]).
    private static String written(final HeadlessCapture capture, final String elementId) {
        final List<String> written = capture.outputOf(elementId).stream()
                .filter(text -> text != null && !text.isBlank())
                .toList();
        return RecordJoin.join(written);
    }

    /// What one element logged over the whole stream, once each: the same rule the per-record runs
    /// already apply, so that a re-ask says no more here than it does there.
    private static List<StoredError> diagnostics(final HeadlessCapture capture,
                                                 final List<String> elementIds,
                                                 final LoggingErrorReceiver logging,
                                                 final String failure) {
        final List<StoredError> errors = new ArrayList<>();
        for (final String elementId : elementIds) {
            capture.getRecords().forEach(record -> {
                final HeadlessCapture.ElementIo io = record.byElement().get(elementId);
                final Indicators indicators = io == null
                        ? null
                        : io.indicators();
                if (indicators != null) {
                    errors.addAll(indicators.getErrorList());
                }
            });
            // And whatever was logged outside a record: a parser that failed before it produced one, an
            // element that refused before anything reached it, or a complaint on the way out. The
            // capture takes what it finds at each record boundary and clears it, so what is left here is
            // exactly what no record carried.
            final Indicators outside = logging.getIndicators(new ElementId(elementId));
            if (outside != null) {
                errors.addAll(outside.getErrorList());
            }
        }
        if (errors.isEmpty() && failure != null) {
            errors.add(new StoredError(Severity.FATAL_ERROR, null, new ElementId(elementIds.getFirst()),
                    failure));
        }
        return PerRecord.told(errors);
    }
}
