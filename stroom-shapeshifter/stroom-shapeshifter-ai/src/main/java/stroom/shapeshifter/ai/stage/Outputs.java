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

package stroom.shapeshifter.ai.stage;

import java.util.List;

/**
 * The bindings every output carries (design 01 §7.3 rule 3), as far as the stage needs to look back at
 * them: retracting a rule means finding the inputs whose outputs it produced. In-memory in scenarios; in
 * a node the bindings are the output stream's attributes and this is a meta search over them.
 */
public interface Outputs {

    /// One output, as it is emitted: which input it was made from, which pipeline was running, and what
    /// bound it (design 01 §7.3 rule 3).
    void emitted(long inputId, String pipeline, Bindings bindings);

    /**
     * What one binding produced: the inputs whose output this rule made *with this fragment*, oldest
     * first, each with the pipeline that made it — what a retraction asks to be processed again (§6).
     * <p>
     * By fragment as well as rule, because a rule keeps its uuid when it is rebound (§7.3 rule 3): a
     * retraction is of the binding in front of us, and the streams an earlier generation of the same
     * rule produced were produced correctly by what was then bound.
     */
    List<Replayable> boundBy(String ruleUuid, String fragmentUuid);

    /**
     * Forget what was produced before a given time (design 01 §12 item 8): a row per output stream is a
     * row per stream, so what a retraction can still reach is what a node is told to keep.
     *
     * @return How many were forgotten.
     */
    int prune(long producedBeforeMs);
}
