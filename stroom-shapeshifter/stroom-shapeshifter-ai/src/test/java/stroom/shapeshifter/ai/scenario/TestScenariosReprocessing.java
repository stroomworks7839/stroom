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
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.StandInFragmentRunner;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 01 §7.3: reprocessing is a choice rather than an accident. **As-current** resolves the
/// selector against today's routing table — what "we have fixed it, run the backlog again" wants, and
/// the mode a release (A12) reprocesses in. **As-processed** runs each stream through the fragment that
/// produced its output before — what an audit wants, and what rule 1 makes possible by never letting a
/// document that has run be edited.
///
/// The two are only distinguishable once a rule has been rebound, which is what these say.
class TestScenariosReprocessing {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String RECORDS_XSLT = Scenarios.resource("records.xsl");
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsPerShape(1)
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), data, "pipeline-1");
    }

    /// A fragment runner that remembers what boundary it was asked to run under, and otherwise runs.
    private static final class Watching implements FragmentRunner {

        private final List<RecordBoundary> boundaries = new ArrayList<>();
        private final FragmentRunner runner;

        private Watching(final FragmentRunner runner) {
            this.runner = runner;
        }

        @Override
        public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
            boundaries.add(boundary);
            return runner.run(fragment, input, boundary);
        }
    }

    private static Stage stage(final Scenarios scenarios) {
        return scenarios.stage(scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT)));
    }

    @Test
    void asProcessedRunsTheFragmentThatProducedTheOutputAndNotWhatIsBoundNow() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = stage(scenarios);

        final ShapeshifterAiDoc doc = doc();
        final StageRun learned = stage.run(doc, stream(1, CSV.input()));
        assertThat(learned.decision()).describedAs(learned.decision().toString())
                .isInstanceOf(Promoted.class);
        final DocRef first = learned.bindings().fragment();

        // The rule is rebound to another fragment, as an improvement does (§7.3 rule 2). The second
        // fragment is a real one: an as-current run would succeed with it and produce other events,
        // which is what makes the two modes tell apart.
        final RoutingRule rule = scenarios.rules.forDocument("doc-1").getFirst();
        final DocRef second = scenarios.stores.writer().write(
                DocPath.fromParts("Shapeshifter", FEED), "improved-v2",
                List.of(new LearnedStep(scenarios.runners().getFirst(), CSV.configuration(),
                                new StepResult("<records/>", List.of()), UNSCORED),
                        new LearnedStep(new XsltStep(), DEGENERATE,
                                new StepResult("<Events/>", List.of()), UNSCORED)),
                rule.getRecordBoundary());
        scenarios.rules.replace("doc-1", rule.copy().pipeline(second).build());

        // As-processed: the stream goes back through the fragment that made its output.
        final StageRun again = stage.reprocess(doc, stream(1, CSV.input()));

        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Bound.class);
        assertThat(again.bindings().fragment().getUuid())
                .describedAs("the fragment that produced it, not the one bound now")
                .isEqualTo(first.getUuid());
        assertThat(again.output()).isEqualTo(learned.output());
    }

    @Test
    void asProcessedRunsUnderTheBoundaryThatProducedTheOutputAndNotTodaysRuleBoundary() {
        // A rebind keeps the rule's uuid and replaces its boundary (§7.3 rule 2), and the boundary is
        // what says where the chain is cut and therefore what the transform is given. So the fragment
        // alone is not enough to run a stream as it ran: the row remembers what one record was when the
        // output was made, and that is what the run is given.
        final Scenarios scenarios = new Scenarios();
        final Watching watching = new Watching(new StandInFragmentRunner(scenarios.stores.pipelines,
                scenarios.stores.stackLoader, scenarios.stores.textConverters, scenarios.stores.xslts,
                scenarios.runners()));
        final Script script = scenarios.jsonScript("events", RECORDS_XSLT)
                .expect(QuestionMatcher.chain()).reply("JSONParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply("events")
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(RECORDS_XSLT));
        final Stage stage = scenarios.stage(document -> script, scenarios.rules, watching);
        final ShapeshifterAiDoc doc = doc().copy()
                .allowedElements(List.of("JSONParser", "XSLTFilter"))
                // The split question is what settles the boundary, and target-first asks it of every input.
                .plan(PlanExample.TARGET_FIRST)
                .build();
        final Input json = new Input(1, "API-GATEWAY", "Raw Events", Map.of("Format", "JSON"),
                Scenarios.resource("records.json"), "pipeline-1");

        final StageRun learned = stage.run(doc, json);
        assertThat(learned.decision()).describedAs(learned.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(learned.bindings().boundary().splitDepth().orElseThrow())
                .describedAs("the items of the array sit three elements down")
                .isEqualTo(3);

        // The rule is rebound with a boundary that would cut somewhere else entirely.
        final RoutingRule rule = scenarios.rules.forDocument("doc-1").getFirst();
        scenarios.rules.replace("doc-1", rule.copy()
                .recordBoundary(RecordBoundary.ofArray("events").atDepth(1))
                .build());

        watching.boundaries.clear();
        final StageRun again = stage.reprocess(doc, json);

        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Bound.class);
        assertThat(watching.boundaries)
                .describedAs("run under the boundary that produced the output, not the rule's today")
                .extracting(boundary -> boundary.splitDepth().orElseThrow())
                .containsExactly(3);
    }

    @Test
    void asProcessedSaysSoWhenNothingIsRecordedRatherThanServingAsCurrent() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(scenarios.script(DEGENERATE, DEGENERATE));

        final StageRun run = stage.reprocess(doc(), stream(99, CSV.input()));

        assertThat(run.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) run.decision()).reason())
                .contains("Nothing is recorded for input 99")
                .contains("as-current");
        assertThat(scenarios.ledger.isEmpty())
                .describedAs("and it is not ledgered: the ledger is what a shape's settling releases, and "
                             + "no promotion can answer an input nothing remembers")
                .isTrue();
    }
}
