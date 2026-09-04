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

import stroom.pipeline.refdata.LookupIdentifier;
import stroom.pipeline.refdata.ReferenceDataResult;
import stroom.pipeline.refdata.store.RefDataValueProxy;

/** {@code lookup}: as {@code stroom.pipeline.xsltfunctions.Lookup}: one key in one map, at a time. */
public final class LookupFunction extends AbstractLookupFunction {

    public LookupFunction() {
        super("lookup");
    }

    @Override
    String lookup(final Call call, final LookupIdentifier identifier) {
        final ReferenceDataResult result = call.referenceData(identifier);
        if (result.getRefDataValueProxy().isEmpty()) {
            call.explainFailure(result);
            return null;
        }
        final RefDataValueProxy proxy = result.getRefDataValueProxy().get();
        final String value = call.render(proxy);
        call.explainValue(value != null, result);
        return value;
    }
}
