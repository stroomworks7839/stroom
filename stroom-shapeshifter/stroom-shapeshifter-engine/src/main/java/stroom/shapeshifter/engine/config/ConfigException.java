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
 * A configuration is wrong: malformed, naming something the engine does not know, or asking
 * for what this build does not carry — found reading it into a {@link Project} or compiling
 * that {@code Project}, before any input has been read.
 *
 * <p>Distinct from anything that happens while <i>running</i> a configuration, which is a
 * message, never an exception: one bad record in a million is not a failure.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(final String message) {
        super(message);
    }

    public ConfigException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * Refuse clearly rather than fail obscurely: a template asks for what this build does not
     * carry, and is told so by name at compile time rather than run to produce nothing.
     */
    public static ConfigException notYet(final String templateName, final String what) {
        return new ConfigException(
                "Template '" + templateName + "' needs " + what + ", which this build does not support");
    }
}
