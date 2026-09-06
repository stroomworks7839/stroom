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

import stroom.shapeshifter.engine.compile.PatternKey;
import stroom.shapeshifter.engine.config.Endianness;
import stroom.shapeshifter.engine.config.MatchStep;
import stroom.shapeshifter.engine.config.Predicate;
import stroom.shapeshifter.engine.config.StepRef;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.text.RegexEncodings;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;
import stroom.shapeshifter.regex.BytePattern;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Progressive matching: a sequence of steps, each starting where the last one stopped.
 *
 * <p>This is what a regex cannot do. A length prefix that decides how many bytes to read next, a
 * seek to an offset a header pointed at, a base64 field decoded and then parsed further — none
 * of them are expressible as a pattern, because each depends on a <i>value</i> the match has
 * already produced. So steps produce values as well as consuming bytes, and a later step can
 * name an earlier one.
 *
 * <p><b>It deliberately does not backtrack.</b> A {@code Choice} takes the first alternative
 * that matches and never reconsiders; a {@code Repeat} is greedy and never gives anything back.
 * That is the language's rule, and it is worth being explicit about, because the
 * obvious-looking alternative would change which inputs match:
 * {@code stroom.shapeshifter.regex.comb} has almost exactly this vocabulary — {@code Tag},
 * {@code Sequence}, {@code Choice}, {@code Repeat}, {@code Ref} — and lowering these steps onto
 * it would compile them into a real pattern that <i>does</i> backtrack. Faster, more capable,
 * and not the same language. Making that change is a decision about the language, not an
 * optimisation.
 */
public final class Steps {

    /** What a step produced, and how much input it ate doing so. */
    private record Result(TypedValue output, int consumed) {

    }

    private static final TypedValue NOTHING = TypedValue.of(new byte[0]);

    /** Decode tables for the single-byte encodings, one lazy row per encoding. */
    private static final ConcurrentHashMap<Encoding, char[]> SINGLE_BYTE = new ConcurrentHashMap<>();

    private Steps() {
    }

    /**
     * Run a whole sequence at a position.
     *
     * <p>Group 0 of the result is everything consumed; group {@code i + 1} is step {@code i}'s
     * output. That numbering is what {@code CaptureSource.Step} indexes into.
     *
     * @return the match, or null if any step failed
     */
    public static MatchResult match(final List<MatchStep> steps,
                                    final byte[] data,
                                    final int from,
                                    final int to,
                                    final Map<PatternKey, BytePattern> patterns,
                                    final Encoding encoding) {
        int pos = 0;
        int highWater = 0;
        final List<TypedValue> outputs = new ArrayList<>(steps.size());

        for (final MatchStep step : steps) {
            // The two seeks that can move backwards are handled here rather than as ordinary
            // steps, because a step reports how far forward it went and cannot express going
            // back. They need the whole input addressable, which is why a configuration using
            // them has to be run whole rather than in buffers.
            if (step instanceof MatchStep.SeekAbs seek) {
                final Integer target = count(seek.offset(), outputs);
                if (target == null || target > to - from) {
                    return null;
                }
                pos = target;
                outputs.add(NOTHING);
            } else if (step instanceof MatchStep.SeekBack seek) {
                final Integer back = count(seek.count(), outputs);
                if (back == null || back > pos) {
                    return null;
                }
                pos -= back;
                outputs.add(NOTHING);
            } else {
                final Result result = step(step, data, from + pos, to, outputs, List.of(), pos, patterns, encoding);
                if (result == null) {
                    return null;
                }
                pos += result.consumed();
                outputs.add(result.output());
            }
            highWater = Math.max(highWater, pos);
        }

        // A rewind does not un-read what was read. The match consumes up to the furthest point
        // reached, so the stream never re-reads bytes a seek stepped back over.
        final TypedValue[] groups = new TypedValue[outputs.size() + 1];
        groups[0] = TypedValue.of(Arrays.copyOfRange(data, from, from + highWater));
        for (int i = 0; i < outputs.size(); i++) {
            groups[i + 1] = outputs.get(i);
        }
        return new MatchResult(groups, highWater, 0);
    }

    // -----------------------------------------------------------------------------------
    // One step
    // -----------------------------------------------------------------------------------

