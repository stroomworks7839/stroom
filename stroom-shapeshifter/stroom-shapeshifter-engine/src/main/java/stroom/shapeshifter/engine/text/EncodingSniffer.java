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

import java.util.ArrayList;
import java.util.List;

/**
 * What encoding an input is in, decided from a leading window of it (design 32 phase 1).
 *
 * <p>{@link Encoding#AUTO} has always described itself as "not an encoding; it is an instruction
 * to look at the input's byte-order mark". This is that instruction, made a thing, so that it can
 * be carried out <b>before</b> a configuration compiles rather than in the middle of a run —
 * which is design 32's whole point, because a compiled model is compiled <i>for</i> a reading and
 * cannot follow the input to another one.
 *
 * <p><b>Nothing uses this yet.</b> Phase 1 is the answer and its tests; the wiring is phase 3.
 *
 * <h2>It says how sure it is, and that is the point</h2>
 *
 * <p>A byte-order mark is a <i>statement</i>. Valid multi-byte UTF-8 is strong evidence. A window
 * of pure ASCII is consistent with every candidate and evidence for none of them. Those are
 * different things, and a sniffer that returned only an {@link Encoding} would flatten them —
 * so this returns {@link Certainty} beside it, and the caller can treat a guess differently from
 * a fact: log it, refuse it, or let a declared encoding win.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not try to tell one <b>single-byte</b> encoding from another. Windows-1252, Latin-1
 * and the ISO-8859 family agree on every byte below 0x80 and differ only above it, and choosing
 * between them from bytes alone is frequency analysis on text in an unknown language. It reports
 * that it cannot tell, and the caller's fallback decides — design 32 §6 is the ruling on what
 * that should be, and it is open. <b>The trap named there is why this asks rather than picks</b>:
 * a single-byte encoding never fails, so a wrong answer here could not be detected downstream.
 *
 * <h2>Multi-byte encodings, and an ordered list</h2>
 *
 * <p>The multi-byte legacy encodings <em>can</em> be told apart from everything else, because each
 * has a byte grammar a scan can check — this is the half of what a full detector does that needs
 * no language model, and it is the half worth having. What a grammar often cannot do is tell them
 * apart from <em>each other</em>: GBK, Big5 and EUC-KR accept much of the same byte space, so a
 * window frequently fits several.
 *
 * <p>So the caller passes an <b>ordered list of candidates</b> and the first that fits wins —
 * which is what a text editor does ({@code vim}'s {@code fileencodings} is the same idea) and is
 * the honest shape, because it puts the tie-break in configuration rather than in a guess. When
 * more than one fits, the answer says {@link Certainty#NARROWED} rather than pretending to have
 * deduced it.
 *
 * <h2>What it costs</h2>
 *
 * <p><b>One window, never more</b> — {@link #WINDOW} bytes, read once. Every rule is a linear
 * scan of it, so the work is a small multiple of 8 KiB per stream and is bounded whatever the
 * input turns out to be. Nothing here reads ahead, seeks, or asks for a second window.
 */
public final class EncodingSniffer {

    /**
     * How much of an input this ever looks at. Enough for a mark, an escape sequence, a NUL and
     * a grammar to disagree.
     *
     * <p><b>Binding, not advisory.</b> Whatever a caller passes, nothing here reads past this —
     * so the claim that sniffing costs one bounded window is enforced by the code rather than by
     * the caller remembering. A caller that hands over a whole file gets the same work as one
     * that hands over the first page.
     */
    public static final int WINDOW = 8192;

    private EncodingSniffer() {
    }

    /** How the answer was arrived at, which is not the same question as what it is. */
    public enum Certainty {

        /** The input says so: a byte-order mark. */
        MARKED,

        /** The bytes cannot be anything else, or cannot be text at all. */
        DEDUCED,

        /**
         * Several candidates fit the bytes and the caller's order chose between them. Their
         * grammars overlap, so this is as far as structure can get without a language model.
         */
        NARROWED,

