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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Ruling A28, design 01 §11.6: **taking a rule back out of the table by hand.**
///
/// The gate retracts a provisional rule that fails it (§6). This is the same act for a reason no gate
/// can see — a person has read what the rule is producing and decided it should not be — and what
/// follows is deliberately identical: the rule goes, the shape is unknown again, and everything the
/// rule produced is asked to be processed again as it would be now (A12).
///
/// It is neither of the two things that look like it. `remove`, on the Routing tab, is exactly what it
/// says: a rule taken out of a table, leaving the shape thinking it is bound and the streams it produced
/// standing as though they were right. `reject` is A25's decision about a draft that served nothing, and
/// gives the shape up so it is not learned again. A retracted shape *is* learned again — the rule was
/// wrong, the shape is not.
class TestRetractingARuleByHand {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    @Test
    void theRuleGoesTheShapeIsUnknownAgainAndWhatItProducedIsAskedForAgain() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();
        stage.run(doc, stream(1L));
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);
        // A second stream, so that the rule has produced something to ask for again.
        stage.run(doc, stream(2L));
        assertThat(scenarios.outputs.boundBy(bound.getUuid(), bound.getPipeline().getUuid()))
                .describedAs("the rule has produced output rows to take back")
                .isNotEmpty();

        stage.retract(doc, bound.getUuid(), "The timestamps are being read as UTC and the feed is local.",
                "jo");

        assertThat(scenarios.rules.forDocument("doc-1"))
                .describedAs("the rule is out of the table")
                .isEmpty();
        assertThat(scenarios.shapes.reasonGivenUp("doc-1", SHAPE))
                .describedAs("and the shape is unknown again rather than given up: the rule was wrong, "
                             + "the shape is not, so the next stream of it learns afresh")
                .isEmpty();
        assertThat(scenarios.shapes.rollingScore("doc-1", SHAPE))
                .describedAs("with nothing of what the retracted rule scored carried into what comes next")
                .isZero();
        assertThat(scenarios.reprocessing.requests())
                .describedAs("and everything the rule produced is asked to be processed again as it "
                             + "would be now (A12), saying who asked and why")
                .isNotEmpty()
                .allSatisfy(request -> assertThat(request.reason())
                        .contains("Retracted by jo")
                        .contains("read as UTC"));
        assertThat(scenarios.regressionSet.accepted(bound.getUuid()))
                .describedAs("the records it was accepted on go with it: they are what it was judged "
                             + "against, and it is not being judged again")
                .isEmpty();
    }

    /// The next stream of a retracted shape learns it afresh. That is the whole difference from
    /// rejecting, and the reason retracting is safe to offer for a rule that is merely wrong.
    @Test
    void theNextStreamOfTheShapeLearnsItAgain() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.stage(document -> learn(scenarios), scenarios.rules).run(doc, stream(1L));
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);
        scenarios.stage(document -> learn(scenarios), scenarios.rules)
                .retract(doc, bound.getUuid(), "Wrong.", "jo");

        final Script asked = learn(scenarios);
        scenarios.stage(document -> asked, scenarios.rules).run(doc, stream(3L));

        assertThat(asked.asked()).describedAs("the model is asked again, from nothing").isNotEmpty();
        assertThat(scenarios.rules.forDocument("doc-1"))
                .describedAs("and a rule is bound again")
                .hasSize(1);
        assertThat(scenarios.rules.forDocument("doc-1").get(0).getUuid())
                .describedAs("a new one: the retracted rule is gone, not rebound")
                .isNotEqualTo(bound.getUuid());
    }

    /// The three states in which retracting must refuse rather than proceed, each of which would undo
    /// something somebody else decided.
    @Test
    void whatCannotBeRetractedSaysSoRatherThanGuessing() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();
        stage.run(doc, stream(1L));
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);

        assertThatThrownBy(() -> stage.retract(doc, "not-a-rule", "Wrong.", "jo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a rule of this document");

        scenarios.rules.append("doc-1", RoutingRule.builder().uuid("reserved").build());
        assertThatThrownBy(() -> stage.retract(doc, "reserved", "Wrong.", "jo"))
                .describedAs("a reserved rule binds nothing, so nothing is serving to be taken back")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binds nothing");

        scenarios.rules.append("doc-1", bound.copy().uuid("a-draft").draft(true).build());
        assertThatThrownBy(() -> stage.retract(doc, "a-draft", "Wrong.", "jo"))
                .describedAs("a draft served nothing, so deciding it is Approve or Reject (A25)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft awaiting review");

        scenarios.rules.replace("doc-1", bound.copy().pinned(true).build());
        assertThatThrownBy(() -> stage.retract(doc, bound.getUuid(), "Wrong.", "jo"))
                .describedAs("§7.3 rule 2: a pin freezes a rule — served, never promoted, retracted or "
                             + "relearned")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned");
    }

    /// The attempt that bound the rule says it has been taken back.
    ///
    /// Not through the door A25's decisions use: that one closes the attempt *awaiting review* for a
    /// rule, and a retraction is about a rule that was **serving** — whose attempt closed promoted a
    /// long time ago. Sent through it, the call matched nothing and the attempt went on reading
    /// "Promoted 0.93" for a rule that no longer exists.
    @Test
    void theAttemptThatBoundItSaysSo() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.rules);
        final ShapeshifterAiDoc doc = doc();
        stage.run(doc, stream(1L));
        final RoutingRule bound = scenarios.rules.forDocument("doc-1").get(0);
        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of("doc-1")).attempts())
                .describedAs("the attempt that bound it closed promoted")
                .anySatisfy(attempt -> assertThat(attempt.status()).isEqualTo(AttemptStatus.PROMOTED));

        stage.retract(doc, bound.getUuid(), "The timestamps are wrong.", "jo");

        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of("doc-1")).attempts())
                .describedAs("and now says it was retracted, with who asked and why")
                .anySatisfy(attempt -> {
                    assertThat(attempt.status()).isEqualTo(AttemptStatus.RETRACTED);
                    assertThat(attempt.decision()).contains("Retracted by jo").contains("timestamps");
                });
    }

    private Script learn(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(1)
                .promotionFloor(0.85)
                // Named, because a document with no scorers scores every chain 1.0.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
