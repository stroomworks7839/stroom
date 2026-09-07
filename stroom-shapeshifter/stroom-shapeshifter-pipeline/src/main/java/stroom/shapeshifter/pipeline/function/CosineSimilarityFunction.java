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

import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.List;

/**
 * {@code cosine-similarity}: as {@code stroom.pipeline.xsltfunctions.CosineSimilarity}: two
 * sequences of numbers, read as floats as the Saxon class reads them, to their cosine.
 */
public final class CosineSimilarityFunction extends StroomFunction {

    public CosineSimilarityFunction() {
        super("cosine-similarity", Signature.of(Kind.NUMBER, Kind.SEQUENCE, Kind.SEQUENCE), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            final float[] a = floats(context, arguments.sequence(0));
            final float[] b = floats(context, arguments.sequence(1));
            if (a == null || b == null) {
                return null;
            }
            if (a.length != b.length) {
                context.error("Embedding dimensions must match");
                return null;
            }
            double dot = 0.0;
            double normA = 0.0;
            double normB = 0.0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            if (normA == 0 || normB == 0) {
                return new TypedValue.Double(0);
            }
            return new TypedValue.Double(dot / (Math.sqrt(normA) * Math.sqrt(normB)));
        };
    }

    private static float[] floats(final FunctionContext context, final List<TypedValue> values) {
        if (values == null) {
            return new float[0];
        }
        final float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) {
            final Double number = values.get(i).asNumber();
            if (number == null) {
                context.error("Embedding values must be numeric");
                return null;
            }
            result[i] = number.floatValue();
        }
        return result;
    }
}
