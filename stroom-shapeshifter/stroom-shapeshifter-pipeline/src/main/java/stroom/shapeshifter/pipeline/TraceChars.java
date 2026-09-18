/*
 * Copyright 2016 Crown Copyright
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

package stroom.shapeshifter.pipeline;

import stroom.shapeshifter.engine.Instrument;
import stroom.shapeshifter.engine.TraceRecorder;
import stroom.shapeshifter.shared.ShapeshifterTrace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The trace as the client reads it: every offset in characters of the text it is an offset
 * into, because the engine counts bytes and the client holds strings. The server has the bytes
 * of every frame's content — the input, a slice of a parent's, or the bytes a non-slice frame
 * reported — so it is the server that converts, once, and the wire carries characters.
 *
 * <p>Text is UTF-8 throughout the preview; a byte offset inside a multi-byte sequence rounds to
 * the character it is in.
 */
final class TraceChars {

    private final TraceRecorder recorder;
    private final byte[] input;
    private final byte[] output;
    private final int[] inputChars;
    private final int[] outputChars;
    private final Map<Long, TraceRecorder.Frame> framesById = new HashMap<>();
    private final Map<Long, byte[]> contentBytes = new HashMap<>();
    private final Map<Long, int[]> contentChars = new HashMap<>();

    TraceChars(final TraceRecorder recorder, final byte[] input, final byte[] output) {
        this.recorder = recorder;
        this.input = input;
        this.output = output;
        this.inputChars = charIndex(input);
        this.outputChars = charIndex(output);
        for (final TraceRecorder.Frame frame : recorder.frames()) {
            framesById.put(frame.id(), frame);
        }
    }

    /** For each byte offset 0..n, the character offset it falls at. */
    static int[] charIndex(final byte[] bytes) {
        final int[] index = new int[bytes.length + 1];
        int chars = 0;
        for (int i = 0; i < bytes.length; i++) {
            index[i] = chars;
            final int b = bytes[i] & 0xff;
            // A continuation byte (10xxxxxx) does not start a character; everything else does,
            // and a UTF-16 surrogate pair - a 4-byte sequence - counts as two characters.
            if ((b & 0xC0) != 0x80) {
                chars += b >= 0xF0
                        ? 2
                        : 1;
            }
        }
        index[bytes.length] = chars;
        return index;
    }

    private static int at(final int[] index, final long byteOffset) {
        if (byteOffset < 0) {
            return -1;
        }
        return index[(int) Math.min(byteOffset, index.length - 1)];
    }

    /** A frame's content bytes: the input for the document, a slice of the parent's, or its own. */
    private byte[] bytesOf(final long frameId) {
        if (frameId == Instrument.ROOT_FRAME) {
            return input;
        }
        return contentBytes.computeIfAbsent(frameId, id -> {
            final TraceRecorder.Frame frame = framesById.get(id);
            final byte[] own = recorder.contents().get(id);
            if (own != null || frame == null || frame.contentOffset() == Instrument.NOT_A_SLICE) {
                return own == null
                        ? new byte[0]
                        : own;
            }
            final byte[] parent = bytesOf(frame.parentId());
            final int from = Math.max(0, Math.min(frame.contentOffset(), parent.length));
            final int to = Math.max(from, Math.min(from + frame.contentLength(), parent.length));
            return Arrays.copyOfRange(parent, from, to);
        });
    }

    private int[] charsOf(final long frameId) {
        if (frameId == Instrument.ROOT_FRAME) {
            return inputChars;
        }
        return contentChars.computeIfAbsent(frameId, id -> charIndex(bytesOf(id)));
    }

