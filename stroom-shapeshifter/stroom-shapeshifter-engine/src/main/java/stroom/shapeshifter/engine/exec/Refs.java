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

import stroom.shapeshifter.engine.config.EngineVars;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.value.TypedValue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Resolving the configuration's authored expressions against what has been captured: what a
 * condition calls. Bodies and capture bindings resolve compiled references through
 * {@link CompiledRefs} instead (design 25 phase 3 took the captures), and E39 owns the seam
 * between the two.
 *
 * <p><b>This is the side that still works in names.</b> Since design 30 phase 5 a compiled
 * reference holds the slot it means, so the registry answers it from an array; a condition
 * arrives with a string and pays a lookup to find the slot. Everything after that — the group,
 * the index rule, absence — is shared with the compiled resolver rather than written twice, and
 * the two methods that are not shared are the two that turn a name into stores.
 *
 * <p><b>Empty is absent.</b> A part that resolves to nothing writes nothing, and an expression
 * whose parts all resolve to nothing has no value at all rather than an empty one. That is what
 * lets a configuration ask whether a field exists.
 *
 * <p>No encoding is threaded here: a captured value carries its own (design 25), and every
 * part of a composite is joined in its UTF-8 form, which for the overwhelming majority of
 * inputs is the bytes themselves.
 */
public final class Refs {

    /**
     * "The last value there is", which a store answers from its own contents and a frame
     * answers by having only one. Outside any store's index range by construction.
     */
    static final int LAST = Integer.MIN_VALUE;

    private Refs() {
    }

    /**
     * The value of an expression with its type preserved, or null if it resolves to nothing.
     *
     * <p>Only a single-part capture reference can carry a type (design/17 §3.1): literal text
     * and a multipart expression are strings by construction. A captured value carries its
     * own encoding (design 25), so nothing here converts.
     */
    public static TypedValue resolveValue(final RefExpression expression,
                                          final MatchResult match,
                                          final int matchCount,
                                          final VarRegistry vars) {
        if (expression == null || expression.parts().isEmpty()) {
            return null;
        }
        if (expression.parts().size() == 1
            && expression.parts().getFirst() instanceof final RefPart.Capture capture) {
            final TypedValue value = lookup(capture, match, matchCount, vars);
            return value == null || value.isEmpty() ? null : value;
        }
        final byte[] resolved = resolve(expression, match, matchCount, vars);
        return resolved == null ? null : TypedValue.utf8(resolved);
    }

    /** The value of an expression as UTF-8 bytes, or null if it resolves to nothing. */
    public static byte[] resolve(final RefExpression expression,
                                 final MatchResult match,
                                 final int matchCount,
                                 final VarRegistry vars) {
        if (expression == null || expression.parts().isEmpty()) {
            return null;
        }

        // One part is the common shape by a wide margin, and it can answer without copying.
        if (expression.parts().size() == 1) {
            return switch (expression.parts().getFirst()) {
                case final RefPart.Text text -> text.value().isEmpty()
                        ? null
                        : text.value().getBytes(StandardCharsets.UTF_8);
                case final RefPart.Capture capture -> {
                    final TypedValue value = lookup(capture, match, matchCount, vars);
                    yield value == null || value.isEmpty() ? null : value.asUtf8();
                }
            };
        }

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean any = false;
        for (final RefPart part : expression.parts()) {
            final byte[] bytes = switch (part) {
                case final RefPart.Text text -> text.value().getBytes(StandardCharsets.UTF_8);
                case final RefPart.Capture capture -> {
                    final TypedValue value = lookup(capture, match, matchCount, vars);
                    yield value == null ? null : value.asUtf8();
                }
            };
            if (bytes != null && bytes.length > 0) {
                buffer.writeBytes(bytes);
                any = true;
            }
        }
        return any ? buffer.toByteArray() : null;
    }

