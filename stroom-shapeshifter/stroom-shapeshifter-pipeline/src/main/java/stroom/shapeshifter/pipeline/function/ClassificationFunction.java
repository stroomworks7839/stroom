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

package stroom.shapeshifter.pipeline.function;

import stroom.feed.api.FeedProperties;
import stroom.pipeline.state.FeedHolder;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

/** {@code classification}: as {@code stroom.pipeline.xsltfunctions.Classification}: the feed's classification. */
public final class ClassificationFunction extends StroomFunction {

    public ClassificationFunction() {
        super("classification", Signature.of(Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final FeedHolder feedHolder = context.service(FeedHolder.class);
            final FeedProperties feedProperties = context.service(FeedProperties.class);
            if (feedHolder == null || feedProperties == null || feedHolder.getFeedName() == null) {
                return null;
            }
            try {
                return text(feedProperties.getDisplayClassification(feedHolder.getFeedName()));
            } catch (final RuntimeException e) {
                context.error(e.getMessage());
                return null;
            }
        };
    }
}