    List<ShapeshifterTrace.Frame> frames() {
        final List<ShapeshifterTrace.Frame> out = new ArrayList<>(recorder.frames().size());
        for (final TraceRecorder.Frame f : recorder.frames()) {
            final boolean slice = f.contentOffset() != Instrument.NOT_A_SLICE;
            final int[] parentChars = charsOf(f.parentId());
            final int contentOffset = slice
                    ? at(parentChars, f.contentOffset())
                    : ShapeshifterTrace.NOT_A_SLICE;
            final int contentLength = slice
                    ? at(parentChars, f.contentOffset() + f.contentLength()) - contentOffset
                    : at(charsOf(f.id()), bytesOf(f.id()).length);
            final boolean located = f.inputOffset() < Instrument.UNLOCATABLE;
            final byte[] own = recorder.contents().get(f.id());
            out.add(new ShapeshifterTrace.Frame(f.id(), f.parentId(), f.templateId(), f.templateName(),
                    f.matchIndex(), f.depth(), located
                    ? at(inputChars, f.inputOffset())
                    : -1, located
                    ? at(inputChars, f.inputOffset() + f.inputLength()) - at(inputChars, f.inputOffset())
                    : 0, contentOffset, contentLength, own == null
                    ? null
                    : new String(own, StandardCharsets.UTF_8)));
        }
        return out;
    }

    List<ShapeshifterTrace.Capture> captures() {
        final List<ShapeshifterTrace.Capture> out = new ArrayList<>(recorder.captures().size());
        for (final TraceRecorder.Capture c : recorder.captures()) {
            final boolean placed = c.contentOffset() != Instrument.NOT_A_SLICE;
            final int[] chars = placed
                    ? charsOf(c.frameId())
                    : null;
            final int from = placed
                    ? at(chars, c.contentOffset())
                    : ShapeshifterTrace.NOT_A_SLICE;
            out.add(new ShapeshifterTrace.Capture(c.frameId(), c.name(), c.value(), c.type(), from, placed
                    ? at(chars, c.contentOffset() + c.contentLength()) - from
                    : 0));
        }
        return out;
    }

    List<ShapeshifterTrace.OutputSpan> outputs() {
        final List<ShapeshifterTrace.OutputSpan> out = new ArrayList<>(recorder.outputs().size());
        for (final TraceRecorder.OutputSpan o : recorder.outputs()) {
            if (o.unit() == stroom.shapeshifter.engine.OutputSink.Unit.BYTES) {
                final int from = at(outputChars, o.offset());
                out.add(new ShapeshifterTrace.OutputSpan(o.frameId(), from,
                        at(outputChars, o.offset() + o.length()) - from, o.unit().name()));
            } else {
                out.add(new ShapeshifterTrace.OutputSpan(o.frameId(), o.offset(), o.length(), o.unit().name()));
            }
        }
        return out;
    }

    List<ShapeshifterTrace.Guard> guards() {
        final List<ShapeshifterTrace.Guard> out = new ArrayList<>(recorder.guards().size());
        for (final TraceRecorder.Guard g : recorder.guards()) {
            out.add(new ShapeshifterTrace.Guard(g.parentFrameId(), g.templateId(), g.allowed()));
        }
        return out;
    }

    List<ShapeshifterTrace.Instruction> instructions() {
        final List<ShapeshifterTrace.Instruction> out = new ArrayList<>(recorder.instructions().size());
        for (final TraceRecorder.Instruction i : recorder.instructions()) {
            if (i.unit() == stroom.shapeshifter.engine.OutputSink.Unit.BYTES) {
                final int from = at(outputChars, i.offset());
                out.add(new ShapeshifterTrace.Instruction(i.frameId(), i.index(), from,
                        at(outputChars, i.offset() + i.length()) - from, i.unit().name()));
            } else {
                out.add(new ShapeshifterTrace.Instruction(i.frameId(), i.index(), i.offset(), i.length(),
                        i.unit().name()));
            }
        }
        return out;
    }

    List<ShapeshifterTrace.Attempt> attempts() {
        final List<ShapeshifterTrace.Attempt> out = new ArrayList<>(recorder.attempts().size());
        for (final TraceRecorder.Attempt a : recorder.attempts()) {
            final boolean located = a.inputOffset() < Instrument.UNLOCATABLE;
            out.add(new ShapeshifterTrace.Attempt(a.parentFrameId(), a.templateId(), a.matched(), located
                    ? at(inputChars, a.inputOffset())
                    : -1, a.contentOffset() == Instrument.NOT_A_SLICE
                    ? ShapeshifterTrace.NOT_A_SLICE
                    : at(charsOf(a.parentFrameId()), a.contentOffset()), a.nanos()));
        }
        return out;
    }
}
