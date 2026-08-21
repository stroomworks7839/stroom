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
 * The configuration model: what a Shapeshifter configuration <i>is</i>, with no opinion about
 * how it is stored or executed.
 *
 * <p>A plain tree of records and sealed interfaces, deliberately free of framework annotations.
 * Serialisation lives in the {@code json} subpackage; compilation and execution live above it.
 * Keeping the model inert is what lets the format be replaced without the engine noticing.
 */
package stroom.shapeshifter.engine.config;
