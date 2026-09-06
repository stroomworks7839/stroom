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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.SaxEventSink;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instrumentation seam, and whether what it reports is true.
 *
 * <p>An interface nobody implements is easy to get wrong, because nothing notices. So this
 * implements one and checks the numbers: that a reported offset really points at the bytes that
 * matched, that a reported output range really contains what the template wrote, and that content
 * with no position in the input is reported as having none rather than as having a plausible one.
 */
class InstrumentTest {

    private record Match(UUID template, String name, long offset, int length, int index, int depth) {

    }

    private record Capture(String name, String value, int index) {

    }

    private record Output(int index, long offset, long length) {

    }

    private record UnitOutput(int index, long offset, long length, OutputSink.Unit unit) {

    }

    /** Keeps everything it is told, which is what an editor would do. */
    private static final class Recorder implements Instrument {

        private final List<Match> matches = new ArrayList<>();
        private final List<Capture> captures = new ArrayList<>();
        private final List<Output> outputs = new ArrayList<>();
        private final List<UnitOutput> unitOutputs = new ArrayList<>();
        private final List<byte[]> unlocatable = new ArrayList<>();
        private int attempts;

        @Override
        public void onMatch(final UUID templateId, final String templateName, final long inputOffset,
                            final int inputLength, final int matchIndex, final int depth) {
            matches.add(new Match(templateId, templateName, inputOffset, inputLength, matchIndex, depth));
        }

        @Override
        public void onCapture(final UUID templateId, final String name, final byte[] value,
                              final int matchIndex) {
            captures.add(new Capture(name, new String(value, StandardCharsets.UTF_8), matchIndex));
        }

        @Override
        public void onMatchContent(final UUID templateId, final byte[] content) {
            unlocatable.add(content);
        }

        @Override
        public void onOutput(final UUID templateId, final int matchIndex, final long outputOffset,
                             final long outputLength, final OutputSink.Unit unit) {
            outputs.add(new Output(matchIndex, outputOffset, outputLength));
            unitOutputs.add(new UnitOutput(matchIndex, outputOffset, outputLength, unit));
        }

        @Override
        public long startTiming() {
            attempts++;
            return System.nanoTime();
        }

        @Override
        public void stopTiming(final UUID templateId, final long token, final boolean matched) {
            assertThat(token).as("the token the engine hands back must be the one it was given").isPositive();
        }
    }

    private static final String CONFIG = """
            {
              "name": "watched", "version": 3,
              "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
              "templates": [
                {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                 "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                               "mode": "row"}}]},
                {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                 "match": {"delimiter": {"delimiter": "\\n"}},
                 "captures": [{"name": "line", "select": {"group": 1}}],
                 "body": [{"value-of": {"parts": [
                   {"text": "<"}, {"capture": {"group": 1}}, {"text": ">"}]}}]}
              ]
            }
            """;

