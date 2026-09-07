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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * namespace or attribute arriving after content is refused by name. Prefixes are the author's:
 * an element whose namespace is given and whose prefix is not already bound to it declares the
 * binding itself (S3's element-declared form); one whose prefix is unbound is written as told,
 * since bytes cannot be wrong about a URI they do not carry — the event sink is where that is
 * refused.
 */
public final class XmlByteSink implements OutputSink {

    private static final int LINE_LENGTH = 80;
    private static final int INDENT = 3;

    /**
     * How the bytes are laid out. {@link #INDENTED} is Saxon's: children on their own lines at
     * three per level, long start tags wrapped, whitespace between elements the indenter's.
     * {@link #FAITHFUL} adds nothing and drops nothing: every character written is written,
     * no line is broken, which is the image a whitespace-significant document needs (design 22
     * phase 3, {@code preserveWhitespace}).
     */
    public enum Layout {
        INDENTED,
        FAITHFUL
    }

    private final OutputStream out;
    private final Layout layout;
    private final Deque<Element> open = new ArrayDeque<>();
    private long position;
    private Attribute attribute;
    /** The bytes between writes that did not finish a UTF-8 sequence; see {@link #write}. */
    private final Utf8.Carry carry = new Utf8.Carry();

    /** A sink writing Saxon's indented layout. */
    public XmlByteSink(final OutputStream out) {
        this(out, Layout.INDENTED);
    }

    /** A sink writing the given layout. */
    public XmlByteSink(final OutputStream out, final Layout layout) {
        this.out = out;
        this.layout = layout;
    }

    // -----------------------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------------------

    @Override
    public void startElement(final String qName, final String namespace, final boolean omitIfEmpty) {
        checkNoAttributeOpen("startElement " + qName);
        flushCarry();
        final Element parent = open.peek();
        // The parent is not started here: a child that turns out to be omitted must leave the
        // parent as empty as it found it. The child's own first emission starts the parent.
        final Element element = new Element(qName, open.size() + 1, parent, omitIfEmpty);
        open.push(element);
        if (namespace != null) {
            // Element-declared (S3): the prefix's binding in scope serves if it already says so,
            // otherwise the element declares it.
            final String prefix = QNames.prefixOf(qName);
            if (!namespace.equals(element.scope.get(prefix))) {
                declare(element, prefix, namespace);
            }
        }
    }

    @Override
    public void namespace(final String prefix, final String uri) {
        final Element element = current("namespace " + prefix);
        if (element.started) {
            throw new StructureException(
                    "namespace '" + prefix + "' arrived after the content of <" + element.qName + "> had begun");
        }
        declare(element, prefix, uri);
    }

    private static void declare(final Element element, final String prefix, final String uri) {
        element.declarations.add(new String[]{prefix, uri});
        element.scope.put(prefix, uri);
    }

    @Override
    public void startAttribute(final String qName, final boolean omitIfEmpty) {
        final Element element = current("attribute " + qName);
        checkNoAttributeOpen("startAttribute " + qName);
        if (element.started) {
            throw new StructureException(
                    "attribute '" + qName + "' arrived after the content of <" + element.qName + "> had begun");
        }
        attribute = new Attribute(qName, omitIfEmpty);
    }

    @Override
    public void endAttribute() {
        if (attribute == null) {
            throw new StructureException("endAttribute with no attribute open");
        }
        final String value = attribute.value.toString(StandardCharsets.UTF_8);
        if (!(attribute.omitIfEmpty && value.isEmpty())) {
            open.peek().attributes.add(new String[]{attribute.qName, value});
        }
        attribute = null;
    }

    @Override
    public void endElement() {
        final Element element = current("endElement");
        checkNoAttributeOpen("endElement " + element.qName);
        flushCarry();
        settleWhitespace(element);
        if (!element.started) {
            if (element.omitIfEmpty && element.declarations.isEmpty() && element.attributes.isEmpty()) {
                open.pop();
                return;
            }
            emitStartTag(element, true);
        } else if (indented() && element.hasElementChildren && !element.hasText) {
            emit("\n" + spaces((element.level - 1) * INDENT) + "</" + element.qName + ">");
        } else {
            emit("</" + element.qName + ">");
        }
        open.pop();
        if (open.isEmpty() && indented()) {
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
        // Content is escaped as characters; what a write leaves mid-character the carry holds.
        content(element, carry.take(data, offset, length));
    }

    private void content(final Element element, final String text) {
        if (text.isBlank() && indented()) {
            // Whitespace between elements is the indenter's to write, not the author's — but
            // whitespace inside text is the text's. Held until the next thing says which: text
            // keeps it, a child element or the close discards it. (Design 22 phase 1 audit: a
            // parser splits character data anywhere, and "a", " ", "b" is "a b".)
            element.pendingWhitespace.append(text);
            return;
        }
        ensureStarted(element);
        element.hasText = true;
        if (!element.pendingWhitespace.isEmpty()) {
            emit(escapeContent(element.pendingWhitespace.toString()));
            element.pendingWhitespace.setLength(0);
        }
        emit(escapeContent(text));
    }

    /**
     * Pending whitespace at a boundary — a child starting, the element closing. In an element that
     * has text it is text, and is written (a parser splits one text node at line ends, so the last
     * line of a {@code <pre>} arrives alone); in element-only content it is the indenter's, and goes.
     */
    private void settleWhitespace(final Element element) {
        if (element.pendingWhitespace.isEmpty()) {
            return;
        }
        if (element.hasText) {
            emit(escapeContent(element.pendingWhitespace.toString()));
        }
        element.pendingWhitespace.setLength(0);
    }

    private void flushCarry() {
        final String rest = carry.flush();
        if (rest != null) {
            final Element element = open.peek();
            if (element != null) {
                content(element, rest);
            }
        }
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
        // Indentation is for element-only content: once a parent has text, its children sit in
        // that text as Saxon leaves them (mixed content), and in the faithful layout nothing is
        // ever added.
        final boolean indentThis = indented() && element.level > 1 && !element.parent.hasText;
        if (element.parent != null) {
            ensureStarted(element.parent);
            element.parent.hasElementChildren = true;
            settleWhitespace(element.parent);
        }
        final StringBuilder tag = new StringBuilder();
        if (indentThis) {
            tag.append('\n').append(spaces((element.level - 1) * INDENT));
        }
        tag.append('<').append(element.qName);

        final boolean wrap = indented() && saxonAttributeLength(element) > LINE_LENGTH;
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

    private static String escapeAttribute(final String value) {
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

    private static String escapeContent(final String text) {
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
            throw new StructureException(call + " with no element open");
        }
        return element;
    }

    private void checkNoAttributeOpen(final String call) {
        if (attribute != null) {
            throw new StructureException(call + " while attribute '" + attribute.qName + "' is open");
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

    private boolean indented() {
        return layout == Layout.INDENTED;
    }

    private static String spaces(final int n) {
        return " ".repeat(n);
    }

    private static final class Element {

        private final String qName;
        private final int level;
        private final Element parent;
        private final boolean omitIfEmpty;
        /** Prefix bindings in scope here: the parent's, plus this element's own declarations. */
        private final Map<String, String> scope;
        private final List<String[]> declarations = new ArrayList<>();
        private final List<String[]> attributes = new ArrayList<>();
        private boolean started;
        private boolean hasElementChildren;
        private boolean hasText;
        private final StringBuilder pendingWhitespace = new StringBuilder();

        private Element(final String qName, final int level, final Element parent, final boolean omitIfEmpty) {
            this.qName = qName;
            this.level = level;
            this.parent = parent;
            this.omitIfEmpty = omitIfEmpty;
            this.scope = new HashMap<>(parent == null ? Map.of() : parent.scope);
        }
    }

    private static final class Attribute {

        private final String qName;
        private final boolean omitIfEmpty;
        private final ByteArrayOutputStream value = new ByteArrayOutputStream();

        private Attribute(final String qName, final boolean omitIfEmpty) {
            this.qName = qName;
            this.omitIfEmpty = omitIfEmpty;
        }
    }
}
