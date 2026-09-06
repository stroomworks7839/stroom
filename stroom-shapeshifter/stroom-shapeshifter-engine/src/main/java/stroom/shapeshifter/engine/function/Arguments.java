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

package stroom.shapeshifter.engine.function;

import stroom.shapeshifter.engine.value.TypedValue;

import java.util.List;

/**
 * A call's arguments, positional: the value at each position cast to the signature's kind,
 * or null where the reference resolved to nothing or the cast had no reading. A position is
 * {@link #miscast(int)} when something was there and the kind could not read it — what
 * Stroom's {@code getSafeString} warns about, left to the function to decide.
 *
 * <p>A {@link Kind#SEQUENCE} position holds every entry of the store its select named, in
 * order, under {@link #sequence(int)}; its {@link #value(int)} is null.
 */
public final class Arguments {

    private final List<TypedValue> values;
    private final List<TypedValue> raw;
    private final List<List<TypedValue>> sequences;

    /** Built by the engine for each call; a test may build one directly. */
    public Arguments(final List<TypedValue> values,
                     final List<TypedValue> raw,
                     final List<List<TypedValue>> sequences) {
        this.values = values;
        this.raw = raw;
        this.sequences = sequences;
    }

    /** How many positions the call wrote, absent ones included. */
    public int size() {
        return values.size();
    }

    /** The cast value at a position, or null: absent, beyond what was written, or unreadable as its kind. */
    public TypedValue value(final int index) {
        return index < values.size() ? values.get(index) : null;
    }

    /** The value before casting, or null when nothing was there. */
    public TypedValue raw(final int index) {
        return index < raw.size() ? raw.get(index) : null;
    }

    /** True when a value was present but had no reading of the position's kind. */
    public boolean miscast(final int index) {
        return raw(index) != null && value(index) == null && sequence(index) == null;
    }

    /** The entries of the store a {@link Kind#SEQUENCE} position named, or null for any other kind. */
    public List<TypedValue> sequence(final int index) {
        return index < sequences.size() ? sequences.get(index) : null;
    }

    public String string(final int index) {
        final TypedValue value = value(index);
        return value == null ? null : value.asString();
    }

    public Double number(final int index) {
        final TypedValue value = value(index);
        return value == null ? null : value.asNumber();
    }

    public Long integer(final int index) {
        final TypedValue value = value(index);
        return value == null ? null : value.asInteger();
    }

    public Boolean bool(final int index) {
        final TypedValue value = value(index);
        return value == null ? null : value.asBoolean();
    }

    public TypedValue.Instant date(final int index) {
        return value(index) instanceof TypedValue.Instant instant ? instant : null;
    }
}
