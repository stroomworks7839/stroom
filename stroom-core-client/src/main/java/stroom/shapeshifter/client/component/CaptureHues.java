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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.model.TraceModel;
import stroom.shapeshifter.shared.ShapeshifterTrace.Capture;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// One colour per capture, allocated over everything a cursor can see (design 18 §5.11.2, ruled
/// 2026-11-16). The variables pane and the content pane both read it, so a run of text and a row
/// in the pane are the same colour because they are the same thing.
///
/// Hues used to be `hue(index + 1)` with the index counted *within the capture's own frame*,
/// which meant capture 0 was the same pink at every depth. That was survivable while only one
/// frame's captures were tinted; it is not, now that the tint reaches two generations and the
/// variables pane lists every frame on the path at once, because two such captures then sit on
/// screen together in the same colour. So they are numbered **cumulatively over the visible
/// set**, in the order they are read.
///
/// **A colour belongs to a variable, not to an occurrence of one.** The ruling says to number
/// down the visible list, and the literal reading of that is one colour per capture bound — but
/// a sample of four hundred lines binds `$line` four hundred times, and four hundred colours
/// down one pane is a rainbow that identifies nothing. The purpose of the tint is that an IP
/// address, a user name or a timestamp is recognisable *as* one, so every binding of the same
/// variable by the same template takes the same hue, and the numbering counts distinct
/// variables. That also keeps the count small enough for the generator to stay honest. Not
/// because a hue would otherwise *repeat* — that needs 360 of them either way — but because the
/// hues crowd long before they repeat: at twenty-four items the closest pair of generated hues
/// is 11° apart and at forty-eight it is 5°, which a reader cannot tell apart (`Colours.STEP`).
/// Counting variables rather than bindings is what keeps an ordinary sample well inside that.
///
/// A variable's colour still moves as the cursor walks, which is the same price already accepted
/// for the tint's reach, and for the same reason — the alternative is a colour that does not
/// mean anything in particular.
public final class CaptureHues {

    /// What the content pane can draw, for [Reach]: a frame draws when it bound a capture whose
    /// bytes are a slice of its content, and the descent ends where a frame's content is no
    /// slice of its parent's.
    ///
    /// A capture read from a variable, or a key-value pair — which the engine reports with no
    /// offset (`Instrument.NOT_A_SLICE`) — is a row in the variables pane and nothing in the
    /// text. A frame of nothing but those draws no ranges, so counting it as a generation would
    /// spend one on an invisible level *and* prune the frames below it from the tint. It still
    /// gets a colour, because the pane does list it.
    private static final Reach TINTED = new Reach() {
        @Override
        public boolean draws(final TraceModel trace, final long frameId) {
            for (final Capture capture : trace.captures(frameId)) {
                if (capture.getContentOffset() >= 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean descends(final TraceModel trace, final Frame child) {
            return child.getContentOffset() >= 0;
        }
    };

    private final Map<String, String> hues = new HashMap<>();
    private final Map<String, String> byVariable = new HashMap<>();
    private final Set<Long> reach = new HashSet<>();
    private int next = 1;

    private CaptureHues() {
    }

    /// The hues for every capture in scope at a cursor, in the order they are read: the cursor's
    /// own captures first, then outwards through its ancestors as the variables pane lists them,
    /// then down through the frames beneath it that the tint reaches.
    ///
    /// The pane's own rows are numbered first on purpose, so that its colours do not depend on
    /// how much of the tree happens to be tinted below.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame.
    /// @return The allocation, which both panes build for themselves and agree on because the
    /// walk is deterministic.
    public static CaptureHues inScope(final TraceModel trace, final long cursor) {
        final CaptureHues allocated = onPath(trace, cursor);
        for (final Long frameId : reached(trace, cursor)) {
            allocated.reach.add(frameId);
            allocated.number(trace, frameId);
        }
        return allocated;
    }

    /// The hues for the captures on the cursor's path alone, for a reader that shows only those.
    ///
    /// The colours are the same ones [#inScope] gives those captures, because the path is
    /// numbered first and the walk below the cursor cannot renumber what is already allocated.
    /// It saves that walk, which for a cursor at the document over a large sample is the whole
    /// tree.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame.
    /// @return The allocation, with nothing [#inReach].
    public static CaptureHues onPath(final TraceModel trace, final long cursor) {
        final CaptureHues allocated = new CaptureHues();
        final List<Long> path = trace.path(cursor);
        for (int i = path.size() - 1; i >= 0; i--) {
            allocated.number(trace, path.get(i));
        }
        return allocated;
    }

    /// The colour of a capture, or null where it is out of scope.
    ///
    /// @param frameId The frame that bound it.
    /// @param index Its index in that frame's captures.
    /// @return A CSS colour.
    public String hue(final long frameId, final int index) {
        return hues.get(key(frameId, index));
    }

    /// The colour of a template's variable, for the pattern workbench, whose groups take the
    /// colour of the variable they are bound to (ruled 2026-11-20), so that one capture is one
    /// colour wherever it is drawn.
    ///
    /// @param templateId The template that binds it.
    /// @param name The variable's name.
    /// @return A CSS colour, or null where no capture of it is in scope.
    public String variableHue(final String templateId, final String name) {
        return byVariable.get(variable(templateId, name));
    }

    /// How many colours are allocated: a hue numbered past this is no variable's in scope.
    ///
    /// @return The count.
    public int allocated() {
        return next - 1;
    }

    /// Which variable a capture bound, as a key: the template that bound it and the variable's
    /// name. Every capture of one key takes one colour, and pointing at one lights them all.
    ///
    /// @param trace The run.
    /// @param frameId The frame that bound it.
    /// @param index Its index in that frame's captures.
    /// @return The key.
    public static String variableOf(final TraceModel trace, final long frameId, final int index) {
        final Frame frame = trace.frame(frameId);
        // The document is not a frame object, so it has no template to name its variables by.
        return variable(frame == null
                ? "frame-" + frameId
                : frame.getTemplateId(), trace.captures(frameId).get(index).getName());
    }

    /// Whether a frame's captures are within the tint's reach beneath the cursor — the cursor's
    /// own frame always is.
    ///
    /// @param frameId The frame.
    /// @return Whether to tint what it bound.
    public boolean inReach(final long frameId) {
        return reach.contains(frameId);
    }

    /// The frames whose captures the content pane tints, outermost first: the cursor and the
    /// frames beneath it that are within [Reach#GENERATIONS] binding generations of it.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame.
    /// @return The frame ids, in the order they were walked.
    public static List<Long> reached(final TraceModel trace, final long cursor) {
        return Reach.from(trace, cursor, TINTED);
    }

    /// Every capture of a frame takes the colour of the variable it bound, allocating a new one
    /// the first time that variable is seen. Unplaced captures are numbered too: they are rows in
    /// the variables pane even when they are nothing in the text.
    private void number(final TraceModel trace, final long frameId) {
        final List<Capture> captures = trace.captures(frameId);
        for (int i = 0; i < captures.size(); i++) {
            final String variable = variableOf(trace, frameId, i);
            String hue = byVariable.get(variable);
            if (hue == null) {
                hue = Colours.hue(next++);
                byVariable.put(variable, hue);
            }
            hues.put(key(frameId, i), hue);
        }
    }

    private static String variable(final String owner, final String name) {
        return owner + "/" + name;
    }

    private static String key(final long frameId, final int index) {
        return frameId + ":" + index;
    }
}