    private static Recorder watch(final String input) {
        final Recorder recorder = new Recorder();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(CONFIG)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(new ByteArrayOutputStream()),
                recorder);
        return recorder;
    }

    @Test
    void reportsWhereInTheInputEachMatchWas() {
        final String input = "alpha\nbeta\ngamma\n";
        final Recorder recorder = watch(input);

        assertThat(recorder.matches).hasSize(3);
        // The offsets and lengths have to point at the actual bytes, so slicing the input with
        // them is the only test worth writing.
        for (final Match match : recorder.matches) {
            final String slice = input.substring((int) match.offset(), (int) (match.offset() + match.length()));
            assertThat(slice).endsWith("\n");
        }
        assertThat(input.substring(0, recorder.matches.getFirst().length())).isEqualTo("alpha\n");
        assertThat(recorder.matches.get(1).offset()).isEqualTo(6);
        assertThat(recorder.matches.get(2).offset()).isEqualTo(11);
    }

    @Test
    void numbersMatchesFromOneAndReportsDepth() {
        final Recorder recorder = watch("a\nb\n");
        assertThat(recorder.matches).extracting(Match::index).containsExactly(1, 2);
        assertThat(recorder.matches).extracting(Match::name).containsOnly("row");
        // Zero: the document template's apply-templates is the streaming loop itself rather than
        // a dispatch, so the templates it names run at the top level, not one level in.
        assertThat(recorder.matches).extracting(Match::depth).containsOnly(0);
    }

    @Test
    void reportsEveryCaptureAsItIsBound() {
        final Recorder recorder = watch("alpha\nbeta\n");
        assertThat(recorder.captures).containsExactly(
                new Capture("line", "alpha", 1),
                new Capture("line", "beta", 2));
    }

    @Test
    void reportsWhichPartOfTheOutputCameFromWhere() {
        final Recorder recorder = watch("a\nb\n");
        assertThat(recorder.outputs).hasSize(2);
        // Each record wrote "<x>", one after the other, with nothing in between.
        assertThat(recorder.outputs.getFirst()).isEqualTo(new Output(1, 0, 3));
        assertThat(recorder.outputs.get(1)).isEqualTo(new Output(2, 3, 3));
    }

    /** Rows as elements under a root the document template opens: the deferred-start shape. */
    private static final String STRUCTURED = """
            {
              "name": "watched", "version": 5,
              "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
              "templates": [
                {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                 "body": [{"element": {"name": "r", "body": [
                   {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "row"}}]}}]},
                {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                 "match": {"delimiter": {"delimiter": "\\n"}},
                 "body": [{"element": {"name": "x", "body": [{"value-of": {"parts": [{"capture": {"group": 1}}]}}]}}]}
              ]
            }
            """;

    private static Recorder watchStructured(final OutputSink sink) {
        final Recorder recorder = new Recorder();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(STRUCTURED)),
                new ByteArrayInputStream("a\nb\n".getBytes(StandardCharsets.UTF_8)),
                sink,
                recorder);
        return recorder;
    }

    @Test
    void theByteSinkReportsBytesAndTheFirstChildsSpanBeginsWithTheParentsDeferredStartTag() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Recorder recorder = watchStructured(new XmlByteSink(out));
        final String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("<r>\n   <x>a</x>\n   <x>b</x>\n</r>\n");
        assertThat(recorder.unitOutputs).extracting(UnitOutput::unit).containsOnly(OutputSink.Unit.BYTES);
        // Row 1's body caused "<r>" to be written, so its span starts at 0 and includes it;
        // row 2's begins where row 1's ended. The root's close is nobody's span.
        final UnitOutput first = recorder.unitOutputs.get(0);
        final UnitOutput second = recorder.unitOutputs.get(1);
        assertThat(first.offset()).isZero();
        assertThat(text.substring(0, (int) first.length())).isEqualTo("<r>\n   <x>a</x>");
        assertThat(second.offset()).isEqualTo(first.length());
        assertThat(text.substring((int) second.offset(), (int) (second.offset() + second.length())))
                .isEqualTo("\n   <x>b</x>");
    }

    @Test
    void theEventSinkReportsEventOrdinalsThatBracketTheSameElements() {
        final List<String> events = new ArrayList<>();
        final Recorder recorder = watchStructured(new SaxEventSink(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void startDocument() {
                events.add("startDocument");
            }

            @Override
            public void startElement(final String uri, final String local, final String qName,
                                     final org.xml.sax.Attributes atts) {
                events.add("start " + qName);
            }

            @Override
            public void characters(final char[] ch, final int start, final int length) {
                events.add("chars " + new String(ch, start, length));
            }

            @Override
            public void endElement(final String uri, final String local, final String qName) {
                events.add("end " + qName);
            }

            @Override
            public void endDocument() {
                events.add("endDocument");
            }
        }));
        assertThat(recorder.unitOutputs).extracting(UnitOutput::unit).containsOnly(OutputSink.Unit.EVENTS);
        final UnitOutput first = recorder.unitOutputs.get(0);
        final UnitOutput second = recorder.unitOutputs.get(1);
        // The same rule in the other currency: row 1's span begins with the document and root
        // start its first element forced, row 2's is its own three events.
        assertThat(events.subList((int) first.offset(), (int) (first.offset() + first.length())))
                .containsExactly("startDocument", "start r", "start x", "chars a", "end x");
        assertThat(events.subList((int) second.offset(), (int) (second.offset() + second.length())))
                .containsExactly("start x", "chars b", "end x");
        assertThat(events).endsWith("end r", "endDocument");
    }

    @Test
    void countsAttemptsThatFailedAsWellAsThoseThatMatched() {
        // A template that is tried and matches nothing is invisible in the output and invisible
        // in the messages if errors are ignored. The attempt is the only trace it leaves, which
        // is why failures are reported and not just successes.
        final Recorder recorder = new Recorder();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read("""
                        {
                          "name": "never", "version": 3,
                          "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                          "templates": [
                            {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                             "match": "source",
                             "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                           "mode": "row"}}]},
                            {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                             "match": {"regex": {"pattern": "^nothing here$"}},
                             "body": [{"text": "x"}]}
                          ]
                        }
                        """)),
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(new ByteArrayOutputStream()),
                recorder);

        assertThat(recorder.matches).isEmpty();
        assertThat(recorder.attempts).isPositive();
    }

    @Test
    void saysSoWhenContentHasNoPlaceInTheInput() {
        final Recorder recorder = new Recorder();
        Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read("""
                        {
                          "name": "from-a-variable", "version": 3,
                          "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                          "templates": [
                            {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                             "match": "source",
                             "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                           "mode": "row"}}]},
                            {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                             "match": {"delimiter": {"delimiter": "\\n"}},
                             "captures": [{"name": "held", "select": {"group": 1}}],
                             "body": [{"apply-templates": {
                               "select": {"parts": [{"capture": {"var_id": "held", "group": 0}}]},
                               "mode": "inner"}}]},
                            {"id": "00000000-0000-0000-0000-000000000003", "name": "inner",
                             "mode": "inner", "match": "all",
                             "body": [{"value-of": {"parts": [{"capture": {"group": 0}}]}}]}
                          ]
                        }
                        """)),
                new ByteArrayInputStream("abc\n".getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(new ByteArrayOutputStream()),
                recorder);

        // The inner template's content came out of a variable, so there is no offset that would
        // let anything find it — and a plausible-looking wrong number would be worse than none.
        assertThat(recorder.unlocatable).isNotEmpty();
        assertThat(recorder.matches)
                .filteredOn(match -> "inner".equals(match.name()))
                .allMatch(match -> match.offset() >= Instrument.UNLOCATABLE);
    }

    @Test
    void watchingNothingCostsNothingAndChangesNothing() {
        final ByteArrayOutputStream watched = new ByteArrayOutputStream();
        final ByteArrayOutputStream unwatched = new ByteArrayOutputStream();
        final String input = "a\nb\n";

        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(CONFIG)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(watched), new Recorder());
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(CONFIG)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(unwatched), Instrument.NONE);

        assertThat(watched.toByteArray()).isEqualTo(unwatched.toByteArray());
        // The default does nothing, including reading the clock.
        assertThat(Instrument.NONE.startTiming()).isZero();
    }
}
