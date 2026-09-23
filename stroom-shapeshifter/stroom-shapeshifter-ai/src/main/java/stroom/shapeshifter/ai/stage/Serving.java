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

import stroom.shapeshifter.shared.ServingRule;

import java.util.Collection;
import java.util.List;

/// What is serving, as a person browses it (ruling A46, design 01 §11.6): the rules of [Rules] with what
/// [Shapes] has been scoring for each and what [Guidance] has been told about it.
///
/// A seam of its own rather than a method on [Rules], because it is none of the three tables and all of
/// them. The node answers it with one query that joins them; the in-memory sibling holds the three maps
/// and does the same work in Java. Putting it on [Rules] would have made the rule store depend on shape
/// state it otherwise knows nothing about, and putting it above the seams would have meant reading every
/// rule of a document into a list before paging it — which is the thing a document with ten thousand
/// shapes cannot afford.
///
/// Read-only, and off the hot path: nothing routing a stream comes here.
public interface Serving {

    /// The serving rules of these documents, ordered by the traffic each carries — most first.
    ///
    /// A46's list is not an alert and not a queue. A rule at 0.93 is above every threshold and nothing is
    /// wrong with it; the question "could this be better?" is one somebody asks when they have an hour,
    /// and the rule worth that hour is the one carrying the most streams. So traffic orders it and the
    /// score only filters it.
    ///
    /// @param docUuids The documents the person may read. Empty answers empty rather than everything:
    ///                 the filtering belongs in the query, or a total would count rows they may not see
    ///                 and their pages would come back short.
    /// @param below    Show only rules whose shape has been scoring below this, or null for all of them.
    ///                 A shape with no rolling score yet is not below anything and is left out when a
    ///                 threshold is given: there is nothing to say it is imperfect.
    /// @param offset   The first row of the page.
    /// @param limit    How many rows.
    Page rules(Collection<String> docUuids, Double below, long offset, int limit);


    // --------------------------------------------------------------------------------


    /// One page of serving rules, and how many there are in all so that a pager can say how far there is
    /// to go.
    record Page(List<ServingRule> rules, long total) {

    }
}
