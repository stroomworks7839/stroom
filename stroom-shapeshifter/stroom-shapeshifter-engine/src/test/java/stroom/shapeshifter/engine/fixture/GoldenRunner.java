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

import stroom.shapeshifter.engine.fixture.EngineHarness.Message;
import stroom.shapeshifter.engine.fixture.EngineHarness.Outcome;
import stroom.shapeshifter.engine.fixture.FixtureLedger.Fixture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs one fixture and says, in one string, why it did not match — or nothing, if it did.
 *
 * <p>The runner never asserts. It returns a verdict, and the suites decide what a verdict means
 * given the fixture's ledger status: for {@code PASS} a failure is a failure, for {@code PENDING}
 * a <i>success</i> is the failure, because it means the ledger is out of date.
 *
 * <p>Legacy fixtures are compared on two axes, not one. The output is checked against the golden
 * {@code .out.xml} that came from Java Stroom, and the engine's messages are checked against a
 * {@code .messages} golden. The Rust suite asserts only on output, leaving the warning and error
 * paths dark; lighting them is the one deliberate deviation this port makes, and it is
 * test-side (D33).
 */
public final class GoldenRunner {

    private GoldenRunner() {
    }

    /**
     * Run a fixture.
     *
     * @return the reason it did not match, or empty if it matched everything expected of it
     */
    public static Optional<String> run(final Fixture fixture) {
        try {
            return switch (fixture.family()) {
                case LEGACY -> runLegacy(fixture);
                case NATIVE -> runNative(fixture);
                case PROJECTS -> runProjects(fixture);
            };
        } catch (final PortPendingException e) {
            return Optional.of("not ported: " + e.getMessage());
        } catch (final RuntimeException e) {
            return Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------
    // The three families
    // -----------------------------------------------------------------------------------

    private static Optional<String> runLegacy(final Fixture fixture) {
        final String config = FixtureLedger.text("legacy/" + fixture.name() + ".ds3.xml");

        // One fixture has no golden output because its config is meant to be rejected. Its
        // whole expectation is the rejection, so there is nothing to run and nothing to diff.
        if (!FixtureLedger.exists("legacy/" + fixture.name() + ".out.xml")) {
            try {
                EngineHarness.importDs3(config);
                return Optional.of("config was accepted; the fixture expects it to be rejected");
            } catch (final PortPendingException e) {
                throw e;
            } catch (final RuntimeException e) {
                return Optional.empty();
            }
        }

        final Outcome outcome = EngineHarness.runDs3(
                config, FixtureLedger.bytes("legacy/" + fixture.name() + ".in"));

        final List<String> problems = new ArrayList<>();
        compareOutput(outcome, "legacy/" + fixture.name() + ".out.xml").ifPresent(problems::add);
        compareMessages(outcome, "legacy/" + fixture.name() + ".messages").ifPresent(problems::add);
        return problems.isEmpty()
                ? Optional.empty()
                : Optional.of(String.join("; ", problems));
    }

    private static Optional<String> runNative(final Fixture fixture) {
        // The native fixtures are project.json rewrites of the legacy configs, so they reuse the
        // legacy inputs and the same Stroom-produced goldens.
        final Outcome outcome = EngineHarness.runProject(
                FixtureLedger.text("native/" + fixture.name() + "/project.json"),
                FixtureLedger.bytes("legacy/" + fixture.name() + ".in"));
        return compareOutput(outcome, "legacy/" + fixture.name() + ".out.xml");
    }

    private static Optional<String> runProjects(final Fixture fixture) {
        final String dir = "projects/" + fixture.name() + "/";
        final String project = FixtureLedger.text(dir + "project.json");

        // Binary inputs are addressed as a whole: the progressive fixtures use absolute and
        // backward seeks, which only mean anything over a buffer the engine can address.
        final Outcome outcome;
        if (FixtureLedger.exists(dir + "input.bin")) {
            outcome = EngineHarness.runProjectWholeBuffer(project, FixtureLedger.bytes(dir + "input.bin"));
        } else {
            final String input = FixtureLedger.exists(dir + "input.txt") ? "input.txt" : "input.xml";
            outcome = EngineHarness.runProject(project, FixtureLedger.bytes(dir + input));
        }
        return compareOutput(outcome, dir + "example_output.xml");
    }

    // -----------------------------------------------------------------------------------
    // Comparison
    // -----------------------------------------------------------------------------------

    private static Optional<String> compareOutput(final Outcome outcome, final String goldenPath) {
        final String actual = new String(outcome.output(), StandardCharsets.UTF_8);
        final String expected = FixtureLedger.text(goldenPath);
        if (actual.equals(expected)) {
            return Optional.empty();
        }
        return Optional.of("output: " + firstDifference(expected, actual));
    }

    private static Optional<String> compareMessages(final Outcome outcome, final String goldenPath) {
        if (!FixtureLedger.exists(goldenPath)) {
            return Optional.of("missing messages golden: " + goldenPath);
        }
        final List<String> expected = FixtureLedger.text(goldenPath).lines().filter(l -> !l.isEmpty()).toList();
        final List<String> actual = outcome.messages().stream().map(Message::toString).toList();
        if (actual.equals(expected)) {
            return Optional.empty();
        }
        if (actual.size() != expected.size()) {
            return Optional.of("messages: expected " + expected.size() + ", got " + actual.size());
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!actual.get(i).equals(expected.get(i))) {
                return Optional.of("messages: line " + (i + 1)
                                   + " expected [" + expected.get(i) + "] but was [" + actual.get(i) + "]");
            }
        }
        return Optional.empty();
    }

    /** The first line where two texts diverge, reported the way a person reads a diff. */
    private static String firstDifference(final String expected, final String actual) {
        final List<String> expectedLines = expected.lines().toList();
        final List<String> actualLines = actual.lines().toList();
        final int shared = Math.min(expectedLines.size(), actualLines.size());
        for (int i = 0; i < shared; i++) {
            if (!expectedLines.get(i).equals(actualLines.get(i))) {
                return "line " + (i + 1)
                       + " expected [" + expectedLines.get(i) + "] but was [" + actualLines.get(i) + "]";
            }
        }
        if (expectedLines.size() != actualLines.size()) {
            return "expected " + expectedLines.size() + " lines, got " + actualLines.size();
        }
        return "trailing whitespace differs";
    }
}
