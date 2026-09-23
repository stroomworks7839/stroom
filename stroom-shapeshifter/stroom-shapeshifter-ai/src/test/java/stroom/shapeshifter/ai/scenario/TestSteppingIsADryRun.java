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

import stroom.docref.DocRef;
import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Would;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 01 §11.7 and A30: **stepping is a dry run**. The stage routes and serves, and does nothing
/// else — no model asked, no fragment written, no rule bound, no ledger row, no output row, no rolling
/// score and no reprocess request. A shape no rule binds is reported rather than learned.
///
/// The case that makes it matter is the ordinary one: a person opens the stepper on a feed nobody has
/// taught yet. Learning it by looking would spend a model call, write a fragment and bind a rule, none
/// of which they asked for and all of which would then be serving live data.
class TestSteppingIsADryRun {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// A transform that makes nothing of the records it is given: it compiles and runs, so the gate it
    /// fails is the one that counts what came out.
    private static final String YIELDS_NOTHING = """
            <xsl:stylesheet xmlns="event-logging:3" xpath-default-namespace="records:2"
                            xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="records"><Events/></xsl:template>
            </xsl:stylesheet>""";
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);

    @Test
    void anUnknownShapeIsReportedRatherThanLearned() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Stage stage = scenarios.stage(document -> script, scenarios.rules);

        final StageRun stepped = stage.dryRun(doc(LearningMode.AUTOMATIC), stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Would.class);
        assertThat(((Would) stepped.decision()).what()).isEqualTo("Learn a fragment for this shape");
        assertThat(((Would) stepped.decision()).said())
                .describedAs("the hypothetical is in the decision, so that every surface reads it the "
                             + "same way and only a decision that is hypothetical reads as one")
                .isEqualTo("Would learn a fragment for this shape");
        assertThat(((Would) stepped.decision()).candidate())
                .describedAs("there is no fragment it could have used").isNull();
        assertThat(script.asked()).describedAs("the model was not asked").isEmpty();
        assertThat(scenarios.rules.forDocument("doc-1")).describedAs("no rule was bound").isEmpty();
        assertThat(scenarios.attempts.forDocument("doc-1", 10)).describedAs("no attempt was recorded").isEmpty();
        assertThat(scenarios.ledger.release("doc-1", stepped.shape().id()))
                .describedAs("and the stream was not sentinelled onto the ledger").isEmpty();
        assertThat(scenarios.outputs.asProcessed("doc-1", 1L, "pipeline-1"))
                .describedAs("nor recorded as having produced anything").isEmpty();
    }

    @Test
    void aDisabledDocumentSaysWhatItWouldDoAndWritesNoLedgerRow() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> Script.of(), scenarios.rules);

        final StageRun stepped = stage.dryRun(doc(LearningMode.DISABLED), stream(), false);

        assertThat(((Would) stepped.decision()).what())
                .isEqualTo("Sentinel the stream: Shapeshifter AI is disabled for this document and no "
                           + "bound variant fits");
        assertThat(scenarios.ledger.release("doc-1", stepped.shape().id()))
                .describedAs("a sentinel a person only asked to see is not a sentinel").isEmpty();
    }

    @Test
    void aBoundShapeIsServedAndNothingIsRecorded() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Stage stage = scenarios.stage(document -> script, scenarios.rules);
        final ShapeshifterAiDoc doc = doc(LearningMode.AUTOMATIC);

        // Learned for real first, so that there is something to step over.
        final StageRun learned = stage.run(doc, stream());
        assertThat(learned.bindings()).isNotNull();

        final StageRun stepped = stage.dryRun(doc, stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Bound.class);
        assertThat(stepped.output())
                .describedAs("the fragment ran: a person steps a pipeline to see what it makes")
                .isEqualTo(learned.output());
        assertThat(stepped.verdicts()).describedAs("and what the scorers made of it").isNotEmpty();
        assertThat(scenarios.outputs.asProcessed("doc-1", 2L, "pipeline-1"))
                .describedAs("but the run is not an output: nothing was produced for anyone")
                .isEmpty();
    }

    /// A provisional rule is on trial (A14, design 01 §6): the first stream that brings enough records
    /// to judge it either promotes it or retracts it. Retraction is the one branch of serving where the
    /// stream gets *no output at all* — the rule goes, the stream is sentinelled — so a step that said
    /// "served by rule X" and showed its output would be showing the opposite of what would happen.
    @Test
    void aProvisionalRuleAboutToBeRetractedSaysSoRatherThanShowingItsOutput() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> Script.of(), scenarios.rules);
        final ShapeshifterAiDoc doc = doc(LearningMode.AUTOMATIC);

        // A rule on trial, bound to a transform that makes nothing of the records: enough records to be
        // judged on, and a score that cannot clear the floor.
        final DocRef fragment = scenarios.stores.writer().write(
                DocPath.fromParts("Shapeshifter", FEED), "on-trial-v1",
                List.of(new LearnedStep(scenarios.runners().getFirst(), CSV.configuration(),
                                new StepResult("<records/>", List.of()), UNSCORED),
                        new LearnedStep(new XsltStep(), YIELDS_NOTHING,
                                new StepResult("<Events/>", List.of()), UNSCORED)),
                null);
        scenarios.rules.append("doc-1", RoutingRule.builder()
                .uuid("rule-on-trial")
                .expression(RoutingRule.learnedSelector(doc.getLearningKey(),
                        Map.of(MetaFields.FIELD_FEED, FEED, MetaFields.FIELD_TYPE, "Raw Events",
                                "Format", "CSV")))
                .pipeline(fragment)
                .provisional(true)
                .build());

        final StageRun stepped = stage.dryRun(doc, stream(), false);

        assertThat(stepped.decision()).isInstanceOf(Would.class);
        assertThat(((Would) stepped.decision()).what())
                .describedAs("what would happen to the rule, and to the stream with it")
                .startsWith("Retract rule rule-on-trial")
                .endsWith("and sentinel the stream");
        assertThat(stepped.output())
                .describedAs("and no output, because a retracted stream produces none").isNull();
        assertThat(scenarios.rules.forDocument("doc-1"))
                .describedAs("the rule is still there: a step decides nothing").hasSize(1);
    }

    /// A shape nothing binds may still be served by a fragment learned for its neighbour (design 01 §6),
    /// and trying one costs no question. A step says it would bind it — and shows what it made of the
    /// stream, because a fragment's output is what the person opening the stepper came to see.
    @Test
    void aVariantThatWouldBeBoundShowsWhatItProduced() {
        final Scenarios scenarios = new Scenarios();
        final Script script = script(scenarios);
        final Stage stage = scenarios.stage(document -> script, scenarios.rules);
        final ShapeshifterAiDoc doc = doc(LearningMode.AUTOMATIC);

        // Learned for one feed, then stepped on another: same content, so the fragment fits.
        final StageRun learned = stage.run(doc, stream());
        assertThat(learned.bindings()).isNotNull();

        final StageRun stepped = stage.dryRun(doc, new Input(2L, FEED, "Raw Events",
                Map.of("Format", "TEXT"), CSV.input(), "pipeline-1"), false);

        assertThat(stepped.decision()).isInstanceOf(Would.class);
        assertThat(((Would) stepped.decision()).what()).startsWith("Bind ");
        assertThat(((Would) stepped.decision()).candidate())
                .describedAs("named, so that the pane can offer it as a link")
                .isEqualTo(learned.bindings().fragment());
        assertThat(stepped.output())
                .describedAs("what the variant made of this stream, which is what the panes show")
                .isEqualTo(learned.output());
        assertThat(stepped.bindings())
                .describedAs("and no bindings, because nothing was bound: what is served follows the "
                             + "run's events and not its bindings")
                .isNull();
        assertThat(scenarios.rules.forDocument("doc-1"))
                .describedAs("nothing was bound for the new shape").hasSize(1);
    }

    private Script script(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    /// @param mode Whether the document may learn.
    private static ShapeshifterAiDoc doc(final LearningMode mode) {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(mode)
                // Format as well as feed and type, so that two streams of one feed can be two shapes —
                // which is what makes a variant learned for the neighbour worth trying (design 01 §6).
                .learningKey(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, "Format"))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(1)
                .promotionFloor(0.85)
                // Named, because a document with no scorers scores every chain 1.0 and nothing it binds
                // could ever fail the gate it is on trial for.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream() {
        return new Input(1L, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
