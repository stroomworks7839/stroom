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
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.ai.stage.Serving;
import stroom.shapeshifter.ai.stage.Shapes;
import stroom.shapeshifter.ai.state.InMemoryGuidance;
import stroom.shapeshifter.ai.state.InMemoryRules;
import stroom.shapeshifter.ai.state.InMemoryServing;
import stroom.shapeshifter.ai.state.InMemoryShapes;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ServingRule;

import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// The serving view of A46: the rules, the shape state behind each, and what a supervisor has said —
/// one page, ordered by the traffic each rule carries.
class TestServingDao {

    private static final String DOC = "doc-" + System.nanoTime();
    private static final String OTHER_DOC = "other-" + System.nanoTime();
    private static final String BUSY = "Feed=DOOR-ACCESS|Type=Raw Events";
    private static final String QUIET = "Feed=TURNSTILE|Type=Raw Events";
    private static final String NEW = "Feed=LIFT|Type=Raw Events";
    private static final int MEMORY = 10;

    @Inject
    private RulesDao rules;
    @Inject
    private ShapesDao shapes;
    @Inject
    private GuidanceDao guidance;
    @Inject
    private ServingDao serving;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        clear(rules, shapes, guidance, DOC);
        clear(rules, shapes, guidance, OTHER_DOC);
    }

    /// Ordered by traffic, because A46's question — could this be better? — is worth asking first about
    /// whatever carries the most streams.
    @Test
    void whatIsServingComesBackBusiestFirst() {
        bound(rules, DOC, "busy", BUSY);
        bound(rules, DOC, "quiet", QUIET);
        bound(rules, DOC, "new", NEW);
        scored(shapes, DOC, BUSY, 0.93, 8);
        scored(shapes, DOC, QUIET, 0.71, 2);

        final Serving.Page page = serving.rules(List.of(DOC), null, 0L, 100);

        assertThat(page.total()).isEqualTo(3L);
        assertThat(page.rules())
                .extracting(ServingRule::getShapeId, ServingRule::getRecords, ServingRule::getRollingScore)
                .describedAs("busiest first, and a rule that has served nothing yet is still shown — a "
                             + "binding made a minute ago is the one most worth a look")
                .containsExactly(
                        tuple(BUSY, 8, 0.93),
                        tuple(QUIET, 2, 0.71),
                        tuple(NEW, 0, null));
        assertThat(page.rules().get(0).getDoc().getUuid()).isEqualTo(DOC);
        assertThat(page.rules().get(0).getFragment().getName()).isEqualTo("busy-fragment");
    }

    /// A46's filter: "good but not perfect" is the only thing worth narrowing by, and a shape with no
    /// score is not below anything.
    @Test
    void aThresholdLeavesOutWhatIsGoodEnoughAndWhatHasNoScoreAtAll() {
        bound(rules, DOC, "busy", BUSY);
        bound(rules, DOC, "quiet", QUIET);
        bound(rules, DOC, "new", NEW);
        scored(shapes, DOC, BUSY, 0.93, 8);
        scored(shapes, DOC, QUIET, 0.71, 2);

        final Serving.Page page = serving.rules(List.of(DOC), 0.9, 0L, 100);

        assertThat(page.rules()).extracting(ServingRule::getShapeId)
                .describedAs("the one scoring below it; not the one above, and not the one with nothing "
                             + "to say it is imperfect")
                .containsExactly(QUIET);
        assertThat(page.total())
                .describedAs("and the total counts what the filter left, or a pager would promise pages "
                             + "that are not there")
                .isEqualTo(1L);
    }

    /// The three kinds of rule the view does not show, each for its own reason.
    @Test
    void aDraftAReservedRuleAndAHandWrittenOneAreNotServingRules() {
        bound(rules, DOC, "busy", BUSY);
        rules.append(DOC, RoutingRule.builder()
                .uuid("a-draft")
                .shapeId(QUIET)
                .pipeline(new DocRef("Pipeline", "draft-fragment", "draft"))
                .draft(true)
                .build());
        rules.append(DOC, RoutingRule.builder().uuid("reserved").shapeId(NEW).build());
        rules.append(DOC, RoutingRule.builder()
                .uuid("by-hand")
                .pipeline(new DocRef("Pipeline", "hand-fragment", "by hand"))
                .build());

        assertThat(serving.rules(List.of(DOC), null, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid)
                .describedAs("a draft is decided rather than improved, a reserved rule binds nothing, and "
                             + "a rule written by hand came from no shape")
                .containsExactly("busy");
    }

    /// Each row says how much has been said about its shape, so that a person can see whether the last
    /// hint was acted on before giving another.
    @Test
    void aRowSaysHowMuchHasBeenSaidAboutItsShape() {
        bound(rules, DOC, "busy", BUSY);
        bound(rules, DOC, "quiet", QUIET);
        guidance.given(DOC, BUSY, "Timestamps are local, not UTC.", "jo");
        guidance.given(DOC, BUSY, "Column four is a terminal id.", "sam");
        guidance.given(OTHER_DOC, BUSY, "A different document's shape of the same name.", "jo");

        assertThat(serving.rules(List.of(DOC), null, 0L, 100).rules())
                .extracting(ServingRule::getShapeId, ServingRule::getGuidance)
                .describedAs("counted per document as well as per shape, since two documents may learn "
                             + "shapes of the same name")
                .containsExactlyInAnyOrder(tuple(BUSY, 2), tuple(QUIET, 0));
    }

    /// The documents a person may read go into the query, as they do for the ledger: a total taken
    /// before the filtering would count rules they may not see.
    @Test
    void aDocumentNobodyNamedIsNotInTheAnswer() {
        bound(rules, DOC, "busy", BUSY);
        bound(rules, OTHER_DOC, "theirs", BUSY);

        assertThat(serving.rules(List.of(DOC), null, 0L, 100).rules())
                .extracting(ServingRule::getRuleUuid).containsExactly("busy");
        assertThat(serving.rules(List.of(), null, 0L, 100).rules())
                .describedAs("and naming no documents answers nothing rather than everything")
                .isEmpty();
        assertThat(serving.rules(List.of(DOC, OTHER_DOC), null, 0L, 100).rules()).hasSize(2);
    }

    /// The table and the heap answer the same question the same way.
    ///
    /// Two implementations exist — the join a node runs and the three maps the scenarios run on — and a
    /// view written against one and served by the other is a view that lies. The order and the paging
    /// are what would drift silently, so they are what is held: the in-memory one sorts in Java and the
    /// node sorts in SQL, and a tiebreaker either one lacked would make this a coin flip.
    @Test
    void theViewInMemoryAgreesWithTheViewInRows() {
        final InMemoryRules memoryRules = new InMemoryRules();
        final InMemoryShapes memoryShapes = new InMemoryShapes();
        final InMemoryGuidance memoryGuidance = new InMemoryGuidance();
        final Serving inMemory = new InMemoryServing(memoryRules, memoryShapes, memoryGuidance);
        final List<Rules> bothRules = List.of(rules, memoryRules);
        final List<Shapes> bothShapes = List.of(shapes, memoryShapes);
        final List<Guidance> bothGuidance = List.of(guidance, memoryGuidance);
        bothRules.forEach(seam -> {
            bound(seam, DOC, "a-busy", BUSY);
            bound(seam, DOC, "b-quiet", QUIET);
            bound(seam, DOC, "c-new", NEW);
        });
        bothShapes.forEach(seam -> {
            scored(seam, DOC, BUSY, 0.93, 8);
            scored(seam, DOC, QUIET, 0.71, 2);
        });
        bothGuidance.forEach(seam -> seam.given(DOC, BUSY, "Timestamps are local.", "jo"));
        // A rule whose shape id is blank rather than absent: both must read it as having come from no
        // shape, or one lists a row whose improve button the other's stage refuses.
        bothRules.forEach(seam -> seam.append(DOC, RoutingRule.builder()
                .uuid("d-blank")
                .shapeId("  ")
                .pipeline(new DocRef("Pipeline", "blank-uuid", "blank-fragment"))
                .build()));

        assertThat(summarise(inMemory.rules(List.of(DOC), null, 0L, 100).rules()))
                .describedAs("the same rules, the same scores, the same counts, in the same order")
                .isEqualTo(summarise(serving.rules(List.of(DOC), null, 0L, 100).rules()));
        assertThat(summarise(inMemory.rules(List.of(DOC), null, 1L, 1).rules()))
                .describedAs("and the same page of them")
                .isEqualTo(summarise(serving.rules(List.of(DOC), null, 1L, 1).rules()));
        assertThat(inMemory.rules(List.of(DOC), 0.9, 0L, 100).total())
                .describedAs("and the same total under a threshold")
                .isEqualTo(serving.rules(List.of(DOC), 0.9, 0L, 100).total());
        assertThat(summarise(serving.rules(List.of(DOC), null, 1L, 1).rules()))
                .containsExactly("b-quiet " + QUIET + " x2 @0.71 said 0");
        assertThat(summarise(serving.rules(List.of(DOC), null, 0L, 100).rules()))
                .describedAs("and a blank shape id is no shape, in the table as in the heap")
                .noneMatch(row -> row.startsWith("d-blank"));
    }

    private static List<String> summarise(final List<ServingRule> rules) {
        return rules.stream()
                .map(rule -> rule.getRuleUuid() + " " + rule.getShapeId() + " x" + rule.getRecords()
                             + " @" + rule.getRollingScore() + " said " + rule.getGuidance())
                .toList();
    }

    private static RoutingRule bound(final Rules rules,
                                     final String docUuid,
                                     final String ruleUuid,
                                     final String shapeId) {
        return rules.append(docUuid, RoutingRule.builder()
                .uuid(ruleUuid)
                .shapeId(shapeId)
                .pipeline(new DocRef("Pipeline", ruleUuid + "-uuid", ruleUuid + "-fragment"))
                .promotedTimeMs(1_700_000_000_000L)
                .score(0.95)
                .build());
    }

    /// A shape scored over enough records to have a rolling score of exactly what was asked for: the
    /// mean of one stream's score is that score, whatever the memory.
    private static void scored(final Shapes shapes,
                               final String docUuid,
                               final String shapeId,
                               final double score,
                               final int records) {
        shapes.scored(docUuid, shapeId, score, records, MEMORY);
    }

    private static void clear(final Rules rules,
                              final Shapes shapes,
                              final Guidance guidance,
                              final String docUuid) {
        rules.forDocument(docUuid).forEach(rule -> rules.remove(docUuid, rule.getUuid()));
        List.of(BUSY, QUIET, NEW).forEach(shape -> {
            shapes.reset(docUuid, shape);
            guidance.standing(docUuid, shape).forEach(given -> guidance.withdraw(docUuid, given.id()));
        });
    }
}
