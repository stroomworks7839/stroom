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

package stroom.shapeshifter.engine.text;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How the bytes of an input are meant to be read as text.
 *
 * <p>The engine matches bytes, not characters (D13), so an encoding matters at exactly two
 * boundaries: turning a configuration's delimiters into bytes to look for, and turning captured
 * bytes back into text to write out. In between, nothing needs to know.
 *
 * <p>Three encodings are handled here rather than by the JDK, because each has a definition
 * simpler than a lookup table. {@link #LATIN_1} and {@link #RAW} map every byte to the code point
 * of the same number — which is what makes RAW usable for binary that must survive a round trip.
 * {@link #ASCII} is the same for the low half and a replacement character above it.
 *
 * <p>{@link #AUTO} is not an encoding; it is an instruction to look at the input's byte-order
 * mark and, failing that, to assume UTF-8.
 */
public enum Encoding {

    /** UTF-8. The default, and what everything else is converted to internally. */
    UTF_8("utf-8", "UTF-8"),
    /** UTF-16, least significant byte first. */
    UTF_16LE("utf-16le", "UTF-16LE"),
    /** UTF-16, most significant byte first. */
    UTF_16BE("utf-16be", "UTF-16BE"),

    /** ISO-8859-1. Every byte is the code point of the same number. */
    LATIN_1("iso-8859-1", "ISO-8859-1"),
    /** Windows-1252 — Latin-1 plus a euro sign and typographic quotes where Latin-1 has controls. */
    WINDOWS_1252("windows-1252", "windows-1252"),
    /** ISO-8859-15 — Latin-1 with a euro sign. */
    ISO_8859_15("iso-8859-15", "ISO-8859-15"),

    /** ISO-8859-2 — Central European. */
    ISO_8859_2("iso-8859-2", "ISO-8859-2"),
    /** Windows-1250 — Central European. */
    WINDOWS_1250("windows-1250", "windows-1250"),
    /** ISO-8859-5 — Cyrillic. */
    ISO_8859_5("iso-8859-5", "ISO-8859-5"),
    /** Windows-1251 — Cyrillic. */
    WINDOWS_1251("windows-1251", "windows-1251"),
    /** KOI8-R — Russian. */
    KOI8_R("koi8-r", "KOI8-R"),
    /** ISO-8859-7 — Greek. */
    ISO_8859_7("iso-8859-7", "ISO-8859-7"),
    /** Windows-1253 — Greek. */
    WINDOWS_1253("windows-1253", "windows-1253"),
    /** ISO-8859-9 — Turkish. */
    ISO_8859_9("iso-8859-9", "ISO-8859-9"),
    /** Windows-1254 — Turkish. */
    WINDOWS_1254("windows-1254", "windows-1254"),

    /** ISO-8859-6 — Arabic. */
    ISO_8859_6("iso-8859-6", "ISO-8859-6"),
    /** Windows-1256 — Arabic. */
    WINDOWS_1256("windows-1256", "windows-1256"),
    /** ISO-8859-8 — Hebrew. */
    ISO_8859_8("iso-8859-8", "ISO-8859-8"),
    /** Windows-1255 — Hebrew. */
    WINDOWS_1255("windows-1255", "windows-1255"),

    /** Shift JIS — Japanese. */
    SHIFT_JIS("shift_jis", "Shift_JIS", "windows-31j"),
    /** EUC-JP — Japanese. */
    EUC_JP("euc-jp", "EUC-JP"),
    /** ISO-2022-JP — Japanese, as used in mail. */
    ISO_2022_JP("iso-2022-jp", "ISO-2022-JP"),
    /** GBK — Simplified Chinese. */
    GBK("gbk", "GBK"),
    /** GB18030 — Chinese, a superset of GBK. */
    GB18030("gb18030", "GB18030"),
    /** Big5 — Traditional Chinese. */
    BIG5("big5", "Big5"),
    /** EUC-KR — Korean. */
    EUC_KR("euc-kr", "EUC-KR"),

    /** US-ASCII. Anything above 0x7F is not text. */
    ASCII("us-ascii", "US-ASCII"),
    /** Windows-874 — Thai. */
    WINDOWS_874("windows-874", "x-windows-874", "windows-874", "TIS-620"),
    /** Windows-1258 — Vietnamese. */
    WINDOWS_1258("windows-1258", "x-windows-1258", "windows-1258"),

    /** No text interpretation: every byte is the code point of the same number. */
    RAW("raw"),
    /** Look at the byte-order mark; failing that, UTF-8. */
    AUTO("auto");

    private static final Map<String, Encoding> BY_LABEL = byLabel();

    private final String label;
    private final String[] charsetNames;

    Encoding(final String label, final String... charsetNames) {
        this.label = label;
        this.charsetNames = charsetNames;
    }

    /** The canonical name for this encoding. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /**
     * How many bytes one unit of this encoding occupies.
     *
     * <p>Two for the UTF-16s and one for everything else — which is not the same as "one byte per
     * character", since UTF-8 is variable width. It is the granularity at which scanning may
     * safely step.
     */
    public int codeUnitSize() {
        return this == UTF_16LE || this == UTF_16BE ? 2 : 1;
    }

    /** True if every byte is exactly one character. */
    public boolean isSingleByte() {
        return switch (this) {
            case LATIN_1, ASCII, RAW, WINDOWS_1252, ISO_8859_15, ISO_8859_2, WINDOWS_1250,
                 ISO_8859_5, WINDOWS_1251, KOI8_R, ISO_8859_7, WINDOWS_1253, ISO_8859_9,
                 WINDOWS_1254, ISO_8859_6, WINDOWS_1256, ISO_8859_8, WINDOWS_1255,
                 WINDOWS_874, WINDOWS_1258 -> true;
            default -> false;
        };
    }

    /**
     * True if bytes in this encoding can be written out as UTF-8 unchanged.
     *
     * <p>The engine's internal form is UTF-8, so this is the test for whether a capture needs
     * converting at all — and for most real inputs the answer is no.
     */
    public boolean isUtf8Compatible() {
        return this == UTF_8 || this == ASCII || this == AUTO;
    }

    /** True if this build can actually read this encoding. */
    public boolean isAvailable() {
        return charset() != null || this == RAW || this == AUTO;
    }

    /** The JDK charset behind this encoding, or null if it is handled here or unavailable. */
    public Charset charset() {
        for (final String name : charsetNames) {
            if (Charset.isSupported(name)) {
                return Charset.forName(name);
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------------------
    // Naming
    // -----------------------------------------------------------------------------------

    /**
     * Find an encoding by name, forgivingly.
     *
     * <p>Case, hyphens, underscores and spaces are ignored, and the common aliases are accepted,
     * because these names are typed by people and {@code Windows-1252}, {@code windows1252} and
     * {@code cp1252} all mean the same thing.
     *
     * @return the encoding, or null if the name is not one this engine knows
     */
    public static Encoding fromLabel(final String name) {
        return name == null ? null : BY_LABEL.get(normalise(name));
    }

    private static String normalise(final String name) {
        final StringBuilder result = new StringBuilder(name.length());
        for (final char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c != '-' && c != '_' && c != ' ') {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static Map<String, Encoding> byLabel() {
        final Map<String, Encoding> map = new LinkedHashMap<>();
        for (final Encoding encoding : values()) {
            map.put(normalise(encoding.label), encoding);
        }
        alias(map, UTF_8, "utf8");
        alias(map, UTF_16LE, "utf16le");
        alias(map, UTF_16BE, "utf16be");
        alias(map, LATIN_1, "latin1", "iso88591", "88591");
        alias(map, WINDOWS_1252, "cp1252", "win1252");
        alias(map, ISO_8859_15, "latin9");
        alias(map, ISO_8859_2, "latin2");
        alias(map, WINDOWS_1250, "cp1250");
        alias(map, ISO_8859_5, "cyrillic");
        alias(map, WINDOWS_1251, "cp1251");
        alias(map, KOI8_R, "koi8");
        alias(map, ISO_8859_7, "greek");
        alias(map, WINDOWS_1253, "cp1253");
        alias(map, ISO_8859_9, "latin5");
        alias(map, WINDOWS_1254, "cp1254");
        alias(map, ISO_8859_6, "arabic");
        alias(map, WINDOWS_1256, "cp1256");
        alias(map, ISO_8859_8, "hebrew");
        alias(map, WINDOWS_1255, "cp1255");
        alias(map, SHIFT_JIS, "shiftjis", "sjis");
        alias(map, ISO_2022_JP, "jis");
        alias(map, GBK, "gb2312", "chinese");
        alias(map, BIG5, "bigfive");
        alias(map, EUC_KR, "ksc5601", "korean");
        alias(map, ASCII, "ascii", "us");
        alias(map, WINDOWS_874, "cp874", "tis620", "thai");
        alias(map, WINDOWS_1258, "cp1258", "vietnamese");
        return Map.copyOf(map);
    }

    private static void alias(final Map<String, Encoding> map,
                              final Encoding encoding,
                              final String... names) {
        for (final String name : names) {
            map.put(normalise(name), encoding);
        }
    }

    // -----------------------------------------------------------------------------------
    // Detection and inheritance
    // -----------------------------------------------------------------------------------

    /**
     * What a byte-order mark at the start of some bytes says, if anything.
     *
     * @param encoding the encoding it indicates
     * @param length   how many bytes the mark occupies, which the reader must skip
     */
    public record ByteOrderMark(Encoding encoding, int length) {

    }

    /** Read a byte-order mark, or null if there is none. */
    public static ByteOrderMark detectByteOrderMark(final byte[] bytes) {
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new ByteOrderMark(UTF_8, 3);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new ByteOrderMark(UTF_16LE, 2);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new ByteOrderMark(UTF_16BE, 2);
        }
        return null;
    }

    /**
     * The encoding in force somewhere, given what was declared there and what encloses it.
     *
     * <p>Saying nothing inherits. So does saying {@code auto}, which is the distinction worth
     * having: {@code auto} at the root means detect, and {@code auto} further in means "whatever
     * was decided out there".
     */
    public static Encoding resolve(final Encoding declared, final Encoding parent) {
        return declared == null || declared == AUTO ? parent : declared;
    }

    // -----------------------------------------------------------------------------------
    // Conversion
    // -----------------------------------------------------------------------------------

    /** Read bytes as text. Anything that cannot be read becomes a replacement character. */
    public String decode(final byte[] bytes) {
        return decode(bytes, 0, bytes.length);
    }

    /** Read part of an array as text. */
    public String decode(final byte[] bytes, final int from, final int length) {
        return switch (this) {
            case UTF_8, AUTO -> new String(bytes, from, length, StandardCharsets.UTF_8);
            // Byte and code point are the same number, so there is nothing to look up.
            case LATIN_1, RAW -> new String(bytes, from, length, StandardCharsets.ISO_8859_1);
            case ASCII -> {
                final char[] chars = new char[length];
                for (int i = 0; i < length; i++) {
                    final int b = bytes[from + i] & 0xFF;
                    chars[i] = b < 0x80 ? (char) b : '�';
                }
                yield new String(chars);
            }
            default -> new String(bytes, from, length, required());
        };
    }

    /** Write text as bytes. Anything this encoding cannot express becomes a question mark. */
    public byte[] encode(final String text) {
        return switch (this) {
            case UTF_8, AUTO -> text.getBytes(StandardCharsets.UTF_8);
            case ASCII -> {
                final byte[] bytes = new byte[text.length()];
                for (int i = 0; i < text.length(); i++) {
                    final char c = text.charAt(i);
                    bytes[i] = c < 0x80 ? (byte) c : (byte) '?';
                }
                yield bytes;
            }
            case LATIN_1, RAW -> {
                final byte[] bytes = new byte[text.length()];
                for (int i = 0; i < text.length(); i++) {
                    final char c = text.charAt(i);
                    bytes[i] = c <= 0xFF ? (byte) c : (byte) '?';
                }
                yield bytes;
            }
            default -> text.getBytes(required());
        };
    }

    private Charset required() {
        final Charset charset = charset();
        if (charset == null) {
            throw new IllegalStateException("This build cannot read " + label);
        }
        return charset;
    }
}
