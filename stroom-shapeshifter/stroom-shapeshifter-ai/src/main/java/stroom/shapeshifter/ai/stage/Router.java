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

import stroom.expression.matcher.ExpressionMatcher;
import stroom.query.api.datasource.QueryField;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.RoutingRule;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a stream against the routing table (A22): rules in table order, the first whose selector
 * matches binds, a null selector matches everything. Evaluated by the matcher receive rules use, over
 * the fields of {@link RoutingFields}. A selector that names a header the stream does not carry does
 * not match — the matcher reports a missing attribute as an error, and here that is simply "not this
 * rule".
 */
public final class Router {

    private static final Map<String, QueryField> FIELDS = RoutingFields.FIELDS.stream()
            .collect(Collectors.toUnmodifiableMap(QueryField::getFldName, Function.identity()));

    private final ExpressionMatcher matcher = new ExpressionMatcher(FIELDS);

    public Optional<RoutingRule> route(final List<RoutingRule> table, final Map<String, Object> attributes) {
        for (final RoutingRule rule : table) {
            if (rule.getExpression() == null || matches(rule, attributes)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private boolean matches(final RoutingRule rule, final Map<String, Object> attributes) {
        try {
            return matcher.match(attributes, rule.getExpression());
        } catch (final RuntimeException e) {
            // The matcher's own exception type is private to it.
            return false;
        }
    }
}
