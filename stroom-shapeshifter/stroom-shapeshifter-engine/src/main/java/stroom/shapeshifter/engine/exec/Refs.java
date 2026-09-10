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
 * <p><b>Empty is absent.</b> A part that resolves to nothing writes nothing, and an expression
 * whose parts all resolve to nothing has no value at all rather than an empty one. That is what
 * lets a configuration ask whether a field exists.
 *
 * <p>No encoding is threaded here: a captured value carries its own (design 25), and every
 * part of a composite is joined in its UTF-8 form, which for the overwhelming majority of
 * inputs is the bytes themselves.
 */
public final class Refs {

    private Refs() {
    }

    /**
     * The value of an expression with its type preserved, or null if it resolves to nothing.
     *
     * <p>Only a single-part capture reference can carry a type (design/17 §3.1): literal text
     * and a multi-part expression are strings by construction. A captured value carries its
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
            && expression.parts().getFirst() instanceof RefPart.Capture capture) {
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
                case RefPart.Text text -> text.value().isEmpty()
                        ? null
                        : text.value().getBytes(StandardCharsets.UTF_8);
                case RefPart.Capture capture -> {
                    final TypedValue value = lookup(capture, match, matchCount, vars);
                    yield value == null || value.isEmpty() ? null : value.asUtf8();
                }
            };
        }

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean any = false;
        for (final RefPart part : expression.parts()) {
            final byte[] bytes = switch (part) {
                case RefPart.Text text -> text.value().getBytes(StandardCharsets.UTF_8);
                case RefPart.Capture capture -> {
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

    /** A group of a named variable, under a reference's index rule. Shared with the compiled form. */
    static TypedValue lookup(final String varId,
                             final int group,
                             final MatchIndex matchIndex,
                             final int matchCount,
                             final VarRegistry vars) {
        // A condition still resolves the authored expression (E39 owns that seam), so the name
        // is classified here rather than at compile time. The compiled form does not come
        // through this arm: it holds a CompiledRef.Context and reads the frame directly.
        final EngineVars engine = EngineVars.byName(varId);
        if (engine != null && engine.framed()) {
            return framed(engine, group, matchIndex, matchCount, vars);
        }
        final List<Store> stores = vars.get(varId);
        if (stores == null || group >= stores.size()) {
            return null;
        }
        final Store store = stores.get(group);
        final Integer index = matchIndex(matchIndex, store, matchCount, vars);
        return index == null ? store.latest() : store.get(index);
    }

    /**
     * An engine variable under a reference's index rule (design 30 phase 4).
     *
     * <p>What a frame replaced was a store holding one value, at index one, in a list of one.
     * So a reference reads it when it asks for the latest, for the last, or for index one, and
     * reads nothing otherwise — which is what indexing past a single-valued store already did.
     */
    static TypedValue framed(final EngineVars engine,
                             final int group,
                             final MatchIndex matchIndex,
                             final int matchCount,
                             final VarRegistry vars) {
        if (group != 0) {
            return null;
        }
        final TypedValue value = vars.frames().value(engine);
        return value == null || !atIndexOne(matchIndex, matchCount, vars) ? null : value;
    }

    /**
     * Whether an index rule picks index one, the only index a framed variable ever had.
     *
     * <p>The four forms are tested in {@link #matchIndex}'s order, not in a tidier one: nothing
     * makes them mutually exclusive, so which is asked first is behaviour.
     */
    private static boolean atIndexOne(final MatchIndex matchIndex,
                                      final int matchCount,
                                      final VarRegistry vars) {
        if (matchIndex == null) {
            return true;
        }
        if (matchIndex.varRef() != null) {
            return indexFrom(matchIndex.varRef(), vars) == 1;
        }
        if (matchIndex.isLast()) {
            // The store held one value, so its last index was one.
            return true;
        }
        if (matchIndex.isOffset()) {
            return matchCount + matchIndex.index() == 1;
        }
        return matchIndex.index() == 1;
    }

    /**
     * Which of a variable's values a reference means, or null for "the most recent".
     *
     * <p>Four ways to say it, and they exist because four things genuinely need saying: read the
     * index out of another variable at runtime, take the last one there is, count relative to the
     * match being processed, or name it outright. The relative form is the one that makes a
     * header row line up with a data row — the engine's own {@code __match_count} threads the
     * column number through.
     */
    private static Integer matchIndex(final MatchIndex matchIndex,
                                      final Store store,
                                      final int matchCount,
                                      final VarRegistry vars) {
        if (matchIndex == null) {
            return null;
        }
        if (matchIndex.varRef() != null) {
            return indexFrom(matchIndex.varRef(), vars);
        }
        if (matchIndex.isLast()) {
            final int last = store.lastIndex();
            return last < 0 ? matchCount : last;
        }
        if (matchIndex.isOffset()) {
            return matchCount + matchIndex.index();
        }
        return matchIndex.index();
    }

    /**
     * The whole number a name currently holds, for the index rule that reads one at run time.
     *
     * <p>The name may be the engine's — {@code $var[$__match_count]} is how a heading captured
     * on one match is read beside a value captured on another — so this asks the frames before
     * the registry. Absent or non-numeric reads as <b>the first match</b>: the conservative
     * answer, since match indexes count from one.
     */
    private static int indexFrom(final String varRef, final VarRegistry vars) {
        final EngineVars engine = EngineVars.byName(varRef);
        final TypedValue value;
        if (engine != null && engine.framed()) {
            value = vars.frames().value(engine);
        } else {
            final List<Store> stores = vars.get(varRef);
            value = stores == null || stores.isEmpty() ? null : stores.getFirst().latest();
        }
        if (value != null) {
            final Double number = value.asNumber();
            if (number != null && number >= 0) {
                return (int) (double) number;
            }
        }
        return 1;
    }
}