    /**
     * Run one step at a position: one arm per step kind, so the method is as long as the
     * vocabulary.
     *
     * @param prior    outputs from enclosing sequences, which a step reference can name
     * @param local    outputs from this sequence so far, numbered after the prior ones
     * @param position how far into the whole match this is, which is what {@code Tell} reports
     */
    private static Result step(final MatchStep step,
                               final byte[] data,
                               final int from,
                               final int to,
                               final List<TypedValue> prior,
                               final List<TypedValue> local,
                               final int position,
                               final Map<PatternKey, BytePattern> patterns,
                                    final Encoding encoding) {
        final int available = to - from;
        return switch (step) {
            case MatchStep.Tag tag -> {
                // The effective encoding, not UTF-8 by fiat: the compiler encodes Delimiter
                // literals under the template's charset (E3), and a tag is the same kind of
                // literal looking for the same kind of bytes.
                final byte[] bytes = encoding.encode(tag.value());
                yield startsWith(data, from, to, bytes) ? new Result(TypedValue.of(bytes), bytes.length) : null;
            }
            case MatchStep.MatchByte value -> startsWith(data, from, to, value.value())
                    ? new Result(TypedValue.of(value.value()), value.value().length)
                    : null;
            case MatchStep.TakeWhile takeWhile -> {
                // E5: the predicate classifies characters, not bytes, and a character is what
                // the effective encoding says it is — a multi-byte UTF-8 letter is a letter,
                // a windows-1252 0xE9 is a letter under that encoding and a stray byte under
                // raw. RAW keeps the ASCII reading: bytes with no declared meaning earn none.
                // D38 rules the regex dialect the same way, so the two vocabularies agree.
                int end = from;
                while (end < to) {
                    final long decoded = decode(data, end, to, encoding);
                    if (decoded < 0 || !matches(takeWhile.predicate(), (int) (decoded >>> 8))) {
                        break;
                    }
                    end += (int) (decoded & 0xFF);
                }
                yield end > from
                        ? new Result(TypedValue.of(Arrays.copyOfRange(data, from, end)), end - from)
                        : null;
            }
            case MatchStep.TakeUntil takeUntil -> {
                final byte[] needle = encoding.encode(takeUntil.pattern());
                if (needle.length == 0) {
                    yield null;
                }
                final int found = indexOf(data, from, to, needle);
                if (found < 0) {
                    yield null;
                }
                final int end = takeUntil.inclusive() ? found + needle.length : found;
                yield new Result(TypedValue.of(Arrays.copyOfRange(data, from, end)), end - from);
            }
            case MatchStep.TakeBytes takeBytes -> take(count(takeBytes.count(), prior, local), data, from, to);
            case MatchStep.TakeN takeN -> take(takeN.count(), data, from, to);
            case MatchStep.AnyChar ignored -> {
                if (available <= 0) {
                    yield null;
                }
                // A character is what the effective encoding says it is — the same decode
                // TakeWhile uses, so a UTF-16 character is two bytes and a RAW byte is one.
                final long decoded = decode(data, from, to, encoding);
                if (decoded < 0) {
                    yield null;
                }
                final int length = (int) (decoded & 0xFF);
                yield new Result(TypedValue.of(Arrays.copyOfRange(data, from, from + length)), length);
            }
            case MatchStep.ReadNumeric numeric -> number(numeric, data, from, to);
            case MatchStep.ReadVarint ignored -> {
                final long[] varint = varint(data, from, to);
                yield varint == null ? null : new Result(unsigned(varint[0]), (int) varint[1]);
            }
            case MatchStep.ReadVarintZigZag ignored -> {
                final long[] varint = varint(data, from, to);
                // ZigZag interleaves positive and negative so that small negatives stay small:
                // the low bit is the sign, the rest is the magnitude.
                yield varint == null
                        ? null
                        : new Result(new TypedValue.Int((varint[0] >>> 1) ^ -(varint[0] & 1)),
                        (int) varint[1]);
            }
            case MatchStep.Seek seek -> {
                final Integer count = count(seek.count(), prior, local);
                yield count == null || count > available ? null : new Result(NOTHING, count);
            }
            // Reached only inside a combinator, where going backwards cannot be expressed. The
            // top-level sequence handles both seeks itself.
            case MatchStep.SeekAbs seek -> {
                final Integer target = count(seek.offset(), prior, local);
                if (target == null || target < position) {
                    yield null;
                }
                final int forward = target - position;
                yield forward > available ? null : new Result(NOTHING, forward);
            }
            case MatchStep.SeekBack ignored -> null;
            case MatchStep.Tell ignored -> new Result(new TypedValue.Int(position), 0);
            case MatchStep.Decode decode -> {
                final byte[] input = bytes(decode.data(), prior, local);
                if (input == null) {
                    yield null;
                }
                final byte[] decoded = Codecs.decode(input, decode.codec());
                yield decoded == null ? null : new Result(TypedValue.of(decoded), 0);
            }
            case MatchStep.Encode encode -> {
                final byte[] input = bytes(encode.data(), prior, local);
                if (input == null) {
                    yield null;
                }
                final byte[] encoded = Codecs.encode(input, encode.codec());
                yield encoded == null ? null : new Result(TypedValue.of(encoded), 0);
            }
            case MatchStep.Regex regex -> {
                final BytePattern pattern = patterns.get(
                        PatternKey.of(regex.pattern(), regex.flags(), RegexEncodings.forMatch(encoding)));
                if (pattern == null) {
                    throw new IllegalStateException("Pattern was not compiled: " + regex.pattern());
                }
                final ByteMatcher matcher = pattern.matcher();
                // An atom, like every other step: it matches at the cursor or it fails (E4).
                if (!matcher.match(data, from, to, Anchoring.ANCHORED)) {
                    yield null;
                }
                final byte[] matched = matcher.groupBytes(0);
                yield new Result(TypedValue.of(matched), matched.length);
            }
            case MatchStep.Choice choice -> {
                for (final List<MatchStep> alternative : choice.alternatives()) {
                    final Integer consumed = sequence(
                            alternative, data, from, to, prior, local, position, patterns, encoding);
                    if (consumed != null) {
                        yield consumed(data, from, consumed);
                    }
                }
                yield null;
            }
            case MatchStep.Optional optional -> {
                final Integer consumed = sequence(
                        optional.steps(), data, from, to, prior, local, position, patterns, encoding);
                yield consumed(data, from, consumed == null ? 0 : consumed);
            }
            case MatchStep.Repeat repeat -> {
                int total = 0;
                int iterations = 0;
                final int max = repeat.max() == null ? Integer.MAX_VALUE : repeat.max();
                while (iterations < max && from + total < to) {
                    final Integer consumed = sequence(
                            repeat.steps(), data, from + total, to, prior, local, position + total, patterns, encoding);
                    if (consumed == null || consumed == 0) {
                        break;
                    }
                    total += consumed;
                    iterations++;
                }
                yield iterations >= repeat.min() ? consumed(data, from, total) : null;
            }
            case MatchStep.Sequence nested -> {
                final Integer consumed = sequence(
                        nested.steps(), data, from, to, prior, local, position, patterns, encoding);
                yield consumed == null ? null : consumed(data, from, consumed);
            }
            case MatchStep.Peek peek -> sequence(
                    peek.steps(), data, from, to, prior, local, position, patterns, encoding) == null
                    ? null
                    : new Result(NOTHING, 0);
            case MatchStep.Not not -> sequence(
                    not.steps(), data, from, to, prior, local, position, patterns, encoding) == null
                    ? new Result(NOTHING, 0)
                    : null;
            case MatchStep.PatternRef ignored -> throw new IllegalStateException(
                    "Pattern references should have been resolved at compile time");
        };
    }

