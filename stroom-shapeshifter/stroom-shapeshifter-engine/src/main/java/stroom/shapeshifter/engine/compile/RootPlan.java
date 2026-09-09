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

import stroom.shapeshifter.engine.config.Dispatch;
import stroom.shapeshifter.engine.config.OutputNode;
import stroom.shapeshifter.engine.config.OutputNode.ApplyDirective;
import stroom.shapeshifter.engine.config.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
                       List<CompiledTemplate> roots,
                       boolean ignoreErrors,
                       List<List<CompiledOp>> prologues,
                       List<CompiledOp.Element> opened,
                       List<List<CompiledOp>> tails) {

    public RootPlan {
        roots = List.copyOf(roots);
        prologues = List.copyOf(prologues);
        opened = List.copyOf(opened);
        tails = List.copyOf(tails);
    }

    /** Settle the plan for a compiled configuration. */
    static RootPlan of(final Project project, final List<CompiledTemplate> templates) {
        final CompiledTemplate source = templates.stream()
                .filter(t -> t.match() instanceof CompiledMatch.Source)
                .findFirst()
                .orElse(null);
        final ApplyDirective directive = source == null
                ? null
                : applyDirective(source.template().body());
        final String mode = directive == null ? null : directive.mode();
        final Dispatch dispatch = Dispatch.effective(
                directive == null ? null : directive.dispatch(), project);
        final List<CompiledTemplate> roots = templates.stream()
                .filter(t -> !(t.match() instanceof CompiledMatch.Source))
                .filter(t -> Objects.equals(t.template().mode(), mode))
                .toList();
        // The root level's gate is the configuration's own ignoreErrors — DS3's flag on the
        // dataSplitter element itself — or the document template's directive saying so.
        final boolean ignoreErrors = project.source().ignoreErrors()
                                     || (directive != null && directive.ignoreErrors());

        if (source == null) {
            return new RootPlan(dispatch, roots, ignoreErrors, List.of(), List.of(), List.of());
        }
        final List<List<CompiledOp>> prologues = new ArrayList<>();
        final List<CompiledOp.Element> opened = new ArrayList<>();
        final List<List<CompiledOp>> tails = new ArrayList<>();
        List<CompiledOp> level = source.body();
        while (true) {
            final int at = indexOfApplyOrEnclosingElement(level);
            if (at < 0) {
                prologues.add(level);
                tails.add(List.of());
                break;
            }
            prologues.add(level.subList(0, at));
            tails.add(level.subList(at + 1, level.size()));
            if (level.get(at) instanceof final CompiledOp.Element element) {
                opened.add(element);
                level = element.body();
            } else {
                break;
            }
        }
        return new RootPlan(dispatch, roots, ignoreErrors, prologues, opened, tails);
    }

    /**
     * The directive of the first apply-templates in a body, searched top down and inside any
     * {@code element} that encloses it — the same descent the split makes.
     */
    private static ApplyDirective applyDirective(final List<OutputNode> body) {
        for (final OutputNode node : body) {
            if (node instanceof final OutputNode.ApplyTemplates apply) {
                return apply.directive();
            }
            if (node instanceof final OutputNode.Element element) {
                final ApplyDirective inside = applyDirective(element.body());
                if (inside != null) {
                    return inside;
                }
            }
        }
        return null;
    }

    private static int indexOfApplyOrEnclosingElement(final List<CompiledOp> body) {
        for (int i = 0; i < body.size(); i++) {
            final CompiledOp op = body.get(i);
            if (op instanceof CompiledOp.Apply
                || (op instanceof final CompiledOp.Element element && containsApply(element.body()))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsApply(final List<CompiledOp> body) {
        for (final CompiledOp op : body) {
            if (op instanceof CompiledOp.Apply
                || (op instanceof final CompiledOp.Element element && containsApply(element.body()))) {
                return true;
            }
        }
        return false;
    }
}
