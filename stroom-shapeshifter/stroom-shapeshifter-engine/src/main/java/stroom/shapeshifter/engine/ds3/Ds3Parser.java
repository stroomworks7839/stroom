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

package stroom.shapeshifter.engine.ds3;

import stroom.shapeshifter.engine.config.ConfigException;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Reads a Data Splitter v3 XML configuration.
 *
 * <p>Strict about the things DS3 is strict about — a {@code <split>} with no delimiter, a
 * {@code <var>} with no id, an element the language does not have — because a configuration that
 * is quietly wrong produces quietly wrong records.
 *
 * <p>Attribute values carry Java-style escapes ({@code \n}, {@code \t}, {@code \\}) on the
 * delimiter and its companions, which is how a config says "split on a newline" in an XML
 * attribute. Those are unescaped here rather than at match time.
 */
public final class Ds3Parser {

    /** What a configuration gets if it does not ask for a buffer size. */
    private static final int DEFAULT_BUFFER_SIZE = 20_000;

    /** However large a configuration asks for, it gets no more than this. */
    private static final int MAX_BUFFER_SIZE = 100_000_000;

    private Ds3Parser() {
    }

    /**
     * Parse a DS3 configuration.
     *
     * @throws ConfigException if it is not well-formed, or not a valid DS3 configuration
     */
    public static Ds3Config parse(final String xml) {
        final Document document;
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Configurations arrive from outside; there is no reason for one to reach out to
            // the filesystem or the network while being read.
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            document = factory.newDocumentBuilder().parse(source(xml));
        } catch (final ParserConfigurationException e) {
            throw new ConfigException("The XML parser could not be configured: " + e.getMessage(), e);
        } catch (final SAXException | IOException e) {
            throw new ConfigException("Configuration is not well-formed XML: " + e.getMessage(), e);
        }
        final Element root = document.getDocumentElement();
        if (!"dataSplitter".equals(localName(root))) {
            throw new ConfigException(
                    "The document element must be <dataSplitter>, not <" + localName(root) + ">");
        }
        return element(root);
    }

    private static InputSource source(final String xml) {
        // A declared encoding is about bytes, and this is already a string; reading it as
        // characters avoids the parser objecting to an encoding it is not being given.
        return new InputSource(new StringReader(xml));
    }

    private static Ds3Config element(final Element element) {
        final String name = localName(element);
        return switch (name) {
            case "dataSplitter" -> {
                // 'version' says which DS3 this is; the format documents it and every real
                // configuration carries it, so it is read for nothing and accepted.
                attributes(element, name, Set.of("bufferSize", "ignoreErrors", "version"));
                yield new Ds3Config.Root(
                        bufferSize(element), flag(element, "ignoreErrors"), children(element));
            }
            case "split" -> {
                attributes(element, name, Set.of("id", "delimiter", "escape", "containerStart",
                        "containerEnd", "minMatch", "maxMatch", "onlyMatch"));
                yield new Ds3Config.Split(
                        attribute(element, "id"),
                        unescape(required(element, "split", "delimiter")),
                        unescape(attribute(element, "escape")),
                        unescape(attribute(element, "containerStart")),
                        unescape(attribute(element, "containerEnd")),
                        number(element, "split", "minMatch", 0),
                        number(element, "split", "maxMatch", -1),
                        onlyMatch(element),
                        children(element));
            }
            case "regex" -> {
                attributes(element, name, Set.of("id", "pattern", "dotAll", "caseInsensitive",
                        "advance", "minMatch", "maxMatch", "onlyMatch"));
                yield new Ds3Config.Regex(
                        attribute(element, "id"),
                        required(element, "regex", "pattern"),
                        flag(element, "dotAll"),
                        flag(element, "caseInsensitive"),
                        number(element, "regex", "advance", 0),
                        number(element, "regex", "minMatch", 0),
                        number(element, "regex", "maxMatch", -1),
                        onlyMatch(element),
                        children(element));
            }
            case "all" -> {
                attributes(element, name, Set.of("id"));
                yield new Ds3Config.All(attribute(element, "id"), children(element));
            }
            case "group" -> {
                attributes(element, name, Set.of("id", "value", "ignoreErrors"));
                yield new Ds3Config.Group(
                        attribute(element, "id"),
                        attribute(element, "value"),
                        flag(element, "ignoreErrors"),
                        children(element));
            }
            case "data" -> {
                attributes(element, name, Set.of("id", "name", "value"));
                yield new Ds3Config.Data(
                        attribute(element, "id"),
                        attribute(element, "name"),
                        attribute(element, "value"),
                        children(element));
            }
            case "var" -> {
                attributes(element, name, Set.of("id", "value"));
                yield new Ds3Config.Var(
                        required(element, "var", "id"), attribute(element, "value"));
            }
            default -> throw new ConfigException("Unknown element in configuration: " + name);
        };
    }

    private static List<Ds3Config> children(final Element element) {
        final List<Ds3Config> children = new ArrayList<>();
        final NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            final Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                if ("dataSplitter".equals(localName((Element) node))) {
                    throw new ConfigException("<dataSplitter> must be the document element");
                }
                children.add(element((Element) node));
            }
        }
        return children;
    }

    // -----------------------------------------------------------------------------------
    // Attributes
    // -----------------------------------------------------------------------------------

    private static String localName(final Element element) {
        final String name = element.getLocalName();
        if (name != null) {
            return name;
        }
        final String tag = element.getTagName();
        final int colon = tag.lastIndexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    /**
     * Refuse any attribute the importer does not know.
     *
     * <p>A misspelled attribute — {@code maxmatch} for {@code maxMatch} — would otherwise change
     * what the configuration does without a word said, which is the quietly-wrong failure this
     * parser exists to prevent. Namespaced attributes ({@code xmlns}, {@code xsi:schemaLocation})
     * are XML machinery, not DS3 vocabulary, and pass. {@code matchOrder} is DS3 vocabulary the
     * import deliberately refuses, and says so by name.
     */
    private static void attributes(final Element element, final String owner, final Set<String> known) {
        final NamedNodeMap all = element.getAttributes();
        for (int i = 0; i < all.getLength(); i++) {
            final Attr attr = (Attr) all.item(i);
            if (attr.getNamespaceURI() != null) {
                continue;
            }
            final String name = attr.getName();
            if (known.contains(name)) {
                continue;
            }
            if ("matchOrder".equals(name)) {
                throw new ConfigException("<" + owner + "> attribute 'matchOrder' is not imported; "
                                          + "the engine's dispatch attribute owns this — see E18/E20");
            }
            throw new ConfigException("Unknown attribute '" + name + "' on <" + owner + ">");
        }
    }

    private static String attribute(final Element element, final String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static String required(final Element element, final String owner, final String name) {
        final String value = attribute(element, name);
        if (value == null) {
            throw new ConfigException("<" + owner + "> is missing its '" + name + "' attribute");
        }
        return value;
    }

    private static boolean flag(final Element element, final String name) {
        return "true".equalsIgnoreCase(element.getAttribute(name));
    }

    private static int number(final Element element,
                              final String owner,
                              final String name,
                              final int fallback) {
        final String value = attribute(element, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new ConfigException(
                    "<" + owner + "> attribute '" + name + "' is not a number: " + value, e);
        }
    }

    /**
     * The buffer size, positive and capped.
     *
     * <p>A non-positive size is a configuration error and refused. A size above
     * {@link #MAX_BUFFER_SIZE} is silently clamped to it — the ask was legitimate, the engine
     * just will not allocate that much, and the parser has no channel for warnings.
     */
    private static int bufferSize(final Element element) {
        final int size = number(element, "dataSplitter", "bufferSize", DEFAULT_BUFFER_SIZE);
        if (size <= 0) {
            throw new ConfigException("<dataSplitter> bufferSize must be positive: " + size);
        }
        return Math.min(size, MAX_BUFFER_SIZE);
    }

    private static Set<Integer> onlyMatch(final Element element) {
        final String value = attribute(element, "onlyMatch");
        if (value == null) {
            return null;
        }
        final Set<Integer> matches = new LinkedHashSet<>();
        for (final String part : value.split(",")) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    matches.add(Integer.valueOf(trimmed));
                } catch (final NumberFormatException e) {
                    throw new ConfigException("onlyMatch is not a list of numbers: " + value, e);
                }
            }
        }
        return matches;
    }

    /** Undo the Java-style escapes an XML attribute has to use to carry a newline or a tab. */
    private static String unescape(final String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        final StringBuilder result = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            final char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                final char next = value.charAt(i + 1);
                switch (next) {
                    case 'n' -> result.append('\n');
                    case 't' -> result.append('\t');
                    case '\\' -> result.append('\\');
                    default -> result.append('\\').append(next);
                }
                i += 2;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }
}
