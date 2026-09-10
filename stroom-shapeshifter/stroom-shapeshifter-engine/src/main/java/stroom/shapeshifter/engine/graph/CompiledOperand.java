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

import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.value.TypedValue;

/**
 * One side of a comparison, compiled (design 30 phase 6).
 *
 * <p>An operand is a reference or a literal, exactly one. A reference becomes a
 * {@link CompiledRef}, so the run resolves it by slot rather than by walking the authored
 * expression and finding its variable by name.
 *
 * <p><b>A literal is finished here.</b> The authored form allocated its {@link TypedValue} and
 * applied its declared cast on every evaluation, and both are constant — so this holds the value
 * already made and already cast, and the run reads a field. Where the cast fails the value is
 * absent, which is what it was per evaluation too: a comparison that cannot be made is false,
 * {@code ne} included (design/17 §8).
 *
 * @param ref     the reference, or null when this is a literal
 * @param literal the literal's value, cast, or null — meaningful only when {@code ref} is null
 * @param as      the cast to apply to a resolved reference, or null; never set for a literal,
 *                whose cast has already been applied
 */
public record CompiledOperand(CompiledRef ref, TypedValue literal, Cast as) {

    public CompiledOperand {
        if (ref == null && as != null) {
            // A literal's cast was applied when it was compiled, so carrying one here would be
            // a cast the run silently ignores. Safe to apply early because Comparisons.cast
            // reads only the value and the cast — no zone, no locale, and its date reading
            // requires an explicit offset.
            throw new IllegalStateException("A literal operand's cast is applied when it compiles");
        }
    }
}
