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

package stroom.shapeshifter.engine.compile;

import stroom.shapeshifter.engine.function.FunctionDefinition;
import stroom.shapeshifter.engine.function.FunctionRegistry;

import java.util.Map;

/**
 * What a compile knows about functions: the registry it resolves names in, and the definitions
 * the configuration turned out to use — what the run binds (design 26 §3).
 */
record Functions(FunctionRegistry registry, Map<String, FunctionDefinition> used) {

}
