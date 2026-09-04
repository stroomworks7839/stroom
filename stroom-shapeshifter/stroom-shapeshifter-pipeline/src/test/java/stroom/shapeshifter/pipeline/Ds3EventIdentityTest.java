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

package stroom.shapeshifter.pipeline;

import stroom.pipeline.errorhandler.LoggingErrorReceiver;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.ds3.Ds3Migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Design 21 phase 1's oracle: for every legacy fixture, the events the shapeshifter parser
 * forwards for the migrated configuration are the events Stroom's own DS3 emits for the original,
 * under the three normalisations {@link EventRecorder} names.
 *
 * <p>The fixture ledger is honoured the way the engine's golden suite honours it: a {@code PASS}
 * fixture must agree, a {@code PENDING} one must still disagree — that is the ratchet — and the
 * one config the ledger says must be rejected must be rejected by the migration.
 */
class Ds3EventIdentityTest {

    private static final Path LEGACY = Paths.get(
            "..", "stroom-shapeshifter-engine", "src", "test", "resources", "fixtures", "legacy");
    private static final Path LEDGER = LEGACY.resolveSibling("status.txt");

    /**
     * Fixtures the byte ledger holds as {@code PENDING} whose remaining difference is invisible to
     * events, and so must still agree here. Empty since design 21 phase 3 closed E33, E34 and
     * E35; kept because the ratchet's two directions are the point of it.
     */
    private static final Map<String, String> EVENT_IDENTICAL_WHILE_BYTE_PENDING = Map.of();

    /**
     * The reverse: fixtures the byte ledger holds as {@code PASS} whose events nonetheless
     * differ from live DS3's. Empty since phase 3; each entry must keep differing while listed.
     */
    private static final Map<String, String> EVENT_DIFFERS_WHILE_BYTE_PASS = Map.of();

    @TestFactory
    Stream<DynamicTest> legacyFixtures() throws IOException {
        final Map<String, String> ledger = ledger();
        try (Stream<Path> configs = Files.list(LEGACY)) {
            return configs
                    .filter(p -> p.getFileName().toString().endsWith(".ds3.xml"))
                    .map(p -> p.getFileName().toString().replace(".ds3.xml", ""))
                    .sorted()
                    .map(stem -> DynamicTest.dynamicTest(stem, () -> check(stem, ledger.get(stem))))
                    .collect(Collectors.toList())
                    .stream();
        }
    }

    private static void check(final String stem, final String status) throws Exception {
        assertThat(status).as("ledger entry for " + stem).isNotNull();
        switch (status) {
            case "SKIPPED", "QUARANTINED" -> Assumptions.abort(stem + " is " + status);
            case "PASS", "PENDING" -> {
            }
            default -> fail("Unhandled ledger status " + status);
        }

        final String config = Files.readString(LEGACY.resolve(stem + ".ds3.xml"));
        final byte[] input = Files.readAllBytes(LEGACY.resolve(stem + ".in"));

        final Project project;
        try {
            project = Ds3Migration.importXml(config);
        } catch (final RuntimeException rejected) {
            assertThat(stem)
                    .as("only the ledger's must-be-rejected fixture may be refused: " + rejected.getMessage())
                    .isEqualTo("008_invalid_xml_FAIL");
            return;
        }

        final List<String> ours = shapeshifter(project, input);
        final List<String> theirs = ds3(config, input);

        final boolean mustAgree = EVENT_IDENTICAL_WHILE_BYTE_PENDING.containsKey(stem)
                                  || (status.equals("PASS") && !EVENT_DIFFERS_WHILE_BYTE_PASS.containsKey(stem));
        if (mustAgree) {
            assertThat(firstDifference(ours, theirs)).as(stem + ": events forwarded versus DS3's").isNull();
        } else if (ours.equals(theirs)) {
            fail(stem + " now produces DS3's events. Promote it in fixtures/status.txt or remove it from "
                 + "EVENT_DIFFERS_WHILE_BYTE_PASS — whichever ledger held it back — so the record moves.");
        }
    }

    /**
     * The first event at which the two streams part, with two of context either side, or null if
     * they do not. A whole-list assertion on a hundred events says nothing a reader can use.
     */
    private static String firstDifference(final List<String> ours, final List<String> theirs) {
        final int n = Math.max(ours.size(), theirs.size());
        for (int i = 0; i < n; i++) {
            final String a = i < ours.size() ? ours.get(i) : "<end>";
            final String b = i < theirs.size() ? theirs.get(i) : "<end>";
            if (!a.equals(b)) {
                final StringBuilder out = new StringBuilder("event " + i + " differs\n");
                for (int j = Math.max(0, i - 2); j <= Math.min(n - 1, i + 2); j++) {
                    out.append(j == i ? " >> " : "    ")
                            .append("ours:   ").append(j < ours.size() ? ours.get(j) : "<end>").append('\n')
                            .append(j == i ? " >> " : "    ")
                            .append("DS3:    ").append(j < theirs.size() ? theirs.get(j) : "<end>").append('\n');
                }
                return out.toString();
            }
        }
        return null;
    }

    private static List<String> shapeshifter(final Project project, final byte[] input) throws Exception {
        final EventRecorder recorder = new EventRecorder();
        final XMLReader parser = new ShapeshifterParserFactory(project).getParser();
        parser.setContentHandler(recorder);
        parser.setErrorHandler(Ds3Oracle.errorHandler("ShapeshifterParser", new LoggingErrorReceiver()));
        parser.parse(new InputSource(new ByteArrayInputStream(input)));
        return recorder.events();
    }

    private static List<String> ds3(final String config, final byte[] input) throws Exception {
        final EventRecorder recorder = new EventRecorder();
        final XMLReader parser = Ds3Oracle.parser(config);
        parser.setContentHandler(recorder);
        parser.setErrorHandler(Ds3Oracle.errorHandler("DS3Parser", new LoggingErrorReceiver()));
        parser.parse(new InputSource(new InputStreamReader(new ByteArrayInputStream(input), StandardCharsets.UTF_8)));
        return recorder.events();
    }

    /** {@code legacy/<stem>  <STATUS>  note} lines of the engine's fixture ledger. */
    private static Map<String, String> ledger() throws IOException {
        return Files.readAllLines(LEDGER).stream()
                .filter(line -> line.startsWith("legacy/"))
                .map(line -> line.split("\\s+"))
                .collect(Collectors.toMap(parts -> parts[0].substring("legacy/".length()), parts -> parts[1]));
    }
}
