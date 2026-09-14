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
import stroom.shapeshifter.engine.output.XmlByteSink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two scope behaviours design 35 phase 3 rebuilds on the declaration mechanism, pinned first
 * (design 35 §12, phase 0). Both are {@code Body} pushes that had no test naming what they are
 * for.
 */
class ScopeCharacterisationTest {

    /**
     * {@code Body.variable()} shadows the variable's own name while its body runs, so the body
     * cannot read a half-built value — or, here, the outer value it is about to replace. Without
     * the shadow the inner body would write {@code AouterB}.
     */
    @Test
    void variableBodyCannotReadTheValueItIsComputing() {
        final String json = """
                {
                  "name": "self", "version": 3,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                     "declarations": [{"name": "x", "type": "scalar"}],
                     "match": "source",
                     "body": [
                       {"variable": {"name": "x", "body": [{"text": "outer"}]}},
                       {"variable": {"name": "x", "body": [
                           {"text": "A"},
                           {"value-of": {"parts": [{"capture": {"var_id": "x", "group": 0}}]}},
                           {"text": "B"}]}},
                       {"value-of": {"parts": [{"capture": {"var_id": "x", "group": 0}}]}}]}
                  ]
                }
                """;
        assertThat(run(json, "ignored")).isEqualTo("AB");
    }

    /**
     * A recursive apply pushes {@code recursiveShadow()} — every capture name of every candidate —
     * so an inner level's capture does not overwrite the outer level's before the outer reads it.
     * Each level captures its first character, recurses on the rest, then writes what it captured:
     * with the shadow the levels unwind as {@code cba}; without it every level reads the innermost
     * {@code c}. Design 35 replaces the coarse shadow with declaration-on-entry (§4).
     */
    @Test
    void recursionLevelsKeepTheirOwnCaptures() {
        final String json = """
                {
                  "name": "rec", "version": 5,
                  "source": {"buffer_size": 2000, "ignore_errors": true, "encoding": "utf-8"},
                  "templates": [
                    {"id": "00000000-0000-0000-0000-000000000001", "name": "source", "match": "source",
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                   "mode": "row"}}]},
                    {"id": "00000000-0000-0000-0000-000000000002", "name": "row", "mode": "row",
                     "match": {"regex": {"pattern": "([^\\n]*)\\n"}},
                     "body": [{"apply-templates": {"select": {"parts": [{"capture": {"group": 1}}]},
                                                   "mode": "head", "max_depth": 8}}]},
                    {"id": "00000000-0000-0000-0000-000000000003", "name": "head", "mode": "head",
                     "declarations": [{"name": "c", "type": "scalar"}],
                     "match": {"regex": {"pattern": "^(.)(.*)$"}},
                     "captures": [{"name": "c", "select": {"group": 1}}],
                     "body": [
                       {"apply-templates": {"select": {"parts": [{"capture": {"group": 2}}]},
                                            "mode": "head", "max_depth": 8}},
                       {"value-of": {"parts": [{"capture": {"var_id": "c", "group": 0}}]}}]}
                  ]
                }
                """;
        assertThat(run(json, "abc\n")).isEqualTo("cba");
    }

    private static String run(final String json, final String input) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Shapeshifter.run(Shapeshifter.compile(ProjectReader.read(json)),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new XmlByteSink(out));
        return out.toString(StandardCharsets.UTF_8).replace("\n", "");
    }
}
