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

package stroom.shapeshifter.client.presenter;

import stroom.shapeshifter.shared.ShapeshifterMessage;
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Attempt;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.shapeshifter.shared.ShapeshifterTrace.Group;
import stroom.shapeshifter.shared.ShapeshifterTrace.Guard;
import stroom.shapeshifter.shared.ShapeshifterTrace.Instruction;
import stroom.shapeshifter.shared.ShapeshifterTrace.OutputSpan;
import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A trace as the navigator reads it (design 18 §5.1, §5.2): the frames as a tree by id, with
 * the document as frame zero; each frame's content as text — the input for the document, a
 * slice of the parent's for a slice, its own for the rest; captures, output spans and attempts
 * by frame; timings by template. Every offset is in characters (the server converted).
 */
public final class TraceModel {

    public static final long ROOT = 0;

    private final ShapeshifterTrace trace;
    private final Map<Long, Frame> byId = new HashMap<>();
    private final Map<Long, List<Frame>> children = new HashMap<>();
    private final Map<String, List<Frame>> byTemplate = new HashMap<>();
    private final Map<Long, List<Capture>> captures = new HashMap<>();
    private final Map<Long, List<Group>> groups = new HashMap<>();
    private final Map<Long, OutputSpan> outputs = new HashMap<>();
    private final Map<Long, List<Attempt>> attempts = new HashMap<>();
    private final Map<Long, Map<String, Boolean>> guards = new HashMap<>();
    private final Map<String, int[]> guardCounts = new HashMap<>();
    private final Map<Long, List<ShapeshifterMessage>> messages = new HashMap<>();
    private final Map<Long, Integer> worstHere = new HashMap<>();
    private final Map<Long, Integer> worstBelow = new HashMap<>();
    private final Map<String, Integer> worstOfTemplate = new HashMap<>();
    private final Map<Long, List<Instruction>> instructions = new HashMap<>();
    private final Map<String, Timing> timings = new HashMap<>();
    private final Map<Long, String> contents = new HashMap<>();
    private long cursor = ROOT;

    public TraceModel(final ShapeshifterTrace trace) {
        this.trace = trace;
        for (final Frame frame : list(trace.getFrames())) {
            byId.put(frame.getId(), frame);
            children.computeIfAbsent(frame.getParentId(), k -> new ArrayList<>()).add(frame);
            byTemplate.computeIfAbsent(frame.getTemplateId(), k -> new ArrayList<>()).add(frame);
        }
        for (final Capture capture : list(trace.getCaptures())) {
            captures.computeIfAbsent(capture.getFrameId(), k -> new ArrayList<>()).add(capture);
        }
        for (final Group group : list(trace.getGroups())) {
            groups.computeIfAbsent(group.getFrameId(), k -> new ArrayList<>()).add(group);
        }
        for (final OutputSpan span : list(trace.getOutputs())) {
            outputs.put(span.getFrameId(), span);
        }
        for (final Attempt attempt : list(trace.getAttempts())) {
            attempts.computeIfAbsent(attempt.getParentFrameId(), k -> new ArrayList<>()).add(attempt);
        }
        for (final Guard guard : list(trace.getGuards())) {
            guards.computeIfAbsent(guard.getParentFrameId(), k -> new HashMap<>())
                    .put(guard.getTemplateId(), guard.isAllowed());
            guardCounts.computeIfAbsent(guard.getTemplateId(), k -> new int[2])[guard.isAllowed()
                    ? 0
                    : 1]++;
        }
        for (final Instruction instruction : list(trace.getInstructions())) {
            instructions.computeIfAbsent(instruction.getFrameId(), k -> new ArrayList<>()).add(instruction);
        }
        for (final Timing timing : list(trace.getTimings())) {
            timings.put(timing.getTemplateId(), timing);
        }
        // A message's severity is its frame's and its frame's template's, and every ancestor's
        // "below": a document frame showing a row in red is showing that something under it
        // went wrong (design 18 §5.8). The ancestors' templates are not tinted - a field's
        // error is the field template's, not the row's.
        for (final ShapeshifterMessage message : list(trace.getMessages())) {
            if (message.getFrameId() == ShapeshifterMessage.NO_FRAME) {
                continue;
            }
            messages.computeIfAbsent(message.getFrameId(), k -> new ArrayList<>()).add(message);
            final int rank = rank(message.getSeverity());
            worstHere.merge(message.getFrameId(), rank, Math::max);
            final Frame said = byId.get(message.getFrameId());
            if (said != null) {
                worstOfTemplate.merge(said.getTemplateId(), rank, Math::max);
            }
            long at = message.getFrameId();
            while (true) {
                worstBelow.merge(at, rank, Math::max);
                final Frame frame = byId.get(at);
                if (frame == null) {
                    break;
                }
                at = frame.getParentId();
            }
        }
    }

    /** Severity as the engine spells it, ranked: 0 none, 1 info, 2 warning, 3 error, 4 fatal. */
    public static int rank(final String severity) {
        if (severity == null) {
            return 0;
        }
        switch (severity.toUpperCase()) {
            case "INFO":
                return 1;
            case "WARNING":
                return 2;
            case "ERROR":
                return 3;
            case "FATAL":
                return 4;
            default:
                return 0;
        }
    }

    /** The messages said in a frame, in order. */
    public List<ShapeshifterMessage> messages(final long frameId) {
        return messages.getOrDefault(frameId, List.of());
    }

