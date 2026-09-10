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
 * The executable graph: what a configuration compiles <i>to</i>.
 *
 * <p>D35's words, made a package. A {@code CompiledProject} <b>is</b> the executable graph, and
 * this is the vocabulary it is built from — the instructions, the references, the conditions, the
 * matches, the interned names, and the plan a run starts from.
 *
 * <h2>The two dependencies it has, and the two it must not</h2>
 *
 * <p>It depends on {@code config}, because D35 keeps the authored model beside the compiled one:
 * a compiled node carries the authored one for names, identifiers and messages rather than
 * copying them. And on {@code match}, {@code value}, {@code regex} and {@code function}, for the
 * things a compiled node <i>holds</i> — a pattern, a parser, a replacer, a typed value.
 *
 * <p><b>It must not depend on {@code compile}</b>, which builds it, or on {@code exec}, which
 * runs it. That is the whole point of the package existing, and both directions are load-bearing:
 *
 * <ul>
 *   <li><b>Not on the compiler.</b> A graph is a value. Nothing in it should be able to reach the
 *       thing that made it, and a reader should be able to learn what an instruction <i>is</i>
 *       without reading how it came to be. The {@code of} and {@code compile} factories here are
 *       public for the compiler's benefit and are the seam — they take the authored model and
 *       return a graph, and take no compiler with them.</li>
 *   <li><b>Not on the interpreter.</b> Design 27 ruling 8 removed a cycle between the compiled
 *       vocabulary and the run, and this keeps it removed by construction rather than by care.
 *       It is also what makes design 31 possible rather than ceremonial: an instruction can be
 *       given a {@code run} method against a context declared <i>here</i>, which {@code exec}
 *       implements, without the graph ever naming the interpreter.</li>
 * </ul>
 *
 * <p><b>This split was drawn on 2026-09-10 and described what was already true.</b> The classes
 * lived in {@code compile} beside the compiler, but no compiled class named a compiler class and
 * {@code exec} imported none of them — the layering existed and only the package was missing.
 * Drawing it costs a boundary that can now be enforced instead of remembered.
 */
package stroom.shapeshifter.engine.graph;
