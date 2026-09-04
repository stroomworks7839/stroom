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

package stroom.shapeshifter.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A byte pipe between two threads with a fixed capacity: the writer blocks when it is full, the
 * reader blocks when it is empty, and either end can be failed so the other stops waiting.
 *
 * <p>Design 22 §2. The pipeline's thread writes the image into it; the engine's worker reads.
 * The capacity is the back-pressure. Not {@code PipedInputStream}, whose liveness is judged by
 * the writing thread being alive rather than by anyone saying so — a worker that has failed
 * and a pipeline thread that has moved on must each be able to tell the other.
 */
final class BoundedPipe {

    private final byte[] ring;
    private int head;
    private int size;
    private boolean writerClosed;
    private Throwable failure;

    BoundedPipe(final int capacity) {
        this.ring = new byte[Math.max(1, capacity)];
    }

    /** Fail both ends: the reader sees the failure, the writer stops blocking. */
    synchronized void fail(final Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
        notifyAll();
    }

    /** No more bytes will be written; the reader drains what is there and then sees the end. */
    synchronized void closeWriter() {
        writerClosed = true;
        notifyAll();
    }

    OutputStream writer() {
        return new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(final byte[] data, final int offset, final int length) throws IOException {
                int written = 0;
                while (written < length) {
                    written += put(data, offset + written, length - written);
                }
            }
        };
    }

    InputStream reader() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                final byte[] one = new byte[1];
                final int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(final byte[] into, final int offset, final int length) throws IOException {
                return take(into, offset, length);
            }
        };
    }

    private synchronized int put(final byte[] data, final int offset, final int length) throws IOException {
        while (size == ring.length && failure == null) {
            await();
        }
        checkFailure();
        final int n = Math.min(length, ring.length - size);
        final int tail = (head + size) % ring.length;
        final int first = Math.min(n, ring.length - tail);
        System.arraycopy(data, offset, ring, tail, first);
        System.arraycopy(data, offset + first, ring, 0, n - first);
        size += n;
        notifyAll();
        return n;
    }

    private synchronized int take(final byte[] into, final int offset, final int length) throws IOException {
        while (size == 0 && !writerClosed && failure == null) {
            await();
        }
        checkFailure();
        if (size == 0) {
            return -1;
        }
        final int n = Math.min(length, size);
        final int first = Math.min(n, ring.length - head);
        System.arraycopy(ring, head, into, offset, first);
        System.arraycopy(ring, 0, into, offset + first, n - first);
        head = (head + n) % ring.length;
        size -= n;
        notifyAll();
        return n;
    }

    private void await() throws IOException {
        try {
            wait();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting on the pipe", e);
        }
    }

    private void checkFailure() throws IOException {
        if (failure != null) {
            throw new IOException("The other end of the pipe failed: " + failure.getMessage(), failure);
        }
    }
}
