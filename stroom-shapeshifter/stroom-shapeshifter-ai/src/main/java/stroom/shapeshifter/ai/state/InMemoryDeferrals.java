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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Learner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// The deferral rows as a list: what scenarios inspect, node-local and gone on restart. Every method is
/// synchronised: one node's tasks share it.
public final class InMemoryDeferrals implements Deferrals {

    private final List<Deferral> rows = new ArrayList<>();

    @Override
    public synchronized void deferred(final Learner learner,
                                      final String docUuid,
                                      final long inputId,
                                      final String feed,
                                      final String type,
                                      final Long taskId,
                                      final Integer filterId,
                                      final String failedAgainst,
                                      final String reason,
                                      final int failingRecords) {
        final long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size(); i++) {
            final Deferral row = rows.get(i);
            if (row.learner().equals(learner) && row.inputId() == inputId) {
                rows.set(i, new Deferral(learner, docUuid, inputId, feed, type, taskId, filterId, failedAgainst,
                        reason, failingRecords, row.firstDeferredMs(), row.attempts() + 1));
                return;
            }
        }
        rows.add(new Deferral(learner, docUuid, inputId, feed, type, taskId, filterId, failedAgainst, reason,
                failingRecords, now, 1));
    }

    @Override
    public synchronized void released(final Learner learner, final long inputId) {
        rows.removeIf(row -> row.learner().equals(learner) && row.inputId() == inputId);
    }

    /// The streams waiting on the learner, brought to the document it runs under (see
    /// [InMemoryLearners]).
    ///
    /// @param learner The learner.
    /// @param docUuid The document.
    public synchronized void runsUnder(final Learner learner, final String docUuid) {
        rows.replaceAll(row -> row.learner().equals(learner)
                ? new Deferral(learner, docUuid, row.inputId(), row.feed(), row.type(), row.taskId(),
                        row.filterId(), row.failedAgainst(), row.reason(), row.failingRecords(),
                        row.firstDeferredMs(), row.attempts())
                : row);
    }

    @Override
    public synchronized Optional<Deferral> of(final Learner learner, final long inputId) {
        return rows.stream()
                .filter(row -> row.learner().equals(learner) && row.inputId() == inputId)
                .findFirst();
    }

    @Override
    public synchronized List<Stamp> stampsWaiting() {
        return rows.stream()
                .filter(row -> row.failedAgainst() != null)
                .map(row -> new Stamp(row.learner(), row.failedAgainst()))
                .distinct()
                .toList();
    }

    /// What waits on the learners of a pipeline, oldest first: what waking the pipeline wakes (see
    /// [InMemoryWake]).
    ///
    /// @param pipelineUuid The pipeline.
    /// @return Its rows.
    public synchronized List<Deferral> forPipeline(final String pipelineUuid) {
        return rows.stream()
                .filter(row -> row.learner().pipelineUuid().equals(pipelineUuid))
                .sorted(Comparator.comparingLong(Deferral::firstDeferredMs).thenComparingLong(Deferral::inputId))
                .toList();
    }

    @Override
    public synchronized List<Deferral> forDocument(final String docUuid) {
        return rows.stream()
                .filter(row -> row.docUuid().equals(docUuid))
                .sorted(Comparator.comparingLong(Deferral::firstDeferredMs).thenComparingLong(Deferral::inputId))
                .toList();
    }
}
