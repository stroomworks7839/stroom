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

import stroom.shapeshifter.engine.text.Encoding;

/**
 * Where a run's output goes.
 *
 * <p>Every write the engine performs goes through this interface. There are two kinds of call.
 * The <b>structural</b> ones — {@link #startElement}, {@link #namespace},
 * {@link #startAttribute}/{@link #endAttribute}, {@link #endElement} — say where the output is;
 * {@link #write} carries bytes, and what those bytes <i>mean</i> is decided by the innermost open
 * container (design 20 §4B, D40): raw at document level, content inside an element, the value
 * inside an attribute. The instructions that write — {@code text}, {@code value-of}, the
 * transforms — do not know which; the sink does. A configuration that never opens a container
 * therefore gets the byte-transparent stream it always had.
 *
 * <p>Four implementations in {@code output}, one per target: {@code XmlByteSink} serialises the
 * structure as Stroom's own serialiser would (D41), {@code SaxEventSink} forwards it as SAX
 * events, {@code CharacterSink} delivers a text configuration's writes as characters (D42), and
 * {@code ByteSink} writes a non-UTF-8 target's bytes as they are. A sink that cannot do
 * structure — a byte counter, a benchmark — keeps the defaults, which refuse it by name.
 *
 * <p>A sink declares the encoding it accepts ({@link #encoding()}, design 25 §4), and every
 * value written through it is transcoded from the value's own encoding to that at the write —
 * the identity for the UTF-8 sinks, which are all three that carry structure. There is no
 * factory here (design 27 ruling 8): a caller names the sink it wants, {@code new
 * XmlByteSink(out)} for UTF-8 and {@code new ByteSink(out, encoding)} for anything else.
 *
 * <p>Ordering is the one rule enforced here rather than by the compiler: a namespace or attribute
 * that arrives after an element's content has begun is a {@link StructureException}, because the
 * content may have come through {@code apply-templates} from a template the compiler never saw
 * beside the attribute.
 */
public interface OutputSink {

    /** Write bytes. */
    void write(byte[] data, int offset, int length);

    /** Write a whole array. */
    default void write(final byte[] data) {
        write(data, 0, data.length);
    }

    /** Write text, in the encoding this sink accepts. */
    default void write(final String text) {
        write(encoding().encode(text));
    }

    /**
     * The encoding this sink accepts (design 25 §4): what a value written here is transcoded
     * to from its own. UTF-8 unless a sink says otherwise, and every sink that carries
     * structure is UTF-8 — an element name has no bytes in {@code raw}.
     */
    default Encoding encoding() {
        return Encoding.UTF_8;
    }

    /**
     * How far the output has got, in the sink's own currency — {@link #unit()} says which. Used
     * for attribution — which template produced which part of the output — rather than for
     * anything the engine needs to run.
     */
    long position();

    /** The currency {@link #position()} counts in. Bytes unless a sink says otherwise. */
    default Unit unit() {
        return Unit.BYTES;
    }

    /**
     * What a position is (design 20 §5): a byte offset when the target is bytes, an event ordinal
     * when it is events. One {@link Instrument} contract, told which it is speaking.
     */
    enum Unit {
        BYTES,
        EVENTS
    }

    /**
     * Open an element. Its start is written when its first content arrives or it closes, so that
     * namespaces and attributes can still be added.
     *
     * @param name      the qualified name, prefix included if it has one
     * @param namespace the namespace URI the name is in, or null to take it from the prefix's
     *                  binding in scope; a URI the prefix is not already bound to is declared here
     */
    default void startElement(final String name, final String namespace) {
        startElement(name, namespace, false);
    }

    /**
     * Open an element that, if {@code omitIfEmpty}, leaves no trace when nothing arrives before it
     * closes — no declaration, attribute or content. Its parent's start is deferred with it, so a
     * parent whose every child was omitted is still empty when it closes.
     */
    default void startElement(final String name, final String namespace, final boolean omitIfEmpty) {
        throw new StructureException("This sink does not carry structure: element " + name);
    }

    /** Open an element in whatever namespace its prefix is bound to. */
    default void startElement(final String name) {
        startElement(name, null, false);
    }

    /** Declare a prefix binding on the open element; the empty prefix is the default namespace. */
    default void namespace(final String prefix, final String uri) {
        throw new StructureException("This sink does not carry structure: namespace " + prefix);
    }

    /** Begin an attribute on the open element; every write until {@link #endAttribute} is its value. */
    default void startAttribute(final String name) {
        startAttribute(name, false);
    }

    /** Begin an attribute that, if {@code omitIfEmpty}, is dropped when its value comes out empty. */
    default void startAttribute(final String name, final boolean omitIfEmpty) {
        throw new StructureException("This sink does not carry structure: attribute " + name);
    }

    /** Close the innermost open attribute. */
    default void endAttribute() {
        throw new StructureException("This sink does not carry structure: endAttribute");
    }

    /** Close the innermost open element. */
    default void endElement() {
        throw new StructureException("This sink does not carry structure: endElement");
    }

    /** The output's structure was misused — an attribute after content, a close with nothing open. */
    final class StructureException extends IllegalStateException {

        public StructureException(final String message) {
            super(message);
        }
    }
}
