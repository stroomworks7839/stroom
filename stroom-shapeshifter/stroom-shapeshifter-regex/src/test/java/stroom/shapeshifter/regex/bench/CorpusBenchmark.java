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

import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This engine against {@code java.util.regex}, on realistic patterns over realistic records.
 * <p>
 * Each workload is a pattern of the kind a data splitter actually runs, applied to 2,000 records
 * of matching data. Both engines extract every capture group, since a benchmark that only asks
 * "did it match" measures a different thing from one that asks "what were the fields".
 * <p>
 * The workloads are chosen to span both execution tiers, because the engine's performance is not
 * one number: an unambiguous pattern compiles to a scan plan, an ambiguous one to an NFA
 * simulation, and the two differ by a large factor. Reporting only the fast half would be
 * misleading.
 * <p>
 * Results: see {@code design/05-engine-benchmarks.md}.
 */
/*
 * Five forks, not one. With a single fork the run-to-run spread on this benchmark reached 25%
 * — the same binary measured QUOTED at 8858 and then 6656 ops/s — while JMH's reported error,
 * which is the spread *within* one JVM, stayed near 2%. A tight-looking error bar on a single
 * fork says only that one JIT's decisions were stable, not that the number is reproducible, and
 * a difference of 10% between two builds measured that way means nothing at all.
 */
