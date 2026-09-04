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
 * How a run is being used (design 26 §4). An editor re-running a configuration on every edit
 * runs it in {@code PREVIEW}, where an {@link Purity#IMPURE} function is not called: its result
 * is absent and the run says so once per function.
 */
public enum RunMode {
    NORMAL,
    PREVIEW
}
