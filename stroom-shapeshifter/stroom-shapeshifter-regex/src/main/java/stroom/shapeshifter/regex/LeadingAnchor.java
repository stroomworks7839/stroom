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
 * Where a pattern's own leading anchor allows a match to begin — the parser's conclusion,
 * published through {@link BytePattern#leadingAnchor()}.
 *
 * <p>This is a fact about the <em>pattern</em>, distinct from {@link Anchoring}, which is the
 * <em>question</em> a caller asks. The two meet in one useful theorem: for an {@link #INPUT}
 * pattern, the unanchored question and the anchored question always have the same answer, so
 * a caller may ask the cheaper one. The search loops already act on this fact internally —
 * publishing it lets a caller dispatching many patterns act on it too, without deriving it
 * from the pattern text and risking a disagreement with the parser.
 */
public enum LeadingAnchor {

    /** No leading anchor: a match may begin anywhere. */
    NONE,

    /** Anchored to line starts ({@code (?m)^}): the region start, or anywhere after a newline. */
    LINE,

    /** Anchored to the start of the input ({@code ^} or {@code \A}): the region start only. */
    INPUT
}
