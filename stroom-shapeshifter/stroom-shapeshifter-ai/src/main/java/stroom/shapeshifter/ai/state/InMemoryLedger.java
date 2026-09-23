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

import stroom.shapeshifter.ai.stage.Ledger;
import stroom.shapeshifter.ai.stage.Ledger.Page;
import stroom.shapeshifter.ai.stage.Replayable;
import stroom.shapeshifter.shared.LedgerShape;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ledger rows as a list: what scenarios run over, and what a node runs over until the A26 module
 * exists — node-local and gone on restart.
 Every method is synchronised: one node's tasks share it.
 */
public final class InMemoryLedger implements Ledger {

    private final List<Row> rows = new ArrayList<>();

    @Override
    public synchronized void sentinelled(final String docUuid,
                                         final String shape,
                                         final long inputId,
                                         final String pipeline,
                                         final String reason) {
        // A stream sentinelled twice for one shape is one row, as the table has it: it must not be
        // replayed twice when the shape settles.
        if (rows.stream().noneMatch(row -> row.docUuid().equals(docUuid)
                                           && row.shape().equals(shape)
                                           && row.inputId() == inputId)) {
            rows.add(new Row(docUuid, shape, inputId, pipeline, reason, System.currentTimeMillis()));
        }
    }

    @Override
    public synchronized List<Replayable> release(final String docUuid, final String shape) {
        final List<Replayable> released = new ArrayList<>();
        rows.removeIf(row -> {
            final boolean match = row.docUuid().equals(docUuid) && row.shape().equals(shape);
            if (match) {
                released.add(new Replayable(row.inputId(), row.pipeline()));
            }
            return match;
        });
        return released;
    }

    /// As the table has it: a row per shape, most recently added to first, and nothing taken off.
    @Override
    public synchronized Page waiting(final Collection<String> docUuids, final long offset, final int limit) {
        final Map<String, List<Row>> byShape = new LinkedHashMap<>();
        rows.stream()
                .filter(row -> docUuids.contains(row.docUuid()))
                // By document as well as shape: two documents may have shapes of the same name.
                .forEach(row -> byShape.computeIfAbsent(row.docUuid() + '\u0000' + row.shape(),
                        key -> new ArrayList<>()).add(row));
        // Newest first, and the tiebreaker the table uses: where two shapes were last added to in the
        // same millisecond, the one whose newest row was written last.
        final List<LedgerShape> shapes = byShape.values().stream()
                .sorted(Comparator
                        .<List<Row>>comparingLong(group -> newest(group).timeMs())
                        .thenComparingInt(group -> rows.indexOf(newest(group)))
                        .reversed())
                .map(InMemoryLedger::shape)
                .toList();
        return new Page(
                shapes.stream().skip(Math.max(0L, offset)).limit(Math.max(0, limit)).toList(),
                shapes.size());
    }

    /// The row a shape's summary is taken from: the last of its streams to arrive.
    private static Row newest(final List<Row> group) {
        return group.get(group.size() - 1);
    }

    /// The newest row's reason stands for the shape: what the last stream to arrive was told is what is
    /// true of the shape now.
    private static LedgerShape shape(final List<Row> group) {
        final Row newest = newest(group);
        return new LedgerShape(
                ShapeshifterAiDoc.buildDocRef().uuid(newest.docUuid()).build(),
                newest.shape(),
                group.size(),
                group.stream().mapToLong(Row::timeMs).min().orElse(newest.timeMs()),
                group.stream().mapToLong(Row::timeMs).max().orElse(newest.timeMs()),
                newest.reason());
    }

    public synchronized List<Row> rows() {
        return List.copyOf(rows);
    }

    public synchronized boolean isEmpty() {
        return rows.isEmpty();
    }

    public record Row(String docUuid,
                      String shape,
                      long inputId,
                      String pipeline,
                      String reason,
                      long timeMs) {

    }
}
