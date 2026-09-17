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
     * first time a question is asked.
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
     * The configuration document for one element of the chain (A21 step 2). Carries the real input that
     * element will receive — the sample for the first element, the previous element's output after that —
     * and the previous configuration if this is a re-ask.
     *
     * @param elementType           The pipeline element type, e.g. {@code XSLTFilter}.
     * @param documentType          The document type the element consumes, e.g. {@code XSLT}.
     * @param sample                The input sample and headers the whole attempt is about.
     * @param input                 What this element will actually be given to process.
     * @param previousConfiguration The configuration this question is asking to improve on, or null.
     */
    record Configuration(String elementType,
                         String documentType,
                         Sample sample,
                         String input,
                         String previousConfiguration,
                         List<StoredError> feedback) implements Question {

    }
}
