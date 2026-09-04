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

import stroom.shapeshifter.engine.Message;
import stroom.shapeshifter.engine.SaxEventSink;
import stroom.shapeshifter.engine.Shapeshifter;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One document through the filter (design 22 §2): the image written into a bounded pipe on the
 * caller's thread, the engine reading it on a worker, and what the engine produces delivered
 * downstream on the caller's thread.
 *
 * <p>Two paths, chosen by the configuration. A text configuration's output is bytes, complete
 * only when the run is: it is forwarded through the reader — parsed, located — at
 * {@link #finish}. A structured configuration runs straight into an event sink (phase 2): the
 * worker enqueues each event, located live, and the caller's thread drains the queue between
 * the input events it pushes — and while it waits on a full input pipe, so neither thread can
 * hold the other — which is what makes both ends stream.
 *
 * <p>Separated from the element so that the mechanics — back-pressure both ways, the join,
 * failure in either direction — can be tested without a pipeline.
 */
final class FilterRun {

    /** How far the engine may run ahead of delivery, in events. */
    static final int EVENT_QUEUE_CAPACITY = 1024;
    private static final long WAIT_MILLIS = 20;

    private final ShapeshifterReader reader;
    private final BoundedPipe pipe;
    private final EventImage image;
    private final ContentHandler downstream;
    private final ErrorHandler errors;
    private final boolean structured;
    private final BlockingQueue<Event> events;
    private final ShapeshifterReader.LiveLocator locator = new ShapeshifterReader.LiveLocator();
    private final Thread worker;
    private volatile ShapeshifterReader.Run result;
    private volatile List<Message> messages;
    private volatile Throwable failure;
    private volatile boolean abandoned;
    private boolean downstreamLocated;
    private int delivered;

    FilterRun(final ShapeshifterReader reader, final int pipeCapacity,
              final ContentHandler downstream, final ErrorHandler errors) {
        this(reader, pipeCapacity, downstream, errors, false);
    }

    FilterRun(final ShapeshifterReader reader, final int pipeCapacity,
              final ContentHandler downstream, final ErrorHandler errors, final boolean preserveWhitespace) {
        this.reader = reader;
        this.pipe = new BoundedPipe(pipeCapacity);
        this.image = new EventImage(new Draining(pipe), preserveWhitespace);
        this.downstream = downstream;
        this.errors = errors;
        this.structured = reader.compiled().structured();
        this.events = structured ? new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY) : null;
        this.worker = new Thread(this::run, "shapeshifter-filter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Where the document's events go. */
    ContentHandler input() {
        return image;
    }

    /** How many events have been delivered downstream so far. */
    int delivered() {
        return delivered;
    }

    private void run() {
        try {
            if (structured) {
                final InputLocations locations = new InputLocations();
                final InputLocations.LineIndex lines = new InputLocations.LineIndex(pipe.reader());
                locations.bound(lines);
                messages = Shapeshifter.run(reader.compiled(), lines,
                        new SaxEventSink(new Enqueuer(locations, lines)), locations);
            } else {
                result = reader.runStreamed(pipe.reader());
            }
        } catch (final Throwable t) {
            failure = t;
            // The writer may be blocked on a full pipe; it must find out.
            pipe.fail(t);
        } finally {
            // The engine may finish before the document does — a FATAL ends its run with the
            // rest of the image still to come. The writer must not wait on a reader that has
            // gone (design 23 phase 2).
            pipe.closeReader();
        }
    }

    /**
     * The document has ended: close the image, deliver what the engine produces until it is done,
     * then report its messages — all on this thread, which is the pipeline's.
     */
    void finish() throws IOException, SAXException {
        pipe.closeWriter();
        try {
            try {
                while (worker.isAlive()) {
                    drain();
                    worker.join(WAIT_MILLIS);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for the engine", e);
            }
            drain();
        } catch (final RuntimeException | IOException | SAXException e) {
            // Whatever stopped the delivery, the worker must not be left waiting on a queue
            // nobody will drain again (design 22 phase 2 audit).
            abandon(e);
            throw e;
        }
        if (failure != null) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("The engine failed", failure);
        }
        reader.setContentHandler(downstream);
        reader.setErrorHandler(errors);
        if (structured) {
            reader.reportAll(messages);
        } else {
            reader.forward(result);
        }
    }

    /**
     * Abandon the document: fail the pipe so the worker's read ends, and mark the run so a
     * worker waiting on the output queue — which nobody will drain again — stops waiting too.
     */
    void abandon(final Throwable cause) {
        abandoned = true;
        pipe.fail(cause);
    }

    /** Whether the worker has finished, waiting up to the time given. */
    boolean workerDone(final long millis) throws InterruptedException {
        worker.join(millis);
        return !worker.isAlive();
    }

    // -----------------------------------------------------------------------------------
    // Delivery, on the caller's thread
    // -----------------------------------------------------------------------------------

    /** Deliver every event the worker has queued so far. */
    private void drain() throws SAXException {
        if (events == null) {
            return;
        }
        Event event;
        while ((event = events.poll()) != null) {
            if (!downstreamLocated) {
                downstream.setDocumentLocator(locator);
                downstreamLocated = true;
            }
            locator.at(event.position());
            event.deliver(downstream);
            delivered++;
        }
    }

    /** The image's output stream: writes into the pipe, delivering output while the pipe is full. */
    private final class Draining extends OutputStream {

        private final BoundedPipe pipe;

        private Draining(final BoundedPipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public void write(final int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(final byte[] data, final int offset, final int length) {
            try {
                int written = 0;
                while (written < length) {
                    final int n = pipe.tryPut(data, offset + written, length - written);
                    if (n == 0) {
                        drain();
                        pipe.awaitSpace(WAIT_MILLIS);
                    }
                    written += n;
                }
                drain();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            } catch (final SAXException e) {
                throw new IllegalStateException("Downstream refused an event: " + e.getMessage(), e);
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // The worker's side: events, located live, queued
    // -----------------------------------------------------------------------------------

    /** An event as the worker produced it, with the input position behind it. */
    private record Event(Kind kind, String uri, String localName, String qName, Attributes atts,
                         char[] chars, String prefix, InputLocations.Position position) {

        enum Kind { START_DOCUMENT, END_DOCUMENT, START_PREFIX, END_PREFIX, START_ELEMENT, END_ELEMENT, CHARACTERS }

        void deliver(final ContentHandler handler) throws SAXException {
            switch (kind) {
                case START_DOCUMENT -> handler.startDocument();
                case END_DOCUMENT -> handler.endDocument();
                case START_PREFIX -> handler.startPrefixMapping(prefix, uri);
                case END_PREFIX -> handler.endPrefixMapping(prefix);
                case START_ELEMENT -> handler.startElement(uri, localName, qName, atts);
                case END_ELEMENT -> handler.endElement(uri, localName, qName);
                case CHARACTERS -> handler.characters(chars, 0, chars.length);
            }
        }
    }

    private final class Enqueuer implements ContentHandler {

        private final InputLocations locations;
        private final InputLocations.Lines lines;

        private Enqueuer(final InputLocations locations, final InputLocations.Lines lines) {
            this.locations = locations;
            this.lines = lines;
        }

        private void enqueue(final Event event) {
            try {
                // Blocks when the caller's thread has not delivered: back-pressure on the output.
                // An abandoned run has no deliverer left, so the wait must end.
                while (!events.offer(event, WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (abandoned) {
                        throw new IllegalStateException("The document was abandoned while an event waited");
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while queueing an event", e);
            }
        }

        private InputLocations.Position here() {
            return lines.locate(locations.currentInputOffset());
        }

        @Override
        public void setDocumentLocator(final Locator locator) {
        }

        @Override
        public void startDocument() {
            enqueue(new Event(Event.Kind.START_DOCUMENT, null, null, null, null, null, null, here()));
        }

        @Override
        public void endDocument() {
            enqueue(new Event(Event.Kind.END_DOCUMENT, null, null, null, null, null, null, here()));
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) {
            enqueue(new Event(Event.Kind.START_PREFIX, uri, null, null, null, null, prefix, here()));
        }

        @Override
        public void endPrefixMapping(final String prefix) {
            enqueue(new Event(Event.Kind.END_PREFIX, null, null, null, null, null, prefix, here()));
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes atts) {
            enqueue(new Event(Event.Kind.START_ELEMENT, uri, localName, qName, new AttributesImpl(atts),
                    null, null, here()));
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            enqueue(new Event(Event.Kind.END_ELEMENT, uri, localName, qName, null, null, null, here()));
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            final char[] copy = new char[length];
            System.arraycopy(ch, start, copy, 0, length);
            enqueue(new Event(Event.Kind.CHARACTERS, null, null, null, null, copy, null, here()));
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length) {
        }

        @Override
        public void processingInstruction(final String target, final String data) {
        }

        @Override
        public void skippedEntity(final String name) {
        }
    }
}
