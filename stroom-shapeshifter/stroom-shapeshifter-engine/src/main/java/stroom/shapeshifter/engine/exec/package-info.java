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
 * vocabulary; {@code FunctionRuntime} binds the configuration's functions once and makes
 * their calls; {@code AbortRun} is how a fatal message ends a run. What they read and write:
 * the scoped {@code VarRegistry} of match-indexed {@code Store}s, the names the engine sets
 * itself in {@code EngineVars}, references resolved by {@code Refs} and {@code CompiledRefs},
 * conditions decided by {@code Conditions}. The values are {@code value}'s, the matching
 * {@code match}'s, the sinks {@code output}'s; this package depends on all three, on the
 * compiled graph, the model, the encodings, the function contract, the root's contracts and the
 * regex library, and nothing depends on it but the facade — and the compiler's reference check,
 * which reads {@code EngineVars} for the names it must treat as writable. The graph performs the execution
 * (D35): the run and its collaborators are its state for one input, and there is nothing
 * between the model and the graph.
 */
package stroom.shapeshifter.engine.exec;
