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

/// One input stream to be processed again (A12), and where: a stream is replayed through the pipeline
/// that processed it the first time, since one Shapeshifter AI document may be used by several and a
/// stream replayed through a pipeline that never saw it produces something nobody asked for.
///
/// What the ledger hands back when a shape settles, and what the outputs hand back when a rule is
/// retracted — the same answer to the same question, from the two places that know it.
///
/// @param pipeline The uuid of the pipeline, or null where nothing was processing it: a harness, or the
///                 deferred worker, which runs no pipeline of its own.
public record Replayable(long inputId, String pipeline) {

}
