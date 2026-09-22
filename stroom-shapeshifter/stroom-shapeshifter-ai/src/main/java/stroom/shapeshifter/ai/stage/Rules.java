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

import stroom.shapeshifter.shared.RoutingRule;

import java.util.List;
import java.util.Optional;

/// The routing rules of a document (A41): rows, not a field on the document, so that two nodes promoting
/// two shapes of one document write two rows rather than racing to rewrite one document, and so that a
/// document with ten thousand shapes is a table. The document holds only what a person authors.
///
/// Order matters: the router takes the first rule whose selector matches, so an operator's rule above the
/// learned ones keeps its precedence (design 02 §4). A rule is appended at the end and moved by an
/// operator; the implementation keeps the order it was given.
///
/// Every method names the document, since one implementation serves them all. The serving path reads
/// through a cache; these are the writes and the cache's source (design 01 §11.4).
public interface Rules {

    /// The document's rules, in the order the router should try them.
    List<RoutingRule> forDocument(String docUuid);

    /// The rule with this uuid, if the document has it.
    Optional<RoutingRule> byUuid(String docUuid, String ruleUuid);

    /// Append a rule to the end of the document's table, giving it a uuid where it has none.
    ///
    /// @return The rule as stored, with its uuid.
    RoutingRule append(String docUuid, RoutingRule rule);

    /// Replace the rule of the same uuid, keeping its position; a rule the document does not have is
    /// appended, since a promotion must not be lost because its row was pruned.
    void replace(String docUuid, RoutingRule rule);

    /// Remove a rule — a draft rejected, a learned rule an operator discards.
    void remove(String docUuid, String ruleUuid);
}
