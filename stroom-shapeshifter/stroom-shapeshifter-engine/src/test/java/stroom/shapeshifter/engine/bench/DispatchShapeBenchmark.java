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
 * Design 37 phase 6's theory, exercised on its own: the same seven-arm dispatch the engine's
 * hot switches have, written four ways over a sealed hierarchy of records, on a stream of
 * receivers mixed as an interpreter sees them. Nothing here touches the engine; the question
 * is whether a pattern {@code switch} (an {@code invokedynamic typeSwitch} bootstrap in front
 * of a jump table) costs anything a plain {@code tableswitch} on an {@code int} kind does not.
 *
 * <p>Four dispatches: the pattern switch; an explicit {@code instanceof} chain, commonest arm
 * first; a {@code switch} on an {@code int} record component; and a virtual call on the
 * interface, which is the shape phase 6 ruled out in advance. The mix is the parameter:
 * one class, three classes evenly, all seven evenly, and seven skewed to one at 90%.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
@State(Scope.Benchmark)
public class DispatchShapeBenchmark {

    /** How many receiver classes the stream holds, and how: 1, 3, 7, or 7 skewed. */
    @Param({"mono", "three", "seven", "skewed"})
    public String mix;

    private static final int N = 4096;

    sealed interface Op permits A, B, C, D, E, F, G {

        int kind();

        int work();
    }

    record A(int v, int kind) implements Op {

        A(final int v) {
            this(v, 0);
        }

        public int work() {
            return v + 1;
        }
    }

    record B(int v, int kind) implements Op {

        B(final int v) {
            this(v, 1);
        }

        public int work() {
            return v + 2;
        }
    }

    record C(int v, int kind) implements Op {

        C(final int v) {
            this(v, 2);
        }

        public int work() {
            return v + 3;
        }
    }

    record D(int v, int kind) implements Op {

        D(final int v) {
            this(v, 3);
        }

        public int work() {
            return v + 4;
        }
    }

    record E(int v, int kind) implements Op {

        E(final int v) {
            this(v, 4);
        }

        public int work() {
            return v + 5;
        }
    }

    record F(int v, int kind) implements Op {

        F(final int v) {
            this(v, 5);
        }

        public int work() {
            return v + 6;
        }
    }

    record G(int v, int kind) implements Op {

        G(final int v) {
            this(v, 6);
        }

        public int work() {
            return v + 7;
        }
    }

    /** The same seven as classes over a base that holds the kind in a field: no call to read it. */
    abstract static class Node {

        final int kind;
        final int v;

        Node(final int kind, final int v) {
            this.kind = kind;
            this.v = v;
        }
    }

    static final class NA extends Node {

        NA(final int v) {
            super(0, v);
        }
    }

    static final class NB extends Node {

        NB(final int v) {
            super(1, v);
        }
    }

    static final class NC extends Node {

        NC(final int v) {
            super(2, v);
        }
    }

    static final class ND extends Node {

        ND(final int v) {
            super(3, v);
        }
    }

    static final class NE extends Node {

        NE(final int v) {
            super(4, v);
        }
    }

    static final class NF extends Node {

        NF(final int v) {
            super(5, v);
        }
    }

    static final class NG extends Node {

        NG(final int v) {
            super(6, v);
        }
    }

    private Op[] ops;
    private Node[] nodes;

    @Setup
    public void setup() {
        final Random random = new Random(42);
        ops = new Op[N];
        nodes = new Node[N];
        for (int i = 0; i < N; i++) {
            final int r = random.nextInt(100);
            final int which = switch (mix) {
                case "mono" -> 0;
                case "three" -> r % 3;
                case "seven" -> r % 7;
                default -> r < 90 ? 0 : 1 + (r % 6);
            };
            ops[i] = make(which, i);
            nodes[i] = makeNode(which, i);
        }
    }

    private static Node makeNode(final int which, final int v) {
        return switch (which) {
            case 0 -> new NA(v);
            case 1 -> new NB(v);
            case 2 -> new NC(v);
            case 3 -> new ND(v);
            case 4 -> new NE(v);
            case 5 -> new NF(v);
            default -> new NG(v);
        };
    }

    private static Op make(final int which, final int v) {
        return switch (which) {
            case 0 -> new A(v);
            case 1 -> new B(v);
            case 2 -> new C(v);
            case 3 -> new D(v);
            case 4 -> new E(v);
            case 5 -> new F(v);
            default -> new G(v);
        };
    }

    @Benchmark
    public int patternSwitch() {
        int sum = 0;
        for (final Op op : ops) {
            sum += switch (op) {
                case final A a -> a.v() + 1;
                case final B b -> b.v() + 2;
                case final C c -> c.v() + 3;
                case final D d -> d.v() + 4;
                case final E e -> e.v() + 5;
                case final F f -> f.v() + 6;
                case final G g -> g.v() + 7;
            };
        }
        return sum;
    }

    @Benchmark
    public int instanceofChain() {
        int sum = 0;
        for (final Op op : ops) {
            if (op instanceof final A a) {
                sum += a.v() + 1;
            } else if (op instanceof final B b) {
                sum += b.v() + 2;
            } else if (op instanceof final C c) {
                sum += c.v() + 3;
            } else if (op instanceof final D d) {
                sum += d.v() + 4;
            } else if (op instanceof final E e) {
                sum += e.v() + 5;
            } else if (op instanceof final F f) {
                sum += f.v() + 6;
            } else {
                sum += ((G) op).v() + 7;
            }
        }
        return sum;
    }

    /** The kind read through the interface: a record component behind a seven-way virtual call. */
    @Benchmark
    public int kindAccessorSwitch() {
        int sum = 0;
        for (final Op op : ops) {
            sum += switch (op.kind()) {
                case 0 -> ((A) op).v() + 1;
                case 1 -> ((B) op).v() + 2;
                case 2 -> ((C) op).v() + 3;
                case 3 -> ((D) op).v() + 4;
                case 4 -> ((E) op).v() + 5;
                case 5 -> ((F) op).v() + 6;
                default -> ((G) op).v() + 7;
            };
        }
        return sum;
    }

    /** The kind read from a field on the base class: no call at all before the jump. */
    @Benchmark
    public int kindFieldSwitch() {
        int sum = 0;
        for (final Node node : nodes) {
            sum += switch (node.kind) {
                case 0 -> ((NA) node).v + 1;
                case 1 -> ((NB) node).v + 2;
                case 2 -> ((NC) node).v + 3;
                case 3 -> ((ND) node).v + 4;
                case 4 -> ((NE) node).v + 5;
                case 5 -> ((NF) node).v + 6;
                default -> ((NG) node).v + 7;
            };
        }
        return sum;
    }

    @Benchmark
    public int virtualCall() {
        int sum = 0;
        for (final Op op : ops) {
            sum += op.work();
        }
        return sum;
    }
}
