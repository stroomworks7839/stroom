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
 * A byte-oriented regular expression engine: patterns match over bytes, offsets are byte
 * offsets, and no decode happens unless the pattern itself demands one.
 *
 * <p>The dialect is an RE2-style subset spelt Rust's way, with the non-regular constructs —
 * backreferences, lookaround, atomic groups, {@code \G} — as an explicitly chosen fancy tier
 * (design/01-regex-language.md). The compiler picks the cheapest machine that can give the
 * right answer and {@link stroom.shapeshifter.regex.BytePattern#explain()} names it; forcing
 * an engine is for tests and diagnostics, which is also how the engines are held to identical
 * answers. Facts a dispatching caller may act on — the leading anchor above all — are
 * published by the pattern itself, never re-derived from its text.
 *
 * <p>{@link stroom.shapeshifter.regex.BytePattern} compiles,
 * {@link stroom.shapeshifter.regex.ByteMatcher} matches over arrays and
 * {@link stroom.shapeshifter.regex.ByteWindow}s,
 * {@link stroom.shapeshifter.regex.StreamMatcher} matches over a stream with an exact
 * need-more-input contract. Known behavioural divergences from {@code java.util.regex} are
 * pinned in {@code KnownDivergenceTest}, and the module's open issues in {@code ISSUES.md}
 * beside it.
 */
package stroom.shapeshifter.regex;
