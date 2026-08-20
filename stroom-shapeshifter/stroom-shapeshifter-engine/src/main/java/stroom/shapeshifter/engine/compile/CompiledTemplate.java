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

/**
 * A template and its compiled match, together.
 *
 * <p>The pair is inlined rather than looked up because the match loop touches it once per
 * candidate per position, which is the hottest thing the engine does.
 *
 * @param template the authored template, still the source of truth for everything but matching
 * @param match    its compiled match expression
 */
public record CompiledTemplate(Template template, CompiledMatch match) {

}
