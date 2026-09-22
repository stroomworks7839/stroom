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

package stroom.shapeshifter.ai.stage;

import java.util.List;

/**
 * The ledger of design 01 §5.2 (ruling A26): which input streams were sentinelled for which document and
 * shape, and why. Nothing is held — the input stays in the store and the ledger names it — so releasing
 * a shape is taking its inputs off the ledger and asking for them to be processed again (A12).
 * In-memory in scenarios; the {@code shapeshifter_ledger} table in a node.
 */
public interface Ledger {

    /// @param pipeline The uuid of the pipeline that was processing the input, where one was: a released
    ///                 input is replayed through the pipeline that sentinelled it, which may be neither
    ///                 the pipeline nor the node that releases it.
    void sentinelled(String docUuid, String shape, long inputId, String pipeline, String reason);

    /**
     * Take the shape's inputs off the ledger.
     *
     * @return The inputs that were on it, oldest first, each with the pipeline that sentinelled it.
     */
    List<Released> release(String docUuid, String shape);


    // --------------------------------------------------------------------------------


    /// One input taken off the ledger, and where it was being processed when it was put there.
    record Released(long inputId, String pipeline) {

    }
}
