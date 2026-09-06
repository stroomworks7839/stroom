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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Turning bytes into other bytes.
 *
 * <p>A codec is not a match — it consumes no input. It takes what an earlier step produced and
 * transforms it, which is how a base64 field or a compressed block gets parsed by the steps that
 * follow.
 *
 * <p>Everything here is in the JDK. The three that are not — snappy, zstd and lz4 — are refused
 * at compile time (D33) rather than silently producing nothing.
 *
 * <p>A codec that cannot do its job returns null, which fails the step and therefore the match.
 * That is deliberate: input that is not valid base64 is input this configuration does not
 * describe, and the right answer is "no match", not an exception.
 */
public final class Codecs {

    private Codecs() {
    }

    /** True if this build can apply a codec at all. */
    public static boolean isSupported(final Codec codec) {
        return switch (codec) {
            case BASE64, BASE64_URL, HEX, URL_ENCODING, DEFLATE, GZIP -> true;
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
                case SNAPPY, ZSTD, LZ4 -> null;
            };
        } catch (final IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /** Encode, or null if this build cannot. */
    public static byte[] encode(final byte[] input, final Codec codec) {
        try {
            return switch (codec) {
                case BASE64 -> Base64.getEncoder().encode(input);
                case BASE64_URL -> Base64.getUrlEncoder().encode(input);
                case HEX -> toHex(input);
                case URL_ENCODING -> toPercent(input);
                case DEFLATE -> deflate(input);
                case GZIP -> gzip(input);
                case SNAPPY, ZSTD, LZ4 -> null;
            };
        } catch (final IOException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------------------
    // Hex
    // -----------------------------------------------------------------------------------

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

    private static byte[] toHex(final byte[] input) {
        final byte[] digits = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        final byte[] result = new byte[input.length * 2];
        for (int i = 0; i < input.length; i++) {
            result[i * 2] = digits[(input[i] >> 4) & 0x0F];
            result[i * 2 + 1] = digits[input[i] & 0x0F];
        }
        return result;
    }

    // -----------------------------------------------------------------------------------
    // Percent-encoding
    // -----------------------------------------------------------------------------------

    /**
     * Undo percent-encoding.
     *
     * <p>Byte-level rather than string-level, and {@code +} stays a plus: this is URI escaping,
     * not HTML form encoding, and the two disagree about exactly that character.
     */
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

    private static byte[] toPercent(final byte[] input) {
        final ByteArrayOutputStream result = new ByteArrayOutputStream(input.length);
        for (final byte b : input) {
            final int value = b & 0xFF;
            final boolean unreserved = (value >= 'A' && value <= 'Z')
                                       || (value >= 'a' && value <= 'z')
                                       || (value >= '0' && value <= '9')
                                       || value == '-' || value == '.' || value == '_' || value == '~';
            if (unreserved) {
                result.write(value);
            } else {
                result.write('%');
                result.write("0123456789ABCDEF".charAt(value >> 4));
                result.write("0123456789ABCDEF".charAt(value & 0x0F));
            }
        }
        return result.toByteArray();
    }

    // -----------------------------------------------------------------------------------
    // Compression
    // -----------------------------------------------------------------------------------

    private static byte[] deflate(final byte[] input) throws IOException {
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(result)) {
            out.write(input);
        }
        return result.toByteArray();
    }

    private static byte[] gzip(final byte[] input) throws IOException {
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(result)) {
            out.write(input);
        }
        return result.toByteArray();
    }

    private static byte[] readAll(final InputStream input) throws IOException {
        try (input) {
            return input.readAllBytes();
        }
    }
}
