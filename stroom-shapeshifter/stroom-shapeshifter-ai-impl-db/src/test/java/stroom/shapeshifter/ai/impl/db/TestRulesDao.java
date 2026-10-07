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

package stroom.shapeshifter.ai.impl.db;

import stroom.docref.DocRef;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.RoutingRule;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The rules of A41 as rows: what the seam promises, against a real database. Order is what these watch,
/// since the router takes the first rule that matches and an operator's rule sits above the learned ones.
class TestRulesDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String OTHER = "other-" + System.nanoTime();

    @Inject
    private RulesDao rules;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        rules.forDocument(DOC).forEach(rule -> rules.remove(DOC, rule.getUuid()));
        rules.forDocument(OTHER).forEach(rule -> rules.remove(OTHER, rule.getUuid()));
    }

    @Test
    void aBoundaryKeepsWhereItsRecordsSit() {
        // §12 item 25: the fragment a rule binds splits at a depth, and the depth is part of the boundary
        // rather than derivable from it. A rule read back without it would write a fragment that splits in
        // the wrong place, and equals ignores the depth, so nothing else would notice.
        final String doc = "doc-" + System.nanoTime();
        rules.append(doc, RoutingRule.builder()
                .uuid("rule-1")
                .recordBoundary(RecordBoundary.ofArray("events").atDepth(3))
                .build());
        rules.append(doc, RoutingRule.builder()
                .uuid("rule-2")
                .recordBoundary(RecordBoundary.ofElement("Event"))
                .build());

        final List<RoutingRule> read = rules.forDocument(doc);

        assertThat(read.get(0).getRecordBoundary().getArray()).isEqualTo("events");
        assertThat(read.get(0).getRecordBoundary().splitDepth())
                .describedAs("where its records sit, kept with their name").hasValue(3);
        assertThat(read.get(1).getRecordBoundary().splitDepth())
                .describedAs("a rule stored before the depth was recorded has none, and is written "
                             + "without a filter until it is learned again")
                .isEmpty();
    }

    @Test
    void aRuleSurvivesTheRoundTripWhole() {
        final RoutingRule learned = RoutingRule.builder()
                .shapeId("Feed=DOOR-ACCESS|Type=Raw Events")
                .pipeline(new DocRef("Pipeline", "fragment-1", "door-v1"))
                .provisional(true)
                .promotedTimeMs(1_700_000_000_000L)
                .score(0.97)
                .recordBoundary(RecordBoundary.ofArray("events"))
                .build();

        final RoutingRule stored = rules.append(DOC, learned);

        assertThat(stored.getUuid()).describedAs("a rule without one is named on the way in").isNotNull();
        final List<RoutingRule> read = rules.forDocument(DOC);
        assertThat(read).hasSize(1);
        assertThat(read.get(0)).isEqualTo(stored);
        assertThat(read.get(0).getShapeId()).isEqualTo("Feed=DOOR-ACCESS|Type=Raw Events");
        assertThat(read.get(0).getRecordBoundary()).isEqualTo(RecordBoundary.ofArray("events"));
        assertThat(read.get(0).getScore()).isEqualTo(0.97);
        assertThat(read.get(0).isProvisional()).isTrue();
        assertThat(rules.byUuid(DOC, stored.getUuid())).contains(stored);
        assertThat(rules.byUuid(DOC, "no-such-rule")).isEmpty();
    }

    @Test
    void replacingARuleKeepsItAndPromotingOneThatWentPutsItBack() {
        final String a = rules.append(DOC, named("a")).getUuid();
        final String b = rules.append(DOC, named("b")).getUuid();

        rules.replace(DOC, rules.byUuid(DOC, a).orElseThrow().copy().pinned(true).score(1.0).build());

        assertThat(uuids()).containsExactly(a, b);
        assertThat(rules.byUuid(DOC, a).orElseThrow().isPinned()).isTrue();
        assertThat(rules.byUuid(DOC, a).orElseThrow().getScore()).isEqualTo(1.0);

        // A promotion must not be lost because its row was pruned between the run and the write.
        rules.remove(DOC, a);
        rules.replace(DOC, RoutingRule.builder().uuid(a).score(0.95).build());
        assertThat(uuids()).containsExactly(b, a);
    }

    @Test
    void oneDocumentsRulesAreItsOwn() {
        final String mine = rules.append(DOC, named("mine")).getUuid();
        rules.append(OTHER, named("theirs"));

        assertThat(uuids()).containsExactly(mine);
        assertThat(rules.byUuid(DOC, rules.forDocument(OTHER).get(0).getUuid())).isEmpty();
        rules.remove(DOC, rules.forDocument(OTHER).get(0).getUuid());
        assertThat(rules.forDocument(OTHER)).describedAs("another document's rule is not ours to remove")
                .hasSize(1);
    }

    private List<String> uuids() {
        return rules.forDocument(DOC).stream().map(RoutingRule::getUuid).toList();
    }

    private static RoutingRule named(final String name) {
        return RoutingRule.builder()
                .pipeline(new DocRef("Pipeline", "fragment-" + name, name))
                .build();
    }

    /// The snapshot a rule serves with (A61) goes round the trip, and moves with a replacement.
    @Test
    void aRuleKeepsItsSnapshotAndTakesTheNextOnReplacement() {
        final String doc = "doc-snapshot-" + System.nanoTime();
        rules.append(doc, RoutingRule.builder().uuid("rule-s").snapshot("snap-1").build());

        assertThat(rules.forDocument(doc).get(0).getSnapshot()).isEqualTo("snap-1");

        rules.replace(doc, rules.forDocument(doc).get(0).copy().snapshot("snap-2").build());
        assertThat(rules.forDocument(doc).get(0).getSnapshot()).isEqualTo("snap-2");
        rules.forDocument(doc).forEach(rule -> rules.remove(doc, rule.getUuid()));
    }
}
