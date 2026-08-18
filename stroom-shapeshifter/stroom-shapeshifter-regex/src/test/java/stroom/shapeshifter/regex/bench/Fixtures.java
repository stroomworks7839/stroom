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

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Deterministic input fixtures for the matching baseline benchmarks.
 * <p>
 * All data is pure ASCII so that byte-oriented and char-oriented implementations do exactly
 * the same amount of logical work, and any measured difference is attributable to the
 * representation rather than to decoding effort.
 */
final class Fixtures {

    /** Field count in a {@link #csv} record. */
    static final int CSV_FIELDS = 5;

    /** Field count in a {@link #syslog} record, the last being the free-text remainder. */
    static final int SYSLOG_FIELDS = 5;

    private static final String[] HOSTS = {
            "server01", "server02", "app-node-7", "db01", "edge-gw-3"};
    private static final String[] LEVELS = {"INFO", "WARN", "ERROR", "DEBUG"};
    private static final String[] MESSAGES = {
            "Connection failed to upstream",
            "Request completed",
            "Retrying after backoff",
            "Cache miss for key",
            "Session expired"};

    private Fixtures() {
    }

    /**
     * Comma separated records, e.g.
     * <pre>2024-03-18T14:30:00Z,server01,ERROR,Connection failed to upstream,4213</pre>
     * Exercises scan-until-byte matching.
     */
    static String csv(final int lines) {
        final Random random = new Random(42);
        final StringBuilder sb = new StringBuilder(lines * 80);
        for (int i = 0; i < lines; i++) {
            sb.append("2024-03-18T14:").append(pad2(i % 60)).append(':').append(pad2((i * 7) % 60)).append("Z,")
                    .append(HOSTS[random.nextInt(HOSTS.length)]).append(',')
                    .append(LEVELS[random.nextInt(LEVELS.length)]).append(',')
                    .append(MESSAGES[random.nextInt(MESSAGES.length)]).append(',')
                    .append(1000 + random.nextInt(9000))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * Whitespace delimited records with a free-text tail, e.g.
     * <pre>Mar 18 14:30:00 server01 sshd[1234]: Failed password for user from 192.168.1.1</pre>
     * Exercises scan-while-class matching rather than scan-until-byte.
     */
    static String syslog(final int lines) {
        final Random random = new Random(43);
        final StringBuilder sb = new StringBuilder(lines * 90);
        for (int i = 0; i < lines; i++) {
            sb.append("Mar ").append(pad2(1 + (i % 28))).append(' ')
                    .append("14:").append(pad2(i % 60)).append(':').append(pad2((i * 7) % 60)).append(' ')
                    .append(HOSTS[random.nextInt(HOSTS.length)]).append(' ')
                    .append("sshd[").append(1000 + random.nextInt(9000)).append("]:").append(' ')
                    .append(MESSAGES[random.nextInt(MESSAGES.length)]).append(" from 192.168.1.")
                    .append(1 + random.nextInt(254))
                    .append('\n');
        }
        return sb.toString();
    }

    static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static char[] chars(final String text) {
        return text.toCharArray();
    }

    private static String pad2(final int value) {
        return value < 10
                ? "0" + value
                : Integer.toString(value);
    }
}
