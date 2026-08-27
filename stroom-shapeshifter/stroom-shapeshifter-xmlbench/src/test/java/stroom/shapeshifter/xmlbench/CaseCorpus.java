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

package stroom.shapeshifter.xmlbench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amplifies a catalogue case's input to benchmark scale. Each case's input is a prologue
 * (declaration and root open), a run of repeating units (records, events, batches — the
 * shape the case's own templates dispatch over), and an epilogue (root close). Amplification
 * repeats the original units cyclically, unchanged: content variety stays whatever the case
 * authored, which is disclosed in design/13 — repetition measures throughput, not branch
 * surprise. {@code CaseAmplifierTest} holds the guarantee that matters: an amplified input
 * still passes Saxon/challenger byte-parity, so the benchmark and the correctness catalogue
 * cannot drift apart.
 */
final class CaseCorpus {

    /** The cases with challengers, each with the pattern matching one repeating unit. */
    static final Map<String, Pattern> UNITS = Map.ofEntries(
            Map.entry("nasty_xml", Pattern.compile("(?s)  <batch [^\n]*\n.*?\n  </batch>\n")),
            Map.entry("computed_names", Pattern.compile("  <(?:item|raw)\\b[^\n]*\n")),
            Map.entry("adjacent_groups", Pattern.compile("  <[he]\\b[^\n]*\n")),
            Map.entry("string_functions", Pattern.compile("  <row [^\n]*\n")),
            Map.entry("analyze_string", Pattern.compile("  <m [^\n]*\n")),
            Map.entry("modes", Pattern.compile("(?s)  <Event [^\n]*\n.*?\n  </Event>\n")),
            Map.entry("reference", Pattern.compile("(?s)  <record>\n.*?\n  </record>\n")),
            Map.entry("arithmetic", Pattern.compile("  <order [^\n]*\n")),
            Map.entry("value_types", Pattern.compile("  <val [^\n]*\n")),
            Map.entry("comparison", Pattern.compile("  <c [^\n]*\n")),
            Map.entry("dates", Pattern.compile("  <e [^\n]*\n")),
            Map.entry("sequence_basics", Pattern.compile("  <item [^\n]*\n")),
            Map.entry("aggregate", Pattern.compile("  <row [^\n]*\n")),
            Map.entry("sort", Pattern.compile("  <p [^\n]*\n")));

    private CaseCorpus() {
    }

    /** The benchmarkable case names, alphabetical for stable JMH param order. */
    static List<String> names() {
        return UNITS.keySet().stream().sorted().toList();
    }

    /**
     * The case's input amplified to about {@code totalUnits} units — rounded down to whole
     * cycles of the original unit run, because a partial cycle can end the document in a
     * shape the case never authored. Found the hard way: cutting {@code adjacent_groups}
     * after a header leaves a trailing empty group, which Saxon serializes self-closed while
     * the challenger has already emitted the open tag — a real idiom limit, recorded in
     * design/14, but not this corpus's question. The benchmark measures the proven job.
     */
    static byte[] amplify(final String name, final int totalUnits) {
        final String input = new String(read(name, "input.xml"), StandardCharsets.UTF_8);
        final Matcher matcher = UNITS.get(name).matcher(input);
        final List<String> units = new ArrayList<>();
        int firstStart = -1;
        int lastEnd = -1;
        while (matcher.find()) {
            if (firstStart < 0) {
                firstStart = matcher.start();
            }
            lastEnd = matcher.end();
            units.add(matcher.group());
        }
        if (units.isEmpty()) {
            throw new IllegalStateException("No units matched for case: " + name);
        }
        final int cycles = Math.max(1, totalUnits / units.size());
        final StringBuilder out = new StringBuilder(
                input.length() + totalUnits * units.getFirst().length());
        out.append(input, 0, firstStart);
        for (int i = 0; i < cycles * units.size(); i++) {
            out.append(units.get(i % units.size()));
        }
        out.append(input, lastEnd, input.length());
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] read(final String name, final String file) {
        try (var in = CaseCorpus.class.getResourceAsStream(
                "/xmlbench/cases/" + name + "/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + name + "/" + file);
            }
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
