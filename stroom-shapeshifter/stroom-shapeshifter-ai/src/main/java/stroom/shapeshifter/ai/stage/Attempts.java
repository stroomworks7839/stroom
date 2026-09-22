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

import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.StepOutcome;

import java.util.List;
import java.util.Optional;

/// Every attempt at learning a shape, as a record that outlives the node that made it (A28): its turns,
/// what each was answered with and by whom, and what the attempt came to. The same record whichever mode
/// produced it — an automatic attempt differs from a deferred or reviewed one only in who answered and
/// when — so that one view can show them all, a person can see what was said, and a resumed attempt
/// (A45) can pick up where it stopped.
///
/// This is the record; the resuming is slice 25's.
public interface Attempts {

    /// Open an attempt for a shape. The row is the claim on the shape (A45): one open attempt per
    /// `(doc, shape)` is what one learner means, and an attempt parked awaiting the model or a person is
    /// still learning.
    ///
    /// @param nowMs What the caller's clock says now, against which a claim's expiry is judged: the two
    ///              must come from one clock, or a stage whose clock is fixed — a test's — opens attempts
    ///              that have lapsed on arrival and claims nothing, as the lease of A42 once did.
    /// @return The attempt's id, or empty where another is open for the shape and has not lapsed — the
    /// caller has not taken the shape and must not learn it.
    Optional<Long> opened(Attempt attempt, long nowMs);

    /// The attempt open for a shape, if one is: what a node meeting the shape finds instead of taking it,
    /// and what the worker picks up.
    Optional<Recorded> open(String docUuid, String shape, long nowMs);

    /// Park an attempt until whoever will answer its next question does (A28): it keeps its claim on the
    /// shape, since it is still learning, and its expiry is pushed out by the same heartbeat. What it has
    /// spent so far is added to what it had spent before, since a resumed attempt's cost is the whole of
    /// it and not its last leg.
    void parked(long attemptId, AttemptStatus status, long expiryMs, long tokensSpent);

    /// Take up an attempt that stopped, as this node, and hold it while it runs (A45). An attempt parked
    /// awaiting an answer is nobody's to carry — no thread is behind it — so whichever worker reaches it
    /// first may take it; one that is running is its own node's until its claim lapses, which is how a
    /// node that died mid-attempt lets the next one in.
    ///
    /// @return Whether this node may carry it on: false where it has finished, and false where it is
    /// running on another node that has not lapsed. An attempt already running on *this* node is taken
    /// again, since that is what a node re-entering its own attempt does; what keeps two of this node's
    /// own threads off one attempt is that the job cannot overlap itself, not this method.
    boolean claimed(long attemptId, String node, long nowMs, long expiryMs);

    /// The attempts waiting for the model, oldest first: what deferred mode's worker advances (A5, A28).
    /// Whether a parked attempt's claim has lapsed does not matter here — nobody is carrying it, so there
    /// is nothing to take it from, and a worker that was down while it lapsed must still pick it up or
    /// the shape waits for a stream that may never come. A parked attempt that *has* been taken over is
    /// no longer awaiting: opening the attempt that took it closed this one.
    List<Recorded> awaiting(int limit);

    /// Push a running attempt's claim out, as the dialogue asks each question (A45): the heartbeat that
    /// keeps a slow model call from costing a node the shape it is learning.
    void heartbeat(long attemptId, long expiryMs);

    /// Write a turn of an attempt, by its number: as it is answered, and again when it is judged. An
    /// attempt still running — or one whose node died — then shows what it had got to, which is the
    /// transcript a person most needs.
    void turn(long attemptId, Turn turn);

    /// What an attempt came to, and when it stopped. The tokens are added to what it had spent before it
    /// was last parked, so a resumed attempt records the whole of its cost.
    void closed(long attemptId, AttemptStatus status, String decision, String ruleUuid, Double score,
                long tokensSpent);

    /// What a person decided about the draft an attempt wrote (A25, A28): the attempt that is awaiting
    /// review for this rule is closed with their decision, so that an approved draft stops reading as
    /// though it were still waiting.
    void decided(String docUuid, String ruleUuid, AttemptStatus status, String decision);

    /// One attempt, whole, with its turns in order.
    Optional<Recorded> byId(long attemptId);

    /// The attempts of a document, newest first, for the Supervisor view of A28.
    List<Recorded> forDocument(String docUuid, int limit);


    // --------------------------------------------------------------------------------


    /// @param shape    The shape being learned, as the learning key writes it.
    /// @param inputId  The stream that raised the attempt, so a person can see what it was learning from.
    /// @param node     Which node opened it.
    /// @param expiryMs When the claim lapses if nothing heartbeats it (A45).
    record Attempt(String docUuid,
                   String shape,
                   String feed,
                   String type,
                   Long inputId,
                   String node,
                   ExecutionMode executionMode,
                   PromotionMode promotionMode,
                   long expiryMs) {

    }

    /// @param answeredBy The model a document names, or the person who answered instead (A28).
    /// @param outcome    What the answer scored, once it was judged; null while it is being asked.
    record Turn(int number,
                String stepId,
                int candidate,
                QuestionKind kind,
                String question,
                String answer,
                String answeredBy,
                StepOutcome outcome) {

    }

    /// An attempt as it is read back: what it was, what it came to, and every turn of it.
    record Recorded(long id,
                    Attempt attempt,
                    AttemptStatus status,
                    String decision,
                    String ruleUuid,
                    Double score,
                    long tokensSpent,
                    long createTimeMs,
                    long updateTimeMs,
                    List<Turn> turns) {

    }
}
