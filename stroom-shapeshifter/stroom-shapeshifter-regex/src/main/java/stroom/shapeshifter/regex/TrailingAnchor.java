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
 * Where a pattern's own trailing anchor allows a match to end — the parser's conclusion,
 * published through {@link BytePattern#trailingAnchor()}, the mirror of
 * {@link LeadingAnchor}.
 *
 * <p>Like its mirror, this is a fact about the <em>pattern</em>, with its own useful
 * theorem: every match of an {@link #INPUT} pattern ends exactly at the region end, so a
 * match that ends at the edge of a buffer known to be a partial view is guaranteed to have
 * matched the buffer's end, not the input's — the caller can refuse it outright instead of
 * warning. It is also what licenses the end-anchor programme's search shortcuts
 * ({@code design/06-performance-plan.md} §6): candidate starts for such a pattern are
 * confined to the region's tail.
 */
public enum TrailingAnchor {

    /** No trailing anchor: a match may end anywhere. */
    NONE,

    /** Anchored to line ends ({@code (?m)$}): the region end, or just before any newline. */
    LINE,

    /** Anchored to the end of the input ({@code $} or {@code \z}): the region end only. */
    INPUT
}
