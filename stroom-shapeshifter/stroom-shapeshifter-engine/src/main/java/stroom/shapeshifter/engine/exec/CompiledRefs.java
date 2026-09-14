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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.graph.CompiledIndex;
import stroom.shapeshifter.engine.graph.CompiledRef;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.value.TypedValue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resolving compiled references — {@link Refs} with the interpretation already done.
 *
 * <p>Two ways in: {@link #write} streams parts through the seam, {@link #resolve} builds the
 * value; and <b>empty is absent</b>, as it is for {@link Refs}. The difference is what no longer happens per
 * call: literal text is a value made at compile time, written as its own array by a UTF-8
 * sink (design 25), and the shape of the expression is a dispatch, not a walk.
 *
 * <p>Two resolvers stay, and after design 30 phase 5 the second one is the design's last loose
 * end rather than a deferred optimisation. Bodies and capture bindings resolve compiled
 * references here, by <b>slot</b>; {@code Conditions} still resolves the authored expression
 * through {@code Refs}, by <b>name</b>. That was deferred on a measurement — design 10 §2's open
 * performance row, at 0.4% to 0.7% (design 27 §2.7, E39) — and the measurement still holds. What
 * changed is that it is now the only run-time name lookup left whose key the compiler knew:
 * 19,440 per operation on {@code apache_httpd}, 8,792 on {@code log_sessions}. Design 30 §6
 * phase 6 is the proposal, and its argument is the count rather than the clock.
 */
final class CompiledRefs {

    /**
     * "The last value there is", which a list answers from its own contents and a scalar
     * answers by having only one. Outside any list's index range by construction.
     */
    private static final int LAST = Integer.MIN_VALUE;

    private CompiledRefs() {
    }

    /**
     * Write a reference to the sink.
     *
     * @return true if anything was written
     */
    static boolean write(final CompiledRef ref,
                         final MatchResult match,
                         final int matchCount,
                         final VarRegistry vars,
                         final Output out) {
        switch (ref) {
            case final CompiledRef.Empty ignored -> {
                return false;
            }
            case final CompiledRef.Bytes bytes -> {
                if (bytes.value().isEmpty()) {
                    return false;
                }
                out.write(bytes.value());
                return true;
            }
            case final CompiledRef.Composite composite -> {
                boolean wrote = false;
                for (final CompiledRef part : composite.parts()) {
                    wrote |= write(part, match, matchCount, vars, out);
                }
                return wrote;
            }
            case final CompiledRef.LocalGroup group -> {
                final TypedValue value = match.group(group.group());
                if (value == null || value.isEmpty()) {
                    return false;
                }
                out.write(value);
                return true;
            }
            case final CompiledRef.RemoteVar remote -> {
                final TypedValue value = lookup(remote, matchCount, vars);
                if (value == null || value.isEmpty()) {
                    return false;
                }
                out.write(value);
                return true;
            }
            case final CompiledRef.Context context -> {
                final TypedValue value = lookup(context, matchCount, vars);
                if (value == null || value.isEmpty()) {
                    return false;
                }
                out.write(value);
                return true;
            }
            case final CompiledRef.Entry entry -> {
                final TypedValue value = lookup(entry, vars);
                if (value == null || value.isEmpty()) {
                    return false;
                }
                out.write(value);
                return true;
            }
        }
    }

    /**
     * The value of a reference with its type preserved, or null if it resolves to nothing.
     *
     * <p>Only a single capture can carry a type (design/17 §3.1): literal text and a
     * multipart composite are strings by construction. A captured value carries its own
     * encoding (design 25), so nothing here converts.
     */
    static TypedValue resolveValue(final CompiledRef ref,
                                   final MatchResult match,
                                   final int matchCount,
                                   final VarRegistry vars) {
        switch (ref) {
            case final CompiledRef.Empty ignored -> {
                return null;
            }
            case final CompiledRef.Bytes bytes -> {
                return bytes.value().isEmpty() ? null : bytes.value();
            }
            case final CompiledRef.Composite composite -> {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                boolean any = false;
                for (final CompiledRef part : composite.parts()) {
                    final TypedValue resolved = resolveValue(part, match, matchCount, vars);
                    if (resolved != null) {
                        buffer.writeBytes(resolved.asUtf8());
                        any = true;
                    }
                }
                return any ? TypedValue.utf8(buffer.toByteArray()) : null;
            }
            case final CompiledRef.LocalGroup group -> {
                final TypedValue value = match.group(group.group());
                return value == null || value.isEmpty() ? null : value;
            }
            case final CompiledRef.RemoteVar remote -> {
                final TypedValue value = lookup(remote, matchCount, vars);
                return value == null || value.isEmpty() ? null : value;
            }
            case final CompiledRef.Context context -> {
                final TypedValue value = lookup(context, matchCount, vars);
                return value == null || value.isEmpty() ? null : value;
            }
            case final CompiledRef.Entry entry -> {
                final TypedValue value = lookup(entry, vars);
                return value == null || value.isEmpty() ? null : value;
            }
        }
    }

    /** The value of a reference as UTF-8 bytes, or null if it resolves to nothing. */
    static byte[] resolve(final CompiledRef ref,
                          final MatchResult match,
                          final int matchCount,
                          final VarRegistry vars) {
        final TypedValue value = resolveValue(ref, match, matchCount, vars);
        return value == null ? null : value.asUtf8();
    }

    /** The value of a reference as text, or null. */
    static String resolveText(final CompiledRef ref,
                              final MatchResult match,
                              final int matchCount,
                              final VarRegistry vars) {
        final byte[] bytes = resolve(ref, match, matchCount, vars);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * One value out of a name's list, under an index rule already resolved to a number.
     *
     * <p>"The latest" and "the last" are both the last element (design 35 §8): a failed capture
     * appended absence to keep positions aligned, and a read that walked back past it would be
     * returning a value from a position the data did not fill.
     */
    private static TypedValue indexed(final TypedValue.List list, final Integer index) {
        if (index == null || index == LAST) {
            return list.last();
        }
        return list.get(index);
    }

    /**
     * A scalar under the same rule: one value, which a reference reads when it asks for the
     * latest, the last, this match's or the first, and reads nothing when it names another
     * position — what indexing past a single-valued store did before design 35 phase 3.
     */
    private static TypedValue scalar(final TypedValue value, final Integer index, final int matchCount) {
        return index == null || index == LAST || index == matchCount || index == 1 ? value : null;
    }

    /** The three index forms that need no variable. */
    private static Integer rule(final int index,
                                final boolean isOffset,
                                final boolean isLast,
                                final int matchCount) {
        if (isLast) {
            return LAST;
        }
        return isOffset ? matchCount + index : index;
    }

    /** The value a name holds for a read with no index: a list's last element, a scalar itself. */
    private static TypedValue latest(final TypedValue value) {
        return value instanceof final TypedValue.List list ? list.last() : value;
    }

    /**
     * The whole number a value holds, for the index rule that reads one at run time.
     *
     * <p>Absent or non-numeric reads as <b>the first match</b>: the conservative answer, since
     * match indexes count from one.
     */
    private static int number(final TypedValue value) {
        if (value != null) {
            final Double count = value.asNumber();
            if (count != null && count >= 0) {
                return (int) (double) count;
            }
        }
        return 1;
    }

    /**
     * A variable's value by slot, group and index rule, or null.
     *
     * <p>No name is resolved here and no map is consulted: the slot was decided when the
     * reference compiled (design 30 phase 5).
     */
    private static TypedValue lookup(final CompiledRef.RemoteVar remote,
                                     final int matchCount,
                                     final VarRegistry vars) {
        final TypedValue value = vars.get(remote.varId());
        // Nothing to index is nothing to resolve the rule for, and absent is the common case.
        if (value == null) {
            return null;
        }
        final Integer index = index(remote.matchIndex(), matchCount, vars);
        return value instanceof final TypedValue.List list
                ? indexed(list, index)
                : scalar(value, index, matchCount);
    }

    /** One entry of a map, or null when the map is unset or has no such key. */
    private static TypedValue lookup(final CompiledRef.Entry entry, final VarRegistry vars) {
        return vars.get(entry.map()) instanceof final TypedValue.Map map ? map.get(entry.key()) : null;
    }

    /**
     * A context value, read from the frame that holds it. Which frame was settled when the
     * reference compiled (design 30 phase 4).
     */
    private static TypedValue lookup(final CompiledRef.Context context,
                                     final int matchCount,
                                     final VarRegistry vars) {
        final TypedValue value = vars.frames().value(context.var());
        return value == null
                ? null
                : scalar(value, index(context.matchIndex(), matchCount, vars), matchCount);
    }

    /**
     * Which value an index rule means: null for the most recent, {@link #LAST} for the last
     * there is, or a match index.
     *
     * <p>Four ways to say it, and they exist because four things genuinely need saying: read the
     * index out of another variable at run time, take the last one there is, count relative to
     * the match being processed, or name it outright. The relative form is the one that makes a
     * header row line up with a data row — the engine's own {@code matchCount()} threads the
     * column number through. Nothing makes the four exclusive, so the order they are asked in is
     * behaviour.
     *
     * <p>The fourth reads another variable, and <em>which</em> variable — a registry slot or an
     * execution frame — was settled when the reference compiled.
     */
    private static Integer index(final CompiledIndex rule,
                                 final int matchCount,
                                 final VarRegistry vars) {
        if (rule == null) {
            return null;
        }
        if (rule.varContext() != null) {
            return number(vars.frames().value(rule.varContext()));
        }
        if (rule.varRef() != null) {
            return number(latest(vars.get(rule.varRef())));
        }
        return rule(rule.index(), rule.isOffset(), rule.isLast(), matchCount);
    }
}
