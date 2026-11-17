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
import stroom.shapeshifter.shared.ShapeshifterTrace;
import stroom.shapeshifter.shared.ShapeshifterTrace.Frame;
import stroom.shapeshifter.shared.ShapeshifterTrace.Instruction;
import stroom.shapeshifter.shared.ShapeshifterTrace.OutputSpan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// One colour per body card over everything the output pane marks, anchored on the cards the
/// template panel is showing beside it.
class InstructionHuesTest {

    // The strict_kv fixture's output, as the workbench's trace carries it: a source frame whose
    // three cards wrap two pairs, each pair's five cards inside the second of those, and a
    // junk_line between them whose one card wrote nothing.
    private static final String OUTPUT = "<records>\n"
                                         + " <pair key=\"host\">web01</pair>\n"
                                         + " <pair key=\"level\">warn</pair>\n"
                                         + "</records>\n";

    private static final String SOURCE = "tpl-source";
    private static final String PAIR = "tpl-pair";
    private static final String JUNK = "tpl-junk";

    @Test
    void theCursorsOwnCardsKeepTheHueTheirCardsWear() {
        final InstructionHues hues = InstructionHues.inScope(trace(), 1L);

        // The body panel beside the pane paints its cards by position, and a run of output
        // agreeing with the card that wrote it is the whole point of the colour — so unlike a
        // capture, whose colour is allocated, these are pinned.
        assertThat(hues.hue(1, 0)).isEqualTo(BodyController.cardHue(0));
        assertThat(hues.hue(1, 1)).isEqualTo(BodyController.cardHue(1));
        assertThat(hues.hue(1, 2)).isEqualTo(BodyController.cardHue(2));
    }

    @Test
    void theGenerationBelowContinuesTheCountRatherThanCollidingWithIt() {
        final InstructionHues hues = InstructionHues.inScope(trace(), 1L);

        // Card 0 of the cursor and card 0 of a pair beneath it are different cards and must not
        // be one colour — the defect that made CaptureHues necessary, in the other pane.
        assertThat(hues.hue(2, 0)).isNotEqualTo(hues.hue(1, 0));
        // and the pair's cards do not collide with each other, or with the source's three
        final Set<String> seen = new HashSet<>(coloursOf(hues));
        assertThat(seen).hasSize(coloursOf(hues).size());
    }

    @Test
    void oneCardKeepsOneColourHoweverOftenItIsRun() {
        final InstructionHues hues = InstructionHues.inScope(trace(), 1L);

        // Both pairs are the same template running the same cards. A colour per execution would
        // make a four-hundred-line sample four hundred colours, which identifies nothing.
        assertThat(hues.hue(2, 0)).isEqualTo(hues.hue(4, 0));
        assertThat(hues.hue(2, 3)).isEqualTo(hues.hue(4, 3));
        // and two cards of the same body are still told apart
        assertThat(hues.hue(2, 0)).isNotEqualTo(hues.hue(2, 3));
    }

    @Test
    void aFrameThatWroteNothingIsNotMarkedAtAll() {
        final TraceModel trace = trace();

        // junk_line's only card is an emit-error, so it wrote no characters. It is not reached,
        // and its card has no colour: an empty span would be a mark the reader can neither see
        // nor point at.
        //
        // This is where the output pane and the content pane part company. There, a frame that
        // binds nothing can still contain frames that bind — a `source` template that takes the
        // whole input and dispatches is exactly that — so not spending a generation on it is
        // what keeps the frames below it in reach. Here it cannot happen: output spans nest, so
        // a frame whose own span is empty contains nothing that wrote either, and "wrote
        // nothing" and "has nothing below worth reaching" are the same fact.
        assertThat(InstructionHues.inScope(trace, 1L).frames()).doesNotContain(3L);
        assertThat(InstructionHues.inScope(trace, 1L).hue(3, 0)).isNull();
    }

    @Test
    void aCardCountedInEventsIsNotAGeneration() {
        // An XML sink counts events, not characters, so such a card has no span in the text to
        // mark — the same case as a capture whose bytes are no slice of its frame's content.
        final List<Frame> frames = Arrays.asList(
                frame(1, 0, SOURCE, "source"),
                frame(2, 1, PAIR, "pair"),
                frame(3, 2, JUNK, "inner"));
        final List<OutputSpan> outputs = Arrays.asList(
                output(1, 0, 10, "BYTES"),
                output(2, 0, 10, "EVENTS"),
                output(3, 0, 10, "BYTES"));
        final List<Instruction> instructions = Arrays.asList(
                instruction(1, 0, 0, 10, "BYTES"),
                instruction(2, 0, 0, 10, "EVENTS"),
                instruction(3, 0, 0, 10, "BYTES"));

        // The event-counted frame ends the descent, because its span cannot be placed in the
        // text and so neither can anything inside it.
        assertThat(InstructionHues.inScope(model(frames, outputs, instructions), 1L).frames())
                .contains(1L)
                .doesNotContain(3L);
    }

