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
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.scoring.Judgement;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Inputs;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.ExecutionMode;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.TextRange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 52: **the records that fell short.**
///
/// Design 04 §3 moves the bar from the stream to the record: a stream is served, or waits, by whether
/// *each* of its records clears every gate (A55), not by a mean over all of them that a few bad
/// records barely move. This scenario is the per-record scoring on its own, before phase 4 makes
/// anything of it — the phase 2 exit of design 04 §7.
///
/// A rule is learned from a stream of logons alone, with a stylesheet that translates a logon fully and
/// leaves anything else as an `Event` with nothing but a `TypeId` in it: a transform that has not met a
/// logoff and does what an honest first draft does with one. A second stream brings six records, two of
/// them logoffs. The stream is served as it always has been — six events, the two thin ones among
/// them — but the verdict now names the two, by index, with the gate each fell short of, and the
/// deferral table has a row for the stream in **shadow**: what phase 4 will put back, recorded now so
/// the backlog can be read before anything changes for a stream.
///
/// The indices are what the spans are kept against (scenario 38), so the record that fell short is
/// also the text that produced it, back out of the stream by its span: what a session will be shown.
class TestScenario52TheRecordsThatFellShort {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String PIPELINE = "pipeline-1";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String LOGON_ONLY_XSLT = Scenarios.resource("csv-logon-only.xsl");
    private static final String FULL_XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");

    @Test
    void theVerdictNamesEachRecordThatFellShortAndTheGateItFellShortOf() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        learn(scenarios, doc);

        final StageRun served = scenarios.stage(document -> Script.of(), scenarios.rules)
                .run(doc, stream(2L, MIXED));

        // Served as it always has been: the rule binds it, and every record is emitted, thin or not.
        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        assertThat(count(served.output(), "<Event>")).isEqualTo(6);
        assertThat(count(served.output(), "<TypeId>logoff</TypeId>"))
                .describedAs("the two logoffs, as the stylesheet leaves them: a TypeId and nothing else")
                .isEqualTo(2);
        assertThat(served.output()).doesNotContain("pete").doesNotContain("sam");

        // The bar is per record (A55): the third and fifth records — pete and sam, the logoffs — are
        // what fell short, and nothing else is, however the mean came out.
        final Verdict transform = served.verdicts().getLast();
        assertThat(transform.failingRecords()).containsExactly(2, 4);

