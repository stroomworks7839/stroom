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

import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.graph.CompiledIndex;
import stroom.shapeshifter.engine.graph.CompiledRef;
import stroom.shapeshifter.engine.graph.VarNames;
import stroom.shapeshifter.engine.value.TypedValue;

/**
 * Deciding what an authored reference expression means, once.
 *
 * <p>The authored {@link RefExpression} is a list of parts to be interpreted; a
 * {@link CompiledRef} is what the interpretation concluded. Literal text becomes <b>a value made
 * once</b>, UTF-8-tagged (design 25), and the common one-part shapes are named so the body
 * interpreter dispatches on what an expression <i>is</i> rather than walking what it says.
 *
 * <p>A name becomes a slot here, and which <i>place</i> holds it — the variable registry or one
 * of the run's execution frames — is settled here too (design 30 phases 4 and 5), so nothing
 * resolves a name while a record runs.
 */
final class RefCompiler {

    private RefCompiler() {
    }

    /** Decide an expression's strategy, interning every name it reads. */
    static CompiledRef compile(final RefExpression expression, final VarNames names) {
        if (expression == null || expression.parts().isEmpty()) {
            return new CompiledRef.Empty();
        }
        if (expression.parts().size() == 1) {
            return part(expression.parts().getFirst(), names);
        }
        final CompiledRef[] parts = new CompiledRef[expression.parts().size()];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = part(expression.parts().get(i), names);
        }
        return new CompiledRef.Composite(parts);
    }

    private static CompiledRef part(final RefPart part, final VarNames names) {
        return switch (part) {
            case final RefPart.Text text -> new CompiledRef.Bytes(TypedValue.of(text.value()));
            case final RefPart.Capture capture -> {
                if (capture.varId() == null) {
                    yield new CompiledRef.LocalGroup(capture.group());
                }
                final CompiledIndex index = index(capture.matchIndex(), names);
                final EngineVars engine = EngineVars.byName(capture.varId());
                yield engine != null && engine.framed()
                        ? new CompiledRef.Context(engine, capture.group(), index)
                        : new CompiledRef.RemoteVar(
                                names.intern(capture.varId()), capture.group(), index);
            }
        };
    }

    /**
     * Compile an authored index rule, or null when the reference carries none.
     *
     * <p>Its fourth form reads the index out of another variable at run time, and that variable
     * may be one of the engine's own — {@code $heading[$__match_count]} is the common shape — so
     * which of the two places holds it is decided here as well.
     */
    static CompiledIndex index(final MatchIndex matchIndex, final VarNames names) {
        if (matchIndex == null) {
            return null;
        }
        final EngineVars engine = EngineVars.byName(matchIndex.varRef());
        final boolean framed = engine != null && engine.framed();
        return new CompiledIndex(matchIndex.index(), matchIndex.isOffset(), matchIndex.isLast(),
                framed ? null : names.intern(matchIndex.varRef()),
                framed ? engine : null);
    }
}
