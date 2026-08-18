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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field-extraction implementations used as benchmark comparands.
 * <p>
 * Every implementation performs the same logical task — locate each field of every record
 * and combine the field lengths into a checksum — so that results are comparable and any
 * divergence is caught by {@code ScannersTest}. The checksum exists purely to make the work
 * observable; nothing depends on its value.
 * <p>
 * The hand-written scanners are the stand-in for what a tier 0 scan plan should approach:
 * straight-line scanning over the raw representation with no automaton and no dispatch. They
 * are written as plain loops rather than with SWAR or the Vector API, so they represent the
 * <em>unoptimised</em> floor of that approach.
 */
final class Scanners {

    static final Pattern CSV_PATTERN = Pattern.compile(
            "^([^,\n]*),([^,\n]*),([^,\n]*),([^,\n]*),([^,\n]*)$", Pattern.MULTILINE);

    static final Pattern SYSLOG_PATTERN = Pattern.compile(
            "^(\\S+) (\\S+) (\\S+) (\\S+) (.*)$", Pattern.MULTILINE);

    private Scanners() {
    }

    // -----------------------------------------------------------------------------------
    // Regex — shared by the String and CharSequence comparands
    // -----------------------------------------------------------------------------------

    static long regex(final Pattern pattern, final CharSequence input, final int groups) {
        final Matcher matcher = pattern.matcher(input);
        long hash = 1L;
        while (matcher.find()) {
            for (int group = 1; group <= groups; group++) {
                hash = hash * 31L + (matcher.end(group) - matcher.start(group));
            }
        }
        return hash;
    }

    // -----------------------------------------------------------------------------------
    // CSV — scan until byte/char
    // -----------------------------------------------------------------------------------

    static long csvBytes(final byte[] data) {
        long hash = 1L;
        int pos = 0;
        while (pos < data.length) {
            int lineEnd = indexOf(data, (byte) '\n', pos, data.length);
            if (lineEnd < 0) {
                lineEnd = data.length;
            }
            int fieldStart = pos;
            for (int field = 0; field < Fixtures.CSV_FIELDS - 1; field++) {
                final int comma = indexOf(data, (byte) ',', fieldStart, lineEnd);
                if (comma < 0) {
                    return hash;
                }
                hash = hash * 31L + (comma - fieldStart);
                fieldStart = comma + 1;
            }
            hash = hash * 31L + (lineEnd - fieldStart);
            pos = lineEnd + 1;
        }
        return hash;
    }

    static long csvChars(final char[] data) {
        long hash = 1L;
        int pos = 0;
        while (pos < data.length) {
            int lineEnd = indexOf(data, '\n', pos, data.length);
            if (lineEnd < 0) {
                lineEnd = data.length;
            }
            int fieldStart = pos;
            for (int field = 0; field < Fixtures.CSV_FIELDS - 1; field++) {
                final int comma = indexOf(data, ',', fieldStart, lineEnd);
                if (comma < 0) {
                    return hash;
                }
                hash = hash * 31L + (comma - fieldStart);
                fieldStart = comma + 1;
            }
            hash = hash * 31L + (lineEnd - fieldStart);
            pos = lineEnd + 1;
        }
        return hash;
    }

    // -----------------------------------------------------------------------------------
    // Syslog — scan while class, then take the line remainder
    // -----------------------------------------------------------------------------------

    static long syslogBytes(final byte[] data) {
        long hash = 1L;
        int pos = 0;
        while (pos < data.length) {
            int lineEnd = indexOf(data, (byte) '\n', pos, data.length);
            if (lineEnd < 0) {
                lineEnd = data.length;
            }
            int cursor = pos;
            for (int field = 0; field < Fixtures.SYSLOG_FIELDS - 1; field++) {
                final int tokenStart = cursor;
                while (cursor < lineEnd && data[cursor] != ' ') {
                    cursor++;
                }
                if (cursor == tokenStart || cursor == lineEnd) {
                    return hash;
                }
                hash = hash * 31L + (cursor - tokenStart);
                cursor++; // the single separating space
            }
            hash = hash * 31L + (lineEnd - cursor);
            pos = lineEnd + 1;
        }
        return hash;
    }

    static long syslogChars(final char[] data) {
        long hash = 1L;
        int pos = 0;
        while (pos < data.length) {
            int lineEnd = indexOf(data, '\n', pos, data.length);
            if (lineEnd < 0) {
                lineEnd = data.length;
            }
            int cursor = pos;
            for (int field = 0; field < Fixtures.SYSLOG_FIELDS - 1; field++) {
                final int tokenStart = cursor;
                while (cursor < lineEnd && data[cursor] != ' ') {
                    cursor++;
                }
                if (cursor == tokenStart || cursor == lineEnd) {
                    return hash;
                }
                hash = hash * 31L + (cursor - tokenStart);
                cursor++; // the single separating space
            }
            hash = hash * 31L + (lineEnd - cursor);
            pos = lineEnd + 1;
        }
        return hash;
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private static int indexOf(final byte[] data, final byte value, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(final char[] data, final char value, final int from, final int to) {
        for (int i = from; i < to; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
