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
 * Execution: the runtime that takes a compiled configuration and an input, and produces output.
 *
 * <p>Values captured during matching are {@code TypedValue}s held in match-indexed {@code Store}s
 * inside a scoped {@code VarRegistry}; {@code Refs} resolves the configuration's expressions
 * against them; {@code Executor} is the loop that drives it all.
 */
package stroom.shapeshifter.engine.exec;
