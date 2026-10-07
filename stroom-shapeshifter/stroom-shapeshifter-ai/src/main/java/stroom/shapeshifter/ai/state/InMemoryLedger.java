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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Replayable;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LedgerLearner;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ledger rows as a list: what scenarios run over. Every method is synchronised: one node's tasks
 * share it.
 */
public final class InMemoryLedger implements Ledger {

    private final List<Row> rows = new ArrayList<>();

    @Override
    public synchronized void sentinelled(final Learner learner,
                                         final String docUuid,
                                         final long inputId,
                                         final String reason) {
        // A stream sentinelled twice is one row, as the table has it: it must not be replayed twice when
        // the learner settles.
        if (rows.stream().noneMatch(row -> row.learner().equals(learner) && row.inputId() == inputId)) {
            rows.add(new Row(learner, docUuid, inputId, reason, System.currentTimeMillis()));
        }
    }

    @Override
    public synchronized void restate(final Learner learner, final String reason) {
        for (int i = 0; i < rows.size(); i++) {
            final Row row = rows.get(i);
            if (row.learner().equals(learner)) {
                // The time it was sentinelled is kept: the stream has been waiting since it arrived,
                // and a new reason does not make it newly late.
                rows.set(i, new Row(row.learner(), row.docUuid(), row.inputId(), reason, row.timeMs()));
            }
        }
    }

    /// The learner's rows brought to the document it runs under (see [InMemoryLearners]).
    ///
    /// @param learner The learner.
    /// @param docUuid The document.
    public synchronized void runsUnder(final Learner learner, final String docUuid) {
        rows.replaceAll(row -> row.learner().equals(learner)
                ? new Row(learner, docUuid, row.inputId(), row.reason(), row.timeMs())
                : row);
    }

    @Override
    public synchronized List<Replayable> release(final Learner learner) {
        final List<Replayable> released = new ArrayList<>();
        rows.removeIf(row -> {
            final boolean match = row.learner().equals(learner);
            if (match) {
                released.add(new Replayable(row.inputId(), learner.pipelineUuid()));
            }
            return match;
        });
        return released;
    }

    /// As the table has it: a row per learner, most recently added to first, and nothing taken off.
    @Override
    public synchronized Page waiting(final Collection<String> docUuids, final long offset, final int limit) {
        final Map<Learner, List<Row>> byLearner = new LinkedHashMap<>();
        rows.stream()
                .filter(row -> docUuids.contains(row.docUuid()))
                .forEach(row -> byLearner.computeIfAbsent(row.learner(), key -> new ArrayList<>()).add(row));
        // Newest first, and the tiebreaker the table uses: where two learners were last added to in the
        // same millisecond, the one whose newest row was written last.
        final List<LedgerLearner> learners = byLearner.values().stream()
                .sorted(Comparator
                        .<List<Row>>comparingLong(group -> newest(group).timeMs())
                        .thenComparingInt(group -> rows.indexOf(newest(group)))
                        .reversed())
                .map(InMemoryLedger::learner)
                .toList();
        return new Page(
                learners.stream().skip(Math.max(0L, offset)).limit(Math.max(0, limit)).toList(),
                learners.size());
    }

    private static Row newest(final List<Row> group) {
        return group.get(group.size() - 1);
    }

    private static LedgerLearner learner(final List<Row> group) {
        final Row newest = newest(group);
        return new LedgerLearner(
                ShapeshifterAiDoc.buildDocRef().uuid(newest.docUuid()).build(),
                new LearnerRef(newest.learner().pipelineUuid(), newest.learner().elementId()),
                group.size(),
                group.stream().mapToLong(Row::timeMs).min().orElse(newest.timeMs()),
                group.stream().mapToLong(Row::timeMs).max().orElse(newest.timeMs()),
                newest.reason());
    }

    /// @return Every row, oldest first.
    public synchronized List<Row> rows() {
        return List.copyOf(rows);
    }

    /// @return Whether nothing is on the ledger.
    public synchronized boolean isEmpty() {
        return rows.isEmpty();
    }

    /// One stream on the ledger.
    ///
    /// @param learner The learner that sentinelled it.
    /// @param docUuid The document it runs under.
    /// @param inputId The stream.
    /// @param reason  Why.
    /// @param timeMs  When.
    public record Row(Learner learner,
                      String docUuid,
                      long inputId,
                      String reason,
                      long timeMs) {

    }
}
