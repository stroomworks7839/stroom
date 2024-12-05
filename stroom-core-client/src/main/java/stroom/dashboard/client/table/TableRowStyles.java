package stroom.dashboard.client.table;

import stroom.dashboard.client.table.cf.ConditionalFormattingDynamicStyles;
import stroom.dashboard.client.table.cf.ConditionalFormattingSwatchUtil;
import stroom.query.api.v2.ConditionalFormattingRule;
import stroom.query.api.v2.ConditionalFormattingType;
import stroom.query.api.v2.TextAttributes;
import stroom.query.client.presenter.TableRow;
import stroom.security.client.presenter.ClassNameBuilder;

import com.google.gwt.user.cellview.client.RowStyles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TableRowStyles implements RowStyles<TableRow> {

    private Map<String, ConditionalFormattingRule> conditionalFormattingRules = new HashMap<>();

    @Override
    public String getStyleNames(final TableRow row, final int rowIndex) {
        final ClassNameBuilder classNameBuilder = new ClassNameBuilder();
        if (row.getMatchingRule() != null) {
            ConditionalFormattingRule rule = conditionalFormattingRules.get(row.getMatchingRule());
            if (rule != null) {
                // Fixed styles.
                if (rule.getFormattingType() == null ||
                    ConditionalFormattingType.CUSTOM.equals(rule.getFormattingType())) {
                    classNameBuilder.addClassName(ConditionalFormattingDynamicStyles.create(rule.getCustomStyle()));
                } else if (ConditionalFormattingType.TEXT.equals(rule.getFormattingType())) {
                    classNameBuilder.addClassName(ConditionalFormattingSwatchUtil.CF_TEXT);
                    classNameBuilder.addClassName(rule.getFormattingStyle().getCssClassName());
                } else if (ConditionalFormattingType.BACKGROUND.equals(rule.getFormattingType())) {
                    classNameBuilder.addClassName(rule.getFormattingStyle().getCssClassName());
                }

                final TextAttributes textAttributes = rule.getTextAttributes();
                classNameBuilder.addClassName(ConditionalFormattingSwatchUtil
                        .getTextAttributeClassNames(textAttributes));
            }
        }

        return classNameBuilder.build();
    }

    public void setConditionalFormattingRules(final List<ConditionalFormattingRule> rules) {
        if (rules == null) {
            conditionalFormattingRules = new HashMap<>();
        } else {
            conditionalFormattingRules = rules
                    .stream()
                    .collect(Collectors.toMap(ConditionalFormattingRule::getId, c -> c));
        }
    }
}
