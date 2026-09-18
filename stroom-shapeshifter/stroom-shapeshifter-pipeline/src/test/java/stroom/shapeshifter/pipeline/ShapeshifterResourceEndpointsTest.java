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

import stroom.shapeshifter.shared.ShapeshifterLibrary;
import stroom.shapeshifter.shared.ShapeshifterPatternInfo;
import stroom.shapeshifter.shared.ShapeshifterPatternRequest;
import stroom.shapeshifter.shared.ShapeshifterText;
import stroom.shapeshifter.shared.ShapeshifterValidation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The editor's four questions (design 43 §5), answered by the resource over the engine — with
 * no document behind them, so the resource is built by hand and the stores are never touched.
 */
class ShapeshifterResourceEndpointsTest {

    private static final String PROJECT = """
            {"name": "t", "version": 5,
             "templates": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
               "declarations": [{"name": "who", "type": "scalar"}],
               "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}, "mode": "rows"}}]},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "rows",
               "match": {"regex": {"pattern": "(?<who>[a-z]+)\\n"}},
               "captures": [{"name": "who", "select": {"group": 1}}],
               "body": [{"value-of": {"parts": [{"capture": {"var_id": "who", "group": 0}}]}}]}]}
            """;

    private final ShapeshifterResourceImpl resource = new ShapeshifterResourceImpl(
            null, null, () -> new StroomFunctionLibrary(Set.of()));

    @Test
    void goodProjectValidatesAndComesBackCanonical() {
        final ShapeshifterValidation validation = resource.validate(PROJECT);
        assertThat(validation.isValid()).as(validation.getMessages().toString()).isTrue();
        assertThat(validation.getCanonical()).startsWith("{\n  \"name\": \"t\",");
        assertThat(resource.validate(validation.getCanonical()).isValid()).isTrue();
    }

    @Test
    void badJsonIsOneFatalMessageWithNoCanonicalForm() {
        final ShapeshifterValidation validation = resource.validate("{\"name\": ");
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getCanonical()).isNull();
        assertThat(validation.getMessages()).hasSize(1);
        assertThat(validation.getMessages().get(0).getSeverity()).isEqualTo("FATAL");
        assertThat(validation.getMessages().get(0).getText()).contains("not valid JSON");
    }

    @Test
    void projectThatReadsButWillNotCompileKeepsItsCanonicalForm() {
        final ShapeshifterValidation validation = resource.validate(PROJECT.replace(
                "\"declarations\": [{\"name\": \"who\", \"type\": \"scalar\"}],", ""));
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getCanonical()).isNotNull();
        assertThat(validation.getMessages().get(0).getText()).contains("who");
    }

    @Test
    void patternInfoNamesTheGroupsAndExplainsTheRun() {
        final ShapeshifterPatternInfo info = resource.patternInfo(
                new ShapeshifterPatternRequest("(?<a>[0-9]+)-([a-z]+)", false, false));
        assertThat(info.isValid()).isTrue();
        assertThat(info.getGroups()).hasSize(2);
        assertThat(info.getGroups().get(0).getName()).isEqualTo("a");
        assertThat(info.getGroups().get(1).getName()).isNull();
        assertThat(info.getExplain()).contains("tier");

        final ShapeshifterPatternInfo broken = resource.patternInfo(new ShapeshifterPatternRequest("(", false, false));
        assertThat(broken.isValid()).isFalse();
        assertThat(broken.getError()).isNotBlank();
        assertThat(broken.getExplain()).isNull();
    }

    @Test
    void explodeAndPrintAreEachOthersInverse() {
        final String regex = "^(?<level>ERROR|WARN) +(?<msg>.*)$";
        final ShapeshifterText tree = resource.explode(new ShapeshifterPatternRequest(regex, false, false));
        assertThat(tree.getText()).contains("\"sequence\"").contains("\"label\": \"level\"");
        final ShapeshifterText printed = resource.print(new ShapeshifterPatternRequest(tree.getText(), false, false));
        assertThat(printed.getText()).isEqualTo("^(?<level>ERROR|WARN) +(?<msg>.*)$");
    }

    @Test
    void libraryListsTheStandardEntriesAsRegexes() {
        final ShapeshifterLibrary library = resource.library();
        assertThat(library.getEntries()).extracting(ShapeshifterLibrary.Entry::getName)
                .startsWith("digits", "word", "identifier").contains("ipv4", "csvField");
        assertThat(library.getEntries().get(0).getRegex()).isEqualTo("[0-9]+");
        // Every entry prints to something the engine will compile back: print is the inverse of
        // explode, and a ref prints as its definition.
        for (final ShapeshifterLibrary.Entry entry : library.getEntries()) {
            final String tree = "{\"ref\": \"" + entry.getName() + "\"}";
            assertThat(resource.print(new ShapeshifterPatternRequest(tree, false, false)).getText())
                    .isEqualTo(entry.getRegex());
        }
    }
}
