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

import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.RoutingRule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The bindings in memory, held to what the table holds them to: a uuid names one binding across every
/// learner, and a write names the learner whose binding it is. A scenario that wrote another learner's
/// binding by mistake would pass in memory and fail on a node otherwise.
class TestInMemoryRules {

    private static final Learner LEARNER = new Learner("pipeline-1", "shapeshifterAi");
    private static final Learner NEIGHBOUR = new Learner("pipeline-2", "shapeshifterAi");

    @Test
    void anotherLearnersBindingIsNotThisOnesToReplaceOrRemove() {
        final InMemoryRules rules = new InMemoryRules();
        final RoutingRule bound = rules.append(NEIGHBOUR, "doc-1", RoutingRule.builder().uuid("rule-1").build());

        assertThatThrownBy(() -> rules.replace(LEARNER, "doc-1", bound.copy().score(1.0).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("binding of pipeline-2");
        rules.remove(LEARNER, "rule-1");

        assertThat(rules.forLearner(NEIGHBOUR)).containsExactly(bound);
        assertThat(rules.forLearner(LEARNER)).isEmpty();
    }

    @Test
    void aLearnersBindingsAreBroughtToItsDocumentAndNoOtherLearnersAre() {
        final InMemoryRules rules = new InMemoryRules();
        rules.append(LEARNER, "doc-1", RoutingRule.builder().uuid("rule-1").build());
        rules.append(NEIGHBOUR, "doc-1", RoutingRule.builder().uuid("rule-2").build());

        rules.runsUnder(LEARNER, "doc-2");

        assertThat(rules.byUuid("rule-1")).hasValueSatisfying(held -> assertThat(held.docUuid()).isEqualTo("doc-2"));
        assertThat(rules.byUuid("rule-2")).hasValueSatisfying(held -> assertThat(held.docUuid()).isEqualTo("doc-1"));
    }
}
