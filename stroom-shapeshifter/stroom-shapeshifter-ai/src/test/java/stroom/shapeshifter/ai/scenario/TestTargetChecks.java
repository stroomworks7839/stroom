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


package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Target;
import stroom.shapeshifter.ai.learning.TargetChecks;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.StoredError;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A proposed target is judged as one event: it must be one well-formed event, and only the scorers of
 * meaning weigh it, since a stream's coverage and yield say nothing about one event on its own.
 */
class TestTargetChecks {

    private static final String RECORD = "2020-06-17T08:00:00.000Z,jim,warehouse,logon";
    private static final String EVENT = firstEvent(Scenarios.resource("csv-logon.events.xml"));

    /**
     * The full scorer set of a strict document, with a yield expectation of one event in five lines that
     * no single-line record could meet were it applied.
     */
    private static Scorecard scorecard() {
        return new Scorecard(List.of(
                new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, true, null),
                new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, true, new YieldParameters(0.2, YieldBasis.LINES)),
                new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                        new SchemaConformanceParameters("EVENTS")),
                new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                        new ExtractionQualityParameters(false, List.of("EventSource/User/Id")))),
                new Scenarios().scorers());
    }

    @Test
    void aValidEventIsAcceptedWhateverTheStreamScorersWouldSay() {
        assertThat(TargetChecks.judge(scorecard(), RECORD, TargetChecks.asDocument(EVENT))).isEmpty();
    }

    @Test
    void anXmlDeclarationBeforeTheEventIsSetAside() {
        final String declared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + EVENT;
        assertThat(TargetChecks.asDocument(declared)).doesNotContain("<?xml");
        assertThat(TargetChecks.judge(scorecard(), RECORD, TargetChecks.asDocument(declared))).isEmpty();
    }

    @Test
    void anEventThatDoesNotParseIsRefusedNotPassedUnscored() {
        final String broken = EVENT.replace("</EventTime>", "");
        final List<StoredError> refusal = TargetChecks.judge(scorecard(), RECORD, TargetChecks.asDocument(broken));
        assertThat(refusal).hasSize(1);
        assertThat(refusal.get(0).getMessage()).contains("not well-formed");
    }

    @Test
    void twoEventsForOneRecordAreRefused() {
        final List<StoredError> refusal = TargetChecks.judge(scorecard(), RECORD,
                TargetChecks.asDocument(EVENT + "\n" + EVENT));
        assertThat(refusal).hasSize(1);
        assertThat(refusal.get(0).getMessage()).contains("2 events");
    }

    @Test
    void aMeaninglessEventIsRefusedByTheScorersOfMeaning() {
        // The degeneracy trap of design 01 §8.3: valid, and the record's fields carried as Data in Unknown.
        final String degenerate = String.join("\n",
                "<Event xmlns=\"event-logging:3\">",
                "  <EventTime><TimeCreated>2020-06-17T08:00:00.000Z</TimeCreated></EventTime>",
                "  <EventSource>",
                "    <System><Name>Door Access</Name><Environment>Test</Environment></System>",
                "    <Generator>CSV</Generator>",
                "    <Device><Name>unknown</Name></Device>",
                "  </EventSource>",
                "  <EventDetail>",
                "    <TypeId>record</TypeId>",
                "    <Unknown>",
                "      <Data Name=\"who\" Value=\"jim\"/><Data Name=\"where\" Value=\"warehouse\"/>",
                "      <Data Name=\"what\" Value=\"logon\"/>",
                "    </Unknown>",
                "  </EventDetail>",
                "</Event>");
        final List<StoredError> refusal = TargetChecks.judge(scorecard(), RECORD,
                TargetChecks.asDocument(degenerate));
        // Only extraction quality speaks: no coverage or yield shortfall for one event on its own.
        assertThat(refusal).isNotEmpty();
        assertThat(refusal).allMatch(error -> "ExtractionQuality".equals(error.getElementId().getId())
                                              || error.getMessage().startsWith("Extraction quality"));
    }

    @Test
    void fidelityReadsNamesByNamespaceNotPrefix() {
        // The same tree written with a prefix on every element, as one live transform did.
        final String prefixed = ("<evt:Events xmlns:evt=\"event-logging:3\">" + EVENT + "</evt:Events>")
                .replaceAll("<(/?)([A-Z])", "<$1evt:$2")
                .replace("<evt:Events xmlns:evt", "<evt:Events xmlns:evt");
        final List<Target> targets = List.of(Target.of(RECORD, EVENT));
        assertThat(TargetChecks.fidelity(prefixed, targets)).isEmpty();
    }

    @Test
    void fidelityShowsTheNearestEventBesideTheTarget() {
        final String swapped = EVENT.replace("<Name>warehouse</Name>", "<Name>jim</Name>");
        final List<StoredError> shortfalls = TargetChecks.fidelity(TargetChecks.asDocument(swapped),
                List.of(Target.of(RECORD, EVENT)));
        assertThat(shortfalls).hasSize(1);
        assertThat(shortfalls.get(0).getMessage())
                .contains("Produce exactly:")
                .contains("The nearest event produced was:")
                .contains("<Name>jim</Name>");
    }

    private static String firstEvent(final String events) {
        final int start = events.indexOf("<Event>");
        final int end = events.indexOf("</Event>") + "</Event>".length();
        return events.substring(start, end);
    }
}
