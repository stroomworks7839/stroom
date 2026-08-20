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

package stroom.shapeshifter.engine.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixture corpus and the states the suite expects of it.
 *
 * <p>{@code fixtures/status.txt} is the index: every fixture set the suite knows about has a
 * line there, and nothing is discovered by walking directories. That is deliberate — a fixture
 * that is not in the ledger is invisible rather than silently skipped, and the file that says
 * what should pass is the file a reviewer reads.
 *
 * <p>The ledger is a <b>ratchet</b>. {@code PENDING} does not mean "ignore"; it means the suite
 * asserts the fixture still fails. When a phase of the port makes one work, the build breaks
 * until the line is promoted to {@code PASS}. Progress therefore cannot happen by accident, and
 * regression cannot happen quietly.
 */
public final class FixtureLedger {

    private static final String CORPUS = "fixtures/";
    private static final String LEDGER = "status.txt";

    private FixtureLedger() {
    }

    /** What the suite expects of a fixture. */
    public enum Status {
        /** Must run and match its goldens. */
        PASS,
        /** Not ported yet: must still fail. */
        PENDING,
        /** Out of scope, with a reason. Never run. */
        SKIPPED,
        /**
         * Vendored, but its golden is known to be wrong — see {@code design/08-fixture-audit.md}.
         * Never run, and never promoted until a corrected golden replaces it. Distinct from
         * {@code SKIPPED} because the fixture is in scope; it is the expectation that is broken.
         */
        QUARANTINED
    }

    /** Which corpus a fixture belongs to, which decides how it is loaded and run. */
    public enum Family {
        /** DS3 XML config, input and golden output from Java Stroom's own DS3. */
        LEGACY,
        /** Hand-written {@code project.json} over the legacy inputs and goldens. */
        NATIVE,
        /** End-to-end {@code project.json} with its own input and expected output. */
        PROJECTS
    }

    /**
     * One fixture set.
     *
     * @param family which corpus it belongs to
     * @param name   the set's name, without a family prefix
     * @param status what the suite expects of it
     * @param note   the reason, for {@code SKIPPED} and for fixtures that need explaining
     */
    public record Fixture(Family family, String name, Status status, String note) {

        /** The ledger key, e.g. {@code legacy/004_simple_regex}. */
        public String id() {
            return family.name().toLowerCase(java.util.Locale.ROOT) + "/" + name;
        }

        @Override
        public String toString() {
            return id();
        }
    }

    /** Every fixture in the ledger, in file order. */
    public static List<Fixture> all() {
        final List<Fixture> fixtures = new ArrayList<>();
        for (final String line : text(LEDGER).lines().toList()) {
            final String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            final String[] fields = trimmed.split("\\s+", 3);
            if (fields.length < 2) {
                throw new IllegalStateException("Malformed ledger line: " + line);
            }
            final int slash = fields[0].indexOf('/');
            if (slash < 0) {
                throw new IllegalStateException("Ledger key needs a family prefix: " + fields[0]);
            }
            fixtures.add(new Fixture(
                    Family.valueOf(fields[0].substring(0, slash).toUpperCase(java.util.Locale.ROOT)),
                    fields[0].substring(slash + 1),
                    Status.valueOf(fields[1]),
                    fields.length > 2 ? fields[2] : ""));
        }
        return fixtures;
    }

    /** The fixtures of one family, in file order. */
    public static List<Fixture> of(final Family family) {
        return all().stream().filter(f -> f.family() == family).toList();
    }

    /** Read a corpus file as bytes. The path is relative to {@code fixtures/}. */
    public static byte[] bytes(final String path) {
        try (InputStream in = FixtureLedger.class.getClassLoader().getResourceAsStream(CORPUS + path)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture file: " + CORPUS + path);
            }
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True if a corpus file exists. The path is relative to {@code fixtures/}. */
    public static boolean exists(final String path) {
        return FixtureLedger.class.getClassLoader().getResource(CORPUS + path) != null;
    }

    /** Read a corpus file as UTF-8 text. The path is relative to {@code fixtures/}. */
    public static String text(final String path) {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }
}
