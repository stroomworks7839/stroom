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

/**
 * The composition layer: patterns built from named parts —
 * {@code sequence(takeWhile("[a-z]").label("key"), tag("="), ...)} — instead of written as one
 * string.
 *
 * <p>A composition lowers to the same intermediate representation a regex parses to, so the
 * choice between them is purely a readability decision: the central claim, pinned by
 * {@code CombinatorTest}, is that a composition and the regex that means the same thing
 * compile to the <i>identical</i> plan. Labels are capture groups; an embedded
 * {@link stroom.shapeshifter.regex.comb.Matcher.Regex} keeps its own groups, renumbered to
 * interleave with the labels around it. {@link stroom.shapeshifter.regex.comb.MatcherLibrary}
 * holds named definitions for reuse and ships a small standard library.
 */
package stroom.shapeshifter.regex.comb;
