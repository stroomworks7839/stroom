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

import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.List;

/**
 * Everything a scenario asserts on after one run (design 02 §1).
 *
 * @param doc            The document as it stands afterwards: the routing table rewritten if a promotion
 *                       happened, otherwise as given.
 * @param decision       What was decided.
 * @param shapeSignature The stream's shape, as routing and quarantine keyed it.
 * @param output         What the bound or promoted fragment produced over the whole stream; null for a
 *                       sentinel or a given-up shape.
 * @param verdicts       The scorecard's verdicts on the fragment over the whole stream, one per step, in
 *                       chain order; empty when nothing ran.
 * @param transcript     Every exchange with the model, oldest first; empty when it was not consulted.
 */
public record StageRun(ShapeshifterAiDoc doc,
                       Decision decision,
                       String shapeSignature,
                       String output,
                       List<Verdict> verdicts,
                       List<Exchange> transcript) {

}
