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

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.scenario.LiveAdvisor.Turn;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PlanExample;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.logging.AsciiTable;
import stroom.util.logging.AsciiTable.Column;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live smoke of design 02 §6.1: the scenarios' machinery driven by a real model in place of a
 * script, to learn what no script can say — whether the questions as put draw replies the grammar
 * accepts, whether the feedback steers a second candidate to a passing one within the candidate limit,
 * and what an attempt costs. The results are a report, not a verdict: this test fails only if the
 * harness itself breaks. It runs only when the environment names an endpoint (see {@link LiveAdvisor}),
 * and writes each run's transcript under {@code build/live/<plan>} for the write-up.
 */
@EnabledIfEnvironmentVariable(named = LiveAdvisor.BASE_URL, matches = ".+")
class TestLiveScenarios {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestLiveScenarios.class);
    /**
     * The candidate limit for the run, to measure how many attempts a strict schema costs a model; the
     * document's default when unset.
     */
    private static final String MAX_ATTEMPTS = "SHAPESHIFTER_LIVE_MAX_ATTEMPTS";
    /**
     * The example plan the run's documents carry as their steps — {@code DIRECT} (A21), {@code TARGET_FIRST}
     * (A31) or {@code ESCALATING} (A37) — so that plans can be compared on the same model and feeds; a new
     * document's when unset.
     */
    private static final String PLAN = "SHAPESHIFTER_LIVE_PLAN";
    private static final PlanExample PLAN_UNDER_TEST = Optional.ofNullable(System.getenv(PLAN))
            .map(PlanExample::valueOf)
            .orElse(PlanExample.DIRECT);
    private static final Path OUT = Paths.get("build", "live", PLAN_UNDER_TEST.name().toLowerCase());
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final Golden REGEX = Scenarios.corpus("004_simple_regex");
    private static final Golden MULTI_LINE = Scenarios.corpus("003_multiline_regex");
    private static final String NESTED_XML = Scenarios.resource("nested-audit.xml");
    private static final String INSTRUCTIONS = """
            The feed is door-access records from a building's badge readers: who went where and what \
            they did, when. Events should name the person as the user and the reader's location as \
            the device.""";

    /**
     * The document as an operator would set it up for a feed like this: the full scorer set, the fields the
     * business needs, and the floor at its default.
     */
    private static ShapeshifterAiDoc doc(final String name, final double coverageThreshold) {
        final ShapeshifterAiDoc.Builder builder = ShapeshifterAiDoc.builder()
                .uuid("live-" + name)
                .name(name)
                .learningMode(LearningMode.AUTOMATIC)
                .plan(PLAN_UNDER_TEST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .instructions(INSTRUCTIONS)
                .minRecordsPerShape(5)
                .promotionFloor(0.85);
        Optional.ofNullable(System.getenv(MAX_ATTEMPTS)).map(Integer::valueOf).ifPresent(builder::maxAttempts);
        return builder
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, coverageThreshold, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false,
                                        List.of("EventSource/User/Id", "EventSource/Device/Name"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 0.5, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    @Test
    void theScenariosAgainstARealModel() throws IOException {
        Files.createDirectories(OUT);
        final List<Outcome> outcomes = new ArrayList<>();

        // Scenario 1: the corpus's CSV with its header line, the default key.
        outcomes.add(run("01-csv-with-header", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.stage(advisor)
                    .run(doc("csv-with-header", 0.8), stream(1, CSV.input())));
        }));

        // A headerless CSV against the full scorer set: does a first candidate mean something, or does
        // it fall into the degeneracy trap and get steered out of it?
        outcomes.add(run("02-csv-meaning", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.stage(advisor)
                    .run(doc("csv-meaning", 0.9), stream(1, CsvLines.lines(7))));
        }));

        // Two record kinds in one stream, coverage at 0.9: the splitter must take both.
        outcomes.add(run("03-two-record-kinds", advisor -> {
            final Scenarios scenarios = new Scenarios();
            return List.of(scenarios.stage(advisor).run(doc("two-kinds", 0.9),
                    stream(1, CsvLines.lines(20, "user", 3))));
        }));

        // Scenario 27 live: learn the plain feed, watch the score fall when alarms appear, relearn.
        outcomes.add(run("04-relearn", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("relearn", 0.9).copy().relearnThreshold(0.8).build();
            final List<StageRun> runs = new ArrayList<>();
            runs.add(scenarios.stage(advisor).run(doc, stream(1, CsvLines.lines(7))));
            runs.add(scenarios.stage(advisor)
                    .run(runs.get(0).doc(), stream(2, CsvLines.lines(20, "user", 3))));
            runs.add(scenarios.stage(advisor)
                    .run(runs.get(1).doc(), stream(3, CsvLines.lines(20, "user", 3))));
            return runs;
        }));

        // A different text shape from the corpus: "123 [abc] This is some text".
        outcomes.add(run("05-regex-corpus", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("regex", 0.9).copy()
                    .instructions("Lines of a number, a bracketed type and free text: a system's own log.")
                    .minRecordsPerShape(2)
                    .build();
            return List.of(scenarios.stage(advisor).run(doc,
                    new Input(1, "SYSLOG-LIKE", "Raw Events", Map.of(), REGEX.input())));
        }));

        // The feeds A31 was designed for (design 02 §6.3): records of several lines, where a splitter can lose
        // fields before the model sees them; and nested XML, where the record is already a tree.
        outcomes.add(run("06-multiline-audit", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("multiline-audit", 0.9).copy()
                    .instructions("Linux audit records: blocks separated by a line of four dashes, a block of "
                                  + "several lines being one record whose lines share the msg=audit(...) id. Events "
                                  + "should name the auid or uid as the user and the node as the device.")
                    .minRecordsPerShape(2)
                    .build();
            return List.of(scenarios.stage(advisor).run(doc,
                    new Input(1, "LINUX-AUDIT", "Raw Events", Map.of(), MULTI_LINE.input())));
        }));
        outcomes.add(run("07-nested-xml", advisor -> {
            final Scenarios scenarios = new Scenarios();
            final ShapeshifterAiDoc doc = doc("nested-xml", 0.9).copy()
                    .instructions("An application's audit log as XML, one entry per thing a person did. Events "
                                  + "should name the login as the user, the host as the device, and the document "
                                  + "opened where there is one.")
                    .minRecordsPerShape(2)
                    .build();
            return List.of(scenarios.stage(advisor).run(doc,
                    new Input(1, "DOCVAULT-AUDIT", "Raw Events", Map.of("Format", "XML"), NESTED_XML)));
        }));

        LOGGER.info("Live scenarios against {}, {}:\n{}", System.getenv(LiveAdvisor.MODEL), PLAN_UNDER_TEST,
                AsciiTable.builder(outcomes)
                        .withColumn(Column.of("Run", (Outcome o) -> o.name()))
                        .withColumn(Column.of("Decisions", (Outcome o) -> o.decisions()))
                        .withColumn(Column.integer("Questions", (Outcome o) -> o.questions()))
                        .withColumn(Column.of("Score", (Outcome o) -> o.score()))
                        .withColumn(Column.integer("Tokens", (Outcome o) -> (int) o.tokens()))
                        .withColumn(Column.integer("Seconds", (Outcome o) -> (int) (o.millis() / 1000)))
                        .withColumn(Column.of("Failure", (Outcome o) -> o.failure()))
                        .build());
        Files.writeString(OUT.resolve("report.md"), report(outcomes), StandardCharsets.UTF_8);
        assertThat(outcomes).describedAs("the harness ran every scenario").hasSize(7);
    }

    private Outcome run(final String name, final Function<LiveAdvisor, List<StageRun>> scenario) {
        // The advisor speaks for a document like the scenarios': the same instructions and scorer demands.
        final LiveAdvisor advisor = LiveAdvisor.fromEnvironment(doc(name, 0.9)).orElseThrow();
        List<StageRun> runs = List.of();
        String failure = "";
        try {
            runs = scenario.apply(advisor);
        } catch (final RuntimeException e) {
            // The harness's own failure is the finding: recorded, and the other runs go on.
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.warn("Live run {} failed", name, e);
        }
        final Outcome outcome = new Outcome(name, runs, advisor.turns(), advisor.tokens(), advisor.millis(), failure);
        transcript(outcome);
        return outcome;
    }

    private void transcript(final Outcome outcome) {
        final StringBuilder text = new StringBuilder("# " + outcome.name() + "\n\n");
        text.append("Model: ").append(System.getenv(LiveAdvisor.MODEL))
                .append(", plan ").append(PLAN_UNDER_TEST).append("\n\n");
        text.append("Decisions: ").append(outcome.decisions()).append("\n\n");
        if (!outcome.failure().isEmpty()) {
            text.append("Failure: ").append(outcome.failure()).append("\n\n");
        }
        int i = 0;
        for (final Turn turn : outcome.turns()) {
            text.append("## Turn ").append(++i).append(" — ").append(turn.kind())
                    .append(" (").append(turn.tokens()).append(" tokens, ").append(turn.millis()).append(" ms)\n\n");
            text.append("### Asked\n\n").append(turn.prompt()).append("\n\n");
            text.append("### Replied\n\n").append(turn.reply()).append("\n\n");
        }
        for (final StageRun run : outcome.runs()) {
            text.append("## Verdicts for decision ").append(describe(run.decision())).append("\n\n");
            if (run.decision() instanceof final GivenUp givenUp) {
                givenUp.diagnostics().forEach(error -> text.append("- ").append(error.getSeverity().getDisplayValue())
                        .append(": ").append(error.getMessage()).append('\n'));
            }
            run.verdicts().forEach(verdict -> verdict.judgements().forEach(judgement -> text.append("- ")
                    .append(judgement.setting().getType().getDisplayValue()).append(": ")
                    .append(judgement.score().value()).append('\n')));
            text.append('\n');
        }
        try {
            Files.writeString(OUT.resolve(outcome.name() + ".md"), text.toString(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String report(final List<Outcome> outcomes) {
        final StringBuilder text = new StringBuilder("# Live scenarios — " + System.getenv(LiveAdvisor.MODEL));
        text.append(", plan ").append(PLAN_UNDER_TEST).append("\n\n");
        text.append("| Run | Decisions | Questions | Score | Tokens | Seconds | Failure |\n");
        text.append("|---|---|---|---|---|---|---|\n");
        for (final Outcome o : outcomes) {
            text.append("| ").append(o.name()).append(" | ").append(o.decisions()).append(" | ").append(o.questions())
                    .append(" | ").append(o.score()).append(" | ").append(o.tokens()).append(" | ")
                    .append(o.millis() / 1000).append(" | ").append(o.failure()).append(" |\n");
        }
        return text.toString();
    }

    private static String describe(final Decision decision) {
        return switch (decision) {
            case Promoted p -> "Promoted " + round(p.score());
            case Provisional p -> "Provisional " + round(p.score());
            case Rebound r -> "Rebound " + round(r.score());
            case Kept k -> "Kept: " + k.reason();
            case GivenUp g -> "GivenUp: " + g.reason();
            case Bound b -> "Bound";
            default -> decision.getClass().getSimpleName();
        };
    }

    private static String round(final double value) {
        return String.format("%.3f", value);
    }

    private record Outcome(String name, List<StageRun> runs, List<Turn> turns, long tokens, long millis,
                           String failure) {

        String decisions() {
            return String.join("; ", runs.stream().map(run -> describe(run.decision())).toList());
        }

        int questions() {
            return turns.size();
        }

        String score() {
            return runs.isEmpty()
                    ? ""
                    : describe(runs.get(runs.size() - 1).decision());
        }
    }
}
