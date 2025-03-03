package stroom.query.common.v2;

import stroom.datasource.api.v2.FieldType;
import stroom.query.api.v2.Column;
import stroom.query.api.v2.Format;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactory;
import stroom.query.language.functions.Type;
import stroom.query.language.functions.Val;
import stroom.util.date.DateUtil;

import java.util.function.Function;

public class ValFunctionFactory implements ValueFunctionFactory<Val[]> {

    private final Column column;
    private final int index;

    public ValFunctionFactory(final Column column, final int index) {
        this.column = column;
        this.index = index;
    }

    @Override
    public Function<Val[], Boolean> createNullCheck() {
        return values -> stroom.query.language.functions.Type.NULL.equals(values[index].type());
    }

    @Override
    public Function<Val[], String> createStringExtractor() {
        return values -> values[index].toString();
    }

    @Override
    public Function<Val[], Long> createDateExtractor() {
        return values -> {
            final Val val = values[index];
            if (Type.LONG.equals(val.type()) || Type.DATE.equals(val.type())) {
                return val.toLong();
            } else {
                String string = val.toString();
                if (string != null) {
                    try {
                        return DateUtil.parseNormalDateTimeString(string);
                    } catch (final NumberFormatException e) {
                        return null;
                    }
                }
            }
            return null;
        };
    }

    @Override
    public Function<Val[], Double> createNumberExtractor() {
        return values -> {
            final Val val = values[index];
            try {
                return val.toDouble();
            } catch (final NumberFormatException e) {
                return null;
            }
        };
    }

    @Override
    public FieldType getFieldType() {
        FieldType fieldType = FieldType.TEXT;
        if (column.getFormat() != null) {
            if (Format.Type.NUMBER.equals(column.getFormat().getType())) {
                fieldType = FieldType.LONG;
            } else if (Format.Type.DATE_TIME.equals(column.getFormat().getType())) {
                fieldType = FieldType.DATE;
            }
        }
        return fieldType;
    }
}
