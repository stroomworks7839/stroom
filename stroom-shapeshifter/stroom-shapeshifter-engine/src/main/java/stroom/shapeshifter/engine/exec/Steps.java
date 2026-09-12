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

package stroom.shapeshifter.engine.exec;

import stroom.shapeshifter.engine.config.Endianness;
import stroom.shapeshifter.engine.config.StepRef;
import stroom.shapeshifter.engine.graph.CompiledStep;
import stroom.shapeshifter.engine.graph.CompiledSteps;
import stroom.shapeshifter.engine.match.Codecs;
import stroom.shapeshifter.engine.match.Decoding;
import stroom.shapeshifter.engine.match.MatchResult;
import stroom.shapeshifter.engine.match.Predicates;
import stroom.shapeshifter.engine.text.Encoding;
import stroom.shapeshifter.engine.value.TypedValue;
import stroom.shapeshifter.regex.Anchoring;
import stroom.shapeshifter.regex.ByteMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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

    /**
     * What a step produced, and how much input it ate doing so.
     */
    private record Result(TypedValue output, int consumed) {

    }

    private static final TypedValue NOTHING = TypedValue.utf8(new byte[0]);

    private Steps() {
    }

    /**
     * Run a whole sequence at a position.
     *
     * <p>Group 0 of the result is everything consumed; group {@code i + 1} is step {@code i}'s
     * output. That numbering is what {@code CaptureSource.Step} indexes into.
     *
     * @param program the compiled steps and the reading they run under
     * @return the match, or null if any step failed
     */
    public static MatchResult match(final CompiledSteps program,
                                    final byte[] data,
                                    final int from,
                                    final int to) {
        final CompiledStep[] steps = program.steps();
        final Decoding decoding = program.decoding();
        final Encoding encoding = program.encoding();
        int pos = 0;
        int highWater = 0;
        final List<TypedValue> outputs = new ArrayList<>(steps.length);

        for (final CompiledStep step : steps) {
            // The two seeks that can move backwards are handled here rather than as ordinary
            // steps, because a step reports how far forward it went and cannot express going
            // back. They need the whole input addressable, which is why a configuration using
            // them has to be run whole rather than in buffers.
            if (step instanceof final CompiledStep.SeekAbs seek) {
                final Integer target = count(seek.offset(), outputs);
                if (target == null || target > to - from) {
                    return null;
                }
                pos = target;
                outputs.add(NOTHING);
            } else if (step instanceof final CompiledStep.SeekBack seek) {
                final Integer back = count(seek.count(), outputs);
                if (back == null || back > pos) {
                    return null;
                }
                pos -= back;
                outputs.add(NOTHING);
            } else {
                final Result result = step(step, data, from + pos, to, outputs, List.of(), pos,
                        decoding, encoding);
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
        groups[0] = TypedValue.of(Arrays.copyOfRange(data, from, from + highWater), encoding);
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
    private static Result step(final CompiledStep step,
                               final byte[] data,
                               final int from,
                               final int to,
                               final List<TypedValue> prior,
                               final List<TypedValue> local,
                               final int position,
                               final Decoding decoding,
                               final Encoding encoding) {
        final int available = to - from;
        return switch (step) {
            // The bytes a tag looks for and the value it produces are both constant once the
            // encoding is known, so a tag that matches allocates nothing. The encoding is the
            // template's, not UTF-8 by fiat: a tag is the same kind of literal as a delimiter,
            // looking for the same kind of bytes (E3).
            case final CompiledStep.Tag tag -> startsWith(data, from, to, tag.bytes())
                    ? new Result(tag.value(), tag.bytes().length)
                    : null;
            case final CompiledStep.MatchByte value -> startsWith(data, from, to, value.bytes())
                    ? new Result(value.value(), value.bytes().length)
                    : null;
            case final CompiledStep.TakeWhile takeWhile -> {
                // E5: the predicate classifies characters, not bytes, and a character is what
                // the effective encoding says it is — a multi-byte UTF-8 letter is a letter,
                // a windows-1252 0xE9 is a letter under that encoding and a stray byte under
                // raw. RAW keeps the ASCII reading: bytes with no declared meaning earn none.
                // D38 rules the regex dialect the same way, so the two vocabularies agree.
                //
                // Which of the three loops applies is a property of the encoding, so it is
                // asked once here rather than once per byte, and the two that have a table
                // answer from it instead of decoding and then classifying.
                final boolean[] table = takeWhile.table();
                int end = from;
                switch (decoding.kind()) {
                    case ASCII_LIKE, SINGLE_BYTE -> {
                        while (end < to && table[data[end] & 0xFF]) {
                            end++;
                        }
                    }
                    case UTF8 -> {
                        while (end < to) {
                            final int b = data[end] & 0xFF;
                            if (b <= 0x7F) {
                                if (!table[b]) {
                                    break;
                                }
                                end++;
                            } else {
                                final long decoded = decode(data, end, to, decoding);
                                if (decoded < 0 || !Predicates.matches(takeWhile.predicate(), (int) (decoded >>> 8))) {
                                    break;
                                }
                                end += (int) (decoded & 0xFF);
                            }
                        }
                    }
                    case MULTI_BYTE -> {
                        while (end < to) {
                            final long decoded = decode(data, end, to, decoding);
                            if (decoded < 0 || !Predicates.matches(takeWhile.predicate(), (int) (decoded >>> 8))) {
                                break;
                            }
                            end += (int) (decoded & 0xFF);
                        }
                    }
                }
                yield end > from
                        ? new Result(TypedValue.of(Arrays.copyOfRange(data, from, end), encoding),
                        end - from)
                        : null;
            }
            case final CompiledStep.TakeUntil takeUntil -> {
                final byte[] needle = takeUntil.needle();
                if (needle.length == 0) {
                    yield null;
                }
                final int found = indexOf(data, from, to, needle);
                if (found < 0) {
                    yield null;
                }
                final int end = takeUntil.inclusive()
                        ? found + needle.length
                        : found;
                yield new Result(TypedValue.of(Arrays.copyOfRange(data, from, end), encoding),
                        end - from);
            }
            case final CompiledStep.TakeBytes takeBytes ->
                    take(count(takeBytes.count(), prior, local), data, from, to, encoding);
            case final CompiledStep.TakeN takeN -> take(takeN.count(), data, from, to, encoding);
            case final CompiledStep.AnyChar ignored -> {
                if (available <= 0) {
                    yield null;
                }
                // A character is what the effective encoding says it is — the same decode
                // TakeWhile uses, so a UTF-16 character is two bytes and a RAW byte is one.
                final long decoded = decode(data, from, to, decoding);
                if (decoded < 0) {
                    yield null;
                }
                final int length = (int) (decoded & 0xFF);
                yield new Result(
                        TypedValue.of(Arrays.copyOfRange(data, from, from + length), encoding),
                        length);
            }
            case final CompiledStep.ReadNumeric numeric -> number(numeric, data, from, to);
            case final CompiledStep.ReadVarint ignored -> {
                final long[] varint = varint(data, from, to);
                yield varint == null
                        ? null
                        : new Result(unsigned(varint[0]), (int) varint[1]);
            }
            case final CompiledStep.ReadVarintZigZag ignored -> {
                final long[] varint = varint(data, from, to);
                // ZigZag interleaves positive and negative so that small negatives stay small:
                // the low bit is the sign, the rest is the magnitude.
                yield varint == null
                        ? null
                        : new Result(new TypedValue.Integer((varint[0] >>> 1) ^ -(varint[0] & 1)),
                                (int) varint[1]);
            }
            case final CompiledStep.Seek seek -> {
                final Integer count = count(seek.count(), prior, local);
                yield count == null || count > available
                        ? null
                        : new Result(NOTHING, count);
            }
            // Reached only inside a combinator, where going backwards cannot be expressed. The
            // top-level sequence handles both seeks itself.
            case final CompiledStep.SeekAbs seek -> {
                final Integer target = count(seek.offset(), prior, local);
                if (target == null || target < position) {
                    yield null;
                }
                final int forward = target - position;
                yield forward > available
                        ? null
                        : new Result(NOTHING, forward);
            }
            case final CompiledStep.SeekBack ignored -> null;
            case final CompiledStep.Tell ignored -> new Result(new TypedValue.Integer(position), 0);
            case final CompiledStep.Decode decode -> {
                final byte[] input = bytes(decode.data(), prior, local);
                if (input == null) {
                    yield null;
                }
                final byte[] decoded = Codecs.decode(input, decode.codec());
                yield decoded == null
                        ? null
                        : new Result(TypedValue.of(decoded, encoding), 0);
            }
            case final CompiledStep.Encode encode -> {
                final byte[] input = bytes(encode.data(), prior, local);
                if (input == null) {
                    yield null;
                }
                final byte[] encoded = Codecs.encode(input, encode.codec());
                yield encoded == null
                        ? null
                        : new Result(TypedValue.of(encoded, encoding), 0);
            }
            case final CompiledStep.Regex regex -> {
                // The pattern was compiled and the matcher built when the step was; neither a
                // lookup nor an allocation stands between the cursor and the question.
                final ByteMatcher matcher = regex.matcher();
                // An atom, like every other step: it matches at the cursor or it fails (E4).
                if (!matcher.match(data, from, to, Anchoring.ANCHORED)) {
                    yield null;
                }
                // Group 0 is the whole match: present whenever match() said yes, so the
                // check states an invariant rather than handling a case.
                final byte[] matched = Objects.requireNonNull(matcher.groupBytes(0));
                yield new Result(TypedValue.of(matched, encoding), matched.length);
            }
            case final CompiledStep.Choice choice -> {
                for (final CompiledStep[] alternative : choice.alternatives()) {
                    final Integer consumed = sequence(
                            alternative, data, from, to, prior, local, position, decoding, encoding);
                    if (consumed != null) {
                        yield consumed(data, from, consumed, encoding);
                    }
                }
                yield null;
            }
            case final CompiledStep.Optional optional -> {
                final Integer consumed = sequence(
                        optional.steps(), data, from, to, prior, local, position, decoding, encoding);
                yield consumed(data,
                        from,
                        consumed == null
                                ? 0
                                : consumed,
                        encoding);
            }
            case final CompiledStep.Repeat repeat -> {
                int total = 0;
                int iterations = 0;
                final int max = repeat.max() == null
                        ? Integer.MAX_VALUE
                        : repeat.max();
                while (iterations < max && from + total < to) {
                    final Integer consumed = sequence(
                            repeat.steps(), data, from + total, to, prior, local, position + total, decoding, encoding);
                    if (consumed == null || consumed == 0) {
                        break;
                    }
                    total += consumed;
                    iterations++;
                }
                yield iterations >= repeat.min()
                        ? consumed(data, from, total, encoding)
                        : null;
            }
            case final CompiledStep.Sequence nested -> {
                final Integer consumed = sequence(
                        nested.steps(), data, from, to, prior, local, position, decoding, encoding);
                yield consumed == null
                        ? null
                        : consumed(data, from, consumed, encoding);
            }
            case final CompiledStep.Peek peek -> sequence(
                    peek.steps(), data, from, to, prior, local, position, decoding, encoding) == null
                    ? null
                    : new Result(NOTHING, 0);
            case final CompiledStep.Not not -> sequence(
                    not.steps(), data, from, to, prior, local, position, decoding, encoding) == null
                    ? new Result(NOTHING, 0)
                    : null;
        };
    }

    /**
     * Run a nested sequence, returning how much it consumed or null if it failed.
     *
     * <p>The nested steps see everything produced so far — the enclosing sequences' outputs
     * and the caller's own — as one flat list. That is the rule a step reference indexes into:
     * output indexes count all step outputs in execution order, at any nesting depth.
     */
    private static Integer sequence(final CompiledStep[] steps,
                                    final byte[] data,
                                    final int from,
                                    final int to,
                                    final List<TypedValue> enclosing,
                                    final List<TypedValue> callerLocal,
                                    final int position,
                                    final Decoding decoding,
                                    final Encoding encoding) {
        final List<TypedValue> prior = concat(enclosing, callerLocal);
        int pos = 0;
        final List<TypedValue> local = new ArrayList<>(steps.length);
        for (final CompiledStep step : steps) {
            final Result result = step(step, data, from + pos, to, prior, local, position + pos,
                    decoding, encoding);
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

    private static Result consumed(final byte[] data,
                                   final int from,
                                   final int length,
                                   final Encoding encoding) {
        return new Result(TypedValue.of(Arrays.copyOfRange(data, from, from + length), encoding),
                length);
    }

    private static Result take(final Integer count,
                               final byte[] data,
                               final int from,
                               final int to,
                               final Encoding encoding) {
        if (count == null || count < 0 || count > to - from) {
            return null;
        }
        return new Result(TypedValue.of(Arrays.copyOfRange(data, from, from + count), encoding),
                count);
    }

    /**
     * A step reference: a number written down, or one an earlier step produced.
     */
    private static Integer count(final StepRef reference, final List<TypedValue> outputs) {
        return count(reference, outputs, List.of());
    }

    private static Integer count(final StepRef reference,
                                 final List<TypedValue> prior,
                                 final List<TypedValue> local) {
        return switch (reference) {
            case final StepRef.Literal literal -> literal.value();
            case final StepRef.StepOutput output -> {
                final TypedValue value = at(output.index(), prior, local);
                if (value == null) {
                    yield null;
                }
                final Double number = value.asNumber();
                yield number == null || number < 0
                        ? null
                        : (int) (double) number;
            }
        };
    }

    private static byte[] bytes(final StepRef reference,
                                final List<TypedValue> prior,
                                final List<TypedValue> local) {
        if (reference instanceof final StepRef.StepOutput output) {
            final TypedValue value = at(output.index(), prior, local);
            return value == null
                    ? null
                    : value.asBytes();
        }
        // A literal is a count, not content; there is nothing for a codec to work on.
        return null;
    }

    private static TypedValue at(final int index, final List<TypedValue> prior, final List<TypedValue> local) {
        if (index < prior.size()) {
            return prior.get(index);
        }
        final int offset = index - prior.size();
        return offset < local.size()
                ? local.get(offset)
                : null;
    }

    /**
     * The character at an offset under the template's reading, packed as
     * {@code codepoint << 8 | length}, or −1 when the bytes there do not form one.
     *
     * <p>Which reading applies was decided when the match compiled ({@link Decoding}); this
     * switches on that answer rather than re-deriving it. RAW and ASCII stay byte-shaped:
     * values past 0x7F carry no textual meaning and fail every class except {@code Any},
     * exactly as the old byte predicates behaved — {@code Any} never reaches here needing more
     * than length 1.
     */
    private static long decode(final byte[] data, final int at, final int to, final Decoding decoding) {
        final int b = data[at] & 0xFF;
        return switch (decoding.kind()) {
            case ASCII_LIKE -> b <= 0x7F
                    ? ((long) b << 8) | 1
                    : ((long) 0xFFFD << 8) | 1;
            case SINGLE_BYTE -> ((long) decoding.table()[b] << 8) | 1;
            case UTF8 -> {
                if (b <= 0x7F) {
                    yield ((long) b << 8) | 1;
                }
                final int length = characterLength(data[at]);
                if (length <= 1 || at + length > to) {
                    yield -1;
                }
                int cp = b & (0x3F >> (length - 1));
                for (int i = 1; i < length; i++) {
                    if ((data[at + i] & 0xC0) != 0x80) {
                        yield -1;
                    }
                    cp = (cp << 6) | (data[at + i] & 0x3F);
                }
                yield ((long) cp << 8) | length;
            }
            // Multi-byte non-UTF-8 encodings (UTF-16, Shift_JIS…): decode one character via the
            // charset. Correct first, fast when a workload asks.
            case MULTI_BYTE -> {
                for (int length = 1; length <= Math.min(4, to - at); length++) {
                    final String text = new String(data, at, length, decoding.charset());
                    if (!text.isEmpty() && text.charAt(0) != 0xFFFD) {
                        yield ((long) text.codePointAt(0) << 8) | length;
                    }
                }
                yield -1;
            }
        };
    }

    /**
     * How many bytes the UTF-8 character starting with this byte occupies.
     */
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

    private static Result number(final CompiledStep.ReadNumeric numeric, final byte[] data,
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
            final int b = data[from + (big
                    ? i
                    : size - 1 - i)] & 0xFF;
            raw = (raw << 8) | b;
        }

        final TypedValue value = switch (numeric.numericType()) {
            case SHORT -> new TypedValue.Integer(numeric.signed()
                    ? (short) raw
                    : raw);
            case INT -> new TypedValue.Integer(numeric.signed()
                    ? (int) raw
                    : raw);
            case LONG -> numeric.signed()
                    ? new TypedValue.Integer(raw)
                    : unsigned(raw);
            case FLOAT -> new TypedValue.Double(Float.intBitsToFloat((int) raw));
            case DOUBLE -> new TypedValue.Double(Double.longBitsToDouble(raw));
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
                ? new TypedValue.Integer(raw)
                : TypedValue.of(Long.toUnsignedString(raw));
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
