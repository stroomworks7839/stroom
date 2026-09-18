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

import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Attempt;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
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
    private final Map<Long, OutputSpan> outputs = new HashMap<>();
    private final Map<Long, List<Attempt>> attempts = new HashMap<>();
    private final Map<String, Timing> timings = new HashMap<>();
    private final Map<Long, String> contents = new HashMap<>();

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
        for (final OutputSpan span : list(trace.getOutputs())) {
            outputs.put(span.getFrameId(), span);
        }
        for (final Attempt attempt : list(trace.getAttempts())) {
            attempts.computeIfAbsent(attempt.getParentFrameId(), k -> new ArrayList<>()).add(attempt);
        }
        for (final Timing timing : list(trace.getTimings())) {
            timings.put(timing.getTemplateId(), timing);
        }
    }

    private static <T> List<T> list(final List<T> list) {
        return list == null
                ? List.of()
                : list;
    }

    public ShapeshifterTrace trace() {
        return trace;
    }

    public boolean isCompiled() {
        return trace.isCompiled();
    }

    /** The frame, or null; the document is not a frame object, so null for {@link #ROOT} too. */
    public Frame frame(final long id) {
        return byId.get(id);
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

    public OutputSpan output(final long frameId) {
        return outputs.get(frameId);
    }

    /** The attempts made while this frame's body dispatched, in order. */
    public List<Attempt> attempts(final long frameId) {
        return attempts.getOrDefault(frameId, List.of());
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
