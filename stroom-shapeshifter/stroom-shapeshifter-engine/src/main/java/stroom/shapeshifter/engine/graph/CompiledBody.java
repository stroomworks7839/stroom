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

package stroom.shapeshifter.engine.graph;

import java.util.Arrays;
import java.util.List;

/**
 * A compiled body: a program, as an opcode array beside its operands (design 33).
 *
 * <p>The engine's own regex library has dispatched this way since it was written — {@code Plan}
 * holds an {@code int[] op} with parallel operand arrays, and six runners switch on
 * {@code op[pc]}. This is the same shape one module over, and for the same reason: a
 * {@code switch} over an {@code int} is a jump table, while a {@code switch} over a sealed type
 * is a chain of {@code instanceof} tests in case order, behind a generated method the JIT
 * declines to inline once there are more than about twenty arms.
 *
 * <p><b>The codes are derived, never authored.</b> {@link #of} computes each from its own
 * instruction through {@link CompiledOp#codeOf}, so a code cannot disagree with the op it
 * indexes — the failure mode that would otherwise make this trade a bad one, since a wrong code
 * would run a real instruction's arm against another instruction's operands and answer
 * confidently. The type switch that does the deriving is exhaustive by javac's rule, so adding a
 * kind is still a compile error rather than a run-time surprise.
 *
 * <p><b>Nothing may write to either array.</b> A compiled project outlives the runs that use it
 * and is shared between them (D35); the arrays are what {@link CompiledOp}'s own javadoc says
 * about bodies, in a type that carries them together.
 *
 * @param codes each instruction's opcode, parallel to {@code ops}
 * @param ops   the instructions themselves, holding the operands each arm reads
 */
public record CompiledBody(int[] codes, CompiledOp[] ops) {

    /** The empty program, shared: a body with nothing in it is common and allocates nothing. */
    public static final CompiledBody EMPTY = new CompiledBody(new int[0], new CompiledOp[0]);

    /** How many instructions there are. */
    public int size() {
        return ops.length;
    }

    /** Derive a program from its instructions, computing each opcode from the op it belongs to. */
    public static CompiledBody of(final List<CompiledOp> ops) {
        if (ops.isEmpty()) {
            return EMPTY;
        }
        final CompiledOp[] instructions = ops.toArray(new CompiledOp[0]);
        final int[] codes = new int[instructions.length];
        for (int i = 0; i < instructions.length; i++) {
            codes[i] = CompiledOp.codeOf(instructions[i]);
        }
        return new CompiledBody(codes, instructions);
    }

    /** The instructions from {@code from} up to {@code to}, as a program of their own. */
    public CompiledBody slice(final int from, final int to) {
        if (from >= to) {
            return EMPTY;
        }
        return new CompiledBody(Arrays.copyOfRange(codes, from, to),
                Arrays.copyOfRange(ops, from, to));
    }
}
