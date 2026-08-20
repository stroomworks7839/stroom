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

import stroom.shapeshifter.regex.BytePattern;

/**
 * A match expression with everything it needs already worked out.
 *
 * <p>The authored {@link stroom.shapeshifter.engine.config.MatchExpression} says what to match;
 * this says how, with patterns compiled and delimiters already encoded. The match loop should
 * not be deciding anything a compiler could have decided once.
 */
public sealed interface CompiledMatch {

    /**
     * A compiled pattern.
     *
     * @param pattern the compiled pattern
     * @param advance which group's end the cursor lands on, or 0 for the end of the whole match
     */
    record Regex(BytePattern pattern, int advance) implements CompiledMatch {

    }

    /** A delimiter and its friends, encoded to bytes once. */
    record Delimiter(byte[] delimiter,
                     byte[] escape,
                     byte[] containerStart,
                     byte[] containerEnd) implements CompiledMatch {

    }

    /** Consume everything given. */
    record All() implements CompiledMatch {

    }

    /** The document itself. Never enters the match loop; the executor handles it. */
    record Source() implements CompiledMatch {

    }

    /** Invocable only by name. Never matches. */
    record Named() implements CompiledMatch {

    }
}
