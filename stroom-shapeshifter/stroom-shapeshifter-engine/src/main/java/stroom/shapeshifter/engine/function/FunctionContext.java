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

package stroom.shapeshifter.engine.function;

import stroom.shapeshifter.engine.Severity;

import java.util.Map;

/**
 * What a bound function may reach (design 26 §2): the run's messages, the input position of
 * the innermost running match, a scratch map that lives for the run, and the services whoever
 * owns the engine configured — none in a bare run, the pipeline's holders in Stroom.
 */
public interface FunctionContext {

    /** A WARNING in the run's messages, prefixed with the function's name. */
    void warn(String message);

    /** An ERROR in the run's messages, prefixed with the function's name. */
    void error(String message);

    /** Where the innermost running match began in the input, or {@code Instrument.UNLOCATABLE}. */
    long inputOffset();

    /** How many bytes the innermost running match covers, or -1 when nothing is running. */
    default long inputLength() {
        return -1;
    }

    /** The number of the top-level record being processed, from 1; 0 before the first. */
    default long recordNumber() {
        return 0;
    }

    /**
     * A message of any severity, prefixed with the function's name. The default folds INFO into
     * a warning and FATAL into an error; the engine's own context keeps the severity.
     */
    default void message(final Severity severity, final String message) {
        switch (severity) {
            case ERROR, FATAL -> error(message);
            default -> warn(message);
        }
    }

    /** The run's scratch space, shared by every function bound to the run. */
    Map<String, Object> state();

    /** A service the run was configured with, or null. */
    <T> T service(Class<T> type);
}
