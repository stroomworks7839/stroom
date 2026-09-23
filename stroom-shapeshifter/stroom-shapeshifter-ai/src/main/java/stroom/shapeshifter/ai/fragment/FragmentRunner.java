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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.pipeline.xml.event.EventList;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.shared.RecordBoundary;

import java.util.List;
import java.util.Optional;

/// Runs a written fragment over an input and says what each of its elements made of it: what the
/// promotion gate judges (§7.4), and what a bound rule's stream goes through.
///
/// Two implementations, and the difference matters. [PipelineFragmentRunner] runs the fragment as a
/// pipeline — the real elements, the real pools, the real filters — and is what a node uses, so that
/// what is judged is what will run. [StandInFragmentRunner] walks the chain with the module's step
/// runners instead, which needs no node, no stores beyond the content ones and no pipeline scope, and is
/// what the Tier 1 scenarios run on (design 02 §2).
///
/// They are meant to agree, and where they do not the pipeline is right. A Tier 2 scenario holds them to
/// the same events on the same fragment and input, because a stand-in that has drifted from the thing it
/// stands in for is worse than no stand-in: it would pass a candidate the pipeline refuses.
public interface FragmentRunner {

    /// @param boundary What one record is in the stream (A35), as the rule carries it, for the scorers
    ///                 that count records; null where none was settled.
    /// @return One attempted step per element of the chain, in chain order. A step that produced nothing
    /// leaves the later ones unrun; the caller sees that from the last step's result.
    List<Attempted> run(DocRef fragment, String input, RecordBoundary boundary);

    default List<Attempted> run(final DocRef fragment, final String input) {
        return run(fragment, input, null);
    }

    /// The events the chain's tail emitted on the run just made, where the runner has a pipeline under
    /// it to emit them. This is how a stream's output reaches the supervisor's own downstream without
    /// the fragment being run a second time: the run that is judged is the run that is served.
    ///
    /// A runner keeps one run's events and the next run replaces them, so a caller that wants a
    /// particular run's output takes it immediately after that run. The stage does: it takes them where
    /// it builds the judgement they belong to, and carries them with it, because which run is the one
    /// being served is not known until the decision is made — a candidate that fails the floor is run
    /// and discarded, and its events must not be the ones played on.
    ///
    /// Empty from [StandInFragmentRunner], which has no pipeline and no events: Tier 1 asserts on what
    /// each step wrote and has no downstream to play anything on to.
    default Optional<EventList> lastOutput() {
        return Optional.empty();
    }
}
