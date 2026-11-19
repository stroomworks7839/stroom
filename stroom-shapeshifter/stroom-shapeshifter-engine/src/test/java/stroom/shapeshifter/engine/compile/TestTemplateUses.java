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
import stroom.shapeshifter.engine.ProjectReader;
import stroom.shapeshifter.engine.Shapeshifter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The mode name `default` is reserved (design 47 §7.3, ruled 2026-11-19): it is what the
// templates with no mode are called, so a mode of that name could not be told apart from none.
class TestTemplateUses {

    private static String project(final String applyMode, final String templateMode) {
        return """
                {"name": "modes", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "document", "match": "source",
                   "body": [{"element": {"name": "records", "body": [
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]}%s}}]}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line"%s,
                   "match": {"regex": {"pattern": "[^\\n]*\\n"}},
                   "body": [{"text": "x"}]}
                 ]}
                """.formatted(applyMode == null ? "" : ", \"mode\": \"" + applyMode + "\"",
                templateMode == null ? "" : ", \"mode\": \"" + templateMode + "\"");
    }

    @Test
    void aTemplateInAModeCalledDefaultIsRefused() {
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(project("lines", "default"))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Template 'line' is in a mode named 'default', which is reserved");
    }

    @Test
    void aDispatchIntoAModeCalledDefaultIsRefusedWhereverItSits() {
        // Inside an element, so the walk into a holder's bodies is what finds it
        assertThatThrownBy(() -> Shapeshifter.compile(ProjectReader.read(project("default", "lines"))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Template 'document' dispatches into a mode named 'default'");
    }

    @Test
    void noModeAndOtherNamesAreAccepted() {
        assertThat(Shapeshifter.compile(ProjectReader.read(project(null, null)))).isNotNull();
        // root is an ordinary name: configurations already use it
        assertThat(Shapeshifter.compile(ProjectReader.read(project("root", "root")))).isNotNull();
        // and only the exact name is reserved
        assertThat(Shapeshifter.compile(ProjectReader.read(project("Default", "Default")))).isNotNull();
    }
}
