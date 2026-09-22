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

    /// Open an attempt for a shape. The row is the claim on it once the dialogue can be resumed (A45);
    /// until then the shape's lease holds the claim and this records what happened.
    long opened(Attempt attempt);

    /// Write a turn of an attempt, by its number: as it is answered, and again when it is judged. An
    /// attempt still running — or one whose node died — then shows what it had got to, which is the
    /// transcript a person most needs.
    void turn(long attemptId, Turn turn);

    /// What an attempt came to, and when it stopped.
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
