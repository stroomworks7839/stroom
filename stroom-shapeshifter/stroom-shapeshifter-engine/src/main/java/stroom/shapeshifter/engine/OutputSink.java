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

package stroom.shapeshifter.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Where a run's output goes.
 *
 * <p>Every write the engine performs goes through this interface, and that is the whole point of
 * it existing while there is only one implementation. Shapeshifter's configurations currently
 * describe output as a byte stream that happens to be XML, and a Stroom pipeline element will
 * want something else — SAX events are the obvious candidate but not the only one, and that
 * choice is deliberately still open (D10). Funnelling the writes through one interface now costs
 * an indirection; retrofitting it later would mean revisiting every output instruction a second
 * time.
 *
 * <p>It is not a pretence that a byte sink and an event sink are the same thing. It is a single
 * place to stand when they turn out not to be.
 */
public interface OutputSink {

    /** Write bytes. */
    void write(byte[] data, int offset, int length);

    /** Write a whole array. */
    default void write(final byte[] data) {
        write(data, 0, data.length);
    }

    /** Write UTF-8 text. */
    default void write(final String text) {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * How many bytes have been written so far.
     *
     * <p>Used for attribution — which template produced which part of the output — rather than
     * for anything the engine needs to run.
     */
    long position();

    /** A sink that writes to a stream. */
    static OutputSink of(final OutputStream stream) {
        return new OutputSink() {
            private long position;

            @Override
            public void write(final byte[] data, final int offset, final int length) {
                try {
                    stream.write(data, offset, length);
                    position += length;
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public long position() {
                return position;
            }
        };
    }
}
