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

import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.config.RefExpression;
import stroom.shapeshifter.engine.config.RefExpression.MatchIndex;
import stroom.shapeshifter.engine.config.RefExpression.RefPart;
import stroom.shapeshifter.engine.text.Encoding;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Resolving the configuration's expressions against what has been captured.
 *
 * <p>Two ways in, and the difference is not cosmetic. {@link #write} sends the parts straight to
 * the sink, which is what an output instruction wants and costs nothing in between.
 * {@link #resolve} builds the value, which is what a condition or a nested match needs.
 *
 * <p><b>Empty is absent.</b> A part that resolves to nothing writes nothing, and an expression
 * whose parts all resolve to nothing has no value at all rather than an empty one. That is what
 * lets a configuration ask whether a field exists.
 *
 * <p>This is also one of the two places an encoding matters. A captured value is a slice of the
 * input, so when the input is not UTF-8 it has to be converted on the way out — and when it is,
 * which is the overwhelming majority of the time, the bytes go straight through.
 */
public final class Refs {

    private Refs() {
    }

    /**
     * Write an expression to the sink.
     *
     * @return true if anything was written
     */
    public static boolean write(final RefExpression expression,
                                final MatchResult match,
                                final int matchCount,
                                final VarRegistry vars,
                                final Encoding encoding,
                                final OutputSink sink) {
        if (expression == null || expression.parts().isEmpty()) {
            return false;
        }
        boolean wrote = false;
        for (final RefPart part : expression.parts()) {
            switch (part) {
                case RefPart.Text text -> {
                    if (!text.value().isEmpty()) {
                        sink.write(text.value());
                        wrote = true;
                    }
                }
                case RefPart.Capture capture -> {
                    final TypedValue value = lookup(capture, match, matchCount, vars);
                    if (value != null && !value.isEmpty()) {
                        // Only a slice of the current match needs converting: a stored value
                        // was normalised to UTF-8 when it was captured (E3).
                        sink.write(capture.varId() == null ? bytes(value, encoding) : value.asBytes());
                        wrote = true;
                    }
                }
            }
        }
        return wrote;
    }

    /** The value of an expression as bytes, or null if it resolves to nothing. */
    public static byte[] resolve(final RefExpression expression,
                                 final MatchResult match,
                                 final int matchCount,
                                 final VarRegistry vars,
                                 final Encoding encoding) {
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
                    yield value == null || value.isEmpty()
                            ? null
                            : capture.varId() == null ? bytes(value, encoding) : value.asBytes();
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
                    yield value == null
                            ? null
                            : capture.varId() == null ? bytes(value, encoding) : value.asBytes();
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
                                     final VarRegistry vars,
                                     final Encoding encoding) {
        final byte[] bytes = resolve(expression, match, matchCount, vars, encoding);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * A value as UTF-8 bytes, ready to write.
     *
     * <p>Numbers are already ASCII whatever the input was. Captured bytes are in the input's
     * encoding and need converting unless that is already UTF-8-compatible — which is the
     * common case and costs nothing to check.
     */
    static byte[] bytes(final TypedValue value, final Encoding encoding) {
        if (encoding.isUtf8Compatible() || !(value instanceof TypedValue.Bytes captured)) {
            return value.asBytes();
        }
        return encoding.decode(captured.value()).getBytes(StandardCharsets.UTF_8);
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
        final List<Store> stores = vars.get(varId);
        if (stores == null || group >= stores.size()) {
            return null;
        }
        final Store store = stores.get(group);
        final Integer index = index(matchIndex, store, matchCount, vars);
        return index == null ? store.latest() : store.get(index);
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
    private static Integer index(final MatchIndex matchIndex,
                                 final Store store,
                                 final int matchCount,
                                 final VarRegistry vars) {
        if (matchIndex == null) {
            return null;
        }
        if (matchIndex.varRef() != null) {
            final List<Store> stores = vars.get(matchIndex.varRef());
            if (stores != null && !stores.isEmpty()) {
                final TypedValue value = stores.getFirst().latest();
                if (value != null) {
                    final Double number = value.asNumber();
                    if (number != null && number >= 0) {
                        return (int) (double) number;
                    }
                }
            }
            // The first match — the conservative read when the index variable is absent or
            // holds nothing numeric. Match indexes count from one, so 1 is the earliest value
            // a store can hold.
            return 1;
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
}
