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
import stroom.shapeshifter.engine.graph.CompiledMatch;
import stroom.shapeshifter.engine.graph.CompiledOp;
import stroom.shapeshifter.engine.graph.CompiledTemplate;
import stroom.shapeshifter.engine.graph.RootPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Settling how a run begins and ends, once, when the project compiles.
 *
 * <p>The document template's body is split at its apply-templates: what comes before is written
 * once at the start, what comes after once at the end, and the apply-templates itself is the loop
 * over the input. None of that depends on the input, so none of it belongs in a run — it was
 * recomputed per run, which is per stream in a pipeline processing many, and design 29 §3.5 is the
 * entry that says so.
 */
final class RootPlanner {

    /** Shared empty, so a level with nothing after its apply allocates no array. */
    private static final CompiledOp[] EMPTY = new CompiledOp[0];

    private RootPlanner() {
    }

    /** Settle the plan for a compiled configuration. */
    static RootPlan plan(final Project project, final CompiledTemplate[] templates) {
        final CompiledTemplate source = Arrays.stream(templates)
                .filter(t -> t.match() instanceof CompiledMatch.Source)
                .findFirst()
                .orElse(null);
        final ApplyDirective directive = source == null
                ? null
                : applyDirective(source.template().body());
        final String mode = directive == null ? null : directive.mode();
        final Dispatch dispatch = Dispatch.effective(
                directive == null ? null : directive.dispatch(), project);
        final CompiledTemplate[] roots = Arrays.stream(templates)
                .filter(t -> !(t.match() instanceof CompiledMatch.Source))
                .filter(t -> Objects.equals(t.template().mode(), mode))
                .toArray(CompiledTemplate[]::new);
        // The root level's gate is the configuration's own ignoreErrors — DS3's flag on the
        // dataSplitter element itself — or the document template's directive saying so.
        final boolean ignoreErrors = project.source().ignoreErrors()
                                     || (directive != null && directive.ignoreErrors());

        if (source == null) {
            return new RootPlan(dispatch, roots, ignoreErrors, List.of(), List.of(), List.of());
        }
        final List<CompiledOp[]> prologues = new ArrayList<>();
        final List<CompiledOp.Element> opened = new ArrayList<>();
        final List<CompiledOp[]> tails = new ArrayList<>();
        CompiledOp[] level = source.body();
        while (true) {
            final int at = indexOfApplyOrEnclosingElement(level);
            if (at < 0) {
                prologues.add(level);
                tails.add(EMPTY);
                break;
            }
            prologues.add(Arrays.copyOfRange(level, 0, at));
            tails.add(Arrays.copyOfRange(level, at + 1, level.length));
            if (level[at] instanceof final CompiledOp.Element element) {
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

    private static int indexOfApplyOrEnclosingElement(final CompiledOp[] body) {
        for (int i = 0; i < body.length; i++) {
            final CompiledOp op = body[i];
            if (op instanceof CompiledOp.Apply
                || (op instanceof final CompiledOp.Element element && containsApply(element.body()))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsApply(final CompiledOp[] body) {
        for (final CompiledOp op : body) {
            if (op instanceof CompiledOp.Apply
                || (op instanceof final CompiledOp.Element element && containsApply(element.body()))) {
                return true;
            }
        }
        return false;
    }
}