        /** Nothing in the window distinguishes the candidates; the fallback was taken. */
        ASSUMED
    }

    /**
     * What the window said.
     *
     * @param encoding  the reading to compile and run under
     * @param certainty how the answer was arrived at
     * @param because   why, in one phrase, for a diagnostic that has to explain itself
     */
    public record Sniff(Encoding encoding, Certainty certainty, String because) {

    }

    /**
     * Read a window and decide.
     *
     * <p>The rules are asked in this order, and the order is the design: a statement beats
     * evidence, evidence beats an assumption, and "this is not text" is settled before any
     * question about which text it is.
     *
     * @param window   the first bytes of the input
     * @param length   how many of them are filled
     * @param fallback what to answer when the window distinguishes nothing
     */
    public static Sniff sniff(final byte[] window, final int length, final Encoding fallback) {
        return sniff(window, length, List.of(), fallback);
    }

    /**
     * Read a window and decide, considering an ordered list of multi-byte candidates.
     *
     * <p>The rules are asked in this order, and the order is the design: a statement beats
     * evidence, evidence beats an assumption, and "this is not text" is settled before any
     * question about which text it is — except that the two encodings which <em>look</em> like
     * binary are recognised first, or their NULs would be mistaken for it.
     *
     * @param window     the first bytes of the input
     * @param length     how many of them are filled
     * @param candidates multi-byte encodings to try, in the order they should be preferred;
     *                   anything else in the list simply never fits, because only the grammars
     *                   in {@link #MULTI_BYTE_CANDIDATES} are known here
     * @param fallback   what to answer when the window distinguishes nothing
     */
    public static Sniff sniff(final byte[] window,
                              final int length,
                              final List<Encoding> candidates,
                              final Encoding fallback) {
        final int limit = Math.min(length, WINDOW);
        final Encoding.ByteOrderMark mark = Encoding.detectByteOrderMark(window);
        if (mark != null && mark.length() <= limit) {
            return new Sniff(mark.encoding(), Certainty.MARKED,
                    "a " + mark.encoding().label() + " byte-order mark");
        }
        if (hasEscapeSequence(window, limit)) {
            // ISO-2022-JP shifts character set with escape sequences, and nothing else here uses
            // ESC that way. Unambiguous, and visible in the first few bytes of real text.
            return new Sniff(Encoding.ISO_2022_JP, Certainty.DEDUCED,
                    "an ISO-2022 escape sequence, which no other candidate uses");
        }
        final Encoding wide = unmarkedUtf16(window, limit);
        if (wide != null) {
            // Must precede the NUL rule below: UTF-16 text is about half NUL bytes, and reading
            // it as binary would be a confident wrong answer rather than an uncertain one.
            return new Sniff(wide, Certainty.DEDUCED,
                    "NUL bytes on one alignment only, which is unmarked " + wide.label());
        }
        if (hasNul(window, limit)) {
            // Text does not contain NUL; a JPEG's JFIF header has one within eleven bytes, and
            // so does almost every other binary format. RAW maps every byte to a code point,
            // which is what a configuration matching binary structure wants — it is the right
            // answer here rather than a failure (design 32 §5).
            return new Sniff(Encoding.RAW, Certainty.DEDUCED,
                    "a NUL byte, which text does not contain");
        }
        final Utf8 utf8 = readUtf8(window, limit);
        if (utf8 == Utf8.INVALID) {
            // UTF-8 is asked before the candidate list, and outranks it, because the evidence is
            // asymmetric: legacy text is very often valid GBK *and* valid Big5, but is rarely
            // valid UTF-8 by accident, while UTF-8 text frequently satisfies a legacy grammar.
            final List<Encoding> fitting = new ArrayList<>();
            for (final Encoding candidate : candidates) {
                if (fits(candidate, window, limit)) {
                    fitting.add(candidate);
                }
            }
            if (fitting.size() == 1) {
                return new Sniff(fitting.getFirst(), Certainty.DEDUCED,
                        "the only candidate whose byte grammar the window fits");
            }
            if (!fitting.isEmpty()) {
                return new Sniff(fitting.getFirst(), Certainty.NARROWED,
                        "first of " + fitting.size() + " candidates the window fits: " + fitting);
            }
            return new Sniff(fallback, Certainty.ASSUMED,
                    "not valid UTF-8, and one single-byte encoding cannot be told from another");
        }
        if (utf8 == Utf8.MULTI_BYTE) {
            return new Sniff(Encoding.UTF_8, Certainty.DEDUCED,
                    "valid UTF-8 including sequences no single-byte encoding would produce");
        }
        if (utf8 == Utf8.TRUNCATED) {
            // A lead byte with the window ending before its continuation. It may be a character
            // cut by the buffer's edge, or a single-byte encoding's own byte with nothing after
            // it; the bytes that would tell them apart are not here.
            return new Sniff(fallback, Certainty.ASSUMED,
                    "the window ends inside what may be a character, so too little to tell");
        }
        // Pure ASCII. Every candidate agrees on it, so it is evidence for none of them — and it
        // is a window rather than the whole input, so it cannot even promise the rest is ASCII.
        return new Sniff(fallback, Certainty.ASSUMED,
                "nothing above 0x7F in the window, which every encoding reads alike");
    }