    /**
     * Run a nested sequence, returning how much it consumed or null if it failed.
     *
     * <p>The nested steps see everything produced so far — the enclosing sequences' outputs
     * and the caller's own — as one flat list. That is the rule a step reference indexes into:
     * output indexes count all step outputs in execution order, at any nesting depth.
     */
    private static Integer sequence(final List<MatchStep> steps,
                                    final byte[] data,
                                    final int from,
                                    final int to,
                                    final List<TypedValue> enclosing,
                                    final List<TypedValue> callerLocal,
                                    final int position,
                                    final Map<PatternKey, BytePattern> patterns,
                                    final Encoding encoding) {
        final List<TypedValue> prior = concat(enclosing, callerLocal);
        int pos = 0;
        final List<TypedValue> local = new ArrayList<>(steps.size());
        for (final MatchStep step : steps) {
            final Result result = step(step, data, from + pos, to, prior, local, position + pos, patterns, encoding);
            if (result == null) {
                return null;
            }
            pos += result.consumed();
            local.add(result.output());
        }
        return pos;
    }

    /**
     * Two output lists as one, copying only when both have content — which only a doubly
     * nested combinator ever asks for, so the common paths stay allocation-free.
     */
    private static List<TypedValue> concat(final List<TypedValue> prior, final List<TypedValue> local) {
        if (local.isEmpty()) {
            return prior;
        }
        if (prior.isEmpty()) {
            return local;
        }
        final List<TypedValue> all = new ArrayList<>(prior.size() + local.size());
        all.addAll(prior);
        all.addAll(local);
        return all;
    }

