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
 * The matching primitives: what a match produces, and the pieces everything that makes one
 * shares.
 *
 * <p>{@code MatchResult} is what any match produced — the groups, how far the cursor moves,
 * where the match began. {@code Splitter} splits on a delimiter with quoting and escaping, the
 * CSV problem generalised. {@code Codecs} recode bytes for the body's {@code decode}
 * transform. {@code PatternKey} is what an interned pattern is looked up by, built here and by
 * the compiler.
 *
 * <p><b>It depends on nothing above {@code value}</b>, which is what makes it a layer of
 * primitives rather than a stage of the pipeline. Everything above reaches in: the compiler for
 * keys and codecs, the run for results.
 *
 * <p>Until 2026-09-10 it held three more things — the compiled step vocabulary, the pass that
 * built it and the interpreter that ran it. Four layers in one package, which is why the step
 * vocabulary looked stuck here behind a package cycle; it was not, and they moved to
 * {@code graph}, {@code compile} and {@code exec}, where design 38 retired them: the pattern
 * tree and the match sequence compile to the regex library instead.
 */
package stroom.shapeshifter.engine.match;
