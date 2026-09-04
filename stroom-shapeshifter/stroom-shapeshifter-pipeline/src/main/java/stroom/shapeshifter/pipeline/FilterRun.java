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

import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * One document through the filter: the image written into a bounded pipe on the caller's
 * thread, the engine reading it on a worker, and the result forwarded on the caller's thread
 * once the worker has finished (design 22 §2, I2).
 *
 * <p>Separated from the element so that the mechanics — back-pressure, the join, failure in
 * either direction — can be tested without a pipeline.
 */
final class FilterRun {

    private final ShapeshifterReader reader;
    private final BoundedPipe pipe;
    private final EventImage image;
    private final Thread worker;
    private volatile ShapeshifterReader.Run result;
    private volatile Throwable failure;

    FilterRun(final ShapeshifterReader reader, final int pipeCapacity) {
        this.reader = reader;
        this.pipe = new BoundedPipe(pipeCapacity);
        this.image = new EventImage(new Failing(pipe.writer()));
        this.worker = new Thread(this::run, "shapeshifter-filter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Where the document's events go. */
    ContentHandler input() {
        return image;
    }

    private void run() {
        try {
            result = reader.runStreamed(pipe.reader());
        } catch (final Throwable t) {
            failure = t;
            // The writer may be blocked on a full pipe; it must find out.
            pipe.fail(t);
        }
    }

    /**
     * The document has ended: close the image, wait for the engine, then forward its result
     * through the reader to the handlers given — on this thread, which is the pipeline's.
     */
    void finish(final ContentHandler downstream, final ErrorHandler errors) throws IOException, SAXException {
        pipe.closeWriter();
        try {
            worker.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            pipe.fail(e);
            throw new IOException("Interrupted waiting for the engine", e);
        }
        if (failure != null) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("The engine failed", failure);
        }
        reader.setContentHandler(downstream);
        reader.setErrorHandler(errors);
        reader.forward(result);
    }

    /** Abandon the document: fail the pipe so the worker's read ends and the worker with it. */
    void abandon(final Throwable cause) {
        pipe.fail(cause);
    }

    /** Whether the worker has finished, waiting up to the time given. */
    boolean workerDone(final long millis) throws InterruptedException {
        worker.join(millis);
        return !worker.isAlive();
    }

    /** An output stream whose failures are the pipe's, surfaced where the image is written. */
    private static final class Failing extends OutputStream {

        private final OutputStream target;

        private Failing(final OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(final int b) {
            try {
                target.write(b);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void write(final byte[] data, final int offset, final int length) {
            try {
                target.write(data, offset, length);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
