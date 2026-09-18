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
import stroom.shapeshifter.shared.ShapeshifterTrace.Guard;
import stroom.shapeshifter.shared.ShapeshifterTrace.Instruction;
import stroom.shapeshifter.shared.ShapeshifterTrace.OutputSpan;
import stroom.shapeshifter.shared.ShapeshifterTrace.Timing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The navigator's reading of a trace: the tree, the path, and each frame's content by slicing or by its own bytes. */
class TraceModelTest {

    private static final String INPUT = "a=1\nb=22\n";

    /** Two rows over the input; a field inside the second; a frame from a variable under the first. */
    private static TraceModel model() {
        final List<Frame> frames = List.of(
                new Frame(1, 0, "row", "row", 1, 1, 0, 4, 0, 4, null),
                new Frame(2, 0, "row", "row", 2, 1, 4, 5, 4, 5, null),
                new Frame(3, 2, "field", "field", 1, 2, 6, 2, 2, 2, null),
                new Frame(4, 1, "any", "any", 1, 2, 0, 0, ShapeshifterTrace.NOT_A_SLICE, 0, "own"));
        return new TraceModel(new ShapeshifterTrace(true, INPUT, "<a/><b/>", frames,
                List.of(new Capture(3, "n", "22", "integer", 0, 2)),
                List.of(new OutputSpan(1, 0, 4, "BYTES"), new OutputSpan(2, 4, 4, "BYTES")),
                List.of(new Attempt(0, "row", true, 0, 0, 1), new Attempt(2, "field", false, 4, 0, 1)),
                List.of(new Guard(0, "row", true), new Guard(2, "field", false)),
                List.of(new Instruction(2, 0, 4, 4, "BYTES")),
                2,
                List.of(new Timing("row", 3, 2, 10)),
                List.of(),
                20));
    }

    @Test
    void theDocumentIsFrameZeroAndItsContentIsTheInput() {
        final TraceModel model = model();
        assertThat(model.has(TraceModel.ROOT)).isTrue();
        assertThat(model.frame(TraceModel.ROOT)).isNull();
        assertThat(model.content(TraceModel.ROOT)).isEqualTo(INPUT);
        assertThat(model.label(TraceModel.ROOT)).isEqualTo("document");
        assertThat(model.children(TraceModel.ROOT)).extracting(Frame::getId).containsExactly(1L, 2L);
    }

    @Test
    void contentSlicesTheParentAllTheWayUp() {
        final TraceModel model = model();
        assertThat(model.content(2)).isEqualTo("b=22\n");
        assertThat(model.content(3)).isEqualTo("22");
        assertThat(model.content(4)).isEqualTo("own");
    }

    @Test
    void pathRunsFromTheDocumentDown() {
        final TraceModel model = model();
        assertThat(model.path(3)).containsExactly(0L, 2L, 3L);
        assertThat(model.path(0)).containsExactly(0L);
        assertThat(model.parent(3)).isEqualTo(2);
        assertThat(model.label(3)).isEqualTo("field #1");
    }

    @Test
    void theIndexesAnswerByFrameAndByTemplate() {
        final TraceModel model = model();
        assertThat(model.matches("row")).hasSize(2);
        assertThat(model.captures(3)).extracting(Capture::getValue).containsExactly("22");
        assertThat(model.captures(1)).isEmpty();
        assertThat(model.output(2).getOffset()).isEqualTo(4);
        assertThat(model.attempts(2)).extracting(Attempt::isMatched).containsExactly(false);
        assertThat(model.timing("row").getMatched()).isEqualTo(2);
        assertThat(model.timing("field")).isNull();
        assertThat(model.has(9)).isFalse();
        assertThat(model.guard(2, "field")).isFalse();
        assertThat(model.guard(0, "row")).isTrue();
        assertThat(model.guard(0, "field")).isNull();
        assertThat(model.guardCounts("row")).containsExactly(1, 0);
        assertThat(model.guardCounts("field")).containsExactly(0, 1);
        assertThat(model.guardCounts("none")).containsExactly(0, 0);
        assertThat(model.instructions(2)).extracting(Instruction::getIndex).containsExactly(0);
    }

    @Test
    void sliceBeyondTheParentIsClampedNotThrown() {
        final TraceModel model = new TraceModel(new ShapeshifterTrace(true, "ab", "", List.of(
                new Frame(1, 0, "t", "t", 1, 1, 0, 2, 1, 50, null)),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, List.of(), List.of(), 0));
        assertThat(model.content(1)).isEqualTo("b");
    }
}
