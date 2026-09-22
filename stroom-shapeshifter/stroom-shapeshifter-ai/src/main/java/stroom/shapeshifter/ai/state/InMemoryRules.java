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

package stroom.shapeshifter.ai.state;

import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.RoutingRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/// The rules of A41 in memory, until the A26 module holds them. Every method is synchronised: one node's
/// tasks share it.
public final class InMemoryRules implements Rules {

    private final Map<String, List<RoutingRule>> byDocument = new HashMap<>();

    @Override
    public synchronized List<RoutingRule> forDocument(final String docUuid) {
        return List.copyOf(rules(docUuid));
    }

    @Override
    public synchronized Optional<RoutingRule> byUuid(final String docUuid, final String ruleUuid) {
        return rules(docUuid).stream()
                .filter(rule -> ruleUuid.equals(rule.getUuid()))
                .findFirst();
    }

    @Override
    public synchronized RoutingRule append(final String docUuid, final RoutingRule rule) {
        final RoutingRule stored = rule.getUuid() == null
                ? rule.copy().uuid(UUID.randomUUID().toString()).build()
                : rule;
        rules(docUuid).add(stored);
        return stored;
    }

    @Override
    public synchronized RoutingRule insert(final String docUuid, final RoutingRule rule, final int at) {
        final RoutingRule stored = rule.getUuid() == null
                ? rule.copy().uuid(UUID.randomUUID().toString()).build()
                : rule;
        final List<RoutingRule> rules = rules(docUuid);
        rules.add(Math.max(0, Math.min(at, rules.size())), stored);
        return stored;
    }

    @Override
    public synchronized void move(final String docUuid, final String ruleUuid, final int to) {
        final List<RoutingRule> rules = rules(docUuid);
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).getUuid().equals(ruleUuid)) {
                rules.add(Math.max(0, Math.min(to, rules.size() - 1)), rules.remove(i));
                return;
            }
        }
    }

    @Override
    public synchronized void replace(final String docUuid, final RoutingRule rule) {
        final List<RoutingRule> rules = rules(docUuid);
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).getUuid().equals(rule.getUuid())) {
                rules.set(i, rule);
                return;
            }
        }
        rules.add(rule);
    }

    @Override
    public synchronized void remove(final String docUuid, final String ruleUuid) {
        rules(docUuid).removeIf(rule -> ruleUuid.equals(rule.getUuid()));
    }

    private List<RoutingRule> rules(final String docUuid) {
        return byDocument.computeIfAbsent(docUuid, key -> new ArrayList<>());
    }
}
