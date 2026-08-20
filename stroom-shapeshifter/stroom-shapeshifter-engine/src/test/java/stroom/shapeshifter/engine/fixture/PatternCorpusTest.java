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

import stroom.shapeshifter.engine.fixture.FixtureLedger.Fixture;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Engine;
import stroom.shapeshifter.regex.Flag;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every regex in the fixture corpus must compile in the matching layer.
 *
 * <p>This is the port's foundation stated as a test. The corpus was written for Rust's
 * {@code regex} and {@code fancy-regex}, and D19 chose that dialect for the matching layer for
 * exactly this reason — but "chose it" and "it works" are different claims, and this is the one
 * that gets checked on every build. Run once by hand before the port was planned, it found 207
 * of 208 patterns compiling, the exception being a literal that is not a regex at all (D33).
 * Keeping it as a test means a dialect regression cannot reach the engine unnoticed.
 *
 * <p>It also prints the tier distribution, which is a second signal: if the corpus started
 * landing overwhelmingly on one engine, that is worth knowing before the benchmarks say so.
 */
class PatternCorpusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A regex found in a config, with where it came from and how it was flagged. */
    private record FoundPattern(String source, String pattern, Set<Flag> flags) {

    }

    @Test
    void everyCorpusPatternCompiles() {
        final List<FoundPattern> found = harvest();
        assertThat(found).as("the corpus must contain patterns to check").isNotEmpty();

        final Map<Engine, Integer> tiers = new EnumMap<>(Engine.class);
        final List<String> failures = new ArrayList<>();
        final Set<String> distinct = new LinkedHashSet<>();

        for (final FoundPattern pattern : found) {
            if (!distinct.add(pattern.pattern() + " " + pattern.flags())) {
                continue;
            }
            try {
                tiers.merge(BytePattern.compile(pattern.pattern(), pattern.flags()).engine(), 1, Integer::sum);
            } catch (final RuntimeException e) {
                failures.add(pattern.source() + ": " + pattern.pattern()
                             + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        System.out.printf("Corpus patterns: %d distinct of %d occurrences; tiers %s%n",
                distinct.size(), found.size(), tiers);

        assertThat(failures).as("every corpus pattern must compile").isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Harvesting
    // -----------------------------------------------------------------------------------

    private static List<FoundPattern> harvest() {
        final List<FoundPattern> found = new ArrayList<>();
        for (final Fixture fixture : FixtureLedger.all()) {
            switch (fixture.family()) {
                case LEGACY -> harvestXml("legacy/" + fixture.name() + ".ds3.xml", found);
                case NATIVE -> harvestJson("native/" + fixture.name() + "/project.json", found);
                case PROJECTS -> harvestJson("projects/" + fixture.name() + "/project.json", found);
                default -> throw new IllegalStateException("Unhandled family: " + fixture.family());
            }
        }
        return found;
    }

    /**
     * Walk a {@code project.json} tree for the three places a regex can appear.
     *
     * <p>Deliberately not "every field called {@code pattern}": {@code replace} carries a
     * pattern that is a plain literal unless {@code is_regex} says otherwise, and treating it as
     * a regex is what produced the single failure in the original hand-run probe.
     */
    private static void harvestJson(final String path, final List<FoundPattern> found) {
        walk(MAPPER.readTree(FixtureLedger.bytes(path)), path, found);
    }

    private static void walk(final JsonNode node, final String source, final List<FoundPattern> found) {
        if (node.isObject()) {
            collect(node.get("regex"), source, found, true);
            collect(node.get("matches"), source, found, true);
            collect(node.get("replace"), source, found, false);
            node.propertyStream().forEach(entry -> walk(entry.getValue(), source, found));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, source, found));
        }
    }

    private static void collect(final JsonNode holder,
                                final String source,
                                final List<FoundPattern> found,
                                final boolean alwaysRegex) {
        if (holder == null || !holder.isObject() || !holder.has("pattern")) {
            return;
        }
        if (!alwaysRegex && !holder.path("is_regex").asBoolean(false)) {
            return;
        }
        final Set<Flag> flags = new LinkedHashSet<>();
        final JsonNode flagNode = holder.get("flags");
        if (flagNode != null && flagNode.isObject()) {
            if (flagNode.path("case_insensitive").asBoolean(false)) {
                flags.add(Flag.CASE_INSENSITIVE);
            }
            if (flagNode.path("dot_all").asBoolean(false)) {
                flags.add(Flag.DOT_ALL);
            }
        }
        found.add(new FoundPattern(source, holder.get("pattern").asString(), flags));
    }

    /** Pull the {@code pattern} attribute off every {@code regex} element in a DS3 config. */
    private static void harvestXml(final String path, final List<FoundPattern> found) {
        final Document document;
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            document = factory.newDocumentBuilder().parse(
                    new InputSource(new ByteArrayInputStream(FixtureLedger.bytes(path))));
        } catch (final Exception e) {
            // One fixture is deliberately malformed; its expectation is rejection, not patterns.
            return;
        }
        final NodeList elements = document.getElementsByTagNameNS("*", "regex");
        for (int i = 0; i < elements.getLength(); i++) {
            final Element element = (Element) elements.item(i);
            if (!element.hasAttribute("pattern")) {
                continue;
            }
            final Set<Flag> flags = new LinkedHashSet<>();
            if (Boolean.parseBoolean(element.getAttribute("caseInsensitive"))) {
                flags.add(Flag.CASE_INSENSITIVE);
            }
            if (Boolean.parseBoolean(element.getAttribute("dotAll"))) {
                flags.add(Flag.DOT_ALL);
            }
            found.add(new FoundPattern(path, element.getAttribute("pattern"), flags));
        }
    }
}
