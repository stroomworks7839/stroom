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
 * Whether a match must begin at the given offset or may be searched for.
 * <p>
 * This is a property of the search, not of the pattern: {@code ^} is an additional assertion
 * on top, not a substitute for anchoring.
 */
public enum Anchoring {

    /** The match must begin at the offset given. */
    ANCHORED,

    /** Search forward for the leftmost offset at which the pattern matches. */
    UNANCHORED
}