        // And each gate says which records it failed, so the feedback can name the gate as well as the
        // record. An Event with no time and no source fails the schema. Extraction quality lays nothing
        // at them: the stylesheet left them typed and empty rather than Unknown, and the demo document
        // requires no field — which is why the bar is the union of the gates (A55) and not any one.
        assertThat(failing(transform, ScorerType.SCHEMA_CONFORMANCE)).containsExactlyInAnyOrder(2, 4);
        assertThat(failing(transform, ScorerType.EXTRACTION_QUALITY)).isEmpty();
        assertThat(failing(transform, ScorerType.COMPILE))
                .describedAs("a gate that judges the whole step names no record")
                .isEmpty();
    }

    @Test
    void aFailingRecordIsItsOwnTextBackOutOfTheStreamByItsSpan() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        learn(scenarios, doc);
        final StageRun served = scenarios.stage(document -> Script.of(), scenarios.rules)
                .run(doc, stream(2L, MIXED));

        final Inputs inputs = metaId -> Optional.of(stream(metaId, MIXED));
        for (final int index : served.verdicts().getLast().failingRecords()) {
            final Optional<TextRange> span = scenarios.outputs.span(DOC, 2L, PIPELINE, index);
            assertThat(span).describedAs("the span of record " + index).isPresent();
            assertThat(inputs.textOf(2L, span.get()))
                    .describedAs("the text of record " + index)
                    .hasValueSatisfying(text -> assertThat(text).contains("logoff"));
        }
    }

    /// The row is shadow: the stream was emitted, and the row says what phase 4 would have held and
    /// against what, with nothing of the stream's text and nothing about which records (A69) — that is
    /// the verdict's to say, for the one stream a session learns from.
    @Test
    void aStreamWithRecordsShortOfTheBarIsRecordedAsWhatWouldBeDeferred() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final StageRun learned = learn(scenarios, doc);
        assertThat(scenarios.deferrals.forDocument(DOC))
                .describedAs("the stream that learned cleared the bar on every record")
                .isEmpty();

        scenarios.stage(document -> Script.of(), scenarios.rules).run(doc, stream(2L, MIXED));

        final List<Deferral> rows = scenarios.deferrals.forDocument(DOC);
        assertThat(rows).hasSize(1);
        final Deferral row = rows.getFirst();
        assertThat(row.inputId()).isEqualTo(2L);
        assertThat(row.feed()).isEqualTo(FEED);
        assertThat(row.type()).isEqualTo("Raw Events");
        assertThat(row.pipeline()).isEqualTo(PIPELINE);
        assertThat(row.failedAgainst())
                .describedAs("against the fragment the rule names today; a snapshot from phase 4 (A61)")
                .isEqualTo(((Promoted) learned.decision()).rule().getPipeline().getUuid());
        assertThat(row.failingRecords()).isEqualTo(2);
        assertThat(row.reason()).isEqualTo("2 of 6 record(s) fell short of the bar");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(row.taskId()).describedAs("a scenario has no task to wake").isNull();

        // The shape's rolling score fell with that stream, so the next stream of the shape has the rule
        // learned again — here with a stylesheet that translates a logoff too. The stream that brought
        // the relearning still goes out under the incumbent, thin records and all (the rebound is for
        // what follows), so its row stands: in phase 4 this is the stream that would have been held.
        final Script relearn = scenarios.script(CSV.configuration(), FULL_XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser"))
                .reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter"))
                .reply(Scenarios.fenced(FULL_XSLT));
        final StageRun again = scenarios.stage(document -> relearn, scenarios.rules).run(doc, stream(2L, MIXED));
        relearn.verifyExhausted();
        assertThat(again.decision()).describedAs(again.decision().toString()).isInstanceOf(Rebound.class);
        assertThat(again.verdicts().getLast().failingRecords())
                .describedAs("emitted under the incumbent, so the same two records fell short")
                .containsExactly(2, 4);
        assertThat(scenarios.deferrals.forDocument(DOC)).hasSize(1);

        // Reprocessed under the rebound rule — what a wake does in phase 4 — the stream is served with
        // every record clearing the bar, and waits no more: the row goes.
        final StageRun reprocessed = scenarios.stage(document -> Script.of(), scenarios.rules)
                .run(doc, stream(2L, MIXED));
        assertThat(reprocessed.decision()).describedAs(reprocessed.decision().toString()).isInstanceOf(Bound.class);
        assertThat(reprocessed.verdicts().getLast().failingRecords()).isEmpty();
        assertThat(scenarios.deferrals.forDocument(DOC))
                .describedAs("served clean, the stream is off the table")
                .isEmpty();
    }

    /// A stream the document has no rule for, whose attempt is opened and parked for a worker (scenario
    /// 30), is sentinelled today and would wait in phase 4: a row with nothing it failed against.
    @Test
    void aStreamOfAShapeNothingIsLearnedForIsRecordedToo() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = DemoDocument.configure(Scenarios.document())
                .uuid(DOC)
                .name("door-access")
                .executionMode(ExecutionMode.DEFERRED)
                .build();

        scenarios.stage(document -> Script.of(), scenarios.rules).run(doc, stream(3L, MIXED));

        final List<Deferral> rows = scenarios.deferrals.forDocument(DOC);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().inputId()).isEqualTo(3L);
        assertThat(rows.getFirst().failedAgainst()).isNull();
        assertThat(rows.getFirst().failingRecords()).isZero();
        assertThat(rows.getFirst().reason()).contains("Awaiting the model");
    }

    private static StageRun learn(final Scenarios scenarios, final ShapeshifterAiDoc doc) {
        final Script script = scenarios.script(CSV.configuration(), LOGON_ONLY_XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser"))
                .reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter"))
                .reply(Scenarios.fenced(LOGON_ONLY_XSLT));
        final StageRun run = scenarios.stage(document -> script, scenarios.rules)
                .run(doc, stream(1L, CSV.input()));
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(run.verdicts().getLast().failingRecords())
                .describedAs("every logon cleared the bar")
                .isEmpty();
        return run;
    }

    private static Set<Integer> failing(final Verdict verdict, final ScorerType type) {
        return verdict.judgements().stream()
                .filter(judgement -> judgement.setting().getType() == type)
                .map(Judgement::score)
                .map(score -> score.failingRecords())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no judgement by " + type));
    }

    private static int count(final String text, final String token) {
        return text.split(Pattern.quote(token), -1).length - 1;
    }

    /// The demo document, as scenario 51 has it: the settings the content pack ships.
    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document())
                .uuid(DOC)
                .name("door-access")
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, PIPELINE);
    }
}
