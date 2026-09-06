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

import org.xml.sax.ContentHandler;

/**
 * A text configuration's output as SAX {@code characters} events, one per write, as the write
 * happens (design 24, D42).
 *
 * <p>In Stroom's pipeline model nothing carries bytes between a parser and an appender: an
 * appender is a destination, and only a writer — a SAX consumer — holds one. So a text
 * configuration streams to a byte sink by emitting characters, and the pipeline's writer is the
 * sink's mouth. This sink is that emission. It carries no structure: a structural call is a
 * {@link StructureException}, which cannot happen through the engine, since a configuration
 * with structure is run into {@link SaxEventSink} instead.
 *
 * <p>The bytes are the engine's internal UTF-8 (E3), decoded losslessly; a multi-byte character
 * split between two writes is held back and delivered whole with the next. The document is
 * started with the first write and ended by {@link #end()}, which the caller owes it, because
 * a text run has no root element to say when it is over.
 */
public final class CharacterSink implements OutputSink {

    private final ContentHandler handler;
    private final Utf8.Carry carry = new Utf8.Carry();
    private final SaxEvents events = new SaxEvents();
    private boolean started;
    private boolean ended;

    public CharacterSink(final ContentHandler handler) {
        this.handler = handler;
    }

    @Override
    public void write(final byte[] data, final int offset, final int length) {
        if (ended) {
            throw new StructureException("write after the document has ended");
        }
        if (length == 0) {
            return;
        }
        final String text = carry.take(data, offset, length);
        if (!text.isEmpty()) {
            characters(text);
        }
    }

    /**
     * The run is over: whatever is held back goes out as it is — an incomplete sequence at the
     * very end is the configuration's, and decodes to a replacement character as it would in a
     * file — and the document ends. A document with no output still begins and ends, so a
     * consumer sees one document per input, as it does for structure.
     */
    public void end() {
        if (ended) {
            return;
        }
        final String rest = carry.flush();
        if (rest != null) {
            characters(rest);
        }
        ensureStarted();
        events.make(handler::endDocument);
        ended = true;
    }

    @Override
    public long position() {
        return events.count();
    }

    @Override
    public Unit unit() {
        return Unit.EVENTS;
    }

    private void characters(final String text) {
        ensureStarted();
        final char[] chars = text.toCharArray();
        events.make(() -> handler.characters(chars, 0, chars.length));
    }

    private void ensureStarted() {
        if (!started) {
            started = true;
            events.make(handler::startDocument);
        }
    }
}
