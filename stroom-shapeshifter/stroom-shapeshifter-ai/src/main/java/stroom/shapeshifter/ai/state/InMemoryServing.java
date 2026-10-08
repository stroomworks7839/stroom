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

import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.LearnerBindings.Held;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.ServingBinding;
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
 * Every binding of every learner is walked, which is what makes this the in-memory one: the point of
 * the node's query is that it never does that.
 */
public final class InMemoryServing implements Serving {

    private final InMemoryLearnerBindings learnerBindings;
    private final LearnerStates states;
    private final Guidance guidance;
    private final DeletedPipelines deletedPipelines;

    @Inject
    public InMemoryServing(final InMemoryLearnerBindings learnerBindings,
                           final LearnerStates states,
                           final Guidance guidance,
                           final DeletedPipelines deletedPipelines) {
        this.learnerBindings = learnerBindings;
        this.states = states;
        this.guidance = guidance;
        this.deletedPipelines = deletedPipelines;
    }

    @Override
    public Page bindings(final Collection<String> docUuids,
                      final Double below,
                      final boolean showDeleted,
                      final long offset,
                      final int limit) {
        if (NullSafe.isEmptyCollection(docUuids)) {
            return new Page(List.of(), 0L);
        }
        final List<ServingBinding> matched = new ArrayList<>();
        for (final Held held : learnerBindings.all()) {
            final boolean deleted = deletedPipelines.isDeleted(held.learner().pipelineUuid());
            if (docUuids.contains(held.docUuid()) && (showDeleted || !deleted)) {
                final ServingBinding serving = serving(held, below, deleted);
                if (serving != null) {
                    matched.add(serving);
                }
            }
        }
        // Most traffic first, and where two carry the same — which is routine, since every binding that has
        // served nothing carries none — the binding's own uuid, so that the list does not reshuffle between
        // one page and the next. The same tiebreaker as the node's query, and for the same reason.
        matched.sort(Comparator.comparingInt(ServingBinding::getRecords).reversed()
                .thenComparing(ServingBinding::getBindingUuid));
        final int from = (int) Math.min(Math.max(offset, 0L), matched.size());
        final int to = Math.min(from + Math.max(limit, 0), matched.size());
        return new Page(List.copyOf(matched.subList(from, to)), matched.size());
    }

    /// One binding as the view shows it, or null where it is not one the view shows: a draft is decided
    /// rather than improved (A25).
    private ServingBinding serving(final Held held, final Double below, final boolean deleted) {
        final Binding binding = held.binding();
        if (binding.isDraft()) {
            return null;
        }
        final Optional<LearnerStates.Rolling> rolling = states.rolling(held.learner());
        if (below != null && (rolling.isEmpty() || rolling.get().score() >= below)) {
            return null;
        }
        return new ServingBinding(
                ShapeshifterAiDoc.buildDocRef().uuid(held.docUuid()).build(),
                new LearnerRef(held.learner().pipelineUuid(), held.learner().elementId()),
                binding.getUuid(),
                binding.getPipeline(),
                binding.isProvisional(),
                binding.getScore(),
                binding.getPromotedTimeMs(),
                rolling.map(LearnerStates.Rolling::score).orElse(null),
                rolling.map(LearnerStates.Rolling::records).orElse(0),
                guidance.standing(held.learner()).size(),
                // Whether it is below its document's threshold is the resource's to say, which has the document.
                false,
                deleted);
    }
}
