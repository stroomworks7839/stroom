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

import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;

/**
 * Which of a variable's values a reference means, compiled (design 30 phase 5).
 *
 * <p>The authored {@link MatchIndex}'s fourth form reads the index out of <em>another
 * variable</em> at run time — {@code $heading[$__match_count]} is how a column's name is read
 * beside its value — and that variable was being found by name on every such reference. Here it
 * is a {@link VarName}, resolved when the reference compiled.
 *
 * <p>The four forms are kept as they are authored, including that nothing makes them mutually
 * exclusive: which is asked first is behaviour, and the resolver asks in this order.
 *
 * <p>That variable may be one of the engine's own — {@code $heading[$__match_count]} is the
 * common shape — and those live in frames rather than in the registry since phase 4, so which of
 * the two holds it is settled here as well. At most one of {@code varRef} and {@code varContext}
 * is set.
 *
 * @param index      the literal index, or the offset when {@code isOffset}
 * @param isOffset   whether the index counts relative to the match being processed
 * @param isLast     whether the reference means the last value there is
 * @param varRef     the registry variable holding the index, or null
 * @param varContext the execution frame holding the index, or null
 */
public record CompiledIndex(int index,
                            boolean isOffset,
                            boolean isLast,
                            VarName varRef,
                            EngineVars varContext) {

    /** Compile an authored index rule, or null when the reference carries none. */
    public static CompiledIndex of(final MatchIndex matchIndex, final VarNames names) {
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
