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

package stroom.shapeshifter.engine.bench;

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

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Design 37 phase 6's other question, on its own: a switch over the type with the work inline,
 * against a method on the object that does the work, against a switch that calls a small method
 * per arm — with <b>real</b> work in the arms, so that the switch method's size can cross the
 * JIT's hot-inline limit as the engine's resolver did. Fifteen numeric operations on a running
 * accumulator; the dispatch sits in a helper the hot loop calls, so inlining budgets apply to it
 * as they do to {@code Level.match} and {@code CompiledRefs.write}; arm counts of seven and
 * fifteen; receiver mixes as an interpreter sees them.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class DispatchWorkBenchmark {

    /** How many kinds the stream draws from, evenly: 2, 3, 7 or 15; or 15 with one at 90%. */
    @Param({"two", "three", "seven", "fifteen", "skewed"})
    public String mix;

    private static final int N = 4096;

    sealed interface Op permits K0, K1, K2, K3, K4, K5, K6, K7, K8, K9, K10, K11, K12, K13, K14 {

        long apply(long acc);
    }

    record K0(long v) implements Op {

        public long apply(final long acc) {
            return acc * 31 + v;
        }
    }

    record K1(long v) implements Op {

        public long apply(final long acc) {
            return acc ^ (v << 3);
        }
    }

    record K2(long v) implements Op {

        public long apply(final long acc) {
            return acc + (v * v);
        }
    }

    record K3(long v) implements Op {

        public long apply(final long acc) {
            return (acc << 1) - v;
        }
    }

    record K4(long v) implements Op {

        public long apply(final long acc) {
            return acc + (v >>> 2);
        }
    }

    record K5(long v) implements Op {

        public long apply(final long acc) {
            return acc * 17 ^ v;
        }
    }

    record K6(long v) implements Op {

        public long apply(final long acc) {
            return acc - (v * 3);
        }
    }

    record K7(long v) implements Op {

        public long apply(final long acc) {
            return acc ^ (acc >>> 7) + v;
        }
    }

    record K8(long v) implements Op {

        public long apply(final long acc) {
            return acc + (v | 5);
        }
    }

    record K9(long v) implements Op {

        public long apply(final long acc) {
            return acc * 13 + (v & 255);
        }
    }

    record K10(long v) implements Op {

        public long apply(final long acc) {
            return (acc ^ v) * 3;
        }
    }

    record K11(long v) implements Op {

        public long apply(final long acc) {
            return acc + (v << 5) - 1;
        }
    }

    record K12(long v) implements Op {

        public long apply(final long acc) {
            return acc * 7 - (v >>> 1);
        }
    }

    record K13(long v) implements Op {

        public long apply(final long acc) {
            return acc ^ (v * 11);
        }
    }

    record K14(long v) implements Op {

        public long apply(final long acc) {
            return acc + (v ^ 0x55);
        }
    }

    private Op[] ops;

    @Setup
    public void setup() {
        final Random random = new Random(42);
        ops = new Op[N];
        for (int i = 0; i < N; i++) {
            final int r = random.nextInt(100);
            final int which = switch (mix) {
                case "two" -> r % 2;
                case "three" -> r % 3;
                case "seven" -> r % 7;
                case "fifteen" -> r % 15;
                default -> r < 90 ? 0 : 1 + (r % 14);
            };
            ops[i] = make(which, i);
        }
    }

    private static Op make(final int which, final long v) {
        return switch (which) {
            case 0 -> new K0(v);
            case 1 -> new K1(v);
            case 2 -> new K2(v);
            case 3 -> new K3(v);
            case 4 -> new K4(v);
            case 5 -> new K5(v);
            case 6 -> new K6(v);
            case 7 -> new K7(v);
            case 8 -> new K8(v);
            case 9 -> new K9(v);
            case 10 -> new K10(v);
            case 11 -> new K11(v);
            case 12 -> new K12(v);
            case 13 -> new K13(v);
            default -> new K14(v);
        };
    }

    // -----------------------------------------------------------------------------------
    // The three shapes, each behind a helper the hot loop calls
    // -----------------------------------------------------------------------------------

    @Benchmark
    public long virtualMethod() {
        long acc = 1;
        for (final Op op : ops) {
            acc = viaMethod(op, acc);
        }
        return acc;
    }

    private static long viaMethod(final Op op, final long acc) {
        return op.apply(acc);
    }

    @Benchmark
    public long switchInline() {
        long acc = 1;
        for (final Op op : ops) {
            acc = viaSwitch(op, acc);
        }
        return acc;
    }

    /** All fifteen arms, the work written in: the shape the resolver's write had at 336 bytes. */
    private static long viaSwitch(final Op op, final long acc) {
        return switch (op) {
            case final K0 k -> {
                final long v = k.v();
                yield acc * 31 + v;
            }
            case final K1 k -> {
                final long v = k.v();
                yield acc ^ (v << 3);
            }
            case final K2 k -> {
                final long v = k.v();
                yield acc + (v * v);
            }
            case final K3 k -> {
                final long v = k.v();
                yield (acc << 1) - v;
            }
            case final K4 k -> {
                final long v = k.v();
                yield acc + (v >>> 2);
            }
            case final K5 k -> {
                final long v = k.v();
                yield acc * 17 ^ v;
            }
            case final K6 k -> {
                final long v = k.v();
                yield acc - (v * 3);
            }
            case final K7 k -> {
                final long v = k.v();
                yield acc ^ (acc >>> 7) + v;
            }
            case final K8 k -> {
                final long v = k.v();
                yield acc + (v | 5);
            }
            case final K9 k -> {
                final long v = k.v();
                yield acc * 13 + (v & 255);
            }
            case final K10 k -> {
                final long v = k.v();
                yield (acc ^ v) * 3;
            }
            case final K11 k -> {
                final long v = k.v();
                yield acc + (v << 5) - 1;
            }
            case final K12 k -> {
                final long v = k.v();
                yield acc * 7 - (v >>> 1);
            }
            case final K13 k -> {
                final long v = k.v();
                yield acc ^ (v * 11);
            }
            case final K14 k -> {
                final long v = k.v();
                yield acc + (v ^ 0x55);
            }
        };
    }

    @Benchmark
    public long switchToMethods() {
        long acc = 1;
        for (final Op op : ops) {
            acc = viaSwitchToMethods(op, acc);
        }
        return acc;
    }

    /** The same switch, each arm one call to a small static method: the category-split shape. */
    private static long viaSwitchToMethods(final Op op, final long acc) {
        return switch (op) {
            case final K0 k -> work0(k.v(), acc);
            case final K1 k -> work1(k.v(), acc);
            case final K2 k -> work2(k.v(), acc);
            case final K3 k -> work3(k.v(), acc);
            case final K4 k -> work4(k.v(), acc);
            case final K5 k -> work5(k.v(), acc);
            case final K6 k -> work6(k.v(), acc);
            case final K7 k -> work7(k.v(), acc);
            case final K8 k -> work8(k.v(), acc);
            case final K9 k -> work9(k.v(), acc);
            case final K10 k -> work10(k.v(), acc);
            case final K11 k -> work11(k.v(), acc);
            case final K12 k -> work12(k.v(), acc);
            case final K13 k -> work13(k.v(), acc);
            case final K14 k -> work14(k.v(), acc);
        };
    }

    private static long work0(final long v, final long acc) {
        return acc * 31 + v;
    }

    private static long work1(final long v, final long acc) {
        return acc ^ (v << 3);
    }

    private static long work2(final long v, final long acc) {
        return acc + (v * v);
    }

    private static long work3(final long v, final long acc) {
        return (acc << 1) - v;
    }

    private static long work4(final long v, final long acc) {
        return acc + (v >>> 2);
    }

    private static long work5(final long v, final long acc) {
        return acc * 17 ^ v;
    }

    private static long work6(final long v, final long acc) {
        return acc - (v * 3);
    }

    private static long work7(final long v, final long acc) {
        return acc ^ (acc >>> 7) + v;
    }

    private static long work8(final long v, final long acc) {
        return acc + (v | 5);
    }

    private static long work9(final long v, final long acc) {
        return acc * 13 + (v & 255);
    }

    private static long work10(final long v, final long acc) {
        return (acc ^ v) * 3;
    }

    private static long work11(final long v, final long acc) {
        return acc + (v << 5) - 1;
    }

    private static long work12(final long v, final long acc) {
        return acc * 7 - (v >>> 1);
    }

    private static long work13(final long v, final long acc) {
        return acc ^ (v * 11);
    }

    private static long work14(final long v, final long acc) {
        return acc + (v ^ 0x55);
    }

    @Benchmark
    public long switchSevenInline() {
        long acc = 1;
        for (final Op op : ops) {
            acc = viaSwitchSeven(op, acc);
        }
        return acc;
    }

    /** Seven arms inline and the other eight behind one call: under the hot-inline limit. */
    private static long viaSwitchSeven(final Op op, final long acc) {
        return switch (op) {
            case final K0 k -> {
                final long v = k.v();
                yield acc * 31 + v;
            }
            case final K1 k -> {
                final long v = k.v();
                yield acc ^ (v << 3);
            }
            case final K2 k -> {
                final long v = k.v();
                yield acc + (v * v);
            }
            case final K3 k -> {
                final long v = k.v();
                yield (acc << 1) - v;
            }
            case final K4 k -> {
                final long v = k.v();
                yield acc + (v >>> 2);
            }
            case final K5 k -> {
                final long v = k.v();
                yield acc * 17 ^ v;
            }
            case final K6 k -> {
                final long v = k.v();
                yield acc - (v * 3);
            }
            default -> op.apply(acc);
        };
    }
}
