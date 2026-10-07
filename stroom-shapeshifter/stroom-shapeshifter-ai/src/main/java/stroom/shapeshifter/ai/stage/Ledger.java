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

import stroom.shapeshifter.shared.LedgerLearner;

import java.util.Collection;
import java.util.List;

/// The ledger of design 01 §5.2 (ruling A26): which input streams a learner sentinelled, and why, each
/// row recording the document the learner runs under. Nothing is held — the input stays in the store and
/// the ledger names it — so releasing a learner's streams is taking them off the ledger and asking for
/// them to be processed again (A12). In memory in scenarios; the `shapeshifter_ledger` table in a node.
public interface Ledger {

    /// A stream put on the ledger. A released stream is replayed through the learner's pipeline, which is
    /// the one that sentinelled it, whichever node releases it.
    ///
    /// @param learner The learner that sentinelled it.
    /// @param docUuid The document the learner runs under.
    /// @param inputId The stream's meta id.
    /// @param reason  Why.
    void sentinelled(Learner learner, String docUuid, long inputId, String reason);

    /// Take the learner's streams off the ledger.
    ///
    /// @param learner The learner.
    /// @return The streams that were on it, oldest first, each with the pipeline that sentinelled it.
    List<Replayable> release(Learner learner);

    /// Say something else about why every stream waiting on a learner is waiting, without releasing any
    /// of them (§5.2).
    ///
    /// A row's reason is written when the stream is sentinelled and is the last thing said about it,
    /// and the view shows the newest — so a decision that changes *why* a learner is unsettled, without
    /// settling it, leaves every row saying something that has stopped being true. Rejecting a draft is
    /// the case: the streams that were waiting for somebody to review it go on saying so, for a draft
    /// that no longer exists and a learner that has now given up, on the very screen a person would use
    /// to start it learning again.
    ///
    /// Nothing is released and nothing is replayed. The streams are still waiting, and still where they
    /// always were; only the reason changes.
    void restate(Learner learner, String reason);

    /// What is on the ledger, a row per learner, most recently added first — for the view of A28 §11.6,
    /// and for anybody who wants to know what a promotion would release.
    ///
    /// A **read**. It is a method of its own rather than a mode of [#release] because releasing is what
    /// a promotion does, and a surface that answered by releasing would put a backlog through the
    /// pipeline because somebody looked at it.
    ///
    /// @param docUuids Whose ledgers to read: the documents the caller may see, already narrowed to the
    ///                 one they asked about if they asked about one. They go into the query rather than
    ///                 filtering its answer, because a total taken before the filtering would say how
    ///                 many learners wait on documents the caller may not see, and the pages they turned
    ///                 would come back short.
    /// @param offset   The first row to return, counted from zero.
    /// @param limit    How many rows at most.
    Page waiting(Collection<String> docUuids, long offset, int limit);


    // --------------------------------------------------------------------------------


    /// One page of the ledger, and how many learners there are in all.
    ///
    /// @param learners The page, most recently added to first.
    /// @param total    Every learner waiting, not just this page's: what a pager counts, and what an
    ///                 operator wants to know before they start turning pages.
    record Page(List<LedgerLearner> learners, long total) {

    }
}
