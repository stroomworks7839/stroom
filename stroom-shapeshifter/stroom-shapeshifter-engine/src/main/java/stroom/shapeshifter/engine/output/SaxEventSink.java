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
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link OutputSink} that forwards structure as SAX events.
 *
 * <p>The same bookkeeping as {@link XmlByteSink}, spent differently: a declaration becomes
 * {@code startPrefixMapping}, attribute bytes accumulate into an {@code Attributes} object, the
 * deferred start fires as {@code startElement} at the first content or child, content is
 * {@code characters()}, and the document begins with the root element and ends with it. Bytes at
 * document level have nowhere to go: whitespace and an XML declaration are dropped, anything else
 * is a {@link StructureException} — a configuration that writes markup as text belongs to the
 * parse-and-forward path (design 20 S7), not here.
 *
 * <p>Unlike the byte sink this one must know every URI, so a prefix that is not bound in scope
 * is refused when the element or attribute using it is emitted. {@link #position()} counts
 * events, which is the currency attribution speaks in for this target (design 20 S5).
 */
public final class SaxEventSink implements OutputSink {

    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    private final ContentHandler handler;
    private final Deque<Element> open = new ArrayDeque<>();
    private long events;
    private Attribute attribute;
    private byte[] carry = new byte[0];
    private boolean documentStarted;
    private boolean documentEnded;

    /** A sink delivering the document's events to a handler. */
    public SaxEventSink(final ContentHandler handler) {
        this.handler = handler;
    }

    // -----------------------------------------------------------------------------------
    // Structure
    // -----------------------------------------------------------------------------------

    @Override
    public void startElement(final String qName, final String namespace, final boolean omitIfEmpty) {
        checkNoAttributeOpen("startElement " + qName);
        flushCarry();
        final Element parent = open.peek();
        if (parent == null && documentEnded) {
            throw new StructureException("element " + qName + " after the document element has closed");
        }
        // As in the byte sink: the parent starts when this child first emits, not now, so an
        // omitted child leaves it untouched — and the document starts with its root's first event.
        final Element element = new Element(qName, parent, omitIfEmpty);
        open.push(element);
        if (namespace != null) {
            final String prefix = XmlByteSink.prefixOf(qName);
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
        if (!element.started && element.omitIfEmpty
            && element.declarations.isEmpty() && element.attributes.isEmpty()) {
            open.pop();
            return;
        }
        ensureStarted(element);
        sax(() -> handler.endElement(element.uri, element.localName, element.qName));
        for (int i = element.declarations.size() - 1; i >= 0; i--) {
            final String prefix = element.declarations.get(i)[0];
            sax(() -> handler.endPrefixMapping(prefix));
        }
        open.pop();
        if (open.isEmpty()) {
            sax(handler::endDocument);
            documentEnded = true;
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
        final byte[] bytes = new byte[carry.length + length];
        System.arraycopy(carry, 0, bytes, 0, carry.length);
        System.arraycopy(data, offset, bytes, carry.length, length);
        final int complete = bytes.length - Utf8.incompleteTail(bytes);
        carry = Arrays.copyOfRange(bytes, complete, bytes.length);
        content(new String(bytes, 0, complete, StandardCharsets.UTF_8));
    }

    @Override
    public long position() {
        return events;
    }

    @Override
    public Unit unit() {
        return Unit.EVENTS;
    }

    private void content(final String text) {
        if (text.isEmpty()) {
            return;
        }
        final Element element = open.peek();
        if (element == null) {
            final String flat = text.strip();
            // Whitespace between top-level things is nobody's; an XML declaration is the byte
            // sink's concern and has no event — a configuration that writes one for the file
            // target is not wrong to be running here.
            if (flat.isEmpty() || (flat.startsWith("<?xml") && flat.endsWith("?>"))) {
                return;
            }
            throw new StructureException(
                    "text outside any element cannot be forwarded as events: '" + excerpt(text) + "'");
        }
        // Whitespace inside an element is the author's, delivered as written — unlike the byte
        // sink, which is a serialiser and applies Saxon's rule that an element-only element's
        // whitespace is the indenter's. On the event path there is no indenter; whatever
        // serialises the events at the end of the pipeline applies its own rule, as it does to
        // a stylesheet's text nodes (E38, ruled 2026-09-04).
        ensureStarted(element);
        final char[] chars = text.toCharArray();
        sax(() -> handler.characters(chars, 0, chars.length));
    }

    private void flushCarry() {
        if (carry.length > 0) {
            final byte[] bytes = carry;
            carry = new byte[0];
            content(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    // -----------------------------------------------------------------------------------
    // The deferred start
    // -----------------------------------------------------------------------------------

    private void ensureStarted(final Element element) {
        if (element.started) {
            return;
        }
        element.started = true;
        if (element.parent != null) {
            ensureStarted(element.parent);
        } else if (!documentStarted) {
            sax(handler::startDocument);
            documentStarted = true;
        }
        for (final String[] declaration : element.declarations) {
            sax(() -> handler.startPrefixMapping(declaration[0], declaration[1]));
        }
        element.uri = resolve(element, XmlByteSink.prefixOf(element.qName), "element " + element.qName);
        element.localName = localOf(element.qName);
        final AttributesImpl attributes = new AttributesImpl();
        for (final String[] attribute : element.attributes) {
            final String prefix = XmlByteSink.prefixOf(attribute[0]);
            // An unprefixed attribute is in no namespace, whatever the default namespace is.
            final String uri = prefix.isEmpty() ? "" : resolve(element, prefix, "attribute " + attribute[0]);
            attributes.addAttribute(uri, localOf(attribute[0]), attribute[0], "CDATA", attribute[1]);
        }
        sax(() -> handler.startElement(element.uri, element.localName, element.qName, attributes));
    }

    private static String resolve(final Element element, final String prefix, final String what) {
        final String uri = element.scope.get(prefix);
        if (uri == null) {
            throw new StructureException(what + " uses prefix '" + prefix + "', which is not bound in scope");
        }
        return uri;
    }

    private static Map<String, String> rootScope() {
        final Map<String, String> scope = new HashMap<>();
        scope.put("", "");
        scope.put("xml", XML_NS);
        return scope;
    }

    private static String localOf(final String qName) {
        final int colon = qName.indexOf(':');
        return colon < 0 ? qName : qName.substring(colon + 1);
    }

    // -----------------------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------------------

    private interface SaxCall {

        void run() throws SAXException;
    }

    private void sax(final SaxCall call) {
        try {
            call.run();
            events++;
        } catch (final SAXException e) {
            throw new StructureException("The event handler refused an event: " + e.getMessage());
        }
    }

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

    private static String excerpt(final String text) {
        final String flat = text.strip();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    private static final class Element {

        private final String qName;
        private final Element parent;
        private final boolean omitIfEmpty;
        private final Map<String, String> scope;
        private final List<String[]> declarations = new ArrayList<>();
        private final List<String[]> attributes = new ArrayList<>();
        private boolean started;
        private String uri;
        private String localName;

        private Element(final String qName, final Element parent, final boolean omitIfEmpty) {
            this.qName = qName;
            this.parent = parent;
            this.omitIfEmpty = omitIfEmpty;
            this.scope = new HashMap<>(parent == null ? rootScope() : parent.scope);
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
