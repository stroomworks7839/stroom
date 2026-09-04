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
 * Extension functions (design 26): the contract a function is written to, the registry a
 * configuration is compiled against, and what a bound function may reach at run time.
 * Mirrored on Stroom's Saxon extension-function library — a definition, a call bound per run,
 * a registry — with design 17's kinds as the type language and a {@code call} instruction as
 * the only way in. The engine ships no functions of its own here; whoever owns the engine
 * builds the registry.
 */
package stroom.shapeshifter.engine.function;
