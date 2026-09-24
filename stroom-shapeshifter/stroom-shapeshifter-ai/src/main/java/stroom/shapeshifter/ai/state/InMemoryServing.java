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

import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ServingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The serving view over the three in-memory seams: what a node's query does as a join, done here in
 * Java. The node reads the same answer from one statement, and an agreement test holds the two to it.
 * <p>
 * Every rule of every named document is walked, which is what makes this the in-memory one: the point of
 * the node's query is that it never does that.
 */
public final class InMemoryServing implements Serving {

    private final Rules rules;
    private final Shapes shapes;
    private final Guidance guidance;

    @Inject
    public InMemoryServing(final Rules rules, final Shapes shapes, final Guidance guidance) {
        this.rules = rules;
        this.shapes = shapes;
        this.guidance = guidance;
    }

    @Override
    public Page rules(final Collection<String> docUuids, final Double below, final long offset, final int limit) {
        if (NullSafe.isEmptyCollection(docUuids)) {
            return new Page(List.of(), 0L);
        }
        final List<ServingRule> matched = new ArrayList<>();
        for (final String docUuid : docUuids) {
            for (final RoutingRule rule : rules.forDocument(docUuid)) {
                final ServingRule serving = serving(docUuid, rule, below);
                if (serving != null) {
                    matched.add(serving);
                }
            }
        }
        // Most traffic first, and where two carry the same — which is routine, since every rule that has
        // served nothing carries none — the rule's own uuid, so that the list does not reshuffle between
        // one page and the next. The same tiebreaker as the node's query, and for the same reason.
        matched.sort(Comparator.comparingInt(ServingRule::getRecords).reversed()
                .thenComparing(ServingRule::getRuleUuid));
        final int from = (int) Math.min(Math.max(offset, 0L), matched.size());
        final int to = Math.min(from + Math.max(limit, 0), matched.size());
        return new Page(List.copyOf(matched.subList(from, to)), matched.size());
    }

    /// One rule as the view shows it, or null where it is not one the view shows: a draft is decided
    /// rather than improved (A25), a reserved rule binds nothing, and a rule an operator wrote by hand
    /// came from no shape and has nothing to learn again.
    private ServingRule serving(final String docUuid, final RoutingRule rule, final Double below) {
        if (rule.isDraft() || rule.isReserved() || NullSafe.isBlankString(rule.getShapeId())) {
            return null;
        }
        final Optional<Shapes.Rolling> rolling = shapes.rolling(docUuid, rule.getShapeId());
        if (below != null && (rolling.isEmpty() || rolling.get().score() >= below)) {
            return null;
        }
        return new ServingRule(
                ShapeshifterAiDoc.buildDocRef().uuid(docUuid).build(),
                rule.getUuid(),
                rule.getShapeId(),
                rule.getPipeline(),
                rule.isPinned(),
                rule.isProvisional(),
                rule.getScore(),
                rule.getPromotedTimeMs(),
                rolling.map(Shapes.Rolling::score).orElse(null),
                rolling.map(Shapes.Rolling::records).orElse(0),
                guidance.standing(docUuid, rule.getShapeId()).size());
    }
}
