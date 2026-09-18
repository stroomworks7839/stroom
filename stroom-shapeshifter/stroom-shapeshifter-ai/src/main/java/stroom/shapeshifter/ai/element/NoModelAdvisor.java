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

package stroom.shapeshifter.ai.element;

import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;

import java.util.List;

/**
 * The advisor a node has until the model is wired in (design 01 §12 item 6): a stage that would ask
 * fails on the stream, loudly, rather than pretending to learn. A document in {@code DISABLED} mode
 * never reaches it.
 */
public class NoModelAdvisor implements Advisor {

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        throw new IllegalStateException("Shapeshifter AI has no model to ask: the model advisor is not yet bound "
                                        + "(design 01 §12 item 6). Set the document's learning mode to DISABLED "
                                        + "or bind an Advisor.");
    }
}
