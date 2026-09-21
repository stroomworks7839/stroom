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

package stroom.shapeshifter.ai.learning;

import stroom.util.shared.StoredError;

import java.util.List;

/**
 * The typed questions of the A21 dialogue. Each carries what the model needs to answer it and nothing
 * about how it is rendered into a prompt, which is the {@link Advisor}'s concern.
 */
public sealed interface Question {

    /**
     * Diagnostics from the previous answer to the same question, if it was refused or failed. Empty the
     * first time a question is asked — unless the attempt is relearning a bound shape (A29), when the
     * first asking of each question carries why the incumbent fell short.
     */
    List<StoredError> feedback();

    /**
     * Which chain of elements fits the sample, chosen from the document's allowed elements (A21 step 1).
     * The sample's key values are the cheap half of the answer (A29): a {@code Format} of JSON, where the
     * key includes it, names the parser before the text is read.
     */
    record Chain(Sample sample,
                 List<String> allowedElements,
                 List<StoredError> feedback) implements Question {

    }

    /**
     * What one record is in this input (A31): asked before anything about meaning, for every kind of
     * input, and answered without a target. For raw text the reply is a Data Splitter configuration
     * that cuts the input into records and emits each record's whole text as one field — the boundary
     * and nothing else — judged by coverage and yield alone; for XML the element that is one record
     * (A35); for JSON the key of the array whose items are records, or {@code root}.
     */
    record Split(Sample sample,
                 String elementType,
                 String documentType,
                 InputKind kind,
                 List<StoredError> feedback) implements Question {

    }

    /**
     * What one kind of record should become (A31): one representative record, as the split cut it, put
     * with the schema's rules and the document's instructions; the reply is the event as a single
     * document, or {@code none} for a kind that yields no event. Validated at once by the scorers of
     * meaning, so the re-ask carries the shortfall of the document the model itself wrote.
     *
     * @param record The representative record's text.
     * @param kind   Which of the sample's record kinds this is, one-based.
     * @param total  How many kinds the sample has.
     */
    record TargetFor(Sample sample,
                     String record,
                     int kind,
                     int total,
                     List<StoredError> feedback) implements Question {

    }

    /**
     * The configuration document for one element of the chain (A21 step 2). Carries the real input that
     * element will receive — the sample for the first element, the previous element's output after that —
     * and the previous configuration if this is a re-ask.
     *
     * @param elementType           The pipeline element type, e.g. {@code XSLTFilter}.
     * @param documentType          The document type the element consumes, e.g. {@code XSLT}.
     * @param sample                The input sample and headers the whole attempt is about.
     * @param input                 What this element will actually be given to process.
     * @param previousConfiguration The configuration this question is asking to improve on, or null.
     * @param split                 The record boundary already settled (A31, A35): the configuration that
     *                              cuts it, for the parser that must extract within it, or the element
     *                              that is one record, for a transform over XML; null where the plan has
     *                              no split or it does not concern this element.
     * @param targets               What each kind of record must become (A31): the parser's records must
     *                              carry every value these need, the transform must produce exactly
     *                              these. Empty where the dialogue has no targets.
     */
    record Configuration(String elementType,
                         String documentType,
                         Sample sample,
                         String input,
                         String previousConfiguration,
                         Boundary split,
                         List<Target> targets,
                         List<StoredError> feedback) implements Question {

        public Configuration {
            targets = List.copyOf(targets);
        }
    }
}
