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
 *
 * <h2>Running out of input</h2>
 * Every operation that stops because it reached the end of the window, rather than because the
 * input told it to stop, sets a flag. If the window may still grow, the attempt then reports
 * {@link #NEED_MORE} rather than a verdict — including when it <em>succeeded</em>, because a
 * greedy scan that halted at the edge would have consumed more had more been there. Reporting
 * that short match instead is exactly how a streaming parser truncates a record.
 */
public final class PlanRunner {

    /** No match, and more input could not change that. */
    public static final int NO_MATCH = -1;

    /** Undetermined: the attempt was limited by the end of the window. */
    public static final int NEED_MORE = -2;

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
     * @param complete   whether the window can still grow.
     * @param slots      capture slots, which the caller must fill with -1 before each attempt.
     * @return the end offset of the match, or {@link #NO_MATCH}, or {@link #NEED_MORE}.
     */
    public static int run(final Plan plan,
                          final byte[] data,
                          final int regionFrom,
                          final int start,
                          final int to,
                          final boolean complete,
                          final int[] slots) {
        final int[] op = plan.op;
        final int[] a = plan.a;
        final int[] b = plan.b;
        final int[] c = plan.c;

        int pc = 0;
        int cursor = start;
        // Set whenever an operation is cut short by the window edge rather than by the data.
        boolean hitEnd = false;

        while (true) {
            switch (op[pc]) {
                case Plan.MATCH_BYTE -> {
                    if (cursor >= to) {
                        return undetermined(complete);
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
                            return NO_MATCH; // a real mismatch, whatever else arrives
                        }
                    }
                    if (available < literal.length) {
                        return undetermined(complete); // ran out part way through
                    }
                    cursor += literal.length;
                    pc++;
                }

                case Plan.MATCH_CLASS -> {
                    if (cursor >= to) {
                        return undetermined(complete);
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
                    if (cursor == to && count < max) {
                        hitEnd = true; // more of the same could have followed
                    }
                    if (count < b[pc]) {
                        return hitEnd
                                ? undetermined(complete)
                                : NO_MATCH;
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
                    if (cursor == to && count < max) {
                        hitEnd = true; // the terminator may simply not have arrived yet
                    }
                    if (count < b[pc]) {
                        return hitEnd
                                ? undetermined(complete)
                                : NO_MATCH;
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
                    if (cursor == to && count < max) {
                        hitEnd = true;
                    }
                    if (count < b[pc]) {
                        return hitEnd
                                ? undetermined(complete)
                                : NO_MATCH;
                    }
                    pc++;
                }

                case Plan.MATCH_CHAR -> {
                    final int length = plan.charClasses[a[pc]].matchAt(data, cursor, to);
                    if (length < 0) {
                        // A character may simply be split across the window edge.
                        return cursor >= to || plan.charClasses[a[pc]].mayContinue(data, cursor, to)
                                ? undetermined(complete)
                                : NO_MATCH;
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
                    if (count < max
                        && (cursor >= to || charClass.mayContinue(data, cursor, to))) {
                        hitEnd = true;
                    }
                    if (count < b[pc]) {
                        return hitEnd
                                ? undetermined(complete)
                                : NO_MATCH;
                    }
                    pc++;
                }

                case Plan.SAVE -> {
                    slots[a[pc]] = cursor;
                    pc++;
                }

                case Plan.BRANCH -> {
                    if (cursor >= to && !complete) {
                        // The byte that would choose a branch may not have arrived. Taking the
                        // default now would silently prefer the empty alternative.
                        return NEED_MORE;
                    }
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
                    if (!complete && cursor == to && endRelated(kind)) {
                        // Whether this is the end is not yet knowable.
                        return NEED_MORE;
                    }
                    if (!holds(kind, data, regionFrom, to, cursor)) {
                        return NO_MATCH;
                    }
                    pc++;
                }

                case Plan.ACCEPT -> {
                    // A match that ended at the edge may not be the whole match.
                    return hitEnd && !complete
                            ? NEED_MORE
                            : cursor;
                }

                default -> throw new IllegalStateException("Unknown opcode " + op[pc]);
            }
        }
    }

    private static int undetermined(final boolean complete) {
        return complete
                ? NO_MATCH
                : NEED_MORE;
    }

    private static boolean endRelated(final Hir.Kind kind) {
        // A word boundary depends on the byte after the cursor as much as the one before, so at
        // the edge of a growing window it is just as undetermined as an end anchor.
        return kind == Hir.Kind.END_INPUT
               || kind == Hir.Kind.END_LINE
               || kind == Hir.Kind.WORD_BOUNDARY
               || kind == Hir.Kind.NOT_WORD_BOUNDARY;
    }



    private static boolean holds(final Hir.Kind kind,
                                 final byte[] data,
                                 final int regionFrom,
                                 final int to,
                                 final int cursor) {
        return switch (kind) {
            case START_INPUT -> cursor == regionFrom;
            case START_LINE -> cursor == regionFrom || data[cursor - 1] == '\n';
            case END_INPUT -> cursor == to;
            case END_LINE -> cursor == to || data[cursor] == '\n';
            case WORD_BOUNDARY -> Words.atBoundary(data, regionFrom, to, cursor, true);
            case NOT_WORD_BOUNDARY -> !Words.atBoundary(data, regionFrom, to, cursor, true);
            case WORD_BOUNDARY_ASCII -> Words.atBoundary(data, regionFrom, to, cursor, false);
            case NOT_WORD_BOUNDARY_ASCII -> !Words.atBoundary(data, regionFrom, to, cursor, false);
            // \G makes a pattern fancy, and a fancy pattern is never one-pass, so it cannot
            // reach a scan plan.
            case PREVIOUS_MATCH_END -> throw new IllegalStateException(
                    "\\G reached an engine that cannot evaluate it");
        };
    }
}
