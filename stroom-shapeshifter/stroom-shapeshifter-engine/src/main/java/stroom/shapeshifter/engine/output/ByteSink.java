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

package stroom.shapeshifter.engine.output;

import stroom.shapeshifter.engine.OutputSink;
import stroom.shapeshifter.engine.text.Encoding;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * A sink for a target that is not UTF-8 (design 25 §4): it declares its encoding, writes what
 * it is given and carries no structure. Every value written through it is transcoded from the
 * value's own encoding to the declared one at the write, so a {@code raw} capture written to a
 * {@code raw} sink is the bytes it matched. An element name has no bytes in {@code raw}, which
 * is why the structural calls keep the interface's refusals.
 */
public final class ByteSink implements OutputSink {

    private final OutputStream out;
    private final Encoding encoding;
    private long position;

    public ByteSink(final OutputStream out, final Encoding encoding) {
        this.out = out;
        this.encoding = encoding;
    }

    @Override
    public void write(final byte[] data, final int offset, final int length) {
        try {
            out.write(data, offset, length);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        position += length;
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public Encoding encoding() {
        return encoding;
    }
}
