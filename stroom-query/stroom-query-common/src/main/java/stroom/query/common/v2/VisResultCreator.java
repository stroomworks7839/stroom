package stroom.query.common.v2;

import stroom.query.api.v2.FlatResult;
import stroom.query.api.v2.Result;
import stroom.query.api.v2.ResultRequest;
import stroom.query.api.v2.VisResult;
import stroom.query.util.LambdaLogger;
import stroom.query.util.LambdaLoggerFactory;
import stroom.util.string.ExceptionStringUtil;

import java.util.Collections;
import java.util.List;

public class VisResultCreator implements ResultCreator {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(VisResultCreator.class);

    private final FlatResultCreator resultCreator;

    public VisResultCreator(final FlatResultCreator resultCreator) {
        this.resultCreator = resultCreator;
    }

    @Override
    public Result create(final DataStore dataStore, final ResultRequest resultRequest) {
        final Result result = resultCreator.create(dataStore, resultRequest);
        if (result instanceof final FlatResult flatResult) {
            return mapVisResult(flatResult);
        }
        return null;
    }

    private VisResult mapVisResult(final FlatResult result) {
        String json = null;
        List<String> errors = result.getErrors();
        try {
            json = new VisJson().createJson(result);
        } catch (final RuntimeException e) {
            LOGGER.debug(e::getMessage, e);
            errors = Collections.singletonList(ExceptionStringUtil.getMessage(e));
        }

        return new VisResult(result.getComponentId(), json, result.getSize(), errors);
    }
}
