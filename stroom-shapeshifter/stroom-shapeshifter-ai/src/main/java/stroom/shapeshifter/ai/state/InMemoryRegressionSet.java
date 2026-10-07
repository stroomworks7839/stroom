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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.RegressionSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The corpus as a map of lists: what scenarios run over — node-local and gone on restart. Every method
/// is synchronised: one node's tasks share it.
public final class InMemoryRegressionSet implements RegressionSet {

    private final Map<String, List<Accepted>> accepted = new HashMap<>();
    /// The feed each rule's newest entries came from.
    private final Map<String, String> feeds = new HashMap<>();

    @Override
    public synchronized List<Accepted> accepted(final String ruleUuid) {
        return List.copyOf(accepted.getOrDefault(ruleUuid, List.of()));
    }

    @Override
    public synchronized void accept(final String docUuid,
                                    final String ruleUuid,
                                    final String feed,
                                    final String type,
                                    final List<Accepted> entries,
                                    final int cap) {
        // As a node's store has it: a copy kept on no feed is not kept at all.
        Objects.requireNonNull(feed, "The feed to keep rule " + ruleUuid + "'s records on");
        append(ruleUuid, entries, cap);
        feeds.put(ruleUuid, feed);
    }

    @Override
    public synchronized Optional<String> feedOf(final String ruleUuid) {
        return accepted.containsKey(ruleUuid)
                ? Optional.ofNullable(feeds.get(ruleUuid))
                : Optional.empty();
    }

    @Override
    public synchronized void discard(final String ruleUuid) {
        accepted.remove(ruleUuid);
        feeds.remove(ruleUuid);
    }

    @Override
    public synchronized void move(final String fromRuleUuid, final String toRuleUuid, final int cap) {
        final List<Accepted> moving = accepted.remove(fromRuleUuid);
        final String movingFeed = feeds.remove(fromRuleUuid);
        if (moving != null) {
            append(toRuleUuid, moving, cap);
            // Appended after what the rule held, so they are its newest.
            if (movingFeed != null) {
                feeds.put(toRuleUuid, movingFeed);
            }
        }
    }

    private void append(final String ruleUuid, final List<Accepted> entries, final int cap) {
        final List<Accepted> kept = accepted.computeIfAbsent(ruleUuid, k -> new ArrayList<>());
        kept.addAll(entries);
        // The cap keeps the most recent; the oldest accepted records are the first to go.
        while (kept.size() > cap) {
            kept.remove(0);
        }
    }
}
