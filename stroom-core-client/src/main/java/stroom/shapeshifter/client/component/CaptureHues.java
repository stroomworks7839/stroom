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

import java.util.ArrayList;
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
/// variables. That also keeps the count small enough for the generator to stay honest: it steps
/// 47°, coprime with 360, so 360 distinct variables would be needed before a hue repeated, where
/// 360 *occurrences* is an ordinary sample.
///
/// A variable's colour still moves as the cursor walks, which is the same price already accepted
/// for the tint's reach, and for the same reason — the alternative is a colour that does not
/// mean anything in particular.
public final class CaptureHues {

    /// How many generations of *binding* matches the content pane tints: the cursor's own
    /// captures and one level of captures below them (design 18 §5.11.1).
    ///
    /// Generations are counted by the frames that bind rather than by frames, because a frame
    /// that binds nothing shows the reader nothing and so should not spend a generation. The cap
    /// is on how many lots of ranges are coloured at once, not on how deep the tree goes.
    public static final int GENERATIONS = 2;

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

    /// Whether a frame's captures are within the tint's reach beneath the cursor — the cursor's
    /// own frame always is.
    ///
    /// @param frameId The frame.
    /// @return Whether to tint what it bound.
    public boolean inReach(final long frameId) {
        return reach.contains(frameId);
    }

    /// The frames whose captures the content pane tints, outermost first: the cursor and the
    /// frames beneath it that are within [#GENERATIONS] binding generations of it.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame.
    /// @return The frame ids, in the order they were walked.
    public static List<Long> reached(final TraceModel trace, final long cursor) {
        final List<Long> frames = new ArrayList<>();
        walk(trace, cursor, 0, frames);
        return frames;
    }

    private static void walk(final TraceModel trace, final long frameId, final int generation,
                             final List<Long> frames) {
        final int generations = binds(trace, frameId)
                ? generation + 1
                : generation;
        if (generations > GENERATIONS) {
            return;
        }
        frames.add(frameId);
        for (final Frame child : trace.children(frameId)) {
            if (child.getContentOffset() >= 0) {
                walk(trace, child.getId(), generations, frames);
            }
        }
    }

    /// Whether a frame bound anything the pane can draw.
    ///
    /// A capture whose bytes are not a slice of the frame's content — one read from a variable, or
    /// a key-value pair ([stroom.shapeshifter.engine.Instrument#NOT_A_SLICE]) — has nowhere in the
    /// text to be tinted, so it is not a generation of ranges however many of them there are. It
    /// still gets a colour, because the variables pane lists it.
    private static boolean binds(final TraceModel trace, final long frameId) {
        for (final Capture capture : trace.captures(frameId)) {
            if (capture.getContentOffset() >= 0) {
                return true;
            }
        }
        return false;
    }

    /// Every capture of a frame takes the colour of the variable it bound, allocating a new one
    /// the first time that variable is seen. Unplaced captures are numbered too: they are rows in
    /// the variables pane even when they are nothing in the text.
    private void number(final TraceModel trace, final long frameId) {
        final List<Capture> captures = trace.captures(frameId);
        final Frame frame = trace.frame(frameId);
        // The document is not a frame object, so it has no template to name its variables by.
        final String owner = frame == null
                ? "frame-" + frameId
                : frame.getTemplateId();
        for (int i = 0; i < captures.size(); i++) {
            final String variable = owner + "/" + captures.get(i).getName();
            String hue = byVariable.get(variable);
            if (hue == null) {
                hue = Colours.hue(next++);
                byVariable.put(variable, hue);
            }
            hues.put(key(frameId, i), hue);
        }
    }

    private static String key(final long frameId, final int index) {
        return frameId + ":" + index;
    }
}
