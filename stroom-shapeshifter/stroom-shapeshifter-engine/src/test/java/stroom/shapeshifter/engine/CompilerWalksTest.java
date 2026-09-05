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

import stroom.shapeshifter.engine.compile.CompiledProject;
import stroom.shapeshifter.engine.compile.PatternKey;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.regex.Encoding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 27 phase 1's audits: the walks that look for something anywhere inside a body reach
 * an iteration's body, because which instructions hold bodies is said once and exhaustively.
 * Before, each walk carried its own list of containers and each list stopped short of
 * {@code for-each}, so a pattern inside one was never interned and a name inside one was never
 * resolved.
 */
class CompilerWalksTest {

    private static CompiledProject compile(final String rootBody) {
        return Shapeshifter.compile(ProjectReader.read("""
                {"name": "walks", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": true, "encoding": "utf-8"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "root", "match": "source",
                   "body": [{"sequence": {"name": "s"}}, %s,
                            {"apply-templates": {"select": {"parts": [{"capture": {"group": 0}}]},
                                                 "mode": "lines"}}]},
                  {"id": "00000000-0000-0000-0000-000000000002", "name": "line", "mode": "lines",
                   "match": {"regex": {"pattern": "(?m)^([a-z]+)\\\\n"}},
                   "body": [{"append": {"select": {"parts": [{"capture": {"group": 1}}]}, "name": "s"}}]}
                 ]}
                """.formatted(rootBody)));
    }

    @Test
    void regexReplaceInsideAnIterationIsInterned() {
        final CompiledProject compiled = compile("""
                {"for-each": {"select": "s", "body": [
                   {"replace": {"select": [{"parts": [{"capture": {"var_id": "s", "group": 0}}]}],
                                "pattern": "x+", "replacement": "y", "is_regex": true}}]}}""");
        assertThat(compiled.patterns()).containsKey(PatternKey.of("x+", Encoding.UTF_8));
    }

    @Test
    void matchesConditionInsideAnIterationIsInterned() {
        final CompiledProject compiled = compile("""
                {"for-each": {"select": "s", "body": [
                   {"if": {"test": {"matches": {"select": {"parts": [{"capture": {"var_id": "s", "group": 0}}]},
                                                "pattern": "z+"}},
                           "then": [{"text": "!"}]}}]}}""");
        assertThat(compiled.patterns()).containsKey(PatternKey.of("z+", Encoding.UTF_8));
    }

    @Test
    void strictApplyInsideAnIterationFeedsTheDispatchLint() {
        final CompiledProject compiled = compile("""
                {"for-each": {"select": "s", "body": [
                   {"apply-templates": {"select": {"parts": [{"capture": {"var_id": "s", "group": 0}}]},
                                        "mode": "lines", "dispatch": "strict"}}]}}""");
        assertThat(compiled.warnings()).anySatisfy(warning ->
                assertThat(warning.text()).contains("line-anchored pattern in a strict level"));
    }
}
