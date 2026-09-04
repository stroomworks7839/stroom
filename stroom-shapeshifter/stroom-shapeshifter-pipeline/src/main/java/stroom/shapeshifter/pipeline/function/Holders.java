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

import stroom.meta.shared.Meta;
import stroom.pipeline.state.MetaHolder;
import stroom.shapeshifter.engine.function.FunctionContext;

/** The pipeline's holders as a function reaches them, absent where a build has none. */
final class Holders {

    private Holders() {
    }

    /** The stream being processed, or null outside a pipeline or before one is set. */
    static Meta meta(final FunctionContext context) {
        final MetaHolder holder = context.service(MetaHolder.class);
        return holder == null ? null : holder.getMeta();
    }
}
