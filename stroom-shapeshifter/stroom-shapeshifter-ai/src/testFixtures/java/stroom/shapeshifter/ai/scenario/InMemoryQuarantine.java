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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.stage.Quarantine;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryQuarantine implements Quarantine {

    private final Map<String, String> reasons = new HashMap<>();

    @Override
    public Optional<String> reasonGivenUp(final String docUuid, final String shape) {
        return Optional.ofNullable(reasons.get(key(docUuid, shape)));
    }

    @Override
    public void giveUp(final String docUuid, final String shape, final String reason) {
        reasons.put(key(docUuid, shape), reason);
    }

    @Override
    public void release(final String docUuid, final String shape) {
        reasons.remove(key(docUuid, shape));
    }

    public boolean isEmpty() {
        return reasons.isEmpty();
    }

    private static String key(final String docUuid, final String shape) {
        return docUuid + "/" + shape;
    }
}
