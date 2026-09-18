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

import stroom.docref.DocRef;

/**
 * What produced an output (design 01 §7.3 rule 3), recorded alongside it so that reprocessing can be
 * as-processed, and so that a retracted rule's outputs can be found. The fragment's and its documents'
 * version ids, and the candidate count, join these when the supervisor element writes them to the
 * output stream (§12 item 7).
 *
 * @param docUuid     The Shapeshifter AI document.
 * @param ruleUuid    The routing rule that matched or was bound.
 * @param fragment    The fragment the rule bound.
 * @param provisional Whether the rule was provisional when it produced this output (§6).
 * @param score       The fragment's score over this stream.
 */
public record Bindings(String docUuid, String ruleUuid, DocRef fragment, boolean provisional, double score) {
}
