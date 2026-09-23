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
import stroom.pipeline.factory.ElementRegistry;
import stroom.pipeline.factory.ElementRegistryFactory;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.security.api.SecurityContext;
import stroom.security.mock.MockSecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.fragment.FragmentCheck;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.state.InMemoryRules;
import stroom.shapeshifter.shared.ReplayUnit;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules resource of A41: the Routing tab's one call per action, each answered with the table as the
 * server now holds it. Where a rule sits is what decides whose rule wins — the router takes the first
 * match — so position is the thing these tests watch.
 */
class TestShapeshifterAiRules {

    private static final String DOC = "doc-1";
    private static final DocRef FRAGMENT = new DocRef(PipelineDoc.TYPE, "fragment-1", "door-v1");

    private final InMemoryRules rules = new InMemoryRules();

    @Test
    void anOperatorsRuleGoesWhereTheyPutItAndTheLearnedOnesShuffleDown() {
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext());
        rules.append(DOC, rule("learned-1"));
        rules.append(DOC, rule("learned-2"));

        final List<RoutingRule> after = resource.addRule(DOC, 0, rule(null));

        assertThat(after).hasSize(3);
        assertThat(after.get(0).getUuid()).describedAs("given a uuid by the store").isNotNull();
        assertThat(after).extracting(RoutingRule::getUuid).element(1).isEqualTo("learned-1");
        assertThat(after).extracting(RoutingRule::getUuid).element(2).isEqualTo("learned-2");
        // No position named: at the end, behind everything.
        assertThat(resource.addRule(DOC, null, rule("last"))).extracting(RoutingRule::getUuid)
                .element(3).isEqualTo("last");
    }

    @Test
    void aRuleIsMovedEditedAndRemovedByItself() {
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext());
        rules.append(DOC, rule("a"));
        rules.append(DOC, rule("b"));
        rules.append(DOC, rule("c"));

        assertThat(resource.moveRule(DOC, "c", 0)).extracting(RoutingRule::getUuid)
                .containsExactly("c", "a", "b");
        assertThat(resource.updateRule(DOC, "a", rule("a").copy().pinned(true).build()))
                .extracting(RoutingRule::getUuid)
                .describedAs("an edit keeps the rule where it was")
                .containsExactly("c", "a", "b");
        assertThat(resource.rules(DOC).get(1).isPinned()).isTrue();
        assertThat(resource.deleteRule(DOC, "a")).extracting(RoutingRule::getUuid).containsExactly("c", "b");
    }

    @Test
    void aRuleOfAnotherDocumentOrNoDocumentIsRefused() {
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext());
        rules.append(DOC, rule("a"));

        assertThatThrownBy(() -> resource.deleteRule(DOC, "not-a-rule"))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("not a rule of this document");
        assertThatThrownBy(() -> resource.updateRule(DOC, "a", rule("b")))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("must match");
        assertThat(rules.forDocument(DOC)).extracting(RoutingRule::getUuid).containsExactly("a");
    }

    @Test
    void everyCallAnswersWithTheTableTheServerHoldsNotTheOneTheClientSent() {
        // A rule promoted on another node while a person had the tab open is theirs to see, not theirs to
        // overwrite: the answer to their action carries it.
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext());
        rules.append(DOC, rule("operator"));
        rules.append(DOC, rule("promoted-meanwhile"));

        assertThat(resource.updateRule(DOC, "operator", rule("operator").copy().pinned(true).build()))
                .extracting(RoutingRule::getUuid)
                .containsExactly("operator", "promoted-meanwhile");
    }

    @Test
    void aPersonWhoMayNotSeeTheDocumentMayNotSeeOrChangeWhatItLearned() {
        // A rule names the feed it binds and the fragment that runs on it: reading them is reading the
        // document, and writing one puts a pipeline of the writer's choosing on that feed's data.
        final ShapeshifterAiResourceImpl resource = resource(new MockSecurityContext() {
            @Override
            public boolean hasDocumentPermission(final DocRef docRef, final DocumentPermission permission) {
                return false;
            }
        });
        rules.append(DOC, rule("a"));

        assertThatThrownBy(() -> resource.rules(DOC)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.addRule(DOC, 0, rule(null))).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.updateRule(DOC, "a", rule("a"))).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.moveRule(DOC, "a", 0)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> resource.deleteRule(DOC, "a")).isInstanceOf(PermissionException.class);
        assertThat(rules.forDocument(DOC)).describedAs("nothing was changed").hasSize(1);
    }

    @Test
    void aRuleMayPointOnlyAtAFragment() {
        // A20, checked here since A41 took the table off the document, where the store checked it on save.
        final FragmentCheck refuses = pipeline -> {
            throw new EntityServiceException(pipeline.getName() + " is not a fragment");
        };
        final ShapeshifterAiResourceImpl resource = new ShapeshifterAiResourceImpl(
                () -> storeOf(List.of("DSParser", "XSLTFilter")), () -> null, () -> rules, () -> refuses,
                () -> registry(), MockSecurityContext::new);

        assertThatThrownBy(() -> resource.addRule(DOC, 0, rule(null).copy().pipeline(FRAGMENT).build()))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("is not a fragment");
        assertThat(rules.forDocument(DOC)).isEmpty();
    }

    @Test
    void aFragmentMustBeReplayableOverWhatTheStageIsGiven() {
        // A1, design 01 §4: a stage fed by a parser is given records and its chains must not parse; one
        // fed by the source is given raw data and its chains must. The document's allowed elements are
        // what a chain is chosen from, so they are what says which stage this is.
        final ShapeshifterAiStore records = storeOf(List.of("XSLTFilter"));
        final ShapeshifterAiResourceImpl resource = new ShapeshifterAiResourceImpl(
                () -> records, () -> null, () -> rules, () -> pipeline -> ReplayUnit.STREAM,
                () -> registry(), MockSecurityContext::new);

        assertThatThrownBy(() -> resource.addRule(DOC, 0, rule(null)))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("has a parser")
                .hasMessageContaining("nothing left to parse");
        assertThat(rules.forDocument(DOC)).describedAs("and nothing is bound").isEmpty();
    }

    @Test
    void aStageFedByTheSourceRefusesAFragmentThatParsesNothing() {
        final ShapeshifterAiStore stream = storeOf(List.of("DSParser", "XSLTFilter"));
        final ShapeshifterAiResourceImpl resource = new ShapeshifterAiResourceImpl(
                () -> stream, () -> null, () -> rules, () -> pipeline -> ReplayUnit.RECORD,
                () -> registry(), MockSecurityContext::new);

        assertThatThrownBy(() -> resource.addRule(DOC, 0, rule(null)))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("has no parser")
                .hasMessageContaining("something must parse it");
    }

    /// A registry that knows the element types these documents name, so that both sides of the unit
    /// check are derived the same way they are in a node.
    private static ElementRegistryFactory registry() {
        final ElementRegistry registry = Mockito.mock(ElementRegistry.class);
        Mockito.lenient().when(registry.getElementType(Mockito.anyString())).thenAnswer(call -> {
            final String type = call.getArgument(0);
            return new PipelineElementType(type, type, null,
                    "DSParser".equals(type) || "JSONParser".equals(type)
                            ? new String[]{PipelineElementType.ROLE_PARSER}
                            : new String[]{PipelineElementType.ROLE_TARGET},
                    null);
        });
        return () -> registry;
    }

    /// A store answering with a document whose allowed elements parse, so that a fragment which parses
    /// is the right unit for its stage (A1). The rules here are about the table, not about the unit.
    private static ShapeshifterAiStore storeOf(final List<String> allowedElements) {
        final ShapeshifterAiStore store = Mockito.mock(ShapeshifterAiStore.class);
        Mockito.lenient().when(store.readDocument(Mockito.any())).thenReturn(ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .allowedElements(allowedElements)
                .build());
        return store;
    }

    private ShapeshifterAiResourceImpl resource(final SecurityContext securityContext) {
        // Takes the fragment at its word, and says its chain parses, as the documents here allow.
        final FragmentCheck takesItAtItsWord = pipeline -> ReplayUnit.STREAM;
        final ShapeshifterAiStore store = storeOf(List.of("DSParser", "XSLTFilter"));
        return new ShapeshifterAiResourceImpl(() -> store, () -> null, () -> rules, () -> takesItAtItsWord,
                () -> registry(), () -> securityContext);
    }

    private static RoutingRule rule(final String uuid) {
        return RoutingRule.builder().uuid(uuid).pipeline(FRAGMENT).build();
    }
}
