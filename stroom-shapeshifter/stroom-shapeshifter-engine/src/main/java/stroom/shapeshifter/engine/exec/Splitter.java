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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splitting on a delimiter, with quoting and escaping.
 *
 * <p>This is the CSV problem generalised: a separator that stops being a separator inside a
 * quoted region, and an escape character that makes the next character literal wherever it
 * appears. Doing it with a regex is possible and miserable; doing it with a scan is neither.
 *
 * <p>Every split produces three groups, which is what lets one configuration ask for the raw
 * field and another for the cleaned one:
 * <ul>
 *   <li><b>0</b> — everything consumed, delimiter included</li>
 *   <li><b>1</b> — the content before the delimiter, with any surrounding quotes removed</li>
 *   <li><b>2</b> — the same, with escape characters removed too</li>
 * </ul>
 *
 * <p>The last field of a region has no delimiter after it, and is still a field.
 */
public final class Splitter {

    private Splitter() {
    }

    /**
     * Find the next field.
     *
     * @param data           the buffer
     * @param from           where to start
     * @param to             where the searchable region ends
     * @param delimiter      the separator
     * @param escape         makes the next character literal, or null
     * @param containerStart opens a region where the delimiter is literal, or null
     * @param containerEnd   closes that region, or null
     * @return the field, or null if there is nothing left to read
     */
    public static MatchResult split(final byte[] data,
                                    final int from,
                                    final int to,
                                    final byte[] delimiter,
                                    final byte[] escape,
                                    final byte[] containerStart,
                                    final byte[] containerEnd) {
        if (from >= to || delimiter.length == 0) {
            return null;
        }

        // The overwhelmingly common case — a one-byte separator, nothing quoted, nothing
        // escaped — is a single scan for a byte, and worth not paying the general machinery for.
        if (delimiter.length == 1 && escape == null && containerStart == null) {
            return splitOnByte(data, from, to, delimiter[0]);
        }

        boolean inContainer = false;
        boolean hasStartContainer = false;
        int scan = from;

        if (containerStart != null && matchesAt(data, from, to, containerStart)) {
            hasStartContainer = true;
            inContainer = true;
            scan = from + containerStart.length;
        }

        final List<Integer> escapes = new ArrayList<>();
        boolean escaped = false;
        int lastContainerEnd = -1;

        while (scan < to) {
            if (escape != null && !escaped && matchesAt(data, scan, to, escape)) {
                escapes.add(scan);
                escaped = true;
                scan += escape.length;
                continue;
            }
            if (escaped) {
                escaped = false;
                scan++;
                continue;
            }
            if (inContainer && containerEnd != null && matchesAt(data, scan, to, containerEnd)) {
                inContainer = false;
                lastContainerEnd = scan;
                scan += containerEnd.length;
                continue;
            }
            if (!inContainer && containerStart != null && matchesAt(data, scan, to, containerStart)) {
                inContainer = true;
                scan += containerStart.length;
                continue;
            }
            if (!inContainer && matchesAt(data, scan, to, delimiter)) {
                return build(data, from, scan, delimiter.length, hasStartContainer,
                        containerStart, containerEnd, escape, escapes, false, lastContainerEnd);
            }
            scan++;
        }

        return build(data, from, to, 0, hasStartContainer,
                containerStart, containerEnd, escape, escapes, true, lastContainerEnd);
    }

    private static MatchResult splitOnByte(final byte[] data, final int from, final int to, final byte delimiter) {
        for (int i = from; i < to; i++) {
            if (data[i] == delimiter) {
                final byte[] content = Arrays.copyOfRange(data, from, i);
                return new MatchResult(new TypedValue[]{
                        TypedValue.of(Arrays.copyOfRange(data, from, i + 1)),
                        TypedValue.of(content),
                        TypedValue.of(content)},
                        i + 1 - from, 0);
            }
        }
        final byte[] content = Arrays.copyOfRange(data, from, to);
        return new MatchResult(new TypedValue[]{
                TypedValue.of(content), TypedValue.of(content), TypedValue.of(content)},
                to - from, 0);
    }

    private static MatchResult build(final byte[] data,
                                     final int from,
                                     final int delimiterPos,
                                     final int delimiterLength,
                                     final boolean hasStartContainer,
                                     final byte[] containerStart,
                                     final byte[] containerEnd,
                                     final byte[] escape,
                                     final List<Integer> escapes,
                                     final boolean isLast,
                                     final int lastContainerEnd) {
        final int matchEnd = isLast ? delimiterPos : delimiterPos + delimiterLength;
        final byte[] group0 = Arrays.copyOfRange(data, from, matchEnd);

        final int contentStart = hasStartContainer ? from + containerStart.length : from;
        int contentEnd = delimiterPos;

        // A closing quote immediately before the delimiter closes the field. If the bytes there
        // are not the closing quote, fall back to the last one seen — which is how a field like
        // "a","b" still ends where the quoting says it does rather than where the scan stopped.
        //
        // Only a field that opened with the container has a closing quote to trim. An unquoted
        // field that merely happens to end in the container byte — a"b" — is text, and trimming
        // it would silently eat a byte the author wrote.
        if (hasStartContainer && containerEnd != null && contentEnd >= containerEnd.length + contentStart) {
            final int candidate = contentEnd - containerEnd.length;
            if (matchesAt(data, candidate, delimiterPos + containerEnd.length, containerEnd)) {
                contentEnd = candidate;
            } else if (lastContainerEnd >= 0) {
                contentEnd = lastContainerEnd;
            }
        }

        final byte[] group1 = Arrays.copyOfRange(data, contentStart, Math.max(contentStart, contentEnd));
        final byte[] group2 = escapes.isEmpty()
                ? group1
                : stripEscapes(data, contentStart, contentEnd, escape.length, escapes);

        return new MatchResult(new TypedValue[]{
                TypedValue.of(group0), TypedValue.of(group1), TypedValue.of(group2)},
                matchEnd - from, 0);
    }

    private static byte[] stripEscapes(final byte[] data,
                                       final int start,
                                       final int end,
                                       final int escapeLength,
                                       final List<Integer> escapes) {
        final byte[] result = new byte[Math.max(0, end - start)];
        int written = 0;
        int cursor = 0;
        int i = start;
        while (i < end) {
            while (cursor < escapes.size() && escapes.get(cursor) < i) {
                cursor++;
            }
            if (cursor < escapes.size() && escapes.get(cursor) == i) {
                i += escapeLength;
                cursor++;
            } else {
                result[written++] = data[i++];
            }
        }
        return Arrays.copyOf(result, written);
    }

    private static boolean matchesAt(final byte[] data, final int pos, final int to, final byte[] pattern) {
        if (pos + pattern.length > to) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (data[pos + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }
}
