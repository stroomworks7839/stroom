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
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.processor.api.DeferTaskException;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Snapshots.Snapshot;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 02 §5, scenario 56: **a promotion keeps a snapshot, and a session edits in place** (A58, A61,
/// A70–A72; design 04 §8 scenario 11).
///
/// A rule learned for a shape points at a snapshot of its fragment's configuration. A relearning edits
/// the same fragment — the same pipeline document, the same stylesheet document, the text replaced —
/// and keeps a new snapshot; the deferral stamp moves with it, so a stream held against the old one is
/// scored again. An output records the snapshot that produced it, and an as-processed reprocess runs
/// that snapshot's code, not what the fragment says today. A stylesheet edited by hand is snapshotted the
/// first time the rule serves after the edit. A retracted rule takes its snapshots with it.
class TestScenario56ASnapshotKeepsWhatRan {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String PIPELINE = "pipeline-1";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");

    @Test
    void aRelearningEditsTheFragmentInPlaceAndTheStampMovesWithTheSnapshot() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        final RoutingRule learned = learn(scenarios, doc, LOGON_ONLY);
        final String first = learned.getSnapshot();
        assertThat(first).describedAs("a promotion keeps a snapshot (A61)").isNotNull();
        assertThat(scenarios.snapshots.forRule(learned.getUuid()))
                .extracting(Snapshot::id)
                .containsExactly(first);
        assertThat(scenarios.snapshots.byId(first).orElseThrow().code().values())
                .describedAs("the code each element ran with")
                .anySatisfy(code -> assertThat(code).isEqualTo(LOGON_ONLY))
                .anySatisfy(code -> assertThat(code).isEqualTo(CSV.configuration()));

        // A stream falls short under it and is held against that snapshot.
        final Script silent = Script.of();
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, learned, stream(2L, MIXED), new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        assertThat(scenarios.deferrals.of(DOC, 2L).orElseThrow().failedAgainst()).isEqualTo(first);

        // The worker relearns: the same fragment, edited in place (A58), under a new snapshot.
        final Script relearning = script(scenarios, TRANSLATES_BOTH);
        assertThat(scenarios.worker(relearning).advance(10)).isEqualTo(1);
        relearning.verifyExhausted();
        final RoutingRule rebound = scenarios.rules.forDocument(DOC).get(0);
        assertThat(rebound.getUuid()).isEqualTo(learned.getUuid());
        assertThat(rebound.getPipeline()).describedAs("the same fragment").isEqualTo(learned.getPipeline());
        assertThat(rebound.getSnapshot()).describedAs("a new snapshot").isNotEqualTo(first);
        assertThat(scenarios.stores.pipelines.list()).describedAs("no new fragment was written").hasSize(1);
        assertThat(scenarios.stores.xslts.list()).describedAs("nor a new stylesheet").hasSize(1);
        assertThat(stylesheet(scenarios, rebound.getPipeline())).isEqualTo(TRANSLATES_BOTH);
        assertThat(scenarios.snapshots.forRule(learned.getUuid())).extracting(Snapshot::id)
                .containsExactly(first, rebound.getSnapshot());

        // The stamp moved, so the held stream is scored again, and now clears.
        final StageRun decided = task(scenarios, silent).decide(doc, stream(2L, MIXED));
        assertThat(decided.decision()).describedAs(decided.decision().toString()).isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        assertThat(task(scenarios, silent).stream(doc, ((Bound) decided.decision()).rule(), stream(2L, MIXED), sink)
                .clear()).isTrue();
        assertThat(sink.text()).contains("<Action>Logoff</Action>");
    }

    @Test
    void anAsProcessedReprocessRunsTheSnapshotThatProducedTheOutput() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        final RoutingRule learned = learn(scenarios, doc, LOGON_ONLY);
        final Script silent = Script.of();
        // The first stream was emitted under the first snapshot by the session that learned it; a second
        // stream falls short and the rule is relearned in place.
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, learned, stream(2L, MIXED), new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        final Script relearning = script(scenarios, TRANSLATES_BOTH);
        scenarios.worker(relearning).advance(10);
        relearning.verifyExhausted();
        assertThat(stylesheet(scenarios, learned.getPipeline())).isEqualTo(TRANSLATES_BOTH);

        // As processed: the first stream goes back through the code that produced its output — the
        // logon-only stylesheet — though the fragment now translates both.
        final StageRun again = scenarios.stage(document -> silent, scenarios.rules)
                .reprocess(doc, stream(1L, CSV.input()));
        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Bound.class);
        assertThat(again.bindings().snapshotId()).isEqualTo(learned.getSnapshot());
        assertThat(again.output()).doesNotContain("Logoff");
    }

    @Test
    void aStylesheetEditedByHandIsSnapshottedTheFirstTimeTheRuleServes() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final RoutingRule learned = learn(scenarios, doc, TRANSLATES_BOTH);
        final Script silent = Script.of();

        // Edited by hand: the stylesheet now admits nothing but a logon.
        edit(scenarios, learned.getPipeline(), LOGON_ONLY);
        final StageRun decided = task(scenarios, silent).decide(doc, stream(2L, MIXED));

        final RoutingRule fresh = ((Bound) decided.decision()).rule();
        assertThat(fresh.getSnapshot()).describedAs("a promotion by hand (A72)").isNotEqualTo(learned.getSnapshot());
        assertThat(scenarios.rules.forDocument(DOC).get(0).getSnapshot()).isEqualTo(fresh.getSnapshot());
        assertThat(scenarios.snapshots.byId(fresh.getSnapshot()).orElseThrow().code().values())
                .anySatisfy(code -> assertThat(code).isEqualTo(LOGON_ONLY));
        // And the edit is what serves: the logoffs fall short, against the new stamp.
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, fresh, stream(2L, MIXED), new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        assertThat(scenarios.deferrals.of(DOC, 2L).orElseThrow().failedAgainst()).isEqualTo(fresh.getSnapshot());
        // Served again before anything else changes: no third snapshot.
        task(scenarios, silent).decide(doc, stream(3L, CSV.input()));
        assertThat(scenarios.snapshots.forRule(learned.getUuid())).hasSize(2);
    }

    /// A fragment bound for two shapes — one learned, one bound by looking (design 01 §6) — is not either
    /// rule's alone: a relearning for the second writes it a fragment of its own, and the first goes on
    /// serving with what it had.
    @Test
    void aFragmentSharedWithANeighbouringShapeIsNotEditedInPlace() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy()
                .learningKey(List.of(MetaFields.FIELD_FEED, MetaFields.FIELD_TYPE, RoutingFields.FORMAT))
                .build();
        scenarios.documents.put(doc);
        final Script learning = script(scenarios, LOGON_ONLY);
        final StageRun first = scenarios.process(learning, doc, stream(1L, CSV.input(), "CSV")).learned();
        learning.verifyExhausted();
        final RoutingRule a = ((Promoted) first.decision()).rule();
        // The neighbour, bound by looking to a's fragment.
        final Script silent = Script.of();
        final StageRun second = task(scenarios, silent).decide(doc, stream(2L, CSV.input(), "CSV-v2"));
        final RoutingRule b = ((Promoted) second.decision()).rule();
        assertThat(b.getPipeline()).isEqualTo(a.getPipeline());
        assertThat(b.getSnapshot())
                .describedAs("its own snapshot of the shared fragment")
                .isNotEqualTo(a.getSnapshot());

        // A mixed stream of the neighbour's shape falls short; the worker relearns the neighbour.
        scenarios.inputs.put(stream(3L, MIXED, "CSV-v2"));
        assertThatThrownBy(() -> task(scenarios, silent).stream(doc, b, stream(3L, MIXED, "CSV-v2"), new TextSink()))
                .isInstanceOf(DeferTaskException.class);
        final Script relearning = script(scenarios, TRANSLATES_BOTH);
        assertThat(scenarios.worker(relearning).advance(10)).isEqualTo(1);
        relearning.verifyExhausted();

        final RoutingRule rebound = scenarios.rules.forDocument(DOC).stream()
                .filter(rule -> rule.getUuid().equals(b.getUuid()))
                .findFirst()
                .orElseThrow();
        assertThat(rebound.getPipeline()).describedAs("a fragment of its own").isNotEqualTo(a.getPipeline());
        assertThat(stylesheet(scenarios, a.getPipeline()))
                .describedAs("the neighbour's fragment is as it was")
                .isEqualTo(LOGON_ONLY);
        assertThat(stylesheet(scenarios, rebound.getPipeline())).isEqualTo(TRANSLATES_BOTH);
        assertThat(scenarios.stores.pipelines.list()).hasSize(2);
    }

    @Test
    void aRetractedRuleTakesItsSnapshotsWithIt() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final RoutingRule learned = learn(scenarios, doc, TRANSLATES_BOTH);
        assertThat(scenarios.snapshots.forRule(learned.getUuid())).hasSize(1);

        scenarios.stage(document -> Script.of(), scenarios.rules).retract(doc, learned.getUuid(), "wrong", "jo");

        assertThat(scenarios.snapshots.forRule(learned.getUuid())).isEmpty();
    }

    private static RoutingRule learn(final Scenarios scenarios, final ShapeshifterAiDoc doc, final String xslt) {
        final Script script = script(scenarios, xslt);
        final StageRun run = scenarios.process(script, doc, stream(1L, CSV.input())).learned();
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        return ((Promoted) run.decision()).rule();
    }

    private static Stage task(final Scenarios scenarios, final Script silent) {
        return scenarios.stage(document -> silent, scenarios.rules);
    }

    private static Script script(final Scenarios scenarios, final String xslt) {
        return scenarios.script(CSV.configuration(), xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /// The fragment's stylesheet document.
    private static DocRef stylesheetRef(final Scenarios scenarios, final DocRef fragment) {
        return scenarios.stores.pipelines.readDocument(fragment).getPipelineData().getAddedProperties().stream()
                .map(PipelineProperty::getValue)
                .filter(value -> value != null && value.getEntity() != null
                                 && XsltDoc.TYPE.equals(value.getEntity().getType()))
                .map(value -> value.getEntity())
                .findFirst()
                .orElseThrow();
    }

    private static String stylesheet(final Scenarios scenarios, final DocRef fragment) {
        return scenarios.stores.xslts.readDocument(stylesheetRef(scenarios, fragment)).getData();
    }

    private static void edit(final Scenarios scenarios, final DocRef fragment, final String xslt) {
        final DocRef ref = stylesheetRef(scenarios, fragment);
        scenarios.stores.xslts.writeDocument(scenarios.stores.xslts.readDocument(ref).copy().data(xslt).build());
    }

    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, PIPELINE);
    }

    private static Input stream(final long id, final String data, final String format) {
        return new Input(id, FEED, "Raw Events", Map.of(RoutingFields.FORMAT, format), data, PIPELINE);
    }
}
