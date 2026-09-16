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
 * The type test an {@code equals} makes, on its own: {@code getClass() == X.class} against
 * {@code instanceof X} on a final class, against {@code instanceof I} on the sealed interface
 * the classes implement — over a stream of receivers that are the class, another class, or
 * null, mixed as a map key's {@code equals} sees them. The value class's comment says the
 * interface test is a secondary-supers search and the final-class tests are one compare; this
 * puts a number on both, and on whether the two final-class forms differ at all.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class TypeTestBenchmark {

    /** What the stream holds: all the class; the class and one other; the class, four others and nulls. */
    @Param({"same", "two", "mixed"})
    public String mix;

    private static final int N = 4096;

    sealed interface Val permits P, Q, R, S, T {
    }

    static final class P implements Val {

        final int v;

        P(final int v) {
            this.v = v;
        }
    }

    static final class Q implements Val {

        Q() {
        }
    }

    static final class R implements Val {

        R() {
        }
    }

    static final class S implements Val {

        S() {
        }
    }

    static final class T implements Val {

        T() {
        }
    }

    /** Something that is not a Val at all. */
    static final class Other {

        Other() {
        }
    }

    private Object[] items;

    @Setup
    public void setup() {
        final Random random = new Random(7);
        items = new Object[N];
        for (int i = 0; i < N; i++) {
            final int r = random.nextInt(100);
            items[i] = switch (mix) {
                case "same" -> new P(i);
                case "two" -> r < 50 ? new P(i) : new Q();
                default -> switch (r % 8) {
                    case 0, 1, 2 -> new P(i);
                    case 3 -> new Q();
                    case 4 -> new R();
                    case 5 -> new S();
                    case 6 -> new Other();
                    default -> null;
                };
            };
        }
    }

    @Benchmark
    public int classCompare() {
        int hits = 0;
        for (final Object item : items) {
            if (item != null && item.getClass() == P.class) {
                hits += ((P) item).v;
            }
        }
        return hits;
    }

    @Benchmark
    public int instanceofFinal() {
        int hits = 0;
        for (final Object item : items) {
            if (item instanceof final P p) {
                hits += p.v;
            }
        }
        return hits;
    }

    @Benchmark
    public int instanceofInterface() {
        int hits = 0;
        for (final Object item : items) {
            if (item instanceof Val) {
                hits += 1;
            }
        }
        return hits;
    }

    /** The interface test then the class test, which is what an equals across three variants does. */
    @Benchmark
    public int instanceofInterfaceThenClass() {
        int hits = 0;
        for (final Object item : items) {
            if (item instanceof Val && item.getClass() == P.class) {
                hits += ((P) item).v;
            }
        }
        return hits;
    }
}
