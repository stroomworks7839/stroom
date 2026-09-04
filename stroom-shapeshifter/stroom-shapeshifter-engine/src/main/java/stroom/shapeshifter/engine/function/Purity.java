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

package stroom.shapeshifter.engine.function;

/**
 * What a function depends on and does (design 26 §4). {@code PURE}: the arguments only.
 * {@code CONTEXT}: the run's context too — the feed, the clock — but no effect. {@code IMPURE}:
 * an effect, or a reach outside the process; not run in {@link RunMode#PREVIEW}.
 */
public enum Purity {
    PURE,
    CONTEXT,
    IMPURE
}
