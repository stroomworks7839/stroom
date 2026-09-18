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

import stroom.shapeshifter.config.json.JsonText;
import stroom.shapeshifter.config.json.JsonValue;
import stroom.shapeshifter.engine.fixture.FixtureLedger;
import stroom.shapeshifter.engine.fixture.FixtureLedger.Fixture;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client parses project text with the config module's own parser and the engine with
 * Jackson (design 43 §5): over every fixture project the two must build the same tree, and
 * the module's printer must read back as itself.
 */
class JsonTextAgreesWithJacksonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    List<DynamicTest> everyFixtureParsesTheSameBothWays() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture : FixtureLedger.all()) {
            final String path = switch (fixture.family()) {
                case NATIVE -> "native/" + fixture.name() + "/project.json";
                case PROJECTS -> "projects/" + fixture.name() + "/project.json";
                default -> null;
            };
            if (path != null && FixtureLedger.exists(path)) {
                tests.add(DynamicTest.dynamicTest(fixture.id(), () -> agree(path)));
            }
        }
        assertThat(tests).as("the corpus must contain configurations to check").hasSizeGreaterThan(40);
        return tests;
    }

    private static void agree(final String path) {
        final String json = new String(FixtureLedger.bytes(path), StandardCharsets.UTF_8);
        final JsonValue ours = JsonText.parse(json);
        final JsonValue jacksons = ProjectReader.toValue(MAPPER.readTree(json));
        assertThat(ours).as("%s parsed by JsonText and by Jackson", path).isEqualTo(jacksons);
        assertThat(JsonText.parse(JsonText.print(ours))).isEqualTo(ours);
        assertThat(JsonText.parse(JsonText.printPretty(ours))).isEqualTo(ours);
    }
}
