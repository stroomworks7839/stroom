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

import stroom.shapeshifter.engine.config.Template;
import stroom.shapeshifter.engine.text.Encoding;

import java.util.List;

/**
 * A template, its compiled match, and its compiled body, together.
 *
 * <p>Inlined rather than looked up because the match loop touches this once per candidate per
 * position, which is the hottest thing the engine does — and what a match runs next is its
 * body, already compiled.
 *
 * @param template the authored template, still the source of truth for limits, guards and
 *                 captures
 * @param match    its compiled match expression
 * @param body     its compiled body
 * @param encoding the template's declared encoding override, parsed and validated (E3), or
 *                 null to inherit the run's — which a byte-order mark may still have replaced
 */
public record CompiledTemplate(Template template,
                               CompiledMatch match,
                               List<CompiledOp> body,
                               Encoding encoding,
                               List<CompiledCapture> captures) {

    public CompiledTemplate {
        body = List.copyOf(body);
        captures = List.copyOf(captures);
    }
}
