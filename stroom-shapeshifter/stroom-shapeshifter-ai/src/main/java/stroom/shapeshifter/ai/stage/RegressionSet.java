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

package stroom.shapeshifter.ai.stage;

import java.util.List;

/**
 * The records a promoted variant was accepted on, per feed and shape (ruling A18): a candidate that
 * would score lower on any of them is not promoted, however well it does on new data. Appended at
 * promotion, capped per shape by the document. In-memory in scenarios; a stream in a node.
 */
public interface RegressionSet {

    List<Accepted> accepted(String feed, String shapeSignature);

    void accept(String feed, String shapeSignature, List<Accepted> records, int capPerShape);

    /**
     * @param input The record as it was received.
     * @param score The weighted score the promoted variant achieved on it.
     */
    record Accepted(String input, double score) {

    }
}