    /** The worst severity said in the frame itself, as {@link #rank}. */
    public int worstHere(final long frameId) {
        return worstHere.getOrDefault(frameId, 0);
    }

    /** The worst severity said in the frame or any frame beneath it. */
    public int worstBelow(final long frameId) {
        return worstBelow.getOrDefault(frameId, 0);
    }

    /** The worst severity said in any frame of the template itself. */
    public int worstOfTemplate(final String templateId) {
        return worstOfTemplate.getOrDefault(templateId, 0);
    }

    private static <T> List<T> list(final List<T> list) {
        return list == null
                ? List.of()
                : list;
    }

    /** The cursor as the host last set it; kept here so a reader of the trace can ask which frame is selected. */
    public long cursorOf() {
        return cursor;
    }

    public void setCursor(final long cursor) {
        this.cursor = cursor;
    }

    public ShapeshifterTrace trace() {
        return trace;
    }

    public boolean isCompiled() {
        return trace.isCompiled();
    }

    /** Whether the sample could be read at all; false is the sample's failure, not the project's. */
    public boolean isSampleRead() {
        return trace.isSampleRead();
    }

    /** The frame, or null; the document is not a frame object, so null for {@link #ROOT} too. */
    public Frame frame(final long id) {
        return byId.get(id);
    }

    /** Whether the run matched anything at all: no frames means no template applied, anywhere. */
    public boolean matchedNothing() {
        return byId.isEmpty();
    }

    public boolean has(final long id) {
        return id == ROOT || byId.containsKey(id);
    }

    public long parent(final long id) {
        final Frame frame = byId.get(id);
        return frame == null
                ? ROOT
                : frame.getParentId();
    }

    public List<Frame> children(final long id) {
        return children.getOrDefault(id, List.of());
    }

    /** The frame's ancestry from the document down to itself: {@code [0, ..., id]}. */
    public List<Long> path(final long id) {
        final List<Long> path = new ArrayList<>();
        long at = id;
        while (at != ROOT) {
            path.add(0, at);
            final Frame frame = byId.get(at);
            if (frame == null) {
                break;
            }
            at = frame.getParentId();
        }
        path.add(0, ROOT);
        return path;
    }

    public List<Frame> matches(final String templateId) {
        return byTemplate.getOrDefault(templateId, List.of());
    }

    public List<Capture> captures(final long frameId) {
        return captures.getOrDefault(frameId, List.of());
    }

    /** The groups of a frame's match, by number, whether or not a capture bound them. */
    public List<Group> groups(final long frameId) {
        return groups.getOrDefault(frameId, List.of());
    }

    public OutputSpan output(final long frameId) {
        return outputs.get(frameId);
    }

    /** The attempts made while this frame's body dispatched, in order. */
    public List<Attempt> attempts(final long frameId) {
        return attempts.getOrDefault(frameId, List.of());
    }

    /**
     * The first frame whose body tried a template, or -1: where the template was dispatched,
     * whether or not it matched there. What the workbench's sample is seeded from, since a
     * template is tried against its parent's content, not against what it matched.
     */
    public long firstTriedIn(final String templateId) {
        for (final Attempt attempt : list(trace.getAttempts())) {
            if (attempt.getTemplateId().equals(templateId)) {
                return attempt.getParentFrameId();
            }
        }
        return -1;
    }

    /** The guard's verdict for a template as this frame's body dispatched, or null when it was not read there. */
    public Boolean guard(final long parentFrameId, final String templateId) {
        final Map<String, Boolean> verdicts = guards.get(parentFrameId);
        return verdicts == null
                ? null
                : verdicts.get(templateId);
    }

    /** How often a template's guard held and was refused over the run: {@code {held, refused}}. */
    public int[] guardCounts(final String templateId) {
        final int[] counts = guardCounts.get(templateId);
        return counts == null
                ? new int[2]
                : new int[]{counts[0], counts[1]};
    }

    /** What each top-level instruction of the frame's body wrote, in execution order. */
    public List<Instruction> instructions(final long frameId) {
        return instructions.getOrDefault(frameId, List.of());
    }

    public Timing timing(final String templateId) {
        return timings.get(templateId);
    }

    /** A frame's content as text; the document's is the input. */
    public String content(final long id) {
        if (id == ROOT) {
            return trace.getInput() == null
                    ? ""
                    : trace.getInput();
        }
        final String cached = contents.get(id);
        if (cached != null) {
            return cached;
        }
        final Frame frame = byId.get(id);
        if (frame == null) {
            return "";
        }
        final String content;
        if (frame.getContent() != null) {
            content = frame.getContent();
        } else if (frame.getContentOffset() == ShapeshifterTrace.NOT_A_SLICE) {
            content = "";
        } else {
            final String parent = content(frame.getParentId());
            final int from = Math.max(0, Math.min(frame.getContentOffset(), parent.length()));
            final int to = Math.max(from, Math.min(from + frame.getContentLength(), parent.length()));
            content = parent.substring(from, to);
        }
        contents.put(id, content);
        return content;
    }

    /** What a frame is called in the crumb and the header: {@code row #2}, or {@code document}. */
    public String label(final long id) {
        final Frame frame = byId.get(id);
        return frame == null
                ? "document"
                : frame.getTemplateName() + " #" + frame.getMatchIndex();
    }
}
