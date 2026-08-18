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

package stroom.shapeshifter.regex.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the benchmark comparands.
 * <p>
 * A benchmark comparing implementations that do different amounts of work measures nothing,
 * so every comparand must agree on the checksum for the same input before any timing figure
 * is worth reading.
 */
class ScannersTest {

    private static final int LINES = 500;

    @Test
    void csvComparandsAgree() {
        final String text = Fixtures.csv(LINES);
        final byte[] bytes = Fixtures.bytes(text);
        final char[] chars = Fixtures.chars(text);

        final long viaRegexOnString = Scanners.regex(Scanners.CSV_PATTERN, text, Fixtures.CSV_FIELDS);
        final long viaRegexOnCharSeq = Scanners.regex(
                Scanners.CSV_PATTERN, new CharArrayCharSequence(chars), Fixtures.CSV_FIELDS);

        assertThat(viaRegexOnCharSeq).isEqualTo(viaRegexOnString);
        assertThat(Scanners.csvBytes(bytes)).isEqualTo(viaRegexOnString);
        assertThat(Scanners.csvChars(chars)).isEqualTo(viaRegexOnString);
        assertThat(Plans.runSealed(Plans.csvPlan(), bytes)).isEqualTo(viaRegexOnString);
        assertThat(Plans.runFlat(Plans.csvCode(), Plans.csvArgs(), Plans.csvClasses(), bytes))
                .isEqualTo(viaRegexOnString);
    }

    @Test
    void syslogComparandsAgree() {
        final String text = Fixtures.syslog(LINES);
        final byte[] bytes = Fixtures.bytes(text);
        final char[] chars = Fixtures.chars(text);

        final long viaRegexOnString = Scanners.regex(Scanners.SYSLOG_PATTERN, text, Fixtures.SYSLOG_FIELDS);
        final long viaRegexOnCharSeq = Scanners.regex(
                Scanners.SYSLOG_PATTERN, new CharArrayCharSequence(chars), Fixtures.SYSLOG_FIELDS);

        assertThat(viaRegexOnCharSeq).isEqualTo(viaRegexOnString);
        assertThat(Scanners.syslogBytes(bytes)).isEqualTo(viaRegexOnString);
        assertThat(Scanners.syslogChars(chars)).isEqualTo(viaRegexOnString);
        assertThat(Plans.runSealed(Plans.syslogPlan(), bytes)).isEqualTo(viaRegexOnString);
        assertThat(Plans.runFlat(Plans.syslogCode(), Plans.syslogArgs(), Plans.syslogClasses(), bytes))
                .isEqualTo(viaRegexOnString);
        assertThat(Plans.runFlat(Plans.syslogCodeTable(), Plans.syslogArgs(), Plans.syslogClasses(),
                Plans.syslogTables(), bytes))
                .isEqualTo(viaRegexOnString);
    }

    @Test
    void fixturesAreAscii() {
        // Byte and char implementations only do identical logical work if the data is ASCII.
        final String text = Fixtures.csv(LINES) + Fixtures.syslog(LINES);
        assertThat(text.chars().allMatch(c -> c < 0x80)).isTrue();
        assertThat(Fixtures.bytes(text)).hasSize(text.length());
    }
}
