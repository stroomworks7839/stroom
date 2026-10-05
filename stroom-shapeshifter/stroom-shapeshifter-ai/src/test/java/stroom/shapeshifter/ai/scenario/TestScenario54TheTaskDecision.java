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
import stroom.processor.api.DeferTaskException;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.RecordJudge;
import stroom.shapeshifter.ai.fragment.ServedStream;
import stroom.shapeshifter.ai.fragment.StandInFragmentRunner;
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.ai.state.InMemoryLedger;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.RoutingRule;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/// Design 02 §5, scenario 54: **the task decision** (design 04 §3; §8's scenarios 1, 2, 3, 7 and 9 at
/// Tier 1; phase 4's first slice).
///
/// A task does one cheap thing — resolve the shape, consult what is known, emit, defer or error — and
/// never learns. A stream of a shape nothing has learned waits, with a session opened on it and parked
/// for the worker; a second stream of the shape waits on that session; nothing is sentinelled to make
/// either wait. The worker carries the session, the promotion wakes what waited, and the next decision
/// on each stream emits it. A stream a rule serves with a record short of the bar waits too, against the
/// transform in force, and is not scored again while that transform stands; a stream of the same shape
/// whose records all clear flows meanwhile. What errors is what nothing will ever learn: a shape given
/// up, a reserved selector.
class TestScenario54TheTaskDecision {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String SHAPE = "Feed=DOOR-ACCESS|Type=Raw Events";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");