    /** The value of an expression as text, or null. */
    public static String resolveText(final RefExpression expression,
                                     final MatchResult match,
                                     final int matchCount,
                                     final VarRegistry vars) {
        final byte[] bytes = resolve(expression, match, matchCount, vars);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** One capture reference: a group of the current match, or of a named variable. */
    private static TypedValue lookup(final RefPart.Capture capture,
                                     final MatchResult match,
                                     final int matchCount,
                                     final VarRegistry vars) {
        if (capture.varId() == null) {
            return match.group(capture.group());
        }
        return lookup(capture.varId(), capture.group(), capture.matchIndex(), matchCount, vars);
    }

    /** A group of a named variable, under a reference's index rule. */
    private static TypedValue lookup(final String varId,
                                     final int group,
                                     final MatchIndex matchIndex,
                                     final int matchCount,
                                     final VarRegistry vars) {
        // A condition still resolves the authored expression, so the name is classified and
        // looked up here rather than at compile time — E39's seam, and the reason this method
        // exists at all beside CompiledRefs' own.
        final EngineVars engine = EngineVars.byName(varId);
        if (engine != null && engine.framed()) {
            final TypedValue value = vars.frames().value(engine);
            // The index rule is resolved only once there is something to index, which is what
            // the walk this replaced did: it may read another variable, and "empty is absent"
            // makes the absent case the common one.
            return value == null
                    ? null
                    : framed(value, group, indexOf(matchIndex, matchCount, vars));
        }
        final List<Store> stores = vars.get(varId);
        return stores == null
                ? null
                : indexed(stores, group, indexOf(matchIndex, matchCount, vars), matchCount);
    }

    /**
     * One value out of a name's stores, under an index rule already resolved to a number.
     *
     * <p>Shared with the compiled resolver: the two differ in how a name becomes stores, and in
     * nothing after that.
     */
    static TypedValue indexed(final List<Store> stores,
                              final int group,
                              final Integer index,
                              final int matchCount) {
        if (stores == null || group >= stores.size()) {
            return null;
        }
        final Store store = stores.get(group);
        if (index == null) {
            return store.latest();
        }
        if (index == LAST) {
            final int last = store.lastIndex();
            return store.get(last < 0 ? matchCount : last);
        }
        return store.get(index);
    }

    /**
     * A framed value under the same rule.
     *
     * <p>What a frame replaced was a store holding one value, at index one, in a list of one. So
     * a reference reads it when it asks for the latest, for the last, or for index one, and reads
     * nothing otherwise — which is what indexing past a single-valued store already did.
     */
    static TypedValue framed(final TypedValue value, final int group, final Integer index) {
        if (group != 0 || value == null) {
            return null;
        }
        return index == null || index == LAST || index == 1 ? value : null;
    }

    /**
     * Which value an authored index rule means: null for the most recent, {@link #LAST} for the
     * last there is, or a match index.
     *
     * <p>Four ways to say it, and they exist because four things genuinely need saying: read the
     * index out of another variable at runtime, take the last one there is, count relative to the
     * match being processed, or name it outright. The relative form is the one that makes a
     * header row line up with a data row — the engine's own {@code __match_count} threads the
     * column number through. Nothing makes the four exclusive, so the order they are asked in is
     * behaviour.
     */
    private static Integer indexOf(final MatchIndex matchIndex,
                                   final int matchCount,
                                   final VarRegistry vars) {
        if (matchIndex == null) {
            return null;
        }
        if (matchIndex.varRef() != null) {
            final EngineVars engine = EngineVars.byName(matchIndex.varRef());
            return number(engine != null && engine.framed()
                    ? vars.frames().value(engine)
                    : latest(vars.get(matchIndex.varRef())));
        }
        return rule(matchIndex.index(), matchIndex.isOffset(), matchIndex.isLast(), matchCount);
    }

    /** The three forms that need no variable, shared with the compiled resolver. */
    static Integer rule(final int index,
                        final boolean isOffset,
                        final boolean isLast,
                        final int matchCount) {
        if (isLast) {
            return LAST;
        }
        return isOffset ? matchCount + index : index;
    }

    /** The most recent value a name's stores hold, or null. */
    static TypedValue latest(final List<Store> stores) {
        return stores == null || stores.isEmpty() ? null : stores.getFirst().latest();
    }

    /**
     * The whole number a value holds, for the index rule that reads one at run time.
     *
     * <p>Absent or non-numeric reads as <b>the first match</b>: the conservative answer, since
     * match indexes count from one.
     */
    static int number(final TypedValue value) {
        if (value != null) {
            final Double count = value.asNumber();
            if (count != null && count >= 0) {
                return (int) (double) count;
            }
        }
        return 1;
    }
}
