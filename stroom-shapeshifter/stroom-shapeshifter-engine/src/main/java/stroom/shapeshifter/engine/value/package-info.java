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
 * Values: what a match captures and a body computes with.
 *
 * <p>{@code TypedValue} is the captured value and its five readings — bytes, text, number,
 * whole, boolean — with bytes the default and the common case; {@code Numbers} reads numbers
 * out of text without throwing; {@code Comparisons} is the one strict ordering and the explicit
 * cast table (design 17 §8); {@code Transforms} are the pure functions a body applies;
 * {@code Dates} parse and format the date pair (design 17 §9). Nothing here knows a match, a
 * level or a run: this package depends on the configuration's casts and exceptions and on the
 * regex library, and everything above it depends on this (design 27 §2.5).
 */
package stroom.shapeshifter.engine.value;
