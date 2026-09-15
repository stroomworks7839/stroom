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
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.TypedValue;

/**
 * Where a body's writes go: the sink and the encoding it accepts, paired once per sink, where
 * it enters a body (design 25 §3). Every value a body writes is transcoded from its own tag to
 * that encoding here, so the record loop never asks the sink what it accepts — the question's
 * answer does not change for the life of a sink, and asking it per write was a dispatch on the
 * path every write takes. Structure goes to the sink itself, which owns the rule for it.
 *
 * @param sink     the sink
 * @param encoding what it accepts, read once
 */
record Output(OutputSink sink, Encoding encoding) {

    /** Pair a sink with what it declares. */
    static Output of(final OutputSink sink) {
        return new Output(sink, sink.encoding());
    }

    /**
     * Write a value in the sink's encoding — the one place transcoding is decided, and the
     * value decides it for itself ({@link TypedValue#writeTo}): bytes go to a UTF-8 sink as
     * their UTF-8 range, uncopied (design 37 §5); everything else asks for its bytes in the
     * sink's encoding, as before.
     *
     * <p>Kept to one call on purpose. A hand-written type test here read 67 bytes and stopped
     * inlining at nine of its eleven sites (2026-09-15, phase 3b's control); this is 11 and
     * inlines everywhere, as the 20-byte form it replaced did.
     */
    void write(final TypedValue value) {
        value.writeTo(sink, encoding);
    }
}
