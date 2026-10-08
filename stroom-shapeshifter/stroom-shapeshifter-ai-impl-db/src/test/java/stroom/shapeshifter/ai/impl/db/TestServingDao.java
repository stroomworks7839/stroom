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

package stroom.shapeshifter.ai.impl.db;

import stroom.docref.DocRef;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.ai.state.InMemoryDeletedPipelines;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.ai.state.InMemoryLearnerBindings;
import stroom.shapeshifter.ai.state.InMemoryLearnerStates;
import stroom.shapeshifter.ai.state.InMemoryServing;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.ServingBinding;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// The serving view of A46: the bindings, the state of the learner each serves, and what a supervisor has
/// said to it — one page, ordered by the traffic each binding carries.
class TestServingDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String OTHER_DOC = "other-" + System.nanoTime();
    /// Three pipelines under the document, and so three learners.
    private static final Learner BUSY = learner(DOC, "busy");
    private static final Learner QUIET = learner(DOC, "quiet");
    private static final Learner NEW = learner(DOC, "new");
    private static final Learner THEIRS = learner(OTHER_DOC, "theirs");
    private static final int MEMORY = 10;
    /// The binding uuids these tests write. A uuid names a binding across every learner, and the database
    /// outlives a run, so each is cleared by its uuid before a test writes it again.
    private static final List<String> BINDINGS = List.of("busy", "quiet", "new", "a-draft", "theirs",
            "a-busy", "b-quiet", "c-new");

    @Inject
    private LearnerBindingsDao learnerBindings;
    @Inject
    private LearnerStatesDao states;
    @Inject
    private GuidanceDao guidance;
    @Inject
    private ServingDao serving;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        BINDINGS.forEach(uuid -> learnerBindings.byUuid(uuid).ifPresent(held -> learnerBindings.remove(held.learner(),
                uuid)));
        List.of(BUSY, QUIET, NEW).forEach(learner -> clear(states, guidance, learner, DOC));
        clear(states, guidance, THEIRS, OTHER_DOC);
    }

    /// Ordered by traffic, because A46's question — could this be better? — is worth asking first about
    /// whatever carries the most streams.
    @Test
    void whatIsServingComesBackBusiestFirst() {
        bound(learnerBindings, BUSY, DOC, "busy");
        bound(learnerBindings, QUIET, DOC, "quiet");
        bound(learnerBindings, NEW, DOC, "new");
        scored(states, BUSY, DOC, 0.93, 8);
        scored(states, QUIET, DOC, 0.71, 2);

        final Serving.Page page = serving.bindings(List.of(DOC), null, false, 0L, 100);

        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.bindings())
                .extracting(ServingBinding::getLearner, ServingBinding::getRecords, ServingBinding::getRollingScore)
                .describedAs("busiest first, and a binding that has served nothing yet is still shown — a "
                             + "binding made a minute ago is the one most worth a look")
                .containsExactly(
                        tuple(ref(BUSY), 8, 0.93),
                        tuple(ref(QUIET), 2, 0.71),
                        tuple(ref(NEW), 0, null));
        assertThat(page.bindings().get(0).getDoc().getUuid()).isEqualTo(DOC);
        assertThat(page.bindings().get(0).getFragment().getName()).isEqualTo("busy-fragment");
    }

    /// A46's filter: "good but not perfect" is the only thing worth narrowing by, and a learner with no
    /// score is not below anything.
    @Test
    void aThresholdLeavesOutWhatIsGoodEnoughAndWhatHasNoScoreAtAll() {
        bound(learnerBindings, BUSY, DOC, "busy");
        bound(learnerBindings, QUIET, DOC, "quiet");
        bound(learnerBindings, NEW, DOC, "new");
        scored(states, BUSY, DOC, 0.93, 8);
        scored(states, QUIET, DOC, 0.71, 2);

        final Serving.Page page = serving.bindings(List.of(DOC), 0.9, false, 0L, 100);

        assertThat(page.bindings()).extracting(ServingBinding::getLearner)
                .describedAs("the one scoring below it; not the one above, and not the one with nothing "
                             + "to say it is imperfect")
                .containsExactly(ref(QUIET));
        assertThat(page.total())
                .describedAs("and the total counts what the filter left, or a pager would promise pages "
                             + "that are not there")
                .isEqualTo(1L);
    }

    /// A draft is not a serving binding: it is decided rather than improved.
    @Test
    void aDraftIsNotAServingBinding() {
        bound(learnerBindings, BUSY, DOC, "busy");
        learnerBindings.append(BUSY, DOC, Binding.builder()
                .uuid("a-draft")
                .pipeline(new DocRef("Pipeline", "draft-fragment", "draft"))
                .draft(true)
                .build());

        assertThat(serving.bindings(List.of(DOC), null, false, 0L, 100).bindings())
                .extracting(ServingBinding::getBindingUuid)
                .describedAs("a draft is decided rather than improved")
                .containsExactly("busy");
    }

    /// Each row says how much has been said to its learner, so that a person can see whether the last
    /// hint was acted on before giving another.
    @Test
    void aRowSaysHowMuchHasBeenSaidToItsLearner() {
        bound(learnerBindings, BUSY, DOC, "busy");
        bound(learnerBindings, QUIET, DOC, "quiet");
        guidance.given(BUSY, DOC, "Timestamps are local, not UTC.", "jo");
        guidance.given(BUSY, DOC, "Column four is a terminal id.", "sam");
        guidance.given(THEIRS, OTHER_DOC, "Another document's learner.", "jo");

        assertThat(serving.bindings(List.of(DOC), null, false, 0L, 100).bindings())
                .extracting(ServingBinding::getLearner, ServingBinding::getGuidance)
                .describedAs("counted per learner")
                .containsExactlyInAnyOrder(tuple(ref(BUSY), 2), tuple(ref(QUIET), 0));
    }

    /// The documents a person may read go into the query, as they do for the attempts: a total taken
    /// before the filtering would count bindings they may not see.
    @Test
    void aDocumentNobodyNamedIsNotInTheAnswer() {
        bound(learnerBindings, BUSY, DOC, "busy");
        bound(learnerBindings, THEIRS, OTHER_DOC, "theirs");

        assertThat(serving.bindings(List.of(DOC), null, false, 0L, 100).bindings())
                .extracting(ServingBinding::getBindingUuid).containsExactly("busy");
        assertThat(serving.bindings(List.of(), null, false, 0L, 100).bindings())
                .describedAs("and naming no documents answers nothing rather than everything")
                .isEmpty();
        assertThat(serving.bindings(List.of(DOC, OTHER_DOC), null, false, 0L, 100).bindings()).hasSize(2);
    }

    /// The table and the heap answer the same question the same way.
    ///
    /// Two implementations exist — the join a node runs and the three maps the scenarios run on — and a
    /// view written against one and served by the other is a view that lies. The order and the paging
    /// are what would drift silently, so they are what is held: the in-memory one sorts in Java and the
    /// node sorts in SQL, and a tiebreaker either one lacked would make this a coin flip.
    @Test
    void theViewInMemoryAgreesWithTheViewInRows() {
        final InMemoryLearnerBindings memoryBindings = new InMemoryLearnerBindings();
        final InMemoryLearnerStates memoryStates = new InMemoryLearnerStates();
        final InMemoryGuidance memoryGuidance = new InMemoryGuidance();
        final Serving inMemory = new InMemoryServing(memoryBindings, memoryStates, memoryGuidance,
                new InMemoryDeletedPipelines());
        final List<LearnerBindings> bothBindings = List.of(learnerBindings, memoryBindings);
        final List<LearnerStates> bothStates = List.of(states, memoryStates);
        final List<Guidance> bothGuidance = List.of(guidance, memoryGuidance);
        bothBindings.forEach(seam -> {
            bound(seam, BUSY, DOC, "a-busy");
            bound(seam, QUIET, DOC, "b-quiet");
            bound(seam, NEW, DOC, "c-new");
        });
        bothStates.forEach(seam -> {
            scored(seam, BUSY, DOC, 0.93, 8);
            scored(seam, QUIET, DOC, 0.71, 2);
        });
        bothGuidance.forEach(seam -> seam.given(BUSY, DOC, "Timestamps are local.", "jo"));

        assertThat(summarise(inMemory.bindings(List.of(DOC), null, false, 0L, 100).bindings()))
                .describedAs("the same bindings, the same scores, the same counts, in the same order")
                .isEqualTo(summarise(serving.bindings(List.of(DOC), null, false, 0L, 100).bindings()));
        assertThat(summarise(inMemory.bindings(List.of(DOC), null, false, 1L, 1).bindings()))
                .describedAs("and the same page of them")
                .isEqualTo(summarise(serving.bindings(List.of(DOC), null, false, 1L, 1).bindings()));
        assertThat(inMemory.bindings(List.of(DOC), 0.9, false, 0L, 100).total())
                .describedAs("and the same total under a threshold")
                .isEqualTo(serving.bindings(List.of(DOC), 0.9, false, 0L, 100).total());
        assertThat(summarise(serving.bindings(List.of(DOC), null, false, 1L, 1).bindings()))
                .containsExactly("b-quiet " + ref(QUIET) + " x2 @0.71 said 0");
    }

    private static List<String> summarise(final List<ServingBinding> bindings) {
        return bindings.stream()
                .map(binding -> binding.getBindingUuid() + " " + binding.getLearner() + " x" + binding.getRecords()
                             + " @" + binding.getRollingScore() + " said " + binding.getGuidance())
                .toList();
    }

    private static Binding bound(final LearnerBindings learnerBindings,
                                     final Learner learner,
                                     final String docUuid,
                                     final String bindingUuid) {
        return learnerBindings.append(learner, docUuid, Binding.builder()
                .uuid(bindingUuid)
                .pipeline(new DocRef("Pipeline", bindingUuid + "-uuid", bindingUuid + "-fragment"))
                .promotedTimeMs(1_700_000_000_000L)
                .score(0.95)
                .build());
    }

    /// A learner scored over enough records to have a rolling score of exactly what was asked for: the
    /// mean of one stream's score is that score, whatever the memory.
    private static void scored(final LearnerStates states,
                               final Learner learner,
                               final String docUuid,
                               final double score,
                               final int records) {
        states.scored(learner, docUuid, score, records, MEMORY);
    }

    private static void clear(final LearnerStates states,
                              final Guidance guidance,
                              final Learner learner,
                              final String docUuid) {
        states.reset(learner);
        guidance.standing(learner).forEach(given -> guidance.withdraw(docUuid, given.id()));
    }

    /// A learner of one of a document's pipelines in these tests.
    private static Learner learner(final String docUuid, final String name) {
        return new Learner("pipeline-" + docUuid + "-" + name, "shapeshifterAi");
    }

    private static LearnerRef ref(final Learner learner) {
        return new LearnerRef(learner.pipelineUuid(), learner.elementId());
    }
}
