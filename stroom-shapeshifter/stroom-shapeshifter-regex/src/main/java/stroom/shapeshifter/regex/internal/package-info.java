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
 * The machinery, none of it API: {@code Parser} and {@code Lowering} produce {@code Hir},
 * which compiles to one of the executable forms — a {@code Plan} for one-pass patterns, an
 * {@code Nfa} for the rest, a {@code NodeTree} for the fancy tier and the ambiguous patterns
 * it runs first for (D31/D32).
 *
 * <p>Four machines execute them: {@code PlanRunner} (a straight-line scan), {@code PikeVm}
 * (the linear-time simulation and the guarantee), {@code Backtracker} (bounded, retired from
 * the default path, kept as a differential witness) and {@code FancyBacktracker} (unbounded
 * with a step budget, the tree's structural fallback). Every engine must give identical
 * answers — {@code DifferentialTest} and the corpus suites hold them to it — and shared
 * semantics live in one place each: assertions in {@code Words}, UTF-8 stepping in
 * {@code Utf8}, length and anchor facts in {@code Analysis}.
 */
package stroom.shapeshifter.regex.internal;