    /// §8 scenarios 1, 7 and 9, at the decision: unseen, held, learned, woken, served.
    @Test
    void aStreamOfAnUnseenShapeWaitsOnASessionAndIsServedWhenItPromotes() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final Script silent = Script.of();

        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(1L, MIXED));
        scenarios.inputs.put(stream(2L, MIXED));
        final StageRun first = task(scenarios, silent, null).decide(doc, stream(1L, MIXED));

        assertThat(first.decision()).describedAs(first.decision().toString()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) first.decision()).reason()).contains("Awaiting the model");
        assertThat(silent.asked()).describedAs("no model call in the task").isEmpty();
        assertThat(first.output()).isNull();
        assertThat(scenarios.ledger.isEmpty()).describedAs("nothing is sentinelled to make it wait (§8.7)").isTrue();
        final List<Recorded> attempts = scenarios.attempts.forDocument(DOC, 10);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(attempts.get(0).attempt().inputId()).isEqualTo(1L);
        assertThat(scenarios.deferrals.forDocument(DOC))
                .extracting(Deferral::inputId, Deferral::failedAgainst)
                .containsExactly(tuple(1L, null));

        // A second stream of the shape while the session is open: waits on it, opens nothing.
        final StageRun second = task(scenarios, silent, null).decide(doc, stream(2L, MIXED));
        assertThat(second.decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).describedAs("one session for one shape").hasSize(1);
        assertThat(scenarios.deferrals.forDocument(DOC)).extracting(Deferral::inputId).containsExactly(1L, 2L);

        // The worker carries the session on the stream it was raised on; the promotion wakes both.
        final Script script = script(scenarios, TRANSLATES_BOTH);
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();
        assertThat(scenarios.attempts.byId(attempts.get(0).id()).orElseThrow().status())
                .isEqualTo(AttemptStatus.PROMOTED);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).containsExactly(1L, 2L);

        // Tried again, each is bound and served as it is read, and its row goes.
        for (final long id : List.of(1L, 2L)) {
            final StageRun bound = task(scenarios, silent, null).decide(doc, stream(id, MIXED));
            assertThat(bound.decision()).describedAs(bound.decision().toString()).isInstanceOf(Bound.class);
            final TextSink sink = new TextSink();
            final ServedStream served = task(scenarios, silent, null)
                    .stream(doc, ((Bound) bound.decision()).rule(), stream(id, MIXED), sink);
            assertThat(served.clear()).isTrue();
            assertThat(served.records()).isEqualTo(6);
            assertThat(sink.text()).contains("<Action>Logoff</Action>");
        }
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
        assertThat(silent.asked()).isEmpty();
    }

    /// §8 scenarios 2, 3 and 9: a record short of the bar holds the stream, against the transform in
    /// force; a stream whose records all clear flows meanwhile; the stream is scored once per transform.
    @Test
    void aStreamWithARecordShortOfTheBarWaitsAndIsNotScoredAgainUntilTheTransformMoves() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final RoutingRule learned = learn(scenarios, doc, LOGON_ONLY);
        final AtomicInteger runs = new AtomicInteger();
        final FragmentRunner counting = counting(scenarios, runs);
        final Script silent = Script.of();

        scenarios.documents.put(doc);
        scenarios.inputs.put(stream(2L, MIXED));
        final StageRun bound = task(scenarios, silent, counting).decide(doc, stream(2L, MIXED));
        assertThat(bound.decision()).describedAs("nothing is known until it is read").isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        assertThatThrownBy(() -> task(scenarios, silent, counting).stream(doc, learned, stream(2L, MIXED), sink))
                .isInstanceOf(DeferTaskException.class)
                .hasMessageStartingWith("Record 3 fell short of the bar");
        assertThat(sink.records())
                .describedAs("the two records before it were handed on; nothing after")
                .isEqualTo(2);
        assertThat(runs.get()).describedAs("read once").isEqualTo(1);
        final Deferral row = scenarios.deferrals.of(DOC, 2L).orElseThrow();
        assertThat(row.failedAgainst())
                .describedAs("stamped with the snapshot in force (A61)")
                .isEqualTo(learned.getSnapshot());
        assertThat(row.failingRecords())
                .describedAs("the one the run stopped at; nothing after it was read")
                .isEqualTo(1);
        assertThat(scenarios.shapes.relearnReason(DOC, SHAPE)).isPresent();
        assertThat(scenarios.attempts.forDocument(DOC, 10))
                .describedAs("a relearning session, opened and parked, beside the one that learned the rule")
                .extracting(Recorded::status)
                .containsExactlyInAnyOrder(AttemptStatus.AWAITING_MODEL, AttemptStatus.PROMOTED);

        // Tried again before the transform moves: deferred by the stamp, without a run.
        final StageRun again = task(scenarios, silent, counting).decide(doc, stream(2L, MIXED));
        assertThat(again.decision()).isInstanceOf(Deferred.class);
        assertThat(runs.get()).describedAs("not read again (§8.3)").isEqualTo(1);
        assertThat(scenarios.deferrals.of(DOC, 2L).orElseThrow().attempts()).isEqualTo(2);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).describedAs("no second session").hasSize(2);

        // A stream of the shape whose records all clear flows while the session runs (§8.2).
        final StageRun flowing = task(scenarios, silent, counting).decide(doc, stream(3L, CSV.input()));
        assertThat(flowing.decision()).describedAs(flowing.decision().toString()).isInstanceOf(Bound.class);
        assertThat(task(scenarios, silent, counting).stream(doc, learned, stream(3L, CSV.input()), new TextSink())
                .clear()).isTrue();
        assertThat(scenarios.deferrals.forDocument(DOC)).extracting(Deferral::inputId).containsExactly(2L);

        // The worker relearns on the held stream; the rebinding wakes it; it is served under the new
        // transform.
        final Script script = script(scenarios, TRANSLATES_BOTH);
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).containsExactly(2L);
        final StageRun served = task(scenarios, silent, counting).decide(doc, stream(2L, MIXED));
        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        final RoutingRule rebound = ((Bound) served.decision()).rule();
        assertThat(task(scenarios, silent, counting).stream(doc, rebound, stream(2L, MIXED), new TextSink()).clear())
                .isTrue();
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void whatNothingWillLearnErrorsRatherThanWaits() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.shapes.giveUp(DOC, SHAPE, "the parse could not be learned");

        final StageRun errored = scenarios.stage(document -> Script.of(), scenarios.rules)
                .decide(doc, stream(1L, MIXED));

        assertThat(errored.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) errored.decision()).reason()).contains("given up");
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).isEmpty();
    }

    /// A pinned rule is frozen: served whatever the bar says, and nothing waits on it.
    @Test
    void aPinnedRuleServesItsStreamsWhateverTheBarSays() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        final RoutingRule learned = learn(scenarios, doc, LOGON_ONLY);
        scenarios.rules.replace(DOC, learned.copy().pinned(true).build());

        final StageRun served = scenarios.stage(document -> Script.of(), scenarios.rules)
                .decide(doc, stream(2L, MIXED));

        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        final TextSink sink = new TextSink();
        final ServedStream streamed = scenarios.stage(document -> Script.of(), scenarios.rules)
                .stream(doc, ((Bound) served.decision()).rule(), stream(2L, MIXED), sink);
        assertThat(streamed.clear()).isTrue();
        assertThat(sink.records()).describedAs("every record, the thin ones too").isEqualTo(6);
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10))
                .describedAs("the session that learned it, and no new one")
                .extracting(Recorded::status)
                .containsExactly(AttemptStatus.PROMOTED);
    }

    /// A task's stage: silent, since a task never asks.
    private static Stage task(final Scenarios scenarios, final Script silent, final FragmentRunner runner) {
        return scenarios.stage(document -> silent, scenarios.rules, runner);
    }

    /// A draft awaiting review holds its shape's streams waiting, not erroring, and Approve wakes them.
    @Test
    void aStreamBehindADraftWaitsForTheReviewAndApproveWakesIt() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc().copy().promotionMode(PromotionMode.REVIEW).build();
        final Script script = script(scenarios, TRANSLATES_BOTH);
        final StageRun drafted = scenarios.stage(document -> script, scenarios.rules).run(doc, stream(1L, MIXED));
        script.verifyExhausted();
        assertThat(drafted.decision()).describedAs(drafted.decision().toString()).isInstanceOf(Drafted.class);
        final RoutingRule draft = ((Drafted) drafted.decision()).rule();
        final Script silent = Script.of();

        final StageRun waiting = task(scenarios, silent, null).decide(doc, stream(2L, MIXED));

        assertThat(waiting.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) waiting.decision()).reason()).contains("Awaiting review");
        assertThat(scenarios.deferrals.of(DOC, 2L)).isPresent();
        assertThat(scenarios.ledger.rows())
                .describedAs("the stream that was drafted on is on the ledger, as a session's is; this one is not")
                .extracting(InMemoryLedger.Row::inputId)
                .doesNotContain(2L);

        scenarios.stage(document -> silent, scenarios.rules).approve(doc, draft.getUuid());

        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).containsExactly(2L);
        final StageRun served = task(scenarios, silent, null).decide(doc, stream(2L, MIXED));
        assertThat(served.decision()).describedAs(served.decision().toString()).isInstanceOf(Bound.class);
        task(scenarios, silent, null)
                .stream(doc, ((Bound) served.decision()).rule(), stream(2L, MIXED), new TextSink());
        assertThat(scenarios.deferrals.forDocument(DOC)).isEmpty();
    }

    /// A stream tried again while its session is still open says so, and its row says only why it waits.
    @Test
    void aStreamTriedAgainWhileItsSessionIsOpenKeepsOneReason() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        learn(scenarios, doc, LOGON_ONLY);
        final Script silent = Script.of();
        final RoutingRule rule = scenarios.rules.forDocument(DOC).get(0);
        assertThatThrownBy(() -> task(scenarios, silent, null).stream(doc, rule, stream(2L, MIXED), new TextSink()))
                .isInstanceOf(DeferTaskException.class);

        final StageRun again = task(scenarios, silent, null).decide(doc, stream(2L, MIXED));

        assertThat(((Deferred) again.decision()).reason())
                .startsWith("Record 3 fell short of the bar: ")
                .contains("learning this shape");
        assertThat(scenarios.deferrals.of(DOC, 2L).orElseThrow().reason())
                .describedAs("the row keeps why, not what each try found")
                .startsWith("Record 3 fell short of the bar: ")
                .doesNotContain("learning this shape");
    }

    private static RoutingRule learn(final Scenarios scenarios, final ShapeshifterAiDoc doc, final String xslt) {
        final Script script = script(scenarios, xslt);
        final StageRun run = scenarios.stage(document -> script, scenarios.rules).run(doc, stream(1L, CSV.input()));
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        return ((Promoted) run.decision()).rule();
    }

    private static Script script(final Scenarios scenarios, final String xslt) {
        return scenarios.script(CSV.configuration(), xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /// The harness's runner, counting how many times a fragment is run over a stream.
    private static FragmentRunner counting(final Scenarios scenarios, final AtomicInteger runs) {
        final FragmentRunner standIn = new StandInFragmentRunner(scenarios.stores.pipelines,
                scenarios.stores.stackLoader, scenarios.stores.textConverters, scenarios.stores.xslts,
                scenarios.runners());
        return new FragmentRunner() {
            @Override
            public List<Attempted> run(final DocRef fragment, final String input, final RecordBoundary boundary) {
                runs.incrementAndGet();
                return standIn.run(fragment, input, boundary);
            }

            @Override
            public ServedStream serve(final DocRef fragment,
                                      final InputStream input,
                                      final RecordBoundary boundary,
                                      final RecordJudge judge,
                                      final ContentHandler downstream) {
                runs.incrementAndGet();
                return standIn.serve(fragment, input, boundary, judge, downstream);
            }
        };
    }

    private static ShapeshifterAiDoc doc() {
        return DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").heldOutFraction(0.0).build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, FEED, "Raw Events", Map.of(), data, "pipeline-1");
    }
}
