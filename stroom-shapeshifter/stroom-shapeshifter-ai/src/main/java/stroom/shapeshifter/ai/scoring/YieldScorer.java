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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;

/**
 * Yield (design §8.4): output records per unit of input, scored as how close the ratio comes to the
 * expected one — {@code min(actual, expected) / max(actual, expected)}, so too many records lose marks
 * as too few do. A record is a child element of the output's root, which is what both {@code records:2}
 * and {@code event-logging:3} documents make of one. Applies to a step that produced XML; when the basis
 * is input records and the input is not XML, there is nothing to count against, and the scorer does
 * not apply.
 */
public final class YieldScorer implements Scorer {

    private static final ElementId YIELD = new ElementId("Yield");

    @Override
    public ScorerType type() {
        return ScorerType.YIELD;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters, final Attempted step) {
        final YieldParameters yield = (YieldParameters) parameters;
        final String output = step.result().output();
        if (output == null || !isXml(output)) {
            return Optional.empty();
        }
        final int records = Records.count(output);
        final double units = switch (yield.getBasis()) {
            case RECORDS -> isXml(step.input())
                    ? Records.count(step.input())
                    : -1;
            case LINES -> step.input().lines().filter(line -> !line.isBlank()).count();
            case BYTES -> step.input().length();
        };
        if (units < 0) {
            return Optional.empty();
        }
        final double actual = units == 0
                ? 0.0
                : records / units;
        final double expected = yield.getExpectedRatio();
        final double value = actual == expected
                ? 1.0
                : Math.min(actual, expected) / Math.max(actual, expected);
        final List<StoredError> diagnostics = value < 1.0
                ? List.of(new StoredError(Severity.WARNING, null, YIELD,
                records + " record(s) from " + (long) units + " input " + unitName(yield.getBasis())
                + " is " + actual + " per unit against an expected " + expected))
                : List.of();
        return Optional.of(new Score(type(), value, diagnostics));
    }

    private static boolean isXml(final String text) {
        return text != null && text.stripLeading().startsWith("<");
    }

    private static String unitName(final YieldBasis basis) {
        return switch (basis) {
            case RECORDS -> "record(s)";
            case LINES -> "line(s)";
            case BYTES -> "byte(s)";
        };
    }
}
