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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two properties an opcode dispatch has that a sealed type switch gave for free.
 *
 * <p>javac checks that {@link CompiledOp#codeOf} covers every kind, because it is a switch
 * expression over a sealed type. It does <b>not</b> check that the interpreter's {@code switch}
 * covers every opcode, nor that the codes are dense — and a sparse set turns the {@code
 * tableswitch} javac emits into a {@code lookupswitch}, which is a binary search wearing the same
 * syntax. Both are held here (design 33 §6).
 */
class OpcodesTest {

    @Test
    void everyOpcodeIsDistinctAndContiguousFromZero() throws IllegalAccessException {
        final List<Integer> codes = new ArrayList<>();
        for (final Field field : CompiledOp.class.getDeclaredFields()) {
            if (field.getName().startsWith("OP_") && Modifier.isStatic(field.getModifiers())) {
                codes.add(field.getInt(null));
            }
        }
        assertThat(codes).isNotEmpty();
        assertThat(new TreeSet<>(codes))
                .as("dense from zero, or the dispatch becomes a binary search")
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, codes.size()).boxed().toList());
    }

    @Test
    void everyInstructionKindHasAnOpcodeNamedAfterIt() {
        final List<String> missing = new ArrayList<>();
        for (final Class<?> kind : CompiledOp.class.getPermittedSubclasses()) {
            final String expected = "OP_" + kind.getSimpleName()
                    .replaceAll("(?<!^)(?=[A-Z])", "_").toUpperCase(Locale.ROOT);
            try {
                CompiledOp.class.getDeclaredField(expected);
            } catch (final NoSuchFieldException e) {
                missing.add(kind.getSimpleName() + " wants " + expected);
            }
        }
        assertThat(missing)
                .as("a kind added without an opcode, or an opcode renamed away from its kind")
                .isEmpty();
    }

    /**
     * The count has to match too, or an opcode could outlive the kind it was named for and leave
     * a hole in the table — which {@link #everyOpcodeIsDistinctAndContiguousFromZero} would then
     * report as sparseness with no clue as to why.
     */
    @Test
    void thereAreNoOpcodesWithoutAKind() {
        final long codes = java.util.Arrays.stream(CompiledOp.class.getDeclaredFields())
                .filter(field -> field.getName().startsWith("OP_"))
                .count();
        assertThat(codes).isEqualTo(CompiledOp.class.getPermittedSubclasses().length);
    }
}
