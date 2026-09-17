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

package stroom.shapeshifter.engine.graph;

import stroom.shapeshifter.config.Dispatch;

/**
 * How a run begins and ends, settled once when the project compiles.
 *
 * <p>The document template's body is split at its apply-templates: what comes before is written
 * once at the start, what comes after once at the end, and the apply-templates itself is the loop
 * over the input. Running the prologues in order with each level's element opened after its
 * prologue, then the loop, then the tails in reverse with each element closed after its tail, is
 * the body run in one piece with the loop where the apply-templates was. A body with no
 * apply-templates is all prologue.
 *
 * <p>None of that depends on the input, so none of it belongs in a run. It was recomputed per
 * run — the source template found by a stream filter, its body walked for the directive, the
 * roots filtered, and the split rebuilt — which is per stream in a pipeline processing many, and
 * design 29 §3.5 is the entry that says so.
 *
 * @param dispatch         the dispatch the root level runs under
 * @param roots            the templates the loop dispatches to, in authored order
 * @param ignoreErrors     the root level's gate: the configuration's own flag, or the directive's
 * @param prologues        each level's ops before the apply-templates, outermost first
 * @param opened           the elements enclosing the apply-templates, outermost first
 * @param tails            each level's ops after the apply-templates, outermost first
 */
public record RootPlan(Dispatch dispatch,
                       CompiledTemplate[] roots,
                       boolean ignoreErrors,
                       CompiledOp[][] prologues,
                       CompiledOp.Element[] opened,
                       CompiledOp[][] tails) {

}
