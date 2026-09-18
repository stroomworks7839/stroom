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

package stroom.shapeshifter.ai.scoring;

import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.filter.SchemaFilter;
import stroom.pipeline.filter.SplitFilter;
import stroom.pipeline.xmlschema.FindXMLSchemaCriteria;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerType;
import stroom.util.shared.ElementId;
import stroom.util.shared.ErrorType;
import stroom.util.shared.Location;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Schema conformance (design 01 §8.2), a gate by ruling A16: the proportion of records that validate
 * against the schemas of the document's schema group, judged per record as {@code SchemaFilterSplit}
 * judges them — the output split one top-level element at a time into Stroom's own {@code SchemaFilter}.
 * The feedback is the first failing records' messages, as Stroom post-processes them for people:
 * "Invalid content was found starting with element 'EventDetail'. One of '{EventSource}' is expected."
 * Applies to a step that transformed records into records; a parser's records are the input's shape and
 * are judged by the stream-level scorers (design 01 §4).
 * <p>
 * The schema filter is pipeline-scoped in a node and reports through the pipeline's error receiver, which
 * is swapped for a recording one for the length of the run — the same arrangement as
 * {@code DataSplitterCompiler}, with the same consequence: an instance is for one thread.
 */
public final class SchemaConformanceScorer implements Scorer {

    private static final ElementId CONFORMANCE = new ElementId("SchemaConformance");
    private static final int RECORDS_SHOWN = 5;

    private final Provider<SchemaFilter> schemaFilters;
    private final ErrorReceiverProxy errorReceiverProxy;

    @Inject
    public SchemaConformanceScorer(final Provider<SchemaFilter> schemaFilters,
                                   final ErrorReceiverProxy errorReceiverProxy) {
        this.schemaFilters = schemaFilters;
        this.errorReceiverProxy = errorReceiverProxy;
    }

    @Override
    public ScorerType type() {
        return ScorerType.SCHEMA_CONFORMANCE;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters, final Attempted step) {
        final String output = step.result().output();
        final Optional<OutputRecords> parsed = OutputRecords.ofTransformed(step)
                .filter(records -> !records.records().isEmpty());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        final int total = parsed.get().records().size();
        final SchemaConformanceParameters conformance = (SchemaConformanceParameters) parameters;
        final RecordErrors errors = new RecordErrors();
        final RecordCounter counter = new RecordCounter(errors);
        final ErrorReceiver previous = errorReceiverProxy.getErrorReceiver();
        errorReceiverProxy.setErrorReceiver(errors);
        try {
            final SchemaFilter schemaFilter = schemaFilters.get();
            schemaFilter.setElementId(CONFORMANCE);
            final FindXMLSchemaCriteria constraint = new FindXMLSchemaCriteria();
            constraint.setSchemaGroup(conformance.getSchemaGroup());
            schemaFilter.setSchemaConstraint(constraint);
            counter.setTarget(schemaFilter);
            final SplitFilter split = new SplitFilter();
            split.setSplitCount(1);
            split.setTarget(counter);

            split.startProcessing();
            try {
                split.startStream();
                final XMLReader reader = ConfinedXml.reader();
                reader.setContentHandler(split);
                reader.parse(new InputSource(new StringReader(output)));
                split.endStream();
            } finally {
                split.endProcessing();
            }
        } catch (final SAXException | IOException | RuntimeException e) {
            // The output parsed once already, to be counted; a failure here is the validator's, and every
            // record it did not get to judge is a record that did not conform.
            errors.log(Severity.FATAL_ERROR, null, CONFORMANCE, e.getMessage(), ErrorType.GENERIC, e);
        } finally {
            errorReceiverProxy.setErrorReceiver(previous);
        }

        // Records the validator never reached count as failing, not as conforming.
        final int passing = Math.max(0, counter.records - errors.failing.size());
        final int failing = total - passing;
        final List<StoredError> diagnostics = new ArrayList<>();
        int shown = 0;
        for (final Map.Entry<Integer, String> failure : errors.failing.entrySet()) {
            if (shown++ == RECORDS_SHOWN) {
                diagnostics.add(new StoredError(Severity.WARNING, null, CONFORMANCE,
                        (failing - RECORDS_SHOWN) + " more record(s) did not validate"));
                break;
            }
            diagnostics.add(new StoredError(Severity.WARNING, null, CONFORMANCE,
                    "Record " + failure.getKey() + ": " + failure.getValue()));
        }
        return Optional.of(new Score(type(), (double) passing / total, diagnostics));
    }

    /**
     * Sits between the split and the schema filter, so that every error the validator raises can be laid
     * at the record whose document is passing through.
     */
    private static final class RecordCounter extends AbstractXMLFilter {

        private final RecordErrors errors;
        private int records;

        private RecordCounter(final RecordErrors errors) {
            this.errors = errors;
        }

        @Override
        public void startDocument() throws SAXException {
            records++;
            errors.current = records;
            super.startDocument();
        }
    }

    /**
     * The first error at or above {@code ERROR} for each record that raised one, by record number.
     */
    private static final class RecordErrors implements ErrorReceiver {

        private final Map<Integer, String> failing = new LinkedHashMap<>();
        private int current;

        @Override
        public void log(final Severity severity,
                        final Location location,
                        final ElementId elementId,
                        final String message,
                        final ErrorType errorType,
                        final Throwable e) {
            if (severity.greaterThanOrEqual(Severity.ERROR)) {
                failing.putIfAbsent(current, message);
            }
        }
    }
}
