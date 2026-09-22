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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/// The attempts of A28 in memory, for a harness with no database. One node's record is not the cluster's,
/// and it does not outlive the node, which is why the tables exist.
public final class InMemoryAttempts implements Attempts {

    /// The states in which an attempt is still learning, and so still holds its shape (A45). A draft
    /// awaiting review is not one of them: it has written its rule, and the rule is what the router and
    /// the shape's own row answer with until a person decides.
    private static final Set<AttemptStatus> OPEN = Set.of(AttemptStatus.IN_PROGRESS,
            AttemptStatus.AWAITING_MODEL);

    private final Map<Long, Recorded> attempts = new LinkedHashMap<>();
    private final Map<Long, List<Turn>> turns = new LinkedHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /// One open attempt per shape is what one learner means (A45); an attempt whose expiry has passed has
    /// lapsed, and the next node takes the shape.
    @Override
    public synchronized Optional<Long> opened(final Attempt attempt, final long nowMs) {
        final long now = System.currentTimeMillis();
        if (open(attempt.docUuid(), attempt.shape(), nowMs).isPresent()) {
            return Optional.empty();
        }
        final long id = nextId.getAndIncrement();
        attempts.put(id, new Recorded(id, attempt, AttemptStatus.IN_PROGRESS, null, null, null, 0L, now, now,
                List.of()));
        turns.put(id, new ArrayList<>());
        return Optional.of(id);
    }

    /// The newest, as the rows are read: an attempt superseded by a later one for the same shape is not
    /// what a node picks up.
    @Override
    public synchronized Optional<Recorded> open(final String docUuid, final String shape, final long nowMs) {
        return attempts.values().stream()
                .filter(attempt -> attempt.attempt().docUuid().equals(docUuid)
                                   && attempt.attempt().shape().equals(shape)
                                   && OPEN.contains(attempt.status())
                                   && attempt.attempt().expiryMs() > nowMs)
                .max(Comparator.comparingLong(Recorded::id))
                .map(this::withTurns);
    }

    @Override
    public synchronized void parked(final long attemptId,
                                    final AttemptStatus status,
                                    final long expiryMs,
                                    final long tokensSpent) {
        final Recorded was = attempts.get(attemptId);
        // A finished attempt is not parked back into life: it holds no shape and asks nothing.
        if (was != null && OPEN.contains(was.status())) {
            attempts.put(attemptId, new Recorded(was.id(), claim(was, expiryMs), status, was.decision(),
                    was.ruleUuid(), was.score(), was.tokensSpent() + tokensSpent, was.createTimeMs(),
                    System.currentTimeMillis(), List.of()));
        }
    }

    @Override
    public synchronized boolean claimed(final long attemptId, final String node, final long nowMs,
                                        final long expiryMs) {
        final Recorded was = attempts.get(attemptId);
        if (was == null || !OPEN.contains(was.status())) {
            return false;
        }
        // A parked attempt is nobody's to carry; a running one is its own node's until it lapses.
        if (was.status() == AttemptStatus.IN_PROGRESS
            && !node.equals(was.attempt().node())
            && was.attempt().expiryMs() > nowMs) {
            return false;
        }
        final Attempt current = was.attempt();
        final Attempt taken = new Attempt(current.docUuid(), current.shape(), current.feed(), current.type(),
                current.inputId(), node, current.executionMode(), current.promotionMode(), expiryMs);
        attempts.put(attemptId, new Recorded(was.id(), taken, AttemptStatus.IN_PROGRESS, was.decision(),
                was.ruleUuid(), was.score(), was.tokensSpent(), was.createTimeMs(),
                System.currentTimeMillis(), List.of()));
        return true;
    }

