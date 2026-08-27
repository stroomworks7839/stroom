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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E26's fix must be <b>behaviour-neutral</b>: the non-throwing casts have to accept and
 * reject exactly what {@code Long.valueOf} and {@code Double.valueOf} did, or a performance
 * change has quietly become a semantic one.
 *
 * <p>So this does not assert the cases somebody thought of — it asserts equivalence against
 * the parsers that were replaced, over every string the corpus below can reach: signs,
 * boundaries, overflow either side, non-ASCII digits (which Java reads and the fix must
 * therefore keep reading), the forms {@code Double} accepts and {@code Long} does not, and a
 * few thousand random strings drawn from an alphabet chosen to land near the interesting
 * edges rather than in the middle of nowhere.
 */
class TypedValueParseEquivalenceTest {

    /** What the replaced parser did: a value, or null where it threw. */
    private static Long oldWhole(final String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Double oldNumber(final String text) {
        try {
            return Double.valueOf(text.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static List<String> corpus() {
        final List<String> cases = new ArrayList<>(List.of(
                // ordinary
                "0", "1", "42", "-1", "-42", "+5", " 42 ", "007",
                // the boundaries, and one past each
                "9223372036854775807", "9223372036854775808",
                "-9223372036854775808", "-9223372036854775809",
                "99999999999999999999999", "-99999999999999999999999",
                // fractional and scientific — Long rejects, Double takes
                "19.5", "-2.5", "0.0", ".5", "5.", "1e3", "1E3", "1e-3", "-1.5e10",
                // forms Double alone accepts
                "NaN", "Infinity", "-Infinity", "0x1p3", "1.5d", "1.5f", "1.5D", "1.5F",
                // degenerate
                "", " ", "-", "+", "--1", "+-1", ".", "..", "e", "e5",
                // not numbers at all
                "n/a", "N/A", "null", "abc", "12abc", "abc12", "1 2", "1,000", "1_000",
                // non-ASCII digits: Java reads these, so the fix must too
                "١٢٣", "-١٢", "٠",
                // unicode and whitespace oddities
                "é", "\t42\t", "\n7\n", "4 2"));
        // Random strings over an alphabet dense in the characters that decide the answer.
        final Random random = new Random(20260827L);
        final String alphabet = "0123456789+-.eE xNI١";
        for (int n = 0; n < 4000; n++) {
            final int length = random.nextInt(6);
            final StringBuilder text = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            cases.add(text.toString());
        }
        return cases;
    }

    @Test
    void theIntegerCastAcceptsExactlyWhatLongValueOfAccepted() {
        for (final String text : corpus()) {
            assertThat(TypedValue.of(text).asInteger())
                    .as("asInteger(%s)", debug(text))
                    .isEqualTo(oldWhole(text));
        }
    }

    @Test
    void theNumberCastAcceptsExactlyWhatDoubleValueOfAccepted() {
        for (final String text : corpus()) {
            assertThat(TypedValue.of(text).asNumber())
                    .as("asNumber(%s)", debug(text))
                    .isEqualTo(oldNumber(text));
        }
    }

    /** Neither cast throws, whatever it is given — the contract the fix exists to keep cheap. */
    @Test
    void neitherCastThrows() {
        for (final String text : corpus()) {
            final TypedValue value = TypedValue.of(text);
            value.asInteger();
            value.asNumber();
            value.asBoolean();
            value.asString();
        }
    }

    private static String debug(final String text) {
        final StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(c -> out.append(c < 32 || c > 126
                ? "\\u%04x".formatted(c)
                : String.valueOf((char) c)));
        return out.append('"').toString();
    }
}
