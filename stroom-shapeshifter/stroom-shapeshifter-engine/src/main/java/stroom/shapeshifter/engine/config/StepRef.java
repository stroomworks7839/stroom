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
 * A count or offset in a progressive match: either written down, or read from an earlier step.
 *
 * <p>The indirection is the point. A length-prefixed record cannot say how long it is until it
 * has been read, so the step that takes the bytes has to name the step that read the length.
 */
public sealed interface StepRef {

    /** A count written into the configuration. */
    record Literal(int value) implements StepRef {

    }

    /**
     * The output of an earlier step in the same sequence, parsed as a decimal number.
     *
     * @param index the step's 0-based position within its containing sequence
     */
    record StepOutput(int index) implements StepRef {

    }
}
