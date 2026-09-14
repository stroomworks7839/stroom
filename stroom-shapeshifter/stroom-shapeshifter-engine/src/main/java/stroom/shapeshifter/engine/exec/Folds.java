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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.config.Cast;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.value.Comparisons;
import stroom.shapeshifter.engine.value.Transforms;
import stroom.shapeshifter.engine.value.TypedValue;

import java.util.List;

/**
 * The folds — {@code sum}, {@code avg}, {@code min}, {@code max} — as functions over a
 * collection's members (design 35 §5; design/16 §8's rules kept).
 *
 * <p>The empty collection answers as XPath does, which is not the same answer twice:
 * {@code sum(())} is zero and {@code avg(())} is empty. Zero is a real total of nothing; a mean
 * of nothing is not a number, and returning zero for it would be a number that looks like an
 * answer. {@code size} is not here: it is an accessor, and applies to every collection.
 */
final class Folds {

    private Folds() {
    }

    static TypedValue fold(final RefExpression.RefPart.Accessor.Kind kind,
                           final List<TypedValue> values,
                           final Cast as) {
        return switch (kind) {
            case SUM -> values.isEmpty() ? new TypedValue.Integer(0) : Transforms.add(values);
            case AVG -> {
                if (values.isEmpty()) {
                    yield null;
                }
                final TypedValue total = Transforms.add(values);
                final Double sum = total == null ? null : total.asNumber();
                yield sum == null ? null : new TypedValue.Double(sum / values.size());
            }
            case MIN, MAX -> extreme(values, as, kind == RefExpression.RefPart.Accessor.Kind.MIN);
            default -> throw new IllegalArgumentException(kind + " is not a fold");
        };
    }

    /**
     * The smallest or largest entry under design/17 §8's ordering. An entry whose cast fails
     * does not participate — the same "this value did not participate" that reads false in a
     * condition and sorts last in an ordering — and if none participates the answer is absent.
     */
    private static TypedValue extreme(final List<TypedValue> values,
                                      final Cast as,
                                      final boolean smallest) {
        TypedValue best = null;
        for (final TypedValue value : values) {
            // Uncast, an ordering compares string forms: the one total reading (17 §8).
            final TypedValue candidate = Comparisons.cast(value, as == null ? Cast.STRING : as);
            if (candidate == null) {
                continue;
            }
            if (best == null) {
                best = candidate;
                continue;
            }
            final Integer order = Comparisons.compare(candidate, best);
            if (order != null && (smallest ? order < 0 : order > 0)) {
                best = candidate;
            }
        }
        return best;
    }
}
