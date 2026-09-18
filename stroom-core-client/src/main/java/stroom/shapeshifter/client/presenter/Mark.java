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

import java.util.Comparator;

/**
 * A marked run of a text the panes render (design 18 §5.3, §5.4): a child match, a capture's
 * tint, an instruction's output, a gap where nothing matched, or the cursor's own extent.
 * Marks nest — a capture inside a match inside the cursor's content — so a pane sorts them
 * outer first and emits them as a tree; a mark that straddles another's end is clipped to it,
 * since text has no overlaps. A mark names what it is of, so that pointing at it can be
 * answered everywhere ({@link Hot}).
 */
public final class Mark {

    public enum Kind {
        /** What lies outside the cursor's extent, in the output: dimmed. */
        DIM,
        /** The cursor's own extent, in the output. */
        OWN,
        /** What one top-level instruction of the cursor's body wrote: the card's hue. */
        INSTRUCTION,
        /** A child frame's content: its template's colour; a click descends. */
        MATCH,
        /** A capture's bytes: its hue; the text itself is coloured. */
        CAPTURE,
        /** A place a template was tried and none matched: zero length. */
        GAP
    }

    /**
     * Outer first: by start, then the longer, then by kind — the cursor's extent outside a
     * match that fills it, a match outside the capture that fills it.
     */
    public static final Comparator<Mark> OUTER_FIRST = (a, b) -> {
        if (a.start != b.start) {
            return Integer.compare(a.start, b.start);
        }
        if (a.end != b.end) {
            return Integer.compare(b.end, a.end);
        }
        return Integer.compare(a.kind.ordinal(), b.kind.ordinal());
    };

    private final Kind kind;
    private final long frameId;
    private final int index;
    private final String templateId;
    private final int start;
    private final int end;
    private final String colour;
    private final String title;
    private final String flag;

    public Mark(final Kind kind, final long frameId, final int index, final String templateId, final int start,
                final int end, final String colour, final String title) {
        this(kind, frameId, index, templateId, start, end, colour, title, null);
    }

    public Mark(final Kind kind, final long frameId, final int index, final String templateId, final int start,
                final int end, final String colour, final String title, final String flag) {
        this.kind = kind;
        this.frameId = frameId;
        this.index = index;
        this.templateId = templateId;
        this.start = start;
        this.end = end;
        this.colour = colour;
        this.title = title;
        this.flag = flag;
    }

    public Kind getKind() {
        return kind;
    }

    /** The frame: the one a click descends to, for a match; the one the capture or instruction belongs to. */
    public long getFrameId() {
        return frameId;
    }

    /** The capture's or instruction's index in its frame; -1 otherwise. */
    public int getIndex() {
        return index;
    }

    /** The match's template, or null. */
    public String getTemplateId() {
        return templateId;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public String getColour() {
        return colour;
    }

    public String getTitle() {
        return title;
    }

    /**
     * A word the stylesheet paints the mark by — a severity: {@code warning}, {@code error},
     * {@code fatal} — or null.
     */
    public String getFlag() {
        return flag;
    }

    /** The flag for a severity rank, or null below warning. */
    public static String flagOf(final int rank) {
        switch (rank) {
            case 2:
                return "warning";
            case 3:
                return "error";
            case 4:
                return "fatal";
            default:
                return null;
        }
    }
}
