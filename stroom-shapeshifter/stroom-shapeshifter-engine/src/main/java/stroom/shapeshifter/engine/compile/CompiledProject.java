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

import java.util.List;

/**
 * A configuration ready to run.
 *
 * @param project   the authored configuration
 * @param templates its templates, compiled, in their authored order
 * @param warnings  anything worth saying that did not stop compilation; these are reported at
 *                  the start of a run, so that a configuration's problems reach the same place
 *                  its data's problems do
 */
public record CompiledProject(Project project, List<CompiledTemplate> templates, List<Message> warnings) {

    public CompiledProject {
        templates = List.copyOf(templates);
        warnings = List.copyOf(warnings);
    }
}
