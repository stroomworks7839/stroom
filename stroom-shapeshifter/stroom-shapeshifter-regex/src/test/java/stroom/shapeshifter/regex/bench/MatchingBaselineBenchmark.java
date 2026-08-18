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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Baseline measurements taken <em>before</em> any Shapeshifter engine code exists.
 * <p>
 * The design claims that a byte-native scan plan should substantially beat
 * {@code java.util.regex} for scan-shaped patterns. This benchmark establishes what there is
 * to beat, and — more usefully — decomposes the gap into its two independent causes so that
 * later engine numbers can be attributed rather than guessed at:
 *
 * <pre>
 *                    │ regex (backtracking node walk) │ hand-written scan
 *   ─────────────────┼────────────────────────────────┼───────────────────
 *   String           │ regexString                    │ scanChars
 *   CharSequence     │ regexCharSequence              │        —
 *   byte[]           │ (not expressible)              │ scanBytes
 * </pre>
 *
 * <ul>
 *   <li>{@code regexString} vs {@code scanChars} isolates <b>regex engine vs straight-line
 *       scanning</b>, holding the representation constant.</li>
 *   <li>{@code scanChars} vs {@code scanBytes} isolates <b>chars vs bytes</b>, holding the
 *       algorithm constant.</li>
 *   <li>{@code regexString} vs {@code regexCharSequence} isolates the cost of the
 *       interface-dispatched {@code charAt} that Stroom's DS3 parser pays today, because its
 *       buffer is a {@link CharSequence} rather than a {@link String}.</li>
 *   <li>{@code regexStringFromBytes} adds the {@code new String(bytes, UTF_8)} decode that a
 *       byte pipeline must perform before {@code java.util.regex} can be used at all. This is
 *       the honest end-to-end number for the current architecture, and the one a byte-native
 *       engine avoids entirely.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 * ./gradlew :stroom-shapeshifter:stroom-shapeshifter-regex:test --tests '*MatchingBaselineBenchmark*'
 * </pre>
 * or directly via {@link #main(String[])}. Add {@code -prof gc} for allocation per operation,
 * which is where the span-vs-String difference shows up most starkly.
 *
 * <p>One operation processes the whole fixture, so
 * {@code MB/s = ops/s × fixture bytes ÷ 1,048,576}. The fixture size is reported by
 * {@code fixtureBytes()} to make that conversion possible.
 *
 * <p>Results: see {@code stroom-shapeshifter/design/03-baseline-results.md}.
 */
@Fork(1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class MatchingBaselineBenchmark {

    /** CSV exercises scan-until-byte; syslog exercises scan-while-class. */
    @Param({"csv", "syslog"})
    private String workload;

    private static final int LINES = 5_000;

    private String text;
    private byte[] bytes;
    private char[] chars;
    private CharArrayCharSequence charSequence;
    private boolean isCsv;

    private Plans.Op[] plan;
    private int[] planCode;
    private int[] planArgs;
    private long[][] planClasses;
    private int[] planCodeTable;
    private byte[][] planTables;

    @Setup
    public void setup() {
        isCsv = "csv".equals(workload);
        text = isCsv
                ? Fixtures.csv(LINES)
                : Fixtures.syslog(LINES);
        bytes = Fixtures.bytes(text);
        chars = Fixtures.chars(text);
        charSequence = new CharArrayCharSequence(chars);

        plan = isCsv
                ? Plans.csvPlan()
                : Plans.syslogPlan();
        planCode = isCsv
                ? Plans.csvCode()
                : Plans.syslogCode();
        planArgs = isCsv
                ? Plans.csvArgs()
                : Plans.syslogArgs();
        planClasses = isCsv
                ? Plans.csvClasses()
                : Plans.syslogClasses();
        planCodeTable = isCsv
                ? Plans.csvCode()
                : Plans.syslogCodeTable();
        planTables = isCsv
                ? Plans.csvTables()
                : Plans.syslogTables();
    }

    /** Not a benchmark — reports the fixture size so ops/s can be converted to MB/s. */
    public int fixtureBytes() {
        return bytes.length;
    }

    // -----------------------------------------------------------------------------------

    @Benchmark
    public long regexString() {
        return isCsv
                ? Scanners.regex(Scanners.CSV_PATTERN, text, Fixtures.CSV_FIELDS)
                : Scanners.regex(Scanners.SYSLOG_PATTERN, text, Fixtures.SYSLOG_FIELDS);
    }

    @Benchmark
    public long regexCharSequence() {
        return isCsv
                ? Scanners.regex(Scanners.CSV_PATTERN, charSequence, Fixtures.CSV_FIELDS)
                : Scanners.regex(Scanners.SYSLOG_PATTERN, charSequence, Fixtures.SYSLOG_FIELDS);
    }

    @Benchmark
    public long regexStringFromBytes() {
        final String decoded = new String(bytes, StandardCharsets.UTF_8);
        return isCsv
                ? Scanners.regex(Scanners.CSV_PATTERN, decoded, Fixtures.CSV_FIELDS)
                : Scanners.regex(Scanners.SYSLOG_PATTERN, decoded, Fixtures.SYSLOG_FIELDS);
    }

    @Benchmark
    public long scanChars() {
        return isCsv
                ? Scanners.csvChars(chars)
                : Scanners.syslogChars(chars);
    }

    @Benchmark
    public long scanBytes() {
        return isCsv
                ? Scanners.csvBytes(bytes)
                : Scanners.syslogBytes(bytes);
    }

    /** Tier 0 prototype: ops as sealed records, pattern-matching switch. */
    @Benchmark
    public long planSealed() {
        return Plans.runSealed(plan, bytes);
    }

    /** Tier 0 prototype: flat opcode arrays, switch on int, class membership via a 256-bit set. */
    @Benchmark
    public long planFlat() {
        return Plans.runFlat(planCode, planArgs, planClasses, bytes);
    }

    /**
     * Tier 0 prototype: as {@link #planFlat()} but class membership via a 256-entry byte table.
     * Identical to {@code planFlat} for the csv workload, which has no class ops — that case is
     * the control.
     */
    @Benchmark
    public long planFlatTable() {
        return Plans.runFlat(planCodeTable, planArgs, planClasses, planTables, bytes);
    }

    // -----------------------------------------------------------------------------------

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(MatchingBaselineBenchmark.class.getSimpleName())
                .build())
                .run();
    }
}
