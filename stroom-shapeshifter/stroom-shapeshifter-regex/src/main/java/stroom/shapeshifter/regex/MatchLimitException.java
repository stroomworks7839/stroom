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

package stroom.shapeshifter.regex;

/**
 * A search on the {@link Engine#FANCY} tier exhausted its step budget.
 * <p>
 * The fancy tier is the one place the linear-time guarantee does not hold — backreference
 * matching is NP-complete, so no engine could restore it — and this exception is the
 * containment: a pathological pattern-input pair costs a bounded amount of work and then fails
 * loudly, rather than hanging a pipeline thread. Every other tier is immune by construction and
 * never raises this.
 * <p>
 * Raised per search, so a caller may catch it and treat the record as unmatched; the matcher
 * remains usable afterwards.
 */
public final class MatchLimitException extends RuntimeException {

    public MatchLimitException(final String message) {
        super(message);
    }
}
