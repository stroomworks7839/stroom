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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.element.StageFactory;
import stroom.shapeshifter.ai.state.InMemoryRules;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.PermissionException;

import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules resource of A41 as design 06 phase J slice 2a leaves it: a document's rules are read, and a
 * draft is approved or rejected, but a rule is never written by hand, since nothing selects among them.
 */
class TestShapeshifterAiRules {

    /// Every case here is refused — no permission, an unknown rule, a rejection with no reason — before
    /// anything could ask for a stage. A null says so: reaching one
    /// would be a loud failure rather than a quiet pass.
    private static final Provider<StageFactory> STAGE_NEVER_REACHED = () -> null;
    private static final Provider<PipelineScopeRunnable> SCOPE_NEVER_ENTERED = () -> null;

    private static final String DOC = "doc-1";
    private static final DocRef FRAGMENT = new DocRef(PipelineDoc.TYPE, "fragment-1", "door-v1");

    private final InMemoryRules rules = new InMemoryRules();

    @Test
    void aPersonWhoMayNotSeeTheDocumentMayNotSeeWhatItLearned() {
        // A rule names the feed it binds and the fragment that runs on it: reading them is reading the
        // document.
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext() {
            @Override
            public boolean hasDocumentPermission(final DocRef docRef, final DocumentPermission permission) {
                return false;
            }
        });
        rules.append(DOC, rule("a"));

        assertThatThrownBy(() -> resource.rules(DOC)).isInstanceOf(PermissionException.class);
    }

    @Test
    void aRejectionSaysWhy() {
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext());
        rules.append(DOC, rule("draft-1").copy().draft(true).build());

        assertThatThrownBy(() -> resource.rejectRule(DOC, "draft-1", new RejectRequest("  ")))
                .describedAs("the shape is given up with the reason, and a blank is not one: the person "
                             + "who finds it given up next month has to be able to read why")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("says why");
        assertThatThrownBy(() -> resource.rejectRule(DOC, "draft-1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rules.forDocument(DOC))
                .describedAs("and the draft is still there").hasSize(1);
    }

    @Test
    void aDecisionIsOnlyForARuleOfThisDocument() {
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext());

        assertThatThrownBy(() -> resource.approveRule(DOC, "not-a-rule"))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("not a rule of this document");
        assertThatThrownBy(() -> resource.rejectRule(DOC, "not-a-rule", new RejectRequest("no good")))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("not a rule of this document");
    }

    @Test
    void aDecisionNeedsPermissionToEditTheDocument() {
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext() {
            @Override
            public boolean hasDocumentPermission(final DocRef docRef, final DocumentPermission permission) {
                return false;
            }
        });
        rules.append(DOC, rule("draft-1").copy().draft(true).build());

        assertThatThrownBy(() -> resource.approveRule(DOC, "draft-1"))
                .isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.rejectRule(DOC, "draft-1", new RejectRequest("no good")))
                .isInstanceOf(PermissionException.class);
    }

    private ShapeshifterAiResourceImpl resource(final SecurityContext securityContext) {
        final ShapeshifterAiStore store = Mockito.mock(ShapeshifterAiStore.class);
        Mockito.lenient().when(store.readDocument(Mockito.any())).thenReturn(ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .build());
        return new ShapeshifterAiResourceImpl(() -> store, () -> null, () -> rules, () -> securityContext,
                STAGE_NEVER_REACHED, SCOPE_NEVER_ENTERED);
    }

    private static RoutingRule rule(final String uuid) {
        return RoutingRule.builder().uuid(uuid).pipeline(FRAGMENT).build();
    }
}
