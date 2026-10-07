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

import stroom.security.mock.MockSecurityContext;
import stroom.shapeshifter.ai.element.LearnerDocuments;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.LearnShapeRequest;
import stroom.shapeshifter.shared.LearnerRef;

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

    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";
    private static final LearnerRef OURS = new LearnerRef("pipeline-1", "shapeshifterAi");
    private static final LearnerRef THEIRS = new LearnerRef("pipeline-2", "shapeshifterAi");

    private final InMemoryGuidance guidance = new InMemoryGuidance();
    private final SupervisorResourceImpl resource = resource();

    @Test
    void guidanceIsGivenToALearnerOfTheDocument() {
        assertThat(resource.hint("doc-1", new GuidanceRequest(SHAPE, OURS, "Times are local.")))
                .singleElement()
                .satisfies(said -> assertThat(said.getMessage()).isEqualTo("Times are local."));
    }

    @Test
    void aLearnerOfAnotherDocumentIsRefused() {
        final long theirs = guidance.given(learner(THEIRS), "doc-2", SHAPE, "Said under doc-2.", "jo");

        assertThatThrownBy(() -> resource.hint("doc-1", new GuidanceRequest(SHAPE, THEIRS, "Planted.")))
                .describedAs("told something")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not run under this document");
        assertThatThrownBy(() -> resource.guidance("doc-1", THEIRS.getPipelineUuid(), THEIRS.getElementId(),
                SHAPE))
                .describedAs("read")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resource.withdraw("doc-1", theirs, THEIRS.getPipelineUuid(),
                THEIRS.getElementId(), SHAPE))
                .describedAs("or read by way of withdrawing")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resource.learn("doc-1", new LearnShapeRequest(SHAPE, THEIRS, "Again.")))
                .describedAs("or sent back to be learned")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(guidance.standing(learner(THEIRS), SHAPE))
                .describedAs("and nothing planted on it")
                .extracting(Guidance.Given::message)
                .containsExactly("Said under doc-2.");
    }

    private SupervisorResourceImpl resource() {
        final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
        Mockito.when(documents.documentOf(learner(OURS))).thenReturn(Optional.of("doc-1"));
        Mockito.when(documents.documentOf(learner(THEIRS))).thenReturn(Optional.of("doc-2"));
        return new SupervisorResourceImpl(
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                MockSecurityContext::new,
                () -> null,
                () -> null,
                () -> guidance,
                () -> null,
                () -> documents);
    }

    private static Learner learner(final LearnerRef ref) {
        return new Learner(ref.getPipelineUuid(), ref.getElementId());
    }
}
