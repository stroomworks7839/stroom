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

package stroom.shapeshifter.ai.stage;

import java.util.List;
import java.util.Optional;

/// The streams that are waiting, and on what (design 04 §3, §6): one row per stream a document has put
/// back — the stream, the task and filter that will be woken, the transform it failed against, and since
/// when. Nothing about which of its records failed and none of its text (A69): the session works that out
/// for the one stream it learns from, and for the rest it would be stale the moment the transform moved.
///
/// A row is a stream that waits: its task deferred it, and a wake or a person brings it back (design 04
/// §3, §5.3). In-memory in scenarios; a table in a node.
public interface Deferrals {

    /// A stream the stage would put back, or has. A second call for the same stream and document is the
    /// same row, with what it failed against and why brought up to date and its attempts counted; the
    /// time it first waited is kept, since a new reason does not make it newly late.
    ///
    /// @param docUuid        The document.
    /// @param inputId        The stream's meta id.
    /// @param feed           Its feed.
    /// @param type           Its type.
    /// @param pipeline       The uuid of the pipeline processing it, where one is.
    /// @param taskId         The processor task to wake, where one is.
    /// @param filterId       The processor filter that task belongs to, where one is.
    /// @param failedAgainst  What the stream was run through and fell short of: the rule's fragment and
    ///                       its version, or null where there was no rule to run it through.
    /// @param reason         Why it waits, in words.
    /// @param failingRecords How many of its records fell short of the bar; zero where there was no rule.
    void deferred(String docUuid,
                  long inputId,
                  String feed,
                  String type,
                  String pipeline,
                  Long taskId,
                  Integer filterId,
                  String failedAgainst,
                  String reason,
                  int failingRecords);

    /// The stream has been served, or errored, and waits no more.
    ///
    /// @param docUuid The document.
    /// @param inputId The stream's meta id.
    void released(String docUuid, long inputId);

    /// What is waiting for a document, oldest first.
    ///
    /// @param docUuid The document.
    /// @return Its rows.
    List<Deferral> forDocument(String docUuid);

    /// One stream's row, where it has one.
    ///
    /// @param docUuid The document.
    /// @param inputId The stream's meta id.
    /// @return Its row.
    Optional<Deferral> of(String docUuid, long inputId);

    /// What is waiting on one shape (A49: a feed and a type), oldest first: what a promotion wakes.
    ///
    /// @param docUuid The document.
    /// @param feed    The feed.
    /// @param type    The type.
    /// @return Its rows.
    List<Deferral> waiting(String docUuid, String feed, String type);

    // --------------------------------------------------------------------------------

    /// One waiting stream.
    ///
    /// @param docUuid         The document.
    /// @param inputId         The stream's meta id.
    /// @param feed            Its feed.
    /// @param type            Its type.
    /// @param pipeline        The pipeline processing it, where one is.
    /// @param taskId          The task to wake, where one is.
    /// @param filterId        That task's filter, where one is.
    /// @param failedAgainst   What it last fell short of, or null where there was no rule.
    /// @param reason          Why it waits.
    /// @param failingRecords  How many records fell short, the last time it was tried.
    /// @param firstDeferredMs When it first waited.
    /// @param attempts        How many times it has been tried and put back.
    record Deferral(String docUuid,
                    long inputId,
                    String feed,
                    String type,
                    String pipeline,
                    Long taskId,
                    Integer filterId,
                    String failedAgainst,
                    String reason,
                    int failingRecords,
                    long firstDeferredMs,
                    int attempts) {

        /// Whether the stream has waited past a document's maximum deferral age (A57, A78): raised, and
        /// still waiting (A79). Computed when read, since age is not a thing to store.
        public boolean raised(final long maxAgeMs, final long nowMs) {
            return nowMs - firstDeferredMs > maxAgeMs;
        }

    }
}
