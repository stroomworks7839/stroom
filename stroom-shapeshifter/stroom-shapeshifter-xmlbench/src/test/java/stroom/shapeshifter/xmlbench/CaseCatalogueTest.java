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

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The A/B correctness catalogue (design/13): each case is a directory of
 * {@code input.xml}, {@code transform.xsl} and {@code challenger.project.json}, and the
 * contract is byte-identical output — Saxon and the shapeshifter engine run live against
 * each other, no goldens to go stale. Cases are listed explicitly so a broken resource path
 * fails loudly rather than silently shrinking the suite.
 */
class CaseCatalogueTest {

    /** The catalogue. Grows with every capability the comparison should protect. */
    private static final List<String> CASES = List.of(
            "nasty_xml",
            "computed_names",
            "adjacent_groups",
            "string_functions",
            "analyze_string",
            "modes",
            "reference",
            "arithmetic",
            "value_types",
            "comparison",
            "dates",
            "sequence_basics",
            "aggregate",
            "sort",
            "keys_grouping",
            "keys_lookup");

    /**
     * The walls: cases whose stylesheet runs but for which no challenger exists — executable
     * documentation of a capability gap (design/14). A wall test fails the day someone adds a
     * challenger config without promoting the case to {@link #CASES}, so a solved wall cannot
     * stay quietly misfiled.
     */
    private static final List<String> WALLS = List.of(
            "dual_output");

    @TestFactory
    List<DynamicTest> everyCaseIsByteIdentical() {
        return CASES.stream()
                .map(name -> DynamicTest.dynamicTest(name, () -> run(name)))
                .toList();
    }

    @TestFactory
    List<DynamicTest> everyWallIsStillAWall() {
        return WALLS.stream()
                .map(name -> DynamicTest.dynamicTest(name + " (gap)", () -> wall(name)))
                .toList();
    }

    private void wall(final String name) throws Exception {
        // The stylesheet must run — a wall is a real job the challenger cannot do yet, not a
        // broken example — and the challenger must be absent, else it belongs in CASES.
        final TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
        final Templates templates = factory.newTemplates(new StreamSource(
                new ByteArrayInputStream(resource(name, "transform.xsl"))));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // A base output URI in a scratch directory, so a wall exercising xsl:result-document
        // (the output-routing gap) really writes its secondary documents.
        final Path scratch = Files.createTempDirectory("wall-" + name);
        try {
            final StreamResult result = new StreamResult(out);
            result.setSystemId(scratch.resolve("primary.xml").toUri().toString());
            templates.newTransformer().transform(
                    new StreamSource(new ByteArrayInputStream(resource(name, "input.xml"))),
                    result);
            assertThat(out.size()).isPositive();
        } finally {
            try (var files = Files.walk(scratch)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        try (var challenger = getClass().getResourceAsStream(
                "/xmlbench/cases/" + name + "/challenger.project.json")) {
            assertThat(challenger)
                    .as("%s has a challenger now — promote it from WALLS to CASES", name)
                    .isNull();
        }
    }

    private void run(final String name) throws Exception {
        final byte[] input = resource(name, "input.xml");

        final TransformerFactory factory = new net.sf.saxon.TransformerFactoryImpl();
        final Templates templates = factory.newTemplates(new StreamSource(
                new ByteArrayInputStream(resource(name, "transform.xsl"))));
        final ByteArrayOutputStream incumbent = new ByteArrayOutputStream();
        templates.newTransformer().transform(
                new StreamSource(new ByteArrayInputStream(input)), new StreamResult(incumbent));

        final var compiled = Shapeshifter.compile(ProjectReader.read(
                new String(resource(name, "challenger.project.json"), StandardCharsets.UTF_8)));
        final ByteArrayOutputStream challenger = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                compiled, new ByteArrayInputStream(input), new XmlByteSink(challenger));

        assertThat(messages)
                .as("%s: the challenger must run clean", name)
                .noneMatch(m -> m.severity() == Severity.ERROR || m.severity() == Severity.FATAL);
        assertThat(challenger.toString(StandardCharsets.UTF_8))
                .as("%s: byte-identical output", name)
                .isEqualTo(incumbent.toString(StandardCharsets.UTF_8));
    }

    private byte[] resource(final String name, final String file) throws Exception {
        try (var in = getClass().getResourceAsStream("/xmlbench/cases/" + name + "/" + file)) {
            assertThat(in).as("missing resource: %s/%s", name, file).isNotNull();
            return in.readAllBytes();
        }
    }
}