    // -----------------------------------------------------------------------------------
    // Pieces
    // -----------------------------------------------------------------------------------

    private static Result consumed(final byte[] data, final int from, final int length) {
        return new Result(TypedValue.of(Arrays.copyOfRange(data, from, from + length)), length);
    }

    private static Result take(final Integer count, final byte[] data, final int from, final int to) {
        if (count == null || count < 0 || count > to - from) {
            return null;
        }
        return new Result(TypedValue.of(Arrays.copyOfRange(data, from, from + count)), count);
    }

    /** A step reference: a number written down, or one an earlier step produced. */
    private static Integer count(final StepRef reference, final List<TypedValue> outputs) {
        return count(reference, outputs, List.of());
    }

    private static Integer count(final StepRef reference,
                                 final List<TypedValue> prior,
                                 final List<TypedValue> local) {
        return switch (reference) {
            case StepRef.Literal literal -> literal.value();
            case StepRef.StepOutput output -> {
                final TypedValue value = at(output.index(), prior, local);
                if (value == null) {
                    yield null;
                }
                final Double number = value.asNumber();
                yield number == null || number < 0 ? null : (int) (double) number;
            }
        };
    }

    private static byte[] bytes(final StepRef reference,
                                final List<TypedValue> prior,
                                final List<TypedValue> local) {
        if (reference instanceof StepRef.StepOutput output) {
            final TypedValue value = at(output.index(), prior, local);
            return value == null ? null : value.asBytes();
        }
        // A literal is a count, not content; there is nothing for a codec to work on.
        return null;
    }

    private static TypedValue at(final int index, final List<TypedValue> prior, final List<TypedValue> local) {
        if (index < prior.size()) {
            return prior.get(index);
        }
        final int offset = index - prior.size();
        return offset < local.size() ? local.get(offset) : null;
    }

    /** Predicates classify decoded codepoints; the encoding story lives at {@link #decode}. */
    private static boolean matches(final Predicate predicate, final int codepoint) {
        return switch (predicate) {
            case Predicate.Alphabetic ignored -> Character.isLetter(codepoint);
            case Predicate.Alphanumeric ignored -> Character.isLetterOrDigit(codepoint);
            case Predicate.Numeric ignored -> Character.isDigit(codepoint);
            case Predicate.Whitespace ignored -> Character.isWhitespace(codepoint);
            case Predicate.NonWhitespace ignored -> !Character.isWhitespace(codepoint);
            case Predicate.Any ignored -> true;
            case Predicate.Custom custom -> codepoint <= Character.MAX_VALUE
                                            && inSet(custom.charSet(), (char) codepoint);
        };
    }

