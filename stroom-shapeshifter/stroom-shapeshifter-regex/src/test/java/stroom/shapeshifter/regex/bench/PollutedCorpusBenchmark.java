package stroom.shapeshifter.regex.bench;

import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;
import stroom.shapeshifter.regex.Engine;
import stroom.shapeshifter.regex.Flag;
import stroom.shapeshifter.regex.corpus.PatternCorpus;

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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link CorpusBenchmark}'s workloads measured in a JVM whose profiles look like a pipeline's,
 * not a microbenchmark's.
 *
 * <p>A JMH fork runs one pattern per JVM. That hands {@code java.util.regex} monomorphic type
 * profiles — every {@code Node.match} call site sees one receiver — which no real pipeline, with
 * dozens of templates and hundreds of patterns, ever gives it; these engines are
 * interpreter-shaped and pollution-immune. It is the "JMH harness condition that flatters the
 * JDK's steady state" recorded against buffer {@code NETWORK} (06 §2), and the many-patterns
 * blind spot recorded in 06 §5: the same thing, seen from two sides.
 *
 * <p>So before the measured loop, {@link #setup} compiles the whole corpus through both
 * libraries and runs every pattern over its category's inputs enough times for the JIT to see
 * them all. The measured methods are byte-for-byte {@code CorpusBenchmark}'s. Report this row
 * beside the clean one, not instead of it: the clean row is what a single hot template sees,
 * this one is what a pipeline sees. Neither is the headline alone.
 */
@Fork(5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class PollutedCorpusBenchmark {

    private static final int RECORDS = 2_000;
    /** Passes over the corpus during setup — enough for C2 to compile the JDK's node walk
     * against every pattern's receivers, so the measured pattern arrives at a polluted site. */
    private static final int POLLUTION_PASSES = 200;

    @Param
    private CorpusBenchmark.Workload workload;

    private byte[] bytes;
    private String text;
    private BytePattern ours;
    private BytePattern oursTree;
    private Pattern theirs;
    private int groups;

    @Setup
    public void setup() {
        pollute();

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RECORDS; i++) {
            sb.append(String.format(workload.template(), i % 60, i % 60)).append('\n');
        }
        text = sb.toString();
        bytes = text.getBytes(StandardCharsets.UTF_8);

        ours = BytePattern.compile(workload.pattern(), Flag.MULTILINE);
        oursTree = BytePattern.compileForcing(Engine.TREE, workload.pattern(),
                EnumSet.of(Flag.MULTILINE));
        theirs = Pattern.compile(workload.pattern(), Pattern.MULTILINE);
        groups = theirs.matcher("").groupCount();
    }

    /**
     * Which library's patterns the setup runs: {@code both} (the default, a pipeline's JVM),
     * {@code ours} or {@code jdk}. Design 07 Phase 8's first question — whose pollution costs the
     * tree its 26–39% — is answered by the difference between the three.
     */
    private static final String POLLUTE_WITH = System.getProperty("shapeshifter.pollute", "both");

    /** Every corpus pattern, both libraries, over its category's inputs, repeatedly. */
    private static void pollute() {
        final boolean jdk = !"ours".equals(POLLUTE_WITH);
        final boolean ours = !"jdk".equals(POLLUTE_WITH);
        final List<Pattern> jdkPatterns = new ArrayList<>();
        final List<BytePattern> ss = new ArrayList<>();
        final List<List<byte[]>> inputs = new ArrayList<>();
        final List<List<String>> texts = new ArrayList<>();
        for (final PatternCorpus.Category category : PatternCorpus.categories()) {
            for (final String pattern : category.patterns()) {
                jdkPatterns.add(Pattern.compile(pattern));
                ss.add(BytePattern.compile(pattern));
                texts.add(category.inputs());
                inputs.add(category.inputs().stream()
                        .map(s -> s.getBytes(StandardCharsets.UTF_8))
                        .toList());
            }
        }
        long sink = 0;
        for (int pass = 0; pass < POLLUTION_PASSES; pass++) {
            for (int i = 0; i < jdkPatterns.size(); i++) {
                if (jdk) {
                    for (final String input : texts.get(i)) {
                        final Matcher m = jdkPatterns.get(i).matcher(input);
                        if (m.find()) {
                            sink += m.end();
                        }
                    }
                }
                if (ours) {
                    final ByteMatcher matcher = ss.get(i).matcher();
                    for (final byte[] input : inputs.get(i)) {
                        if (matcher.match(input, 0, input.length, Anchoring.UNANCHORED)) {
                            sink += matcher.end();
                        }
                    }
                }
            }
        }
        if (sink == 42) {
            System.out.println("unlikely"); // keeps the pollution loop observable
        }
    }

    @Benchmark
    public long shapeshifter() {
        final ByteMatcher matcher = ours.matcher();
        long hash = 1L;
        int pos = 0;
        while (pos < bytes.length && matcher.match(bytes, pos, bytes.length, Anchoring.UNANCHORED)) {
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
    public long shapeshifterTree() {
        final ByteMatcher matcher = oursTree.matcher();
        long hash = 1L;
        int pos = 0;
        while (pos < bytes.length && matcher.match(bytes, pos, bytes.length, Anchoring.UNANCHORED)) {
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
                .include(PollutedCorpusBenchmark.class.getSimpleName())
                .build())
                .run();
    }
}
