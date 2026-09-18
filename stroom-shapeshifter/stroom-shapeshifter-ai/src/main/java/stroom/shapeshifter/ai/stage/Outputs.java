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

    void emitted(long inputId, Bindings bindings);

    /**
     * @return The ids of the inputs whose output the rule produced, oldest first.
     */
    List<Long> boundBy(String ruleUuid);
}
