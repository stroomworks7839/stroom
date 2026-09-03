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

package stroom.shapeshifter.engine.fixture;

import stroom.shapeshifter.engine.fixture.FixtureLedger.Family;
import stroom.shapeshifter.engine.fixture.FixtureLedger.Fixture;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The three golden suites, driven by the ledger.
 *
 * <p>Each fixture is asserted against the state {@code fixtures/status.txt} records for it,
 * which makes the suite a ratchet rather than a scoreboard: a {@code PENDING} fixture that
 * starts passing <b>fails</b>, with a message telling you to promote it. Every phase of the
 * work therefore has to move a number on purpose, and no phase can quietly lose one.
 *
 * <p>The legacy family's goldens are Stroom's own DS3 output (D41). Three fixtures are skipped
 * for deferred binary formats; four {@code projects} goldens found wrong when the corpus was
 * first audited have since been fixed at the configuration and re-frozen under review (E6–E8,
 * E16).
 */
class GoldenFixtureTest {

    @TestFactory
    List<DynamicTest> legacy() {
        return suite(Family.LEGACY);
    }

    @TestFactory
    List<DynamicTest> nativeProjects() {
        return suite(Family.NATIVE);
    }

    @TestFactory
    List<DynamicTest> projects() {
        return suite(Family.PROJECTS);
    }

    private static List<DynamicTest> suite(final Family family) {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture : FixtureLedger.of(family)) {
            tests.add(DynamicTest.dynamicTest(fixture.name(), () -> check(fixture)));
        }
        return tests;
    }

    private static void check(final Fixture fixture) {
        switch (fixture.status()) {
            case SKIPPED -> org.junit.jupiter.api.Assumptions.abort(
                    fixture.id() + " skipped: " + fixture.note());
            case QUARANTINED -> org.junit.jupiter.api.Assumptions.abort(
                    fixture.id() + " quarantined: " + fixture.note());
            case PASS -> {
                final Optional<String> failure = GoldenRunner.run(fixture);
                if (failure.isPresent()) {
                    fail(fixture.id() + " regressed — " + failure.get());
                }
            }
            case PENDING -> {
                final Optional<String> failure = GoldenRunner.run(fixture);
                if (failure.isEmpty()) {
                    fail(fixture.id() + " now passes. Promote it to PASS in fixtures/status.txt "
                         + "— the ledger is how progress gets recorded.");
                }
            }
            default -> throw new IllegalStateException("Unhandled status: " + fixture.status());
        }
    }

    /**
     * The count, printed every run so a build log says where the port has got to.
     *
     * <p>It also checks the ledger against the corpus on disk, so a fixture cannot be vendored
     * and then forgotten, or listed and then deleted.
     */
    @Test
    void ledgerCoversTheCorpusAndReportsProgress() {
        final List<Fixture> all = FixtureLedger.all();
        assertThat(all).as("the ledger must not be empty").isNotEmpty();

        for (final Fixture fixture : all) {
            final String probe = switch (fixture.family()) {
                case LEGACY -> "legacy/" + fixture.name() + ".ds3.xml";
                case NATIVE -> "native/" + fixture.name() + "/project.json";
                case PROJECTS -> "projects/" + fixture.name() + "/project.json";
            };
            assertThat(FixtureLedger.exists(probe))
                    .as("%s is in the ledger but %s is not vendored", fixture.id(), probe)
                    .isTrue();
        }

        final Map<FixtureLedger.Status, Long> counts = all.stream()
                .collect(Collectors.groupingBy(Fixture::status, Collectors.counting()));
        final long passing = counts.getOrDefault(FixtureLedger.Status.PASS, 0L);
        final long pending = counts.getOrDefault(FixtureLedger.Status.PENDING, 0L);

        System.out.printf(
                "Shapeshifter engine port: %d/%d fixtures passing, %d pending, %d skipped, %d quarantined%n",
                passing, passing + pending, pending,
                counts.getOrDefault(FixtureLedger.Status.SKIPPED, 0L),
                counts.getOrDefault(FixtureLedger.Status.QUARANTINED, 0L));
    }
}