    @Test
    void theTintReachesTwoWritingGenerationsAndStops() {
        final TraceModel trace = trace();

        // From the source: its own cards are the first generation and the pairs' the second.
        // Frame 5, under the second pair, is the third and is out.
        assertThat(InstructionHues.inScope(trace, 1L).frames())
                .contains(1L, 2L, 4L)
                .doesNotContain(5L);
    }

    @Test
    void theCapRebasesOnTheCursor() {
        final TraceModel trace = trace();

        // What is out of reach from the source is in reach from a pair, so there are always two
        // levels of look-ahead wherever the cursor stands.
        assertThat(InstructionHues.inScope(trace, 4L).frames()).contains(4L, 5L);
        assertThat(InstructionHues.inScope(trace, 4L).hue(5, 0)).isNotNull();
    }

    @Test
    void aCardOutOfReachHasNoColour() {
        final InstructionHues hues = InstructionHues.inScope(trace(), 1L);

        assertThat(hues.hue(5, 0)).isNull();
        assertThat(hues.frames()).doesNotContain(5L);
    }

    private static List<String> coloursOf(final InstructionHues hues) {
        final List<String> colours = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            colours.add(hues.hue(1, i));
        }
        for (int i = 0; i < 5; i++) {
            colours.add(hues.hue(2, i));
        }
        return colours;
    }

    private static TraceModel trace() {
        final List<Frame> frames = Arrays.asList(
                frame(1, 0, SOURCE, "source"),
                frame(2, 1, PAIR, "pair"),
                frame(3, 1, JUNK, "junk_line"),
                frame(4, 1, PAIR, "pair"),
                // under the second pair, so three writing generations from the source
                frame(5, 4, SOURCE, "nested"));
        final List<OutputSpan> outputs = Arrays.asList(
                output(1, 0, 83, "BYTES"),
                output(2, 10, 31, "BYTES"),
                output(3, 41, 0, "BYTES"),
                output(4, 41, 31, "BYTES"),
                output(5, 60, 4, "BYTES"));
        final List<Instruction> instructions = Arrays.asList(
                instruction(1, 0, 0, 10, "BYTES"),
                instruction(1, 1, 10, 62, "BYTES"),
                instruction(1, 2, 72, 11, "BYTES"),
                instruction(2, 0, 10, 12, "BYTES"),
                instruction(2, 1, 22, 4, "BYTES"),
                instruction(2, 2, 26, 2, "BYTES"),
                instruction(2, 3, 28, 5, "BYTES"),
                instruction(2, 4, 33, 8, "BYTES"),
                // the card that wrote nothing
                instruction(3, 0, 41, 0, "BYTES"),
                instruction(4, 0, 41, 12, "BYTES"),
                instruction(4, 1, 53, 5, "BYTES"),
                instruction(4, 2, 58, 2, "BYTES"),
                instruction(4, 3, 60, 4, "BYTES"),
                instruction(4, 4, 64, 8, "BYTES"),
                instruction(5, 0, 60, 4, "BYTES"));
        return model(frames, outputs, instructions);
    }

    private static TraceModel model(final List<Frame> frames, final List<OutputSpan> outputs,
                                    final List<Instruction> instructions) {
        return new TraceModel(new ShapeshifterTrace(true, true, "", OUTPUT, frames,
                Collections.emptyList(), Collections.emptyList(), outputs, Collections.emptyList(),
                Collections.emptyList(), instructions, 7L,
                Collections.emptyList(), Collections.emptyList(), 412_000L));
    }

    private static Frame frame(final long id, final long parentId, final String templateId,
                               final String templateName) {
        return new Frame(id, parentId, templateId, templateName, 0, 0, 0, 0, 0, 0, null);
    }

    private static OutputSpan output(final long frameId, final long offset, final long length,
                                     final String unit) {
        return new OutputSpan(frameId, offset, length, unit);
    }

    private static Instruction instruction(final long frameId, final int index, final long offset,
                                           final long length, final String unit) {
        return new Instruction(frameId, index, offset, length, unit);
    }
}
