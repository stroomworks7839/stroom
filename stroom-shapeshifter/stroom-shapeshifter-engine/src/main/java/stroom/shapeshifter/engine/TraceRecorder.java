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

package stroom.shapeshifter.engine;

import stroom.shapeshifter.engine.value.TypedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link Instrument} that keeps everything it is told, which is what an editor wants
 * (design 18 §4, §5.8): every frame with its parent and its content's place, every capture
 * with its typed value, every output span, every attempt with where it was tried, and the
 * per-template timing that every run carries because profiling is not a separate request.
 *
 * <p>Attempts are the one thing that can grow without bound — every position times every
 * candidate — so they are kept up to a cap and counted beyond it; the timing totals count
 * them all.
 */
public final class TraceRecorder implements Instrument {

    /** How many attempts are kept in full; the totals in {@link Timing} count every one. */
    public static final int ATTEMPT_CAP = 20_000;

    private final List<Frame> frames = new ArrayList<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<OutputSpan> outputs = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private final List<Guard> guards = new ArrayList<>();
    private final List<Instruction> instructions = new ArrayList<>();
    private final List<Said> said = new ArrayList<>();
    private final Map<String, Timing> timings = new LinkedHashMap<>();
    private final Map<Long, byte[]> contents = new LinkedHashMap<>();
    private long attemptsSeen;

    /** A frame: a match, with where it sits in the input and in its parent's content. */
    public record Frame(long id,
                        long parentId,
                        String templateId,
                        String templateName,
                        long inputOffset,
                        int inputLength,
                        int contentOffset,
                        int contentLength,
                        int matchIndex,
                        int depth) {

    }

    /** A capture bound in a frame, with its value's doc-17 type as the variable pane badges it. */
    public record Capture(long frameId, String name, String value, String type, int matchIndex,
                          int contentOffset, int contentLength) {

    }

    public record OutputSpan(long frameId, String templateId, int matchIndex, long offset, long length,
                             OutputSink.Unit unit) {

    }

    /** Something the engine said, in the frame it was in. */
    public record Said(long frameId, Message message) {

    }

    /** A guard's verdict for a template, as a frame's body began dispatching. */
    public record Guard(long parentFrameId, String templateId, boolean allowed) {

    }

    /** What the top-level instruction at an index of a frame's body wrote. */
    public record Instruction(long frameId, int index, long offset, long length, OutputSink.Unit unit) {

    }

    /** An attempt to match a template at a place, and whether it did. */
    public record Attempt(long parentFrameId, String templateId, boolean matched, long inputOffset,
                          int contentOffset, long nanos) {

    }

    /** A template's totals over the run: attempts, matches, time. */
    public static final class Timing {

        private long attempts;
        private long matched;
        private long nanos;

        public long attempts() {
            return attempts;
        }

        public long matched() {
            return matched;
        }

        public long nanos() {
            return nanos;
        }
    }

    @Override
    public void onMatch(final long frameId, final long parentFrameId, final String templateId,
                        final String templateName, final long inputOffset, final int inputLength,
                        final int contentOffset, final int contentLength, final int matchIndex,
                        final int depth) {
        frames.add(new Frame(frameId, parentFrameId, templateId, templateName, inputOffset, inputLength,
                contentOffset, contentLength, matchIndex, depth));
    }

    @Override
    public void onCapture(final long frameId, final String templateId, final String name,
                          final TypedValue value, final int matchIndex, final int contentOffset,
                          final int contentLength) {
        captures.add(new Capture(frameId, name, value.asString(), typeOf(value), matchIndex, contentOffset,
                contentLength));
    }

    @Override
    public void onMatchContent(final long frameId, final byte[] content) {
        contents.put(frameId, content);
    }

    @Override
    public long startTiming() {
        return System.nanoTime();
    }

    @Override
    public void stopTiming(final long parentFrameId, final String templateId, final long token,
                           final boolean matched, final long inputOffset, final int contentOffset) {
        final long nanos = System.nanoTime() - token;
        final Timing timing = timings.computeIfAbsent(templateId, id -> new Timing());
        timing.attempts++;
        timing.nanos += nanos;
        if (matched) {
            timing.matched++;
        }
        attemptsSeen++;
        if (attempts.size() < ATTEMPT_CAP) {
            attempts.add(new Attempt(parentFrameId, templateId, matched, inputOffset, contentOffset, nanos));
        }
    }

    @Override
    public void onMessage(final long frameId, final Message message) {
        said.add(new Said(frameId, message));
    }

    @Override
    public void onGuard(final long parentFrameId, final String templateId, final boolean allowed) {
        guards.add(new Guard(parentFrameId, templateId, allowed));
    }

    @Override
    public void onInstruction(final long frameId, final int index, final long outputOffset,
                              final long outputLength, final OutputSink.Unit unit) {
        instructions.add(new Instruction(frameId, index, outputOffset, outputLength, unit));
    }

    @Override
    public void onOutput(final long frameId, final String templateId, final int matchIndex,
                         final long outputOffset, final long outputLength, final OutputSink.Unit unit) {
        outputs.add(new OutputSpan(frameId, templateId, matchIndex, outputOffset, outputLength, unit));
    }

    /** The doc-17 type of a value, as the variable panes badge it. */
    static String typeOf(final TypedValue value) {
        if (value instanceof TypedValue.Bytes) {
            return "string";
        } else if (value instanceof TypedValue.Integer) {
            return "integer";
        } else if (value instanceof TypedValue.Double) {
            return "double";
        } else if (value instanceof TypedValue.Bool) {
            return "boolean";
        } else if (value instanceof TypedValue.Instant) {
            return "instant";
        } else if (value instanceof TypedValue.List) {
            return "list";
        } else if (value instanceof TypedValue.Map) {
            return "map";
        } else if (value instanceof TypedValue.Set) {
            return "set";
        }
        return "value";
    }

    public List<Frame> frames() {
        return Collections.unmodifiableList(frames);
    }

    public List<Capture> captures() {
        return Collections.unmodifiableList(captures);
    }

    public List<OutputSpan> outputs() {
        return Collections.unmodifiableList(outputs);
    }

    /** The attempts kept, up to the cap; {@link #attemptsSeen()} is how many there were. */
    public List<Attempt> attempts() {
        return Collections.unmodifiableList(attempts);
    }

    public long attemptsSeen() {
        return attemptsSeen;
    }

    /** Every message of the run in order, each with its frame. */
    public List<Said> said() {
        return Collections.unmodifiableList(said);
    }

    public List<Guard> guards() {
        return Collections.unmodifiableList(guards);
    }

    public List<Instruction> instructions() {
        return Collections.unmodifiableList(instructions);
    }

    public Map<String, Timing> timings() {
        return Collections.unmodifiableMap(timings);
    }

    /** The content of each frame that is not a slice of its parent's, by frame id. */
    public Map<Long, byte[]> contents() {
        return Collections.unmodifiableMap(contents);
    }
}
