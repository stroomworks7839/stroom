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
import stroom.shapeshifter.engine.value.TypedValue;

/**
 * A reference expression with its resolution strategy already decided.
 *
 * <p>The authored {@link RefExpression} is a list of parts to be interpreted; this is what the
 * interpretation concluded, once, at compile time. Literal text is <b>a value made once</b>,
 * UTF-8-tagged (design 25) — a UTF-8 sink writes its array without re-encoding the string —
 * and the common one-part shapes are named so the body interpreter dispatches on what an
 * expression <i>is</i> rather than walking what it says. Kept as compiled nodes rather than
 * annotations on the model, because the model stays the model (D35).
 */
public sealed interface CompiledRef {

    /** An expression with no parts. Resolves to nothing, writes nothing. */
    record Empty() implements CompiledRef {

    }

    /** Pure literal text: a UTF-8-tagged value (design 25). */
    record Bytes(TypedValue value) implements CompiledRef {

    }

    /** One group of the current match. */
    record LocalGroup(int group) implements CompiledRef {

    }

    /**
     * A named variable's value, with the reference's index rule.
     *
     * <p>No group travels. A reference names one group, and which group it named is settled when
     * the configuration is compiled — a migrated {@code $h$2} binds a capture of its own (E48) —
     * so by the time the graph exists there is one store per name and nothing to select within
     * it. A non-zero group is refused in {@code RefCompiler} rather than carried here and
     * silently resolved to nothing.
     */
    record RemoteVar(VarName varId, CompiledIndex matchIndex) implements CompiledRef {

    }

    /**
     * One value out of the execution context: a frame read rather than a name (design 30
     * phase 4).
     *
     * <p>Named for where the value comes from, as {@link LocalGroup} and {@link RemoteVar} are
     * — the run's context, rather than the current match or a variable. That it is the engine
     * which writes it is true and is not the distinction the other kinds are drawn on.
     *
     * <p>Which frame is a compile-time fact, and was already being treated as one — the
     * compiler's {@code ReferenceCheck} warns about reading {@code position()} outside a
     * {@code for-each} precisely because it knows. This carries the same knowledge into the run
     * instead of resolving the name against the scope stack on every read. The index rule
     * travels because an author may still write one; a scalar answers to index one and to
     * nothing else, which is what the store holding it did. No group travels, for the same
     * reason it does not on {@link RemoteVar}.
     */
    record Context(EngineVars var, CompiledIndex matchIndex) implements CompiledRef {

    }

    /** Several parts, concatenated. Each element is one of the three shapes above. */
    record Composite(CompiledRef[] parts) implements CompiledRef {

    }
}
