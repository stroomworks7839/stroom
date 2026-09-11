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
import org.xml.sax.helpers.AttributesImpl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
    /** The element being written, or null at document level: an element knows its own parent. */
    private Element innermost;
    private final SaxEvents events = new SaxEvents();
    private Attribute attribute;
    private final Utf8.Carry carry = new Utf8.Carry();
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
        checkNoAttributeOpen("startElement", qName);
        flushCarry();
        final Element parent = innermost;
        if (parent == null && documentEnded) {
            throw new StructureException("element " + qName + " after the document element has closed");
        }
        // As in the byte sink: the parent starts when this child first emits, not now, so an
        // omitted child leaves it untouched — and the document starts with its root's first event.
        final Element element = new Element(qName, parent, omitIfEmpty);
        innermost = element;
        if (namespace != null) {
            final String prefix = QNames.prefixOf(qName);
            if (!namespace.equals(inScope(element, prefix))) {
                declare(element, prefix, namespace);
            }
        }
    }

    @Override
    public void namespace(final String prefix, final String uri) {
        final Element element = current("namespace", prefix);
        if (element.started) {
            throw new StructureException(
                    "namespace '" + prefix + "' arrived after the content of <" + element.qName + "> had begun");
        }
        declare(element, prefix, uri);
    }

    private static void declare(final Element element, final String prefix, final String uri) {
        element.declarations = append(element.declarations, element.declarationCount, prefix, uri);
        element.declarationCount += 2;
    }

    @Override
    public void startAttribute(final String qName, final boolean omitIfEmpty) {
        final Element element = current("attribute", qName);
        checkNoAttributeOpen("startAttribute", qName);
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
            final Element holder = innermost;
            holder.attributes = append(holder.attributes, holder.attributeCount,
                    attribute.qName, value);
            holder.attributeCount += 2;
        }
        attribute = null;
    }

    @Override
    public void endElement() {
        final Element element = current("endElement");
        checkNoAttributeOpen("endElement", element.qName);
        flushCarry();
        if (!element.started && element.omitIfEmpty
            && element.declarationCount == 0 && element.attributeCount == 0) {
            innermost = element.parent;
            return;
        }
        ensureStarted(element);
        events.make(() -> handler.endElement(element.uri, element.localName, element.qName));
        for (int i = element.declarationCount - 2; i >= 0; i -= 2) {
            final String prefix = element.declarations[i];
            events.make(() -> handler.endPrefixMapping(prefix));
        }
        innermost = element.parent;
        if (innermost == null) {
            events.make(handler::endDocument);
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
        content(carry.take(data, offset, length));
    }

    @Override
    public long position() {
        return events.count();
    }

    @Override
    public Unit unit() {
        return Unit.EVENTS;
    }

    private void content(final String text) {
        if (text.isEmpty()) {
            return;
        }
        final Element element = innermost;
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
        events.make(() -> handler.characters(chars, 0, chars.length));
    }

    private void flushCarry() {
        final String rest = carry.flush();
        if (rest != null) {
            content(rest);
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
            events.make(handler::startDocument);
            documentStarted = true;
        }
        for (int i = 0; i < element.declarationCount; i += 2) {
            final String prefix = element.declarations[i];
            final String uri = element.declarations[i + 1];
            events.make(() -> handler.startPrefixMapping(prefix, uri));
        }
        // The name is split once, here, rather than twice — prefixOf and localOf each scanned
        // for the colon, for the element and again for every attribute.
        final int elementColon = element.qName.indexOf(':');
        element.uri = resolve(element, QNames.prefixOf(element.qName, elementColon),
                "element", element.qName);
        element.localName = QNames.localOf(element.qName, elementColon);
        final AttributesImpl attributes = new AttributesImpl();
        for (int i = 0; i < element.attributeCount; i += 2) {
            final String qName = element.attributes[i];
            final int colon = qName.indexOf(':');
            final String prefix = QNames.prefixOf(qName, colon);
            // An unprefixed attribute is in no namespace, whatever the default namespace is.
            final String uri = prefix.isEmpty()
                    ? ""
                    : resolve(element, prefix, "attribute", qName);
            attributes.addAttribute(uri, QNames.localOf(qName, colon), qName, "CDATA",
                    element.attributes[i + 1]);
        }
        events.make(() ->
                handler.startElement(element.uri, element.localName, element.qName, attributes));
    }

    // The subject travels in two pieces for the same reason the guards' does: this runs per
    // element and per prefixed attribute, and the refusal it describes is the rare case.
    private static String resolve(final Element element,
                                  final String prefix,
                                  final String kind,
                                  final String name) {
        final String uri = inScope(element, prefix);
        if (uri == null) {
            throw new StructureException(
                    kind + " " + name + " uses prefix '" + prefix + "', which is not bound in scope");
        }
        return uri;
    }

    /**
     * A prefix's binding here, or null — walked up the open elements rather than held as a map.
     *
     * <p>The two the document always has are answered without walking: the empty prefix is bound
     * to no namespace, and {@code xml} is bound by the specification.
     */
    private static String inScope(final Element from, final String prefix) {
        for (Element e = from; e != null; e = e.parent) {
            for (int i = 0; i < e.declarationCount; i += 2) {
                if (e.declarations[i].equals(prefix)) {
                    return e.declarations[i + 1];
                }
            }
        }
        if (prefix.isEmpty()) {
            return "";
        }
        return "xml".equals(prefix) ? XML_NS : null;
    }

    /** Add a name and value to a flat pair array, growing it — null until the first pair. */
    private static String[] append(final String[] pairs, final int count,
                                   final String name, final String value) {
        String[] grown = pairs;
        if (grown == null) {
            grown = new String[4];
        } else if (count == grown.length) {
            grown = Arrays.copyOf(grown, count * 2);
        }
        grown[count] = name;
        grown[count + 1] = value;
        return grown;
    }

    // -----------------------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------------------

    private Element current(final String call, final String subject) {
        final Element element = innermost;
        if (element == null) {
            throw new StructureException(call + " " + subject + " with no element open");
        }
        return element;
    }

    /** The same, for the one call that names no subject. */
    private Element current(final String call) {
        final Element element = innermost;
        if (element == null) {
            throw new StructureException(call + " with no element open");
        }
        return element;
    }

    // As in XmlByteSink: the call and its subject travel separately, so the description of a
    // refusal that almost never fires is not concatenated on every structural write.
    private void checkNoAttributeOpen(final String call, final String subject) {
        if (attribute != null) {
            throw new StructureException(
                    call + " " + subject + " while attribute '" + attribute.qName + "' is open");
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
        /**
         * Declarations and attributes as flat {@code prefix, uri, …} and {@code qName, value, …}
         * pairs, null until there is one, and no scope map — a prefix is looked up by walking the
         * open elements. The byte sink says why, at length; this is the same change.
         */
        private String[] declarations;
        private int declarationCount;
        private String[] attributes;
        private int attributeCount;
        private boolean started;
        private String uri;
        private String localName;

        private Element(final String qName, final Element parent, final boolean omitIfEmpty) {
            this.qName = qName;
            this.parent = parent;
            this.omitIfEmpty = omitIfEmpty;
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
