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
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Rules.Held;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.RoutingRule;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// The bindings of A41 as rows: what the seam promises, against a real database. A binding is a
/// learner's (design 06 phase J slice 2b), and its uuid names it across every learner. The database
/// outlives a run, so every uuid and learner here is made fresh for it.
class TestRulesDao {

    private static final String RUN = String.valueOf(System.nanoTime());
    private static final String DOC = "doc-" + RUN;
    private static final Learner LEARNER = new Learner("pipeline-" + RUN, "shapeshifterAi");
    private static final Learner OTHER = new Learner("pipeline-" + RUN, "shapeshifterAi2");

    @Inject
    private RulesDao rules;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        for (final Learner learner : List.of(LEARNER, OTHER)) {
            rules.forLearner(learner).forEach(rule -> rules.remove(learner, rule.getUuid()));
        }
    }

    @Test
    void aBoundaryKeepsWhereItsRecordsSit() {
        // §12 item 25: the fragment a rule binds splits at a depth, and the depth is part of the boundary
        // rather than derivable from it. A rule read back without it would write a fragment that splits in
        // the wrong place, and equals ignores the depth, so nothing else would notice.
        rules.append(LEARNER, DOC, RoutingRule.builder()
                .uuid(fresh("array"))
                .recordBoundary(RecordBoundary.ofArray("events").atDepth(3))
                .build());
        rules.append(LEARNER, DOC, RoutingRule.builder()
                .uuid(fresh("element"))
                .recordBoundary(RecordBoundary.ofElement("Event"))
                .build());

        final List<RoutingRule> read = rules.forLearner(LEARNER);

        assertThat(read.get(0).getRecordBoundary().getArray()).isEqualTo("events");
        assertThat(read.get(0).getRecordBoundary().splitDepth())
                .describedAs("where its records sit, kept with their name").hasValue(3);
        assertThat(read.get(1).getRecordBoundary().splitDepth())
                .describedAs("a rule stored before the depth was recorded has none, and is written "
                             + "without a filter until it is learned again")
                .isEmpty();
    }

    @Test
    void aRuleSurvivesTheRoundTripWholeAndSaysWhoseItIs() {
        final RoutingRule learned = RoutingRule.builder()
                .pipeline(new DocRef("Pipeline", "fragment-1", "door-v1"))
                .provisional(true)
                .promotedTimeMs(1_700_000_000_000L)
                .score(0.97)
                .recordBoundary(RecordBoundary.ofArray("events"))
                .build();

        final RoutingRule stored = rules.append(LEARNER, DOC, learned);

        assertThat(stored.getUuid()).describedAs("a rule without one is named on the way in").isNotNull();
        final List<RoutingRule> read = rules.forLearner(LEARNER);
        assertThat(read).hasSize(1);
        assertThat(read.get(0)).isEqualTo(stored);
        assertThat(read.get(0).getPipeline()).isEqualTo(new DocRef("Pipeline", "fragment-1", "door-v1"));
        assertThat(read.get(0).getRecordBoundary()).isEqualTo(RecordBoundary.ofArray("events"));
        assertThat(read.get(0).getScore()).isEqualTo(0.97);
        assertThat(read.get(0).isProvisional()).isTrue();
        assertThat(rules.byUuid(stored.getUuid())).contains(new Held(LEARNER, DOC, stored));
        assertThat(rules.byUuid(fresh("no-such-rule"))).isEmpty();
    }

    @Test
    void replacingARuleKeepsItAndPromotingOneThatWentPutsItBack() {
        final String a = rules.append(LEARNER, DOC, named("a")).getUuid();
        final String b = rules.append(LEARNER, DOC, named("b")).getUuid();

        rules.replace(LEARNER, DOC, rule(a).copy().pinned(true).score(1.0).build());

        assertThat(uuids()).containsExactly(a, b);
        assertThat(rule(a).isPinned()).isTrue();
        assertThat(rule(a).getScore()).isEqualTo(1.0);

        // A promotion must not be lost because its row was pruned between the run and the write.
        rules.remove(LEARNER, a);
        rules.replace(LEARNER, DOC, RoutingRule.builder().uuid(a).score(0.95).build());
        assertThat(uuids()).containsExactly(b, a);
    }

    /// A row records the document the learner runs under, and a replacement brings it up to date: a
    /// pipeline whose element is pointed at another document keeps what it learned, under the new one.
    @Test
    void aReplacementRecordsTheDocumentTheLearnerNowRunsUnder() {
        final String a = rules.append(LEARNER, DOC, named("a")).getUuid();

        rules.replace(LEARNER, "moved-" + RUN, rule(a).copy().score(0.9).build());

        assertThat(rules.byUuid(a)).map(Held::docUuid).contains("moved-" + RUN);
    }

    @Test
    void oneLearnersRulesAreItsOwn() {
        final String mine = rules.append(LEARNER, DOC, named("mine")).getUuid();
        final String theirs = rules.append(OTHER, DOC, named("theirs")).getUuid();

        assertThat(uuids()).containsExactly(mine);
        assertThat(rules.byUuid(theirs)).map(Held::learner).contains(OTHER);
        rules.remove(LEARNER, theirs);
        assertThat(rules.forLearner(OTHER)).describedAs("another learner's rule is not ours to remove")
                .hasSize(1);
    }

    /// The snapshot a rule serves with (A61) goes round the trip, and moves with a replacement.
    @Test
    void aRuleKeepsItsSnapshotAndTakesTheNextOnReplacement() {
        rules.append(LEARNER, DOC, RoutingRule.builder().uuid(fresh("snapshot")).snapshot("snap-1").build());

        assertThat(rules.forLearner(LEARNER).get(0).getSnapshot()).isEqualTo("snap-1");

        rules.replace(LEARNER, DOC, rules.forLearner(LEARNER).get(0).copy().snapshot("snap-2").build());
        assertThat(rules.forLearner(LEARNER).get(0).getSnapshot()).isEqualTo("snap-2");
    }

    private List<String> uuids() {
        return rules.forLearner(LEARNER).stream().map(RoutingRule::getUuid).toList();
    }

    private RoutingRule rule(final String uuid) {
        final Optional<Held> held = rules.byUuid(uuid);
        return held.orElseThrow().rule();
    }

    private static String fresh(final String name) {
        return name + "-" + RUN;
    }

    private static RoutingRule named(final String name) {
        return RoutingRule.builder()
                .pipeline(new DocRef("Pipeline", "fragment-" + name, name))
                .build();
    }
}