    /**
     * The character at an offset under an encoding, packed as {@code codepoint << 8 | length},
     * or −1 when the bytes there do not form one. RAW and ASCII stay byte-shaped: values past
     * 0x7F carry no textual meaning and fail every class except {@code Any}, exactly as the
     * old byte predicates behaved — {@code Any} never reaches here needing more than length 1.
     */
    private static long decode(final byte[] data, final int at, final int to, final Encoding encoding) {
        final int b = data[at] & 0xFF;
        if (encoding == Encoding.RAW || encoding == Encoding.ASCII) {
            return b <= 0x7F ? ((long) b << 8) | 1 : ((long) 0xFFFD << 8) | 1;
        }
        if (encoding.isUtf8Compatible()) {
            if (b <= 0x7F) {
                return ((long) b << 8) | 1;
            }
            final int length = characterLength(data[at]);
            if (length <= 1 || at + length > to) {
                return -1;
            }
            int cp = b & (0x3F >> (length - 1));
            for (int i = 1; i < length; i++) {
                if ((data[at + i] & 0xC0) != 0x80) {
                    return -1;
                }
                cp = (cp << 6) | (data[at + i] & 0x3F);
            }
            return ((long) cp << 8) | length;
        }
        if (encoding.isSingleByte()) {
            final char[] table = SINGLE_BYTE.computeIfAbsent(encoding, e -> {
                final char[] chars = new char[256];
                final byte[] one = new byte[1];
                for (int i = 0; i < 256; i++) {
                    one[0] = (byte) i;
                    final String text = new String(one, e.charset());
                    chars[i] = text.isEmpty() ? 0xFFFD : text.charAt(0);
                }
                return chars;
            });
            return ((long) table[b] << 8) | 1;
        }
        // Multi-byte non-UTF-8 encodings (UTF-16, Shift_JIS…): decode one character via the
        // charset. Correct first, fast when a workload asks.
        for (int length = 1; length <= Math.min(4, to - at); length++) {
            final String text = new String(data, at, length, encoding.charset());
            if (!text.isEmpty() && text.charAt(0) != 0xFFFD) {
                return ((long) text.codePointAt(0) << 8) | length;
            }
        }
        return -1;
    }

    private static boolean inSet(final Predicate.CharSet set, final char c) {
        boolean present = set.chars().contains(c);
        if (!present) {
            for (final Predicate.CharSet.Range range : set.ranges()) {
                if (c >= range.from() && c <= range.to()) {
                    present = true;
                    break;
                }
            }
        }
        return set.negated() != present;
    }

    /** How many bytes the UTF-8 character starting with this byte occupies. */
    private static int characterLength(final byte first) {
        final int b = first & 0xFF;
        if (b < 0x80) {
            return 1;
        }
        if (b < 0xE0) {
            return 2;
        }
        if (b < 0xF0) {
            return 3;
        }
        return 4;
    }

    private static boolean startsWith(final byte[] data, final int from, final int to, final byte[] prefix) {
        if (from + prefix.length > to) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[from + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(final byte[] data, final int from, final int to, final byte[] needle) {
        for (int i = from; i + needle.length <= to; i++) {
            if (startsWith(data, i, to, needle)) {
                return i;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------------------
    // Numbers
    // -----------------------------------------------------------------------------------

    private static Result number(final MatchStep.ReadNumeric numeric, final byte[] data,
                                 final int from, final int to) {
        final int size = switch (numeric.numericType()) {
            case SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
        };
        if (to - from < size) {
            return null;
        }

        final boolean big = numeric.endian() == Endianness.BIG;
        long raw = 0;
        for (int i = 0; i < size; i++) {
            final int b = data[from + (big ? i : size - 1 - i)] & 0xFF;
            raw = (raw << 8) | b;
        }

        final TypedValue value = switch (numeric.numericType()) {
            case SHORT -> new TypedValue.Int(numeric.signed() ? (short) raw : raw);
            case INT -> new TypedValue.Int(numeric.signed() ? (int) raw : raw);
            case LONG -> numeric.signed() ? new TypedValue.Int(raw) : unsigned(raw);
            case FLOAT -> new TypedValue.Real(Float.intBitsToFloat((int) raw));
            case DOUBLE -> new TypedValue.Real(Double.longBitsToDouble(raw));
        };
        return new Result(value, size);
    }

    /**
     * An unsigned 64-bit value, which Java has no type for.
     *
     * <p>Below the signed maximum it is just a number. Above it, the only lossless thing to do is
     * write it out — so it becomes text rather than a silently negative long.
     */
    private static TypedValue unsigned(final long raw) {
        return raw >= 0
                ? new TypedValue.Int(raw)
                : TypedValue.of(Long.toUnsignedString(raw).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Read an LEB128 varint: seven bits a byte, high bit meaning "there is more".
     *
     * @return the value and how many bytes it took, or null if it is truncated or absurd
     */
    private static long[] varint(final byte[] data, final int from, final int to) {
        long result = 0;
        int shift = 0;
        for (int i = 0; from + i < to; i++) {
            if (i >= 10) {
                // Ten bytes of seven bits is more than 64, so this is not a varint.
                return null;
            }
            final int b = data[from + i] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{result, i + 1};
            }
            shift += 7;
        }
        return null;
    }
}