@Fork(5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class CorpusBenchmark {

    private static final int RECORDS = 2_000;

    /** Each workload names a pattern and the shape of record it is run over. */
    public enum Workload {
        CSV("^([^,]+),([^,]+),([^,]+),([^,]+)$",
                "2026-08-17T14:%02d:00Z,server%02d,ERROR,Connection failed to upstream"),
        SYSLOG("^(\\S+) (\\S+) (\\S+) (\\S+) (.*)$",
                "Mar 18 14:%02d:00 server%02d sshd: Failed password for root"),
        WEBLOG("^(\\S+) (\\S+) (\\S+) \\[([^\\]]+)\\] \"([^\"]*)\" (\\d{3}) (\\d+)$",
                "192.168.1.%d - - [17/Aug/2026:14:%02d:00 +0000] \"GET /index.html HTTP/1.1\" 200 4213"),
        KEYVALUE("^(\\w+)=(\\w+) (\\w+)=(\\w+) (\\w+)=(\\w+)$",
                "host=server%02d level=error code=%d"),
        DATETIME("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})",
                "at 2026-08-%02dT14:%02d:00 the thing happened"),
        NETWORK("^(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+) -> (\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)$",
                "10.0.0.%d:443 -> 192.168.1.%d:51234"),
        QUOTED("^\"([^\"]*)\",\"([^\"]*)\",\"([^\"]*)\"$",
                "\"field one %02d\",\"field two %02d\",\"field three\""),
        ALTERNATION("^(ERROR|WARN|INFO|DEBUG|TRACE): (.*)$",
                "ERROR: something went wrong in module %d at %d"),
        /** Ambiguous — greedy repeats over permissive classes, so this one runs on the NFA. */
        TIER1_GREEDY("^(.+):(.+)$",
                "key%02d:value with spaces %02d"),
        /** Ambiguous — overlapping alternation, the quoted-or-unquoted field problem. */
        TIER1_ALTERNATION("^(\"[^\"]*\"|[^ ]+) (\"[^\"]*\"|[^ ]+) (.*)$",
                "\"quoted %02d\" unquoted%02d the rest of the record"),
        /**
         * Fancy — a backreference, so this runs on the unbounded backtracker against the JDK's
         * own backtracker: the one comparison where the JDK plays at home and the only edge
         * available is byte-level execution against a decoded String.
         */
        FANCY_BACKREF("^(\\w+)=(\\w+);\\1=(\\w+)$",
                "host%02d=alpha;host%02d=beta"),
        /** Fancy — the lookahead shape of the harvested log-splitter patterns. */
        FANCY_LOOKAHEAD("^(\\w+): (?=.*\\berror\\b)(.*)$",
                "app%02d: failure error code %d"),
        /** Fancy — possessive classes, the harvested atomic-group field shape. */
        FANCY_ATOMIC("^([\\w ]++),(\\d++),(\\S+)$",
                "alpha beta %02d,42%d,tail-data");

        private final String pattern;
        private final String template;

        Workload(final String pattern, final String template) {
            this.pattern = pattern;
            this.template = template;
        }

        String pattern() {
            return pattern;
        }

        String template() {
            return template;
        }
    }

    @Param
    private Workload workload;

    private byte[] bytes;
    private String text;
    private BytePattern ours;
    private BytePattern oursTree;
    private Pattern theirs;
    private int groups;

    @Setup
    public void setup() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RECORDS; i++) {
            sb.append(String.format(workload.template, i % 60, i % 60)).append('\n');
        }
        text = sb.toString();
        bytes = text.getBytes(StandardCharsets.UTF_8);

        ours = BytePattern.compile(workload.pattern, stroom.shapeshifter.regex.Flag.MULTILINE);
        oursTree = BytePattern.compileForcing(stroom.shapeshifter.regex.Engine.TREE,
                workload.pattern,
                java.util.EnumSet.of(stroom.shapeshifter.regex.Flag.MULTILINE));
        theirs = Pattern.compile(workload.pattern, Pattern.MULTILINE);
        groups = theirs.matcher("").groupCount();
    }

    /** Reported alongside the scores so each workload's tier is visible rather than guessed. */
    public int tier() {
        return ours.tier();
    }

    public int fixtureBytes() {
        return bytes.length;
    }

    @Benchmark
    public long shapeshifter() {
        final ByteMatcher matcher = ours.matcher();
        long hash = 1L;
        int pos = 0;
        while (pos < bytes.length && matcher.match(
                bytes, pos, bytes.length, stroom.shapeshifter.regex.Anchoring.UNANCHORED)) {
            for (int group = 1; group <= groups; group++) {
                hash = hash * 31L + (matcher.matchedGroup(group)
                        ? matcher.end(group) - matcher.start(group)
                        : -1);
            }
            pos = matcher.end() == matcher.start()
                    ? matcher.end() + 1
                    : matcher.end();
        }
        return hash;
    }

    /** The experimental tree-walking engine (Engine.TREE), pinned; see D30. */
    @Benchmark
    public long shapeshifterTree() {
        final ByteMatcher matcher = oursTree.matcher();
        long hash = 1L;
        int pos = 0;
        while (pos < bytes.length && matcher.match(
                bytes, pos, bytes.length, stroom.shapeshifter.regex.Anchoring.UNANCHORED)) {
            for (int group = 1; group <= groups; group++) {
                hash = hash * 31L + (matcher.matchedGroup(group)
                        ? matcher.end(group) - matcher.start(group)
                        : -1);
            }
            pos = matcher.end() == matcher.start()
                    ? matcher.end() + 1
                    : matcher.end();
        }
        return hash;
    }

    @Benchmark
    public long javaRegex() {
        final Matcher matcher = theirs.matcher(text);
        long hash = 1L;
        while (matcher.find()) {
            for (int group = 1; group <= groups; group++) {
                hash = hash * 31L + (matcher.start(group) >= 0
                        ? matcher.end(group) - matcher.start(group)
                        : -1);
            }
        }
        return hash;
    }

    /** The honest end-to-end comparison for a byte pipeline: the JDK needs characters first. */
    @Benchmark
    public long javaRegexFromBytes() {
        final Matcher matcher = theirs.matcher(new String(bytes, StandardCharsets.UTF_8));
        long hash = 1L;
        while (matcher.find()) {
            for (int group = 1; group <= groups; group++) {
                hash = hash * 31L + (matcher.start(group) >= 0
                        ? matcher.end(group) - matcher.start(group)
                        : -1);
            }
        }
        return hash;
    }

    public static void main(final String[] args) throws RunnerException {
        new Runner(new OptionsBuilder()
                .include(CorpusBenchmark.class.getSimpleName())
                .build())
                .run();
    }
}
