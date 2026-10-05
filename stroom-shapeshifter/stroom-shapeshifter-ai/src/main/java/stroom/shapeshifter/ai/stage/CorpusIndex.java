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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.ai.stage.RegressionSet.Kind;

import java.util.List;

/// The rows behind a node's corpus (design 04 §4.1): one per entry, naming the stream and the segment
/// that hold the record and its accepted output, so that the set can be read without scanning the feed.
/// The text itself is in the stream store; this is the index of it. In-memory in a harness without a
/// database; a table in a node.
public interface CorpusIndex {

    /// A rule's rows, oldest first.
    ///
    /// @param ruleUuid The rule.
    /// @return Its rows.
    List<Row> rows(String ruleUuid);

    /// Rows for the entries a promotion wrote, after the stream holding them is closed.
    ///
    /// @param rows The rows, in the order the entries were written.
    void add(List<Row> rows);

    /// Drop a rule's oldest rows beyond the cap (A63).
    ///
    /// @param ruleUuid The rule.
    /// @param cap      How many rows it may keep.
    /// @return The streams no row names any more, for the caller to delete.
    List<Long> trim(String ruleUuid, int cap);

    /// Drop every row of a rule.
    ///
    /// @param ruleUuid The rule.
    /// @return The streams no row names any more, for the caller to delete.
    List<Long> discard(String ruleUuid);

    /// Re-key a rule's rows to another: a draft's history becomes the incumbent's on Approve, after what
    /// the incumbent already holds.
    ///
    /// @param fromRuleUuid The draft.
    /// @param toRuleUuid   The rule it replaced.
    void move(String fromRuleUuid, String toRuleUuid);

    // --------------------------------------------------------------------------------

    /// One row.
    ///
    /// @param docUuid      The document.
    /// @param ruleUuid     The rule.
    /// @param metaId       The stream holding the entry.
    /// @param segment      Which segment of it, from zero.
    /// @param kind         What the record became.
    /// @param score        The score it was accepted with.
    /// @param attemptId    The attempt that accepted it, where one was open.
    /// @param acceptTimeMs When.
    record Row(String docUuid,
               String ruleUuid,
               long metaId,
               int segment,
               Kind kind,
               double score,
               Long attemptId,
               long acceptTimeMs) {

    }
}
