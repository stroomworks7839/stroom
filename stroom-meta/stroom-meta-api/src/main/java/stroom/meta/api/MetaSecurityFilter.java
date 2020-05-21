package stroom.meta.api;

import stroom.query.api.v2.ExpressionOperator;

import java.util.List;
import java.util.Optional;

public interface MetaSecurityFilter {
    Optional<ExpressionOperator> getExpression(String permission, List<String> fields);
}
