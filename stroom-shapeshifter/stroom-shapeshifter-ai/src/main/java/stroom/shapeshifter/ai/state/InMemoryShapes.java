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
import stroom.shapeshifter.ai.stage.Shapes;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The shape rows as a map, by learner and shape: what scenarios run over, and what a node runs over until
 * the tables of A26 exist — node-local and gone on restart, so until then a cluster does not share what a stage has
 * learned about a shape. The rolling score is a running mean whose memory is capped in records: the
 * two columns a row can hold, {@code score} and {@code records}, with {@code records} never counted
 * above the memory so that the latest stream always weighs against at most that many earlier records.
 Every method is synchronised: one node's tasks share it.
 */
public final class InMemoryShapes implements Shapes {

    private final Map<String, Row> rows = new HashMap<>();

    @Override
    public synchronized Optional<String> reasonGivenUp(final Learner learner, final String shape) {
        return Optional.ofNullable(row(learner, shape).givenUp);
    }

    @Override
    public synchronized void giveUp(final Learner learner, final String docUuid, final String shape,
                                    final String reason) {
        row(learner, shape).givenUp = reason;
    }

    @Override
    public synchronized OptionalDouble scored(final Learner learner,
                                              final String docUuid,
                                              final String shape,
                                              final double score,
                                              final int records,
                                              final int memory) {
        final Row row = row(learner, shape);
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
    public synchronized Optional<String> relearnReason(final Learner learner, final String shape) {
        return Optional.ofNullable(row(learner, shape).relearn);
    }

    @Override
    public synchronized void markForRelearning(final Learner learner, final String docUuid, final String shape,
                                               final String reason) {
        row(learner, shape).relearn = reason;
    }

    @Override
    public synchronized void awaitReview(final Learner learner, final String docUuid, final String shape,
                                         final String ruleUuid) {
        row(learner, shape).draft = ruleUuid;
    }

    @Override
    public synchronized Optional<String> draftAwaiting(final Learner learner, final String shape) {
        return Optional.ofNullable(row(learner, shape).draft);
    }

    @Override
    public synchronized Optional<String> shapeAwaiting(final Learner learner, final String ruleUuid) {
        final String prefix = learner + "#";
        return rows.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix) && ruleUuid.equals(entry.getValue().draft))
                .map(entry -> entry.getKey().substring(prefix.length()))
                .findFirst();
    }

    /// What was learned is forgotten; who is learning is not. A promotion resets the shape while the
    /// attempt that promoted it is still open, and the attempt's row is what holds the shape (A45), so a
    /// reset here takes nothing from it.
    @Override
    public synchronized void reset(final Learner learner, final String shape) {
        final Row row = row(learner, shape);
        row.givenUp = null;
        row.relearn = null;
        row.draft = null;
        row.score = 0.0;
        row.records = 0;
    }

    /// Whatever the row holds, scored or not: the list of A46 shows a shape that has brought three
    /// records as well as one that has brought a thousand, and says how many each rests on.
    @Override
    public synchronized Optional<Rolling> rolling(final Learner learner, final String shape) {
        final Row row = rows.get(key(learner, shape));
        return row == null || row.records == 0
                ? Optional.empty()
                : Optional.of(new Rolling(row.score, row.records));
    }

    public synchronized double rollingScore(final Learner learner, final String shape) {
        return row(learner, shape).score;
    }

    public synchronized boolean isEmpty() {
        return rows.isEmpty();
    }

    private Row row(final Learner learner, final String shape) {
        return rows.computeIfAbsent(key(learner, shape), k -> new Row());
    }

    private static String key(final Learner learner, final String shape) {
        return learner + "#" + shape;
    }

    private static final class Row {

        private String givenUp;
        private String relearn;
        private String draft;
        private double score;
        private int records;
    }
}
