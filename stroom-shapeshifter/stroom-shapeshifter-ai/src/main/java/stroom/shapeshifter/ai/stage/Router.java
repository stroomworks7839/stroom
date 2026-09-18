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
import stroom.meta.shared.MetaFields;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.datasource.QueryField;
import stroom.shapeshifter.shared.RoutingFields;
import stroom.shapeshifter.shared.RoutingRule;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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

    /**
     * Whether a rule's variant is worth trying on a stream of another shape (design 01 §6): the rule
     * binds for the same feed and type, judged from its selector alone — every {@code Feed} or
     * {@code Type} term at the top level must agree with the stream, and a selector too intricate to
     * read that way (an {@code OR} or {@code NOT} at the root) is left alone.
     */
    public static boolean compatible(final RoutingRule rule, final String feed, final String type) {
        final ExpressionOperator expression = rule.getExpression();
        if (expression == null) {
            return true;
        }
        if (expression.op() != Op.AND) {
            return false;
        }
        for (final ExpressionItem item : expression.getEnabledChildren()) {
            if (!(item instanceof final ExpressionTerm term)) {
                return false;
            }
            final String actual = MetaFields.FIELD_FEED.equals(term.getField())
                    ? feed
                    : MetaFields.FIELD_TYPE.equals(term.getField())
                            ? type
                            : null;
            if (actual == null) {
                continue;
            }
            final boolean equal = valueMatches(term.getValue(), actual);
            if (term.getCondition() == Condition.EQUALS && !equal
                || term.getCondition() == Condition.NOT_EQUALS && equal) {
                return false;
            }
        }
        return true;
    }

    /**
     * A term value as the matcher reads it: a case-insensitive pattern with {@code *} for anything; a
     * value the matcher could not compile matches nothing, as it would there.
     */
    private static boolean valueMatches(final String termValue, final String actual) {
        if (termValue == null || termValue.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(termValue.trim().replaceAll("\\*", ".*"), Pattern.CASE_INSENSITIVE)
                    .matcher(actual)
                    .matches();
        } catch (final PatternSyntaxException e) {
            return false;
        }
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
