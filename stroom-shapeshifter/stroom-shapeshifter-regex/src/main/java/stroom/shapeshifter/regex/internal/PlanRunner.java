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

package stroom.shapeshifter.regex.internal;

/**
 * Executes a {@link Plan} against a byte array.
 * <p>
 * A single forward pass with no backtracking: every op either consumes bytes and continues, or
 * fails the whole attempt. That is what the one-pass analysis buys, and it is why the plan can
 * be a loop over flat arrays rather than a thread list.
 */
public final class PlanRunner {

    /** No match. */
    public static final int NO_MATCH = -1;

    private PlanRunner() {
    }

    /**
     * Runs {@code plan} starting at {@code start}.
     *
     * @param regionFrom the start of the search region. Distinct from {@code start}: during an
     *                   unanchored search {@code start} advances, but {@code ^} and {@code \A}
     *                   must still refer to where the region began, or a start-anchored pattern
     *                   would match at any offset.
     * @param start      the offset this attempt begins at.
     * @param slots      capture slots, which the caller must fill with -1 before each attempt.
     * @return the end offset of the match, or {@link #NO_MATCH}.
     */
    public static int run(final Plan plan,
                          final byte[] data,
                          final int regionFrom,
                          final int start,
                          final int to,
                          final int[] slots) {
        final int[] op = plan.op;
        final int[] a = plan.a;
        final int[] b = plan.b;
        final int[] c = plan.c;

        int pc = 0;
        int cursor = start;

        while (true) {
            switch (op[pc]) {
                case Plan.MATCH_BYTE -> {
                    if (cursor >= to) {
                        return NO_MATCH;
                    }
                    if ((data[cursor] & 0xFF) != a[pc]) {
                        return NO_MATCH;
                    }
                    cursor++;
                    pc++;
                }

                case Plan.MATCH_LITERAL -> {
                    final byte[] literal = plan.literals[a[pc]];
                    final int available = Math.min(literal.length, to - cursor);
                    for (int i = 0; i < available; i++) {
                        if (data[cursor + i] != literal[i]) {
                            return NO_MATCH; // a real mismatch
                        }
                    }
                    if (available < literal.length) {
                        return NO_MATCH; // ran out part way through
                    }
                    cursor += literal.length;
                    pc++;
                }

                case Plan.MATCH_CLASS -> {
                    if (cursor >= to) {
                        return NO_MATCH;
                    }
                    if (plan.classes[a[pc]][data[cursor] & 0xFF] == 0) {
                        return NO_MATCH;
                    }
                    cursor++;
                    pc++;
                }

                case Plan.SCAN_WHILE_BYTE -> {
                    final int value = a[pc];
                    final int max = c[pc];
                    int count = 0;
                    while (cursor < to && count < max && (data[cursor] & 0xFF) == value) {
                        cursor++;
                        count++;
                    }
                    if (count < b[pc]) {
                        return NO_MATCH;
                    }
                    pc++;
                }

                case Plan.SCAN_UNTIL_BYTE -> {
                    final int stop = a[pc];
                    final int max = c[pc];
                    int count = 0;
                    while (cursor < to && count < max && (data[cursor] & 0xFF) != stop) {
                        cursor++;
                        count++;
                    }
                    if (count < b[pc]) {
                        return NO_MATCH;
                    }
                    pc++;
                }

                case Plan.SCAN_WHILE_CLASS -> {
                    final byte[] table = plan.classes[a[pc]];
                    final int max = c[pc];
                    int count = 0;
                    while (cursor < to && count < max && table[data[cursor] & 0xFF] != 0) {
                        cursor++;
                        count++;
                    }
                    if (count < b[pc]) {
                        return NO_MATCH;
                    }
                    pc++;
                }

                case Plan.MATCH_CHAR -> {
                    final int length = plan.charClasses[a[pc]].matchAt(data, cursor, to);
                    if (length < 0) {
                        return NO_MATCH;
                    }
                    cursor += length;
                    pc++;
                }

                case Plan.SCAN_WHILE_CHAR -> {
                    final CharClass charClass = plan.charClasses[a[pc]];
                    final int max = c[pc];
                    int count = 0;
                    while (count < max) {
                        final int length = charClass.matchAt(data, cursor, to);
                        if (length < 0) {
                            break;
                        }
                        cursor += length;
                        count++;
                    }
                    if (count < b[pc]) {
                        return NO_MATCH;
                    }
                    pc++;
                }

                case Plan.SAVE -> {
                    slots[a[pc]] = cursor;
                    pc++;
                }

                case Plan.BRANCH -> {
                    final int[] table = plan.branchTables[a[pc]];
                    final int target = cursor < to
                            ? table[data[cursor] & 0xFF]
                            : Plan.NO_TARGET;
                    if (target != Plan.NO_TARGET) {
                        pc = target;
                    } else if (table[Plan.BRANCH_DEFAULT] != Plan.NO_TARGET) {
                        pc = table[Plan.BRANCH_DEFAULT];
                    } else {
                        return NO_MATCH;
                    }
                }

                case Plan.JUMP -> pc = a[pc];

                case Plan.ASSERT -> {
                    final Hir.Kind kind = Hir.Kind.VALUES[a[pc]];
                    // \G makes a pattern fancy, and a fancy pattern is never one-pass, so the
                    // shared evaluation's \G refusal is unreachable from here.
                    if (!Words.assertionHolds(kind, data, regionFrom, to, cursor)) {
                        return NO_MATCH;
                    }
                    pc++;
                }

                case Plan.ACCEPT -> {
                    return cursor;
                }

                default -> throw new IllegalStateException("Unknown opcode " + op[pc]);
            }
        }
    }
}
