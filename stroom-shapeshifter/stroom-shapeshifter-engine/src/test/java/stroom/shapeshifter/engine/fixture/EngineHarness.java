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

import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The single place the fixture suites touch the engine.
 *
 * <p>Every golden runner goes through these four methods, so the production API can take
 * whatever shape each phase of the port decides without the corpus tests moving. The ones the
 * port has not reached yet throw {@link PortPendingException}, so that "not written" and
 * "written and wrong" stay different words.
 *
 * <p>Filling these in is what each phase of the port does. See
 * {@code design/07-engine-port-plan.md}.
 */
public final class EngineHarness {

    private EngineHarness() {
    }

    /** A message the engine emitted during a run — severity and text, in emission order. */
    public record Message(String severity, String text) {

        @Override
        public String toString() {
            return severity + "\t" + text;
        }
    }

    /** What a run produced: the output bytes, and the messages raised on the way. */
    public record Outcome(byte[] output, List<Message> messages) {
    }

    /**
     * Run a {@code project.json} configuration over streamed input, as the engine would in a
     * pipeline: fixed-size chunks, matches never spanning a chunk boundary (D33).
     */
    public static Outcome runProject(final String projectJson, final byte[] input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<stroom.shapeshifter.engine.Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(projectJson)),
                new ByteArrayInputStream(input),
                OutputSink.of(output));
        return outcome(output, messages);
    }

    /**
     * Run a {@code project.json} configuration over the whole input as a single buffer. The
     * progressive fixtures need this: absolute and backward seeks are only meaningful when the
     * whole input is addressable.
     */
    public static Outcome runProjectWholeBuffer(final String projectJson, final byte[] input) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<stroom.shapeshifter.engine.Message> messages = Shapeshifter.runWhole(
                Shapeshifter.compile(ProjectReader.read(projectJson)), input, OutputSink.of(output));
        return outcome(output, messages);
    }

    /** Import a DS3 XML configuration and run it over streamed input. */
    public static Outcome runDs3(final String ds3Xml, final byte[] input) {
        throw new PortPendingException("DS3 XML import is not ported yet");
    }

    /**
     * Import a DS3 XML configuration and discard it. Used by the one fixture whose expectation
     * is that the config is rejected, so there is nothing to run.
     */
    public static void importDs3(final String ds3Xml) {
        throw new PortPendingException("DS3 XML import is not ported yet");
    }

    private static Outcome outcome(final ByteArrayOutputStream output,
                                   final List<stroom.shapeshifter.engine.Message> messages) {
        return new Outcome(
                output.toByteArray(),
                messages.stream()
                        .map(m -> new Message(name(m.severity()), m.text()))
                        .toList());
    }

    /** The goldens spell severities the way the Rust engine's message dump does. */
    private static String name(final stroom.shapeshifter.engine.Severity severity) {
        final String name = severity.name();
        return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