    @Override
    public synchronized void heartbeat(final long attemptId, final long expiryMs) {
        final Recorded was = attempts.get(attemptId);
        if (was != null && OPEN.contains(was.status())) {
            attempts.put(attemptId, new Recorded(was.id(), claim(was, expiryMs), was.status(), was.decision(),
                    was.ruleUuid(), was.score(), was.tokensSpent(), was.createTimeMs(),
                    System.currentTimeMillis(), List.of()));
        }
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
            // A claim that has ended holds nothing (A45), and frees the shape for the next attempt; what it
            // spent is added to what it had spent before it was parked.
            attempts.put(attemptId, new Recorded(was.id(), claim(was, 0L), status, decision, ruleUuid, score,
                    was.tokensSpent() + tokensSpent, was.createTimeMs(), System.currentTimeMillis(),
                    List.of()));
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
                        0L));
    }

    @Override
    public synchronized void amended(final long attemptId, final int turnNumber, final String answer,
                                     final String answeredBy) {
        final List<Turn> written = turns.computeIfAbsent(attemptId, key -> new ArrayList<>());
        // Found before anything is destroyed: a turn number that names nothing must leave the transcript
        // as it was, as it does in the table, where the two writes are one transaction.
        final int at = indexOf(written, turnNumber);
        if (at < 0) {
            throw new IllegalArgumentException("Attempt " + attemptId + " has no turn " + turnNumber);
        }
        final Turn was = written.get(at);
        written.set(at, new Turn(was.number(), was.stepId(), was.candidate(), was.kind(), was.question(),
                answer, answeredBy, null));
        // What came after this turn is a consequence of the answer that has changed, and the walk will
        // derive it again.
        written.removeIf(turn -> turn.number() > turnNumber);
    }

    private static int indexOf(final List<Turn> written, final int turnNumber) {
        for (int i = 0; i < written.size(); i++) {
            if (written.get(i).number() == turnNumber) {
                return i;
            }
        }
        return -1;
    }

    /// One open attempt per shape is what one learner means (A45), whether it is opened for the first
    /// time or opened again.
    @Override
    public synchronized boolean reopened(final long attemptId, final long nowMs, final long expiryMs) {
        final Recorded was = attempts.get(attemptId);
        if (was == null) {
            return false;
        }
        // A node is walking it at this moment: its answers are that walk's to give.
        if (was.status() == AttemptStatus.IN_PROGRESS && was.attempt().expiryMs() > nowMs) {
            return false;
        }
        // Whatever else holds this shape and has lapsed holds nothing (A45), or an attempt whose node
        // died would block the shape from ever being run again.
        lapsed(was.attempt().docUuid(), was.attempt().shape(), attemptId, nowMs);
        if (open(was.attempt().docUuid(), was.attempt().shape(), nowMs)
                .filter(other -> other.id() != attemptId)
                .isPresent()) {
            return false;
        }
        attempts.put(attemptId, new Recorded(was.id(), claim(was, expiryMs), AttemptStatus.AWAITING_MODEL,
                null, null, null, was.tokensSpent(), was.createTimeMs(), System.currentTimeMillis(),
                List.of()));
        return true;
    }

    /// Every other attempt on this shape whose claim has passed, abandoned: the same release that opening
    /// an attempt performs (A45).
    private void lapsed(final String docUuid, final String shape, final long except, final long nowMs) {
        attempts.values().stream()
                .filter(attempt -> attempt.id() != except
                                   && attempt.attempt().docUuid().equals(docUuid)
                                   && attempt.attempt().shape().equals(shape)
                                   && OPEN.contains(attempt.status())
                                   && attempt.attempt().expiryMs() <= nowMs)
                .toList()
                .forEach(attempt -> closed(attempt.id(), AttemptStatus.ABANDONED,
                        "Lapsed: the node learning it stopped", null, null, 0L));
    }

    @Override
    public synchronized List<Recorded> awaiting(final int limit) {
        return attempts.values().stream()
                .filter(attempt -> attempt.status() == AttemptStatus.AWAITING_MODEL)
                .sorted(Comparator.comparingLong(Recorded::id))
                .limit(limit)
                .map(this::withTurns)
                .toList();
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

    /// The same attempt with its claim expiring at a given time: the record is immutable, so a claim
    /// taken, extended or ended is a new one.
    private static Attempt claim(final Recorded was, final long expiryMs) {
        final Attempt attempt = was.attempt();
        return new Attempt(attempt.docUuid(), attempt.shape(), attempt.feed(), attempt.type(),
                attempt.inputId(), attempt.node(), attempt.executionMode(), attempt.promotionMode(), expiryMs);
    }

    private Recorded withTurns(final Recorded attempt) {
        return new Recorded(attempt.id(), attempt.attempt(), attempt.status(), attempt.decision(),
                attempt.ruleUuid(), attempt.score(), attempt.tokensSpent(), attempt.createTimeMs(),
                attempt.updateTimeMs(), List.copyOf(turns.getOrDefault(attempt.id(), List.of())));
    }
}
