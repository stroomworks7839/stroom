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

import java.util.Optional;

/**
 * The shapes a stage has given up on (design §5, item 4): later records of a given-up shape are emitted
 * as sentinels without consulting the model. Promotion of a variant covering the shape releases it
 * (A12). In-memory in scenarios; a stream in a node.
 */
public interface Quarantine {

    Optional<String> reasonGivenUp(String feed, String shapeSignature);

    void giveUp(String feed, String shapeSignature, String reason);

    void release(String feed, String shapeSignature);
}
