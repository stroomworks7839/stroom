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


package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.security.mock.MockSecurityContext;
import stroom.shapeshifter.ai.element.LearnerDocuments;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.state.InMemoryDeletedPipelines;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.ai.state.InMemoryRules;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.RoutingRule;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A Supervisor request names a learner and a document separately, and the permission it is held to is
/// the document's (design 06 phase J slice 2b). So the learner must be one that runs under that
/// document: otherwise a person who may edit one document could tell, read or relearn another's learner
/// by naming it.
class TestSupervisorResourceImpl {

    private static final LearnerRef OURS = new LearnerRef("pipeline-1", "shapeshifterAi");
    private static final LearnerRef THEIRS = new LearnerRef("pipeline-2", "shapeshifterAi");
    private static final LearnerRef GONE = new LearnerRef("pipeline-3", "shapeshifterAi");

    private final InMemoryGuidance guidance = new InMemoryGuidance();
    private final InMemoryDeletedPipelines deletedPipelines = new InMemoryDeletedPipelines();
    private final InMemoryRules rules = new InMemoryRules();
    private final SupervisorResourceImpl resource = resource();

    @Test
    void guidanceIsGivenToALearnerOfTheDocument() {
        assertThat(resource.hint("doc-1", new GuidanceRequest(OURS, "Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
    }

    @Test
    void aLearnerOfAnotherDocumentIsRefused() {
        final long theirs = guidance.given(learner(THEIRS), "doc-2", "Said under doc-2.", "jo");

        assertThatThrownBy(() -> resource.hint("doc-1", new GuidanceRequest(THEIRS, "Planted.")))
                .describedAs("told something")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not run under this document");
        assertThatThrownBy(() -> resource.guidance("doc-1", THEIRS.getPipelineUuid(),
                THEIRS.getElementId()))
                .describedAs("read")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resource.withdraw("doc-1", theirs, THEIRS.getPipelineUuid(),
                THEIRS.getElementId()))
                .describedAs("or read by way of withdrawing")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(guidance.standing(learner(THEIRS)))
                .describedAs("and nothing planted on it")
                .extracting(Guidance.Given::message)
                .containsExactly("Said under doc-2.");
    }

    /// A deleted pipeline's learning is kept to be read until it ages out, and nothing more is done with it
    /// (design 06 A137): what was said to its learner can still be read from the document its bindings
    /// were kept under, though the pipeline names no document now, and nothing is said to it, taken back,
    /// improved, accepted or retracted.
    @Test
    void aDeletedPipelinesLearnerIsReadAndNotChanged() {
        final Learner gone = learner(GONE);
        rules.append(gone, "doc-1", RoutingRule.builder()
                .uuid("rule-gone")
                .pipeline(new DocRef("Pipeline", "fragment-gone", "DOOR-ACCESS learned"))
                .build());
        final long said = guidance.given(gone, "doc-1", "Times are local.", "jo");
        deletedPipelines.deleted(GONE.getPipelineUuid(), 1_000L);

        assertThat(resource.guidance("doc-1", GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("read, from the document it learned for")
                .singleElement()
                .satisfies(given -> assertThat(given.getMessage()).isEqualTo("Times are local."));
        assertThatThrownBy(() -> resource.guidance("doc-2", GONE.getPipelineUuid(), GONE.getElementId()))
                .describedAs("but not from another")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resource.hint("doc-1", new GuidanceRequest(GONE, "And the year.")))
                .describedAs("told nothing")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has been deleted");
        assertThatThrownBy(() -> resource.withdraw("doc-1", said, GONE.getPipelineUuid(),
                GONE.getElementId()))
                .describedAs("nothing taken back")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.improve("doc-1", "rule-gone", new ImproveRequest(null)))
                .describedAs("its rule not improved")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.accept("doc-1", "rule-gone"))
                .describedAs("accepted")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resource.retract("doc-1", "rule-gone", new RejectRequest("Gone.")))
                .describedAs("or retracted")
                .isInstanceOf(IllegalStateException.class);
        assertThat(guidance.standing(gone)).hasSize(1);
    }

    private SupervisorResourceImpl resource() {
        final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
        Mockito.when(documents.documentOf(learner(OURS))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(THEIRS))).thenReturn(Optional.of("doc-2"));
        // A deleted pipeline names no document.
        Mockito.when(documents.documentOf(learner(GONE))).thenReturn(Optional.empty());
        return new SupervisorResourceImpl(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                MockSecurityContext::new,
                () -> null,
                () -> guidance,
                () -> null,
                () -> documents,
                () -> deletedPipelines,
                () -> rules);
    }

    private static Learner learner(final LearnerRef ref) {
        return new Learner(ref.getPipelineUuid(), ref.getElementId());
    }
}
