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

package stroom.shapeshifter.config.json;

import stroom.shapeshifter.config.ConfigException;

import java.util.Map;

/**
 * JSON text in and out of the tree: the client's parser and everyone's printer.
 *
 * <p>The engine parses with Jackson and could print with it, but the client cannot, and a
 * project printed on one side of the wire should read the same as one printed on the other —
 * so this printer is the one both use, and {@code ProjectReader} keeps Jackson only for
 * reading. The parser exists because a JavaScript {@code JSON.parse} makes every number a
 * double, and {@link JsonNumber} keeps the spelling: it is the whole of RFC 8259, strict about
 * what it accepts (no comments, no trailing commas, no bare words but the three literals) and
 * naming the line and column of what it refuses, because a configuration is edited by hand.
 *
 * <p>The pretty form is two-space indented, one member or element per line, {@code "key":
 * value}, and {@code {}}/{@code []} for empty; the compact form has no whitespace at all.
 * Non-ASCII is written as itself — the wire is UTF-8 — and only the quote, the backslash
 * and control characters are escaped.
 */
public final class JsonText {

    private JsonText() {
    }

    /** @throws ConfigException naming line and column, if the text is not JSON */
    public static JsonValue parse(final String text) {
        final Parser parser = new Parser(text);
        final JsonValue value = parser.value();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected content after the document");
        }
        return value;
    }

    public static String print(final JsonValue value) {
        final StringBuilder out = new StringBuilder();
        write(out, value, -1);
        return out.toString();
    }

    public static String printPretty(final JsonValue value) {
        final StringBuilder out = new StringBuilder();
        write(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    /** @param depth the current indentation level, or -1 for the compact form */
    private static void write(final StringBuilder out, final JsonValue value, final int depth) {
        final int inner = depth < 0 ? -1 : depth + 1;
        if (value instanceof JsonObject object) {
            if (object.size() == 0) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (final Map.Entry<String, JsonValue> entry : object.entries()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, inner);
                writeString(out, entry.getKey());
                out.append(depth < 0 ? ":" : ": ");
                write(out, entry.getValue(), inner);
            }
            newline(out, depth);
            out.append('}');
        } else if (value instanceof JsonArray array) {
            if (array.size() == 0) {
                out.append("[]");
                return;
            }
            out.append('[');
            boolean first = true;
            for (final JsonValue element : array.elements()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, inner);
                write(out, element, inner);
            }
            newline(out, depth);
            out.append(']');
        } else if (value instanceof JsonString string) {
            writeString(out, string.value());
        } else if (value instanceof JsonNumber number) {
            out.append(number.literal());
        } else if (value instanceof JsonBoolean bool) {
            out.append(bool.value() ? "true" : "false");
        } else {
            out.append("null");
        }
    }

    private static void newline(final StringBuilder out, final int depth) {
        if (depth < 0) {
            return;
        }
        out.append('\n');
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private static void writeString(final StringBuilder out, final String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // -----------------------------------------------------------------------------------

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(final String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        ConfigException error(final String what) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < pos && i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new ConfigException("Configuration is not valid JSON: " + what
                                       + " at line " + line + ", column " + column);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                final char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private char peek() {
            if (atEnd()) {
                throw error("Unexpected end of the document");
            }
            return text.charAt(pos);
        }

        private void expect(final char c) {
            if (peek() != c) {
                throw error("Expected '" + c + "' but found '" + peek() + "'");
            }
            pos++;
        }

        JsonValue value() {
            skipWhitespace();
            final char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new JsonString(string());
                case 't' -> literal("true", JsonBoolean.TRUE);
                case 'f' -> literal("false", JsonBoolean.FALSE);
                case 'n' -> literal("null", JsonNull.NULL);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        private JsonValue literal(final String word, final JsonValue value) {
            if (!text.startsWith(word, pos)) {
                throw error("Unexpected word");
            }
            pos += word.length();
            return value;
        }

        private JsonObject object() {
            expect('{');
            final JsonObject object = new JsonObject();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected a quoted member name");
                }
                final String name = string();
                skipWhitespace();
                expect(':');
                object.put(name, value());
                skipWhitespace();
                final char c = peek();
                pos++;
                if (c == '}') {
                    return object;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}' but found '" + c + "'");
                }
            }
        }

        private JsonArray array() {
            expect('[');
            final JsonArray array = new JsonArray();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return array;
            }
            while (true) {
                array.add(value());
                skipWhitespace();
                final char c = peek();
                pos++;
                if (c == ']') {
                    return array;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']' but found '" + c + "'");
                }
            }
        }

        private String string() {
            expect('"');
            final StringBuilder out = new StringBuilder();
            while (true) {
                final char c = peek();
                pos++;
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    pos--;
                    throw error("A control character must be escaped in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                final char escaped = peek();
                pos++;
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> out.append(hex4());
                    default -> {
                        pos -= 2;
                        throw error("Unknown escape '\\" + escaped + "'");
                    }
                }
            }
        }

        private char hex4() {
            int code = 0;
            for (int i = 0; i < 4; i++) {
                final char c = peek();
                final int digit;
                if (c >= '0' && c <= '9') {
                    digit = c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    digit = c - 'a' + 10;
                } else if (c >= 'A' && c <= 'F') {
                    digit = c - 'A' + 10;
                } else {
                    throw error("Expected four hex digits after \\u");
                }
                code = (code << 4) | digit;
                pos++;
            }
            return (char) code;
        }

        /** The number's spelling, checked against the JSON grammar and kept as it was. */
        private JsonNumber number() {
            final int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (peek() >= '1' && peek() <= '9') {
                digits();
            } else {
                throw error("Expected a digit");
            }
            if (!atEnd() && text.charAt(pos) == '.') {
                pos++;
                if (atEnd() || !isDigit(text.charAt(pos))) {
                    throw error("Expected a digit after '.'");
                }
                digits();
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                if (atEnd() || !isDigit(text.charAt(pos))) {
                    throw error("Expected a digit in the exponent");
                }
                digits();
            }
            return new JsonNumber(text.substring(start, pos));
        }

        private void digits() {
            while (!atEnd() && isDigit(text.charAt(pos))) {
                pos++;
            }
        }

        private static boolean isDigit(final char c) {
            return c >= '0' && c <= '9';
        }
    }
}
