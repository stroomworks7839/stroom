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

package stroom.shapeshifter.engine.config;

/**
 * Binds something a match produced to a name the body can use.
 *
 * <p>Captures are populated after a successful match and before the body runs, which is the
 * whole reason a guard cannot see them.
 *
 * @param name   the name the value takes in scope
 * @param select where the value comes from
 *
 * @param name   the variable the value binds to (ignored by a key-value source, which names
 *               its own)
 * @param select where the value comes from
 * @param as     the kind the capture declares, applied once at bind by the casting table
 *               (design 25 §9, D50): a string, a number, an integer, a double, a boolean or a
 *               date; null for none, the bytes as tagged. A cast that fails is absent.
 */
public record CaptureBinding(String name, CaptureSource select, Cast as) {

    /** Where a captured value comes from. */
    public sealed interface CaptureSource {

        /** A regex capture group; 0 is the whole match. */
        record Group(int group) implements CaptureSource {

        }

        /** The output of a progressive match step, by its 0-based position. */
        record Step(int index) implements CaptureSource {

        }

        /**
         * A named field of a decoded record, for the native formats.
         *
         * <p>Resolved against the decoded schema's field order when the binding runs, rather
         * than at compile time, because the schema may be embedded in the data.
         */
        record Field(String name) implements CaptureSource {

        }

        /** A value computed from an expression rather than taken from the match. */
        record Select(RefExpression select) implements CaptureSource {

        }

        /**
         * A binding whose <i>name</i> is also computed at runtime.
         *
         * <p>This is how a configuration turns key-value input into named variables without
         * knowing the keys in advance. The enclosing {@link CaptureBinding#name()} is ignored.
         *
         * @param keyRef   the expression producing the variable name
         * @param valueRef the expression producing its value
         */
        record KeyValue(RefExpression keyRef, RefExpression valueRef) implements CaptureSource {

        }
    }
}
