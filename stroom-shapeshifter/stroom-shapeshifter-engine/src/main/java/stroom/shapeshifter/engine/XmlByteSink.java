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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * An {@link OutputSink} that serialises structure the way Stroom's own serialiser does.
 *
 * <p>Design 21 phase 2a. The structural calls — {@link #startElement}, {@link #namespace},
 * {@link #startAttribute}/{@link #endAttribute}, {@link #endElement} — tell the sink where it is,
 * and {@link #write} means what design 20 §4B says it means by the innermost open container: raw
 * bytes at document level, content inside an element, the value inside an attribute. Nothing
 * else in the engine changes meaning; a configuration that never opens a container gets the
 * byte-transparent sink it always had.
 *
 * <p>The bytes are Saxon's, because Stroom's DS3 goldens are Saxon's (D41, E35): XML declaration
 * left to the caller as document-level text; indent three per level; an element with element
 * children closes on its own line, one with text closes inline, one with nothing self-closes;
 * and the start tag wraps by Saxon's {@code XMLIndenter} rule, read from its bytecode — sum
 * {@code 9 + uri} for a default namespace declaration, {@code prefix + 10 + uri} for a prefixed
 * one, {@code name + value + 8} for an attribute ({@code + 9} with a prefix, raw value lengths),
 * and if the sum exceeds 80 every attribute after the first goes on its own line, aligned under
 * the first. Entities are Saxon's too: {@code &#34;} for a quote, {@code &#xA;}, {@code &#xD;}
 * and {@code &#x9;} for the whitespace controls in attribute values, {@code &#xD;} in content.
 *
 * <p>The ordering rule of design 20 §4B is enforced here for what the compiler cannot see: a
 * namespace or attribute arriving after content is refused by name.
 */
public final class XmlByteSink implements OutputSink {

    private static final int LINE_LENGTH = 80;
    private static final int INDENT = 3;

    private final OutputStream out;
    private final Deque<Element> open = new ArrayDeque<>();
    private long position;
    private Attribute attribute;
    /** The tail of the last content write that did not finish a UTF-8 sequence; see {@link #write}. */
    private byte[] carry = new byte[0];

    public XmlByteSink(final OutputStream out) {
        this.out = out;
    }

    // -----------------------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------------------

    /** Open an element. Its start tag is written when its first content arrives, or it closes. */
    public void startElement(final String qName) {
        checkNoAttributeOpen("startElement " + qName);
        flushCarry();
        final Element parent = open.peek();
        if (parent != null) {
            ensureStarted(parent);
            parent.hasElementChildren = true;
        }
        open.push(new Element(qName, open.size() + 1));
    }

    /** Declare a prefix binding on the open element; the empty prefix is the default namespace. */
    public void namespace(final String prefix, final String uri) {
        final Element element = current("namespace " + prefix);
        if (element.started) {
            throw new IllegalStateException(
                    "namespace '" + prefix + "' arrived after the content of <" + element.qName + "> had begun");
        }
        element.declarations.add(new String[]{prefix, uri});
    }

    /** Begin an attribute; every write until {@link #endAttribute} is its value. */
    public void startAttribute(final String qName) {
        final Element element = current("attribute " + qName);
        checkNoAttributeOpen("startAttribute " + qName);
        if (element.started) {
            throw new IllegalStateException(
                    "attribute '" + qName + "' arrived after the content of <" + element.qName + "> had begun");
        }
        attribute = new Attribute(qName);
    }

    public void endAttribute() {
        if (attribute == null) {
            throw new IllegalStateException("endAttribute with no attribute open");
        }
        open.peek().attributes.add(new String[]{attribute.qName, attribute.value.toString(StandardCharsets.UTF_8)});
        attribute = null;
    }

    public void endElement() {
        final Element element = current("endElement");
        checkNoAttributeOpen("endElement " + element.qName);
        flushCarry();
        if (!element.started) {
            emitStartTag(element, true);
        } else if (element.hasElementChildren && !element.hasText) {
            emit("\n" + spaces((element.level - 1) * INDENT) + "</" + element.qName + ">");
        } else {
            emit("</" + element.qName + ">");
        }
        open.pop();
        if (open.isEmpty()) {
            // Saxon ends an indented document with a newline after the root's close.
            emit("\n");
        }
    }

    // -----------------------------------------------------------------------------------
    // OutputSink
    // -----------------------------------------------------------------------------------

    @Override
    public void write(final byte[] data, final int offset, final int length) {
        if (attribute != null) {
            attribute.value.write(data, offset, length);
            return;
        }
        final Element element = open.peek();
        if (element == null) {
            raw(data, offset, length);
            return;
        }
        // Content is escaped as characters, and a write may end mid-character: the interface
        // promises bytes, not whole strings. Whatever does not complete a UTF-8 sequence waits
        // for the next write, or for the structural call that ends the content.
        final byte[] bytes = new byte[carry.length + length];
        System.arraycopy(carry, 0, bytes, 0, carry.length);
        System.arraycopy(data, offset, bytes, carry.length, length);
        final int complete = bytes.length - incompleteTail(bytes);
        carry = java.util.Arrays.copyOfRange(bytes, complete, bytes.length);
        content(element, new String(bytes, 0, complete, StandardCharsets.UTF_8));
    }

    private void content(final Element element, final String text) {
        if (text.isBlank()) {
            // Whitespace between elements is the indenter's to write, not the author's.
            return;
        }
        ensureStarted(element);
        element.hasText = true;
        emit(escapeContent(text));
    }

    private void flushCarry() {
        if (carry.length > 0) {
            final byte[] bytes = carry;
            carry = new byte[0];
            final Element element = open.peek();
            if (element != null) {
                content(element, new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }

    /** How many trailing bytes begin a UTF-8 sequence the array does not finish. */
    private static int incompleteTail(final byte[] bytes) {
        for (int back = 1; back <= 3 && back <= bytes.length; back++) {
            final int b = bytes[bytes.length - back] & 0xFF;
            if ((b & 0xC0) != 0x80) {
                // A lead byte (or ASCII): the sequence it starts needs this many bytes in total.
                final int needed = b < 0x80 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
                return needed > back ? back : 0;
            }
        }
        return 0;
    }

    @Override
    public long position() {
        return position;
    }

    // -----------------------------------------------------------------------------------
    // The start tag
    // -----------------------------------------------------------------------------------

    private void ensureStarted(final Element element) {
        if (!element.started) {
            emitStartTag(element, false);
        }
    }

    private void emitStartTag(final Element element, final boolean selfClose) {
        final StringBuilder tag = new StringBuilder();
        if (element.level > 1) {
            tag.append('\n').append(spaces((element.level - 1) * INDENT));
        }
        tag.append('<').append(element.qName);

        final boolean wrap = saxonAttributeLength(element) > LINE_LENGTH;
        final String continuation = "\n" + spaces((element.level - 1) * INDENT + element.qName.length() + 2);
        int written = 0;
        for (final String[] declaration : element.declarations) {
            tag.append(written++ == 0 || !wrap ? " " : continuation)
                    .append(declaration[0].isEmpty() ? "xmlns" : "xmlns:" + declaration[0])
                    .append("=\"").append(escapeAttribute(declaration[1])).append('"');
        }
        for (final String[] attribute : element.attributes) {
            tag.append(written++ == 0 || !wrap ? " " : continuation)
                    .append(attribute[0]).append("=\"").append(escapeAttribute(attribute[1])).append('"');
        }
        tag.append(selfClose ? "/>" : ">");
        emit(tag.toString());
        element.started = true;
    }

    /** Saxon's {@code XMLIndenter.startContent} sum, on raw lengths, which decides wrapping. */
    private static int saxonAttributeLength(final Element element) {
        int total = 0;
        for (final String[] declaration : element.declarations) {
            total += declaration[0].isEmpty()
                    ? 9 + declaration[1].length()
                    : declaration[0].length() + 10 + declaration[1].length();
        }
        for (final String[] attribute : element.attributes) {
            final int colon = attribute[0].indexOf(':');
            final int prefix = colon < 0 ? 0 : colon;
            final int local = colon < 0 ? attribute[0].length() : attribute[0].length() - colon - 1;
            total += local + attribute[1].length() + 4 + (prefix == 0 ? 4 : prefix + 5);
        }
        return total;
    }

    // -----------------------------------------------------------------------------------
    // Escaping — Saxon's forms
    // -----------------------------------------------------------------------------------

    static String escapeAttribute(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&#34;");
                case '\n' -> out.append("&#xA;");
                case '\r' -> out.append("&#xD;");
                case '\t' -> out.append("&#x9;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String escapeContent(final String text) {
        final StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#xD;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // -----------------------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------------------

    private Element current(final String call) {
        final Element element = open.peek();
        if (element == null) {
            throw new IllegalStateException(call + " with no element open");
        }
        return element;
    }

    private void checkNoAttributeOpen(final String call) {
        if (attribute != null) {
            throw new IllegalStateException(call + " while attribute '" + attribute.qName + "' is open");
        }
    }

    private void emit(final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        raw(bytes, 0, bytes.length);
    }

    private void raw(final byte[] data, final int offset, final int length) {
        try {
            out.write(data, offset, length);
            position += length;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String spaces(final int n) {
        return " ".repeat(n);
    }

    private static final class Element {

        private final String qName;
        private final int level;
        private final List<String[]> declarations = new ArrayList<>();
        private final List<String[]> attributes = new ArrayList<>();
        private boolean started;
        private boolean hasElementChildren;
        private boolean hasText;

        private Element(final String qName, final int level) {
            this.qName = qName;
            this.level = level;
        }
    }

    private static final class Attribute {

        private final String qName;
        private final ByteArrayOutputStream value = new ByteArrayOutputStream();

        private Attribute(final String qName) {
            this.qName = qName;
        }
    }
}
