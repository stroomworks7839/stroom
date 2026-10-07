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

import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Rules;
import stroom.shapeshifter.shared.RoutingRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// The bindings of A41 in memory, until the A26 module holds them. Every method is synchronised: one node's
/// tasks share it.
public final class InMemoryRules implements Rules {

    private final List<Held> rows = new ArrayList<>();

    @Override
    public synchronized List<RoutingRule> forLearner(final Learner learner) {
        return rows.stream()
                .filter(row -> row.learner().equals(learner))
                .map(Held::rule)
                .toList();
    }

    @Override
    public synchronized Optional<Held> byUuid(final String ruleUuid) {
        return rows.stream()
                .filter(row -> ruleUuid.equals(row.rule().getUuid()))
                .findFirst();
    }

    @Override
    public synchronized RoutingRule append(final Learner learner, final String docUuid, final RoutingRule rule) {
        final RoutingRule stored = rule.getUuid() == null
                ? rule.copy().uuid(UUID.randomUUID().toString()).build()
                : rule;
        rows.add(new Held(learner, docUuid, stored));
        return stored;
    }

    @Override
    public synchronized void replace(final Learner learner, final String docUuid, final RoutingRule rule) {
        for (int i = 0; i < rows.size(); i++) {
            final Held row = rows.get(i);
            if (row.rule().getUuid().equals(rule.getUuid())) {
                // As the table has it: a uuid names one binding across every learner, so another
                // learner's is not this one's to overwrite.
                if (!row.learner().equals(learner)) {
                    throw new IllegalStateException("Rule " + rule.getUuid() + " is a binding of "
                                                    + row.learner() + ", not of " + learner);
                }
                rows.set(i, new Held(learner, docUuid, rule));
                return;
            }
        }
        rows.add(new Held(learner, docUuid, rule));
    }

    @Override
    public synchronized void remove(final Learner learner, final String ruleUuid) {
        rows.removeIf(row -> row.learner().equals(learner) && ruleUuid.equals(row.rule().getUuid()));
    }

    /// The learner's bindings brought to the document it runs under (see [InMemoryLearners]).
    ///
    /// @param learner The learner.
    /// @param docUuid The document.
    public synchronized void runsUnder(final Learner learner, final String docUuid) {
        rows.replaceAll(row -> row.learner().equals(learner)
                ? new Held(learner, docUuid, row.rule())
                : row);
    }

    /// Every row of every learner, oldest first: what the Supervisor's list reads in memory.
    ///
    /// @return The rows.
    public synchronized List<Held> all() {
        return List.copyOf(rows);
    }
}