    private static boolean hasNul(final byte[] window, final int length) {
        for (int i = 0; i < length; i++) {
            if (window[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** What a window's bytes are as UTF-8. */
    private enum Utf8 { ASCII, MULTI_BYTE, TRUNCATED, INVALID }

    /**
     * Read the window as UTF-8.
     *
     * <p>A truncated sequence at the very end is not invalid — the window is a prefix of the
     * input and a character may straddle its edge, so the read stops there rather than
     * condemning the whole stream for where the buffer happened to end.
     */
    private static Utf8 readUtf8(final byte[] window, final int length) {
        boolean multiByte = false;
        int i = 0;
        while (i < length) {
            final int b = window[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            final int follow = b >= 0xF0 ? 3 : b >= 0xE0 ? 2 : b >= 0xC2 ? 1 : -1;
            if (follow < 0 || b > 0xF4) {
                // A continuation byte with nothing to continue, or an over-long or out-of-range
                // lead. Both say this is not UTF-8.
                return Utf8.INVALID;
            }
            if (i + follow >= length) {
                // Straddles the window's edge; the rest of the input would settle it. Said as
                // its own answer rather than folded into ASCII, because a byte above 0x7F is
                // present and a reason claiming otherwise would be a lie.
                return multiByte ? Utf8.MULTI_BYTE : Utf8.TRUNCATED;
            }
            for (int j = 1; j <= follow; j++) {
                if ((window[i + j] & 0xC0) != 0x80) {
                    return Utf8.INVALID;
                }
            }
            multiByte = true;
            i += follow + 1;
        }
        return multiByte ? Utf8.MULTI_BYTE : Utf8.ASCII;
    }

    /**
     * Every multi-byte encoding this can recognise, narrowest grammar first.
     *
     * <p><b>Passing this whole list is usually the wrong thing to do</b>, and it is here to be
     * named rather than to be a default. Measured against real encoder output, the grammars
     * overlap like this — a row is the encoding text was written in, a column a grammar that
     * accepts it:
     *
     * <pre>
     *              shift_jis  euc-jp  gb18030  gbk  big5  euc-kr
     *  shift_jis      fits       -      fits   fits   -    fits
     *  euc-jp          -       fits     fits   fits  fits  fits
     *  gb18030         -         -      fits   fits  fits   -
     *  gbk             -         -      fits   fits  fits   -
     *  big5           fits       -      fits   fits  fits   -
     *  euc-kr         fits     fits     fits   fits  fits  fits
     * </pre>
     *
     * <p>Every grammar accepts its own output, and most accept several others'. Big5 text fits
     * the Shift_JIS grammar, so <em>no</em> ordering of all six answers both correctly — which
     * is the thing to understand before using this: order cannot repair an overlap, it can only
     * decide who wins one. <b>A deployment should pass the one or two encodings its feeds
     * actually carry</b>, and get {@link Certainty#DEDUCED}, rather than pass everything and get
     * a confident-looking {@link Certainty#NARROWED} that is right by luck.
     *
     * <p>The order here is by how much each grammar accepts, narrowest first, which is the least
     * bad ordering of a list that should not usually be used whole.
     */
    public static final List<Encoding> MULTI_BYTE_CANDIDATES = List.of(
            Encoding.EUC_JP, Encoding.SHIFT_JIS, Encoding.EUC_KR,
            Encoding.BIG5, Encoding.GBK, Encoding.GB18030);

    /**
     * An ISO-2022 escape sequence: {@code ESC $ @}, {@code ESC $ B}, {@code ESC ( B},
     * {@code ESC ( J} or {@code ESC ( I}.
     *
     * <p>The one multi-byte encoding here that announces itself. Real text switches character set
     * in its first line, so a window is far more than enough.
     */
    private static boolean hasEscapeSequence(final byte[] window, final int length) {
        for (int i = 0; i + 2 < length; i++) {
            if (window[i] != 0x1B) {
                continue;
            }
            final int b1 = window[i + 1] & 0xFF;
            final int b2 = window[i + 2] & 0xFF;
            if (b1 == '$' && (b2 == '@' || b2 == 'B')) {
                return true;
            }
            if (b1 == '(' && (b2 == 'B' || b2 == 'J' || b2 == 'I')) {
                return true;
            }
        }
        return false;
    }

    /**
     * UTF-16 with no byte-order mark, or null.
     *
     * <p>Text below U+0100 encodes as a NUL beside each byte, and which side it falls on is the
     * byte order: NULs at even offsets are big-endian, at odd offsets little-endian. So the
     * signal is not "how many NULs" but "are they all on one alignment", which random binary
     * does not manage and which needs no language model.
     *
     * <p>The threshold guards against a handful of NULs in something else. It does not try to
     * detect UTF-16 text made entirely of characters above U+00FF, which carries no NULs at all
     * and cannot be told from other two-byte data by structure.
     */
    private static Encoding unmarkedUtf16(final byte[] window, final int length) {
        if (length < 16) {
            return null;
        }
        int even = 0;
        int odd = 0;
        for (int i = 0; i < length; i++) {
            if (window[i] == 0) {
                if ((i & 1) == 0) {
                    even++;
                } else {
                    odd++;
                }
            }
        }
        final int enough = length / 8;
        if (even > enough && odd == 0) {
            return Encoding.UTF_16BE;
        }
        if (odd > enough && even == 0) {
            return Encoding.UTF_16LE;
        }
        return null;
    }

    /**
     * Whether a window satisfies a multi-byte encoding's byte grammar.
     *
     * <p>Structure only. These grammars overlap heavily — most GBK is also valid Big5 and
     * EUC-KR — so fitting is evidence that a window <em>could</em> be an encoding, never that it
     * is. That is what {@link Certainty#NARROWED} exists to say.
     *
     * <p>A sequence cut by the window's edge ends the scan rather than failing it, for the same
     * reason the UTF-8 read stops there: the window is a prefix.
     */
    private static boolean fits(final Encoding encoding, final byte[] window, final int length) {
        int i = 0;
        while (i < length) {
            final int b = window[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            final int taken = switch (encoding) {
                case SHIFT_JIS -> shiftJis(window, length, i, b);
                case EUC_JP -> eucJp(window, length, i, b);
                case GBK -> twoByte(window, length, i, b, 0x81, 0xFE, 0x40, 0x7E, 0x80, 0xFE);
                case GB18030 -> gb18030(window, length, i, b);
                case BIG5 -> twoByte(window, length, i, b, 0x81, 0xFE, 0x40, 0x7E, 0xA1, 0xFE);
                case EUC_KR -> eucKr(window, length, i, b);
                default -> -1;
            };
            if (taken == 0) {
                // Cut by the window's edge; the rest of the input would settle it.
                return true;
            }
            if (taken < 0) {
                return false;
            }
            i += taken;
        }
        return true;
    }

    /** Shift_JIS: half-width katakana stand alone, and trail bytes reach into the ASCII range. */
    private static int shiftJis(final byte[] w, final int length, final int i, final int b) {
        if (b >= 0xA1 && b <= 0xDF) {
            return 1;
        }
        if ((b >= 0x81 && b <= 0x9F) || (b >= 0xE0 && b <= 0xFC)) {
            return twoByte(w, length, i, b, 0x81, 0xFC, 0x40, 0x7E, 0x80, 0xFC);
        }
        return -1;
    }

    /** EUC-JP: 0x8E and 0x8F introduce; nothing else between 0x80 and 0xA0 is legal. */
    private static int eucJp(final byte[] w, final int length, final int i, final int b) {
        if (b == 0x8E) {
            return twoByte(w, length, i, b, 0x8E, 0x8E, 0xA1, 0xDF, 0xA1, 0xDF);
        }
        if (b == 0x8F) {
            if (i + 2 >= length) {
                return 0;
            }
            return inRange(w[i + 1], 0xA1, 0xFE) && inRange(w[i + 2], 0xA1, 0xFE) ? 3 : -1;
        }
        if (b >= 0xA1 && b <= 0xFE) {
            return twoByte(w, length, i, b, 0xA1, 0xFE, 0xA1, 0xFE, 0xA1, 0xFE);
        }
        return -1;
    }

    /** EUC-KR: trail bytes also reach into the ASCII letters. */
    private static int eucKr(final byte[] w, final int length, final int i, final int b) {
        if (b < 0x81 || b > 0xFE) {
            return -1;
        }
        if (i + 1 >= length) {
            return 0;
        }
        final int t = w[i + 1] & 0xFF;
        final boolean ok = (t >= 0x41 && t <= 0x5A) || (t >= 0x61 && t <= 0x7A)
                           || (t >= 0x81 && t <= 0xFE);
        return ok ? 2 : -1;
    }

    /** GB18030: GBK, plus a four-byte form whose second and fourth bytes are ASCII digits. */
    private static int gb18030(final byte[] w, final int length, final int i, final int b) {
        if (b < 0x81 || b > 0xFE) {
            return -1;
        }
        if (i + 1 >= length) {
            return 0;
        }
        if (inRange(w[i + 1], 0x30, 0x39)) {
            if (i + 3 >= length) {
                return 0;
            }
            return inRange(w[i + 2], 0x81, 0xFE) && inRange(w[i + 3], 0x30, 0x39) ? 4 : -1;
        }
        return twoByte(w, length, i, b, 0x81, 0xFE, 0x40, 0x7E, 0x80, 0xFE);
    }

    /**
     * A lead byte in range followed by a trail in either of two ranges.
     *
     * @return 2 when it fits, 0 when the pair is cut by the window's edge, −1 when it does not
     */
    private static int twoByte(final byte[] w, final int length, final int i, final int b,
                               final int leadLo, final int leadHi,
                               final int trailLoA, final int trailHiA,
                               final int trailLoB, final int trailHiB) {
        if (b < leadLo || b > leadHi) {
            return -1;
        }
        if (i + 1 >= length) {
            return 0;
        }
        return inRange(w[i + 1], trailLoA, trailHiA) || inRange(w[i + 1], trailLoB, trailHiB)
                ? 2
                : -1;
    }

    private static boolean inRange(final byte value, final int lo, final int hi) {
        final int b = value & 0xFF;
        return b >= lo && b <= hi;
    }
}
