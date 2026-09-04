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

import stroom.shapeshifter.engine.exec.TypedValue;
import stroom.shapeshifter.engine.function.FunctionCall;
import stroom.shapeshifter.engine.function.FunctionContext;
import stroom.shapeshifter.engine.function.Kind;
import stroom.shapeshifter.engine.function.Purity;
import stroom.shapeshifter.engine.function.Signature;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code pointIsInsideXYPolygon}: as {@code stroom.pipeline.xsltfunctions.PointIsInsideXYPolygon}:
 * a point's x and y, then the polygon's x values and y values as two sequences. False, with a
 * warning, for anything the Saxon class warns about.
 */
public final class PointIsInsideXyPolygonFunction extends StroomFunction {

    public PointIsInsideXyPolygonFunction() {
        super("pointIsInsideXYPolygon",
                Signature.of(Kind.BOOLEAN, Kind.NUMBER, Kind.NUMBER, Kind.SEQUENCE, Kind.SEQUENCE), Purity.PURE);
    }

    @Override
    public FunctionCall bind(final FunctionContext context) {
        return arguments -> {
            try {
                final Double x = arguments.number(0);
                final Double y = arguments.number(1);
                if (x == null) {
                    context.warn("Illegal non numeric argument found in function " + name() + "() at position 0");
                }
                if (y == null) {
                    context.warn("Illegal non numeric argument found in function " + name() + "() at position 1");
                }
                final List<Double> xs = numbers(context, arguments.sequence(2), 2);
                final List<Double> ys = numbers(context, arguments.sequence(3), 3);
                final double[][] polygon = polygon(xs, ys);
                final boolean inside = x != null && y != null && isPointInPolygon(x, y, polygon);
                return new TypedValue.Bool(inside);
            } catch (final RuntimeException e) {
                context.warn(e.getMessage());
                return new TypedValue.Bool(false);
            }
        };
    }

    private List<Double> numbers(final FunctionContext context, final List<TypedValue> values, final int position) {
        final List<Double> result = new ArrayList<>();
        if (values != null) {
            for (final TypedValue value : values) {
                final Double number = value.asNumber();
                if (number == null) {
                    context.warn("Illegal non numeric value in sequence provided to function " + name()
                                 + "() at position " + position);
                } else {
                    result.add(number);
                }
            }
        }
        return result;
    }

    private double[][] polygon(final List<Double> xs, final List<Double> ys) {
        if (xs.isEmpty()) {
            throw new IllegalArgumentException("No x values for polygon in XSLT function " + name());
        }
        if (ys.isEmpty()) {
            throw new IllegalArgumentException("No x values for polygon in XSLT function " + name());
        }
        if (xs.size() < 3) {
            throw new IllegalArgumentException("Too few points for polygon in XSLT function " + name());
        }
        if (xs.size() != ys.size()) {
            throw new IllegalArgumentException(
                    "Different numbers of x and y values for polygon provided to XSLT function " + name());
        }
        final double[][] result = new double[xs.size()][2];
        for (int i = 0; i < result.length; i++) {
            result[i][0] = xs.get(i);
            result[i][1] = ys.get(i);
        }
        return result;
    }

    private static boolean isPointInPolygon(final double px, final double py, final double[][] polygon) {
        double minX = polygon[0][0];
        double maxX = polygon[0][0];
        double minY = polygon[0][1];
        double maxY = polygon[0][1];
        for (int i = 1; i < polygon.length; i++) {
            minX = Math.min(polygon[i][0], minX);
            maxX = Math.max(polygon[i][0], maxX);
            minY = Math.min(polygon[i][1], minY);
            maxY = Math.max(polygon[i][1], maxY);
        }
        if (px < minX || px > maxX || py < minY || py > maxY) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
            if ((polygon[i][1] > py) != (polygon[j][1] > py)
                && px < (polygon[j][0] - polygon[i][0]) * (py - polygon[i][1])
                        / (polygon[j][1] - polygon[i][1]) + polygon[i][0]) {
                inside = !inside;
            }
        }
        return inside;
    }
}
