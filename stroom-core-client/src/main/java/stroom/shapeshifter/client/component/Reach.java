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
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;

import java.util.ArrayList;
import java.util.List;

/// How far from the cursor a pane marks, counted in generations (design 18 §5.12.2).
///
/// A pane marks **two generations from the cursor** — the cursor's own and one level below —
/// and nothing beyond, and the cap re-bases as the cursor walks so there are always two levels
/// of look-ahead wherever the reader stands. The alternative, counting from the document root,
/// leaves the reader with no marks at all past level two, which is precisely where the detail
/// being inspected lives.
///
/// A **generation is a frame that produces something the pane can draw**, not a frame. A frame
/// that draws nothing shows the reader nothing and must not spend one of the two, because
/// spending it also prunes the frames below. What counts as drawable is the pane's own
/// business, which is the whole of what this interface asks of its caller:
///
/// - the content pane draws a capture whose bytes are a slice of its frame's content, so a
///   frame of nothing but key-value pairs (`Instrument.NOT_A_SLICE`) draws nothing;
/// - the output pane draws an instruction that wrote characters, so a frame whose only card
///   wrote nothing, or wrote events rather than bytes, draws nothing.
///
/// One walk and not two: the first version of this counted generations by frames rather than by
/// frames that draw, which cost the pane one of its two lots of marks on any configuration with
/// a `source` template in it. That was found by review in one pane. Copied, it would have been
/// fixed in one pane.
public interface Reach {

    /// How many generations of *drawing* frames a pane marks: the cursor's own and one level
    /// below it (design 18 §5.12.2).
    int GENERATIONS = 2;

    /// Whether a frame produced anything the pane can draw, and so spends a generation.
    ///
    /// @param trace The run.
    /// @param frameId The frame.
    /// @return Whether it draws.
    boolean draws(TraceModel trace, long frameId);

    /// Whether a child frame has a place in the text the pane shows, and so can be descended
    /// into. A frame whose content is no slice of its parent's, or that wrote nothing the pane
    /// can place, ends the descent: nothing under it has anywhere to be drawn either.
    ///
    /// @param trace The run.
    /// @param child The child frame.
    /// @return Whether to descend into it.
    boolean descends(TraceModel trace, Frame child);

    /// The frames a pane marks, outermost first: the cursor, and the frames beneath it within
    /// [#GENERATIONS] drawing generations of it.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame.
    /// @param reach What the pane can draw.
    /// @return The frame ids, in the order they were walked.
    static List<Long> from(final TraceModel trace, final long cursor, final Reach reach) {
        final List<Long> frames = new ArrayList<>();
        walk(trace, cursor, 0, reach, frames);
        return frames;
    }

    private static void walk(final TraceModel trace, final long frameId, final int generation,
                             final Reach reach, final List<Long> frames) {
        final int generations = reach.draws(trace, frameId)
                ? generation + 1
                : generation;
        if (generations > GENERATIONS) {
            return;
        }
        frames.add(frameId);
        for (final Frame child : trace.children(frameId)) {
            if (reach.descends(trace, child)) {
                walk(trace, child.getId(), generations, reach, frames);
            }
        }
    }
}
