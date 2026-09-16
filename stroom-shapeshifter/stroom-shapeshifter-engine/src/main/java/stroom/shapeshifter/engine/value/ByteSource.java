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

package stroom.shapeshifter.engine.value;

import stroom.shapeshifter.engine.text.Encoding;

import java.util.Arrays;

/**
 * What a match runs over, answering for a range of itself as a value (design 37 §5, phase 3c).
 *
 * <p>Bytes reach a match by two routes, and the source is how the match knows which without
 * being told. The input window presents bytes that <b>move</b> — the window refills and shifts
 * — so a group taken from it must be copied out before the window does. A value's bytes never
 * move, so a group taken from a match over a value is a {@link TypedValue.ByteSlice} of it.
 * The root copies once; every match below it slices what the root copied.
 *
 * <p>Two implementations and no more, on purpose: the call that makes a group value is on the
 * hottest path in the engine, and two receiver classes is what the JIT still inlines.
 */
public sealed interface ByteSource permits ByteSource.Copying, ByteSource.Slicing {

    /**
     * The value of a range of the bytes the match ran over. Positions are in that array — what
     * the matcher reports — and the encoding is the level's, as the match tagged its groups.
     */
    TypedValue slice(int from, int to, Encoding encoding);

    /** Bytes that move under the match — the input window, a chunk of a whole-buffer run, a working buffer. */
    record Copying(byte[] data) implements ByteSource {

        @Override
        public TypedValue slice(final int from, final int to, final Encoding encoding) {
            return TypedValue.of(Arrays.copyOfRange(data, from, to), encoding);
        }
    }

    /** Bytes that never move — a value's bytes as read, which is what a nested match runs over. */
    record Slicing(byte[] data) implements ByteSource {

        @Override
        public TypedValue slice(final int from, final int to, final Encoding encoding) {
            return TypedValue.slice(data, from, to, encoding);
        }
    }
}
