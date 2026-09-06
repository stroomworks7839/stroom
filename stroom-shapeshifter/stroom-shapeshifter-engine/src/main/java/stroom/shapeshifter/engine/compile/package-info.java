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
 * Compilation: turning an authored {@code Project} into something ready to run.
 *
 * <p>Patterns are compiled, delimiters pre-encoded, and everything the match loop needs is
 * inlined onto the template that needs it, so the hot path has no lookups to do. Compilation is
 * also where a configuration's errors are found — before any input has been read.
 *
 * <p>{@code Compiler} is the pipeline and the passes are their own classes: {@code MatchCompiler}
 * interns the patterns and compiles each template's match; {@code BodyCompiler} compiles
 * its body against them; {@code TemplateUses} collects what each body calls and applies to, for
 * name resolution and the dispatch lint; {@code ReferenceCheck} walks every body once for what
 * reads what; {@code StructureCheck} judges what an element's body may contain; and
 * {@code Containers} says once which instructions hold bodies. The result is the
 * executable graph, {@code CompiledProject} (D35).
 *
 * <p>This package depends on the model, the values (the compile-time halves of the date
 * functions), the matching (the pattern key it interns by, the codecs it refuses), the text
 * encodings, the function contract, the root's messages and the regex library; the run and the
 * facade depend on it, and nothing below it does (design 27 §2.5).
 */
package stroom.shapeshifter.engine.compile;
