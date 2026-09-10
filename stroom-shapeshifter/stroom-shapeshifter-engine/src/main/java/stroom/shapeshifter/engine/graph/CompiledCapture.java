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


/**
 * A capture binding with its resolution decided once (design 25 §9.2, D50): where the value
 * comes from, as the run reads it, and the cast the binding declares. The authored
 * {@link CaptureBinding} stays the model's, read by the compile-time checks; this is what the
 * level binds by, so a {@code select} or key-value source resolves through the compiled
 * reference rather than walking the authored expression per match (E39's capture half).
 *
 * @param name   the variable bound; ignored by a key-value source
 * @param source what is read
 * @param as     the cast applied at bind, or null for none
 */
public record CompiledCapture(VarName name, Source source, Cast as) {

    /** What a capture reads. */
    public sealed interface Source {

        /** A group of the match; a step's output is its index plus one. */
        record Group(int group) implements Source {

        }

        /** A value computed from an expression: a composite, UTF-8 by construction. */
        record Select(CompiledRef ref) implements Source {

        }

        /** A binding whose name is computed too; the cast applies to the value. */
        record KeyValue(CompiledRef key, CompiledRef value) implements Source {

        }
    }
}
