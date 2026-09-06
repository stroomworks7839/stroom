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
 * Matching: what a template's match produces, and the two ways of matching that are not a
 * regex.
 *
 * <p>{@code MatchResult} is what any match produced — the groups, how far the cursor moves,
 * where the match began. {@code Steps} is progressive matching, a sequence of steps each
 * starting where the last stopped, which is what a regex cannot do; {@code Codecs} recode the
 * bytes a step produced for the steps after it; {@code Splitter} splits on a delimiter with
 * quoting and escaping, the CSV problem generalised. This package depends on the values, the
 * configuration's step vocabulary, the interned-pattern key, the text encodings and the regex
 * library, and the run depends on it (design 27 §2.5).
 */
package stroom.shapeshifter.engine.match;
