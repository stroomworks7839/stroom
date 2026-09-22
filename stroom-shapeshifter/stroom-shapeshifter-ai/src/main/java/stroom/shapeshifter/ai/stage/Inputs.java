/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai.stage;

import java.util.Optional;

/// The streams an attempt was raised on, by the meta id its row names (A28): what lets an attempt be
/// carried on outside the task that raised it. Deferred mode's worker re-walks the dialogue over the
/// same sample, and the sample is the stream, which is kept where every stream is kept rather than
/// copied into the attempt — a stream's own text may not be stored until redaction is built (A17, A38),
/// and storing it twice would be storing it twice.
///
/// A node reads the stream store; a scenario answers from what it put there.
public interface Inputs {

    /// The stream, or empty where it has been deleted since — a shape whose only example is gone cannot
    /// be learned, and the attempt that was learning it is closed rather than left waiting.
    Optional<Input> byId(long metaId);
}
