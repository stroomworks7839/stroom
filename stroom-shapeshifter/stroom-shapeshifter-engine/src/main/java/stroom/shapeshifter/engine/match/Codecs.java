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

package stroom.shapeshifter.engine.match;

import stroom.shapeshifter.engine.config.Codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Turning bytes into other bytes.
 *
 * <p>A codec is not a match — it consumes no input. It takes a value and transforms it, which
 * is how a base64 field or a compressed block gets parsed by the templates a body dispatches
 * the decoded bytes to (design 38's {@code decode} transform).
 *
 * <p>Everything here is in the JDK. The three that are not — snappy, zstd and lz4 — are refused
 * at compile time (D33) rather than silently producing nothing.
 *
 * <p>A codec that cannot do its job returns null, which is absence. That is deliberate: input
 * that is not valid base64 is input this configuration does not describe, and the right
 * answer is "nothing", not an exception.
 */
public final class Codecs {

    private Codecs() {
    }

    /** True if this build can apply a codec at all. */
    public static boolean isSupported(final Codec codec) {
        return switch (codec) {
            case BASE64, BASE64_URL, HEX, URL_ENCODING, DEFLATE, GZIP, XML_ENTITIES, JSON_STRING -> true;
            case SNAPPY, ZSTD, LZ4 -> false;
        };
    }

    /** Decode, or null if the input is not what the codec expects. */
    public static byte[] decode(final byte[] input, final Codec codec) {
        try {
            return switch (codec) {
                case BASE64 -> Base64.getDecoder().decode(input);
                case BASE64_URL -> Base64.getUrlDecoder().decode(input);
                case HEX -> fromHex(input);
                case URL_ENCODING -> fromPercent(input);
                case DEFLATE -> readAll(new InflaterInputStream(new ByteArrayInputStream(input)));
                case GZIP -> readAll(new GZIPInputStream(new ByteArrayInputStream(input)));
                case XML_ENTITIES -> xmlEntities(input);
                case JSON_STRING -> jsonString(input);
                case SNAPPY, ZSTD, LZ4 -> null;
            };
        } catch (final IllegalArgumentException | IOException e) {
            return null;
        }
    }

    private static byte[] fromHex(final byte[] input) {
        if (input.length % 2 != 0) {
            return null;
        }
        final byte[] result = new byte[input.length / 2];
        for (int i = 0; i < result.length; i++) {
            final int high = digit(input[i * 2]);
            final int low = digit(input[i * 2 + 1]);
            if (high < 0 || low < 0) {
                return null;
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static int digit(final byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    private static byte[] fromPercent(final byte[] input) {
        final ByteArrayOutputStream result = new ByteArrayOutputStream(input.length);
        int i = 0;
        while (i < input.length) {
            if (input[i] == '%' && i + 2 < input.length) {
                final int high = digit(input[i + 1]);
                final int low = digit(input[i + 2]);
                if (high >= 0 && low >= 0) {
                    result.write((high << 4) | low);
                    i += 3;
                    continue;
                }
            }
            result.write(input[i]);
            i++;
        }
        return result.toByteArray();
    }

    /**
     * XML references decoded in UTF-8 bytes (design 41): {@code &lt; &gt; &amp; &quot; &apos;},
     * {@code &#N;} and {@code &#xN;}. Anything else after an ampersand is left as written,
     * which is what a value that was never XML-escaped needs. The input is returned as itself
     * when it holds no ampersand, so a value with nothing to decode costs a scan and nothing
     * else.
     */
    private static byte[] xmlEntities(final byte[] input) {
        int amp = indexOf(input, (byte) '&', 0);
        if (amp < 0) {
            return input;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        int from = 0;
        while (amp >= 0) {
            final int semi = indexOf(input, (byte) ';', amp + 1);
            final int decoded = semi < 0 ? -1 : reference(input, amp + 1, semi);
            if (decoded < 0) {
                out.write(input, from, amp + 1 - from);
                from = amp + 1;
            } else {
                out.write(input, from, amp - from);
                writeUtf8(out, decoded);
                from = semi + 1;
            }
            amp = indexOf(input, (byte) '&', from);
        }
        out.write(input, from, input.length - from);
        return out.toByteArray();
    }

    /** The code point a reference between {@code &} and {@code ;} names, or -1 when it names none. */
    private static int reference(final byte[] a, final int from, final int to) {
        final int n = to - from;
        if (n >= 2 && a[from] == '#') {
            final boolean hex = a[from + 1] == 'x' || a[from + 1] == 'X';
            int value = 0;
            int digits = 0;
            for (int i = from + (hex ? 2 : 1); i < to; i++) {
                final int d = Character.digit(a[i], hex ? 16 : 10);
                if (d < 0 || value > 0x10FFFF) {
                    return -1;
                }
                value = value * (hex ? 16 : 10) + d;
                digits++;
            }
            return digits == 0 || value > 0x10FFFF ? -1 : value;
        }
        if (n == 2 && a[from] == 'l' && a[from + 1] == 't') {
            return '<';
        }
        if (n == 2 && a[from] == 'g' && a[from + 1] == 't') {
            return '>';
        }
        if (n == 3 && a[from] == 'a' && a[from + 1] == 'm' && a[from + 2] == 'p') {
            return '&';
        }
        if (n == 4 && a[from] == 'q' && a[from + 1] == 'u' && a[from + 2] == 'o' && a[from + 3] == 't') {
            return '"';
        }
        if (n == 4 && a[from] == 'a' && a[from + 1] == 'p' && a[from + 2] == 'o' && a[from + 3] == 's') {
            return '\'';
        }
        return -1;
    }

    /**
     * A JSON string's escapes decoded in UTF-8 bytes (design 41): the eight short escapes and
     * the four-hex-digit unicode escape, a high surrogate followed by an escaped low one
     * joined into one code point. A backslash that begins no valid escape is left as written. The input is returned
     * as itself when it holds no backslash.
     */
    private static byte[] jsonString(final byte[] input) {
        int slash = indexOf(input, (byte) '\\', 0);
        if (slash < 0) {
            return input;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
        int from = 0;
        while (slash >= 0 && slash + 1 < input.length) {
            out.write(input, from, slash - from);
            final byte c = input[slash + 1];
            int consumed = 2;
            switch (c) {
                case '"' -> out.write('"');
                case '\\' -> out.write('\\');
                case '/' -> out.write('/');
                case 'b' -> out.write('\b');
                case 'f' -> out.write('\f');
                case 'n' -> out.write('\n');
                case 'r' -> out.write('\r');
                case 't' -> out.write('\t');
                case 'u' -> {
                    int point = hex4(input, slash + 2);
                    if (point < 0) {
                        out.write(input, slash, 1);
                        consumed = 1;
                    } else {
                        consumed = 6;
                        if (Character.isHighSurrogate((char) point) && slash + 11 < input.length
                            && input[slash + 6] == '\\' && input[slash + 7] == 'u') {
                            final int low = hex4(input, slash + 8);
                            if (low >= 0 && Character.isLowSurrogate((char) low)) {
                                point = Character.toCodePoint((char) point, (char) low);
                                consumed = 12;
                            }
                        }
                        writeUtf8(out, point);
                    }
                }
                default -> {
                    out.write(input, slash, 1);
                    consumed = 1;
                }
            }
            from = slash + consumed;
            slash = indexOf(input, (byte) '\\', from);
        }
        out.write(input, from, input.length - from);
        return out.toByteArray();
    }

    private static int hex4(final byte[] a, final int from) {
        if (from + 4 > a.length) {
            return -1;
        }
        int value = 0;
        for (int i = from; i < from + 4; i++) {
            final int d = Character.digit(a[i], 16);
            if (d < 0) {
                return -1;
            }
            value = value * 16 + d;
        }
        return value;
    }

    private static void writeUtf8(final ByteArrayOutputStream out, final int codePoint) {
        if (codePoint < 0x80) {
            out.write(codePoint);
        } else if (codePoint < 0x800) {
            out.write(0xC0 | (codePoint >> 6));
            out.write(0x80 | (codePoint & 0x3F));
        } else if (codePoint < 0x10000) {
            out.write(0xE0 | (codePoint >> 12));
            out.write(0x80 | ((codePoint >> 6) & 0x3F));
            out.write(0x80 | (codePoint & 0x3F));
        } else {
            out.write(0xF0 | (codePoint >> 18));
            out.write(0x80 | ((codePoint >> 12) & 0x3F));
            out.write(0x80 | ((codePoint >> 6) & 0x3F));
            out.write(0x80 | (codePoint & 0x3F));
        }
    }

    private static int indexOf(final byte[] a, final byte b, final int from) {
        for (int i = from; i < a.length; i++) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] readAll(final InputStream input) throws IOException {
        try (input) {
            return input.readAllBytes();
        }
    }
}
