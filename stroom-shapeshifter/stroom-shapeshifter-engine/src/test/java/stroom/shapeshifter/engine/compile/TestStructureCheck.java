/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.config.ConfigException;
import stroom.shapeshifter.config.Project;
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Whether a configuration writes text, asked of the model (design 24).
class TestStructureCheck {

    private static final String TEXT = "{\"text\": \"a\"}";
    private static final String VALUE = "{\"value-of\": {\"parts\": [{\"capture\": {\"group\": 0}}]}}";

    @Test
    void textAndValuesAreText() {
        assertThat(StructureCheck.writesText(project(TEXT))).isTrue();
        assertThat(StructureCheck.writesText(project(VALUE))).isTrue();
        assertThat(StructureCheck.writesText(project(
                "{\"lower-case\": {\"select\": [{\"parts\": [{\"capture\": {\"group\": 0}}]}]}}"))).isTrue();
    }

    @Test
    void nothingWrittenIsNoText() {
        assertThat(StructureCheck.writesText(project(""))).isFalse();
        assertThat(StructureCheck.writesText(project("{\"emit-error\": {\"severity\": \"warning\", "
                                                     + "\"message\": {\"parts\": [{\"text\": \"w\"}]}}}")))
                .isFalse();
    }

    @Test
    void whatIsBoundIsNotWritten() {
        assertThat(StructureCheck.writesText(project(variable(TEXT)))).isFalse();
        assertThat(StructureCheck.writesText(project(TEXT + ", " + variable(TEXT)))).isTrue();
    }

    @Test
    void anElementAnywhereIsStructure() {
        assertThat(StructureCheck.writesText(project(element(TEXT)))).isFalse();
        assertThat(StructureCheck.writesText(project(TEXT + ", " + variable(element(""))))).isFalse();
    }

    @Test
    void theAnswerAgreesWithTheCompiledModels() {
        for (final String body : new String[]{TEXT, element("")}) {
            final Project project = project(body);
            assertThat(StructureCheck.writesText(project))
                    .isEqualTo(!Shapeshifter.compile(project).structured());
        }
    }

    @Test
    void aBodyCompilingRefusesIsStructureAllTheSame() {
        // An attribute after content: compiling says what is wrong with it, and this does not
        final Project project = project(element(TEXT + ", {\"attribute\": {\"name\": \"b\", \"body\": []}}"));
        assertThat(StructureCheck.writesText(project)).isFalse();
        assertThatThrownBy(() -> Shapeshifter.compile(project)).isInstanceOf(ConfigException.class);
    }

    private static String variable(final String body) {
        return "{\"variable\": {\"name\": \"v\", \"body\": [" + body + "]}}";
    }

    private static String element(final String body) {
        return "{\"element\": {\"name\": \"e\", \"body\": [" + body + "]}}";
    }

    private static Project project(final String body) {
        return ProjectReader.read("""
                {"name": "c", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                   "declarations": [{"name": "v", "type": "scalar"}],
                   "body": [%s]}]}
                """.formatted(body));
    }
}
