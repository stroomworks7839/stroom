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

import stroom.shapeshifter.engine.Shapeshifter;
import stroom.shapeshifter.engine.config.ConfigException;
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.config.ProjectReader;
import stroom.shapeshifter.engine.function.FunctionRegistry;
import stroom.shapeshifter.engine.text.Encoding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A configuration is compiled <b>for a reading</b> (design 32 phase 3).
 *
 * <p>Every pattern is interned under a key that carries an encoding, every delimiter is
 * pre-encoded, every step is compiled for a decoding. So a compiled model serves one reading, and
 * {@code auto} is an instruction rather than one — accepting it is how a graph ends up unable to
 * say what it was built for, which is the whole defect design 32 removes.
 */
class CompiledForAReadingTest {

    private static Project project(final String encoding) {
        return ProjectReader.read("""
                {"name": "t", "version": 5,
                 "source": {"buffer_size": 20000, "ignore_errors": false, "encoding": "%s"},
                 "templates": [
                  {"id": "00000000-0000-0000-0000-000000000001", "name": "source",
                   "match": "source", "body": [{"text": "x"}]}]}
                """.formatted(encoding));
    }

    @Test
    void autoIsRefusedWhereAReadingBelongs() {
        assertThatThrownBy(() -> Shapeshifter.compile(
                project("auto"), FunctionRegistry.EMPTY, Encoding.AUTO))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("auto");
    }

    @Test
    void declaredReadingIsTheOneCompiledFor() {
        assertThat(Shapeshifter.compile(project("auto"), FunctionRegistry.EMPTY, Encoding.LATIN_1)
                .encoding()).isEqualTo(Encoding.LATIN_1);
        assertThat(Shapeshifter.compile(project("auto"), FunctionRegistry.EMPTY, Encoding.UTF_8)
                .encoding()).isEqualTo(Encoding.UTF_8);
    }

    @Test
    void compilingWithoutAnInputTakesTheDefault() {
        // There is nothing to sniff here, and design 32 §6 ruled UTF-8. This is what keeps every
        // existing caller — and phase 3 — a structural change rather than a behavioural one.
        assertThat(Shapeshifter.compile(project("auto")).encoding()).isEqualTo(Encoding.UTF_8);
    }

    @Test
    void transcodedSourceStillCompilesAsUtf8() {
        // Design 19 phase 6: UTF-16 is decoded whole before the window sees it, so the graph is
        // compiled for UTF-8 and remembers what it transcodes from. Settling the encoding earlier
        // must not have changed that.
        assertThat(Shapeshifter.compile(project("auto"), FunctionRegistry.EMPTY, Encoding.UTF_16LE)
                .transcodeFrom()).isEqualTo(Encoding.UTF_16LE);
        assertThat(Shapeshifter.compile(project("auto"), FunctionRegistry.EMPTY, Encoding.UTF_16LE)
                .encoding()).isEqualTo(Encoding.UTF_8);
    }
}
