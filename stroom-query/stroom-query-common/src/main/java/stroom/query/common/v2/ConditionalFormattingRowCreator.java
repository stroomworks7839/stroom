package stroom.query.common.v2;

import stroom.expression.api.DateTimeSettings;
import stroom.query.api.v2.Column;
import stroom.query.api.v2.ConditionalFormattingRule;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.Row;
import stroom.query.common.v2.format.ColumnFormatter;
import stroom.query.language.functions.ref.ErrorConsumer;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ConditionalFormattingRowCreator extends FilteredRowCreator {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ConditionalFormattingRowCreator.class);

    private final ExpressionOperator rowFilter;
    private final List<ConditionalFormattingRule> rules;
    private final ColumnExpressionMatcher expressionMatcher;

    private ConditionalFormattingRowCreator(final List<Column> originalColumns,
                                            final List<Column> newColumns,
                                            final ColumnFormatter columnFormatter,
                                            final KeyFactory keyFactory,
                                            final ExpressionOperator rowFilter,
                                            final List<ConditionalFormattingRule> rules,
                                            final ColumnExpressionMatcher expressionMatcher,
                                            final ErrorConsumer errorConsumer) {
        super(originalColumns, newColumns, columnFormatter, keyFactory, rowFilter, expressionMatcher, errorConsumer);

        this.rowFilter = rowFilter;
        this.rules = rules;
        this.expressionMatcher = expressionMatcher;
    }

    public static Optional<ItemMapper<Row>> create(final List<Column> originalColumns,
                                                   final List<Column> newColumns,
                                                   final ColumnFormatter columnFormatter,
                                                   final KeyFactory keyFactory,
                                                   final ExpressionOperator rowFilter,
                                                   final List<ConditionalFormattingRule> rules,
                                                   final DateTimeSettings dateTimeSettings,
                                                   final ErrorConsumer errorConsumer) {
        // Create conditional formatting expression matcher.
        if (rules != null) {
            final List<ConditionalFormattingRule> activeRules = rules
                    .stream()
                    .filter(ConditionalFormattingRule::isEnabled)
                    .collect(Collectors.toList());
            if (activeRules.size() > 0) {
                final ColumnExpressionMatcher expressionMatcher =
                        new ColumnExpressionMatcher(newColumns, dateTimeSettings);
                return Optional.of(new ConditionalFormattingRowCreator(
                        originalColumns,
                        newColumns,
                        columnFormatter,
                        keyFactory,
                        rowFilter,
                        activeRules,
                        expressionMatcher,
                        errorConsumer));
            }
        }

        return Optional.empty();
    }

    @Override
    public Row create(final Item item,
                      final List<String> stringValues,
                      final Map<String, Object> fieldIdToValueMap) {
        Row row = null;

        // Find a matching rule.
        ConditionalFormattingRule matchingRule = null;

        try {
            // See if we can exit early by applying row filter.
            if (rowFilter != null) {
                if (!expressionMatcher.match(fieldIdToValueMap, rowFilter)) {
                    return null;
                }
            }

            for (final ConditionalFormattingRule rule : rules) {
                try {
                    final ExpressionOperator operator = rule.getExpression();
                    final boolean match = expressionMatcher.match(fieldIdToValueMap, operator);
                    if (match) {
                        matchingRule = rule;
                        break;
                    }
                } catch (final RuntimeException e) {
                    final RuntimeException exception = new RuntimeException(
                            "Error applying conditional formatting rule: " +
                                    rule.toString() +
                                    " - " +
                                    e.getMessage());
                    LOGGER.debug(exception.getMessage(), exception);
                    errorConsumer.add(exception);
                }
            }
        } catch (final RuntimeException e) {
            LOGGER.debug(e.getMessage(), e);
            errorConsumer.add(e);
        }

        if (matchingRule != null) {
            if (!matchingRule.isHide()) {
                final Row.Builder builder = Row.builder()
                        .groupKey(keyFactory.encode(item.getKey(), errorConsumer))
                        .values(stringValues)
                        .depth(item.getKey().getDepth());

                if (matchingRule.getBackgroundColor() != null
                        && !matchingRule.getBackgroundColor().isEmpty()) {
                    builder.backgroundColor(matchingRule.getBackgroundColor());
                }
                if (matchingRule.getTextColor() != null
                        && !matchingRule.getTextColor().isEmpty()) {
                    builder.textColor(matchingRule.getTextColor());
                }

                row = builder.build();
            }
        } else {
            row = Row.builder()
                    .groupKey(keyFactory.encode(item.getKey(), errorConsumer))
                    .values(stringValues)
                    .depth(item.getKey().getDepth())
                    .build();
        }

        return row;
    }

    @Override
    public boolean hidesRows() {
        return true;
    }
}
