/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.client.model;

import stroom.shapeshifter.client.component.Marks;

import java.util.Comparator;

/// A marked run of a text the panes render (design 18 §5.3, §5.4): a child match, a capture's
/// tint, an instruction's output, a gap where nothing matched, or the cursor's own extent.
/// Marks nest — a capture inside a match inside the cursor's content — so a pane sorts them
/// outer first and emits them as a tree; a mark that straddles another's end is clipped to it,
/// since text has no overlaps. A mark names what it is of, so that pointing at it can be
/// answered everywhere ([Hot]).
public final class Mark {

    public enum Kind {
        /// What lies outside the cursor's extent, in the output: dimmed.
        DIM,
        /// The cursor's own extent, in the output.
        OWN,
        /// What one top-level instruction of the cursor's body wrote: the card's hue.
        INSTRUCTION,
        /// A child frame's content: its template's colour; a click descends.
        MATCH,
        /// A capture's bytes: its hue; the text itself is coloured.
        CAPTURE,
        /// What a node of a tree matched, in the workbench: a capture with no colour of its own,
        /// since a node is named by pointing at it, and a hue would name nothing else.
        NODE,
        /// Where something failed: no template matched here, or a tree stopped here. Zero length.
        GAP,
        /// A place the workbench's template was tried and did not match, which on a trial is no
        /// failure: a quieter mark than a gap. Zero length.
        TRIED
    }

    /// Outer first: by start, then the longer, then by kind — the cursor's extent outside a
    /// match that fills it, a match outside the capture that fills it.
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
    private final boolean ruled;
    private final String variable;

    public Mark(final Kind kind, final long frameId, final int index, final String templateId, final int start,
                final int end, final String colour, final String title) {
        this(kind, frameId, index, templateId, start, end, colour, title, false);
    }

    /// A mark that is ruled: a capture of the current level, underlined in its colour.
    ///
    /// @param ruled Whether to draw the rule.
    public Mark(final Kind kind, final long frameId, final int index, final String templateId, final int start,
                final int end, final String colour, final String title, final boolean ruled) {
        this(kind, frameId, index, templateId, start, end, colour, title, ruled, null);
    }

    private Mark(final Kind kind, final long frameId, final int index, final String templateId, final int start,
                 final int end, final String colour, final String title, final boolean ruled,
                 final String variable) {
        this.kind = kind;
        this.frameId = frameId;
        this.index = index;
        this.templateId = templateId;
        this.start = start;
        this.end = end;
        this.colour = colour;
        this.title = title;
        this.ruled = ruled;
        this.variable = variable;
    }

    /// This capture, saying which variable it bound, so that pointing at it lights every capture
    /// of that variable on screen ([stroom.shapeshifter.client.component.CaptureHues#variableOf]).
    ///
    /// @param variable The variable's key.
    /// @return A copy of this mark with the variable.
    public Mark withVariable(final String variable) {
        return new Mark(kind, frameId, index, templateId, start, end, colour, title, ruled, variable);
    }

    /// The variable a capture bound, as `CaptureHues.variableOf` keys it, or null.
    ///
    /// @return The key.
    public String getVariable() {
        return variable;
    }

    /// Whether the mark carries a rule under it (design 18 §5.11.2, ruled 2026-11-16).
    ///
    /// The rule answers one question only — *is this capture one of the current level's?* — and
    /// so it is one bit. Nothing about depth is drawn by it, or by the tint: a capture from
    /// below the cursor is tinted exactly as one at it, and simply not underlined. Three tiers
    /// of rule came before this, and before them three steps of opacity, both of which were
    /// trying to make a mark say where in the hierarchy its bytes came from (design 18 §5.11.5).
    ///
    /// @return Whether to draw the rule.
    public boolean isRuled() {
        return ruled;
    }

    public Kind getKind() {
        return kind;
    }

    /// The frame: the one a click descends to, for a match; the one the capture or instruction belongs to.
    public long getFrameId() {
        return frameId;
    }

    /// The capture's or instruction's index in its frame; -1 otherwise.
    public int getIndex() {
        return index;
    }

    /// The match's template, or null.
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

    /// A severity as a word the stylesheet paints by, or null below warning.
    ///
    /// A mark no longer carries one (design 18 §5.11.4, 2026-11-16): the rules that painted a
    /// match by its severity were removed on 2026-11-12 with the border they shared, and the
    /// gutter that replaces them keys markers by line rather than by mark, so the content pane
    /// emitted classes that styled nothing. A template's row still carries a flag and still
    /// paints by it, which is what this is for.
    ///
    /// @param rank The severity, as [stroom.shapeshifter.client.model.TraceModel#rank] ranks it.
    /// @return The word, or null.
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
