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
 *   <li><b>Not on the compiler.</b> A graph is a value, and <b>nothing here builds one</b>: there
 *       are no factories, no {@code of}, no {@code compile}. Every node is constructed by the
 *       pass that decided its contents, so a reader learns what an instruction <i>is</i> from
 *       this package and how it came to be from {@code compile}, and neither file has to explain
 *       the other. The one method that mutates a node after construction — an apply's or a call's
 *       {@code link} — takes what it is given rather than working it out, because working it out
 *       meant reading the authored model.</li>
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
 *
 * <p><b>Everything compiled is here</b>, since the same day: the step interpreter's compiled
 * vocabulary (retired by design 38) had been in {@code match} beside the interpreter that ran
 * it, kept there by what looked like a package cycle. It was not one — {@code match} was
 * holding four things at once, and separating them left this package holding the whole
 * compiled vocabulary with nothing to except.
 *
 * <p>The building came out the same day. Each node had carried a static factory reading the
 * authored model, which is compiler work sitting on the thing it makes; those are
 * {@code RefCompiler}, {@code ConditionCompiler}, {@code CaptureCompiler}, {@code RootPlanner}
 * and one private method of {@code Compiler} now. What is left here depends on {@code config}
 * and that is D35 rather than a leak: a compiled node <i>carries</i> the authored one for names,
 * identifiers and messages, and reads it nowhere.
 */
package stroom.shapeshifter.engine.graph;
