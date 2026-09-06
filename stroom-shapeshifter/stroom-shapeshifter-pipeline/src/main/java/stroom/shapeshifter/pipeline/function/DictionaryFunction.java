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

import stroom.dictionary.api.WordListProvider;
import stroom.docref.DocRef;
import stroom.shapeshifter.engine.Severity;
import stroom.shapeshifter.engine.function.Arguments;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code dictionary}: as {@code stroom.pipeline.xsltfunctions.Dictionary}: a dictionary's combined
 * words by its uuid or, failing that, its name, remembered for the run.
 */
public final class DictionaryFunction extends StroomFunction {

    public DictionaryFunction() {
        super("dictionary", Signature.of(Kind.STRING, Kind.STRING), Purity.CONTEXT);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return new FunctionCall() {
            private final Map<String, String> cached = new HashMap<>();

            @Override
            public TypedValue call(final Arguments arguments) {
                final String name = requiredString(context, arguments, 0);
                if (name == null || name.isEmpty()) {
                    return null;
                }
                if (cached.containsKey(name)) {
                    return text(cached.get(name));
                }
                final WordListProvider provider = context.service(WordListProvider.class);
                if (provider == null) {
                    return null;
                }
                String result = null;
                try {
                    final Optional<DocRef> byUuid = provider.findByUuid(name);
                    DocRef docRef = byUuid.orElse(null);
                    if (docRef == null) {
                        final List<DocRef> byName = provider.findByName(name);
                        if (byName == null || byName.isEmpty()) {
                            context.warn("Dictionary not found with name '" + name
                                         + "'. You might not have permission to access this dictionary");
                        } else {
                            if (byName.size() > 1) {
                                context.message(Severity.INFO, "Multiple dictionaries found with name '" + name
                                                               + "' - using the first one that was created");
                            }
                            docRef = byName.getFirst();
                        }
                    }
                    if (docRef == null) {
                        context.message(Severity.INFO, "Unable to find dictionary " + name);
                    } else {
                        result = provider.getCombinedData(docRef);
                    }
                } catch (final RuntimeException e) {
                    context.error(e.getMessage());
                }
                cached.put(name, result);
                return text(result);
            }
        };
    }
}
