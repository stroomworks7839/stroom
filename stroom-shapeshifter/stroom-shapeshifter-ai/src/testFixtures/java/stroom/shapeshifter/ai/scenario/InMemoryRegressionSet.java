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

import stroom.shapeshifter.ai.stage.RegressionSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryRegressionSet implements RegressionSet {

    private final Map<String, List<Accepted>> accepted = new HashMap<>();

    @Override
    public List<Accepted> accepted(final String ruleUuid) {
        return List.copyOf(accepted.getOrDefault(ruleUuid, List.of()));
    }

    @Override
    public void accept(final String ruleUuid, final List<Accepted> records, final int cap) {
        final List<Accepted> kept = accepted.computeIfAbsent(ruleUuid, k -> new ArrayList<>());
        kept.addAll(records);
        // The cap keeps the most recent; the oldest accepted records are the first to go.
        while (kept.size() > cap) {
            kept.remove(0);
        }
    }
}
