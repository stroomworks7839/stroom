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

import stroom.shapeshifter.ai.learning.Target;

import java.util.List;

/**
 * The records a promoted variant was accepted on, per rule (ruling A18, keyed on the rule's uuid so
 * that a rule an operator widens keeps its history): a candidate that would score lower on any of them
 * is not promoted, however well it does on new data. Appended at promotion, capped per rule by the
 * document. In-memory in scenarios; a stream in a node.
 */
public interface RegressionSet {

    List<Accepted> accepted(String ruleUuid);

    void accept(String ruleUuid, List<Accepted> records, int cap);

    /**
     * A rule that will never serve — a rejected draft, or a draft whose history moved to the rule it
     * replaced on Approve — takes its records with it.
     */
    void discard(String ruleUuid);

    /**
     * @param input   The record as it was received.
     * @param score   The weighted score the promoted variant achieved on it.
     * @param targets What each kind of record in it was to become (A31): the goldens, as design 01 §7.4
     *                described the set — empty where the attempt had none.
     */
    record Accepted(String input, double score, List<Target> targets) {

        public Accepted {
            targets = List.copyOf(targets);
        }

        public Accepted(final String input, final double score) {
            this(input, score, List.of());
        }

    }
}
