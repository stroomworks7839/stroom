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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.stage.Shapes;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The shape rows as a map. The rolling score is a running mean whose memory is capped in records: the
 * two columns a row can hold, {@code score} and {@code records}, with {@code records} never counted
 * above the memory so that the latest stream always weighs against at most that many earlier records.
 */
public final class InMemoryShapes implements Shapes {

    private final Map<String, Row> rows = new HashMap<>();

    @Override
    public Optional<String> reasonGivenUp(final String docUuid, final String shape) {
        return Optional.ofNullable(row(docUuid, shape).givenUp);
    }

    @Override
    public void giveUp(final String docUuid, final String shape, final String reason) {
        row(docUuid, shape).givenUp = reason;
    }

    @Override
    public OptionalDouble scored(final String docUuid,
                                 final String shape,
                                 final double score,
                                 final int records,
                                 final int memory) {
        final Row row = row(docUuid, shape);
        final int remembered = Math.min(row.records, memory);
        if (remembered + records > 0) {
            row.score = (row.score * remembered + score * records) / (remembered + records);
        }
        row.records = remembered + records;
        return row.records >= memory
                ? OptionalDouble.of(row.score)
                : OptionalDouble.empty();
    }

    @Override
    public Optional<String> relearnReason(final String docUuid, final String shape) {
        return Optional.ofNullable(row(docUuid, shape).relearn);
    }

    @Override
    public void markForRelearning(final String docUuid, final String shape, final String reason) {
        row(docUuid, shape).relearn = reason;
    }

    @Override
    public void reset(final String docUuid, final String shape) {
        rows.remove(key(docUuid, shape));
    }

    public double rollingScore(final String docUuid, final String shape) {
        return row(docUuid, shape).score;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    private Row row(final String docUuid, final String shape) {
        return rows.computeIfAbsent(key(docUuid, shape), k -> new Row());
    }

    private static String key(final String docUuid, final String shape) {
        return docUuid + "/" + shape;
    }

    private static final class Row {

        private String givenUp;
        private String relearn;
        private double score;
        private int records;
    }
}
