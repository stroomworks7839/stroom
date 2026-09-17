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

package stroom.shapeshifter.config.json;

/**
 * A JSON number, held as its literal text so that {@code 5} and {@code 5.0} stay what they
 * were: a literal's spelling is its declared type (design 17 §8) — {@code 80} is whole and
 * {@code 80.0} is fractional — and a parser that had already made both a double could not
 * tell them apart. So whichever parser feeds this tree must hand over the spelling, not a
 * double; a JavaScript {@code JSON.parse} cannot, which is why the client needs a parser of
 * its own (design 43).
 */
public record JsonNumber(String literal) implements JsonValue {

    public static JsonNumber of(final long value) {
        return new JsonNumber(Long.toString(value));
    }

    /** A fractional number: always spelt with a fraction, {@code 80.0}, because the spelling is the type. */
    public static JsonNumber of(final double value) {
        return new JsonNumber(Double.toString(value));
    }

    @Override
    public String shape() {
        return "number";
    }

    @Override
    public boolean isNumber() {
        return true;
    }

    @Override
    public boolean isIntegralNumber() {
        return literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0;
    }

    /** The spelling: a number where text was asked for reads as its literal, as Jackson's accessor would. */
    @Override
    public String asString() {
        return literal;
    }

    @Override
    public int asInt() {
        return (int) asLong();
    }

    @Override
    public long asLong() {
        return isIntegralNumber() ? Long.parseLong(literal) : (long) asDouble();
    }

    @Override
    public double asDouble() {
        return Double.parseDouble(literal);
    }
}
