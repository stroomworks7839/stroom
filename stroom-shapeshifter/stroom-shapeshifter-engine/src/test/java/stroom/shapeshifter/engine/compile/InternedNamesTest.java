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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Names became slots (design 30 phase 5), so the two halves of a variable — whatever writes it
 * and whatever reads it — have to agree on which slot.
 *
 * <p><b>Why that is safe is not obvious, and is worth writing down.</b> Whatever writes a name
 * interns it when the configuration compiles, and the compiler refuses a read of a name nothing
 * writes, so every name a reference can reach is in the table. The one exception is a key-value
 * capture, whose name comes from the data: there the write and the read both go through the same
 * run-time map and agree on the slot they invent. The slot agreement itself is pinned in
 * {@code StoreTest}; what is pinned here is the resolver that could most easily have been
 * forgotten.
 *
 * <p>A <b>condition</b> is that resolver. It still walks the authored expression rather than a
 * compiled reference (E39's open seam), so it is the one place that looks a name up by string
 * while a record runs, and the only one that would not have been noticed if names became slots
 * everywhere else. The golden corpus exercises it — {@code apache_httpd} alone has thirty-one
 * conditions — but by accident.
 */
class InternedNamesTest {

    @Test
    void conditionResolvesACaptureAfterNamesBecameSlots() {
        // A capture writes the name and a guard is the only thing that reads it.
        assertThat(run("kept")).contains("<row>yes</row>");
    }

    @Test
    void namesCloseWhenTheConfigurationHasCompiled() {
        final VarNames names = new VarNames();
        names.intern("while compiling");
        names.freeze();

        // A run sized its slot array from this table, so a name interned afterwards would have a
        // slot past the end of it. Loud rather than silent.
        assertThatThrownBy(() -> names.intern("while running"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("while running");
    }

    private static String run(final String name) {
        final String json = """
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source",
                   "body": [
                     {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                       "mode": "doc"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "doc",
                   "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                   "captures": [{"name": "%1$s", "select": {"group": 1}}],
                   "body": [
                     {"element": {"name": "row", "body": [
                        {"if": {"test": {"equals": {
                            "select": {"parts": [{"capture":
                                {"var_id": "%1$s", "group": 0}}]},
                            "value": "match me"}},
                          "then": [{"text": "yes"}]}}]}}]}]}
                """.formatted(name);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Message> messages = Shapeshifter.run(
                Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream("match me\n".getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        assertThat(messages).noneMatch(m -> m.severity().ordinal() >= stroom.shapeshifter.engine
                .Severity.ERROR.ordinal());
        return out.toString(StandardCharsets.UTF_8);
    }
}
