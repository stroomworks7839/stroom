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

import java.util.ArrayList;
import java.util.List;

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
                                         final String reason) {
        // A stream sentinelled twice for one shape is one row, as the table has it: it must not be
        // replayed twice when the shape settles.
        if (rows.stream().noneMatch(row -> row.docUuid().equals(docUuid)
                                           && row.shape().equals(shape)
                                           && row.inputId() == inputId)) {
            rows.add(new Row(docUuid, shape, inputId, reason));
        }
    }

    @Override
    public synchronized List<Long> release(final String docUuid, final String shape) {
        final List<Long> released = new ArrayList<>();
        rows.removeIf(row -> {
            final boolean match = row.docUuid().equals(docUuid) && row.shape().equals(shape);
            if (match) {
                released.add(row.inputId());
            }
            return match;
        });
        return released;
    }

    public synchronized List<Row> rows() {
        return List.copyOf(rows);
    }

    public synchronized boolean isEmpty() {
        return rows.isEmpty();
    }

    public record Row(String docUuid, String shape, long inputId, String reason) {

    }
}
