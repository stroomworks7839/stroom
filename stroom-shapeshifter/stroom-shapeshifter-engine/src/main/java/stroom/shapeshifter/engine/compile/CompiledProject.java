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
import stroom.shapeshifter.engine.config.Project;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.regex.BytePattern;

import java.util.List;
import java.util.Map;

/**
 * A configuration ready to run — the executable graph, and the second of the only two layers
 * there are (D35).
 *
 * <p>Its nodes own their state, so the graph executes <b>one run at a time</b> and is reused
 * sequentially — a {@code ByteMatcher}'s contract, one level up. Concurrency is one compiled
 * graph per instance, which compilation prices at milliseconds.
 *
 * @param project   the authored configuration
 * @param templates its templates, compiled, in their authored order
 * @param patterns  every pattern used somewhere other than a template's own match — in a
 *                  condition, or in a regex replacement — compiled once and keyed by its text.
 *                  Interning by text rather than by position means the same pattern written in
 *                  three places is compiled once, and means a pattern that will not compile is
 *                  an error before any input is read
 * @param encoding  the encoding its input is declared to be in, which a byte-order mark on the
 *                  input may still override
 * @param warnings  anything worth saying that did not stop compilation; these are reported at
 *                  the start of a run, so that a configuration's problems reach the same place
 *                  its data's problems do
 */
public record CompiledProject(Project project,
                              List<CompiledTemplate> templates,
                              Map<String, BytePattern> patterns,
                              Encoding encoding,
                              List<Message> warnings) {

    public CompiledProject {
        templates = List.copyOf(templates);
        patterns = Map.copyOf(patterns);
        warnings = List.copyOf(warnings);
    }
}
