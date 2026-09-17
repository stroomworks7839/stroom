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
    public Optional<String> reasonGivenUp(final String feed, final String shapeSignature) {
        return Optional.ofNullable(reasons.get(key(feed, shapeSignature)));
    }

    @Override
    public void giveUp(final String feed, final String shapeSignature, final String reason) {
        reasons.put(key(feed, shapeSignature), reason);
    }

    @Override
    public void release(final String feed, final String shapeSignature) {
        reasons.remove(key(feed, shapeSignature));
    }

    public boolean isEmpty() {
        return reasons.isEmpty();
    }

    private static String key(final String feed, final String shapeSignature) {
        return feed + "/" + shapeSignature;
    }
}
