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
 * Execution: one run of a compiled configuration over one input.
 *
 * <p>{@code Run} owns a run — the graph, the sink, the messages, the encoding in force, the
 * document template's split around its {@code apply-templates} — and hands the input to
 * {@code Level}, window by window through {@code InputWindow} or chunk by chunk. {@code Level}
 * dispatches one level's templates against one region and binds a winning match's captures;
 * {@code Body} interprets the match's body, the switch over the compiled instruction
 * vocabulary, with the scoped {@code VarRegistry} of match-indexed {@code Store}s that every
 * body reads and every capture writes; {@code FunctionRuntime} binds the configuration's
 * functions once and makes their calls. Around them the vocabulary a body reaches for:
 * {@code Refs} and {@code CompiledRefs} resolve references, {@code Conditions} decide,
 * {@code Transforms} compute, {@code Steps} and {@code Splitter} match progressively and on
 * delimiters, {@code Codecs} recode bytes; {@code TypedValue} and {@code MatchResult} are what
 * passes between them, and {@code AbortRun} is how a fatal message ends a run. The graph
 * performs the execution (D35): the run and its collaborators are its state for one input,
 * and there is nothing between the model and the graph.
 */
package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.value.Transforms;
import stroom.shapeshifter.engine.value.TypedValue;
