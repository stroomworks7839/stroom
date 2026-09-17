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
 * {@code EngineVars} names the engine's functions — {@code matchCount()}, {@code position()}
 * and the rest — which the compiler resolves to frame reads; nothing in the language is reserved.
 *
 * <p>This module is shared with the GWT client, which edits the same model the engine runs
 * (design 43): so it is JDK only and Java 17 — no switch patterns, no {@code UUID}, ids are
 * text — and its build compiles it at release 17 and with GWT to keep it so. The engine's
 * compiler, which switches over these families with patterns, is unaffected: a sealed record
 * family compiled at 17 is still sealed for it. Two GWT 2.13 quirks shape the source: a sealed
 * interface nested in a record needs an explicit {@code permits} clause, and a record whose
 * other constructor delegates with {@code this(...)} needs its canonical constructor spelt out.
 */
package stroom.shapeshifter.config;
