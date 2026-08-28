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

import stroom.shapeshifter.regex.Encoding;

/**
 * The interned-pattern key: the same source text compiled for two encodings is two different
 * byte machines, so text alone stopped being an identity when phase 3 let templates bring
 * their own (design 19 — the deferral phase 1 recorded, landing where a second key value
 * first exists).
 */
public record PatternKey(String text, Encoding encoding) {

}
