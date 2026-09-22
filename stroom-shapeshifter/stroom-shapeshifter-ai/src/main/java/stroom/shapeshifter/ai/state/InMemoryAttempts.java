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

import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.shared.AttemptStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/// The attempts of A28 in memory, for a harness with no database. One node's record is not the cluster's,
/// and it does not outlive the node, which is why the tables exist.
public final class InMemoryAttempts implements Attempts {

    private final Map<Long, Recorded> attempts = new LinkedHashMap<>();
    private final Map<Long, List<Turn>> turns = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public synchronized long opened(final Attempt attempt) {
        final long id = nextId.getAndIncrement();
        final long now = System.currentTimeMillis();
        attempts.put(id, new Recorded(id, attempt, AttemptStatus.IN_PROGRESS, null, null, null, 0L, now, now,
                List.of()));
        turns.put(id, new ArrayList<>());
        return id;
    }

    /// By number: a turn written as it is asked and again when it is judged is one turn.
    @Override
    public synchronized void turn(final long attemptId, final Turn turn) {
        final List<Turn> written = turns.computeIfAbsent(attemptId, key -> new ArrayList<>());
        for (int i = 0; i < written.size(); i++) {
            if (written.get(i).number() == turn.number()) {
                written.set(i, turn);
                return;
            }
        }
        written.add(turn);
    }

    @Override
    public synchronized void closed(final long attemptId,
                                    final AttemptStatus status,
                                    final String decision,
                                    final String ruleUuid,
                                    final Double score,
                                    final long tokensSpent) {
        final Recorded was = attempts.get(attemptId);
        if (was != null) {
            attempts.put(attemptId, new Recorded(was.id(), was.attempt(), status, decision, ruleUuid, score,
                    tokensSpent, was.createTimeMs(), System.currentTimeMillis(), List.of()));
        }
    }

    @Override
    public synchronized void decided(final String docUuid,
                                     final String ruleUuid,
                                     final AttemptStatus status,
                                     final String decision) {
        attempts.values().stream()
                .filter(attempt -> attempt.attempt().docUuid().equals(docUuid)
                                   && ruleUuid.equals(attempt.ruleUuid())
                                   && attempt.status() == AttemptStatus.AWAITING_REVIEW)
                .toList()
                .forEach(attempt -> closed(attempt.id(), status, decision, attempt.ruleUuid(), attempt.score(),
                        attempt.tokensSpent()));
    }

    @Override
    public synchronized Optional<Recorded> byId(final long attemptId) {
        return Optional.ofNullable(attempts.get(attemptId)).map(this::withTurns);
    }

    @Override
    public synchronized List<Recorded> forDocument(final String docUuid, final int limit) {
        return attempts.values().stream()
                .filter(attempt -> attempt.attempt().docUuid().equals(docUuid))
                .sorted((a, b) -> Long.compare(b.id(), a.id()))
                .limit(limit)
                .map(this::withTurns)
                .toList();
    }

    private Recorded withTurns(final Recorded attempt) {
        return new Recorded(attempt.id(), attempt.attempt(), attempt.status(), attempt.decision(),
                attempt.ruleUuid(), attempt.score(), attempt.tokensSpent(), attempt.createTimeMs(),
                attempt.updateTimeMs(), List.copyOf(turns.getOrDefault(attempt.id(), List.of())));
    }
}
