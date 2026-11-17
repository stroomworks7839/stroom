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
import stroom.shapeshifter.shared.ShapeshifterTrace.Instruction;
import stroom.shapeshifter.shared.ShapeshifterTrace.OutputSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// One colour per body card, allocated over everything the output pane marks (design 18 §5.13.4,
/// ruled 2026-11-17). An instruction is to the output pane what a capture is to the content pane
/// ([CaptureHues]), and the two allocators read one walk ([Reach]) — but they are not the same
/// allocator, because an instruction's colour has a visible external anchor where a capture's
/// has none.
///
/// **The cursor's own cards keep `hue(index)`.** That is what the body cards in the template
/// panel are painted with ([BodyController#cardHue]), and the agreement between a run of output
/// and the card beside it that wrote it is the whole point of the colour. So they are allocated
/// first and at their own positions, which is [CaptureHues]' rule — number what is visible
/// elsewhere first — applied to a different anchor.
///
/// **The generation below continues the count.** A cursor template with three cards takes
/// `hue(0..2)` and the children's cards take `hue(3)`, `hue(4)` and so on. They cannot also be
/// `hue(index)`: card 0 of the cursor and card 0 of a child would then be one colour, which is
/// the defect that made [CaptureHues] necessary in the first place.
///
/// The cost, accepted: a deeper card's colour does not match the cards the reader would see on
/// descending into it. It cannot — those are painted by position in their own body, and their
/// positions collide with the cursor's. It is the price §5.11.1 already accepted for the capture
/// tint, paid for the same thing: no two colours on screen meaning different cards.
///
/// **A colour belongs to a card, not to an execution of one** (design 18 §5.12.6). Four hundred
/// matches of one template each run its card 0, and those are one colour, not four hundred —
/// so the key is the card's identity, `templateId + "#" + index`.
public final class InstructionHues {

    /// What the output pane can draw, for [Reach]: a frame draws when one of its cards wrote
    /// characters, and the descent ends where a frame wrote none.
    ///
    /// An instruction counted in events has no character span to tint — the pane says so in a
    /// note when it meets one — and an instruction of zero length wrote nothing. Neither is a
    /// generation of ranges, which is the exact counterpart of the content pane's test for a
    /// capture whose bytes are no slice, and has the same failure mode if got wrong: a
    /// generation spent on an invisible level, and the frames below it pruned.
    ///
    /// The descent ends at a frame that wrote nothing because spans nest — a parent's covers its
    /// children's — so nothing beneath a frame of zero length wrote anything either.
    ///
    /// That nesting also makes the two tests below agree with each other here, where in the
    /// content pane they do not: a frame's span is the sum of its cards', so a frame that wrote
    /// characters has a card that did and vice versa. The content pane's `source` template,
    /// which binds nothing and yet holds every frame that binds, has no counterpart in output.
    /// Both are written out anyway, because the equivalence is a fact about the engine's spans
    /// rather than about what this pane means.
    private static final Reach WRITTEN = new Reach() {
        @Override
        public boolean draws(final TraceModel trace, final long frameId) {
            for (final Instruction instruction : trace.instructions(frameId)) {
                if (wrote(instruction.getUnit(), instruction.getLength())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean descends(final TraceModel trace, final Frame child) {
            final OutputSpan span = trace.output(child.getId());
            return span != null && wrote(span.getUnit(), span.getLength());
        }
    };

    private final Map<String, String> hues = new HashMap<>();
    private final Map<String, String> byCard = new HashMap<>();
    private final List<Long> frames = new ArrayList<>();
    private int next;

    private InstructionHues() {
    }

    /// The hues for every instruction the pane marks at a cursor: the cursor's own cards first,
    /// at the hue their cards wear in the template panel, then the cards of the frames beneath
    /// it that are within reach.
    ///
    /// @param trace The run.
    /// @param cursor The selected frame.
    /// @return The allocation.
    public static InstructionHues inScope(final TraceModel trace, final long cursor) {
        final InstructionHues allocated = new InstructionHues();
        allocated.own(trace, cursor);
        allocated.frames.addAll(Reach.from(trace, cursor, WRITTEN));
        for (final Long frameId : allocated.frames) {
            allocated.number(trace, frameId);
        }
        return allocated;
    }

    /// The colour of an instruction's output, or null where it is out of reach.
    ///
    /// @param frameId The frame whose body ran it.
    /// @param index Its index in that body.
    /// @return A CSS colour.
    public String hue(final long frameId, final int index) {
        return hues.get(key(frameId, index));
    }

    /// The frames whose output the pane marks, outermost first: the cursor and the frames
    /// beneath it within reach.
    ///
    /// The walk this came from is the one the allocation was built on, handed back rather than
    /// repeated. The content pane recurses a second time because it has to accumulate a base
    /// offset down the tree; output offsets are absolute, so here there is nothing to accumulate
    /// and a second walk would be the whole frame tree again on every refresh.
    ///
    /// @return The frame ids, in the order they were walked.
    public List<Long> frames() {
        return frames;
    }

    /// Whether an instruction or a frame put characters in the output: an EVENTS span counts
    /// events rather than characters and has no place in the text, and a span of nothing has
    /// nothing to mark.
    private static boolean wrote(final String unit, final long length) {
        return !"EVENTS".equals(unit) && length > 0;
    }

    /// The cursor's own cards, at the positions the template panel paints them by, so that a run
    /// of output and the card beside it that wrote it are the same colour.
    private void own(final TraceModel trace, final long cursor) {
        final Frame frame = trace.frame(cursor);
        for (final Instruction instruction : trace.instructions(cursor)) {
            final String hue = BodyController.cardHue(instruction.getIndex());
            hues.put(key(cursor, instruction.getIndex()), hue);
            if (frame != null) {
                byCard.put(card(frame.getTemplateId(), instruction.getIndex()), hue);
            }
            next = Math.max(next, instruction.getIndex() + 1);
        }
    }

    /// Every card of a frame takes the colour of the card it is, allocating a new one the first
    /// time that card is seen. The cursor's own are already allocated and keep what they have.
    private void number(final TraceModel trace, final long frameId) {
        final Frame frame = trace.frame(frameId);
        // The document is not a frame object, so it has no template to name its cards by.
        final String owner = frame == null
                ? "frame-" + frameId
                : frame.getTemplateId();
        for (final Instruction instruction : trace.instructions(frameId)) {
            if (hues.containsKey(key(frameId, instruction.getIndex()))) {
                continue;
            }
            final String identity = card(owner, instruction.getIndex());
            String hue = byCard.get(identity);
            if (hue == null) {
                hue = Colours.hue(next++);
                byCard.put(identity, hue);
            }
            hues.put(key(frameId, instruction.getIndex()), hue);
        }
    }

    private static String card(final String templateId, final int index) {
        return templateId + "#" + index;
    }

    private static String key(final long frameId, final int index) {
        return frameId + ":" + index;
    }
}
